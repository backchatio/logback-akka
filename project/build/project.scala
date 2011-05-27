import sbt._

class Project(info: ProjectInfo) extends DefaultProject(info) with AkkaProject {

  object Repositories {
    val GlassFishRepo = MavenRepository("GlassFish Repo", "http://download.java.net/maven/glassfish/")
    val SonatypeSnapshots = MavenRepository("Sonatype Nexus Snapshots", "https://oss.sonatype.org/content/repositories/snapshots")
  }
  import Repositories._

  lazy val glassfishModuleConfig    = ModuleConfiguration("org.glassfish", GlassFishRepo)
  lazy val slf4sModuleConfiguration = ModuleConfiguration("com.weiglezilczek.slf4s", ScalaToolsSnapshots)
  lazy val liftModuleConfiguration = ModuleConfiguration("net.liftweb", ScalaToolsSnapshots)
  lazy val scalatraCore = ModuleConfiguration("org.scalatra", SonatypeSnapshots)

  val liftJson = "net.liftweb" %% "lift-json" % "2.4-SNAPSHOT"

  // To capture web context
  val scalatra = "org.scalatra" %% "scalatra" % "2.0.0-SNAPSHOT" % "provided"
  val servletApi = "org.glassfish" % "javax.servlet" % "3.0" % "provided"

  // For hoptoad
  val asyncHttpClient = "com.ning" % "async-http-client" % "1.6.1"

  // Common modules
  val akkaStm = akkaModule("stm")
  val slf4s = "com.weiglewilczek.slf4s" %% "slf4s" % "1.0.6"
  val logback = "ch.qos.logback" % "logback-classic" % "0.9.28"

  // Test dependencies
  val specs2 = "org.specs2" %% "specs2" % "1.3" % "test"

  // Test frameworks
  def specs2Framework = new TestFramework("org.specs2.runner.SpecsFramework")
  override def testFrameworks = super.testFrameworks ++ Seq(specs2Framework)
}
