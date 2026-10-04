addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.12.1")

// addSbtPlugin("com.eed3si9n" % "sbt-projectmatrix" % "0.11.0")


addSbtPlugin("com.github.sbt" % "sbt2-compat" % "0.1.0")

Compile / unmanagedSourceDirectories ++= {
  val base = (ThisBuild / baseDirectory).value.getParentFile /
    "mod" / "core" / "src" / "main"
  // meta-build runs on Scala 3 (sbt 2); pull in the Scala-3 MCP impl
  Seq(base / "scala", base / "scala-3")
}

libraryDependencies += "com.indoorvivants" %% "mcp-quick" % "0.2.0+5-94fdf6c7-SNAPSHOT"
