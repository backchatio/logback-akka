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

package com.mojolly.logback

import akka.actor._
import Actor._

import com.ning.http.client.filter.{IOExceptionFilter, FilterContext, ResponseFilter}
import com.ning.http.client._
import akka.config.Config
import ch.qos.logback.classic.spi.ILoggingEvent
import reflect.BeanProperty
import akka.util.Duration
import akka.util.duration._
import java.util.concurrent.atomic.AtomicBoolean
import ch.qos.logback.core.{UnsynchronizedAppenderBase, LayoutBase}
import java.io.File
import java.net.URLDecoder
import collection.JavaConverters._
import com.mojolly.logback.Hoptoad.{Throttle, HoptoadConfig}
import ch.qos.logback.core.filter.Filter
import ch.qos.logback.classic.filter.ThresholdFilter

object Hoptoad {

  val REQUEST_PATH = "REQUEST_PATH"
  val REQUEST_APP = "REQUEST_APP"  // maps to a scalatra servlet
  val REQUEST_PARAMS = "REQUEST_PARAMS"
  val SESSION_PARAMS = "SESSION_PARAMS"
  val CGI_PARAMS = "CGI_PARAMS"

  case class Backtrace(file: String, number: Int, methodName: String) {
    def toXml = <line method={methodName} file={file} number={number.toString}></line>
  }

  object Var {
    def apply(pairs: String): List[Var] = {
      if (pairs == null || pairs.trim.isEmpty) Nil
      else {
        val mapped = if (pairs.indexOf('&') > -1) {
          pairs.split('&').foldRight(Map.empty[String, List[String]]) { readQsPair _ }
        } else {
          readQsPair(pairs)
        }
        mapped map { case (k, v) => Var(k, v.mkString(", ")) } toList
      }
    }

    private def readQsPair(pair: String, current: Map[String, List[String]] = Map.empty) = {
      (pair split '=' toList) map { s => if(s != null && !s.trim.isEmpty) URLDecoder.decode(s, "UTF-8") else "" } match {
        case item :: Nil => current + (item -> List[String]())
        case item :: rest =>
          if(!current.contains(item)) current + ( item -> rest ) else (current + (item -> (rest ::: current(item)).distinct))
        case _ => current
      }
    }
  }

  case class  Var(key: String, value: String) {
    def toXml = <val key={key}>{value}</val>
  }

  case class Request(url: String, component: String,
              params: List[Var] = Nil, sessions: List[Var] = Nil,
              cgi_data: List[Var] = Nil) {

    def paramsXml = <params>{params.map(_.toXml)}</params>
    def sessionsXml = <sessions>{sessions.map(_.toXml)}</sessions>
    def cgiDataXml = <cgi-data>{cgi_data.map(_.toXml)}</cgi-data>

    def toXml =
      <request>
        <url>{url}</url>
        <component>{component}</component>
        {if (params.length > 0) paramsXml}
        {if (sessions.length > 0) sessionsXml}
        {if (cgi_data.length > 0) cgiDataXml}
      </request>
  }
  case class HoptoadConfig(
          applicationName: String,
          applicationVersion: String,
          applicationUrl: String,
          apiKey: String,
          useSsl: Boolean,
          userAgent: String,
          socketTimeout: Int,
          environmentName: String,
          projectRoot: Option[String] = None)

  object HoptoadNotice {

    def apply(config: HoptoadConfig, evt: ILoggingEvent) = {
      val backtrace = readBacktraces(evt)
      val request = readRequest(config, evt)
      new HoptoadNotice(
        config,
        evt.getLoggerName,
        evt.getMessage + "\n" + evt.getThrowableProxy.getMessage,
        backtrace,
        request)
    }

    private def readRequest(config: HoptoadConfig, evt: ILoggingEvent): Option[Request] = {
      val mdc = evt.getMdc.asScala
      mdc.get(REQUEST_PATH) map { path =>
        Request(
          path,
          mdc.get(REQUEST_APP) getOrElse config.applicationName,
          Var(mdc.get(REQUEST_PARAMS).orNull),
          Var(mdc.get(SESSION_PARAMS).orNull),
          Var(mdc.get(CGI_PARAMS).orNull))
      }
    }

    private def readBacktraces(evt: ILoggingEvent) = {
      evt.getCallerData map { stl => Backtrace(stl.getFileName, stl.getLineNumber, stl.getMethodName) }
    }
  }

