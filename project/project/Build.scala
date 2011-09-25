import sbt._

object PluginDef extends Build {
    override lazy val projects = super.projects ++ Seq(root)
    lazy val root = Project("plugins", file(".")) dependsOn( scalariform )
    lazy val scalariform = uri("git://github.com/typesafehub/sbt-scalariform")
}
