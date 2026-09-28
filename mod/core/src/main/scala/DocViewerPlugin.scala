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
          val name = path.getFileName().toString
          val docJarMaybe = if (name.endsWith(".jar")) {
            val jarPath = path
              .getParent()
              .resolve(name.stripSuffix(".jar") + "-javadoc.jar")

            if (Files.exists(jarPath)) Some(jarPath) else None
          } else None

          f.metadata.get(sbtcompat.PluginCompat.moduleIDStr).map { attr =>
            val n = parseModuleIDStrAttribute(attr)
            println(s"$n -- ${n.crossVersion}")
            Dep(n.organization + "/" + n.name + "/" + n.revision, docJarMaybe)
          }
        }

        val sorted = javadocs.sortBy { dep =>
          (dep.javadoc.isDefined, dep.moduleId)
        }

        new Server(sorted, sLog.value, port).start()

      }
    )
}

private case class Dep(moduleId: String, javadoc: Option[java.nio.file.Path])

private class Server(
    mapping: Seq[Dep],
    log: sbt.Logger,
    bindPort: Option[Int]
) {

  // returns the opened ZipFile so the caller can close it on shutdown
  def setupContext(
      serv: HttpServer,
      moduleId: String,
      javadoc: java.nio.file.Path
  ): (Boolean, ZipFile) = {
    val base = "/" + moduleId
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

    def serve(entry: ZipEntry): HttpExchange => Unit = {
      (h: HttpExchange) => {
        def setContentType(name: String) = {
          val head = h.getResponseHeaders()

          name
            .split('.')
            .lastOption
            .collect {
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
            .foreach { str =>
              head.set("Content-type", str)
            }
        }
        setContentType(entry.getName())
        try {
          // drain request body so the connection can be reused
          h.getRequestBody().close()
          val contents = zf.getInputStream(entry)
          val size = entry.getSize()
          // 0 = unknown length (chunked); -1 is invalid when we intend to send a body
          h.sendResponseHeaders(200, if (size >= 0) size else 0)
          val resp = h.getResponseBody()
          try contents.transferTo(resp)
          finally {
            contents.close()
            resp.close()
          }
        } finally h.close()
      }
    }

    // one context per module — route inside instead of thousands of per-file contexts
    serv.createContext(
      base + "/",
      (h: HttpExchange) => {
        val path = h.getRequestURI().getPath().stripPrefix(base + "/")
        byName.get(path) match {
          case Some(entry) => serve(entry)(h)
          case None =>
            h.sendResponseHeaders(404, -1)
            h.close()
        }
      }
    )

    (hasIndex, zf)
  }

  def start() = {
    val serv = HttpServer.create()

    val setup = mapping.collect { case Dep(p, Some(j)) =>
      val (hasIdx, zf) = setupContext(serv, p, j)
      (p, hasIdx, zf)
    }
    val hasIndex = setup.map { case (p, h, _) => p -> h }.toMap.withDefaultValue(false)
    val openZips = setup.map { case (_, _, z) => z }

    serv.createContext(
      "/",
      (h: HttpExchange) => {
        if (h.getRequestURI().getPath() == "/") {
          h.getResponseHeaders().set("Content-type", "text/html")

          val listing = mapping
            .map {
              case Dep(p, Some(j)) if hasIndex(p) =>
                s"""
                      <li style="margin-bottom:0.75rem"><a href="${p.toString}/index.html" style="display:block;padding:1rem;background:#fff;border-radius:6px;text-decoration:none;color:#0066cc;box-shadow:0 1px 3px rgba(0,0,0,0.1);transition:box-shadow 0.2s" onmouseover="this.style.boxShadow='0 2px 8px rgba(0,0,0,0.15)'" onmouseout="this.style.boxShadow='0 1px 3px rgba(0,0,0,0.1)'">$p</a>
                      </li>"""
              case Dep(p, None | Some(_)) =>
                s"""<li style="margin-bottom:0.75rem">
                      <div style="padding:1rem;background:#fafafa;border-radius:6px;color:#888">$p <span style="font-size:0.85rem;font-style:italic">— no docs available</span></div></li>"""
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

    serv.bind(new InetSocketAddress("localhost", bindPort.getOrElse(0)), 5)
    // default executor is synchronous — one slow client would block all others
    serv.setExecutor(Executors.newCachedThreadPool())
    // publish handle only after bind so a concurrent stop() cannot race
    Handle.set(serv, openZips)
    serv.start()

    val host = serv.getAddress().getHostString()
    val port = serv.getAddress().getPort()

    log.info(s"Dependency doc server started on http://$host:$port")

  }
}
