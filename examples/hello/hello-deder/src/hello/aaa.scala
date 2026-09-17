package hello

object dederutils:
  def greet(name: String): String =
    s"Hello, $name!"

 

trait Parent {
  def greet(): String
}

trait Child extends Parent {
  override def greet(): String = "Hello from Child"
}
