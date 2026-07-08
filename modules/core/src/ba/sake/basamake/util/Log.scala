package ba.sake.basamake.util

import org.slf4j.{Logger, LoggerFactory}

/** Logger wrapper. Uses SLF4J with Logback backend. */
object Log:
  private val logger: Logger = LoggerFactory.getLogger("basamake")

  def info(msg: String): Unit  = logger.info(msg)
  def warn(msg: String): Unit  = logger.warn(msg)
  def error(msg: String): Unit = logger.error(msg)
  def error(msg: String, t: Throwable): Unit = logger.error(msg, t)
  def debug(msg: String): Unit = logger.debug(msg)
