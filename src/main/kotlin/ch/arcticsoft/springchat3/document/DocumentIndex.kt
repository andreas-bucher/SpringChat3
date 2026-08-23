package ch.arcticsoft.springchat3.document

import org.springframework.ai.document.Document
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.transformer.splitter.TokenTextSplitter
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.SimpleVectorStore
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Document-Q&A "Phase 2" (2026-08-22, see springchat3_document_qa.md in
 * project memory): chunks an uploaded PDF's pages, embeds the chunks, and
 * answers per-question similarity searches against them - replacing Phase
 * 1's approach of folding a document's (size-capped, sometimes truncated)
 * full text directly into [ch.arcticsoft.springchat3.agent.ChatAgent.answer]'s
 * prompt.
 *
 * **Per-document vector stores since 2026-08-22** (see
 * springchat3_document_qa.md in project memory - this replaces this same
 * class's earlier, same-day design of one [SimpleVectorStore] shared by
 * every uploaded document, scoped per document via a `documentId` metadata
 * field and [SearchRequest.Builder.filterExpression]/`delete(String)`
 * filter expressions): each [documentId] now gets its own [SimpleVectorStore]
 * instance, built directly from the injected [EmbeddingModel] rather than
 * from a shared `@Bean` (see the now-removed `VectorStoreConfig`, moved to
 * `_to_delete/` - constructing [SimpleVectorStore] per document needs a
 * factory, not a singleton). This was requested explicitly, not just a
 * guess at what's "better" - the earlier shared-store design re-saved the
 * *entire* store (every document's chunks) to disk on every single
 * index/remove call, an O(all documents) cost paid per mutation that only
 * gets worse as documents accumulate, which is exactly what this feature is
 * for; per-document stores make each mutation O(one document)'s chunks, and
 * bound a corrupt-file load failure to that one document instead of losing
 * every document's chunks at once. A welcome side effect: the `documentId`
 * metadata tag and the filter-expression syntax it required are gone
 * entirely - a document's own store only ever holds that document's chunks,
 * so [search] no longer needs to filter at all. [storeFor] lazily
 * constructs/loads a document's store on first use ([index] or [search]) and
 * caches it in [stores] for the life of the process - there's no eager
 * startup scan of [dataDir], unlike [DocumentStore]'s document metadata,
 * since nothing here needs to enumerate documents up front.
 *
 * API surface confirmed against Spring AI's own reference/javadoc pages
 * before writing the original version of this class (given this project's
 * history of one Spring AI API guess turning out wrong - see
 * springchat3_native_tool_calling.md risk #6): [TokenTextSplitter]'s real
 * package is `org.springframework.ai.transformer.splitter` (an initial doc
 * fetch wrongly suggested `org.springframework.ai.document` - caught by
 * cross-checking against Maven Central's full-class search, a second
 * independent source). [index] originally used [Document.mutate] to add a
 * metadata key without hand-copying the rest, but that hit a real
 * null-metadata bug on the first real upload (see [index]'s own doc
 * comment) and was replaced with building a fresh, hand-cleaned [Document]
 * instead - that fix is unrelated to the per-document-store change above and
 * still applies, since [PagePdfDocumentReader]'s own metadata can still
 * contain null values regardless of how the store is scoped.
 *
 * **NOT verified, unlike the above:** whether [PagePdfDocumentReader]
 * actually populates a `page_number` metadata key on each page - the
 * reference docs mention that key only in the context of a *writer*
 * (`FileDocumentWriter`), not confirmed as a *reader* default. [search]'s
 * caller (`ChatAgent.answer`) treats it as optional/best-effort (falls back
 * to an unnumbered "Passage N" label) specifically because of this, so nothing
 * breaks if the key turns out to be named differently or absent - but page
 * citations in replies won't work until this is checked against a real
 * upload.
 *
 * **Persisted to disk** via [SimpleVectorStore]'s own confirmed
 * `.save(File)`/`.load(File)` methods, one file per document:
 * `vectorstore.json` inside [documentId]'s own directory (resolved via
 * [DocumentStore.documentDir] since 2026-08-23, so a project-scoped
 * document's vector store lands in the same place as its `document.json` -
 * see [vectorStoreFile]). Write-through, same simple approach as
 * [DocumentStore]'s own persistence: [index] and [remove] each re-save only
 * the *one* document's store they touched, not every document's - fine even
 * as this app's document collection grows, unlike the old shared-file
 * design. A load failure for one document's file logs a warning and starts
 * that document with an empty store rather than failing application startup
 * or affecting any other document. **[remove] must be called before
 * [DocumentStore.remove] for the same [documentId]** - see that method's own
 * doc comment.
 */
@Component
class DocumentIndex(
    private val embeddingModel: EmbeddingModel,
    @Value("\${springchat3.data-dir}") private val dataDir: String,
    private val documentStore: DocumentStore,
) {
    private val log = LoggerFactory.getLogger(DocumentIndex::class.java)
    private val stores = ConcurrentHashMap<String, SimpleVectorStore>()

    companion object {
        /**
         * How many chunks [search] returns per question - injected directly
         * into [ch.arcticsoft.springchat3.agent.ChatAgent.answer]'s prompt,
         * so this trades recall for a bounded, predictable prompt size. Pure
         * guess, not tuned against real documents yet; revisit if answers
         * seem to be missing relevant content that's genuinely in the
         * document (raise it) or the generation model seems to drown in
         * marginally-relevant passages (lower it).
         */
        private const val TOP_K = 6
    }

    // Spring AI's own defaults (chunkSize=800 tokens, minChunkSizeChars=350)
    // - not tuned for this app specifically. A local single-user app with
    // real PDFs to test against is exactly the situation to tune this in,
    // once Phase 2 is confirmed working at all.
    private val splitter = TokenTextSplitter.builder().build()

    // Delegates to DocumentStore.documentDir (2026-08-23, see that method's
    // own doc comment) so a project-scoped document's vector store lands
    // alongside its document.json instead of this class independently
    // guessing the same path - falls back to the old flat layout only for an
    // id DocumentStore doesn't recognize at all (defensive, same as before
    // this feature existed).
    private fun vectorStoreFile(documentId: String) = File(documentStore.documentDir(documentId) ?: File(dataDir, documentId), "vectorstore.json")

    /**
     * Returns [documentId]'s [SimpleVectorStore], constructing and - if a
     * persisted file already exists for it - loading it on first use, then
     * caching it in [stores] for subsequent calls. Called by both [index]
     * (first use, right after a fresh upload) and [search] (possibly before
     * [index] has ever run for this process, e.g. right after a restart).
     */
    private fun storeFor(documentId: String): SimpleVectorStore =
        stores.computeIfAbsent(documentId) { id ->
            val store = SimpleVectorStore.builder(embeddingModel).build()
            val file = vectorStoreFile(id)
            if (file.exists()) {
                try {
                    store.load(file)
                } catch (e: Exception) {
                    log.warn("Could not load persisted vector store from {} - starting empty", file, e)
                }
            }
            store
        }

    private fun persist(documentId: String, store: SimpleVectorStore) {
        try {
            val file = vectorStoreFile(documentId)
            file.parentFile.mkdirs()
            store.save(file)
        } catch (e: Exception) {
            log.warn("Could not persist vector store for document {} to {}", documentId, vectorStoreFile(documentId), e)
        }
    }

    /**
     * Splits [pages] into chunks and adds them to [documentId]'s own vector
     * store, embedding each chunk in the process (handled internally by
     * `VectorStore.add` - this class never calls an embedding model
     * directly). Called once per upload
     * ([ch.arcticsoft.springchat3.web.DocumentController.upload]).
     *
     * **Real bug found on first upload (2026-08-22, a 175-page PDF):**
     * `page.mutate().metadata(...).build()` threw
     * `IllegalArgumentException: metadata cannot have null values` -
     * [Document]'s constructor validates every metadata value is non-null,
     * and [PagePdfDocumentReader] apparently puts at least one null-valued
     * entry into each page's metadata (most likely something derived from
     * the [org.springframework.core.io.ByteArrayResource] this app reads
     * from - e.g. a "source"/filename field, since `ByteArrayResource` has
     * no real filename and returns null for it - but the exact key was never
     * pinned down, since the fix doesn't need to know it). This slipped past
     * unnoticed through both Phase 1 and this class's own initial version
     * because nothing had ever re-validated/rebuilt these `Document`s after
     * [PagePdfDocumentReader.read] first created them - `.mutate().build()`
     * is the first code path that does. Fixed by stripping null values from
     * each page's existing metadata before rebuilding.
     */
    fun index(documentId: String, pages: List<Document>) {
        val cleaned = pages.map { page ->
            val cleanMetadata = HashMap<String, Any>()
            page.metadata.forEach { (key, value) -> if (value != null) cleanMetadata[key] = value }
            // .orEmpty(): a real `./gradlew` compile error (2026-08-22) showed
            // this 3-arg Document(id, text, metadata) constructor actually
            // requires a non-null `text`, contradicting an earlier web-fetched
            // doc summary that described it as @Nullable (same as the 2-arg
            // constructor) - ground truth from the compiler wins over that
            // summary. Document.getText() is still nullable in general (see
            // this class's own doc comment / other .text.orEmpty() call sites
            // in this app), just not accepted as null by this specific
            // constructor overload.
            Document(page.id, page.text.orEmpty(), cleanMetadata)
        }
        val chunks = splitter.apply(cleaned)
        val store = storeFor(documentId)
        store.add(chunks)
        persist(documentId, store)
    }

    /**
     * Removes [documentId]'s vector store entirely - both from the
     * in-memory [stores] cache and its on-disk file - called from
     * [ch.arcticsoft.springchat3.web.DocumentController.delete] alongside
     * [DocumentStore.remove] so a deleted document stops being searchable
     * immediately rather than lingering on disk. Also removes [documentId]'s
     * whole storage directory if it's now empty - see [DocumentStore.remove]
     * for why the order relative to that call doesn't matter.
     */
    fun remove(documentId: String) {
        stores.remove(documentId)
        val file = vectorStoreFile(documentId)
        file.delete()
        file.parentFile.delete()
    }

    /**
     * Returns the [TOP_K] chunks of [documentId] most relevant to [question],
     * most-relevant first - empty if [documentId] has no indexed chunks (e.g.
     * embedding failed at upload time, or [documentId] doesn't correspond to
     * any real document) or nothing meets the similarity bar. Called once
     * per chat turn that has an attached document
     * ([ch.arcticsoft.springchat3.agent.ChatAgent.answer]), embedding
     * [question] itself internally via the same `VectorStore.similaritySearch`
     * call - no separate embedding step needed here either.
     */
    fun search(documentId: String, question: String): List<Document> =
        storeFor(documentId).similaritySearch(
            SearchRequest.builder()
                .query(question)
                .topK(TOP_K)
                .build(),
        )
}
