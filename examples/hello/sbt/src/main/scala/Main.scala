import upickle.default._

@main def hello(): Unit =
  println("Hello world!")
  println(msg)
  write(Seq(1, 2, 3))
 
def msg = 
  utils.getMsg()

