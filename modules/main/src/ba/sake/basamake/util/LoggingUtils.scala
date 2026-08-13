package ba.sake.basamake.util

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.FileAppender
import com.typesafe.scalalogging.StrictLogging
import org.slf4j.LoggerFactory

object LoggingUtils extends StrictLogging {

  def configureFileLogging(workspace: os.Path): Unit =
    val ctx = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
    val rootLogger = ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)

    // Detach existing FILE appender if reconfiguring
    rootLogger.getAppender("FILE") match
      case fa: FileAppender[_] =>
        rootLogger.detachAppender(fa)
        fa.stop()
      case _ => ()

    val logsDir = workspace / ".basamake/logs"
    os.makeDir.all(logsDir)
    val logFile = (logsDir / "basamake.log").toString

    val encoder = PatternLayoutEncoder()
    encoder.setContext(ctx)
    encoder.setPattern("%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n")
    encoder.start()

    val appender = FileAppender[ILoggingEvent]()
    appender.setName("FILE")
    appender.setContext(ctx)
    appender.setFile(logFile)
    appender.setEncoder(encoder)
    appender.start()

    rootLogger.addAppender(appender)
    logger.info(s"File logging configured: $logFile")

  /** Opt-in DEBUG diagnostics for workspace startup: when the user sets
    * `debugSlowFallbackMs` in .basamake/config.json, raise the WorkspaceIndex
    * logger to DEBUG so slow-fallback extractions become visible in the file
    * log. No-op when unset — normal logs stay at INFO. */
  def enableWorkspaceIndexDebugIfRequested(slowFallbackThresholdMs: Option[Long]): Unit =
    if slowFallbackThresholdMs.isDefined then
      val ctx = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
      val logger = ctx.getLogger(classOf[ba.sake.basamake.navigation.indexing.WorkspaceIndex].getName)
      logger.setLevel(ch.qos.logback.classic.Level.DEBUG)
      logger.info("DEBUG enabled for WorkspaceIndex (slow-fallback diagnostics)")

}