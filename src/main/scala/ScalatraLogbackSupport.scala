package com.mojolly.logback

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
    "DOCUMENT_ROOT" -> request.getServletContext.getRealPath("/"),
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
    "SERVER_SOFTWARE" -> request.getServletContext.getServerInfo
  )

  private def %-(s: String) = URLEncoder.encode(s, "UTF-8")
}