package com.mojolly.logback

import org.specs2.Specification
import org.specs2.specification.Before
import ch.qos.logback.classic.{Logger, LoggerContext}
import ch.qos.logback.classic.joran.JoranConfigurator
import collection.mutable.ListBuffer
import reflect.BeanProperty
import ch.qos.logback.core.{Appender, Layout, AppenderBase}
import ch.qos.logback.core.util.StatusPrinter
import org.multiverse.api.latches.StandardLatch
import ch.qos.logback.classic.spi.ILoggingEvent
import java.util.concurrent.{TimeUnit, ConcurrentSkipListSet}
import akka.actor.{ActorRef, Actor}
import javax.management.remote.rmi._RMIConnection_Stub

object StringListAppender {
  val messages = new ConcurrentSkipListSet[String]()
}
class StringListAppender[E] extends AppenderBase[E] {
  val messages = new ListBuffer[String]
  @BeanProperty var layout: Layout[E] = _

  override def stop() {
    messages.clear()
    super.stop()
  }

  override def start() {
    messages.clear()
    Option(layout).filter(_.isStarted).foreach(_ => super.start())
  }

  def append(p1: E) {
    messages += layout.doLayout(p1)
  }
}

class ActorAppenderSpec extends Specification { def is =

  "An actor appender for logback should" ^
    "log to the child appender" ! withStringListAppender(logToChildAppenders) ^
    "log to a listener actor" ! logToListenerActor ^ end

  def logToListenerActor = {
    val loggerContext = new LoggerContext
    val configUrl = getClass.getClassLoader.getResource("actor-appender-spec.xml")
    val cf = new JoranConfigurator
    cf.setContext(loggerContext)
    cf.doConfigure(configUrl)
    loggerContext.start()
    StatusPrinter.printIfErrorsOccured(loggerContext)
    val logger = loggerContext.getLogger(getClass)
    val latch = new StandardLatch()
    val actor = Actor.actorOf(new Actor {
      def receive = {
        case evt: ILoggingEvent if evt.getMessage == "The logged message" => latch.open()
        case _: ILoggingEvent => 
      }
    }).start()
    LogbackActor.addListener(actor)
    logger.info("The logged message")
    val res = latch.tryAwait(2, TimeUnit.SECONDS) must beTrue
    actor.stop()
    res
  }

  def logToChildAppenders = {
    val logger = withStringListAppender.logger
    val rootLogger = withStringListAppender.rootLogger
    logger.info("the logged message")

    val op = rootLogger.getAppender("ACTOR").asInstanceOf[ActorAppender[_]]
    val app = op.getAppender("STR_LIST").asInstanceOf[StringListAppender[_]]
    app.messages must contain("the logged message")
  }



  object withStringListAppender extends Before {
    val loggerContext = new LoggerContext
    val logger = loggerContext.getLogger(getClass)
    val rootLogger = loggerContext.getLogger("ROOT")
    var actor: ActorRef = _

    def before = {
      val latch = new StandardLatch
      actor = Actor.actorOf(new Actor {
        protected def receive = {
          case 'Start => latch.open()
          case _ =>
        }
      }).start()
      val configUrl = getClass.getClassLoader.getResource("actor-appender-spec.xml")
      val cf = new JoranConfigurator
      cf.setContext(loggerContext)
      cf.doConfigure(configUrl)
      loggerContext.start()
      StatusPrinter.printIfErrorsOccured(loggerContext)
      latch.tryAwait(2, TimeUnit.SECONDS)
//      Thread.sleep(500)  // TODO: Find another way to do this...
    }

    def stopActor = Option(actor) foreach { _.stop() }
  }

}