package okhttp3.internal.idn

import java.io.File
import okio.FileSystem
import okio.Path.Companion.toPath

fun String.escapeDataString(): String =
  buildString {
    for (codePoint in this@escapeDataString.codePoints()) {
      when (codePoint) {
        in 0..0x20,
        '"'.code,
        '$'.code,
        '\\'.code,
        '·'.code,
        127,
        -> append(String.format("\\u%04x", codePoint))

        else -> appendCodePoint(codePoint)
      }
    }
  }

fun main(args: Array<String>) {
  val txt = args[0].toPath()
  val out = File(args[1])
  val table = FileSystem.SYSTEM.read(txt) { readPlainTextIdnaMappingTable() }
  val data = buildIdnaMappingTableData(table)
  val sections = data.sections.escapeDataString()
  val ranges = data.ranges.escapeDataString()
  val mappings = data.mappings.escapeDataString()
  out.parentFile.mkdirs()
  out.writeText(buildString {
    appendLine("/*")
    appendLine(" * Copyright (C) 2023 Square, Inc.")
    appendLine(" *")
    appendLine(" * Licensed under the Apache License, Version 2.0 (\"License\");")
    appendLine(" * you may not use this file except in compliance with the License.")
    appendLine(" * You may obtain a copy of the License at")
    appendLine(" *")
    appendLine(" *      http://www.apache.org/licenses/LICENSE-2.0")
    appendLine(" *")
    appendLine(" * Unless required by applicable law or agreed to in writing, software")
    appendLine(" * distributed under the License is distributed on an \"AS IS\" BASIS,")
    appendLine(" * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.")
    appendLine(" * See the License for the specific language governing permissions and")
    appendLine(" * limitations under the License.")
    appendLine(" */")
    appendLine()
    appendLine("@file:Suppress(\"ktlint:standard:filename\")")
    appendLine()
    appendLine("package okhttp3.internal.idn")
    appendLine()
    appendLine("internal val IDNA_MAPPING_TABLE: IdnaMappingTable = IdnaMappingTable(")
    appendLine("  sections = \"" + sections + "\",")
    appendLine("  ranges = \"" + ranges + "\",")
    appendLine("  mappings = \"" + mappings + "\",")
    appendLine(")")
  })
}
