package foo

import scalatags.Text.all.*

/** This is the Foo object */
object Foo {

  /** this is a value */
  val value = h1("hello")

  def main(args: Array[String]): Unit = {
    println("Foo.value: " + Foo.value)

    Console.flush()
  }
}
