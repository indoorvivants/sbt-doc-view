addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.11.2")

Compile / unmanagedSourceDirectories +=
  (ThisBuild / baseDirectory).value.getParentFile /
    "mod" / "core" / "src" / "main" / "scala"
