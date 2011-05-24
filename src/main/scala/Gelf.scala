package com.mojolly.logback

import java.net.InetAddress
import net.liftweb.json._
import JsonDSL._
import ch.qos.logback.classic.util.LevelToSyslogSeverity
import collection.JavaConversions._
import ch.qos.logback.core.AppenderBase
import reflect.{BeanProperty}
import ch.qos.logback.core.LayoutBase
import ch.qos.logback.classic.spi.ILoggingEvent
import java.nio.channels.DatagramChannel
import java.util.zip.GZIPOutputStream
import java.nio.ByteBuffer
import java.net._
import java.io.{IOException, ByteArrayOutputStream}
import java.util.concurrent.atomic.AtomicInteger
import java.security.MessageDigest
import java.nio.charset.Charset

trait GraylogTransport {
  def send(gelfJsonMessage: String)
}

class GelfLayout[E] extends LayoutBase[E] {
  @BeanProperty var facility: String = _
  @BeanProperty var useLoggerName: Boolean = true
  @BeanProperty var useThreadId: Boolean = true
  @BeanProperty var additionalFields = Map.empty[String, String]
  @BeanProperty var shortMessageLength: Int = 100

  def doLayout(p1: E) = {
    Gelf.shortMessageLength = shortMessageLength
    Gelf.GelfMessage(facility, p1.asInstanceOf[ILoggingEvent], additionalFields, useThreadId, useLoggerName).toJson
  }
}

class GelfAppender[E] extends AppenderBase[E] {

  var gelfLayout: GelfLayout[E] = _
  var udpTransport: GraylogTransport = _

  @BeanProperty var facility: String = "GELF"
  @BeanProperty var graylogHost = "localhost"
  @BeanProperty var graylogPort = 12201
  @BeanProperty var useLoggerName = true
  @BeanProperty var useThreadId = true
  @BeanProperty var additionalFields = Map.empty[String, String]
  @BeanProperty var shortMessageLength = 255
  @BeanProperty var maxChunkSize = 8154


  override def start() {
    super.start()
    gelfLayout = new GelfLayout[E]
    gelfLayout.shortMessageLength = shortMessageLength
    gelfLayout.useLoggerName = useLoggerName
    gelfLayout.useThreadId = useThreadId
    gelfLayout.additionalFields = additionalFields
    gelfLayout.facility = facility

    udpTransport = new Gelf.GelfUdpTransport(graylogHost, graylogPort, maxChunkSize)
  }

  def append(p1: E) {
    try {
      udpTransport send gelfLayout.doLayout(p1)
    } catch {
      case e => addError("Error occurred: ", e)
    }
  }
}

object Gelf {

  val Version = "1.0".intern
  val UTF_8 = "UTF-8"
  val Utf8 = Charset.forName(UTF_8)
  

  implicit var formats = DefaultFormats

  private class ConvertingByteArray(arr: Array[Byte]) {

    def asInt = {
      (0 until 4).foldLeft(0)((r, i) => (r << 8) - Byte.MinValue + arr(i).toInt)
    }

    def asShort : Short = ((((arr(0).toShort - Byte.MinValue.toShort) << 8).toShort - Byte.MinValue.toShort).toShort + arr(1).toShort).toShort
  }
  private class ByteConvertibleInt(value: Int) {
    private var inter = value
    def createVal() = {
      val r = (0xFFl & value) + Byte.MinValue
      inter >>>= 8
      r.toByte
    }
    def asBytes = (createVal() :: createVal() :: createVal() :: createVal() :: Nil).reverse.toArray
  }
  private class ByteConvertibleShort(value: Short) {

    private var inter = value
    def createVal() = {
      val r = (0xFFl & value) + Byte.MinValue
      inter = (inter.toShort >>> 8.toShort).toShort
      r.toByte
    }
    def asBytes = (createVal() :: createVal() :: Nil).reverse.toArray
  }

  private implicit def ba2intshor(arr: Array[Byte]) = new ConvertingByteArray(arr)
  private implicit def int2ba(value: Int) = new ByteConvertibleInt(value)
  private implicit def short2ba(value: Short) = new ByteConvertibleShort(value)

  var shortMessageLength = 100

  object GelfMessage {
    def apply(
          facility: String,
          event: ILoggingEvent,
          additionalFields: Map[String, String],
          useThreadId: Boolean = true,
          useLoggerName: Boolean = true) = {
      val (short, full) = buildMessage(event)
      val firstLineOfStackTrace = event.getCallerData.headOption
      new GelfMessage(
        facility,
        LevelToSyslogSeverity.convert(event),
        short,
        full,
        getAdditionalFields(event, additionalFields, useThreadId, useLoggerName),
        firstLineOfStackTrace.map(_.getFileName),
        firstLineOfStackTrace.map(_.getLineNumber),
        timestamp = event.getTimeStamp)
    }

    private def buildMessage(event: ILoggingEvent) = {
      val message = event.getMessage
      val stackTrace = Option(event.getThrowableProxy).map(_.getStackTraceElementProxyArray.map(_.getSTEAsString).mkString("\n"))
      (if (message.length > shortMessageLength) message.substring(0, shortMessageLength) else message, stackTrace getOrElse message)
    }

    private def getAdditionalFields(
                  event: ILoggingEvent,
                  additionalFields: Map[String, String],
                  useThreadId: Boolean,
                  useLoggerName: Boolean) = {
      var res = Map[String, String]()
      if (useLoggerName) res += ("_logger_name" -> event.getLoggerName)
      if (useThreadId) res += ("_thread_id" -> event.getThreadName )

      Option(event.getMDCPropertyMap) foreach { mdc =>
        additionalFields.keys foreach { key =>
          Option(mdc.get(key)) foreach { res += additionalFields(key) -> _ }
        }
      }
      res
    }
  }
  class GelfMessage(
               facility: String,
               level: Int,
               shortMessage: String,
               fullMessage: String,
               additionalFields: Map[String, String] = Map.empty,
               filePath: Option[String] = None,
               lineNumber: Option[Int] = None,
               host: String = InetAddress.getLocalHost.getHostName,
               version: String = Version,
               timestamp: Long = System.currentTimeMillis()) {

    def toJson = {
      val start: JValue = ("facility" -> facility) ~
                          ("host" -> host) ~
                          ("level" -> level) ~
                          ("short_message" -> shortMessage) ~
                          ("full_message" -> fullMessage) ~
                          ("version" -> version) ~
                          ("timestamp" -> timestamp)

      val withFPath = filePath.foldLeft(start) { (jv, fp) => jv merge ("file" -> fp)  }
      val withLineNo = lineNumber.foldLeft(withFPath) { (jv, ln) => jv merge ("line" -> ln)  }
      val addition = Extraction.decompose(additionalFields) transform {
        case JField(name, value) if (!name.startsWith("_")) => JField("_" + name, value)
        case x => x
      }
      Printer.compact(render(withLineNo ++ addition))
    }
  }

  class GelfUdpTransport(graylogHost: String, graylogPort: Int, maxChunkSize: Int = 8154) extends GraylogTransport {

    val address = new InetSocketAddress(graylogHost, graylogPort)
    val nextId = new AtomicInteger(1)

    def send(gelfJsonMessage: String)  {
      val msg = gzip(gelfJsonMessage)
      if (msg.size > maxChunkSize) {
        sendChunked(msg)
      } else {
        udpSend(ByteBuffer.allocate(msg.length).put(msg))
      }
    }

    private def digest(in: String) = {
      val md = MessageDigest.getInstance("MD5")
      md.digest(in.getBytes(UTF_8))
    }

    private def sendChunked(msg: Array[Byte]) {
      val msgId = digest("%s-%s".format(System.currentTimeMillis(), nextId.getAndIncrement)).slice(0, 8)
      val totalChunks = ((msg.length / maxChunkSize)).toDouble.ceil.toInt
      val preamble = ByteBuffer.allocateDirect(14)
      (1 to totalChunks) foreach { it =>
        val no = it - 1
        preamble.put(30.byteValue()).put(15.byteValue).put(msgId).put(Array(
          (no >>> 8).toByte,
          no.toByte,
          (totalChunks >>> 8).toByte,
          totalChunks.toByte
        ))

        val (chunkSize, chunkEnd) = {
          if (msg.length < (it + maxChunkSize)) {
            (msg.length - (maxChunkSize * it), msg.length)
          } else {
            (maxChunkSize, maxChunkSize * it)
          }
        }
        val message = ByteBuffer.allocateDirect(14 + chunkSize)
        message.put(preamble)
        message.put(msg.drop(chunkEnd - chunkSize), 14, chunkSize )
        udpSend(message)
      }
    }

    private def udpSend(msg: ByteBuffer) {
      val channel = DatagramChannel.open
      try {
        channel.send(msg, address)
      } catch {
        case e: UnknownHostException => {
          System.err.println("Could not determine address for: %s" format graylogHost)
        }
        case e: IOException => {
          System.err.println("Error when sending data to [%s:%s]. %s".format(graylogHost, graylogPort, e.getMessage))
        }
      } finally {
        if (channel != null && channel.isOpen) channel.close()
      }
    }

    private def gzip(gelfJsonMessage: String) = {
      val out = new ByteArrayOutputStream()
      val gzipStream = new GZIPOutputStream(out)
      gzipStream.write(gelfJsonMessage.getBytes(Utf8))
      gzipStream.close()
      out.toByteArray
    }
  }

}