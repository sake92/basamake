@main def hello(): Unit =
  println(add(2, 3))
  println(siblingVal)
  println(other())
  println(Helper.greet())
  val local = add(4, 5)
  println(local)

def msg =
  add(1, 1)
