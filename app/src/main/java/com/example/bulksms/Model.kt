package com.example.bulksms

import kotlinx.serialization.Serializable
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

const val APP_NAME = "Bulk SMS"

enum class Status(val label: String) { Pending("Pending"), Sent("Sent"), Failed("Failed") }
enum class State(val label: String) { Running("Sending"), Paused("Paused"), Done("Completed"), Stopped("Stopped") }

@Serializable
data class Recipient(val phone: String, val fields: Map<String, String>, val status: Status = Status.Pending, val error: String? = null, val time: Long = 0)

@Serializable
data class Campaign(
    val id: Long, val name: String, val template: String, val recipients: List<Recipient>,
    val subId: Int = -1, val delaySec: Int = 5, val state: State = State.Paused, val created: Long = System.currentTimeMillis(),
) {
    fun count(s: Status) = recipients.count { it.status == s }
    val finished get() = recipients.none { it.status == Status.Pending }
}

/** A spreadsheet: header names + rows (all cells as text). */
data class Table(val headers: List<String>, val rows: List<List<String>>)

// ---------------- Import ----------------

/** Reads .xlsx, or delimited text (.csv / .tsv / .txt; the delimiter is guessed from the first line). */
fun readRows(fileName: String, bytes: ByteArray): List<List<String>> =
    if (bytes.size > 1 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) readXlsx(bytes)
    else if (fileName.endsWith(".xls", true)) error("Old .xls files are not supported. In Excel, use Save As → .xlsx or .csv")
    else readDelimited(bytes.toString(Charsets.UTF_8).removePrefix("﻿"))

fun readDelimited(text: String): List<List<String>> {
    val first = text.lineSequence().firstOrNull().orEmpty()
    val sep = listOf('\t', ';', ',').maxBy { c -> first.count { it == c } }.takeIf { c -> first.contains(c) } ?: ','
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>(); val cell = StringBuilder(); var quoted = false; var i = 0
    fun endCell() { row += cell.toString().trim(); cell.clear() }
    fun endRow() { endCell(); if (row.any { it.isNotEmpty() }) rows += row; row = mutableListOf() }
    while (i < text.length) {
        val c = text[i]
        when {
            quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
            c == '"' -> quoted = !quoted
            quoted -> cell.append(c)
            c == sep -> endCell()
            c == '\n' -> endRow()
            c != '\r' -> cell.append(c)
        }
        i++
    }
    endRow()
    return rows
}

/** First worksheet of an .xlsx (a zip of XML files); no library needed. */
fun readXlsx(bytes: ByteArray): List<List<String>> {
    val files = mutableMapOf<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
        generateSequence { z.nextEntry }.forEach { e -> if (e.name.endsWith(".xml") || e.name.endsWith(".rels")) files[e.name] = z.readBytes() }
    }
    fun xml(name: String) = files[name]?.let { DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }.newDocumentBuilder().parse(ByteArrayInputStream(it)).documentElement }
    fun Element.all(tag: String) = getElementsByTagName(tag).let { l -> (0 until l.length).map { l.item(it) as Element } }
    val shared = xml("xl/sharedStrings.xml")?.all("si")?.map { si -> si.all("t").joinToString("") { it.textContent } }.orEmpty()
    // first sheet in workbook order -> its file via the relationships
    val rid = xml("xl/workbook.xml")?.all("sheet")?.firstOrNull()?.getAttribute("r:id")
    val target = xml("xl/_rels/workbook.xml.rels")?.all("Relationship")?.firstOrNull { it.getAttribute("Id") == rid }?.getAttribute("Target")
    val sheet = target?.let { "xl/" + it.removePrefix("/xl/").removePrefix("/") }?.takeIf { it in files }
        ?: files.keys.filter { it.startsWith("xl/worksheets/sheet") }.minOrNull() ?: error("No worksheet found")
    return xml(sheet)!!.all("row").map { r ->
        val out = mutableListOf<String>()
        r.all("c").forEach { c ->
            val col = c.getAttribute("r").takeWhile { it.isLetter() }.fold(0) { a, ch -> a * 26 + (ch.uppercaseChar() - 'A' + 1) } - 1
            val v = c.all("v").firstOrNull()?.textContent.orEmpty()
            val text = when (c.getAttribute("t")) {
                "s" -> shared.getOrNull(v.toIntOrNull() ?: -1).orEmpty()
                "inlineStr" -> c.all("t").joinToString("") { it.textContent }
                "str", "b", "e" -> v
                else -> plainNumber(v)
            }
            if (col >= 0) { while (out.size < col) out += ""; if (out.size == col) out += text.trim() else out[col] = text.trim() }
        }
        out
    }.filter { r -> r.any { it.isNotEmpty() } }
}

