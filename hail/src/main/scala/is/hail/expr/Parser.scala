package is.hail.expr

import is.hail.utils.StringEscapeUtils._
import is.hail.utils._
import is.hail.variant._

import scala.util.parsing.combinator.JavaTokenParsers
import scala.util.parsing.input.Position

class RichParser[T](parser: Parser.Parser[T]) {
  def parse(input: String): T = {
    Parser.parseAll(parser, input) match {
      case Parser.Success(result, _) => result
      case Parser.NoSuccess(msg, next) => ParserUtils.error(next.pos, msg)
    }
  }

  def parseOpt(input: String): Option[T] = {
    Parser.parseAll(parser, input) match {
      case Parser.Success(result, _) => Some(result)
      case Parser.NoSuccess(msg, next) => None
    }
  }
}

object ParserUtils {
  def error(pos: Position, msg: String): Nothing = {
    val lineContents = pos.longString.split("\n").head
    val prefix = s"<input>:${ pos.line }:"
    fatal(
      s"""$msg
         |$prefix$lineContents
         |${ " " * prefix.length }${
        lineContents.take(pos.column - 1).map { c => if (c == '\t') c else ' ' }
      }^""".stripMargin)
  }

  def error(pos: Position, msg: String, tr: Truncatable): Nothing = {
    val lineContents = pos.longString.split("\n").head
    val prefix = s"<input>:${ pos.line }:"
    fatal(
      s"""$msg
         |$prefix$lineContents
         |${ " " * prefix.length }${
        lineContents.take(pos.column - 1).map { c => if (c == '\t') c else ' ' }
      }^""".stripMargin, tr)
  }
}

object Parser extends JavaTokenParsers {
  def parse[T](parser: Parser[T], code: String): T = {
    parseAll(parser, code) match {
      case Success(result, _) => result
      case NoSuccess(msg, next) => ParserUtils.error(next.pos, msg)
    }
  }

  def parseAnnotationRoot(code: String, root: String): List[String] = {
    val path = parseAll(annotationIdentifier, code) match {
      case Success(result, _) => result.asInstanceOf[List[String]]
      case NoSuccess(msg, _) => fatal(msg)
    }

    if (path.isEmpty)
      fatal(s"expected an annotation path starting in `$root', but got an empty path")
    else if (path.head != root)
      fatal(s"expected an annotation path starting in `$root', but got a path starting in '${ path.head }'")
    else
      path.tail
  }

  def parseLocusInterval(input: String, rg: RGBase): Interval = {
    parseAll[Interval](locusInterval(rg), input) match {
      case Success(r, _) => r
      case NoSuccess(msg, next) => fatal(s"invalid interval expression: `$input': $msg")
    }
  }

  def parseCall(input: String): Call = {
    parseAll[Call](call, input) match {
      case Success(r, _) => r
      case NoSuccess(msg, next) => fatal(s"invalid call expression: `$input': $msg")
    }
  }

  def oneOfLiteral(s: String*): Parser[String] = oneOfLiteral(s.toArray)

  def oneOfLiteral(a: Array[String]): Parser[String] = new Parser[String] {
    var hasEnd: Boolean = false

    val m = a.flatMap { s =>
      val l = s.length
      if (l == 0) {
        hasEnd = true
        None
      }
      else if (l == 1) {
        Some((s.charAt(0), ""))
      }
      else
        Some((s.charAt(0), s.substring(1)))
    }.groupBy(_._1).mapValues { v => oneOfLiteral(v.map(_._2)) }

    def apply(in: Input): ParseResult[String] = {
      m.get(in.first) match {
        case Some(p) =>
          p(in.rest) match {
            case s: Success[_] =>
              Success(in.first.toString + s.result, in.drop(s.result.length + 1))
            case _ => Failure("", in)
          }
        case None =>
          if (hasEnd)
            Success("", in)
          else
            Failure("", in)
      }
    }
  }

  def annotationIdentifier: Parser[List[String]] =
    rep1sep(identifier, ".") ^^ {
      _.toList
    }

  def identifier = backtickLiteral | ident

  def quotedLiteral(delim: Char, what: String): Parser[String] =
    new Parser[String] {
      def apply(in: Input): ParseResult[String] = {
        var r = in

        val source = in.source
        val offset = in.offset
        val start = handleWhiteSpace(source, offset)
        r = r.drop(start - offset)

        if (r.atEnd || r.first != delim)
          return Failure(s"expected $what", r)
        r = r.rest

        val sb = new StringBuilder()

        val escapeChars = "\\bfnrtu'\"`".toSet
        var continue = true
        while (continue) {
          if (r.atEnd)
            return Failure(s"unterminated $what", r)
          val c = r.first
          r = r.rest
          if (c == delim)
            continue = false
          else {
            sb += c
            if (c == '\\') {
              if (r.atEnd)
                return Failure(s"unterminated $what", r)
              val d = r.first
              if (!escapeChars.contains(d))
                return Failure(s"invalid escape character in $what", r)
              sb += d
              r = r.rest
            }
          }
        }
        Success(unescapeString(sb.result()), r)
      }
    }

