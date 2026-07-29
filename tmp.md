# Plan: SemanticDB-Spec-Exact Scala Def Collector

## Part A — Spec compliance audit of `scala_defs_parser.md`

Source of truth: [SemanticDB v4 specification](https://github.com/scalameta/scalameta/blob/main/docs/semanticdb/specification.md) and `agents/semanticdb.md` (repo-local contract that codifies **compiler-output parity**, including the `X$package.` / `package.` wrappers the real Scala 3 compiler emits — these are NOT in the bare spec text but ARE part of the repo's "byte-for-byte identical" contract).

### Violations found in the plan as written

| # | Plan rule | Spec rule | Severity |
|---|-----------|-----------|----------|
| 1 | Methods always emit `name().` | METHOD/MACRO/CONSTRUCTOR require a **disambiguator**: `()` for first/only overload, `(+N)` for nth same-name declaration in source order. OBJECT and exceptional VAL-METHOD symbols do **not** contribute to the counter. | **CRITICAL** — produces collisions on overloaded defs and is byte-wrong for any overloaded method. |
| 2 | No `<init>` constructor symbols emitted for classes | Every CLASS emits primary `` `<owner>#`<init>`(). `` and every `def this(...)` secondary emits `` `<owner>#`<init>`(+N). ``. Spec explicitly: constructors carry disambiguators like methods; traits/objects get **no** synthetic constructor. | **CRITICAL** — spec violation; `go-to-definition` on `new Foo()` will miss. |
| 3 | Vals/vars inside method bodies emit `name.` as globals | vals/vars/local params/type-params are **LOCAL** symbols (`local<N>`), document-scoped, counter resets per file. Only top-level and member vals/vars are global with `.` descriptor. | **MEDIUM** — wrong category; inflates global table with locals. Repo's `semanticdb.md` "Not covered" list explicitly defers locals, so **drop locals entirely** (do not emit). |
| 4 | No `Pkg.Object` (package object) case | Spec: package object's name is `package`; descriptor is `.`; its owner is the **associated package**; its members' owner is the package object term, i.e. `<pkg>/package.<member>`. Plan handles packages + objects but not the union `Pkg.Object`. | **HIGH** — Scala 2 + Scala 3 package objects missed. |
| 5 | Top-level defs in `Foo.scala` use owner `<pkg>/` directly | Repo contract (per `agents/semanticdb.md` "Covered: Scala 3 top-level wrappers `X$package.`, `package.`", and existing tests `Bug B`, `Bug C`) requires: top-level **non-class** defs in `Foo.scala` are wrapped under `<pkg>/Foo$package.<def>`; **classes/traits/enums** are unchanged (`<pkg>/Bar#`); inside `package.scala` the wrapper is `<pkg>/package.`. The bare spec text doesn't say this, but the repo's "byte-for-byte identical to compiler" mandate does. | **HIGH** — diverges from real Scala 3 compiler output. |
| 6 | Enum cases emit `name.` (term) only | Acceptable for synthetic nav, but real compiler emits each case as a VAL/OBJECT with `ENUM` property and a `+N`-style disambiguator only when overloaded (enum case names are unique so no `+N`). The plan's `name.` is fine; the **companion object for the enum case** (e.g. `Color.Red.` owning case-class-style members) is also created by the compiler — out of scope, skip. | LOW — keep plan behavior. |
| 7 | Extension methods emit under enclosing owner | Spec-correct (extensions desugar to methods on enclosing scope). **But** the method belongs to the enclosing *term* scope and **must** go through the same overload counter as siblings. Plan says "do not change owner" — correct, but the new per-scope counter must still apply. | LOW — auto-fixed by adopting Q3's counter. |
| 8 | `Defn.Type` and `Defn.OpaqueTypeAlias` → `#` | Correct (TYPE kind). No disambiguator (TYPE symbols don't carry one). | OK |
| 9 | Classes/traits with type params — no `[T]` symbol emitted | Spec mandates TYPE_PARAMETER `[T]` symbols, but they are **local** (document-scoped) per `agents/semanticdb.md` "Not covered". Skip per repo policy. | OK |
| 10 | Method params `(x)` — not emitted | PARAMETER symbols. Per `agents/semanticdb.md` "Not covered". Skip. | OK |
| 11 | Case class synthetics: companion + apply + copy | Compiler also emits `unapply`, `applyCurried`, `applyTupled`, `apply$default$N`, `copy$default$N`. Per user Q4: emit only **companion `Person.`, `Person.apply().`, `Person#copy().`** plus the primary `<init>` from Q2. Documented incompleteness. | Acceptable per user. |
| 12 | Backtick escaping for non-Java identifiers (`<init>`, `::`, `==`, `???`) | **Already implemented in `SymbolUtils.escapedName`** — the new extractor MUST route every name through `SymbolUtils.*Symbol` helpers, never string-interpolate raw `name.value`. Plan step 3's `s"${currentOwner}${name.value}#"` is **wrong** — bypasses escaping. | **HIGH** — silent bug on operators/constructors. |
| 13 | Package owner prefix | Plan starts at `_empty_/` and replaces `.` with `/` appending `/`. Spec: empty package → `_empty_/`; nested packages concatenate descriptors `a/b/c/`. Top-level package's owner is `_root_`, but `_root_/` is **never written into symbols** (symbols start at `_empty_/` or the first real package). Plan is correct here. | OK |
| 14 | Range conversion | Scalameta `Position.startLine/startColumn` are 0-based; SemanticDB Range is 0-based; one-to-one. Plan correct. **But** Scalameta `Position` for synthetic/zero-width nodes may be `Position.None` — must guard. | LOW — add guard. |
| 15 | `SymbolTable` is keyed by `String` and last-write-wins | Two overloads of `def foo` produce different symbols (after Q3 fix), so no collision. Good. But two same-named **classes** in nested scopes would collide if owner is wrong — owner-tracking must be stack-correct. | OK |

### Summary of mandatory corrections (set by user answers)

1. **Constructors**: emit primary `<owner>#`<init>`().` for every `Defn.Class` and `Defn.Enum` (enum class part). For each `Defn.Ctor.Secondary` emit `<owner>#`<init>`(+N).` using the same overload counter slot as methods (`<init>` is its own name bucket). Traits and objects: **no** constructor symbol.
2. **Overload counter**: mutable `Map[(owner, name), Int]` per traverse; bumped on every `Defn.Def`, `Defn.Ctor.Secondary`, and `Defn.Ctor.Primary`-as-`<init>`. `Defn.Val`/`Var`/`Object`/`Package.Object` and `Defn.Type`/`EnumCase` do **not** bump and do **not** consult it.
3. **Case-class synthetics**: `companion` `.` + `apply().` (overload index 0, name `apply`) + `copy().` (overload index 0, name `copy`) — emitted **after** the class symbol and **before** traversing the body. `apply` consumes an overload slot in the companion-object scope (so a user-defined `def apply` would be `(+1)`); `copy` consumes a slot in the class scope.
4. **Scala 3 top-level wrapping** (Bug B/C): when the source filename is non-empty and not `package.scala`, top-level **non-class/trait/enum/object** stats are wrapped under owner `<pkg>/<FileBasename>$package.`; classes/traits/enums/objects keep owner `<pkg>/`. If the file is `package.scala`, all top-level stats (including non-classes) are wrapped under `<pkg>/package.`. If filename is empty, skip wrapping (test-compat path). The `extract(name, is)` API already carries the filename — pass it through.
5. **Raw names forbidden**: every descriptor built via `SymbolUtils.typeSymbol` / `termSymbol` / `methodSymbol` / `constructorSymbol`. No `s"$owner$name#"` anywhere.
6. **Locals dropped**: `Defn.Val`/`Var` whose enclosing owner is a method (or any non-type, non-package, non-object enclosing scope where the symbol table can't host a global) are silently skipped. Practically: only emit val/vars whose `currentOwner` ends in `#` (class/trait/enum-class scope), `.` (object/package-object scope) or `/` (package scope, i.e. top-level pkg-level vals). Inside method body (`currentOwner` ending in `).`), skip.

### Out-of-scope per repo policy (already documented in `agents/semanticdb.md` "Not covered")

- Local symbols (`local<N>`), method parameters `(x)`, type parameters `[T]`, self parameters.
- Anonymous givens (`given X = ...` with no name).
- Java SemanticDB.
- SUID resolution.
- Enum-case companion objects and their members.
- Trait/object synthetic constructors.
- Method *signatures*/`SymbolInformation` metadata (kind, properties, access) — this collector only emits symbol strings + range + isType + shortName. Full `SymbolInformation` is a later milestone.

---

## Part B — Implementation plan for executing agent

All edits confined to two files (per user's "Leave callers broken, extractor only" choice): `modules/navigation/src/ba/sake/basamake/navigation/ScalaDefinitionsExtractor.scala` and `modules/navigation/test/src/ba/sake/basamake/navigation/ScalaDefinitionsExtractorTest.scala` (new). `SymbolUtils` and `SymbolTable` are **already complete and correct** — do NOT modify them. Do NOT touch `DependencySourceParsing`, `ScalaSourceParser`, tests, or `BasamakeLanguageServer`.

### B.0 Pre-flight (read-only verification)

1. `read` `modules/navigation/src/ba/sake/basamake/navigation/SymbolUtils.scala` — confirm helpers: `packageOwner(dotted)`, `typeSymbol(owner,name)`, `termSymbol(owner,name)`, `methodSymbol(owner,name,idx)`, `constructorSymbol(owner,idx)`, `escapedName(name)`. All exist and are spec-correct. Use them verbatim.
2. `read` `modules/navigation/src/ba/sake/basamake/navigation/SymbolTable.scala` — confirm `SymbolDefinition(symbol, shortName, isType, range: Option[Range])` and `add`/`get`. Do not change.
3. `read` `modules/core/test/src/ba/sake/basamake/navigation/DependencySourceParsingTest.scala` lines 1–80 — these are the canonical examples of expected symbol strings; mirror their assertion style.
4. Confirm `scala.meta` tree type names: `Pkg`, `Pkg.Object`, `Defn.Class`, `Defn.Trait`, `Defn.Object`, `Defn.Enum`, `Defn.EnumCase`, `Defn.RepeatedEnumCase`, `Defn.Def`, `Defn.Val`, `Defn.Var`, `Defn.Type`, `Defn.OpaqueTypeAlias`, `Defn.Given`, `Defn.GivenAlias`, `Defn.ExtensionGroup`, `Defn.Ctor.Primary`, `Defn.Ctor.Secondary`, `Ctor.Block` (the body of a class with a primary ctor), `Template.body`/`Template.stats`.
5. Run `deder exec` once to confirm baseline compile (it will fail in `DependencySourceParsing` etc. — that's expected per user's Q1 choice; do **not** try to fix those failures).

### B.1 Implement `ScalaDefinitionsExtractor.scala`

**Design decisions:**

- **Functional owner parameter** instead of `var currentOwner` + `withOwner` — pass owner through `extractStats(stats, owner, ovl, wrapper)` directly. No mutable state beyond overload counter.
- **Overload counter**: `mutable.Map[(String, String), Int]` initialized per file traversal.
- **Top-level wrapper**: computed once from filename at parse time. Applied only to non-class/trait/enum/object top-level stats when filename is non-empty.
- **All symbol names routed through `SymbolUtils`** helpers — never raw string interpolation.
- **Locals skipped**: when owner ends with `).`, `Defn.Val`/`Defn.Var` are silently dropped.

**Algorithm outline:**

```
extractInternal(fileName, content):
  parseSource(content) match
    case Some(src) =>
      val ovl = mutable.Map.empty[(String, String), Int]
      val pkgOwner = extractPkgOwner(src.stats)
      val wrapper = computeWrapper(fileName, pkgOwner)
      extractStats(src.stats, pkgOwner, ovl, wrapper)
    case None => () // parse failure, logged already

extractStats(stats, owner, ovl, wrapper):
  for each stat:
    match
      case Pkg(ref, stats) =>
        val newOwner = mkPackageOwner(owner, ref.syntax.split('.').toList)
        extractStats(stats, newOwner, ovl, None) // no wrapper inside nested pkgs
        
      case Pkg.Object(ref, body) =>
        val pkgOwner = extractPkgOwner(List(Pkg(ref, Nil))) // owner of the package
        val pkgObjOwner = s"${pkgOwner}package."
        addSymbol(pkgObjOwner, "package", isType=false)
        extractStats(body.stats, pkgObjOwner, ovl, None)
        
      case cls @ Defn.Class(mods, name, _, _, templ) =>
        val classOwner = if (isTopLevel(owner) && wrapper.isDefined) owner else owner
        // Classes are NOT wrapped
        val sym = SymbolUtils.typeSymbol(classOwner, name.value)
        addSymbol(sym, name.value, isType=true)
        // primary constructor
        val idx = bumpOvl(ovl, sym, "<init>")
        addSymbol(SymbolUtils.constructorSymbol(sym, idx), name.value, isType=false)
        // case class synthetics
        if (cls.mods.exists(_.isInstanceOf[Mod.Case]))
          emitCaseClassSynthetics(classOwner, name.value, ovl)
        extractStats(templ.stats, sym, ovl, None)
        
      case Defn.Trait(mods, name, _, _, templ) =>
        val sym = SymbolUtils.typeSymbol(owner, name.value)
        addSymbol(sym, name.value, isType=true)
        // NO constructor for traits
        extractStats(templ.stats, sym, ovl, None)
        
      case Defn.Object(mods, name, templ) =>
        val sym = SymbolUtils.termSymbol(owner, name.value)
        addSymbol(sym, name.value, isType=false)
        // NO constructor for objects
        extractStats(templ.stats, sym, ovl, None)
        
      case Defn.Enum(mods, name, _, _, templ) =>
        val typeSym = SymbolUtils.typeSymbol(owner, name.value)
        val termSym = SymbolUtils.termSymbol(owner, name.value)
        addSymbol(typeSym, name.value, isType=true)
        addSymbol(termSym, name.value, isType=false)
        val idx = bumpOvl(ovl, typeSym, "<init>")
        addSymbol(SymbolUtils.constructorSymbol(typeSym, idx), name.value, isType=false)
        // enum cases go under companion object
        extractStats(templ.stats, termSym, ovl, None)
        
      case Defn.EnumCase(mods, name, _, _, _) =>
        addSymbol(SymbolUtils.termSymbol(owner, name.value), name.value, isType=false)
        
      case Defn.RepeatedEnumCase(mods, cases) =>
        cases.foreach { c =>
          addSymbol(SymbolUtils.termSymbol(owner, c.name.value), c.name.value, isType=false)
        }
        
      case Defn.Def(mods, name, _, _, _, body) =>
        val effectiveOwner =
          if (isTopLevel(owner) && wrapper.isDefined) wrapper.get
          else owner
        val idx = bumpOvl(ovl, effectiveOwner, name.value)
        addSymbol(SymbolUtils.methodSymbol(effectiveOwner, name.value, idx), name.value, isType=false)
        // recurse into body for nested defs
        body match
          case Term.Block(stats) => extractStats(stats, effectiveOwner, ovl, None)
          case _ => ()
          
      case Defn.Ctor.Secondary(mods, name, _, _, _) =>
        val idx = bumpOvl(ovl, owner, "<init>")
        addSymbol(SymbolUtils.constructorSymbol(owner, idx), "<init>", isType=false)
        
      case Defn.Val(mods, pats, _, rhs) =>
        if (!owner.endsWith(").")) // skip locals
          pats.foreach {
            case Pat.Var(name) =>
              addSymbol(SymbolUtils.termSymbol(owner, name.value), name.value, isType=false)
            case _ => ()
          }
        // traverse RHS for nested defs (rare)
        rhs match
          case Term.Block(stats) => extractStats(stats, owner, ovl, None)
          case _ => ()
          
      case Defn.Var(mods, pats, _, rhs) =>
        // same as Val
        ...
        
      case Defn.Type(mods, name, _, _) =>
        addSymbol(SymbolUtils.typeSymbol(owner, name.value), name.value, isType=true)
        
      case Defn.OpaqueTypeAlias(mods, name, _, _, _) =>
        addSymbol(SymbolUtils.typeSymbol(owner, name.value), name.value, isType=true)
        
      case Defn.Given(mods, name, _, _, body) if name.value.nonEmpty =>
        val sym = SymbolUtils.termSymbol(owner, name.value)
        addSymbol(sym, name.value, isType=false)
        body match
          case Term.Block(stats) => extractStats(stats, sym, ovl, None)
          case _ => ()
          
      case Defn.GivenAlias(mods, name, _, _, _, _) if name.value.nonEmpty =>
        addSymbol(SymbolUtils.termSymbol(owner, name.value), name.value, isType=false)
        
      case Defn.Given(...) | Defn.GivenAlias(...) =>
        () // anonymous given: skip
        
      case Defn.ExtensionGroup(mods, _, body) =>
        body match
          case Term.Block(stats) => extractStats(stats, owner, ovl, None)
          case _ => ()
          
      case _ => () // imports, exports, expression statements
```

### B.2 Create `PositionUtils.scala`

File: `modules/navigation/src/ba/sake/basamake/navigation/PositionUtils.scala`

```scala
package ba.sake.basamake.navigation

import scala.meta.inputs.Position
import scala.meta.internal.semanticdb.Range

object PositionUtils {
  def toRange(pos: Position): Range =
    new Range(
      startLine = pos.startLine,
      startCharacter = pos.startColumn,
      endLine = pos.endLine,
      endCharacter = pos.endColumn
    )
}
```

Note: `Position.None` is filtered by caller before calling `toRange`.

### B.3 Non-breaking addition to `SymbolTable`

Add `def all: Set[SymbolDefinition]` for testability:

```scala
import scala.jdk.CollectionConverters.*

def all: Set[SymbolDefinition] = definitions.values().asScala.toSet
```

### B.4 Verify

```bash
deder exec -t test -m navigation-test    # runs new test — must pass 100%
```

If `deder exec` (no `-m`) fails due to pre-existing broken `ScalaSourceParser` refs in other modules, that is **expected and approved**. Do NOT fix those.

---

## Part C — Test cases

File: `modules/navigation/test/src/ba/sake/basamake/navigation/ScalaDefinitionsExtractorTest.scala`

Each test: parse source string → run extractor → assert exact symbol set with isType.

### C.1 Empty package, single class

```scala
// filename: ""
val code = "class C"
```
Expected:
```
_empty_/C#           (isType=true)
_empty_/C#`<init>`(). (isType=false)
```

### C.2 One package, single class

```scala
val code = "package a\nclass C"
```
Expected:
```
a/C#              (isType=true)
a/C#`<init>`().    (isType=false)
```

### C.3 Nested packages a.b.c

```scala
val code = "package a.b.c\nclass C"
```
Expected:
```
a/b/c/C#           (isType=true)
a/b/c/C#`<init>`().(isType=false)
```

### C.4 Empty package, multiple top-level classes

```scala
// filename: ""
val code = "class A; class B"
```
Expected:
```
_empty_/A#           _empty_/A#`<init>`().
_empty_/B#           _empty_/B#`<init>`().
```

### C.5 Trait + object + class with method

```scala
val code = """package com.example
trait T { def t: Int }
class C { def m(x: Int): Int = x }
object O { val v: Int = 1 }"""
```
Expected:
```
com/example/T#           (isType=true)
com/example/T#t().        (isType=false)
com/example/C#            (isType=true)
com/example/C#`<init>`().  (isType=false)
com/example/C#m().        (isType=false)
com/example/O.             (isType=false)
com/example/O.v.          (isType=false)
```
No constructor for T or O.

### C.6 Nested object → class → method (owner chaining)

```scala
val code = """package pkg
object Outer {
  class Inner {
    def m(): Int = 0
  }
}"""
```
Expected:
```
pkg/Outer.
pkg/Outer.Inner#
pkg/Outer.Inner#`<init>`().
pkg/Outer.Inner#m().
```

### C.7 Class inside a method body

```scala
val code = """package pkg
def top(): Unit = {
  class InMethod
  object AlsoInMethod
}"""
```
Expected:
```
pkg/top().
pkg/top().InMethod#           (isType=true)
pkg/top().InMethod#`<init>`(). (isType=false)
pkg/top().AlsoInMethod.       (isType=false)
```
No `<init>` for object. Locals NOT emitted.

### C.8 Method overloads (per (owner,name) counter)

```scala
val code = """package p
class O {
  def f(): Int = 0
  def f(x: Int): Int = x
  def f(x: Int, y: Int): Int = x + y
  def g(): Int = 0
}"""
```
Expected:
```
p/O#              (isType=true)
p/O#`<init>`().   (idx 0)
p/O#f().          (idx 0)
p/O#f(+1).        (idx 1)
p/O#f(+2).        (idx 2)
p/O#g().          (idx 0)
```

### C.9 Secondary constructors

```scala
val code = """package p
class C(x: Int) {
  def this() = this(0)
  def this(s: String) = this(s.length)
}"""
```
Expected:
```
p/C#               (isType=true)
p/C#`<init>`().    (primary, idx 0)
p/C#`<init>`(+1).  (secondary #1)
p/C#`<init>`(+2).  (secondary #2)
```

### C.10 Package object

```scala
val code = """package scala.collection
package object mutable {
  val answer: Int = 42
  def hello(): String = "x"
}"""
```
Expected:
```
scala/collection/mutable/package.        (isType=false)
scala/collection/mutable/package.answer. (isType=false)
scala/collection/mutable/package.hello().(isType=false)
```

### C.11 Type aliases + opaque type

```scala
val code = """package com.example
type IntList = List[Int]
opaque type ID = String"""
```
Expected:
```
com/example/IntList#   (isType=true)
com/example/ID#        (isType=true)
```

### C.12 Enum (single + RepeatedEnumCase)

```scala
// filename: ""
val code = """package com.example
enum Color { case Red, Blue }
enum Color2 { case Green; case Yellow }"""
```
Expected:
```
com/example/Color#              (isType=true)
com/example/Color.              (isType=false)
com/example/Color#`<init>`().   (isType=false)
com/example/Color.Red.         (isType=false)
com/example/Color.Blue.        (isType=false)
com/example/Color2#             (isType=true)
com/example/Color2.             (isType=false)
com/example/Color2#`<init>`().  (isType=false)
com/example/Color2.Green.        (isType=false)
com/example/Color2.Yellow.        (isType=false)
```

### C.13 Named givens with body method

```scala
// filename: ""
val code = """package com.example
trait Show[T] { def show(t: T): String }
given stringShow: Show[String] with {
  def show(t: String): String = t
}
given intShow: Show[Int] = new Show[Int] { def show(t: Int): String = t.toString }"""
```
Expected:
```
com/example/Show#                  (isType=true)
com/example/Show#show().           (isType=false)
com/example/stringShow.            (isType=false)
com/example/stringShow.show().     (isType=false)
com/example/intShow.               (isType=false)
```
No constructor for trait Show#.

### C.14 Extension method

```scala
val code = """package com.example
extension (s: String) {
  def makeLoud(): String = s + "!"
  def doubled(): String = s + s
}"""
```
Expected:
```
com/example/makeLoud().    (isType=false)
com/example/doubled().     (isType=false)
```

### C.15 Case class synthetics

```scala
val code = """package com.example
case class Person(name: String)
case class Empty()"""
```
Expected:
```
com/example/Person#               (isType=true)
com/example/Person#`<init>`().    (isType=false)
com/example/Person.              (isType=false)
com/example/Person.apply().       (isType=false)
com/example/Person#copy().         (isType=false)
com/example/Empty#                (isType=true)
com/example/Empty#`<init>`().     (isType=false)
com/example/Empty.              (isType=false)
com/example/Empty.apply().       (isType=false)
com/example/Empty#copy().         (isType=false)
```

### C.16 Case class with user-defined apply/copy (overload interaction)

```scala
val code = """package com.example
case class Person(name: String) {
  def apply(): Int = 0
  def copy(x: String): Person = this
}"""
```
Expected:
```
com/example/Person#               (isType=true)
com/example/Person#`<init>`().    (isType=false)
com/example/Person.              (isType=false)
com/example/Person.apply().       (synthetic, idx 0, isType=false)
com/example/Person.apply(+1).      (user, idx 1, isType=false)
com/example/Person#copy().         (synthetic, idx 0, isType=false)
com/example/Person#copy(+1).       (user, idx 1, isType=false)
```

### C.17 Top-level defs in Foo.scala → X$package. wrapper

```scala
// filename: "Foo.scala"
val code = """package com.example
def topLevelMethod(): Int = 42
val topLevelVal: Int = 1
class TopClass
object TopObject"""
```
Expected:
```
com/example/Foo$package.topLevelMethod().   (isType=false)
com/example/Foo$package.topLevelVal.        (isType=false)
com/example/TopClass#                         (isType=true)
com/example/TopClass#`<init>`().              (isType=false)
com/example/TopObject.                        (isType=false)
```

### C.18 Top-level defs in package.scala → package. wrapper

```scala
// filename: "package.scala"
val code = """package com.example
def helper(): Int = 0
val default: Int = 1
class Inside"""
```
Expected:
```
com/example/package.helper().   (isType=false)
com/example/package.default.    (isType=false)
com/example/Inside#             (isType=true)
com/example/Inside#`<init>`().  (isType=false)
```

### C.19 Empty filename → no wrapping

```scala
// filename: ""
val code = """package com.example
def helper(): Int = 0"""
```
Expected:
```
com/example/helper().    (isType=false)
```

### C.20 Operator-named methods (backtick escape)

```scala
val code = """package com.example
class C {
  def + (x: Int): Int = 0
  def `unary_!`: Int = 0
  def ==(that: Any): Boolean = true
}"""
```
Expected:
```
com/example/C#              (isType=true)
com/example/C#`<init>`().   (isType=false)
com/example/C#`+`().         (isType=false)
com/example/C#`unary_!`().   (isType=false)
com/example/C#`==`().        (isType=false)
```
**Critical**: this test fails if any code path uses raw `name.value` instead of `SymbolUtils`.

### C.21 Original scala_defs_parser.md integration test (full)

```scala
// filename: "Features.scala"
val code = """package com.example
opaque type ID = String
enum Color { case Red, Blue }
trait Show[T] { def show(t: T): String }
given stringShow: Show[String] with { def show(t: String): String = t }
extension (s: String) { def makeLoud(): String = s + "!" }
case class Person(name: String)
def topLevelMethod(): Int = 42"""
```
Expected:
```
com/example/ID#                              (isType=true)
com/example/Color#                          (isType=true)
com/example/Color.                          (isType=false)
com/example/Color#`<init>`().               (isType=false)
com/example/Color.Red.                     (isType=false)
com/example/Color.Blue.                    (isType=false)
com/example/Show#                            (isType=true)
com/example/Show#show().                     (isType=false)
com/example/stringShow.                    (isType=false)
com/example/stringShow.show().             (isType=false)
com/example/makeLoud().                     (isType=false)
com/example/Person#                         (isType=true)
com/example/Person#`<init>`().               (isType=false)
com/example/Person.                          (isType=false)
com/example/Person.apply().                  (isType=false)
com/example/Person#copy().                   (isType=false)
com/example/Features$package.topLevelMethod(). (isType=false)
```

---

## Part D — Scalameta AST gotchas (risk list)

1. **`Defn.OpaqueTypeAlias`**: verify field arity in scalameta 4.17.x — likely `(mods, name, tparams, body)`. If compile fails, adjust.
2. **`Defn.RepeatedEnumCase`**: constructor `(mods, cases)` where `cases: List[EnumCase]`. Confirm.
3. **`Pkg.Object`**: `.body.stats` vs `.templ.stats` — verify which compiles.
4. **`Defn.Ctor.Secondary.pos`**: may not expose `.name` directly; use `.pos` for the range.
5. **`c.templ.stats`**: verify it returns only body members, not constructor params.
6. **`Term.Block` extraction**: block children can be `Defn.Def` (nested defs), `Term.*` (expression stmts — skip), `Defn.Val`/`Var` (locals — skip per owner check).
7. **Wrapper `X$package.`** symbol: the wrapper object IS emitted as a term symbol (test C.17 expects `Foo$package.`). Actually looking more carefully: the test expects `com/example/Foo$package.topLevelMethod().` — the wrapper is an owner prefix, NOT a separate SymbolDefinition. In Scala 3, the compiler DOES emit `X$package.` as a synthetic OBJECT symbol, but our extractor's task is just to correctly OWNER-wrap the defs. Whether we emit `Foo$package.` as a separate symbol is optional. The test C.17 does NOT assert `Foo$package.` exists as a separate entry. Keep it as owner-only.

---

## Part E — Final acceptance criteria

- `deder exec -t test -m navigation-test` passes all 21 tests green.
- `deder exec -t test -m core-test` may fail (pre-existing broken refs — explicitly out of scope).
- No edits to `SymbolUtils.scala` or `SymbolTable` (beyond the additive `def all` accessor).
- All names routed through `SymbolUtils.*Symbol` helpers.
- Test C.20 (backtick escape) and test C.17 (X$package. wrapper) MUST pass.
