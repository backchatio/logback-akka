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

import javax.servlet.http.{ HttpServletResponse, HttpServletRequest }
import org.scalatra.{ ScalatraKernel, Handler, MatchedRoute }
import ScalatraKernel.MultiParamsKey
import org.scalatra.util.MultiMap
import org.slf4j.MDC
import java.net.URLEncoder
import com.weiglewilczek.slf4s.Logging
import collection.JavaConversions._
import java.util.{ Map ⇒ JMap }
object ScalatraLogbackSupport {

  val CgiParamsKey = "com.mojolly.logback.ScalatraLogbackSupport"
}

trait ScalatraLogbackSupport extends Handler with Logging { self: ScalatraKernel ⇒

  import ScalatraLogbackSupport.CgiParamsKey

  abstract override def handle(req: HttpServletRequest, res: HttpServletResponse) {
    val realMultiParams = req.getParameterMap.asInstanceOf[JMap[String, Array[String]]].toMap transform { (k, v) ⇒ v: Seq[String] }
    withRequest(req) {
      request(MultiParamsKey) = MultiMap(Map() ++ realMultiParams)
      request(CgiParamsKey) = readCgiParams(req)
      fillMdc()
      super.handle(req, res)
      MDC.clear()
    }
  }

  override protected def withRouteMultiParams[S](matchedRoute: Option[MatchedRoute])(thunk: ⇒ S): S = {
    val originalParams = multiParams
    request(ScalatraKernel.MultiParamsKey) = originalParams ++ matchedRoute.map(_.multiParams).getOrElse(Map.empty)
    fillMdc()
    try { thunk } finally { request(ScalatraKernel.MultiParamsKey) = originalParams }
  }

  protected def fillMdc() { // Do this twice so that we get all the route params if they are available and applicable
    MDC.clear()
    MDC.put(RequestSupport.REQUEST_PATH, requestPath)
    MDC.put(RequestSupport.REQUEST_APP, getClass.getSimpleName)
    MDC.put(RequestSupport.REQUEST_PARAMS, multiParams map { case (k, vl) ⇒ vl.map(v ⇒ "%s=%s".format(%-(k), %-(v))) } mkString "&")
    MDC.put(RequestSupport.SESSION_PARAMS, session map { case (k, v) ⇒ "%s=%s".format(%-(k), %-(v.toString)) } mkString "&")
    MDC.put(RequestSupport.CGI_PARAMS, cgiParams map { case (k, v) ⇒ "%s=%s".format(%-(k), %-(v)) } mkString "&")
  }

  def cgiParams = request get CgiParamsKey map (_.asInstanceOf[Map[String, String]]) getOrElse Map.empty

  private def readCgiParams(req: HttpServletRequest) = Map(
    "AUTH_TYPE" -> req.getAuthType,
    "CONTENT_LENGTH" -> req.getContentLength.toString,
    "CONTENT_TYPE" -> req.getContentType,
    "DOCUMENT_ROOT" -> applicationContext.getRealPath(applicationContext.getContextPath),
    "PATH_INFO" -> req.getPathInfo,
    "PATH_TRANSLATED" -> req.getPathTranslated,
    "QUERY_STRING" -> req.getQueryString,
    "REMOTE_ADDR" -> req.getRemoteAddr,
    "REMOTE_HOST" -> req.getRemoteHost,
    "REMOTE_USER" -> req.getRemoteUser,
    "REQUEST_METHOD" -> req.getMethod,
    "SCRIPT_NAME" -> req.getServletPath,
    "SERVER_NAME" -> req.getServerName,
    "SERVER_PORT" -> req.getServerPort.toString,
    "SERVER_PROTOCOL" -> req.getProtocol,
    "SERVER_SOFTWARE" -> applicationContext.getServerInfo)

  private def %-(s: String) = if (s == null || s.trim.isEmpty) "" else URLEncoder.encode(s, "UTF-8")
}
