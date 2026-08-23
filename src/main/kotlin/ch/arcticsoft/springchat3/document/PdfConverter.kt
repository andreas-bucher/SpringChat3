package ch.arcticsoft.springchat3.document

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** Thrown for every conversion failure; [unavailable] separates "no LibreOffice here" from "this document didn't convert". */
class PdfConversionException(message: String, val unavailable: Boolean = false, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * Converts a Word document to PDF by shelling out to LibreOffice
 * (2026-08-23, user's own request "Let's implement Option 3 using
 * LibreOffice" - the in-app PDF preview, see springchat3_pdf_preview.md in
 * project memory).
 *
 * Bytes in, bytes out, no app knowledge at all - the same layering
 * [WordDocumentService] has against docx4j, and for the same reason: the
 * decision of *which* document to convert, *when*, and where to cache the
 * result belongs to [PdfPreviewService], not here.
 *
 * **Every conversion gets its own throwaway LibreOffice user profile**
 * (`-env:UserInstallation=`), which is not a detail. Measured on the user's
 * own documents before this was written: two conversions sharing one profile
 * directory produced one correct PDF and one *no output at all*
 * (`javasettings_<platform>.xml: Document is empty`). The same collision is
 * what makes a plain `soffice --convert-to` silently do nothing on a desktop
 * machine where LibreOffice is already open - the new invocation hands off to
 * the running instance and returns immediately. A per-conversion profile
 * removes the shared state entirely, and costs about 0.1s: the user's largest
 * real document (4.5 MB, 38 pages, 18 tables) converted in 0.83s with a fresh
 * profile against 0.73s with a warm one. Three of those in parallel, each with
 * its own profile, all succeeded in 0.9s wall clock.
 *
 * [permits] therefore exists to bound memory, not to protect correctness -
 * without it a burst of warm-ups could fork one soffice process per document
 * at once.
 */
@Component
class PdfConverter(
    @Value("\${springchat3.libreoffice.path:}") private val configuredPath: String,
    @Value("\${springchat3.libreoffice.timeout-seconds:120}") private val timeoutSeconds: Long,
    @Value("\${springchat3.libreoffice.max-concurrent:2}") maxConcurrent: Int,
) {
    private val log = LoggerFactory.getLogger(PdfConverter::class.java)
    private val permits = Semaphore(maxConcurrent.coerceAtLeast(1))

    /**
     * Probed once, on first use rather than at startup - this app starts
     * against Ollama and Google OAuth already, and a preview feature nobody
     * has touched yet has no business adding a subprocess to boot time.
     */
    private val binary: String? by lazy { probeBinary() }

    val available: Boolean get() = binary != null

    /**
     * Candidates cover the two machines this app actually runs on - the
     * user's Mac (LibreOffice.app, never on PATH) and the Linux server
     * (`/usr/bin/soffice`) - so neither needs configuring. A bare `soffice`
     * is the last entry so a PATH installation somewhere else still works;
     * `springchat3.libreoffice.path` overrides the lot.
     */
    private fun probeBinary(): String? {
        val candidates = if (configuredPath.isNotBlank()) {
            listOf(configuredPath)
        } else {
            listOf(
                "/Applications/LibreOffice.app/Contents/MacOS/soffice",
                "/usr/bin/soffice",
                "/usr/local/bin/soffice",
                "/opt/homebrew/bin/soffice",
                "/usr/lib/libreoffice/program/soffice",
                "soffice",
            )
        }
        for (candidate in candidates) {
            if (!candidate.contains('/') || File(candidate).canExecute()) {
                versionOf(candidate)?.let {
                    log.info("PDF preview will use {} ({})", candidate, it)
                    return candidate
                }
            }
        }
        log.warn(
            "No working LibreOffice found (tried {}) - PDF previews are disabled. Set springchat3.libreoffice.path " +
                "or LIBREOFFICE_PATH to enable them.",
            candidates.joinToString(", "),
        )
        return null
    }

    private fun versionOf(candidate: String): String? = try {
        val process = ProcessBuilder(candidate, "--version").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        if (process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) output.lineSequence().first() else null
    } catch (e: Exception) {
        null
    }

    fun convertToPdf(bytes: ByteArray): ByteArray {
        val soffice = binary ?: throw PdfConversionException(
            "LibreOffice is not installed on this server, so Word documents cannot be previewed as PDF.",
            unavailable = true,
        )
        permits.acquire()
        // The permit is released in the outer finally, not alongside the temp
        // directory: acquiring it and then failing to create that directory
        // would otherwise leak a permit, and enough of those deadlock every
        // future conversion.
        try {
            val work = Files.createTempDirectory("springchat3-pdf-").toFile()
            try {
                val input = File(work, "input.docx").apply { writeBytes(bytes) }
                val outDir = File(work, "out").apply { mkdirs() }
                val profile = File(work, "profile")
                val logFile = File(work, "soffice.log")
                val process = ProcessBuilder(
                    soffice,
                    "--headless", "--invisible", "--nodefault", "--nolockcheck", "--nologo", "--norestore",
                    "-env:UserInstallation=${profile.toPath().toUri()}",
                    "--convert-to", "pdf:writer_pdf_Export",
                    "--outdir", outDir.absolutePath,
                    input.absolutePath,
                )
                    // To a file, not a pipe: a pipe whose buffer fills while
                    // nothing drains it deadlocks the child, and draining it from
                    // this thread would mean blocking on read() with no way to
                    // honour the timeout below.
                    .redirectErrorStream(true)
                    .redirectOutput(logFile)
                    .start()

                if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    throw PdfConversionException("LibreOffice did not finish converting within ${timeoutSeconds}s.")
                }
                // Both checks, in this order: a corrupt file exits non-zero with a
                // useful message, but soffice also has failure modes where it
                // reports success having written nothing at all.
                if (process.exitValue() != 0) {
                    throw PdfConversionException("LibreOffice failed (exit ${process.exitValue()}): ${tail(logFile)}")
                }
                val pdf = File(outDir, "input.pdf").takeIf { it.exists() }
                    ?: outDir.listFiles()?.firstOrNull { it.extension.equals("pdf", ignoreCase = true) }
                    ?: throw PdfConversionException("LibreOffice produced no PDF: ${tail(logFile)}")
                val converted = pdf.readBytes()
                if (converted.isEmpty()) throw PdfConversionException("LibreOffice produced an empty PDF: ${tail(logFile)}")
                return converted
            } finally {
                work.deleteRecursively()
            }
        } catch (e: PdfConversionException) {
            throw e
        } catch (e: Exception) {
            throw PdfConversionException("Could not run LibreOffice: ${e.message}", cause = e)
        } finally {
            permits.release()
        }
    }

    private fun tail(logFile: File): String = try {
        logFile.readText().trim().takeLast(500).ifBlank { "no output" }
    } catch (e: Exception) {
        "no output"
    }
}
