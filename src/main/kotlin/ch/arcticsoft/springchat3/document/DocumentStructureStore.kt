package ch.arcticsoft.springchat3.document

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * One entry in a document's outline/table-of-contents, as extracted by
 * [DocumentStructureExtractor] from the PDF's own embedded bookmarks -
 * never guessed or LLM-generated. [pageNumber] is 1-based (PDFBox's own
 * [org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination.retrievePageNumber]
 * is 0-based; [DocumentStructureExtractor] adds 1), null if the outline
 * entry's destination couldn't be resolved to a page at all - not expected
 * to be common, but PDF outlines can reference external files/URIs instead
 * of a page in this same document, which this app has no use for and
 * doesn't attempt to resolve.
 */
data class StructureNode(
    val title: String,
    val pageNumber: Int?,
    val children: List<StructureNode> = emptyList(),
)

/**
 * A document's full outline, top-level entries only (each with its own
 * nested [StructureNode.children]) - null/absent for a document whose PDF
 * has no embedded outline at all (see [DocumentStructureExtractor]), which
 * is common for documents that were never authored with bookmarks (e.g. a
 * plain scanned report vs. an exported course/slide deck). [ChatAgent.answer]
 * treats "no structure" and "structure exists but the question doesn't need
 * it" identically - both fall through to the existing vector-search path.
 */
data class DocumentStructure(val nodes: List<StructureNode>)

/**
 * Store for [DocumentStructure]s extracted at upload time
 * ([ch.arcticsoft.springchat3.web.DocumentController.upload] calls
 * [DocumentStructureExtractor] then [store] here), looked up by
 * [ChatAgent.answer] as the first stage of document-Q&A's two-stage search
 * (2026-08-22, see springchat3_document_qa.md in project memory - the
 * user's own proposal after noticing vector search alone answers
 * enumeration-style questions like "what modules does this have" poorly,
 * since such questions don't have strong semantic content of their own for
 * a chunk embedding to match against).
 *
 * Same per-document persistence pattern as [DocumentStore]/[DocumentIndex]:
 * `[dataDir]/<documentId>/structure.json`, write-through on [store], deleted
 * (with the directory cleaned up once all three files are gone - see
 * [DocumentStore.remove]'s doc comment for why the order across the three
 * `remove` calls doesn't matter) on [remove]. Unlike those two, this class
 * keeps an in-memory [cache] populated lazily on first [get] rather than
 * eagerly at construction - structure is looked up far less often (once per
 * chat turn with an attached document, at most) than document metadata
 * ([DocumentStore.list] backs a UI panel refreshed constantly) or vector
 * chunks (needed on every retrieval), so there's nothing to eagerly warm.
 * [EMPTY] is the cache's sentinel for "no structure" (a document with no
 * embedded outline, or a load failure) - [ConcurrentHashMap] can't hold a
 * real `null` value, and a *stored* [DocumentStructure] genuinely having
 * zero nodes is never produced by [DocumentStructureExtractor] (it returns
 * null instead in that case), so reusing that shape as the "absent" marker
 * is safe and needs no separate wrapper type.
 */
@Component
class DocumentStructureStore(
    @Value("\${springchat3.data-dir}") private val dataDir: String,
) {
    private val log = LoggerFactory.getLogger(DocumentStructureStore::class.java)
    private val objectMapper = jacksonObjectMapper()
    private val cache = ConcurrentHashMap<String, DocumentStructure>()

    companion object {
        private val EMPTY = DocumentStructure(emptyList())
    }

    private fun structureFile(documentId: String) = File(File(dataDir, documentId), "structure.json")

    /** Persists [structure] for [documentId], overwriting any previous structure for it. */
    fun store(documentId: String, structure: DocumentStructure) {
        try {
            val file = structureFile(documentId)
            file.parentFile.mkdirs()
            objectMapper.writeValue(file, structure)
            cache[documentId] = structure
        } catch (e: Exception) {
            log.warn("Could not persist structure for document {} to {}", documentId, structureFile(documentId), e)
        }
    }

    /**
     * Returns [documentId]'s structure, or null if it has none - either
     * because its PDF had no embedded outline at upload time, or because
     * loading its persisted `structure.json` failed (logged, not thrown;
     * treated the same as "no structure" rather than failing the chat turn
     * that triggered this lookup).
     */
    fun get(documentId: String): DocumentStructure? {
        val cached = cache.computeIfAbsent(documentId) { id ->
            val file = structureFile(id)
            if (!file.exists()) {
                EMPTY
            } else {
                try {
                    objectMapper.readValue<DocumentStructure>(file)
                } catch (e: Exception) {
                    log.warn("Could not load persisted structure from {} - treating as absent", file, e)
                    EMPTY
                }
            }
        }
        return cached.takeIf { it.nodes.isNotEmpty() }
    }

    /** Removes [documentId]'s structure, if any - a no-op if it never had one. */
    fun remove(documentId: String) {
        cache.remove(documentId)
        val file = structureFile(documentId)
        file.delete()
        file.parentFile.delete()
    }
}
