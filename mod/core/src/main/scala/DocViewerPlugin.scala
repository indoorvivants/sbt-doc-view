package com.indoorvivants.docviewer

import scala.io.StdIn

import sbt.Keys._
import sbt._
import sbt.nio.Keys._
import java.nio.file.Files
import com.sun.net.httpserver.{HttpServer, HttpHandler, HttpExchange}
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import sbtcompat.PluginCompat._

private[docviewer] object Handle {
  private var prom: Option[HttpServer] = Option.empty
  // track open ZipFiles so we can release file handles on stop/restart
  private var zips: List[ZipFile] = Nil

  def stop() = {
    prom.foreach(_.stop(0))
    prom = None
    zips.foreach(z => scala.util.Try(z.close()))
    zips = Nil
  }

  def set(h: HttpServer, openZips: Seq[ZipFile]) = {
    stop()
    prom = Some(h)
    zips = openZips.toList
  }

}

object DocViewerPlugin extends AutoPlugin {
  override def trigger = allRequirements
  object autoImport {
    val docViewStart = inputKey[Unit](
      "Doc view: start or restart the doc server (pass port number as first parameter, otherwise a random one will be chosen)"
    )
    val docViewStop = taskKey[Unit](
      "Doc view: stop the server"
    )
  }

  import autoImport.*

  override def globalSettings: Seq[Setting[?]] = Seq(
  )

  override def projectSettings: Seq[Setting[?]] =
    Seq(
      // stop a possibly running application if the project is reloaded and the state is reset
      Global / onUnload ~= { onUnload => state =>
        Handle.stop()
        onUnload(state)
      },
      docViewStop := {
        Handle.stop()
      },
      docViewStart := {
        import complete.DefaultParsers._
        import scala.sys.process._

        val args = spaceDelimited("<port>").parsed

        val port = args.headOption.map(_.toInt)

        implicit val conv: xsbti.FileConverter = fileConverter.value

        val logger = sLog.value
        val compileCP = (Compile / externalDependencyClasspath).value
        val javadocs = compileCP.flatMap { f =>
          val path = toNioPath(f)
          val m2Path = path.getParent()
          val name = path.getFileName().toString

          val docJarMaybe = if (name.endsWith(".jar")) {
            val ivyPath = path.getParent().getParent().resolve("docs")
            val docJarName = name.stripSuffix(".jar") + "-javadoc.jar"
            Option(m2Path.resolve(docJarName))
              .filter(Files.exists(_))
              .orElse(
                Option(ivyPath.resolve(docJarName)).filter(Files.exists(_))
              )
          } else None

          val sourcesJar = if (name.endsWith(".jar")) {
            val ivyPath = path.getParent().getParent().resolve("srcs")
            val srcJarName = name.stripSuffix(".jar") + "-sources.jar"

            Option(m2Path.resolve(srcJarName))
              .filter(Files.exists(_))
              .orElse(
                Option(ivyPath.resolve(srcJarName)).filter(Files.exists(_))
              )
          } else None

          f.metadata.get(sbtcompat.PluginCompat.moduleIDStr).map { attr =>
            val n = parseModuleIDStrAttribute(attr)
            Dep(
              n.organization + "/" + n.name + "/" + n.revision,
              docJarMaybe,
              sourcesJar
            )
          }
        }

        val sorted = javadocs.sortBy { dep =>
          (dep.javadoc.isDefined, dep.moduleId)
        }

        new Server(sorted, sLog.value, port).start()

      }
    )
}

private case class Dep(
    moduleId: String,
    javadoc: Option[java.nio.file.Path],
    source: Option[java.nio.file.Path]
)

