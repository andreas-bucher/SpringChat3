package ch.arcticsoft.springchat3.document

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Reads an uploaded PDF's own embedded outline (bookmarks) directly via
 * PDFBox, independent of [PdfTextExtractor]'s Spring AI-based page/text
 * extraction - `PagePdfDocumentReader` only exposes page text, not the
 * outline tree, so this class talks to PDFBox's own model classes
 * directly (build.gradle.kts's own doc comment on the explicit `pdfbox`
 * dependency has more on why that's a separate, explicit dependency rather
 * than relying on the one Spring AI's PDF reader pulls in transitively).
 *
 * API surface confirmed against PDFBox 3.0.7's own GitHub source (the exact
 * version this app depends on - see build.gradle.kts) before writing this,
 * given this project's history of API guesses going wrong, and because
 * PDFBox 3.x is a genuine breaking change from the 2.x API most examples
 * and search results still show:
 * - `PDDocument.load(...)` was removed entirely in 3.x; loading now goes
 *   through [org.apache.pdfbox.Loader.loadPDF] instead.
 * - [PDOutlineNode] (the common superclass of both
 *   `PDDocument.documentCatalog.documentOutline` - the tree's root - and
 *   every [PDOutlineItem] within it) exposes a `children(): Iterable<PDOutlineItem>`
 *   method, simpler than manually chasing `getFirstChild()`/`getNextSibling()`
 *   links by hand.
 * - An outline item's target page comes from its
 *   [org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination]
 *   (via `getDestination()`) or, if it uses an action instead, from a
 *   [PDActionGoTo]'s own destination - [PDPageDestination.retrievePageNumber]
 *   resolves either a direct page reference or an explicit page-number
 *   destination to the same zero-based page index, so this class never
 *   needs to distinguish the two cases itself.
 *
 * Returns null - never an empty [DocumentStructure] - when a PDF has no
 * outline at all, or the outline exists but has zero top-level entries;
 * [DocumentStructureStore]'s cache relies on an empty structure never being
 * a genuinely *stored* value for exactly this reason.
 */
@Component
class DocumentStructureExtractor {
    private val log = LoggerFactory.getLogger(DocumentStructureExtractor::class.java)

    fun extractStructure(pdfBytes: ByteArray): DocumentStructure? =
        try {
            Loader.loadPDF(pdfBytes).use { document ->
                val outline = document.documentCatalog?.documentOutline
                val nodes = outline?.let { walkChildren(it) }.orEmpty()
                nodes.takeIf { it.isNotEmpty() }?.let { DocumentStructure(it) }
            }
        } catch (e: Exception) {
            log.warn("Could not extract document structure/outline - continuing without it", e)
            null
        }

    private fun walkChildren(node: PDOutlineNode): List<StructureNode> =
        node.children().map { item -> toStructureNode(item) }

    private fun toStructureNode(item: PDOutlineItem): StructureNode =
        StructureNode(
            title = item.title?.trim().orEmpty(),
            pageNumber = resolvePageNumber(item),
            children = walkChildren(item),
        )

    /**
     * Resolves [item]'s target page, 1-based, or null if it can't be
     * resolved at all (e.g. the item points somewhere other than a page in
     * this same document - an external file or URI, which this app has no
     * use for). Checks the item's own destination first, falling back to a
     * [PDActionGoTo]'s destination if it uses an action instead - PDF
     * outlines can be built either way, and nothing here needs to know
     * which one a given PDF actually used.
     */
    private fun resolvePageNumber(item: PDOutlineItem): Int? =
        try {
            val destination = item.destination ?: (item.action as? PDActionGoTo)?.destination
            val zeroBasedPage = (destination as? PDPageDestination)?.retrievePageNumber() ?: -1
            zeroBasedPage.takeIf { it >= 0 }?.plus(1)
        } catch (e: Exception) {
            log.debug("Could not resolve a page number for outline item \"{}\" - leaving it unnumbered", item.title, e)
            null
        }
}
