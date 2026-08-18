package ba.sake.basamake.util

/** Project-root resolution: basamake's `.basamake/` (logs, config, data.json, source
  * walk, .bsp discovery) lives at the first ancestor of the opened folder that is
  * either a git root (`.git` dir or file — a file marks a git worktree) or an
  * existing workspace marker (`.basamake/` dir). Non-git folders without a marker
  * fall back to the opened folder itself. */
object ProjectRoot {

  def resolve(openedDir: os.Path): os.Path = {
    var cur = openedDir
    while true do {
      if os.exists(cur / ".git") then return cur
      if os.isDir(cur / ".basamake") then return cur
      if cur == os.root then return openedDir
      cur = cur / os.up
    }
    openedDir
  }
}
