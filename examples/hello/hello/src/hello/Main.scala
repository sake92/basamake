package hello

object Main:
  def main(args: Array[String]): Unit =
    println("Hello, Basamake!")

  def broken(): Unit =
    // Deliberate compile error: type mismatch + undefined identifier
    val x: Int = "this is a string"
    val y = nonexistentFunction(x)
    println(y)

?
