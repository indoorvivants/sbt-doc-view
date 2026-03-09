inThisBuild(
  List(
    organization := "com.indoorvivants",
    organizationName := "Anton Sviridov",
    homepage := Some(
      url("https://github.com/indoorvivants/sbt-doc-view")
    ),
    startYear := Some(2024),
    licenses := List(
      "Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")
    ),
    developers := List(
      Developer(
        "keynmol",
        "Anton Sviridov",
        "keynmol@gmail.com",
        url("https://blog.indoorvivants.com")
      )
    )
  )
)

lazy val root = project
  .in(file("."))
  .aggregate(core, example)
  .settings(
    publish / skip := true,
    publishLocal / skip := true
  )

lazy val core = project
  .in(file("mod/core"))
  .settings(
    scalaVersion := "2.12.21",
    sbtPlugin := true,
    name := "sbt-doc-view",
    scriptedLaunchOpts := {
      scriptedLaunchOpts.value ++
        Seq("-Xmx1024M", "-Dplugin.version=" + version.value)
    },
    scriptedBufferLog := false
  )
  .enablePlugins(ScriptedPlugin, SbtPlugin)

lazy val example = project
  .in(file("mod/example"))
  .settings(
    scalaVersion := "3.8.2",
    libraryDependencies += "com.lihaoyi" %% "os-lib" % "0.11.8",
    publish / skip := true,
    publishLocal / skip := true
  )
// .enablePlugins(DocViewerPlugin)
