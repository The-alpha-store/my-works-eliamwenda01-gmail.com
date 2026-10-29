package com.willykez.campconnect.data.docs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentAnalyzerTest {

  private val normalisation = """
    Database normalization structures relational tables to reduce redundancy and update anomalies.
    Functional dependencies describe how one attribute determines another attribute in a relation.
    First normal form requires that every attribute holds a single atomic value in a relation.
    Second normal form removes partial dependencies on a composite key in a relation table.
    Third normal form removes transitive dependencies so that attributes depend only on the key.
    Normalization reduces redundancy, but too much normalization can slow down queries on relational tables.
  """.trimIndent()

  @Test
  fun `summary is built from sentences of the document itself`() {
    val result = DocumentAnalyzer.analyse(normalisation)
    assertTrue(result.summary.isNotBlank())
    // Extractive: every sentence of the summary appears in the source.
    result.summary.split(Regex("(?<=[.!?])\\s+")).forEach { sentence ->
      assertTrue("not from the document: $sentence", normalisation.replace('\n', ' ').contains(sentence.trim().removeSuffix("…")))
    }
    assertTrue(result.summary.length <= 725)
  }

  @Test
  fun `key topics reflect the repeated terms`() {
    val topics = DocumentAnalyzer.analyse(normalisation).keyTopics.map { it.lowercase() }
    assertTrue(topics.isNotEmpty())
    assertTrue(topics.any { "normal" in it || "relation" in it || "attribute" in it || "dependenc" in it })
    assertFalse(topics.any { it == "the" || it == "and" })
  }

  @Test
  fun `counts words and estimates reading time`() {
    val text = (1..450).joinToString(" ") { "concept$it" } + "."
    val result = DocumentAnalyzer.analyse(text)
    assertEquals(450, result.wordCount)
    assertEquals(3, result.readingMinutes)
  }

  @Test
  fun `written learning objectives are used when the document has them`() {
    val text = """
      Learning objectives
      Explain how a binary search tree keeps its keys in order
      Insert and delete nodes while keeping the tree valid
      Compare the cost of searching a tree and a sorted list

      A binary search tree stores keys so that smaller keys sit on the left and larger keys on the right of each node.
    """.trimIndent()
    val result = DocumentAnalyzer.analyse(text)
    assertEquals(3, result.objectives.size)
    assertTrue(result.objectives.first().startsWith("Explain how a binary search tree"))
  }

  @Test
  fun `swahili text is detected and gets swahili study tips`() {
    val text = """
      Hifadhidata ni mkusanyiko wa taarifa ambazo zimepangwa ili kupatikana kwa urahisi na haraka sana.
      Jedwali la hifadhidata lina safu na nguzo ambazo zinaonyesha uhusiano kati ya taarifa za wanafunzi.
      Kila jedwali linapaswa kuwa na ufunguo ambao unatambulisha kila safu kwa njia ya kipekee katika mfumo.
    """.trimIndent()
    val result = DocumentAnalyzer.analyse(text)
    assertTrue(result.isSwahili)
    assertTrue(result.studyTips.first().startsWith("Soma"))
  }

  @Test
  fun `short slide-style text still produces a summary`() {
    val text = "TCP congestion control\n• Slow start\n• Congestion avoidance\n• Fast retransmit\n• Fast recovery"
    val result = DocumentAnalyzer.analyse(text)
    assertTrue(result.summary.contains("Slow start"))
  }

  @Test
  fun `document kinds are decided by extension then by mime type`() {
    assertEquals(DocumentKind.PDF, DocumentKind.of("notes.PDF"))
    assertEquals(DocumentKind.WORD, DocumentKind.of("essay.docx"))
    assertEquals(DocumentKind.SLIDES, DocumentKind.of("lecture.pptx"))
    assertEquals(DocumentKind.SHEET, DocumentKind.of("marks.xlsx"))
    assertEquals(DocumentKind.IMAGE, DocumentKind.of("scan", "image/jpeg"))
    assertEquals(DocumentKind.OTHER, DocumentKind.of("archive.zip", "application/zip"))
    assertFalse(DocumentKind.isAnalysable("movie.mp4", "video/mp4"))
  }
}
