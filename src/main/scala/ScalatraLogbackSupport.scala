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

import javax.servlet.http.{HttpServletResponse, HttpServletRequest}
import org.scalatra.{ScalatraKernel, Handler}
import util.DynamicVariable
import org.slf4j.MDC
import java.net.URLEncoder
import com.weiglewilczek.slf4s.Logging

trait ScalatraLogbackSupport extends Handler with Logging { self: ScalatraKernel =>

  protected val _cgiParams = new DynamicVariable[Map[String, String]](Map.empty)

  abstract override def handle(req: HttpServletRequest, res: HttpServletResponse) {
    _request.withValue(req) {
      _response.withValue(res) {
        _cgiParams.withValue(readCgiParams) {
          fillMdc()
          super.handle(req, res)
          MDC.clear()
        }
      }
    }
  }

  protected def fillMdc() {
    MDC.put(Hoptoad.REQUEST_PATH, requestPath)
    MDC.put(Hoptoad.REQUEST_APP, getClass.getSimpleName)
    MDC.put(Hoptoad.REQUEST_PARAMS, multiParams flatMap {
      case (k, vl) => vl.map(v => "%s=%s".format(%-(k), %-(v)))
    } mkString "&")
    MDC.put(Hoptoad.SESSION_PARAMS, session map { case (k, v) => "%s=%s".format(%-(k), %-(v.toString)) } mkString "&")
    MDC.put(Hoptoad.CGI_PARAMS, cgiParams map { case (k, v) => "%s=%s".format(%-(k), %-(v)) } mkString "&")
  }

  def cgiParams = _cgiParams.value

  private def readCgiParams =  Map(
    "AUTH_TYPE" -> request.getAuthType,
    "CONTENT_LENGTH" -> request.getContentLength.toString,
    "CONTENT_TYPE" -> request.getContentType,
    "DOCUMENT_ROOT" -> servletContext.getRealPath(servletContext.getContextPath),
    "PATH_INFO" -> request.getPathInfo,
    "PATH_TRANSLATED" -> request.getPathTranslated,
    "QUERY_STRING" -> request.getQueryString,
    "REMOTE_ADDR" -> request.getRemoteAddr,
    "REMOTE_HOST" -> request.getRemoteHost,
    "REMOTE_USER" -> request.getRemoteUser,
    "REQUEST_METHOD" -> request.getMethod,
    "SCRIPT_NAME" -> request.getServletPath,
    "SERVER_NAME" -> request.getServerName,
    "SERVER_PORT" -> request.getServerPort.toString,
    "SERVER_PROTOCOL" -> request.getProtocol,
    "SERVER_SOFTWARE" -> servletContext.getServerInfo
  )

  private def %-(s: String) = if (s == null || s.trim.isEmpty) "" else URLEncoder.encode(s, "UTF-8")
}