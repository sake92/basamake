package hello

object Main:
  def main(args: Array[String]): Unit =
    println("Hello, Basamake!")
    broken()
    val d = new Deder()

  def broken(): Unit =
    // Deliberate compile error: type mismatch + undefined identifier
    val x = "this is a string"
    val y =  nonexistentFunction(x)
    println(y)

class Deder