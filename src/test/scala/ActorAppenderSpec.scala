package com.mojolly.logback

import org.specs2.Specification
import ch.qos.logback.classic.{LoggerContext}
import ch.qos.logback.classic.joran.JoranConfigurator
import collection.mutable.ListBuffer
import reflect.BeanProperty
import ch.qos.logback.core.{Layout, AppenderBase}
import ch.qos.logback.core.util.StatusPrinter
import org.multiverse.api.latches.StandardLatch
import ch.qos.logback.classic.spi.ILoggingEvent
import java.util.concurrent.{TimeUnit, ConcurrentSkipListSet}
import akka.actor.{ActorRef, Actor}
import org.specs2.specification.{Before, Around}
import scala.Some
import java.lang.StringBuffer

object StringListAppender {
  var messages = ListBuffer[String]()
  var latch: Option[StandardLatch] = None
}
class StringListAppender[E] extends AppenderBase[E] {
  import StringListAppender._

  @BeanProperty var layout: Layout[E] = _

  override def start() {
    Option(layout).filter(_.isStarted).foreach(_ => super.start())
  }

  def append(p1: E) {
    messages += layout.doLayout(p1)
    latch foreach { _.open() }
  }
}
class ActorAppenderSpec extends Specification { def is =

  sequential ^
  "An actor appender for logback should" ^
    "log to the child appender" ! withStringListAppender(logToChildAppenders) ^
    "log to a listener actor" ! logToListenerActor ^ end

  def logToListenerActor = {
    StringListAppender.messages = ListBuffer[String]()
    val loggerContext = new LoggerContext
    val configUrl = getClass.getClassLoader.getResource("actor-appender-spec.xml")
    val cf = new JoranConfigurator
    cf.setContext(loggerContext)
    cf.doConfigure(configUrl)
    loggerContext.start()
    StatusPrinter.printIfErrorsOccured(loggerContext)
    val logger = withStringListAppender.logger
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
    val latch = new StandardLatch
    StringListAppender.messages = ListBuffer[String]()
    StringListAppender.latch = Some(latch)
    logger.info("the logged message")
    val res = latch.tryAwait(2, TimeUnit.SECONDS) must beTrue
    withStringListAppender.stopActor
    res and  (StringListAppender.messages must contain("the logged message"))
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
      LogbackActor.addListener(actor)
      val configUrl = getClass.getClassLoader.getResource("actor-appender-spec.xml")
      val cf = new JoranConfigurator
      cf.setContext(loggerContext)
      cf.doConfigure(configUrl)
      loggerContext.start()
      StatusPrinter.printIfErrorsOccured(loggerContext)
    }

    def stopActor = {
      LogbackActor.removeListener(actor)
      Option(actor) foreach { _.stop() }
    }
  }

}