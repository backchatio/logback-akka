package mojolly
package logback

import ch.qos.logback.classic.pattern.ClassicConverter
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.Level

class LevelColorizer extends ClassicConverter {
  private val EndColor = "\u001b[m"
  private val ErrorColor = "\u001b[0;31m"
  private val WarnColor = "\u001b[0;33m"
  private val InfoColor = "\u001b[0;32m"
  private val DebugColor = "\u001b[0;37m"

  private val colors = Map(
    Level.TRACE -> DebugColor,
    Level.DEBUG -> DebugColor,
    Level.INFO -> InfoColor,
    Level.WARN -> WarnColor,
    Level.ERROR -> ErrorColor)

  def convert(event: ILoggingEvent) = {
    val c = colors.getOrElse(event.getLevel, "")
    val cOpt = if (c == null || c.trim().isEmpty) None else Some(c)
    "%s%s%s" format (c, event.getLevel, cOpt map (_ ⇒ EndColor) getOrElse "")
  }
}