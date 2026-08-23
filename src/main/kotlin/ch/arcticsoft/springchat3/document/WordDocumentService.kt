package ch.arcticsoft.springchat3.document

import jakarta.xml.bind.JAXBElement
import org.docx4j.TextUtils
import org.docx4j.openpackaging.packages.WordprocessingMLPackage
import org.docx4j.wml.P
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * One paragraph of a Word document as the chat tools see it: its [index]
 * (0-based, counting only paragraphs - never tables or other body content),
 * its plain [text], and its Word style id ([style], e.g. "Heading1",
 * "ListParagraph"; null for body text with no explicit style).
 *
 * [index] is a positional address, NOT a stable id - it shifts as soon as a
 * paragraph is inserted or deleted above it. That's a deliberate, agreed
 * trade-off (2026-08-23, the user chose "indexes + re-read after each write"
 * over text anchors or docx4j bookmarks), which is why every editing method
 * on [WordDocumentService] returns the document's new paragraph count and
 * every edit tool's description tells the model its indexes are stale
 * afterwards.
 */
data class WordParagraph(val index: Int, val text: String, val style: String?)

/**
 * All the docx4j this app contains (2026-08-23, user's own request "create
 * tools to read and write ms word documents. use docx4j."). Deliberately
 * byte-array in, byte-array out and completely unaware of [DocumentStore],
 * projects, chat or tools: the storage/re-indexing side lives in
 * [WordDocumentWorkspace] and the LLM-facing side in the two tool classes,
 * so this layer stays a plain, testable .docx manipulation library that the
 * REST layer could reuse just as easily.
 *
 * Scope of this first version: the document body's own top-level paragraphs.
 * Paragraphs nested inside tables, headers, footers and text boxes are
 * neither listed nor edited - [paragraphs] simply doesn't see them, so an
 * index never accidentally addresses one. Tables are left exactly as they
 * are by every edit here (they're skipped, not dropped: edits splice the
 * body's own content list rather than rebuilding it).
 *
 * Every docx4j API used here was confirmed against docx4j's own Getting
 * Started guide and its GitHub source before writing, per this project's
 * standard for external API shapes (see springchat3_native_tool_calling.md
 * risk #6 in project memory): `WordprocessingMLPackage.load(InputStream)` /
 * `.createPackage()` / `.save(OutputStream)`, `MainDocumentPart.getContent():
 * List<Object>` / `.createParagraphOfText(String): P` /
 * `.createStyledParagraphOfText(String, String): P`, `TextUtils.getText(Object):
 * String` (package `org.docx4j`, not `org.docx4j.text`), and
 * `P.getPPr()?.getPStyle()?.getVal()` for a paragraph's style id.
 *
 * Note what is NOT used: docx4j's `ObjectFactory`/`PPr` construction. Styling
 * only ever happens through `createStyledParagraphOfText`, which keeps the
 * docx4j surface this app depends on down to six methods - a deliberate
 * choice, since every additional generated-JAXB class is another thing to
 * get right without being able to compile here.
 */
@Component
class WordDocumentService {

    /**
     * Mini-markdown understood by every text-taking method here, so the LLM
     * can express structure without a second "style" parameter per line:
     * a line starting with `# `, `## ` or `### ` becomes Heading1/2/3, `- `
     * or `* ` becomes a ListParagraph bullet, anything else is body text.
     * Blank lines are dropped (they'd otherwise become empty paragraphs).
     * Deliberately tiny - it exists to cover the structure a chat reply
     * actually produces, not to be a Markdown implementation.
     */
    private fun styleAndTextFor(line: String): Pair<String?, String> {
        val trimmed = line.trim()
        return when {
            trimmed.startsWith("### ") -> "Heading3" to trimmed.removePrefix("### ").trim()
            trimmed.startsWith("## ") -> "Heading2" to trimmed.removePrefix("## ").trim()
            trimmed.startsWith("# ") -> "Heading1" to trimmed.removePrefix("# ").trim()
            trimmed.startsWith("- ") -> "ListParagraph" to trimmed.removePrefix("- ").trim()
            trimmed.startsWith("* ") -> "ListParagraph" to trimmed.removePrefix("* ").trim()
            else -> null to trimmed
        }
    }

    private fun load(bytes: ByteArray): WordprocessingMLPackage =
        WordprocessingMLPackage.load(ByteArrayInputStream(bytes))

    private fun save(pkg: WordprocessingMLPackage): ByteArray {
        val out = ByteArrayOutputStream()
        pkg.save(out)
        return out.toByteArray()
    }

    /**
     * A body content entry can arrive either as a bare JAXB object or
     * wrapped in a [JAXBElement] depending on the element - unwrapped here
     * rather than via docx4j's own `XmlUtils.unwrap`, which would be one
     * more external API to get exactly right for no gain.
     */
    private fun asParagraph(node: Any?): P? {
        val unwrapped = if (node is JAXBElement<*>) node.value else node
        return unwrapped as? P
    }

    private fun styleOf(p: P): String? = p.pPr?.pStyle?.getVal()?.ifBlank { null }

    private fun newParagraph(pkg: WordprocessingMLPackage, line: String): P {
        val (style, text) = styleAndTextFor(line)
        val part = pkg.mainDocumentPart
        return if (style == null) part.createParagraphOfText(text) else part.createStyledParagraphOfText(style, text)
    }

