package com.example

@main def run(): Unit =
  println(Models.greeting)
  println(Util.doubled(21))
  println(helper())
  val p = new Person("x")
  println(p.name)

def caller(): Int = helper()
