package ch.arcticsoft.springchat3.document

import org.springframework.ai.document.Document
import org.springframework.ai.reader.tika.TikaDocumentReader
import org.springframework.core.io.ByteArrayResource
import org.springframework.stereotype.Component

/**
 * Extracts plain text from an uploaded Word document's raw bytes (2026-08-23,
 * "upload MS Word document" - see [WordDocumentStore]), via Spring AI's own
 * `spring-ai-tika-document-reader` module (Apache Tika under the hood),
 * mirroring how [PdfTextExtractor] leans on `spring-ai-pdf-document-reader`
 * rather than integrating the underlying library by hand.
 *
 * [TikaDocumentReader]'s API was confirmed against Spring AI's own API docs
 * AND its source for this exact version tag before writing this, per this
 * project's standard for external API shapes (see
 * springchat3_native_tool_calling.md risk #6 in project memory):
 * `TikaDocumentReader(Resource)` is a real documented constructor, and
 * `.get()` returns `List.of(...)` - **exactly one** [Document] holding the
 * whole file's text, not one per page/paragraph. That single-element list is
 * returned as-is (rather than unwrapped to a String) so
 * [DocumentIndex.index]'s existing `List<Document>` signature takes it
 * unchanged; chunking happens there, same as for a linked web page's single
 * markdown [Document].
 *
 * The anonymous [ByteArrayResource] subclass overriding `getFilename()` is
 * deliberate: [TikaDocumentReader] tags each [Document]'s `source` metadata
 * with the resource's filename, falling back to its URI - and a plain
 * [ByteArrayResource] has neither, which lands it in Tika's own
 * "Invalid source URI: ..." fallback string. Harmless either way (it's
 * metadata, not content), but the real filename is more useful in a
 * retrieved chunk than an error string.
 */
@Component
class WordTextExtractor {

    fun extract(bytes: ByteArray, filename: String): List<Document> {
        val resource = object : ByteArrayResource(bytes) {
            override fun getFilename(): String = filename
        }
        return TikaDocumentReader(resource).get()
    }
}
