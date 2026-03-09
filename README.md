# Browse documentation for your library dependencies

directly from SBT.

This plugin serves the scala/java docs directly from jars for your dependencies.
It has no dependencies and starts an HTTP server using built-in JDK server.

## Getting started

Add this to your `project/plugins.sbt` (find the version on github releases or on Scaladex):

```scala
addSbtPlugin("com.indoorvivants" % "sbt-doc-view" % "<VERSION>")
```

This will automatically add `docViewStart`/`docViewStop` tasks to all your projects.
