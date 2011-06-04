package com.mojolly.logback.tests

import org.specs2.Specification
import org.specs2.specification.Around
import org.specs2.execute.Result
import org.slf4j.helpers.MarkerIgnoringBase
import org.multiverse.api.latches.StandardLatch
import com.mojolly.logback.StringListAppender
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.core.util.StatusPrinter
import collection.JavaConversions._
import java.util.concurrent.{ConcurrentSkipListSet, TimeUnit}
import reflect.BeanProperty
import ch.qos.logback.core.{Layout, AppenderBase}
import collection.mutable.ListBuffer
import org.slf4j.Logger
import ch.qos.logback.classic.LoggerContext
import net.liftweb.json._
import java.lang.RuntimeException

object StringListAppender2 {
  val messages = ListBuffer[String]()
  var latch: Option[StandardLatch] = None
}
class StringListAppender2[E] extends AppenderBase[E] {
  import StringListAppender2._

  @BeanProperty var layout: Layout[E] = _

  override def start() {
    Option(layout).filter(_.isStarted).foreach(_ => super.start())
  }

  def append(p1: E) {
    messages += layout.doLayout(p1)
    latch foreach { _.open() }
  }
}
class LogstashRedisLayoutSpec extends Specification { def is =

  sequential ^
  "A logstash layout should" ^
    "render a regular log statement" ! withLogger("redis-logger-1").renderNormalLog ^
    "render a log statement with an exception" ! withLogger("redis-logger-2").renderExceptionLog ^ end


  case class withLogger(loggerName: String) {

    implicit val formats = DefaultFormats
    var loggerContext: LoggerContext = _
    var logger: Logger = _
    val latch = new StandardLatch

    def around(t: => Result): Result = {
      loggerContext = new LoggerContext
      val configUrl = getClass.getClassLoader.getResource("redis-layout-spec.xml")
      StringListAppender2.messages.clear
      val cf = new JoranConfigurator
      cf.setContext(loggerContext)
      cf.doConfigure(configUrl)
      loggerContext.start()
      logger = loggerContext.getLogger(loggerName)
      StatusPrinter.printIfErrorsOccured(loggerContext)
      StringListAppender2.latch = Some(latch)
      val res: Result = t
      loggerContext.stop()
      StatusPrinter.printIfErrorsOccured(loggerContext)
      res
    }

    def renderNormalLog = {
      around {
        val ms = "this is a a message with a [fabricated] param"
        logger.info(ms)
        (latch.tryAwait(2, TimeUnit.SECONDS) must beTrue) and {
          val js = JsonParser.parse(StringListAppender2.messages.head)
          (js \ "@message").extract[String] must_== ms
        }
      }
    }

    def renderExceptionLog = around {
      val ms = "this is a message for an error"
      try { throw new RuntimeException("the exception message") }
      catch { case e => logger.error(ms, e) }
      (latch.tryAwait(2, TimeUnit.SECONDS) must beTrue) and {
        val js = JsonParser.parse(StringListAppender2.messages.head)
        val msgMatch = (js \ "@message").extract[String] must_== ms
        val err = (js \ "@fields")
        msgMatch and {
          ((err \ "error_message").extract[String] must_== "the exception message") and {
            (err \ "stack_trace").extract[List[JValue]] must not(beEmpty)
          }
        }

      }
    }
  }
}