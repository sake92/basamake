package foo

import scalatags.Text.all.*

/**
 * This is the Foo object.
 */
object Foo {

  /** this is a value */
  val value = h1("hello")

  def main(args: Array[String]): Unit = {
    println("Foo.value: " + Foo.value)

    val lista = List(1, 2, 3)
    println("Lista: " + lista.map(_ * 2))

    Console.flush()
  }
}

trait Parent {
  def greet(): String
}

trait Child extends Parent {
  override def greet(): String = "Hello from Child"
}


