package ba.sake.basamake

import ch.qos.logback.classic.{Level, LoggerContext}
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.{ConsoleAppender, FileAppender}
import org.slf4j.LoggerFactory
import ba.sake.basamake.lsp.BasamakeLanguageServer
import org.eclipse.lsp4j.launch.LSPLauncher
import java.nio.file.{Files, Path, Paths}

object Main:
  def main(args: Array[String]): Unit =
    // Parse --workspace from args before any logging
    val workspacePath = parseWorkspace(args)
    configureLogging(workspacePath)

    val log = LoggerFactory.getLogger("basamake")
    log.info("Basamake LSP server starting")
    log.info(s"Workspace: $workspacePath")
    log.info(s"Java: ${System.getProperty("java.version")}")

    // Wrap stdout in auto-flush so LSP messages go out immediately
    val autoFlushOut = new java.io.PrintStream(System.out, true, "UTF-8")

    val server = BasamakeLanguageServer()
    val launcher = LSPLauncher.createServerLauncher(server, System.in, autoFlushOut)
    server.connect(launcher.getRemoteProxy)

    log.info("LSP launcher created, listening on stdio...")

    launcher.startListening() // starts async message processing on lsp4j thread pool
    log.info("LSP message processor started")

    // Keep JVM alive until exit() calls System.exit
    java.util.concurrent.locks.LockSupport.park()
    log.info("LSP server stopped")

  private def parseWorkspace(args: Array[String]): Path =
    args.sliding(2).collectFirst:
      case Array("--workspace", path) => Paths.get(path)
    .getOrElse(Paths.get("."))

  private def configureLogging(workspace: Path): Unit =
    val ctx = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]

    val encoder = PatternLayoutEncoder()
    encoder.setContext(ctx)
    encoder.setPattern("%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n")
    encoder.start()

    // 1. Console appender (stderr — VS Code output panel)
    val consoleAppender = ConsoleAppender[ILoggingEvent]()
    consoleAppender.setContext(ctx)
    consoleAppender.setTarget("System.err")
    consoleAppender.setEncoder(encoder)
    consoleAppender.start()

    // 2. File appender (.basamake/logs/basamake.log)
    val logsDir = workspace.resolve(".basamake").resolve("logs")
    Files.createDirectories(logsDir)
    val logFile = logsDir.resolve("basamake.log").toString

    val fileAppender = FileAppender[ILoggingEvent]()
    fileAppender.setContext(ctx)
    fileAppender.setFile(logFile)
    fileAppender.setEncoder(encoder)
    fileAppender.start()

    // Attach to root logger — kill any existing appenders first
    val rootLogger = ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
    rootLogger.detachAndStopAllAppenders()
    rootLogger.setLevel(Level.DEBUG)
    rootLogger.addAppender(consoleAppender)
    rootLogger.addAppender(fileAppender)

  // Called after initialize to set the correct workspace for file logging
  def reconfigureFileLogging(workspace: Path): Unit =
    val ctx = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
    val encoder = PatternLayoutEncoder()
    encoder.setContext(ctx)
    encoder.setPattern("%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n")
    encoder.start()

    val logsDir = workspace.resolve(".basamake").resolve("logs")
    Files.createDirectories(logsDir)
    val logFile = logsDir.resolve("basamake.log").toString

    // Remove old file appenders, add new one
    val rootLogger = ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
    import scala.jdk.CollectionConverters.*
    rootLogger.iteratorForAppenders().asScala.foreach {
      case fa: FileAppender[_] => rootLogger.detachAppender(fa); fa.stop()
      case _ => ()
    }

    val fileAppender = FileAppender[ILoggingEvent]()
    fileAppender.setContext(ctx)
    fileAppender.setFile(logFile)
    fileAppender.setEncoder(encoder)
    fileAppender.start()
    rootLogger.addAppender(fileAppender)
