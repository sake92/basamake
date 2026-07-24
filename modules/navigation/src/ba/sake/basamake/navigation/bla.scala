import scala.meta.*
import scala.meta.dialects.Scala3Future

@main def verify: Unit =
  val code = """package pkg
    |class Foo(x: Int) {
    |  def this() = this(0)
    |  def bar(s: String): Unit = ()
    |  val x = 1
    |  def bar(i: Int): Unit = ()
    |  def this(y: Int, z: Int) = this(0)
    |}
    |""".stripMargin
  
  given Dialect = Scala3Future
  code.parse[Source] match
    case Parsed.Success(source) =>
      source.stats.foreach {
        case p: Pkg =>
          println(s"Package: ${p.ref}")
          p.stats.foreach {
            case c: Defn.Class =>
              println(s"  Class: ${c.name} at ${c.pos.startLine}:${c.pos.startColumn}")
              // Check methods in order
              val methods = c.templ.stats.collect { case d: Defn.Def => d }
              println(s"  Methods in order:")
              methods.foreach(m => println(s"    ${m} at ${m.pos.startLine}:${m.pos.startColumn}"))
              // Check constructors in order
              println(s"  Primary ctor at: ${c.name.pos.startLine}:${c.name.pos.startColumn}")
              val seconds = c.templ.stats.collect { case ct: Ctor.Secondary => ct }
              seconds.foreach(ct => println(s"    Secondary ${ct} at: ${ct.name.pos.startLine}:${ct.name.pos.startColumn}"))
            case _ =>
          }
        case _ =>
      }
    case Parsed.Error(_, msg, _) => println(s"Parse error: $msg")