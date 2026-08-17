package basamake.sbt

import upickle.default._

import java.util.ArrayList

@main def hello(): Unit =
  val ccc = Array(1, 2, 3)
  val d = ccc
  println("Hello world!")
  println(msg)
  write(Seq(1, 2, 3))
  val x: ArrayList[Int] = new ArrayList()
 
def msg = 
  sbtutils.getMsg()