  def backtickLiteral: Parser[String] = quotedLiteral('`', "backtick identifier")

  override def stringLiteral: Parser[String] =
    quotedLiteral('"', "string literal") | quotedLiteral('\'', "string literal")

  def call: Parser[Call] = {
    wholeNumber ~ "/" ~ rep1sep(wholeNumber, "/") ^^ { case a0 ~ _ ~ arest =>
      CallN(coerceInt(a0) +: arest.map(coerceInt).toArray, phased = false)
    } |
      wholeNumber ~ "|" ~ rep1sep(wholeNumber, "|") ^^ { case a0 ~ _ ~ arest =>
        CallN(coerceInt(a0) +: arest.map(coerceInt).toArray, phased = true)
      } |
      wholeNumber ^^ { a => Call1(coerceInt(a), phased = false) } |
      "|" ~ wholeNumber ^^ { case _ ~ a => Call1(coerceInt(a), phased = true) } |
      "-" ^^ { _ => Call0(phased = false) } |
      "|-" ^^ { _ => Call0(phased = true) }
  }

  def intervalWithEndpoints[T](bounds: Parser[(T, T, Boolean, Boolean)]): Parser[Interval] = {
    val start = ("[" ^^^ true) | ("(" ^^^ false)
    val end = ("]" ^^^ true) | (")" ^^^ false)

    start ~ bounds ~ end ^^ { case istart ~ int ~ iend => Interval(int._1, int._2, istart, iend) } |
      bounds ^^ { int => Interval(int._1, int._2, int._3, int._4) }
  }

  def locusInterval(rgBase: RGBase): Parser[Interval] = {
    val rg = rgBase.asInstanceOf[ReferenceGenome]
    val contig = rg.contigParser

    val valueParser =
      locusUnchecked(rg) ~ "-" ~ rg.contigParser ~ ":" ~ pos ^^ { case l1 ~ _ ~ c2 ~ _ ~ p2 => p2 match {
        case Some(p) => (l1, Locus(c2, p), true, false)
        case None => (l1, Locus(c2, rg.contigLength(c2)), true, true)
      }
      } |
        locusUnchecked(rg) ~ "-" ~ pos ^^ { case l1 ~ _ ~ p2 => p2 match {
          case Some(p) => (l1, l1.copy(position = p), true, false)
          case None => (l1, l1.copy(position = rg.contigLength(l1.contig)), true, true)
        }
        } |
        contig ~ "-" ~ contig ^^ { case c1 ~ _ ~ c2 => (Locus(c1, 1), Locus(c2, rg.contigLength(c2)), true, true) } |
        contig ^^ { c => (Locus(c, 1), Locus(c, rg.contigLength(c)), true, true) }

    intervalWithEndpoints(valueParser) ^^ { i =>
      val normInterval = rg.normalizeLocusInterval(i)
      rg.checkLocusInterval(normInterval)
      normInterval
    }
  }

  def locusUnchecked(rg: RGBase): Parser[Locus] =
    (rg.contigParser ~ ":" ~ pos) ^^ { case c ~ _ ~ p => Locus(c, p.getOrElse(rg.contigLength(c))) }

  def locus(rg: RGBase): Parser[Locus] =
    (rg.contigParser ~ ":" ~ pos) ^^ { case c ~ _ ~ p => Locus(c, p.getOrElse(rg.contigLength(c)), rg) }

  def coerceInt(s: String): Int = try {
    s.toInt
  } catch {
    case e: java.lang.NumberFormatException => Int.MaxValue
  }

  def exp10(i: Int): Int = {
    var mult = 1
    var j = 0
    while (j < i) {
      mult *= 10
      j += 1
    }
    mult
  }

  def pos: Parser[Option[Int]] = {
    "[sS][Tt][Aa][Rr][Tt]".r ^^ { _ => Some(1) } |
      "[Ee][Nn][Dd]".r ^^ { _ => None } |
      "\\d+".r <~ "[Kk]".r ^^ { i => Some(coerceInt(i) * 1000) } |
      "\\d+".r <~ "[Mm]".r ^^ { i => Some(coerceInt(i) * 1000000) } |
      "\\d+".r ~ "." ~ "\\d{1,3}".r ~ "[Kk]".r ^^ { case lft ~ _ ~ rt ~ _ => Some(coerceInt(lft + rt) * exp10(3 - rt.length)) } |
      "\\d+".r ~ "." ~ "\\d{1,6}".r ~ "[Mm]".r ^^ { case lft ~ _ ~ rt ~ _ => Some(coerceInt(lft + rt) * exp10(6 - rt.length)) } |
      "\\d+".r ^^ { i => Some(coerceInt(i)) }
  }
}
