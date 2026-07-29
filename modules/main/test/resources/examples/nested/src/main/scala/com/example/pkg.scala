package com.example

package object models:
  val answer: Int = 42
  def hello(): String = "x"

object Use:
  def go(): Int =
    println(answer)
    hello()
    answer
