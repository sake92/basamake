package ba.sake.basamake.core

import java.nio.file.Path

final case class ConnectionSpec(
    path: Path,
    argv: List[String],
    workingDir: Path,
    debounceMs: Long = 500
)
