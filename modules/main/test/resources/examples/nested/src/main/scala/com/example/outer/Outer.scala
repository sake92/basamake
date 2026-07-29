package com.example.outer

class Outer:
  def id(self: Outer): Outer = self
  object Comp:
    def m(): Int = 1
