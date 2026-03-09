package com.indoorvivants.docviewer

import scala.io.StdIn

import sbt.Keys._
import sbt._
import sbt.nio.Keys._
import java.nio.file.Files
import com.sun.net.httpserver.{HttpServer, HttpHandler, HttpExchange}
import java.net.InetSocketAddress
import java.util.zip.ZipFile
import java.util.zip.ZipEntry

private[docviewer] object Handle {
  private var prom: Option[HttpServer] = Option.empty

  def stop() =
    prom = prom match {
      case Some(value) => value.stop(0); None
      case None        => None
    }

  def set(h: HttpServer) = {
    stop()
    prom = Some(h)
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
      docViewStop := {
        Handle.stop()
      },
      docViewStart := {
        import complete.DefaultParsers._
        import scala.sys.process._

        val args = spaceDelimited("<port>").parsed

        val port = args.headOption.map(_.toInt)

        val logger = sLog.value
        val compileCP = (Compile / dependencyClasspath).value
        val javadocs = compileCP.flatMap { f =>
          val path = f.data.toPath()
          val name = path.getFileName().toString
          val docJarMaybe = if (name.endsWith(".jar")) {
            val jarPath = path
              .getParent()
              .resolve(name.stripSuffix(".jar") + "-javadoc.jar")

            if (Files.exists(jarPath)) Some(jarPath) else None
          } else None

          f.metadata.get(AttributeKey[ModuleID]("moduleID")).map { n =>
            Dep(n.organization + "/" + n.name + "/" + n.revision, docJarMaybe)
          }
        }

        new Server(javadocs, sLog.value, port).start()

      }
    )
}

private case class Dep(moduleId: String, javadoc: Option[java.nio.file.Path])

private class Server(
    mapping: Seq[Dep],
    log: sbt.Logger,
    bindPort: Option[Int]
) {

  def setupContext(
      serv: HttpServer,
      moduleId: String,
      javadoc: java.nio.file.Path
  ) = {
    val base = "/" + moduleId
    val zf = new ZipFile(javadoc.toFile)
    val entries = zf.entries()

    val extensions = collection.mutable.Set.empty[String]

    def serve(entry: ZipEntry): HttpHandler = {
      entry.getName().split('.').lastOption.foreach(extensions.add)

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
            }
            .foreach { str =>
              head.set("Content-type", str)
            }
        }
        setContentType(entry.getName())
        val contents = zf.getInputStream(entry)
        val resp = h.getResponseBody()
        h.sendResponseHeaders(200, entry.getSize())
        contents.transferTo(resp)
        h.close()
      }
    }

    var hasIndex = false

    while (entries.hasMoreElements()) {
      val entry: ZipEntry = entries.nextElement()
      if (!entry.isDirectory()) {
        if (entry.getName() == "index.html") hasIndex = true
        serv.createContext(base + "/" + entry.getName(), serve(entry))
      }
    }

    println(extensions)

    hasIndex
  }

  def start() = {
    val serv = HttpServer.create()

    val hasIndex = mapping
      .collect { case Dep(p, Some(j)) =>
        p -> setupContext(serv, p, j)
      }
      .toMap
      .withDefaultValue(false)

    serv.createContext(
      "/",
      (h: HttpExchange) => {
        if (h.getRequestURI().getPath() == "/") {
          h.getResponseHeaders().set("Content-type", "text/html")
          val body = s"""<!DOCTYPE html>
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
      ${mapping
              .map {
                case Dep(p, Some(j)) if hasIndex(p) =>
                  s"""<li style="margin-bottom:0.75rem"><a href="${p.toString}/index.html" style="display:block;padding:1rem;background:#fff;border-radius:6px;text-decoration:none;color:#0066cc;box-shadow:0 1px 3px rgba(0,0,0,0.1);transition:box-shadow 0.2s" onmouseover="this.style.boxShadow='0 2px 8px rgba(0,0,0,0.15)'" onmouseout="this.style.boxShadow='0 1px 3px rgba(0,0,0,0.1)'">$p</a><br><small>Serving from ${j}</small></li>"""
                case Dep(p, None | Some(_)) =>
                  s"""<li style="margin-bottom:0.75rem"><div style="padding:1rem;background:#fafafa;border-radius:6px;color:#888">$p <span style="font-size:0.85rem;font-style:italic">— no docs available</span></div></li>"""
              }
              .mkString("\n")}
    </ul>
  </div>
</body>
</html>"""

          val bytes = body.getBytes("UTF-8")
          h.sendResponseHeaders(200, bytes.length)
          val resp = h.getResponseBody()
          resp.write(bytes)
        } else {
          h.sendResponseHeaders(404, 0)
        }
        h.close()
      }
    )

    Handle.set(serv)

    serv.bind(new InetSocketAddress("localhost", bindPort.getOrElse(0)), 5)
    serv.start()

    val host = serv.getAddress().getHostString()
    val port = serv.getAddress().getPort()

    log.info(s"Dependency doc server started on http://$host:$port")

  }
}
