package hello

object Main:
  def main(args: Array[String]): Unit =
    println("Hello, Basamake!")
    broken()

  def broken(): Unit =
    // Deliberate compile error: type mismatch + undefined identifier
    val x = "this is a string"
    val y =5// nonexistentFunction(x)
    println(y)
