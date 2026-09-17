package ba.sake.basamake.index.indexing

import java.util.concurrent.locks.ReentrantLock
import scala.collection.mutable

/** Workspace-only override relationships read from SemanticDB.
  *
  * Every replacement is owned by one source path, so a recompilation or file
  * deletion removes its old edges before publishing the new ones. Dependency
  * sources deliberately do not participate: their cache stores definitions,
  * not SemanticDB inheritance metadata.
  */
private[indexing] final class InheritanceIndex {
  private val lock = new ReentrantLock()
  private val supersByChild = mutable.Map.empty[String, Set[String]]
  private val childrenBySuper = mutable.Map.empty[String, Set[String]]
  private val childrenByPath = mutable.Map.empty[os.Path, Set[String]]

  def replace(path: os.Path, edges: Map[String, Set[String]]): Unit = withLock {
    removePath(path)
    val children = edges.iterator.collect {
      case (child, supers) if child.nonEmpty && supers.nonEmpty => child -> supers.filter(_.nonEmpty)
    }.filter(_._2.nonEmpty).toMap
    childrenByPath(path) = children.keySet
    children.foreach { case (child, supers) =>
      supersByChild(child) = supers
      supers.foreach { superSymbol =>
        childrenBySuper.updateWith(superSymbol) {
          case Some(existing) => Some(existing + child)
          case None => Some(Set(child))
        }
      }
    }
  }

  def remove(path: os.Path): Unit = withLock(removePath(path))

  def clear(): Unit = withLock {
    supersByChild.clear()
    childrenBySuper.clear()
    childrenByPath.clear()
  }

  /** All direct and indirect workspace overrides of `symbol`. */
  def implementationsOf(symbol: String): Set[String] = withLock {
    val result = mutable.Set.empty[String]
    val pending = mutable.Queue.from(childrenBySuper.getOrElse(symbol, Set.empty))
    while (pending.nonEmpty) {
      val child = pending.dequeue()
      if (result.add(child)) pending.enqueueAll(childrenBySuper.getOrElse(child, Set.empty))
    }
    result.toSet
  }

  private def removePath(path: os.Path): Unit = {
    childrenByPath.remove(path).foreach { children =>
      children.foreach { child =>
        supersByChild.remove(child).foreach { supers =>
          supers.foreach { superSymbol =>
            childrenBySuper.get(superSymbol).foreach { existing =>
              val remaining = existing - child
              if (remaining.isEmpty) childrenBySuper.remove(superSymbol)
              else childrenBySuper(superSymbol) = remaining
            }
          }
        }
      }
    }
  }

  private def withLock[T](body: => T): T = {
    lock.lock()
    try body
    finally lock.unlock()
  }
}
