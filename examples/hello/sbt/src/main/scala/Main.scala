package basamake.sbt

import upickle.default._

@main def hello(): Unit =
  val ccc = Array(1, 2, 3)
  val d = ccc
  println("Hello world!")
  println(msg)
  write(Seq(1, 2, 3))
  new java.lang.Object()
 
def msg = 
  sbtutils.getMsg()


