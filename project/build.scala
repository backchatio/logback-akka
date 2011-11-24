import sbt._
import Keys._
import com.typesafe.sbtscalariform._
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
  val buildVersion      = "0.7.7-SNAPSHOT"

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
      //retrieveManaged := true,
      (excludeFilter in formatSources) <<= (excludeFilter) (_ || "*Spec.scala"),
      libraryDependencies ++= Seq(
        "org.scalatra" %% "scalatra" % "2.0.1" % "provided",
        "org.glassfish" % "javax.servlet" % "3.1" % "provided",
        "com.ning" % "async-http-client" % "1.6.5",
        "org.scala-tools.time" %% "time" % "0.5",
        "se.scalablesolutions.akka" % "akka-actor" % "1.2" % "provided",
        "se.scalablesolutions.akka" % "akka-stm" % "1.2" % "test",
        "org.slf4j" % "slf4j-api" % "1.6.4",
        "org.slf4j" % "log4j-over-slf4j" % "1.6.4",
        "com.weiglewilczek.slf4s" %% "slf4s" % "1.0.7",
        "ch.qos.logback" % "logback-classic" % "1.0.0",
        "junit" % "junit" % "4.10" % "test",
        "redis.clients" % "jedis" % "1.5.2" % "provided"
      ),
      libraryDependencies <+= (scalaVersion) {
        case "2.9.0-1" => "org.specs2" %% "specs2" % "1.5" % "test"
        case _ => "org.specs2" %% "specs2" % "1.6.1" % "test"
      },
      libraryDependencies <+= (scalaVersion) {
        case "2.9.0-1" => "net.liftweb" %% "lift-json" % "2.4-M3"
        case "2.9.1" => "net.liftweb" %% "lift-json" % "2.4-M5"
      },
      crossScalaVersions := Seq("2.9.1", "2.9.0-1"),
      libraryDependencies ++= compilerPlugins,
      autoCompilerPlugins := true,
      parallelExecution in Test := false,
      publishTo <<= (version) { version: String =>
        val nexus = "http://nexus.scala-tools.org/content/repositories/"
        if (version.trim.endsWith("SNAPSHOT")) Some("snapshots" at nexus+"snapshots/") 
        else                                   Some("releases" at nexus+"releases/")
      },
      shellPrompt  := ShellPrompt.buildShellPrompt,
      testOptions := Seq(
        Tests.Argument("console", "junitxml")),
      testOptions <+= crossTarget map { ct =>
        Tests.Setup { () => System.setProperty("specs2.junit.outDir", new File(ct, "specs-reports").getAbsolutePath) }
      })

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
