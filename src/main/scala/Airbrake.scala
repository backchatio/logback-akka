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

import akka.actor._
import Actor._

import com.ning.http.client.filter.{ IOExceptionFilter, FilterContext, ResponseFilter }
import com.ning.http.client._
import akka.config.Config
import ch.qos.logback.classic.spi.ILoggingEvent
import reflect.BeanProperty
import ch.qos.logback.core.{ UnsynchronizedAppenderBase, LayoutBase }
import java.io.File
import collection.JavaConverters._
import ch.qos.logback.core.filter.Filter
import ch.qos.logback.classic.filter.ThresholdFilter
import org.scala_tools.time.Imports._
import mojolly.logback.Airbrake.{ Throttle, AirbrakeConfig }

object Airbrake {

  class RichVar(v: RequestSupport.Var) {
    def toXml = <val key={ v.key }>{ v.value }</val>
  }

  implicit def richVar(v: RequestSupport.Var) = new RichVar(v)

  class RichRequest(r: RequestSupport.Request) {
    def paramsXml = <params>{ r.params.map(_.toXml) }</params>
    def sessionsXml = <sessions>{ r.sessions.map(_.toXml) }</sessions>
    def cgiDataXml = <cgi-data>{ r.cgi_data.map(_.toXml) }</cgi-data>

    def toXml =
      <request>
        <url>{ r.url }</url>
        <component>{ r.component }</component>
        { if (r.params.length > 0) paramsXml }
        { if (r.sessions.length > 0) sessionsXml }
        { if (r.cgi_data.length > 0) cgiDataXml }
      </request>
  }

  implicit def richRequest(r: RequestSupport.Request) = new RichRequest(r)

  class RichBacktrace(b: BacktraceSupport.Backtrace) {
    def toXml = <line method={ b.methodName } file={ b.file } number={ b.number.toString }></line>
  }

  implicit def richBacktrace(r: BacktraceSupport.Backtrace) = new RichBacktrace(r)

  class MojollyDuration(duration: Duration) {
    def doubled = (duration.millis * 2).toDuration
    def max(upperBound: Duration) = if (duration > upperBound) upperBound else duration
  }

  implicit def duration2mojollyDuration(dur: Duration) = new MojollyDuration(dur)

  case class AirbrakeConfig(
    applicationName: String,
    applicationVersion: String,
    applicationUrl: String,
    apiKey: String,
    useSsl: Boolean,
    userAgent: String,
    socketTimeout: Int,
    environmentName: String,
    projectRoot: Option[String] = None)

  object AirbrakeNotice {

    def apply(config: AirbrakeConfig, evt: ILoggingEvent) = {
      val backtrace = BacktraceSupport.Backtraces(evt)
      val request = RequestSupport.Request(config.applicationName, evt)
      new AirbrakeNotice(
        config,
        evt.getLoggerName,
        evt.getMessage + "\n" + evt.getThrowableProxy.getMessage,
        backtrace,
        request)
    }
  }

  case class AirbrakeNotice(
      config: AirbrakeConfig, clazz: String, message: String, backtraces: Seq[BacktraceSupport.Backtrace], request: Option[RequestSupport.Request] = None) {

    def toXml =
      <notice version="2.0">
        <api-key>{ config.apiKey }</api-key>
        <notifier>
          <name>{ config.applicationName }</name>
          <version>{ config.applicationVersion }</version>
          <url>{ config.applicationUrl }</url>
        </notifier>
        <error>
          <class>{ clazz }</class>
          <message>{ message }</message>
          <backtrace>{ backtraces.map(_.toXml) }</backtrace>
        </error>
        { request.map(_.toXml).getOrElse(scala.xml.Comment("No request was set")) }
        <server-environment>
          { config.projectRoot.map(x ⇒ <project-root>{ x }</project-root>).getOrElse(scala.xml.Comment("No project root was set")) }
          <environment-name>{ config.environmentName }</environment-name>
        </server-environment>
      </notice>

  }

  /**
   * Throttle
   *
   * This class implements a waiting strategy when errors arise
   */
  case class Throttle(delay: Duration, maxWait: Duration) {

    def apply() = {
      Thread sleep delay.millis
      this.copy(delay = delay.doubled max maxWait)
    }
  }

}

