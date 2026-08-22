package ch.arcticsoft.springchat3.document

import org.springframework.ai.document.Document
import org.springframework.ai.reader.pdf.PagePdfDocumentReader
import org.springframework.core.io.ByteArrayResource
import org.springframework.stereotype.Component

/**
 * Extracts page-level [Document]s from an uploaded PDF's raw bytes, via
 * Spring AI's own `spring-ai-pdf-document-reader` module (PDFBox under the
 * hood) rather than a hand-rolled PDFBox integration.
 *
 * [PagePdfDocumentReader]'s constructor/config API was confirmed against
 * Spring AI's own reference docs and API docs before writing this (not
 * assumed) - `PagePdfDocumentReader(Resource pdfResource)` is a real
 * documented constructor that uses PDFBox's own default page-per-document
 * splitting, and `.read(): List<Document>` returns one [Document] per page by
 * default.
 *
 * Was `extractText(pdfBytes): String` (joined plain text only) through
 * document-Q&A "Phase 1" - see springchat3_document_qa.md in project memory.
 * Changed 2026-08-22 ("Phase 2": chunking + embeddings + vector-store
 * retrieval, replacing full-text-stuffing) to return the page-level
 * [Document]s themselves rather than a pre-joined string, since
 * [DocumentIndex] needs per-page structure (to tag each page with a
 * `documentId` before splitting, and because [PagePdfDocumentReader]
 * plausibly - not confirmed, see [DocumentIndex]'s doc comment - attaches a
 * `page_number` to each page's metadata, useful for citations). Callers that
 * only want the joined text for display (e.g.
 * [ch.arcticsoft.springchat3.web.DocumentController.upload]'s character-count
 * metadata) now do that one-line join themselves, so a PDF is only parsed
 * once per upload rather than twice.
 */
@Component
class PdfTextExtractor {

    fun extractPages(pdfBytes: ByteArray): List<Document> =
        PagePdfDocumentReader(ByteArrayResource(pdfBytes)).read()
}
