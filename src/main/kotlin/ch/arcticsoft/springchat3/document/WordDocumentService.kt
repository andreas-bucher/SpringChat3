package ch.arcticsoft.springchat3.document

import jakarta.xml.bind.JAXBElement
import org.docx4j.TextUtils
import org.docx4j.openpackaging.packages.WordprocessingMLPackage
import org.docx4j.wml.JcEnumeration
import org.docx4j.wml.ObjectFactory
import org.docx4j.wml.P
import org.docx4j.wml.PPr
import org.docx4j.wml.PPrBase
import org.docx4j.wml.R
import org.docx4j.wml.RPr
import org.docx4j.wml.STLineSpacingRule
import org.docx4j.wml.Styles
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger

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
 * One style a document actually DEFINES, as `list_word_styles` reports it.
 *
 * The distinction matters more than it looks: `pStyle` and
 * `createStyledParagraphOfText` take a **styleId**, and applying an id the
 * document does not define does not fail - Word silently renders it as
 * Normal. A German-authored document may have no "Heading1" at all, so
 * "apply Heading 1" has to be resolved against this list (or through
 * `StyleDefinitionsPart.getIDForStyleName`) rather than guessed.
 */
data class WordStyle(val styleId: String, val name: String?)

/**
 * What one paragraph's formatting looks like, as far as a report needs to
 * see it. Everything here is DIRECT run formatting except [style] - that is
 * the point of the type: [WordParagraph] already shows the style, and direct
 * formatting is the half the model has been blind to.
 *
 * [directlyFormattedRuns] against [runs] is the number that answers "why did
 * my font change do nothing": direct formatting beats both docDefaults and
 * the style, so a document pasted in from elsewhere overrides everything.
 *
 * [fonts] holds [THEME_FONT] rather than a face name for a run whose
 * `rFonts` carries a theme attribute - Word resolves those through
 * theme1.xml and ignores the explicit name, which is its own reason a font
 * change appears not to stick.
 *
 * [allRunsBold] on a paragraph with no heading style is the signature of the
 * commonest defect in a hand-formatted document: a heading that is really
 * body text in bold, invisible to the navigation pane and to any TOC.
 */
data class WordParagraphFormat(
    val index: Int,
    val style: String?,
    val runs: Int,
    val directlyFormattedRuns: Int,
    val allRunsBold: Boolean,
    val fonts: Set<String>,
    val sizesPt: Set<Int>,
    val listNumbered: Boolean,
    val manuallyNumbered: Boolean,
    val empty: Boolean,
)

/**
 * [WordDocumentService.formatting]'s whole answer: every paragraph, the
 * styles the document defines, and the weakest level of the precedence
 * chain - `docDefaults`, which is what Word's *Change Styles -> Fonts* writes
 * and what a document-wide font change should target.
 */
