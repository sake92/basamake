package ba.sake.basamake.navigation

import java.io.InputStream
import scala.meta.*
import scala.meta.dialects.{Scala3Future, Scala213}
import com.typesafe.scalalogging.StrictLogging
import scala.util.control.NonFatal

class ScalaDefinitionsExtractor(symbolTable: SymbolTable) extends StrictLogging {

  def extract(name: String, is: InputStream): Unit = try extractInternal(is) catch {
    case NonFatal(e) =>
      logger.warn(s"Failed to parse Scala source ${name}: ${e.getMessage}")
  }

  private def extractInternal(content: InputStream): Unit = {
    extractFromSource(content) match
      case Some(src) =>
        extractFromStats(src.stats)
      case None =>
  }

  private def extractFromSource(content: InputStream): Option[Source] = {
    val parsed3 = { given Dialect = Scala3Future; content.parse[Source] }
    parsed3 match {
      case Parsed.Success(source) => Some(source)
      case Parsed.Error(_, _, _) =>
        val parsed213 = { given Dialect = Scala213; content.parse[Source] }
        parsed213 match
          case Parsed.Success(source) => Some(source)
          case Parsed.Error(_, _, _) => None
    }
  }

  private def extractFromStats(stats: List[Stat]): Unit =
    stats.foreach{
      case  pkg: Pkg =>
        // TODO fill this in

    } 

}
