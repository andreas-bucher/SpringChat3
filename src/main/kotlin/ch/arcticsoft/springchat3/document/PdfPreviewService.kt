package ch.arcticsoft.springchat3.document

import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** One document rendered as PDF, plus the name to hand the browser. */
data class PdfPreview(val bytes: ByteArray, val filename: String)

/**
 * The PDF a document is *viewed* as, whatever it is stored as (2026-08-23,
 * user's own request "Let's implement Option 3 using LibreOffice" - see
 * springchat3_pdf_preview.md in project memory).
 *
 * Deliberately uniform across every document kind, which is what lets the
 * frontend point one link at every card: a Word upload is converted and
 * cached, and anything already stored as PDF (a direct upload, a Drive file,
 * a Google Doc's export) is passed straight through untouched. A web page,
 * which has no raw file at all, has no preview - an ordinary 404, same as it
 * already gets from the "open the file" endpoint.
 *
 * **A cached preview is never trusted on its age, only on its hash.** The
 * user asked for the PDF to be built "after word document is imported and
 * after app has edited it", and [warm] does exactly that - but as an
 * optimization, not as the guarantee. The guarantee is the hash comparison in
 * [preview]: a write path that forgets to warm (or fails to) costs one
 * conversion on the next view, where it would otherwise silently show
 * somebody the previous version of their document. That failure mode is the
 * whole reason this is not simply "regenerate on write" - [WordDocumentWorkspace.undo]
 * is precisely the kind of second write path that gets missed.
 */
@Component
class PdfPreviewService(
    private val documentStore: DocumentStore,
    private val pdfConverter: PdfConverter,
) {
    private val log = LoggerFactory.getLogger(PdfPreviewService::class.java)

    /** Per-document, so a warm-up and a click on the same document convert once, not twice. */
    private val locks = ConcurrentHashMap<String, Any>()

    /**
     * Single-threaded on purpose: warming is background work nobody is
     * waiting for, and one thread keeps it from competing with a view someone
     * *is* waiting for. Daemon so it can never hold up a shutdown.
     */
    private val warmExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pdf-preview-warm").apply { isDaemon = true }
    }

    val available: Boolean get() = pdfConverter.available

    /**
     * Returns [documentId] as PDF - cached, converted, or passed through -
     * or null if there is no such document or it has no stored file.
     * Throws [PdfConversionException] if LibreOffice is missing or the
     * conversion fails.
     */
    fun preview(documentId: String): PdfPreview? {
        val document = documentStore.get(documentId) ?: return null
        val source = documentStore.getBytes(documentId) ?: return null
        if (!document.rawFilename.endsWith(".docx", ignoreCase = true)) {
            return PdfPreview(source, document.filename)
        }
        val name = document.filename.substringBeforeLast('.', document.filename) + ".pdf"
        return synchronized(locks.computeIfAbsent(documentId) { Any() }) {
            val hash = sha256(source)
            val cached = documentStore.getPreviewBytes(documentId)
            if (cached != null && documentStore.previewHash(documentId) == hash) {
                PdfPreview(cached, name)
            } else {
                val started = System.currentTimeMillis()
                val pdf = pdfConverter.convertToPdf(source)
                documentStore.storePreview(documentId, pdf, hash)
                log.info(
                    "Converted '{}' ({}) to PDF - {} bytes in {} ms",
                    document.filename, documentId, pdf.size, System.currentTimeMillis() - started,
                )
                PdfPreview(pdf, name)
            }
        }
    }

    /**
     * Builds [documentId]'s preview in the background if it needs building.
     * Never throws and never blocks the caller - an upload or an edit must
     * not fail, or even slow down, because a preview couldn't be made; the
     * next view retries.
     */
    fun warm(documentId: String) {
        if (!pdfConverter.available) return
        try {
            // execute, not submit: submit is overloaded on Runnable and
            // Callable, which a Kotlin lambda can resolve either way, and the
            // Future it would return is never looked at anyway.
            warmExecutor.execute {
                try {
                    preview(documentId)
                } catch (e: Exception) {
                    log.warn("Could not pre-build the PDF preview for document {} - it will be retried on first view", documentId, e)
                }
            }
        } catch (e: Exception) {
            log.warn("Could not schedule a PDF preview for document {}", documentId, e)
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @PreDestroy
    fun shutdown() {
        warmExecutor.shutdownNow()
    }
}