data class WordFormattingReport(
    val paragraphs: List<WordParagraphFormat>,
    val styles: List<WordStyle>,
    val defaultFont: String?,
    val defaultSizePt: Int?,
)

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

    companion object {
        /**
         * Stands in for a face name when the run resolves its font through
         * the document theme - see [WordParagraphFormat.fonts].
         */
        const val THEME_FONT = "(theme font)"

        /**
         * Typed-in numbering: "1." "2)" "a)" "iv." or a dash used as a
         * bullet. A heuristic, and only ever reported - never acted on - so
         * a false positive costs a line in a report and nothing else. What
         * it is FOR is the gap between this and `pPr/numPr`: a paragraph
         * that looks numbered but is not a real Word list is why numbering
         * breaks when anything is inserted above it.
         */
        private val MANUAL_NUMBERING = Regex("""^\s*(\d+[.)]|[ivxIVX]+[.)]|[a-zA-Z][.)]|[-*\u2022\u2013\u2014])\s+""")
    }

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

    /**
     * The read half of formatting support (2026-08-29, from the design
     * agreed 2026-08-24 - springchat3_word_formatting_design.md in project
     * memory). Read-only on purpose and shipped on its own: it cannot
     * corrupt a document, needs no undo, and it answers the question the
     * write tools would otherwise be blamed for - "why did nothing change?".
     *
     * Every docx4j member touched below was verified on 2026-08-29 against
     * the actual bundled artifact (docx4j 11.5.14, read out of this app's
     * own fat jar), not against documentation: `DocumentPart.
     * getStyleDefinitionsPart()`, `JaxbXmlPart.getJaxbElement()` (erased to
     * Object, hence the cast), `Styles.getStyle()/getDocDefaults()`,
     * `DocDefaults.getRPrDefault().getRPr()`, `Style.getStyleId()/getName().
     * getVal()`, `RPrAbstract.getRFonts()/getSz()/getB()`, `RFonts`'s four
     * name slots and four theme slots, `HpsMeasure.getVal(): BigInteger` and
     * `BooleanDefaultTrue.isVal(): boolean`.
     */
    private fun stylesPartOf(pkg: WordprocessingMLPackage): Styles? =
        pkg.mainDocumentPart.styleDefinitionsPart?.jaxbElement as? Styles

    private fun styleList(styles: Styles?): List<WordStyle> =
        styles?.style.orEmpty()
            .mapNotNull { style ->
                style.styleId?.ifBlank { null }?.let { WordStyle(it, style.name?.getVal()?.ifBlank { null }) }
            }
            .sortedBy { it.styleId }

    /**
     * The runs of a paragraph, unwrapped the same way [asParagraph] unwraps
     * body content - a paragraph's children arrive as bare objects or inside
     * a [JAXBElement] depending on the element. Anything that is not a run
     * (bookmarks, hyperlinks, proofing marks) is simply skipped: this is a
     * report, and a hyperlink's own runs are not where a document-wide font
     * problem lives.
     */
    private fun runsOf(p: P): List<R> = p.content.mapNotNull { node ->
        val unwrapped = if (node is JAXBElement<*>) node.value else node
        unwrapped as? R
    }

    /**
     * Whether this run carries direct formatting at all - the level that
     * beats both docDefaults and the paragraph's style. Deliberately narrow:
     * only the properties this feature can also SET are counted, so the
     * number it produces is one the user can act on.
     */
    private fun hasDirectFormatting(rPr: RPr?): Boolean =
        rPr != null &&
            (rPr.rFonts != null || rPr.sz != null || rPr.szCs != null || rPr.b != null || rPr.i != null || rPr.color != null)

    /**
     * A theme attribute wins over the explicit name beside it, so a run
     * carrying one is reported as [THEME_FONT] rather than as whatever
     * `ascii` happens to say - naming that face would be worse than saying
     * nothing, since Word is not using it.
     */
    private fun fontOf(rPr: RPr?): String? {
        val fonts = rPr?.rFonts ?: return null
        if (fonts.asciiTheme != null || fonts.hAnsiTheme != null) return THEME_FONT
        return fonts.ascii?.ifBlank { null } ?: fonts.hAnsi?.ifBlank { null }
    }

    /** Word stores sizes in HALF-points; 22 means 11pt. An odd value (10.5pt) rounds up. */
    private fun sizePtOf(rPr: RPr?): Int? = rPr?.sz?.getVal()?.let { (it.toInt() + 1) / 2 }

    /**
     * Paragraph-level formatting - spacing, alignment and indentation
     * (2026-08-29). Added after reading the app's own stored traces: "change
     * paragraph format, less space between lines" was asked three times in
     * one session and there was no tool for it, so the model set the font
     * size instead and then told the user there was no excess spacing to
     * adjust. A confident wrong answer produced by a missing capability.
     *
     * **Three different units, which is the whole difficulty here.**
     *  - `spacing/@before` and `@after` are TWENTIETHS OF A POINT: 12pt = 240.
     *  - `spacing/@line` under `lineRule="auto"` is 240THS OF A LINE, so
     *    single spacing is 240, 1.5 is 360 and double is 480 - it is a
     *    multiple, not a measurement, and mixing it up with the twips above
     *    produces line spacing roughly twenty times too large.
     *  - `ind/@left`, `@right`, `@firstLine` are TWIPS: 1440 to the inch, so
     *    a centimetre is 1440/2.54 = 566.93, rounded here.
     *
     * Everything is optional and null means "leave alone", the same rule
     * [applyRunProperties] follows, so asking for less space after a
     * paragraph cannot silently reset its indentation.
     */
    private fun ensureSpacing(pPr: PPr): PPrBase.Spacing =
        pPr.spacing ?: factory.createPPrBaseSpacing().also { pPr.spacing = it }

    private fun ensureIndent(pPr: PPr): PPrBase.Ind =
        pPr.ind ?: factory.createPPrBaseInd().also { pPr.ind = it }

    private fun twipsFromPoints(points: Double): BigInteger = BigInteger.valueOf(Math.round(points * 20.0))

    private fun twipsFromCentimetres(cm: Double): BigInteger = BigInteger.valueOf(Math.round(cm * 1440.0 / 2.54))

    /** A line-spacing MULTIPLE (1.0 single, 1.5, 2.0) as Word's 240ths of a line. */
    private fun lineFromMultiple(multiple: Double): BigInteger = BigInteger.valueOf(Math.round(multiple * 240.0))

    private fun applyParagraphSpacing(pPr: PPr, beforePt: Double?, afterPt: Double?, lineSpacing: Double?) {
        if (beforePt == null && afterPt == null && lineSpacing == null) return
        val spacing = ensureSpacing(pPr)
        if (beforePt != null) spacing.before = twipsFromPoints(beforePt)
        if (afterPt != null) spacing.after = twipsFromPoints(afterPt)
        if (lineSpacing != null) {
            spacing.line = lineFromMultiple(lineSpacing)
            // Without AUTO the value above is read as an exact measurement in
            // twentieths of a point, which is not what a multiple means.
            spacing.lineRule = STLineSpacingRule.AUTO
        }
    }

    /**
     * Word's own names, not CSS ones: BOTH is what it calls justified, and
     * LEFT/RIGHT rather than START/END, which are the bidi-aware variants
     * this app has no use for.
     */
    fun alignmentValue(alignment: String): JcEnumeration? = when (alignment.trim().lowercase()) {
        "left" -> JcEnumeration.LEFT
        "center", "centre", "centered", "centred" -> JcEnumeration.CENTER
        "right" -> JcEnumeration.RIGHT
        "justify", "justified", "both" -> JcEnumeration.BOTH
        else -> null
    }

    fun setParagraphSpacing(
        bytes: ByteArray,
        from: Int,
        to: Int,
        beforePt: Double?,
        afterPt: Double?,
        lineSpacing: Double?,
    ): Pair<ByteArray?, Int> {
        if (beforePt == null && afterPt == null && lineSpacing == null) return null to 0
        val pkg = load(bytes)
        val content = pkg.mainDocumentPart.content
        var changed = 0
        paragraphsInRange(content, from, to).forEach { p ->
            applyParagraphSpacing(ensurePPr(p), beforePt, afterPt, lineSpacing)
            changed++
        }
        return if (changed == 0) null to 0 else save(pkg) to changed
    }

    fun setParagraphAlignment(bytes: ByteArray, from: Int, to: Int, alignment: JcEnumeration): Pair<ByteArray?, Int> {
        val pkg = load(bytes)
        val content = pkg.mainDocumentPart.content
        var changed = 0
        paragraphsInRange(content, from, to).forEach { p ->
            val pPr = ensurePPr(p)
            val jc = pPr.jc ?: factory.createJc().also { pPr.jc = it }
            jc.setVal(alignment)
            changed++
        }
        return if (changed == 0) null to 0 else save(pkg) to changed
    }

    fun setParagraphIndent(
        bytes: ByteArray,
        from: Int,
        to: Int,
        leftCm: Double?,
        rightCm: Double?,
        firstLineCm: Double?,
    ): Pair<ByteArray?, Int> {
        if (leftCm == null && rightCm == null && firstLineCm == null) return null to 0
        val pkg = load(bytes)
        val content = pkg.mainDocumentPart.content
        var changed = 0
        paragraphsInRange(content, from, to).forEach { p ->
            val ind = ensureIndent(ensurePPr(p))
            if (leftCm != null) ind.left = twipsFromCentimetres(leftCm)
            if (rightCm != null) ind.right = twipsFromCentimetres(rightCm)
            if (firstLineCm != null) ind.firstLine = twipsFromCentimetres(firstLineCm)
            changed++
        }
        return if (changed == 0) null to 0 else save(pkg) to changed
    }

    /**
     * The same three properties on a STYLE - which is where "less space
     * between lines" usually belongs. Setting it on Normal fixes the whole
     * document in one write and keeps every paragraph added later consistent,
     * where the paragraph-level call fixes only the range it was given.
     *
     * Returns null when the document defines no such style, so the caller can
     * say so rather than silently doing nothing.
     */
    fun setStyleParagraphFormat(
        bytes: ByteArray,
        styleId: String,
        beforePt: Double?,
        afterPt: Double?,
        lineSpacing: Double?,
        alignment: JcEnumeration?,
    ): ByteArray? {
        if (beforePt == null && afterPt == null && lineSpacing == null && alignment == null) return null
        val pkg = load(bytes)
        val styles = stylesPartOf(pkg) ?: return null
        val style = styles.style.orEmpty().firstOrNull { it.styleId == styleId } ?: return null
        val pPr = style.pPr ?: factory.createPPr().also { style.pPr = it }
        applyParagraphSpacing(pPr, beforePt, afterPt, lineSpacing)
        if (alignment != null) {
            val jc = pPr.jc ?: factory.createJc().also { pPr.jc = it }
            jc.setVal(alignment)
        }
        return save(pkg)
    }

    /** Every style the document defines, for `list_word_styles`. */
    fun styles(bytes: ByteArray): List<WordStyle> = styleList(stylesPartOf(load(bytes)))

    /** The full formatting picture - see [WordFormattingReport]. */
    fun formatting(bytes: ByteArray): WordFormattingReport {
        val pkg = load(bytes)
        val content = pkg.mainDocumentPart.content
        val paragraphs = paragraphPositions(content).mapIndexed { index, position ->
            val p = asParagraph(content[position])!!
            val runs = runsOf(p)
            val text = TextUtils.getText(p).trim()
            WordParagraphFormat(
                index = index,
                style = styleOf(p),
                runs = runs.size,
                directlyFormattedRuns = runs.count { hasDirectFormatting(it.rPr) },
                allRunsBold = runs.isNotEmpty() && runs.all { it.rPr?.b?.isVal == true },
                fonts = runs.mapNotNull { fontOf(it.rPr) }.toSet(),
                sizesPt = runs.mapNotNull { sizePtOf(it.rPr) }.toSet(),
                listNumbered = p.pPr?.numPr != null,
                manuallyNumbered = text.isNotBlank() && MANUAL_NUMBERING.containsMatchIn(text),
                empty = text.isBlank(),
            )
        }
        val styles = stylesPartOf(pkg)
        val defaults = styles?.docDefaults?.rPrDefault?.rPr
        return WordFormattingReport(
            paragraphs = paragraphs,
            styles = styleList(styles),
            defaultFont = fontOf(defaults),
            defaultSizePt = sizePtOf(defaults),
        )
    }

    /**
     * The write half of formatting support (2026-08-29). One method per
     * level of Word's precedence chain -
     * `docDefaults -> style -> direct run formatting`, weakest to strongest -
     * because which level you write to decides whether a change sticks at all
     * and whether it is one edit or thousands. A single `set_font` that
     * guessed the level would be wrong roughly half the time.
     *
     * Five traps are honoured here rather than discovered later; the first
     * four are why this is not two lines per method:
     *  1. An existing `PPr`/`RPr` is REUSED, never replaced - replacing one
     *     discards the numbering, spacing, indentation or language sitting
     *     beside the property being changed.
     *  2. Sizes are HALF-points (11pt -> 22), and `szCs` is set alongside
     *     `sz` or complex-script runs keep the old size.
     *  3. `rFonts` has four slots (ascii, hAnsi, cs, eastAsia); setting only
     *     `ascii` leaves part of the text on the old face.
     *  4. A theme attribute BEATS the explicit name, so an explicit font must
     *     clear all four theme slots or Word keeps resolving through
     *     theme1.xml and nothing appears to happen.
     *  5. A defined style can be latent and ignored until activated, which
     *     [setParagraphStyle] does through the property resolver.
     *
     * Every one of the docx4j members used was verified on 2026-08-29 against
     * the bundled artifact (docx4j 11.5.14) rather than against
     * documentation - see springchat3_word_formatting_read_half.md in project
     * memory for the full list and for what the docs got wrong.
     *
     * What is deliberately NOT here: any automatic stripping of direct
     * formatting. That is [clearDirectFormatting]'s job and only ever on the
     * user's say-so - a font change reports the conflict instead, because
     * silently discarding somebody's deliberate local formatting is worse
     * than a font change that did not take.
     */
    private val factory = ObjectFactory()

    private fun ensurePPr(p: P): PPr = p.pPr ?: factory.createPPr().also { p.pPr = it }

    private fun ensureRPr(r: R): RPr = r.rPr ?: factory.createRPr().also { r.rPr = it }

    /**
     * Sets only the properties actually asked for - a null argument means
     * "leave this alone", never "clear it", so "make it bold" cannot quietly
     * reset a font.
     */
    private fun applyRunProperties(
        rPr: RPr,
        fontName: String?,
        sizePt: Int?,
        bold: Boolean?,
        italic: Boolean?,
        color: String?,
    ) {
        if (fontName != null) {
            val fonts = rPr.rFonts ?: factory.createRFonts().also { rPr.rFonts = it }
            fonts.ascii = fontName
            fonts.hAnsi = fontName
            fonts.cs = fontName
            fonts.eastAsia = fontName
            // Trap 4: without this the explicit name above is ignored.
            fonts.asciiTheme = null
            fonts.hAnsiTheme = null
            fonts.cstheme = null
            fonts.eastAsiaTheme = null
        }
        if (sizePt != null) {
            val halfPoints = BigInteger.valueOf(sizePt.toLong() * 2)
            rPr.sz = factory.createHpsMeasure().also { it.setVal(halfPoints) }
            rPr.szCs = factory.createHpsMeasure().also { it.setVal(halfPoints) }
        }
        if (bold != null) rPr.b = factory.createBooleanDefaultTrue().also { it.setVal(bold) }
        if (italic != null) rPr.i = factory.createBooleanDefaultTrue().also { it.setVal(italic) }
        if (color != null) rPr.color = factory.createColor().also { it.setVal(color) }
    }

    /** The paragraphs of [content] whose 0-based index falls in [from]..[to], clamped to what exists. */
    private fun paragraphsInRange(content: List<Any?>, from: Int, to: Int): List<P> =
        paragraphPositions(content)
            .filterIndexed { index, _ -> index in from..to }
            .mapNotNull { position -> asParagraph(content[position]) }

    /**
     * Applies [styleId] to a range of paragraphs - the Word-native
     * structural fix, which is what turns bold pseudo-headings into real
     * headings and so makes the navigation pane, a table of contents and any
     * later restyling start working as a consequence.
     *
     * [styleId] must already be a style the document DEFINES; resolving a
     * display name ("Heading 1") to an id is the caller's job, because
     * applying an undefined id does not fail - Word silently renders it as
     * Normal, which is indistinguishable from the tool doing nothing.
     */
    fun setParagraphStyle(bytes: ByteArray, from: Int, to: Int, styleId: String): Pair<ByteArray?, Int> {
        val pkg = load(bytes)
        val content = pkg.mainDocumentPart.content
        var changed = 0
        paragraphsInRange(content, from, to).forEach { p ->
            if (p.pPr?.pStyle?.getVal() == styleId) return@forEach
            val pPr = ensurePPr(p)
            val pStyle = pPr.pStyle ?: factory.createPPrBasePStyle().also { pPr.pStyle = it }
            pStyle.setVal(styleId)
            changed++
        }
        if (changed == 0) return null to 0
        // Trap 5. Best-effort: a resolver that cannot activate the style is
        // not a reason to throw away an edit the user asked for.
        runCatching { pkg.mainDocumentPart.propertyResolver.activateStyle(styleId) }
        return save(pkg) to changed
    }

    /**
     * Writes `docDefaults` - the weakest level, and the one Word's own
     * *Change Styles -> Fonts* writes. One call, whole document, and it is
     * what "make this document Calibri 11" should mean.
     */
    fun setDocumentFont(bytes: ByteArray, fontName: String?, sizePt: Int?): ByteArray? {
        if (fontName == null && sizePt == null) return null
        val pkg = load(bytes)
        val styles = stylesPartOf(pkg) ?: return null
        val docDefaults = styles.docDefaults ?: factory.createDocDefaults().also { styles.docDefaults = it }
        val rPrDefault = docDefaults.rPrDefault
            ?: factory.createDocDefaultsRPrDefault().also { docDefaults.rPrDefault = it }
        val rPr = rPrDefault.rPr ?: factory.createRPr().also { rPrDefault.rPr = it }
        applyRunProperties(rPr, fontName, sizePt, null, null, null)
        return save(pkg)
    }

    /**
     * Writes one style's own run properties - "Heading 1 should be Georgia 16
     * bold", consistent for every paragraph carrying that style, now and
     * later. Returns null when the document does not define [styleId].
     */
    fun setStyleFont(
        bytes: ByteArray,
        styleId: String,
        fontName: String?,
        sizePt: Int?,
        bold: Boolean?,
        color: String?,
    ): ByteArray? {
        if (fontName == null && sizePt == null && bold == null && color == null) return null
        val pkg = load(bytes)
        val styles = stylesPartOf(pkg) ?: return null
        val style = styles.style.orEmpty().firstOrNull { it.styleId == styleId } ?: return null
        val rPr = style.rPr ?: factory.createRPr().also { style.rPr = it }
        applyRunProperties(rPr, fontName, sizePt, bold, null, color)
        return save(pkg)
    }

    /**
     * Direct run formatting on a range - the strongest level, for a title
     * page, a caption, one callout. Deliberately the last resort of the
     * three: what it writes is exactly what makes a later document-wide
     * change appear to do nothing.
     */
    fun setParagraphFont(
        bytes: ByteArray,
        from: Int,
        to: Int,
        fontName: String?,
        sizePt: Int?,
        bold: Boolean?,
        italic: Boolean?,
        color: String?,
    ): Pair<ByteArray?, Int> {
        if (fontName == null && sizePt == null && bold == null && italic == null && color == null) return null to 0
        val pkg = load(bytes)
        val content = pkg.mainDocumentPart.content
        var changed = 0
        paragraphsInRange(content, from, to).forEach { p ->
            val runs = runsOf(p)
            if (runs.isEmpty()) return@forEach
            runs.forEach { applyRunProperties(ensureRPr(it), fontName, sizePt, bold, italic, color) }
            changed++
        }
        return if (changed == 0) null to 0 else save(pkg) to changed
    }

    /**
     * Removes the direct run formatting this feature can set, over a range -
     * the counterpart that makes [setDocumentFont] and [setStyleFont] work at
     * all, since what they write is otherwise overridden run by run.
     *
     * Clears only those six properties, NOT the whole `rPr`. Nulling the
     * element wholesale would also drop highlighting, character spacing, the
     * run's language and any character style - none of which this feature
     * claims to manage, and all of which the user never asked to lose. That
     * makes this narrower than Word's own "Clear All Formatting", on purpose.
     */
    fun clearDirectFormatting(bytes: ByteArray, from: Int, to: Int): Pair<ByteArray?, Int> {
        val pkg = load(bytes)
        val content = pkg.mainDocumentPart.content
        var changed = 0
        paragraphsInRange(content, from, to).forEach { p ->
            var touched = false
            runsOf(p).forEach { run ->
                val rPr = run.rPr
                if (rPr != null && hasDirectFormatting(rPr)) {
                    rPr.rFonts = null
                    rPr.sz = null
                    rPr.szCs = null
                    rPr.b = null
                    rPr.i = null
                    rPr.color = null
                    touched = true
                }
            }
            if (touched) changed++
        }
        return if (changed == 0) null to 0 else save(pkg) to changed
    }

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
