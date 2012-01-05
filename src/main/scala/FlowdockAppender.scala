package mojolly.logback

import BacktraceSupport._
import RequestSupport._
import collection.JavaConverters._
import reflect.BeanProperty
import com.ning.http.client.{ AsyncHttpClient, AsyncHttpClientConfig }
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.{ Layout, LayoutBase, UnsynchronizedAppenderBase }
import ch.qos.logback.core.spi.ContextAwareBase
import ch.qos.logback.core.spi.LifeCycle
import net.liftweb.json.{ NoTypeHints, Serialization }

object FlowdockAppender {
  import net.liftweb.json.Serialization._
  implicit val formats = Serialization.formats(NoTypeHints)
  val Url = "https://api.flowdock.com/v1/"

  class RichVar(v: RequestSupport.Var) {
    def toHtml = <tr><td>{ v.key }</td><td>{ v.value }</td></tr>
  }

  implicit def richVar(v: RequestSupport.Var) = new RichVar(v)

  class RichRequest(r: Request) {
    def table(vars: List[Var]) = <table>
                                   { for (v ← vars) yield <tr><td>{ v.key }</td><td>{ v.value }</td></tr> }
                                 </table>
    def paramsHtml = <h3>Params</h3> ++: table(r.params)
    def sessionHtml = <h3>Session</h3> ++: table(r.sessions)
    def cgiDataHtml = <h3>CGI</h3> ++: table(r.cgi_data)
    def toHtml = paramsHtml ++: sessionHtml ++: cgiDataHtml
  }

  implicit def richRequest(r: Request) = new RichRequest(r)

  class RichBacktrace(b: Backtrace) {
    def toHtml = <span>{ b.methodName }</span><br/>
  }

  implicit def richBacktrace(r: Backtrace) = new RichBacktrace(r)

  case class FlowdockMessage(source: String, fromName: String, fromAddress: String, subject: String, content: String) {
    def toJson = write(this)
  }

  object FlowdockMessage {
    def apply(event: String): FlowdockMessage = read[FlowdockMessage](event)
  }

  trait Validator extends LifeCycle {
    self: ContextAwareBase ⇒

    def params: Map[String, String]

    def validate(params: Map[String, String]) = {
      var valid = true
      def error(p: String) = {
        valid = false
        self.addError("You have to provide the '%s' param." format p)
      }
      params.foreach { case (k, v) ⇒ if (v == null || v.trim.length == 0) error(k) }
      valid
    }

    abstract override def start = {
      if (!validate(params)) throw new RuntimeException("Missing parameters in Logback config.")
      super.start
    }
  }
}

/** Simple layout */
class FlowdockLayout[E] extends LayoutBase[E] with FlowdockAppender.Validator {
  import FlowdockAppender._

  @BeanProperty
  var source: String = null
  @BeanProperty
  var fromName: String = null
  @BeanProperty
  var fromAddress: String = null

  def params = Map("source" -> source, "fromName" -> fromName, "fromAddress" -> fromAddress)

  protected def handleLayout: PartialFunction[E, FlowdockMessage] = { case e: ILoggingEvent ⇒ Message(e) }
  def doLayout(event: E) = handleLayout.lift(event) map (_.toJson) getOrElse ""

  object Message {
    def apply(e: ILoggingEvent) = {
      val backtraces = Backtraces(e)
      val request = Request("", e)
      val content = <html>
                      <p>{ e.getMessage }</p>
                      { backtraces.map(_.toHtml) }
                      { request.map(_.toHtml).getOrElse(scala.xml.Comment("No request was set")) }
                    </html>
      FlowdockMessage(source, fromName, fromAddress, "%s: %s" format (e.getLevel, e.getLoggerName), content.toString())
    }

    def traceToHtml(t: List[Backtrace]) = if (t.isEmpty) None else Some(<h3>Trace</h3> :: (t map (t ⇒ <span>{ t.methodName }</span><br/>)))
  }
}

class FlowdockAppender[E] extends UnsynchronizedAppenderBase[E] with FlowdockAppender.Validator {
  import FlowdockAppender._

  @BeanProperty
  var token: String = null
  @BeanProperty
  var applicationUrl = Url + """messages/influx/"""
  @BeanProperty
  var layout: Layout[E] = null

  def params = Map("token" -> token)

  def flowdockUrl = applicationUrl + token

  private val httpConfig = new AsyncHttpClientConfig.Builder()
    .setMaximumConnectionsTotal(1)
    .setMaxRequestRetry(3)
    .build

  private val http = new AsyncHttpClient(httpConfig)

  def append(event: E) = {
    val msg = FlowdockMessage(layout.doLayout(event))
    val req = http.preparePost(flowdockUrl)
      .addParameter("source", msg.source)
      .addParameter("from_name", msg.fromName)
      .addParameter("from_address", msg.fromAddress)
      .addParameter("subject", msg.subject)
      .addParameter("content", msg.content)
    req.execute
  }

  override def stop() {
    super.stop()
    http.close()
  }
}