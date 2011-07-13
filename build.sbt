
name := "logback-akka"

version := "0.3"

organization := "com.mojolly.logback"

scalaVersion := "2.9.0-1"

scalacOptions ++= Seq("-optimize", "-unchecked", "-deprecation", "-Xcheckinit", "-encoding", "utf8", "-P:continuations:enable")

resolvers ++= Seq(
  "GlassFish Repo" at "http://download.java.net/maven/glassfish/",
  "Sonatype Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots",
  "ScalaTools Snapshots" at "http://scala-tools.org/repo-snapshots",
  "Akka Repo" at "http://akka.io/repository"
)

libraryDependencies ++= Seq(
  "net.liftweb" %% "lift-json" % "2.4-M1",
  "org.scalatra" %% "scalatra" % "2.0.0-SNAPSHOT" % "provided",
  "org.glassfish" % "javax.servlet" % "3.1" % "provided",
  "com.ning" % "async-http-client" % "1.6.4",
  "org.scala-tools.time" %% "time" % "0.4",
  "se.scalablesolutions.akka" % "akka-stm" % "1.1.3",
  "org.slf4j" % "slf4j-api" % "1.6.1",
  "com.weiglewilczek.slf4s" %% "slf4s" % "1.0.6",
  "ch.qos.logback" % "logback-classic" % "0.9.28",
  "redis.clients" % "jedis" % "1.5.2" % "provided",
  "org.specs2" %% "specs2" % "1.5" % "test"
)

libraryDependencies ++= Seq(
  compilerPlugin("org.scala-lang.plugins" % "continuations" % "2.9.0-1"),
  compilerPlugin("org.scala-tools.sxr" % "sxr_2.9.0" % "0.2.7")
)

autoCompilerPlugins := true

parallelExecution in Test := false

testFrameworks += new TestFramework("org.specs2.runner.SpecsFramework")

credentials += Credentials(Path.userHome / ".ivy2" / ".scala_tools_credentials")

publishTo <<= (version) { version: String =>
  val nexus = "http://nexus.scala-tools.org/content/repositories/"
  if (version.trim.endsWith("SNAPSHOT")) Some("snapshots" at nexus+"snapshots/") 
  else                                   Some("releases" at nexus+"releases/")
}