/** Excel stores phone numbers typed as numbers like 2.51945287216E11. */
fun plainNumber(v: String): String = try { BigDecimal(v).stripTrailingZeros().toPlainString() } catch (_: NumberFormatException) { v }

fun looksLikePhone(s: String): Boolean { val d = s.filter(Char::isDigit); return d.length in 7..15 && s.all { it.isDigit() || it in "+-() ." } }

/** Uses the first row as headers unless it looks like data (contains a phone number). */
fun toTable(rows: List<List<String>>, firstRowHeader: Boolean = rows.firstOrNull()?.none(::looksLikePhone) ?: true): Table {
    val width = rows.maxOfOrNull { it.size } ?: 0
    val head = if (firstRowHeader) rows.first() else emptyList()
    val headers = (0 until width).map { i -> head.getOrNull(i)?.takeIf { it.isNotBlank() } ?: "Column ${i + 1}" }
    return Table(headers, rows.drop(if (firstRowHeader) 1 else 0).map { r -> List(width) { r.getOrElse(it) { "" } } })
}

fun guessPhoneColumn(t: Table): Int =
    t.headers.indexOfFirst { Regex("phone|mobile|cell|number|tel|whatsapp|msisdn|contact|ስልክ", RegexOption.IGNORE_CASE).containsMatchIn(it) }.takeIf { it >= 0 }
        ?: t.headers.indices.maxByOrNull { i -> t.rows.count { looksLikePhone(it[i]) } } ?: 0

fun guessNameColumn(t: Table, phone: Int): Int =
    t.headers.indexOfFirst { Regex("name|ስም", RegexOption.IGNORE_CASE).containsMatchIn(it) }.takeIf { it >= 0 && it != phone }
        ?: t.headers.indices.firstOrNull { i -> i != phone && t.rows.any { it[i].any(Char::isLetter) } } ?: -1

/** Valid, de-duplicated recipients plus how many rows were skipped. */
fun recipients(t: Table, phone: Int, name: Int): Pair<List<Recipient>, Int> {
    val seen = HashSet<String>()
    val out = t.rows.mapNotNull { r ->
        val p = r[phone].filter { it.isDigit() || it == '+' }
        if (!looksLikePhone(r[phone]) || !seen.add(p.filter(Char::isDigit).takeLast(9))) return@mapNotNull null
        val fields = t.headers.zip(r).toMap().toMutableMap()
        if (name >= 0) { fields[NAME] = r[name]; fields[FIRST_NAME] = r[name].trim().substringBefore(' ') }
        Recipient(p, fields)
    }
    return out to (t.rows.size - out.size)
}

const val NAME = "Name"
const val FIRST_NAME = "First Name"

/** Replaces {Column} placeholders (case-insensitive) with the recipient's values; unknown ones are left as typed. */
fun fill(template: String, fields: Map<String, String>): String {
    val lower = fields.mapKeys { it.key.lowercase() }
    return Regex("\\{([^{}]+)\\}").replace(template) { m -> lower[m.groupValues[1].trim().lowercase()] ?: m.value }
}
