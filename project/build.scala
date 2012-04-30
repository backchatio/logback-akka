import sbt._
import Keys._
import xml.Group

// import com.typesafe.sbtscalariform._
// import ScalariformPlugin._
// import ScalariformKeys._

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
  val buildOrganization = "io.backchat.logback"
  val buildScalaVersion = "2.9.1"
  val buildVersion      = "0.8.5-SNAPSHOT"

  // lazy val formatSettings = ScalariformPlugin.settings ++ Seq(
  //   preferences in ThisProject := formattingPreferences
  // )

  // def formattingPreferences = {
  //   import scalariform.formatter.preferences._
  //   (FormattingPreferences()
  //       setPreference(IndentSpaces, 2)
  //       setPreference(AlignParameters, true)
  //       setPreference(AlignSingleLineCaseStatements, true)
  //       setPreference(DoubleIndentClassDeclaration, true)
  //       setPreference(RewriteArrowSymbols, true)
  //       setPreference(PreserveSpaceBeforeArguments, true))
  // }

  val description = SettingKey[String]("description")

  val compilerPlugins = Seq(
    compilerPlugin("org.scala-lang.plugins" % "continuations" % buildScalaVersion)//,
    // compilerPlugin("org.scala-tools.sxr" % "sxr_2.9.0" % "0.2.7")
  )

  val buildSettings = Defaults.defaultSettings ++ Seq(
      name := "logback-ext",
      version := buildVersion,
      organization := buildOrganization,
      scalaVersion := buildScalaVersion,
      javacOptions ++= Seq("-Xlint:unchecked"),
      exportJars := true,
      testOptions in Test += Tests.Setup( () => System.setProperty("akka.mode", "test") ),
      scalacOptions ++= Seq(
        "-optimize",
        "-deprecation",
        "-unchecked",
        "-Xcheckinit",
        "-encoding", "utf8",
        "-P:continuations:enable"),
      externalResolvers <<= resolvers map { rs => Resolver.withDefaultResolvers(rs, mavenCentral = true, scalaTools = false) },
      resolvers ++= Seq(
        "GlassFish Repo" at "http://download.java.net/maven/glassfish/",
        "Sonatype Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots",
        "ScalaTools Snapshots" at "http://scala-tools.org/repo-snapshots"
      ),
      //retrieveManaged := true,
      // (excludeFilter in format) <<= (excludeFilter) (_ || "*Spec.scala"),
      libraryDependencies ++= Seq(
        "org.scalatra" % "scalatra" % "2.1.0-SNAPSHOT" % "provided",
        "javax.servlet" % "javax.servlet-api"  % "3.0.1" % "provided",
        "com.ning" % "async-http-client" % "1.7.4",
        "org.scala-tools.time" %% "time" % "0.5" % "provided",
        "org.slf4j" % "slf4j-api" % "1.6.4",
        "org.slf4j" % "log4j-over-slf4j" % "1.6.4",
        "org.slf4j" % "jcl-over-slf4j" % "1.6.4",
        "com.weiglewilczek.slf4s" %% "slf4s" % "1.0.7",
        "ch.qos.logback" % "logback-classic" % "1.0.0",
        "junit" % "junit" % "4.10" % "test",
        "redis.clients" % "jedis" % "1.5.2" % "provided"
      ),
      libraryDependencies <+= (scalaVersion) {
        case "2.9.0-1" => "org.specs2" %% "specs2" % "1.5" % "test"
        case _ => "org.specs2" %% "specs2" % "1.8.2" % "test"
      },
      libraryDependencies <+= (scalaVersion) {
        case "2.9.0-1" => "net.liftweb" %% "lift-json" % "2.4"
        case "2.9.1" => "net.liftweb" %% "lift-json" % "2.4"
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
          "Created-By" -> "Simple Build Tool",
          "Built-By" -> System.getProperty("user.name"),
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
    homepage := Some(url("https://backchat.io")),
    startYear := Some(2010),
    licenses := Seq(("MIT", url("http://github.com/mojolly/logback-akka/raw/HEAD/LICENSE"))),
    pomExtra <<= (pomExtra, name, description) {(pom, name, desc) => pom ++ Group(
      <scm>
        <connection>scm:git:git://github.com/mojolly/logback-akka.git</connection>
        <developerConnection>scm:git:git@github.com:mojolly/logback-akka.git</developerConnection>
        <url>https://github.com/mojolly/logback-akka</url>
      </scm>
      <developers>
        <developer>
          <id>casualjim</id>
          <name>Ivan Porto Carrero</name>
          <url>http://flanders.co.nz/</url>
        </developer>
        <developer>
          <id>sdb</id>
          <name>Stefan De Boey</name>
          <url>http://ellefant.be</url>
        </developer>
      </developers>
    )},
    publishMavenStyle := true,
    publishTo <<= version { (v: String) =>
      val nexus = "https://oss.sonatype.org/"
      if (v.trim.endsWith("SNAPSHOT"))
        Some("snapshots" at nexus + "content/repositories/snapshots")
      else
        Some("releases"  at nexus + "service/local/staging/deploy/maven2")
    },
    publishArtifact in Test := false,
    pomIncludeRepository := { x => false })

  val projectSettings = buildSettings ++ packageSettings
}

object LogbackAkkaBuild extends Build {

  import LogbackAkkaSettings._
  val buildShellPrompt =  ShellPrompt.buildShellPrompt

  lazy val root = Project ("logback-akka", file("."), settings = projectSettings ++ Seq(
    description := "An async akka based logback appender")) 
  
}
// vim: set ts=2 sw=2 et:
