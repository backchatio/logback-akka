name := "logback-akka"

version := "0.1-SNAPSHOT"

organization := "com.mojolly"

scalaVersion := "2.9.0-1"

resolvers ++= Seq(
  "GlassFish Repo" at "http://download.java.net/maven/glassfish/",
  "Sonatype Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots",
  "ScalaTools Snapshots" at "http://scala-tools.org/repo-snapshots",
  "Akka Repo" at "http://akka.io/repository"
)

libraryDependencies ++= Seq(
  "net.liftweb" % "lift-json_2.9.0-1" % "2.4-SNAPSHOT",
  "org.scalatra" %% "scalatra" % "2.0.0-SNAPSHOT" % "provided",
  "org.glassfish" % "javax.servlet" % "3.0" % "provided",
  "com.ning" % "async-http-client" % "1.6.1",
  "se.scalablesolutions.akka" % "akka-stm" % "1.1.2",
  "com.weiglewilczek.slf4s" %% "slf4s" % "1.0.6",
  "ch.qos.logback" % "logback-classic" % "0.9.28",
  "org.specs2" %% "specs2" % "1.3" % "test"
)

testFrameworks += new TestFramework("org.specs2.runner.SpecsFramework")



