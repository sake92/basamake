package hello

object Main:
  def main(args: Array[String]): Unit =
    println("Hello, Basamake!")
    broken()
    val d = new Deder(args)

  def broken(): Unit =
    // Deliberate compile error: type mismatch + undefined identifier
    val x = "this is a string"
    val y =  x //nonexistentFunction(x) 
    println(y)

class Deder(args: Array[String]):
  println("Deder initialized with arguments: " + args.mkString(", "))