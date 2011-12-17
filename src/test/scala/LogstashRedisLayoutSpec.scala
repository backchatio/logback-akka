/*
 * The MIT License (MIT)
 * Copyright (c) 2011 Mojolly Ltd.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software
 * and associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE
 * OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package mojolly.logback.tests

import org.specs2.Specification
import org.specs2.specification.Around
import org.specs2.execute.Result
import org.slf4j.helpers.MarkerIgnoringBase
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.core.util.StatusPrinter
import collection.JavaConversions._
import reflect.BeanProperty
import ch.qos.logback.core.{ Layout, AppenderBase }
import collection.mutable.ListBuffer
import org.slf4j.Logger
import ch.qos.logback.classic.LoggerContext
import net.liftweb.json._
import java.lang.RuntimeException
import org.specs2.matcher.MustMatchers
import java.util.concurrent.{CountDownLatch, ConcurrentSkipListSet, TimeUnit}

object StringListAppender {
  var messages = ListBuffer[String]()
  var latch: Option[CountDownLatch] = None
}
class StringListAppender[E] extends AppenderBase[E] {
  import StringListAppender._

  @BeanProperty
  var layout: Layout[E] = _

  override def start() {
    Option(layout).filter(_.isStarted).foreach(_ ⇒ super.start())
  }

  def append(p1: E) {
    messages += layout.doLayout(p1)
    latch foreach { _.countDown() }
  }
}

object StringListAppender2 {
  val messages = ListBuffer[String]()
  var latch: Option[CountDownLatch] = None
}
class StringListAppender2[E] extends AppenderBase[E] {
  import StringListAppender2._

  @BeanProperty
  var layout: Layout[E] = _

  override def start() {
    Option(layout).filter(_.isStarted).foreach(_ ⇒ super.start())
  }

  def append(p1: E) {
    messages += layout.doLayout(p1)
    latch foreach { _.countDown() }
  }
}
class LogstashRedisLayoutSpec extends Specification {
  def is =

    sequential ^
      "A logstash layout should" ^
      "render a regular log statement" ! withLogger("redis-logger-1").renderNormalLog ^
      "render a log statement with an exception" ! withLogger("redis-logger-2").renderExceptionLog ^ end

}

case class withLogger(loggerName: String) extends MustMatchers {

  implicit val formats = DefaultFormats
  var loggerContext: LoggerContext = _
  var logger: Logger = _
  val latch = new CountDownLatch(1)

  def around(t: ⇒ Result): Result = {
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
      (latch.await(2, TimeUnit.SECONDS) must beTrue) and {
        val js = JsonParser.parse(StringListAppender2.messages.head)
        (js \ "@message").extract[String] must_== ms
      }
    }
  }

  def renderExceptionLog = around {
    val ms = "this is a message for an error"
    try { throw new RuntimeException("the exception message") }
    catch { case e ⇒ logger.error(ms, e) }
    (latch.await(2, TimeUnit.SECONDS) must beTrue) and {
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

