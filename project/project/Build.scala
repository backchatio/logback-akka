import sbt._
object PluginDef extends Build {
    override lazy val projects = Seq(root)
    lazy val root = Project("plugins", file(".")) dependsOn( webPlugin )
    lazy val webPlugin = uri("git://github.com/typesafehub/sbt-scalariform")
}
