package es.openrtve.ui.tv

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import es.openrtve.AppContainer
import es.openrtve.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Los dos botones de la copia de seguridad: guardar y restaurar. */
class BackupActions(val save: () -> Unit, val restore: () -> Unit)

private const val BACKUP_NAME = "clan-peques-copia.json"

/**
 * Elige el fichero con el selector del sistema. Si la tele no trae selector (es habitual en
 * Android TV), la copia se guarda y se busca en la carpeta Descargas.
 */
@Composable
fun rememberBackupActions(container: AppContainer, onMessage: (String) -> Unit, onRestored: () -> Unit): BackupActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved = stringResource(R.string.settings_backup_saved)
    val savedDownloads = stringResource(R.string.settings_backup_saved_downloads)
    val restored = stringResource(R.string.settings_backup_restored)
    val invalid = stringResource(R.string.settings_backup_invalid)
    val unavailable = stringResource(R.string.settings_backup_unavailable)

    fun restoreFrom(raw: String?) {
        scope.launch {
            if (raw != null && container.backup.import(raw)) {
                container.watchHistory.reload()
                onRestored()
                onMessage(restored)
            } else {
                onMessage(invalid)
            }
        }
    }

    val createLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(container.backup.export().toByteArray()) } }.isSuccess
                }
                onMessage(if (ok) saved else unavailable)
            }
        }
    }
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { restoreFrom(readText(context, uri)) }
    }

    return remember(container) {
        BackupActions(
            save = {
                try {
                    createLauncher.launch(BACKUP_NAME)
                } catch (_: ActivityNotFoundException) {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { saveToDownloads(context, container.backup.export()) }
                        onMessage(if (ok) savedDownloads else unavailable)
                    }
                }
            },
            restore = {
                try {
                    openLauncher.launch(arrayOf("*/*"))
                } catch (_: ActivityNotFoundException) {
                    scope.launch { restoreFrom(withContext(Dispatchers.IO) { findInDownloads(context)?.let { readText(context, it) } }) }
                }
            },
        )
    }
}

private fun readText(context: Context, uri: Uri): String? =
    runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()

private fun saveToDownloads(context: Context, content: String): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
    return runCatching {
        val resolver = context.contentResolver
        findInDownloads(context)?.let { resolver.delete(it, null, null) }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, BACKUP_NAME)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        resolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) } != null
    }.getOrDefault(false)
}

private fun findInDownloads(context: Context): Uri? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    return runCatching {
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME} = ?",
            arrayOf(BACKUP_NAME),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0)) else null
        }
    }.getOrNull()
}
