package com.example.bulksms

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ModelTest {
    @Test fun csv() {
        val rows = readRows("a.csv", "﻿Name,Phone,City\r\n\"Kebede, Abebe\",+251 911 223 344,Addis\n\nSara,0922334455,\"He said \"\"hi\"\"\"\n".toByteArray())
        assertEquals(listOf(listOf("Name", "Phone", "City"), listOf("Kebede, Abebe", "+251 911 223 344", "Addis"), listOf("Sara", "0922334455", "He said \"hi\"")), rows)
        assertEquals(listOf(listOf("a", "b"), listOf("1", "2")), readDelimited("a;b\n1;2"))
        assertEquals(listOf(listOf("a", "b")), readDelimited("a\tb"))
    }

    @Test fun xlsx() {
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                fun put(n: String, s: String) { z.putNextEntry(ZipEntry(n)); z.write(s.toByteArray()); z.closeEntry() }
                put("xl/workbook.xml", """<workbook xmlns:r="r"><sheets><sheet name="People" sheetId="1" r:id="rId1"/></sheets></workbook>""")
                put("xl/_rels/workbook.xml.rels", """<Relationships><Relationship Id="rId1" Target="worksheets/sheet1.xml"/></Relationships>""")
                put("xl/sharedStrings.xml", """<sst><si><t>Name</t></si><si><t>Phone</t></si><si><r><t>Abebe </t></r><r><t>Kebede</t></r></si></sst>""")
                put("xl/worksheets/sheet1.xml", """<worksheet><sheetData>
                    <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>
                    <row r="2"><c r="A2" t="s"><v>2</v></c><c r="B2"><v>2.51911223344E11</v></c></row>
                    <row r="3"><c r="B3" t="inlineStr"><is><t>0922334455</t></is></c></row>
                    </sheetData></worksheet>""")
            }
        }.toByteArray()
        assertEquals(listOf(listOf("Name", "Phone"), listOf("Abebe Kebede", "251911223344"), listOf("", "0922334455")), readRows("x.xlsx", bytes))
    }

    @Test fun recipientsAndFill() {
        val t = toTable(listOf(listOf("Full name", "Mobile"), listOf("Abebe Kebede", "+251911223344"), listOf("Sara", "0911223344"), listOf("Bad", "12"), listOf("Hana", "0922334455")))
        assertEquals(1, guessPhoneColumn(t)); assertEquals(0, guessNameColumn(t, 1))
        val (people, skipped) = recipients(t, 1, 0)
        assertEquals(listOf("+251911223344", "0922334455"), people.map { it.phone }) // Sara is a duplicate number, Bad is invalid
        assertEquals(2, skipped)
        assertEquals("Hi Abebe, Abebe Kebede {Unknown}", fill("Hi {first name}, {Full name} {Unknown}", people[0].fields))
        // no header row: first row is data
        val n = toTable(listOf(listOf("0911223344", "Sara")))
        assertEquals(listOf("Column 1", "Column 2"), n.headers); assertEquals(1, n.rows.size)
        assertEquals(Table(emptyList(), emptyList()), toTable(emptyList()))
    }
}
