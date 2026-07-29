package com.example

object Models:
  val greeting: String = "hi"
  val answer: Int = 42

case class Person(name: String):
  def hi(): String = name

enum Color { case Red, Blue }
