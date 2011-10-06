package mojolly.logback

import ch.qos.logback.classic.spi.ILoggingEvent
import collection.JavaConverters._

trait BacktraceSupport {

  case class Backtrace(file: String, number: Int, methodName: String)

  object Backtraces {
    def apply(evt: ILoggingEvent): List[Backtrace] = evt.getCallerData.toList map { stl ⇒ Backtrace(stl.getFileName, stl.getLineNumber, stl.getMethodName) }
  }

}

object BacktraceSupport extends BacktraceSupport