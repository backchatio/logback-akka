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

package mojolly.logback

import redis.clients.jedis.exceptions.JedisException
import redis.clients.jedis.{ Jedis, JedisPool }
import scala.reflect.BeanProperty
import java.util.Locale
import scala.util.matching.Regex
import collection.JavaConversions._
import collection.mutable
import collection.JavaConverters._
import ch.qos.logback.classic.spi.{ ILoggingEvent }
import net.liftweb.json._
import net.liftweb.json.JsonDSL._
import ch.qos.logback.core.{ Layout, LayoutBase, UnsynchronizedAppenderBase }
import org.scala_tools.time.Imports._
import org.joda.time.format.{ ISODateTimeFormat }

object LogstashRedisLayout {
  implicit var formats = DefaultFormats
}
class LogstashRedisLayout[E] extends LayoutBase[E] {
  import mojolly.logback.LogstashRedisLayout._
  private val TAG_REGEX: Regex = """(?iu)\B#([^,#=!\s./]+)([\s,.]|$)""".r

  @BeanProperty
  var applicationName: String = _

  def doLayout(p1: E) = {
    try {
      val event = p1.asInstanceOf[ILoggingEvent]
      val msg = event.getFormattedMessage
      val tags = parseTags(msg)
      val jv: JValue =
        ("@timestamp" -> new DateTime(event.getTimeStamp).toString(ISODateTimeFormat.dateTime.withZone(DateTimeZone.UTC))) ~
          ("@tags" -> tags) ~
          ("@type" -> "string") ~
          ("@source" -> event.getLoggerName) ~
          ("@message" -> event.getFormattedMessage)

      val fields = {
        exceptionFields(event) merge {
          val mdc = { if (event.getMDCPropertyMap == null) JNothing else Extraction.decompose(event.getMDCPropertyMap.asScala) }
          (mdc merge
            ("thread_name" -> event.getThreadName) ~
            ("level" -> event.getLevel.toString) ~
            ("application" -> applicationName))
        }
      }

      Printer.compact {
        render {
          val flds: JValue = ("@fields" -> fields)
          jv merge flds
        }
      }
    } catch {
      case e ⇒ {
        addError("There was a problem formatting the event:", e)
        ""
      }
    }
  }

  private def exceptionFields(event: ILoggingEvent): JValue = {
    if (event.getThrowableProxy == null) {
      JNothing
    } else {
      val th = event.getThrowableProxy
      val stea: Seq[StackTraceElement] = if (th.getStackTraceElementProxyArray != null) {
        th.getStackTraceElementProxyArray.map(_.getStackTraceElement)
      } else {
        List.empty[StackTraceElement]
      }
      ("error_message" -> th.getMessage) ~
        ("error" -> th.getClassName) ~
        ("stack_trace" -> (stea map { stl ⇒
          val jv: JValue =
            ("line" -> stl.getLineNumber) ~
              ("file" -> stl.getFileName) ~
              ("method_name" -> stl.getMethodName)
          jv
        }))
    }
  }

  private def parseTags(msg: String) = {
    TAG_REGEX.findAllIn(msg).matchData.map(_.group(1).toUpperCase(Locale.ENGLISH)).toSet
  }

}
class LogbackRedisAppender[E] extends UnsynchronizedAppenderBase[E] {

  @BeanProperty
  var host = "localhost"
  @BeanProperty
  var port = 6379
  @BeanProperty
  var database = 9
  @BeanProperty
  var queueName: String = _
  @BeanProperty
  var layout: Layout[E] = new LogstashRedisLayout[E]

  private var redisPool: JedisPool = _

  override def start() {
    super.start()
    redisPool = createPool
  }

  override def stop() {
    if (started) {
      try { Option(redisPool) foreach { _.destroy() } } catch { case _ ⇒ } // if you die do it quietly
    }
    super.stop()
  }

  def append(p1: E) {
    withRedis { redis ⇒
      val msg = layout.doLayout(p1)
      if (msg != null && !msg.trim.isEmpty) {
        redis.rpush(queueName, msg)
      } else {
        0L
      }
    }
  }

  def returnClient(client: Jedis) {
    try {
      redisPool.returnResource(client)
    } catch {
      case e: JedisException ⇒ {
        if (redisPool != null) { redisPool.destroy() }
        redisPool = createPool
      }
    }
  }

  def returnBrokenResource(client: Jedis) {
    try {
      redisPool.returnBrokenResource(client)
    } catch {
      case e: JedisException ⇒ {
        if (redisPool != null) { redisPool.destroy() }
        redisPool = createPool
      }
    }
  }

  private def createPool = new JedisPool(host, port)

  def createClient = try {
    val cl = redisPool.getResource
    if (!cl.isConnected) cl.connect()
    cl select database
    cl
  } catch {
    case e: JedisException ⇒ {
      addError("There was an error when creating the redis client in the logback appender", e)
      if (redisPool != null) { redisPool.destroy() }
      redisPool = createPool
      val sec = redisPool.getResource
      sec select database
      sec
    }
  }

  def withRedis[T](block: Jedis ⇒ T): T = {
    val client = createClient
    try {
      val res = block(client)
      returnClient(client)
      res
    } catch {
      case e: JedisException ⇒ {
        addInfo("Redis was disconnected, reconnecting...")
        withRedis(block)
      }
      case e ⇒ {
        addError("There was a problem using jedis", e)
        throw e
      }
    }
  }
}