class AirbrakeNotice[E] extends LayoutBase[E] {
  var config: AirbrakeConfig = _
  def doLayout(p1: E) = {
    Airbrake.AirbrakeNotice(config, p1.asInstanceOf[ILoggingEvent]).toXml.toString
  }
}

class AirbrakeAppender[E] extends UnsynchronizedAppenderBase[E] {
  @BeanProperty
  var apiKey: String = null
  @BeanProperty
  var useSsl: Boolean = false
  @BeanProperty
  var userAgent: String = "AirbrakeClient/1.0 (compatible; Mozilla/5.0; AsyncHttpClient +http://mojolly.com)"
  @BeanProperty
  var socketTimeout: Int = 1.minute.millis.toInt
  @BeanProperty
  var applicationName = "Mojolly Airbrake Notifier"
  @BeanProperty
  var applicationVersion = {
    val implVersion = getClass.getPackage.getImplementationVersion
    if (implVersion == null || implVersion.trim().isEmpty) "0.0.1" else implVersion
  }
  @BeanProperty
  var applicationUrl = "https://backchat.io"

  private implicit def thf2fe(f: ThresholdFilter) = f.asInstanceOf[Filter[E]]
  private val filter = new ThresholdFilter()
  filter.setLevel("ERROR")
  addFilter(filter)

  var environmentName: String = null
  var layout: AirbrakeNotice[E] = null
  private var _projectRoot: Option[String] = None

  private val httpConfig = new AsyncHttpClientConfig.Builder()
    .addIOExceptionFilter(throttleTcp)
    .addResponseFilter(throttleHttp)
    .setUserAgent(userAgent)
    .setConnectionTimeoutInMs(socketTimeout)
    .setMaximumConnectionsTotal(1)
    .setMaxRequestRetry(3)
    .build

  private lazy val http = new AsyncHttpClient(httpConfig)

  val airbrakeUrl = "http%s://hoptoadapp.com/notifier_api/v2/notices" format (if (useSsl) "s" else "")

  override def stop() {
    super.stop()
    if (filter != null && filter.isStarted) filter.stop()
    if (http != null) {
      http.close()
    }
  }

  override def start() {
    if (apiKey == null || apiKey.trim.isEmpty) {
      addError("You have to provide an api key for airbrake in the config")
      throw new RuntimeException("Missing Airbrake API key in logback config.")
    }
    if (!filter.isStarted) filter.start()
    _projectRoot = {
      val pr = Config.HOME getOrElse (new File(".").getCanonicalPath)
      if (pr == null || pr.trim.isEmpty) {
        None
      } else Some(pr)
    }
    initEnvironment
    layout = new AirbrakeNotice[E]
    super.start()
  }

  private def initEnvironment = {
    environmentName = if (LogbackActor.environment == null || LogbackActor.environment.trim.isEmpty) {
      if (environmentName == null || environmentName.trim.isEmpty) {
        LogbackActor.readEnvironmentKey(addWarn _)
      } else {
        environmentName
      }
    } else LogbackActor.environment
    LogbackActor.environment = environmentName
    environmentName
  }

  private def projectRoot: Option[String] = _projectRoot

  def append(p1: E) {
    layout.config = AirbrakeConfig(
      applicationName, applicationVersion, applicationName, apiKey,
      useSsl, userAgent, socketTimeout, environmentName, projectRoot)
    http.preparePost(airbrakeUrl).addHeader("Content-Type", "text/xml").setBody(layout.doLayout(p1)).execute
  }

  protected def throttleHttp = {
    new ResponseFilter {
      private val throttle = Throttle(10.seconds, 4.minutes)
      def filter(context: FilterContext[_]): FilterContext[_] = {
        addInfo("Filtering response for status: %s".format(context.getResponseStatus.getStatusCode))

        if (context.getResponseStatus == null || context.getResponseStatus.getStatusCode > 200) {
          throttle()
          new FilterContext.FilterContextBuilder(context).request(context.getRequest).replayRequest(true).build
        } else context
      }
    }
  }

  protected def throttleTcp = {
    new IOExceptionFilter {
      private val throttle = Throttle(250.millis, 16.seconds)
      def filter(context: FilterContext[_]): FilterContext[_] = {
        addInfo("Filtering IOException: %s" format context.getResponseStatus, context.getIOException)
        if (context.getIOException != null) {
          throttle()
          new FilterContext.FilterContextBuilder(context).request(context.getRequest).replayRequest(true).build
        } else context
      }
    }
  }
}