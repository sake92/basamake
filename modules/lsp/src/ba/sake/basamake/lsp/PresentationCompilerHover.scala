package ba.sake.basamake.lsp

import java.net.{URI, URL, URLClassLoader}
import java.util.ServiceLoader
import java.util.concurrent.{CompletableFuture, ConcurrentHashMap, TimeUnit}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import com.typesafe.scalalogging.StrictLogging
import coursierapi.{Dependency, Fetch}
import org.eclipse.lsp4j.{CompletionList, Hover}
import scala.meta.pc.{CancelToken, OffsetParams, PresentationCompiler}
import ba.sake.basamake.bsp.ScalaPresentationTarget
import PresentationCompilerHover.*

/** Resolves target-matched Scala presentation compilers behind the stable mtags
  * interface. Each compiler runs in its own classloader so Scala compiler
  * internals never leak into the language server's classpath. */
private[lsp] final class PresentationCompilerHover(workspaceRoot: os.Path) extends StrictLogging {
  private val compilers = new ConcurrentHashMap[CompilerKey, LoadedCompiler]()

  def hover(
      target: ScalaPresentationTarget,
      uri: URI,
      text: String,
      line: Int,
      character: Int
  ): Option[Hover] = {
    offsetAt(text, line, character).flatMap { offset =>
      try {
        compilerFor(target).presentationCompiler
          .hover(new SourceOffsetParams(uri, text, offset))
          .get(5, TimeUnit.SECONDS)
          .toScala
          .map(_.toLsp)
      } catch {
        case e: Throwable =>
          logger.debug(s"Presentation hover failed for ${target.id} (Scala ${target.scalaVersion}): ${e.getMessage}")
          None
      }
    }
  }

  def complete(
      target: ScalaPresentationTarget,
      uri: URI,
      text: String,
      line: Int,
      character: Int
  ): Option[CompletionList] = {
    offsetAt(text, line, character).flatMap { offset =>
      try {
        Option(compilerFor(target).presentationCompiler
          .complete(new SourceOffsetParams(uri, text, offset))
          .get(5, TimeUnit.SECONDS))
      } catch {
        case e: Throwable =>
          logger.debug(s"Presentation completion failed for ${target.id} (Scala ${target.scalaVersion}): ${e.getMessage}")
          None
      }
    }
  }

  def shutdown(): Unit = {
    compilers.values().asScala.foreach(_.close())
    compilers.clear()
  }

  private def compilerFor(target: ScalaPresentationTarget): LoadedCompiler =
    compilers.computeIfAbsent(CompilerKey(target), _ => newCompiler(target))

  private def newCompiler(target: ScalaPresentationTarget): LoadedCompiler = {
    val loader = new PresentationCompilerClassLoader(resolve(target), getClass.getClassLoader)
    try {
      val compiler = withContextClassLoader(loader) {
        target.scalaVersion match {
          case version if version.startsWith("2.") =>
            ServiceLoader.load(classOf[PresentationCompiler], loader).iterator().asScala.nextOption
              .getOrElse(throw new IllegalStateException(s"mtags did not provide a Scala 2 presentation compiler for $version"))
          case _ =>
            Class.forName("dotty.tools.pc.ScalaPresentationCompiler", true, loader)
              .getConstructor()
              .newInstance()
              .asInstanceOf[PresentationCompiler]
        }
      }
      LoadedCompiler(
        compiler.newInstance(target.id, target.classpath.map(_.toNIO).asJava, target.options.asJava)
          .withWorkspace(workspaceRoot.toNIO),
        loader
      )
    } catch {
      case e: Throwable =>
        loader.close()
        throw e
    }
  }

  private def resolve(target: ScalaPresentationTarget): Array[URL] = {
    val dependency = if (target.scalaVersion.startsWith("2.")) {
      // mtags is full-cross-published because it links Scala compiler internals.
      Dependency.of("org.scalameta", s"mtags_${target.scalaVersion}", MtagsVersion)
    } else {
      Dependency.of("org.scala-lang", "scala3-presentation-compiler_3", target.scalaVersion)
    }
    Fetch.create().addDependencies(dependency).fetch().asScala.map(_.toURI.toURL).toArray
  }

  private def withContextClassLoader[A](loader: ClassLoader)(body: => A): A = {
    val thread = Thread.currentThread()
    val previous = thread.getContextClassLoader
    try {
      thread.setContextClassLoader(loader)
      body
    } finally thread.setContextClassLoader(previous)
  }

  private def offsetAt(text: String, line: Int, character: Int): Option[Int] = {
    if (line < 0 || character < 0) return None
    var offset = 0
    var currentLine = 0
    while (currentLine < line) {
      val newline = text.indexOf('\n', offset)
      if (newline < 0) return None
      offset = newline + 1
      currentLine += 1
    }
    val lineEnd = text.indexOf('\n', offset) match {
      case -1 => text.length
      case end => end
    }
    val requested = offset + character
    if (requested <= lineEnd) Some(requested) else None
  }

  private final class SourceOffsetParams(val uri: URI, val text: String, val offset: Int) extends OffsetParams {
    override def token(): CancelToken = NeverCancelled
  }

  private object NeverCancelled extends CancelToken {
    override def checkCanceled(): Unit = ()
    override def onCancel(): java.util.concurrent.CompletionStage[java.lang.Boolean] =
      new CompletableFuture[java.lang.Boolean]()
  }
}

private object PresentationCompilerHover {
  private val MtagsVersion = "1.6.2"

  private final case class CompilerKey(
      id: String,
      scalaVersion: String,
      classpath: List[os.Path],
      options: List[String]
  )

  private object CompilerKey {
    def apply(target: ScalaPresentationTarget): CompilerKey =
      CompilerKey(target.id, target.scalaVersion, target.classpath, target.options)
  }

  private final case class LoadedCompiler(
      presentationCompiler: PresentationCompiler,
      classLoader: URLClassLoader
  ) {
    def close(): Unit = {
      try presentationCompiler.shutdown()
      finally classLoader.close()
    }
  }

  /** Parent-first only for values which cross the server/compiler boundary. */
  private final class PresentationCompilerClassLoader(urls: Array[URL], parent: ClassLoader)
      extends URLClassLoader(urls, parent) {
    override def loadClass(name: String, resolve: Boolean): Class[?] = {
      if (isShared(name)) return super.loadClass(name, resolve)
      getClassLoadingLock(name).synchronized {
        val loaded = findLoadedClass(name)
        val clazz = if (loaded != null) loaded else {
          try findClass(name)
          catch { case _: ClassNotFoundException => super.loadClass(name, false) }
        }
        if (resolve) resolveClass(clazz)
        clazz
      }
    }

    private def isShared(name: String): Boolean =
      name.startsWith("java.") ||
        name.startsWith("javax.") ||
        name.startsWith("scala.meta.pc.") ||
        name.startsWith("org.eclipse.lsp4j.") ||
        name.startsWith("org.slf4j.")
  }
}
