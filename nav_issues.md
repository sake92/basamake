

These are all issues with source files in THIS project itself. 

## methods call chain

```scala
object JavaSourceParser {
  private def nameRange(name: Node): Range =
    val begin = name.getBegin.orElseThrow()
```

- go to name works
- go to getBegin works
- go to orElseThrow doesnt work!


## methods chain again

```scala
class BasamakeBuildClient(queue: BlockingQueue[ConnectionMessage]) extends BuildClient, StrictLogging {
  override def onBuildTargetDidChange(params: DidChangeBuildTarget): Unit =
    logger.debug(s"BSP TARGET DID CHANGE: ${params.getChanges.size()} event(s)")
    queue.offer(ConnectionMessage.BuildTargetChanged(params))
```

- go to queue works
- go to offer doesnt work!
- go to ConnectionMessage works
- go to BuildTargetChanged doesnt work!
- go to params works


## private case class

```scala
object ScalaSourceParser extends StrictLogging {
  // ...
  private def collectConstructors(
      d: Defn.Class,
      classOwner: String
  ): List[SourceDefinition] = {
    // Primary constructor uses the class name position
    val primaryProto = ConstructorProto(toLspRange(d.name.pos))
    val secondaryProtos = d.templ.stats.collect {
      case c: Ctor.Secondary =>
        ConstructorProto(toLspRange(c.name.pos))
    }
    // ...
  }
  private case class ConstructorProto(range: Range)
}
```

- go to `ConstructorProto` in `ConstructorProto(toLspRange(d.name.pos))` doesnt work
- go to `d.name` doesnt work but  go to `d.name.pos` works! weird
- same in `d.templ.stats.collect {`, go to `d.templ` doesnt work, but go to `d`, `d.templ.stats`, `d.templ.stats.collect` do work :D
- seems like first selector doesnt work, a bit of pattern i noticed there


### object apply call again
```scala
private def termDef(kind: SymbolKind, name: meta.Name, owner: String): SourceDefinition =
    SourceDefinition(
```

go to `SourceDefinition` apply method doesnt work


### enum companion/definition?
```scala
  private def attachConnection(bspSpec: BspConnectionSpec): Unit = try {
    logger.debug(s"Attaching (lazy) BSP connection for ${bspSpec.path} (${bspSpec.content.name})")
    val id = BspConnectionId(bspSpec.path.toString)
    val record = DurableRecord(
      bspFile = new AtomicReference(bspSpec),
      attemptCounter = new AtomicInteger(0),
      lastKnownDiagnostics = new AtomicReference(Map.empty),
      currentState = BspConnectionState.Idle
    )
```

- `BspConnectionState.Idle` works but `BspConnectionState` not


### jdk sources
```scala
import java.util.concurrent.{TimeUnit, TimeoutException}
```

Nothing from JDK doesnt work, I guess we need to index WHOLE JDK???
This will be reeeally slow I think..