    private fun newParagraphs(pkg: WordprocessingMLPackage, text: String): List<P> =
        text.split("\n").filter { it.isNotBlank() }.map { newParagraph(pkg, it) }

    /**
     * Positions in the body's own content list of each paragraph, in order -
     * the bridge between a caller's paragraph index (0..n over paragraphs
     * only) and the real list position an edit has to splice at (which also
     * counts tables and anything else in the body).
     */
    private fun paragraphPositions(content: List<Any?>): List<Int> =
        content.indices.filter { asParagraph(content[it]) != null }

    fun paragraphs(bytes: ByteArray): List<WordParagraph> {
        val content = load(bytes).mainDocumentPart.content
        return paragraphPositions(content).mapIndexed { index, position ->
            val p = asParagraph(content[position])!!
            WordParagraph(index, TextUtils.getText(p).trim(), styleOf(p))
        }
    }

    /** The whole document as plain text - what [DocumentStore]/[DocumentIndex] get re-fed after an edit. */
    fun plainText(bytes: ByteArray): String = paragraphs(bytes).joinToString("\n\n") { it.text }

    fun create(text: String): ByteArray {
        val pkg = WordprocessingMLPackage.createPackage()
        val content = pkg.mainDocumentPart.content
        newParagraphs(pkg, text).forEach { content.add(it) }
        return save(pkg)
    }

    fun append(bytes: ByteArray, text: String): ByteArray {
        val pkg = load(bytes)
        val content = pkg.mainDocumentPart.content
        newParagraphs(pkg, text).forEach { content.add(it) }
        return save(pkg)
    }

    /**
     * Inserts after paragraph [afterIndex], or at the very top when
     * [afterIndex] is -1 - the one out-of-range value that's meaningful
     * rather than an error, since "insert before the first paragraph" has no
     * other way to be expressed in a purely index-based scheme.
     */
    fun insertAfter(bytes: ByteArray, afterIndex: Int, text: String): ByteArray {
        val pkg = load(bytes)
        val content = pkg.mainDocumentPart.content
        val positions = paragraphPositions(content)
        val at = when {
            afterIndex < 0 -> 0
            afterIndex >= positions.size -> content.size
            else -> positions[afterIndex] + 1
        }
        newParagraphs(pkg, text).forEachIndexed { offset, p -> content.add(at + offset, p) }
        return save(pkg)
    }

    /**
     * Replaces paragraph [index]'s text, keeping its style. Any formatting
     * applied to parts of the paragraph (a bold phrase, a hyperlink) is lost
     * - the paragraph is rebuilt as a single run. That's inherent to
     * replacing text through docx4j without run-level diffing, and it only
     * affects the paragraph actually being replaced.
     */
    fun replaceParagraph(bytes: ByteArray, index: Int, text: String): ByteArray {
        val pkg = load(bytes)
        val content = pkg.mainDocumentPart.content
        val positions = paragraphPositions(content)
        require(index in positions.indices) { "No paragraph $index - the document has ${positions.size}." }
        val position = positions[index]
        val existingStyle = styleOf(asParagraph(content[position])!!)
        val (lineStyle, lineText) = styleAndTextFor(text)
        val style = lineStyle ?: existingStyle
        val part = pkg.mainDocumentPart
        content[position] = if (style == null) part.createParagraphOfText(lineText) else part.createStyledParagraphOfText(style, lineText)
        return save(pkg)
    }

    fun deleteParagraph(bytes: ByteArray, index: Int): ByteArray {
        val pkg = load(bytes)
        val content = pkg.mainDocumentPart.content
        val positions = paragraphPositions(content)
        require(index in positions.indices) { "No paragraph $index - the document has ${positions.size}." }
        content.removeAt(positions[index])
        return save(pkg)
    }

    /**
     * Find/replace across the body's paragraphs, returning the new bytes and
     * how many paragraphs were changed (null bytes when nothing matched, so
     * a caller can skip the whole save/re-index cycle).
     *
     * Works paragraph-at-a-time on each paragraph's *full* text rather than
     * per `<w:t>` run - the standard trap with .docx find/replace is that
     * Word splits a sentence across several runs (spell-check state,
     * formatting, tracked-changes remnants), so a run-by-run search silently
     * misses text that looks perfectly contiguous in Word. The cost is the
     * same as [replaceParagraph]'s: a changed paragraph keeps its style but
     * loses any formatting applied to parts of it. Untouched paragraphs are
     * left completely alone.
     */
    fun replaceText(bytes: ByteArray, find: String, replacement: String, replaceAll: Boolean): Pair<ByteArray?, Int> {
        require(find.isNotEmpty()) { "Nothing to find - the search text is empty." }
        val pkg = load(bytes)
        val content = pkg.mainDocumentPart.content
        val part = pkg.mainDocumentPart
        var changed = 0
        for (position in paragraphPositions(content)) {
            if (!replaceAll && changed > 0) break
            val p = asParagraph(content[position])!!
            val text = TextUtils.getText(p)
            if (!text.contains(find)) continue
            val updated = if (replaceAll) text.replace(find, replacement) else text.replaceFirst(find, replacement)
            val style = styleOf(p)
            content[position] = if (style == null) part.createParagraphOfText(updated) else part.createStyledParagraphOfText(style, updated)
            changed++
        }
        return if (changed == 0) null to 0 else save(pkg) to changed
    }
}
