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

import akka.config.Supervision._
import akka.actor._
import akka.actor.Actor._
import ch.qos.logback.classic.spi.{LoggingEventVO, ILoggingEvent}
import reflect.BeanProperty
import akka.util.ListenerManagement
import akka.dispatch.Future
import ch.qos.logback.core.spi.{AppenderAttachableImpl, AppenderAttachable}
import ch.qos.logback.core.{Appender, UnsynchronizedAppenderBase}

object LogbackActor extends ListenerManagement {

  var environment: String = _

  def readEnvironmentKey(onFail: String => Unit = _ => null) = {
    val envConf = System.getenv("AKKA_MODE") match {
      case null | "" ⇒ None
      case value     ⇒ Some(value)
    }

    val systemConf = System.getProperty("akka.mode") match {
      case null | "" ⇒ None
      case value     ⇒ Some(value)
    }
    (envConf orElse systemConf) getOrElse {
      onFail("no environment found, defaulting to development")
      "development"
    }
  }

  def notifyLogListeners(evt: ILoggingEvent) {
    notifyListeners(evt)
  }

  def notifyStart = {
    notifyListeners('Start)
  }
}
object ActorAppender {
  class LogbackActor[E <: ILoggingEvent](appenders: AppenderAttachableImpl[E]) extends Actor {

    protected def receive = {
      case evt: ILoggingEvent => {
        Future({
          appenders.appendLoopOnAppenders(evt.asInstanceOf[E])
          LogbackActor.notifyLogListeners(evt)
        }, 1000)
      }
      case 'Start => LogbackActor.notifyStart
    }
  }
}

class ActorAppender[E <: ILoggingEvent] extends UnsynchronizedAppenderBase[E] with AppenderAttachable[E] {

  private var supervisor: Supervisor = _

  @BeanProperty var includeCallerData: Boolean = false
  private val appenders = new AppenderAttachableImpl[E]

  lazy val environment = LogbackActor.readEnvironmentKey(addWarn _)

  override def stop() {
    if (super.isStarted) super.stop()
    if (isStarted) supervisor.shutdown()
  }

  override def start() {
    super.start()
    LogbackActor.environment = environment
    supervisor = SupervisorFactory(
      SupervisorConfig(
        AllForOneStrategy(List(classOf[Exception]), 3, 1000),
        Supervise(
          actorOf(new ActorAppender.LogbackActor(appenders)),
          Permanent) ::
        Nil,
      (actorRef, args) => {
        addError("Too many restarts for %s".format(args.victim.getClass.getSimpleName), args.lastExceptionCausingRestart)
      })).newInstance.start
  }

  def append(p1: E) {
    p1 match {
      case evt: ILoggingEvent => {
        evt.prepareForDeferredProcessing()
        if (includeCallerData) evt.getCallerData
        val immutableEvent = LoggingEventVO.build(evt)
        registry.actorFor[ActorAppender.LogbackActor[_]] foreach { _ ! immutableEvent }
      }
    }
  }

  def detachAppender(p1: String) = appenders.detachAppender(p1)

  def detachAppender(p1: Appender[E]) = appenders.detachAppender(p1)

  def detachAndStopAllAppenders() { appenders.detachAndStopAllAppenders() }

  def isAttached(p1: Appender[E]) = appenders.isAttached(p1)

  def getAppender(p1: String) = appenders.getAppender(p1)

  def iteratorForAppenders() = appenders.iteratorForAppenders()

  def addAppender(p1: Appender[E]) {
    appenders.addAppender(p1)
  }
}