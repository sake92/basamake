package ba.sake.basamake.bsp

import ch.epfl.scala.bsp4j.{BuildServer, ScalaBuildServer}

/** Union of BuildServer + ScalaBuildServer. lsp4j discovers all @JsonRequest
  * methods from both parent interfaces. Single proxy, both casts work. */
trait BasamakeBspServer extends BuildServer, ScalaBuildServer