  case class HoptoadNotice(
               config: HoptoadConfig, clazz: String, message: String, backtraces: Seq[Backtrace], request: Option[Request] = None) {

    def toXml =
      <notice version="2.0">
        <api-key>{config.apiKey}</api-key>
        <notifier>
          <name>{config.applicationName}</name>
          <version>{config.applicationVersion}</version>
          <url>{config.applicationUrl}</url>
        </notifier>
        <error>
          <class>{clazz}</class>
          <message>{message}</message>
          <backtrace>{backtraces.map(_.toXml)}</backtrace>
        </error>
        {request.map(_.toXml).getOrElse(scala.xml.Comment("No request was set"))}
        <server-environment>
          {config.projectRoot.map(x => <project-root>{x}</project-root>).getOrElse(scala.xml.Comment("No project root was set"))}
          <environment-name>{config.environmentName}</environment-name>
        </server-environment>
      </notice>

  }

  /**
   * Throttle
   *
   * This class implements a waiting strategy when errors arise
   */
  case class Throttle(delay: Duration, maxWait: Duration) {

    private var throttleInterval = delay
    private val _isAtMax = new AtomicBoolean(false)

    def isAtMax = _isAtMax.get()

    def throttle = {
      Thread.sleep(throttleInterval.toMillis)

      // Double the interval to give some more time to recover
      throttleInterval = throttleInterval + throttleInterval

      // Don't wait longer than the maxWait
      if(throttleInterval >= maxWait) {
        _isAtMax.set(true)
        throttleInterval = maxWait
      }
    }

    def reset() {
      throttleInterval = delay
      _isAtMax.set(false)
    }
  }

}
class HoptoadLayout[E] extends LayoutBase[E] {

  var config: HoptoadConfig = _
  def doLayout(p1: E) = {
    Hoptoad.HoptoadNotice(config, p1.asInstanceOf[ILoggingEvent]).toXml.toString
  }
}

class HoptoadAppender[E] extends UnsynchronizedAppenderBase[E] {

  @BeanProperty var apiKey: String = null
  @BeanProperty var useSsl: Boolean = false
  @BeanProperty var userAgent: String = "HoptoadClient/1.0 (compatible; Mozilla/5.0; AsyncHttpClient +http://mojolly.com)"
  @BeanProperty var socketTimeout: Int = 1.minute.toMillis.toInt
  @BeanProperty var applicationName = "Mojolly Hoptoad Notifier"
  @BeanProperty var applicationVersion = {
    val implVersion = getClass.getPackage.getImplementationVersion
    if(implVersion == null || implVersion.trim().isEmpty) "0.0.1" else implVersion
  }
  @BeanProperty var applicationUrl = "https://backchat.io"

  private implicit def thf2fe(f: ThresholdFilter) = f.asInstanceOf[Filter[E]]
  private val filter = new ThresholdFilter()
  filter.setLevel("ERROR")
  addFilter(filter)

  var environmentName: String = null
  var layout: HoptoadLayout[E] = null
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

  val hoptoadUrl = "http%s://hoptoadapp.com/notifier_api/v2/notices" format (if (useSsl) "s" else "")

  override def stop() {
    super.stop()
    if(filter != null && filter.isStarted) filter.stop()
    if(http != null) {
      http.close()
    }
  }

  override def start() {
    if (apiKey == null || apiKey.trim.isEmpty) {
      addError("You have to provide an api key for hoptoad in the config")
      throw new RuntimeException("Missing Hoptoad API key in logback config.")
    }
    if (!filter.isStarted) filter.start()
    _projectRoot = {
      val pr = Config.HOME getOrElse (new File(".").getCanonicalPath)
      if (pr == null || pr.trim.isEmpty) {
        None
      } else Some(pr)
    }
    initEnvironment
    layout = new HoptoadLayout[E]
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
    layout.config = HoptoadConfig(
      applicationName, applicationVersion, applicationName, apiKey,
      useSsl, userAgent, socketTimeout, environmentName, projectRoot)
    http.preparePost(hoptoadUrl).addHeader("Content-Type", "text/xml").setBody(layout.doLayout(p1)).execute
  }

  protected def throttleHttp = {
    new ResponseFilter {
      private val throttle = Throttle(10.seconds, 4.minutes)
      def filter(context: FilterContext[_]): FilterContext[_] = {
        addInfo("Filtering response for status: %s".format(context.getResponseStatus.getStatusCode))

        if(context.getResponseStatus == null || context.getResponseStatus.getStatusCode > 200) {
          throttle.throttle
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
        if(context.getIOException != null) {
          throttle.throttle
          new FilterContext.FilterContextBuilder(context).request(context.getRequest).replayRequest(true).build
        } else context
      }
    }
  }
}