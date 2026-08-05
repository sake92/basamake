package hello

object Main {
  def main(args: Array[String]): Unit = {
    println("Hello, Basamake!")
    broken()
    val d = new Deder(args)
    dederutils.greet(name = "World")
  }

  def broken(): Unit = {
    // Deliberate compile error: type mismatch + undefined identifier
    val x = "this is a string" 
    val yy = 545 //nonexistentFunggdgction(x) 
    println(yy)
  }
}

class Deder(args: Array[String]) {
  println("Deder initialized with arguments: " + args.mkString(", "))
}


 

