package com.indoorvivants.docviewer

import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile

import scala.jdk.CollectionConverters.*

import com.sun.net.httpserver.HttpHandler

import mcp.*
import mcp.json.*

private[docviewer] object McpBridge:

  def handler(mapping: Seq[Dep]): Option[HttpHandler] =
    val byId: Map[String, Dep] = mapping.map(d => d.moduleId -> d).toMap

    def readEntry(jar: java.nio.file.Path, path: String): Either[String, String] =
      val zf = new ZipFile(jar.toFile)
      try
        val e = zf.getEntry(path)
        if e == null then Left(s"path not found in jar: $path")
        else
          val is = zf.getInputStream(e)
          try
            val bytes = is.readAllBytes()
            Right(new String(bytes, StandardCharsets.UTF_8))
          finally is.close()
      finally zf.close()

    def listEntries(jar: java.nio.file.Path): Seq[String] =
      val zf = new ZipFile(jar.toFile)
      try
        zf.entries().asScala.collect {
          case e if !e.isDirectory() => e.getName()
        }.toVector.sorted
      finally zf.close()

    def text(s: String): CallToolResult =
      CallToolResult(content = Seq(TextContent(text = s, `type` = "text")))

    def err(msg: String): CallToolResult =
      CallToolResult(
        content = Seq(TextContent(text = msg, `type` = "text")),
        isError = Some(true)
      )

    val listModulesTool = Tool(
      name = "list_modules",
      description = Some(
        "List all project dependencies and which artifacts (docs, sources) are available for each."
      ),
      inputSchema = Tool.InputSchema(properties = Some(ujson.Obj()))
    )

    val getDocFileTool = Tool(
      name = "get_doc_file",
      description = Some(
        "Fetch a file from a dependency's javadoc jar. Default path is index.html. module_id is organization/name/revision."
      ),
      inputSchema = Tool.InputSchema(
        properties = Some(
          ujson.Obj(
            "module_id" -> ujson.Obj("type" -> ujson.Str("string")),
            "path" -> ujson.Obj("type" -> ujson.Str("string"))
          )
        ),
        required = Some(Seq("module_id"))
      )
    )

    val listSourcesTool = Tool(
      name = "list_sources",
      description = Some(
        "Flat list of every file in a dependency's -sources.jar. module_id is organization/name/revision."
      ),
      inputSchema = Tool.InputSchema(
        properties = Some(
          ujson.Obj(
            "module_id" -> ujson.Obj("type" -> ujson.Str("string"))
          )
        ),
        required = Some(Seq("module_id"))
      )
    )

    val getSourceFileTool = Tool(
      name = "get_source_file",
      description = Some(
        "Fetch a raw source file from a dependency's -sources.jar. module_id is organization/name/revision, path is the full in-jar path (e.g. cats/Monad.scala)."
      ),
      inputSchema = Tool.InputSchema(
        properties = Some(
          ujson.Obj(
            "module_id" -> ujson.Obj("type" -> ujson.Str("string")),
            "path" -> ujson.Obj("type" -> ujson.Str("string"))
          )
        ),
        required = Some(Seq("module_id", "path"))
      )
    )

    val builder = MCPBuilder
      .create()
      .handle(ping)(_ => PingResult())
      .handle(initialize): req =>
        InitializeResult(
          capabilities =
            ServerCapabilities(tools = Some(ServerCapabilities.Tools())),
          protocolVersion = req.protocolVersion,
          serverInfo = Implementation(
            name = "sbt-doc-view",
            version = "0.1.0"
          )
        )
      .handle(notifications.initialized)(_ => ())
      .handle(tools.list): _ =>
        ListToolsResult(
          Seq(listModulesTool, getDocFileTool, listSourcesTool, getSourceFileTool)
        )
      .handle(tools.call): req =>
        val args = req.arguments.getOrElse(ujson.Obj())
        req.name match
          case "list_modules" =>
            val lines = mapping.map { d =>
              val bits = List(
                d.javadoc.map(_ => "docs"),
                d.source.map(_ => "sources")
              ).flatten
              val tag = if bits.isEmpty then "no artifacts" else bits.mkString(", ")
              s"- ${d.moduleId} [$tag]"
            }
            text(lines.mkString("\n"))

          case "get_doc_file" =>
            val id = args.obj("module_id").str
            val path =
              args.obj.get("path").map(_.str).filter(_.nonEmpty).getOrElse("index.html")
            byId.get(id) match
              case None => err(s"unknown module_id: $id")
              case Some(dep) =>
                dep.javadoc match
                  case None => err(s"no javadoc jar available for $id")
                  case Some(jar) =>
                    readEntry(jar, path) match
                      case Left(msg) => err(msg)
                      case Right(s)  => text(s)

          case "list_sources" =>
            val id = args.obj("module_id").str
            byId.get(id) match
              case None => err(s"unknown module_id: $id")
              case Some(dep) =>
                dep.source match
                  case None      => err(s"no sources jar available for $id")
                  case Some(jar) => text(listEntries(jar).mkString("\n"))

          case "get_source_file" =>
            val id = args.obj("module_id").str
            val path = args.obj("path").str
            byId.get(id) match
              case None => err(s"unknown module_id: $id")
              case Some(dep) =>
                dep.source match
                  case None => err(s"no sources jar available for $id")
                  case Some(jar) =>
                    readEntry(jar, path) match
                      case Left(msg) => err(msg)
                      case Right(s)  => text(s)

          case other => err(s"unknown tool: $other")

    Some(HttpTransport.default.handler(builder.endpoints))
  end handler
end McpBridge
