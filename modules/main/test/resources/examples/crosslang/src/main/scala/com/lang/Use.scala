package com.lang

import com.lang.Greeter

object Use:
  def go(): String =
    val s = Greeter.hello()
    val g = new Greeter()
    g.bye()
