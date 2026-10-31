package es.openrtve.data

import androidx.core.util.AtomicFile
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
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
    /** Se poda una vez por proceso, con la primera escritura: basta para que no crezca sin fin. */
    private val pruned = AtomicBoolean(false)

    fun read(key: String): CachedDocument? = runCatching {
        val file = fileFor(key)
        if (!file.isFile) return null
        val raw = AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        val etag = etagFileFor(key).takeIf { it.isFile }?.readText(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
        CachedDocument(raw = raw, savedAtMillis = file.lastModified(), etag = etag)
    }.getOrNull()

    fun write(key: String, raw: String, savedAtMillis: Long, etag: String? = null) {
        directory.mkdirs()
        val atomicFile = AtomicFile(fileFor(key))
        val output = atomicFile.startWrite()
        try {
            output.write(raw.toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(output)
            atomicFile.baseFile.setLastModified(savedAtMillis)
        } catch (error: Throwable) {
            atomicFile.failWrite(output)
            throw error
        }
        val etagFile = etagFileFor(key)
        if (etag != null) etagFile.writeText(etag, Charsets.UTF_8) else etagFile.delete()
        if (pruned.compareAndSet(false, true)) prune()
    }

    /** Borra los documentos más antiguos, con su ETag, hasta bajar de tres cuartos de [maxBytes]. */
    fun prune() {
        val documents = directory.listFiles { file -> file.extension == "json" }.orEmpty()
        var total = documents.sumOf { it.length() + etagFor(it).length() }
        if (total <= maxBytes) return
        for (document in documents.sortedBy(File::lastModified)) {
            if (total <= maxBytes * 3 / 4) break
            val etag = etagFor(document)
            total -= document.length() + etag.length()
            document.delete()
            etag.delete()
        }
    }

    private fun etagFor(document: File) = File(document.parentFile, "${document.nameWithoutExtension}.etag")

    /** Un 304 confirma que la copia sigue vigente: solo se renueva su fecha. */
    fun touch(key: String, savedAtMillis: Long) {
        fileFor(key).setLastModified(savedAtMillis)
    }

    fun sizeBytes(): Long = directory.listFiles()?.sumOf { it.length() } ?: 0L

    fun clear() {
        directory.listFiles()?.forEach { it.delete() }
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
