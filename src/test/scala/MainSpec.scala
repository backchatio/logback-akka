package com.mojolly.logback

import java.io.ByteArrayOutputStream
import org.specs2._

/**
  * Specification for Runner for logback-akka.
  */
class MainSpec extends Specification { def is =

  "This is a specification to check that Main prints correctly to System.out:" ^
                                                                               p^
  "Calling Main.main (with any arguments) should"                              ^
    "print 'Welcome to logback-akka!\n' to System.out"                               ! printWelcome

  def printWelcome = {
    val bytes = new ByteArrayOutputStream
    Console.withOut(bytes) {
      Main.main(null)
      bytes.toString must_== "Welcome to logback-akka!\n"
    }
  }
}
