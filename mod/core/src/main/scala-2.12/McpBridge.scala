package com.indoorvivants.docviewer

import com.sun.net.httpserver.HttpHandler

// Scala 2.12 stub: mcp-quick is Scala-3-only, so no MCP endpoint on 2.12 plugins.
private[docviewer] object McpBridge {
  def handler(mapping: Seq[Dep]): Option[HttpHandler] = None
}
