package mojolly.logback

import collection.JavaConverters._
import java.net.URLDecoder
import ch.qos.logback.classic.spi.ILoggingEvent

trait RequestSupport {

  val REQUEST_PATH = "REQUEST_PATH"
  val REQUEST_APP = "REQUEST_APP" // maps to a scalatra servlet
  val REQUEST_PARAMS = "REQUEST_PARAMS"
  val SESSION_PARAMS = "SESSION_PARAMS"
  val CGI_PARAMS = "CGI_PARAMS"

  object Var {
    def apply(pairs: String): List[Var] = {
      if (pairs == null || pairs.trim.isEmpty) Nil
      else {
        val mapped = if (pairs.indexOf('&') > -1) {
          pairs.split('&').foldRight(Map.empty[String, List[String]]) { readQsPair _ }
        } else {
          readQsPair(pairs)
        }
        mapped map { case (k, v) ⇒ Var(k, v.mkString(", ")) } toList
      }
    }

    private def readQsPair(pair: String, current: Map[String, List[String]] = Map.empty) = {
      (pair split '=' toList) map { s ⇒ if (s != null && !s.trim.isEmpty) URLDecoder.decode(s, "UTF-8") else "" } match {
        case item :: Nil ⇒ current + (item -> List[String]())
        case item :: rest ⇒
          if (!current.contains(item)) current + (item -> rest) else (current + (item -> (rest ::: current(item)).distinct))
        case _ ⇒ current
      }
    }
  }

  case class Var(key: String, value: String)

  case class Request(url: String, component: String,
                     params: List[Var] = Nil, sessions: List[Var] = Nil,
                     cgi_data: List[Var] = Nil)

  object Request {
    def apply(appName: String, evt: ILoggingEvent): Option[Request] = {
      val mdc = evt.getMdc.asScala
      mdc.get(REQUEST_PATH) map { path ⇒
        Request(
          path,
          mdc.get(REQUEST_APP) getOrElse appName,
          Var(mdc.get(REQUEST_PARAMS).orNull),
          Var(mdc.get(SESSION_PARAMS).orNull),
          Var(mdc.get(CGI_PARAMS).orNull))
      }
    }
  }

}

object RequestSupport extends RequestSupport