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
    val docViewStart = taskKey[Unit](
      "Doc view: start or restart the doc server"
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

        new Server(javadocs, sLog.value).start()

      }
    )
}

private case class Dep(moduleId: String, javadoc: Option[java.nio.file.Path])

private class Server(
    mapping: Seq[Dep],
    log: sbt.Logger
) {

  def setupContext(
      serv: HttpServer,
      moduleId: String,
      javadoc: java.nio.file.Path
  ) = {
    val base = "/" + moduleId
    val zf = new ZipFile(javadoc.toFile)
    val entries = zf.entries()

    def serve(entry: ZipEntry): HttpHandler =
      (h: HttpExchange) => {
        def setContentType(name: String) = {
          val head = h.getResponseHeaders()
          if (name.endsWith(".html"))
            head.set("Content-type", "text/html")
          if (name.endsWith(".js"))
            head.set("Content-type", "text/javascript")
          if (name.endsWith(".json"))
            head.set("Content-type", "application/json")
        }
        setContentType(entry.getName())
        val contents = zf.getInputStream(entry)
        val resp = h.getResponseBody()
        h.sendResponseHeaders(200, entry.getSize())
        contents.transferTo(resp)
        h.close()
      }

    var hasIndex = false

    while (entries.hasMoreElements()) {
      val entry: ZipEntry = entries.nextElement()
      if (!entry.isDirectory()) {
        if (entry.getName() == "index.html") hasIndex = true
        serv.createContext(base + "/" + entry.getName(), serve(entry))
      }
    }

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

    println(hasIndex)

    serv.createContext(
      "/",
      (h: HttpExchange) => {
        h.getResponseHeaders().set("Content-type", "text/html")
        val body = s"""
        <h1>Dependency docs</h1>
        <ul>
          ${mapping
            .map {
              case Dep(p, Some(j)) if hasIndex(p) =>
                s"<li><h2><a href='${p.toString}/index.html'>$p</a></h2></li>"
              case Dep(p, None | Some(_)) =>
                s"<li><h2>$p (<i>javadoc jar not found or no docs in the jar</i>)</h2></li>"
            }
            .mkString("\n")}
        </ul>
        """

        h.sendResponseHeaders(200, body.length)
        val resp = h.getResponseBody()
        resp.write(body.getBytes())
        h.close()
      }
    )

    Handle.set(serv)

    serv.bind(new InetSocketAddress("localhost", 4599), 5)
    serv.start()

    val host = serv.getAddress().getHostString()
    val port = serv.getAddress().getPort()

    log.info(s"Dependency doc server started on http://$host:$port")

  }
}
