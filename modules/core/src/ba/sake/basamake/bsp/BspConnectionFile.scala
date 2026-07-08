package ba.sake.basamake.bsp

import java.nio.file.Path

final case class BspConnectionFile(
    path: Path,
    argv: List[String],
    workingDir: Path,
    debounceMs: Long = 500
)
