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

val Scala212 = "2.12.21"
val Scala3 = "3.8.4"

lazy val root = project
  .in(file("."))
  .aggregate(core.projectRefs *)
  .aggregate(example)
  .settings(
    publish / skip := true,
    publishLocal / skip := true
  )

lazy val core = projectMatrix
  .jvmPlatform(Seq(Scala212, Scala3))
  .in(file("mod/core"))
  .settings(
    addSbtPlugin("com.github.sbt" % "sbt2-compat" % "0.1.0"),
    sbtPlugin := true,
    name := "sbt-doc-view",
    pluginCrossBuild / sbtVersion := {
      scalaBinaryVersion.value match {
        case "2.12" => "1.12.11"
        case _      => "2.0.9"
      }
    },
    sbtTestDirectory := {
      scalaBinaryVersion.value match {
        case "2.12" => (sourceDirectory).value / "sbt-test"
        case _      => (sourceDirectory).value / "sbt-test-sbt2"
      }
    },
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
    scalaVersion := Scala3,
    libraryDependencies += "com.lihaoyi" %% "os-lib" % "0.11.8",
    publish / skip := true,
    publishLocal / skip := true
  )
// .enablePlugins(DocViewerPlugin)
