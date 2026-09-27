addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.11.2")

addSbtPlugin("com.eed3si9n" % "sbt-projectmatrix" % "0.11.0")


addSbtPlugin("com.github.sbt" % "sbt2-compat" % "0.1.0")

Compile / unmanagedSourceDirectories +=
  (ThisBuild / baseDirectory).value.getParentFile /
    "mod" / "core" / "src" / "main" / "scala"
