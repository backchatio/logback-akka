import sbt._
import Keys._
import ScalariformPlugin._

// Shell prompt which show the current project, git branch and build version
// git magic from Daniel Sobral, adapted by Ivan Porto Carrero to also work with git flow branches
object ShellPrompt {
 
  object devnull extends ProcessLogger {
    def info (s: => String) {}
    def error (s: => String) { }
    def buffer[T] (f: => T): T = f
  }
  
  val current = """\*\s+([^\s]+)""".r
  
  def gitBranches = ("git branch --no-color" lines_! devnull mkString)
  
  val buildShellPrompt = { 
    (state: State) => {
      val currBranch = current findFirstMatchIn gitBranches map (_ group(1)) getOrElse "-"
      val currProject = Project.extract (state).currentProject.id
      "%s:%s:%s> ".format (currBranch, currProject, LogbackAkkaSettings.buildVersion)
    }
  }
 
}

object LogbackAkkaSettings {
  val buildOrganization = "com.mojolly.logback"
  val buildScalaVersion = "2.9.1"
  val buildVersion      = "0.7-SNAPSHOT"

  lazy val formatSettings = ScalariformPlugin.settings ++ Seq(
    formatPreferences in Compile := formattingPreferences,
    formatPreferences in Test    := formattingPreferences
  )

  def formattingPreferences = {
    import scalariform.formatter.preferences._
    (FormattingPreferences()
        setPreference(IndentSpaces, 2)
        setPreference(AlignParameters, true)
        setPreference(AlignSingleLineCaseStatements, true)
        setPreference(DoubleIndentClassDeclaration, true)
        setPreference(RewriteArrowSymbols, true)
        setPreference(PreserveSpaceBeforeArguments, true))
  }

  val description = SettingKey[String]("description")

  val compilerPlugins = Seq(
    compilerPlugin("org.scala-lang.plugins" % "continuations" % buildScalaVersion),
    compilerPlugin("org.scala-tools.sxr" % "sxr_2.9.0" % "0.2.7")
  )

  val buildSettings = Defaults.defaultSettings ++ formatSettings ++ Seq(
      name := "logback-akka",
      version := buildVersion,
      organization := buildOrganization,
      scalaVersion := buildScalaVersion,
      javacOptions ++= Seq("-Xlint:unchecked"),
      testOptions in Test += Tests.Setup( () => System.setProperty("akka.mode", "test") ),
      scalacOptions ++= Seq(
        "-optimize",
        "-deprecation",
        "-unchecked",
        "-Xcheckinit",
        "-encoding", "utf8",
        "-P:continuations:enable"),
      resolvers ++= Seq(
        "GlassFish Repo" at "http://download.java.net/maven/glassfish/",
        "Sonatype Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots",
        "ScalaTools Snapshots" at "http://scala-tools.org/repo-snapshots",
        "Akka Repo" at "http://akka.io/repository"
      ),
      retrieveManaged := true,
      (defaultExcludes in formatSources) <<= (defaultExcludes) (_ || "*Spec.scala"),
      libraryDependencies ++= Seq(
        "net.liftweb" %% "lift-json" % "2.4-SNAPSHOT",
        "org.scalatra" %% "scalatra" % "2.0.0-SNAPSHOT" % "provided",
        "org.glassfish" % "javax.servlet" % "3.1" % "provided",
        "com.ning" % "async-http-client" % "1.6.4",
        "org.scala-tools.time" %% "time" % "0.5",
        "se.scalablesolutions.akka" % "akka-stm" % "1.2-RC6",
        "org.slf4j" % "slf4j-api" % "1.6.1",
        "com.weiglewilczek.slf4s" %% "slf4s" % "1.0.7",
        "ch.qos.logback" % "logback-classic" % "0.9.29",
        "redis.clients" % "jedis" % "1.5.2" % "provided",
        "org.specs2" %% "specs2" % "1.6" % "test"
      ),
      crossScalaVersions := Seq("2.9.1", "2.9.0-1"),
      libraryDependencies ++= compilerPlugins,
      autoCompilerPlugins := true,
      parallelExecution in Test := false,
      publishTo <<= (version) { version: String => 
        val nexus = "http://nexus.scala-tools.org/content/repositories/"
        if (version.trim.endsWith("SNAPSHOT")) Some("snapshots" at nexus+"snapshots/") 
        else                                   Some("releases" at nexus+"releases/")
      },
      shellPrompt  := ShellPrompt.buildShellPrompt)

  val packageSettings = Seq (
    packageOptions <<= (packageOptions, name, version, organization) map {
      (opts, title, version, vendor) =>
         opts :+ Package.ManifestAttributes(
          "Created-By" -> System.getProperty("user.name"),
          "Built-By" -> "Simple Build Tool",
          "Build-Jdk" -> System.getProperty("java.version"),
          "Specification-Title" -> title,
          "Specification-Vendor" -> "Mojolly Ltd.",
          "Specification-Version" -> version,
          "Implementation-Title" -> title,
          "Implementation-Version" -> version,
          "Implementation-Vendor-Id" -> vendor,
          "Implementation-Vendor" -> "Mojolly Ltd.",
          "Implementation-Url" -> "https://backchat.io"
         )
    },
    pomExtra <<= (pomExtra, name, description) { (extra, title, desc) => extra ++ Seq(
      <name>{title}</name>, <description>{desc}</description>)
    })
 
  val projectSettings = buildSettings ++ packageSettings
}

object LogbackAkkaBuild extends Build {

  import LogbackAkkaSettings._
  val buildShellPrompt =  ShellPrompt.buildShellPrompt

  lazy val root = Project ("logback-akka", file("."), settings = projectSettings ++ Seq(
    description := "An async akka based logback appender")) 
  
}
// vim: set ts=2 sw=2 et:
