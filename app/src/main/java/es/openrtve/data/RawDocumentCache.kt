package es.openrtve.data

import androidx.core.util.AtomicFile
import java.io.File
import java.security.MessageDigest

data class CachedDocument(
    val raw: String,
    val savedAtMillis: Long,
    /** Validador del servidor para revalidar con `If-None-Match`. */
    val etag: String?,
)

/**
 * Copia local de los documentos del catálogo. Cada programa, temporada y página
 * visitados dejan su fichero: al pasar de [maxBytes] se borran los más antiguos
 * (por fecha de descarga o de revalidación) hasta quedar en tres cuartos del límite.
 */
class RawDocumentCache(
    private val directory: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    /** Solo se lista el directorio al iniciar la contabilidad o cuando toca podar. */
    private var totalBytes: Long? = null

    @Synchronized
    fun read(key: String): CachedDocument? = runCatching {
        val file = fileFor(key)
        if (!file.isFile) return null
        val raw = AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        val etag = etagFileFor(key).takeIf { it.isFile }?.readText(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
        CachedDocument(raw = raw, savedAtMillis = file.lastModified(), etag = etag)
    }.getOrNull()

    @Synchronized
    fun write(key: String, raw: String, savedAtMillis: Long, etag: String? = null) {
        directory.mkdirs()
        val before = totalBytes ?: sizeBytes()
        val previousBytes = fileFor(key).length() + etagFileFor(key).length()
        // Un fallo entre cuerpo y validador debe dejar un cuerpo sin ETag, nunca uno ajeno.
        etagFileFor(key).delete()
        totalBytes = null
        val atomicFile = AtomicFile(fileFor(key))
        val output = atomicFile.startWrite()
        try {
            output.write(raw.toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(output)
            atomicFile.baseFile.setLastModified(savedAtMillis)
        } catch (error: Throwable) {
            atomicFile.failWrite(output)
            totalBytes = null
            throw error
        }
        val etagFile = etagFileFor(key)
        try {
            if (etag != null) etagFile.writeText(etag, Charsets.UTF_8)
        } finally {
            totalBytes = null
        }
        totalBytes = before - previousBytes + atomicFile.baseFile.length() + etagFile.length()
        if (checkNotNull(totalBytes) > maxBytes) prune()
    }

    /** Borra los documentos más antiguos, con su ETag, hasta bajar de tres cuartos de [maxBytes]. */
    @Synchronized
    fun prune() {
        val documents = directory.listFiles { file -> file.extension == "json" }.orEmpty()
        var total = documents.sumOf { it.length() + etagFor(it).length() }
        totalBytes = total
        if (total <= maxBytes) return
        for (document in documents.sortedBy(File::lastModified)) {
            if (total <= maxBytes * 3 / 4) break
            val etag = etagFor(document)
            val documentSize = document.length()
            val etagSize = etag.length()
            if (document.delete()) total -= documentSize
            if (etag.delete()) total -= etagSize
        }
        totalBytes = total
    }

    private fun etagFor(document: File) = File(document.parentFile, "${document.nameWithoutExtension}.etag")

    /** Un 304 confirma que la copia sigue vigente: solo se renueva su fecha. */
    @Synchronized
    fun touch(key: String, savedAtMillis: Long) {
        fileFor(key).setLastModified(savedAtMillis)
    }

    @Synchronized
    fun sizeBytes(): Long = (directory.listFiles()?.sumOf { it.length() } ?: 0L).also { totalBytes = it }

    @Synchronized
    fun clear() {
        directory.listFiles()?.forEach { it.delete() }
        totalBytes = null
    }

    private fun fileFor(key: String): File = File(directory, "${digest(key)}.json")

    private fun etagFileFor(key: String): File = File(directory, "${digest(key)}.etag")

    private companion object {
        const val DEFAULT_MAX_BYTES = 32L * 1024 * 1024
    }

    private fun digest(key: String): String = MessageDigest.getInstance("SHA-256")
        .digest(key.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { "%02x".format(it) }
}