private class Server(
    mapping: Seq[Dep],
    log: sbt.Logger,
    bindPort: Option[Int]
) {

  // shared by doc + sources handlers: stream a zip entry, close everything on the way out
  private def serveEntry(
      zf: ZipFile,
      entry: ZipEntry,
      contentTypeOverride: Option[String] = None
  )(h: HttpExchange): Unit = {
    val head = h.getResponseHeaders()
    val ct = contentTypeOverride.orElse {
      entry.getName().split('.').lastOption.collect {
        case "html"  => "text/html"
        case "js"    => "text/javascript"
        case "json"  => "application/json"
        case "woff"  => "application/woff"
        case "woff2" => "application/woff2"
        case "png"   => "image/png"
        case "ico"   => "image/vnd.microsoft.icon"
        case "map"   => "application/json"
        case "svg"   => "image/svg+xml"
      }
    }
    ct.foreach(head.set("Content-type", _))
    try {
      h.getRequestBody().close()
      val contents = zf.getInputStream(entry)
      val size = entry.getSize()
      h.sendResponseHeaders(200, if (size >= 0) size else 0)
      val resp = h.getResponseBody()
      try contents.transferTo(resp)
      finally {
        contents.close()
        resp.close()
      }
    } finally h.close()
  }

  // returns the opened ZipFile so the caller can close it on shutdown
  def setupDocContext(
      serv: HttpServer,
      moduleId: String,
      javadoc: java.nio.file.Path
  ): (Boolean, ZipFile) = {
    val base = "/" + moduleId + "/doc"
    val zf = new ZipFile(javadoc.toFile)
    val entries = zf.entries()

    // index entries once so the handler does not scan the zip per request
    val byName = collection.mutable.Map.empty[String, ZipEntry]
    var hasIndex = false
    while (entries.hasMoreElements()) {
      val entry = entries.nextElement()
      if (!entry.isDirectory()) {
        byName(entry.getName()) = entry
        if (entry.getName() == "index.html") hasIndex = true
      }
    }

    serv.createContext(
      base + "/",
      (h: HttpExchange) => {
        val raw = h.getRequestURI().getPath().stripPrefix(base + "/")
        val path = if (raw.isEmpty) "index.html" else raw
        byName.get(path) match {
          case Some(entry) => serveEntry(zf, entry)(h)
          case None        =>
            h.sendResponseHeaders(404, -1)
            h.close()
        }
      }
    )

    (hasIndex, zf)
  }

  // mounts /<moduleId>/sources (flat listing) and /<moduleId>/sources/<path> (raw file)
  def setupSourcesContext(
      serv: HttpServer,
      moduleId: String,
      sourcesJar: java.nio.file.Path
  ): ZipFile = {
    val base = "/" + moduleId + "/sources"
    val zf = new ZipFile(sourcesJar.toFile)
    val entries = zf.entries()

    val byName = collection.mutable.Map.empty[String, ZipEntry]
    while (entries.hasMoreElements()) {
      val e = entries.nextElement()
      if (!e.isDirectory()) byName(e.getName()) = e
    }
    val sortedNames = byName.keys.toSeq.sorted

    serv.createContext(
      base,
      (h: HttpExchange) => {
        val fullPath = h.getRequestURI().getPath()
        if (fullPath == base || fullPath == base + "/") {
          // flat listing of source files
          val items = sortedNames
            .map { n =>
              s"""<li style="margin:0.15rem 0"><a href="$base/$n" style="color:#0066cc;text-decoration:none;font-family:ui-monospace,monospace;font-size:0.9rem">$n</a></li>"""
            }
            .mkString("\n")
          val body = s"""<!DOCTYPE html>
<html><head><meta charset="UTF-8"><title>Sources: $moduleId</title></head>
<body style="margin:0;font-family:system-ui,-apple-system,sans-serif;background:#f5f5f5;color:#333">
  <div style="max-width:900px;margin:0 auto;padding:2rem">
    <p><a href="/" style="color:#0066cc;text-decoration:none">&larr; back</a></p>
    <h1 style="font-weight:300">Sources: $moduleId</h1>
    <ul style="list-style:none;padding:0">$items</ul>
  </div>
</body></html>"""
          val bytes = body.getBytes("UTF-8")
          h.getResponseHeaders().set("Content-type", "text/html")
          try {
            h.getRequestBody().close()
            h.sendResponseHeaders(200, bytes.length)
            val resp = h.getResponseBody()
            try resp.write(bytes)
            finally resp.close()
          } finally h.close()
        } else {
          val path = fullPath.stripPrefix(base + "/")
          byName.get(path) match {
            // serve source files as plain text so the browser displays them
            case Some(entry) =>
              serveEntry(zf, entry, Some("text/plain; charset=utf-8"))(h)
            case None =>
              h.sendResponseHeaders(404, -1)
              h.close()
          }
        }
      }
    )

    zf
  }

  def start() = {
    val serv = HttpServer.create()

    val docSetup = mapping.collect { case Dep(p, Some(javadoc), _) =>
      val (hasIdx, zf) = setupDocContext(serv, p, javadoc)
      (p, hasIdx, zf)
    }
    val hasIndex =
      docSetup.map { case (p, h, _) => p -> h }.toMap.withDefaultValue(false)

    val srcSetup = mapping.collect { case Dep(p, _, Some(src)) =>
      setupSourcesContext(serv, p, src)
    }

    val openZips = docSetup.map { case (_, _, z) => z } ++ srcSetup

    // https://llmstxt.org — plain-text guide for LLMs crawling this server
    def llmsTxt: String = {
      val modules = mapping
        .map { dep =>
          val p = dep.moduleId
          val parts = List(
            if (dep.javadoc.isDefined && hasIndex(p)) Some(s"docs: /$p/doc/")
            else None,
            if (dep.source.isDefined) Some(s"sources: /$p/sources") else None
          ).flatten
          if (parts.isEmpty) s"- $p (no artifacts)"
          else s"- $p — ${parts.mkString(", ")}"
        }
        .mkString("\n")
      s"""# sbt-doc-view
         |
         |A local HTTP server that exposes scaladoc and source jars of the current sbt project's dependencies.
         |
         |## Endpoints
         |
         |- `/` — HTML index of all dependencies with links to docs and sources.
         |- `/llms.txt` — this file.
         |- `/<module-id>/doc/` — scaladoc index (HTML) for a dependency, if a `-javadoc.jar` was resolved.
         |- `/<module-id>/doc/<path>` — a specific asset inside the javadoc jar (HTML, JS, CSS, images).
         |- `/<module-id>/sources` — flat HTML listing of every file inside the `-sources.jar`.
         |- `/<module-id>/sources/<path>` — raw source file (served as `text/plain; charset=utf-8`).
         |- `/mcp` — Model Context Protocol (Streamable HTTP) endpoint exposing the same functionality as tools: `list_modules`, `get_doc_file`, `list_sources`, `get_source_file`.
         |
         |`<module-id>` has the form `organization/name/revision` (e.g. `org.typelevel/cats-core_3/2.10.0`).
         |`<path>` is the full path inside the jar (e.g. `cats/Monad.scala`).
         |
         |## How to use
         |
         |- To read the API docs of a dependency, fetch `/<module-id>/doc/index.html` and follow links.
         |- To find a symbol, fetch `/<module-id>/sources` and look for the matching file, then fetch `/<module-id>/sources/<path>`.
         |- Source files are plain text — safe to read directly without HTML parsing.
         |
         |## Modules
         |
         |$modules
         |""".stripMargin
    }

    serv.createContext(
      "/",
      (h: HttpExchange) => {
        val path = h.getRequestURI().getPath()
        if (path == "/llms.txt") {
          val bytes = llmsTxt.getBytes("UTF-8")
          h.getResponseHeaders()
            .set("Content-type", "text/plain; charset=utf-8")
          try {
            h.getRequestBody().close()
            h.sendResponseHeaders(200, bytes.length)
            val resp = h.getResponseBody()
            try resp.write(bytes)
            finally resp.close()
          } finally h.close()
        } else if (path == "/") {
          h.getResponseHeaders().set("Content-type", "text/html")

          val listing = mapping
            .map { case dep =>
              val p = dep.moduleId
              val docBtn =
                if (dep.javadoc.isDefined && hasIndex(p))
                  s"""<a href="/$p/doc/" style="display:inline-block;padding:0.4rem 0.8rem;margin-right:0.5rem;background:#0066cc;color:#fff;border-radius:4px;text-decoration:none;font-size:0.9rem">docs</a>"""
                else
                  s"""<span style="display:inline-block;padding:0.4rem 0.8rem;margin-right:0.5rem;background:#eee;color:#999;border-radius:4px;font-size:0.9rem">no docs</span>"""
              val srcBtn =
                if (dep.source.isDefined)
                  s"""<a href="/$p/sources" style="display:inline-block;padding:0.4rem 0.8rem;background:#28a745;color:#fff;border-radius:4px;text-decoration:none;font-size:0.9rem">sources</a>"""
                else
                  s"""<span style="display:inline-block;padding:0.4rem 0.8rem;background:#eee;color:#999;border-radius:4px;font-size:0.9rem">no sources</span>"""
              s"""<li style="margin-bottom:0.75rem;padding:1rem;background:#fff;border-radius:6px;box-shadow:0 1px 3px rgba(0,0,0,0.1);display:flex;align-items:center;justify-content:space-between">
                      <span style="color:#222">$p</span>
                      <span>$docBtn$srcBtn</span>
                    </li>"""
            }
            .mkString("\n")

          val body = s"""
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Dependency Docs</title>
</head>
<body style="margin:0;font-family:system-ui,-apple-system,sans-serif;background:#f5f5f5;color:#333;line-height:1.6">
  <div style="max-width:800px;margin:0 auto;padding:2rem">
    <h1 style="font-weight:300;font-size:2rem;margin-bottom:1.5rem;color:#222">Dependency Docs</h1>
    <ul style="list-style:none;padding:0;margin:0">
      $listing
    </ul>
  </div>
</body>
</html>"""

          val bytes = body.getBytes("UTF-8")
          h.sendResponseHeaders(200, bytes.length)
          val resp = h.getResponseBody()
          resp.write(bytes)
        } else {
          // -1 means "no response body"; 0 would signal chunked and hang the client
          h.sendResponseHeaders(404, -1)
        }
        h.close()
      }
    )

    // MCP endpoint (Scala 3 only; stub returns None on 2.12)
    McpBridge.handler(mapping).foreach(serv.createContext("/mcp", _))

    serv.bind(new InetSocketAddress("localhost", bindPort.getOrElse(0)), 5)
    // default executor is synchronous — one slow client would block all others
    serv.setExecutor(Executors.newCachedThreadPool())
    // publish handle only after bind so a concurrent stop() cannot race
    Handle.set(serv, openZips)
    serv.start()

    val host = serv.getAddress().getHostString()
    val port = serv.getAddress().getPort()

    val welcome = s"""
    |Dependency doc server started on http://$host:$port:
    |- Agents, visit http://$host:$port/llms.txt for instructions
    |- MCP (streamable HTTP) server available on http://$host:$port/mcp
    """.stripMargin.trim

    log.info(welcome)

  }
}
