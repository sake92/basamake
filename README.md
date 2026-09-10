# basamake

Minimalistic Scala LSP for fast navigation, compiler-backed editing help, and build diagnostics.

Focused on the essentials: navigate, understand, edit, and compile Scala code without a
full IDE backend.

Use the [Basamake VSCode extension](https://github.com/sake92/basamake-vscode) to get started.

Documentation: https://sake92.github.io/basamake

Features:
- multiple BSP servers in one workspace, lazily started
- reports BSP diagnostics
- go to definition and find references for Scala and Java
- cached dependency and JDK source indexes for navigation
- hover: inferred types, signatures, and available Scaladoc/Javadoc
- Scala code completion, powered by the BSP target's presentation compiler

Hover and completion use the Scala version, classpath, and compiler options reported by the
owning BSP target. They require a compatible presentation compiler for that exact Scala
version; navigation and references remain available through the workspace index when one is
not available.
