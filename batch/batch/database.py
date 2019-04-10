import json
import uuid
import asyncio
import pymysql
import aiomysql
from asyncinit import asyncinit


def run_synchronous(coro):
    loop = asyncio.get_event_loop()
    return loop.run_until_complete(coro)


@asyncinit
class Database:
    @staticmethod
    def create_synchronous(config_file):
        db = run_synchronous(Database(config_file))
        return db

    async def __init__(self, config_file):
        with open(config_file, 'r') as f:
            config = json.loads(f.read().strip())

        self.host = config['host']
        self.port = config['port']
        self.user = config['user']
        self.db = config['db']
        self.password = config['password']
        self.charset = 'utf8'

        self.pool = await aiomysql.create_pool(host=self.host,
                                               port=self.port,
                                               db=self.db,
                                               user=self.user,
                                               password=self.password,
                                               charset=self.charset,
                                               cursorclass=aiomysql.cursors.DictCursor,
                                               autocommit=True)

    async def has_table(self, name):
        async with self.pool.acquire() as conn:
            async with conn.cursor() as cursor:
                sql = f"SELECT * FROM INFORMATION_SCHEMA.tables " \
                    f"WHERE TABLE_SCHEMA = %s AND TABLE_NAME = %s"
                await cursor.execute(sql, (self.db, name))
                result = cursor.fetchone()
        return result.result() is not None

    def has_table_sync(self, name):
        return run_synchronous(self.has_table(name))

    async def drop_table(self, *names):
        async with self.pool.acquire() as conn:
            async with conn.cursor() as cursor:
                await cursor.execute("DROP TABLE IF EXISTS {}".format(",".join([f'`{name}`' for name in names])))

    def drop_table_sync(self, *names):
        return run_synchronous(self.drop_table(*names))

    async def create_table(self, name, schema, keys, can_exist=True):
        assert all([k in schema for k in keys])

        async with self.pool.acquire() as conn:
            async with conn.cursor() as cursor:
                schema = ", ".join([f'`{n.replace("`", "``")}` {t}' for n, t in schema.items()])
                key_names = ", ".join([f'`{name.replace("`", "``")}`' for name in keys])
                keys = f", PRIMARY KEY( {key_names} )" if keys else ''
                exists = 'IF NOT EXISTS' if can_exist else ''
                sql = f"CREATE TABLE {exists} `{name}` ( {schema} {keys})"
                await cursor.execute(sql)

    def create_table_sync(self, name, schema, keys):
        return run_synchronous(self.create_table(name, schema, keys))

    async def create_temporary_table(self, root_name, schema, keys):
        for i in range(5):
            try:
                suffix = uuid.uuid4().hex[:8]
                name = f'{root_name}-{suffix}'
                return await Table(self, name, schema, keys, can_exist=False)
            except pymysql.err.InternalError:
                pass
        raise Exception("Too many attempts to get temp table.")

    def create_temporary_table_sync(self, root_name, schema, keys):
        return run_synchronous(self.create_temporary_table(root_name, schema, keys))


def make_where_statement(items):
    template = []
    values = []
    for k, v in items.items():
        if isinstance(v, list):
            if len(v) == 0:
                template.append("FALSE")
            else:
                template.append(f'`{k.replace("`", "``")}` IN %s')
                values.append(v)
        else:
            template.append(f'`{k.replace("`", "``")}` = %s')
            values.append(v)

    template = " AND ".join(template)
    return template, values


@asyncinit
class Table:  # pylint: disable=R0903
    async def __init__(self, db, name, schema, keys, can_exist=True):
        self.name = name
        self._db = db
        await self._db.create_table(name, schema, keys, can_exist)

    async def new_record(self, items):
        names = ", ".join([f'`{name.replace("`", "``")}`' for name in items.keys()])
        values_template = ", ".join(["%s" for _ in items.values()])
        async with self._db.pool.acquire() as conn:
            async with conn.cursor() as cursor:
                sql = f"INSERT INTO `{self.name}` ({names}) VALUES ({values_template})"
                await cursor.execute(sql, tuple(items.values()))
                id = cursor.lastrowid  # This returns 0 unless an autoincrement field is in the table
        return id

    async def update_record(self, where_items, set_items):
        async with self._db.pool.acquire() as conn:
            async with conn.cursor() as cursor:
                if len(set_items) != 0:
                    where_template, where_values = make_where_statement(where_items)
                    set_template = ", ".join([f'`{k.replace("`", "``")}` = %s' for k, v in set_items.items()])
                    set_values = set_items.values()
                    sql = f"UPDATE `{self.name}` SET {set_template} WHERE {where_template}"
                    await cursor.execute(sql, (*set_values, *where_values))

    async def get_record(self, where_items, select_fields=None):
        assert select_fields is None or len(select_fields) != 0
        async with self._db.pool.acquire() as conn:
            async with conn.cursor() as cursor:
                where_template, where_values = make_where_statement(where_items)
                select_fields = ",".join(select_fields) if select_fields is not None else "*"
                sql = f"SELECT {select_fields} FROM `{self.name}` WHERE {where_template}"
                await cursor.execute(sql, where_values)
                result = await cursor.fetchall()
        return result

    async def has_record(self, where_items):
        async with self._db.pool.acquire() as conn:
            async with conn.cursor() as cursor:
                where_template, where_values = make_where_statement(where_items)
                sql = f"SELECT COUNT(1) FROM `{self.name}` WHERE {where_template}"
                count = await cursor.execute(sql, tuple(where_values))
        return count >= 1

    async def delete_record(self, where_items):
        async with self._db.pool.acquire() as conn:
            async with conn.cursor() as cursor:
                where_template, where_values = make_where_statement(where_items)
                sql = f"DELETE FROM `{self.name}` WHERE {where_template}"
                await cursor.execute(sql, where_values)