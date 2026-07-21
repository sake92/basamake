package ba.sake.basamake.navigation

import java.util.concurrent.Executors
import munit.FunSuite

class JavaSourceParserConcurrencyTest extends FunSuite {

  private val javaSource = """package com.example;
    |
    |import java.util.List;
    |
    |public class Foo {
    |    private int count;
    |    private String name;
    |
    |    public Foo(int count, String name) {
    |        this.count = count;
    |        this.name = name;
    |    }
    |
    |    public int getCount() { return count; }
    |    public void setCount(int c) { this.count = c; }
    |    public String getName() { return name; }
    |    public List<String> getItems() { return null; }
    |
    |    public static class Bar {
    |        private boolean flag;
    |        public Bar() { this.flag = true; }
    |        public boolean isFlag() { return flag; }
    |    }
    |
    |    public enum Color { RED, GREEN, BLUE }
    |}
    """.stripMargin

  test("concurrent Java parse yields consistent results") {
    val parallelism = 50
    val executor = Executors.newVirtualThreadPerTaskExecutor()
    try {
      val futures = (1 to parallelism).map { _ =>
        executor.submit(() => JavaSourceParser.extractDefinitions(javaSource, "Foo.java"))
      }
      val results = futures.map(_.get())
      val first = results.head
      assert(first.nonEmpty, "expected definitions, got empty — shared parser corrupt?")
      results.foreach { r =>
        assertEquals(r.size, first.size, "concurrent parses produced different def counts")
        assertEquals(r.map(_.name).sorted, first.map(_.name).sorted, "concurrent parses diverged")
      }
    } finally executor.shutdown()
  }
}
