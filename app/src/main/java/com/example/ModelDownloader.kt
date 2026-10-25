package com.example

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.OpenableColumns
import com.example.domain.LocalDiscoveredFile
import com.example.domain.StorageLocationOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class DownloadProgressSnapshot(
    val downloadId: Long,
    val status: Int,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val progressPercent: Int,
    val speedMbps: Float = 0f,
    val errorMessage: String? = null,
    val savedFilePath: String? = null,
    val reason: Int = 0
) {
    val isCompleted: Boolean
        get() = status == DownloadManager.STATUS_SUCCESSFUL

    val isFailed: Boolean
        get() = status == DownloadManager.STATUS_FAILED

    val isRunning: Boolean
        get() = status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING
}

class ModelDownloader(private val context: Context) {

    companion object {
        // Tamaño mínimo de un modelo LLM real en disco (5 MB) para descartar archivos vacíos o sintéticos antiguos
        private const val MIN_VALID_MODEL_BYTES = 5L * 1024L * 1024L
    }

    private val downloaderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val idGenerator = AtomicLong(1000L)
    private val activeJobs = ConcurrentHashMap<Long, Job>()
    private val progressSnapshots = ConcurrentHashMap<Long, DownloadProgressSnapshot>()

    @Volatile
    var hfAuthToken: String = ""

    @Volatile
    var preferredStorage: StorageLocationOption = StorageLocationOption.INTERNAL_FILES

    // Cliente OkHttp configurado con interceptor que limpia cabeceras conflictivas al redirigir al CDN (CloudFront/LFS)
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            val host = request.url.host.lowercase()
            // Si fuimos redirigidos fuera de huggingface.co (ej. cdn-lfs o s3), quitar Authorization para evitar error 400/403 de AWS S3
            val cleanReq = if (!host.endsWith("huggingface.co") && request.header("Authorization") != null) {
                request.newBuilder().removeHeader("Authorization").build()
            } else {
                request
            }
            chain.proceed(cleanReq)
        }
        .build()

    /**
     * Limpia cualquier archivo .gguf sintético antiguo (< 5 MB) que haya quedado de pruebas previas.
     */
    fun cleanupSyntheticFiles() {
        val dirs = listOfNotNull(
            File(context.filesDir, "models"),
            context.filesDir,
            context.getExternalFilesDir(null)?.let { File(it, "models") }
        )
        for (dir in dirs) {
            try {
                if (dir.exists() && dir.isDirectory) {
                    dir.listFiles()?.forEach { f ->
                        if (f.isFile && f.name.endsWith(".gguf", ignoreCase = true) && f.length() < MIN_VALID_MODEL_BYTES) {
                            f.delete()
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Resuelve y garantiza un directorio de almacenamiento viable y con permisos de escritura.
     * Si la ruta elegida falla, hace fallback automático a Memoria Interna (context.filesDir/models).
     */
    fun resolveViableStorageDir(option: StorageLocationOption = preferredStorage): File {
        val candidate: File? = when (option) {
            StorageLocationOption.INTERNAL_FILES -> File(context.filesDir, "models")
            StorageLocationOption.EXTERNAL_APP_FILES -> {
                val ext = context.getExternalFilesDir(null)
                if (ext != null) File(ext, "models") else null
            }
            StorageLocationOption.PUBLIC_DOWNLOADS -> {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            }
        }

        if (candidate != null) {
            try {
                if (!candidate.exists()) {
                    candidate.mkdirs()
                }
                if (candidate.exists() && candidate.canWrite()) {
                    return candidate
                }
            } catch (_: Exception) {
            }
        }

        // Fallback infalible en Memoria Interna de la App
        val internalFallback = File(context.filesDir, "models")
        if (!internalFallback.exists()) {
            internalFallback.mkdirs()
        }
        return internalFallback
    }

    /**
     * Busca un modelo real (> 5 MB) por su nombre o ruta personalizada en todas las ubicaciones posibles
     * (Memoria Interna, Memoria Externa de la App y Carpeta Descargas del teléfono).
     */
    fun getModelFile(fileName: String, customLocalPath: String? = null): File {
        if (!customLocalPath.isNullOrBlank()) {
            val customFile = File(customLocalPath)
            if (customFile.exists() && customFile.length() >= MIN_VALID_MODEL_BYTES) {
                return customFile
            }
        }

        val searchDirs = listOfNotNull(
            resolveViableStorageDir(preferredStorage),
            File(context.filesDir, "models"),
            context.filesDir,
            context.getExternalFilesDir(null)?.let { File(it, "models") },
            context.getExternalFilesDir(null),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            File("/storage/emulated/0/Download"),
            File("/sdcard/Download")
        )

        for (dir in searchDirs) {
            try {
                val candidate = File(dir, fileName)
                if (candidate.exists() && candidate.length() >= MIN_VALID_MODEL_BYTES) {
                    return candidate
                }
            } catch (_: Exception) {
            }
        }

        return File(resolveViableStorageDir(preferredStorage), fileName)
    }

    fun isModelDownloaded(fileName: String, customLocalPath: String? = null): Boolean {
        val file = getModelFile(fileName, customLocalPath)
        return file.exists() && file.length() >= MIN_VALID_MODEL_BYTES
    }

    fun getModelSizeOnDisk(fileName: String, customLocalPath: String? = null): Long {
        val file = getModelFile(fileName, customLocalPath)
        return if (file.exists() && file.length() >= MIN_VALID_MODEL_BYTES) file.length() else 0L
    }

    fun hasEnoughDiskSpace(requiredMb: Int): Boolean {
        return try {
            val dir = resolveViableStorageDir(preferredStorage)
            val stat = StatFs(dir.absolutePath)
            val requiredBytes = requiredMb.toLong() * 1024L * 1024L
            stat.availableBytes > (requiredBytes + 32L * 1024L * 1024L)
        } catch (_: Exception) {
            true
        }
    }

    /**
     * Escanea la carpeta de Descargas del teléfono y las carpetas internas en busca de archivos .gguf, .bin, .task o .tflite reales (> 5 MB).
     */
    fun scanDeviceForModelFiles(): List<LocalDiscoveredFile> {
        val discovered = mutableListOf<LocalDiscoveredFile>()
        val seenPaths = mutableSetOf<String>()
        val validExtensions = listOf(".gguf", ".bin", ".task", ".tflite")

        val foldersToScan = listOfNotNull(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) to "Carpeta Descargas (Download)",
            File("/storage/emulated/0/Download") to "Descargas (/storage/emulated/0/Download)",
            File(context.filesDir, "models") to "Memoria Interna (files/models)",
            context.getExternalFilesDir(null)?.let { File(it, "models") to "Memoria Externa App" },
            context.getExternalFilesDir(null)?.let { it to "Raíz Externa App" }
        )

        for ((folder, label) in foldersToScan) {
            try {
                if (folder.exists() && folder.isDirectory && folder.canRead()) {
                    val files = folder.listFiles().orEmpty()
                    for (f in files) {
                        if (!f.isFile || f.length() < MIN_VALID_MODEL_BYTES) continue
                        val matchesExt = validExtensions.any { ext -> f.name.endsWith(ext, ignoreCase = true) }
                        val canonical = f.canonicalPath
                        if (matchesExt && seenPaths.add(canonical)) {
                            discovered.add(
                                LocalDiscoveredFile(
                                    name = f.name,
                                    absolutePath = f.absolutePath,
                                    sizeMb = (f.length() / (1024L * 1024L)).coerceAtLeast(1L),
                                    sourceFolder = label
                                )
                            )
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        return discovered
    }

    /**
     * Importa y guarda un modelo seleccionado mediante el Selector de Archivos del sistema (Uri de Descargas o SD)
     * hacia la Memoria Interna viable de la aplicación.
     */
    suspend fun importModelFromUri(uri: Uri): Result<File> = withContext(Dispatchers.IO) {
        try {
            var resolvedName = "modelo_importado_${System.currentTimeMillis()}.gguf"
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    val displayName = cursor.getString(nameIndex)
                    if (!displayName.isNullOrBlank()) {
                        resolvedName = displayName
                    }
                }
            }

            if (!resolvedName.endsWith(".gguf", true) &&
                !resolvedName.endsWith(".bin", true) &&
                !resolvedName.endsWith(".task", true) &&
                !resolvedName.endsWith(".tflite", true)
            ) {
                resolvedName = "$resolvedName.gguf"
            }

            val destDir = resolveViableStorageDir(preferredStorage)
            val destFile = File(destDir, resolvedName)

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            } ?: return@withContext Result.failure(
                IllegalStateException("No se pudo abrir el flujo de lectura para el archivo seleccionado.")
            )

            if (!destFile.exists() || destFile.length() == 0L) {
                return@withContext Result.failure(
                    IllegalStateException("El archivo importado está vacío (0 bytes).")
                )
            }

            Result.success(destFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Carga o copia un modelo desde una ruta escrita manualmente (ej. /storage/emulated/0/Download/mi_modelo.gguf).
     */
    suspend fun resolveOrImportLocalPath(rawPath: String): Result<File> = withContext(Dispatchers.IO) {
        try {
            val cleanPath = rawPath.trim()
            if (cleanPath.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("Escribe una ruta de archivo válida."))
            }

            val candidates = listOf(
                File(cleanPath),
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), cleanPath),
                File("/storage/emulated/0/Download", cleanPath),
                File(resolveViableStorageDir(StorageLocationOption.INTERNAL_FILES), cleanPath),
                File(resolveViableStorageDir(StorageLocationOption.EXTERNAL_APP_FILES), cleanPath)
            )

            val found = candidates.firstOrNull { it.exists() && it.isFile && it.length() > 0L }
                ?: return@withContext Result.failure(
                    IllegalArgumentException(
                        "No se encontró el archivo en '$cleanPath'. Verifica el nombre o usa el botón 'Explorar Teléfono / Descargas' para seleccionarlo directamente."
                    )
                )

            if (found.canRead()) {
                return@withContext Result.success(found)
            }

            val destFile = File(resolveViableStorageDir(StorageLocationOption.INTERNAL_FILES), found.name)
            found.copyTo(destFile, overwrite = true)
            Result.success(destFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Descarga y guarda el modelo real (.gguf / .bin) en la ruta viable (Memoria Interna o Externa).
     */
    fun downloadModel(
        url: String,
        fileName: String,
        onComplete: (File) -> Unit = {}
    ): Long {
        val targetDir = resolveViableStorageDir(preferredStorage)
        val destinationFile = File(targetDir, fileName)

        if (destinationFile.exists() && destinationFile.length() >= MIN_VALID_MODEL_BYTES) {
            onComplete(destinationFile)
            return -1L
        }

        val downloadId = idGenerator.incrementAndGet()
        val tempFile = File(targetDir, "$fileName.part")

        progressSnapshots[downloadId] = DownloadProgressSnapshot(
            downloadId = downloadId,
            status = DownloadManager.STATUS_RUNNING,
            bytesDownloaded = 0L,
            totalBytes = -1L,
            progressPercent = 1,
            speedMbps = 0f,
            savedFilePath = destinationFile.absolutePath
        )

        val job = downloaderScope.launch {
            try {
                if (tempFile.exists()) {
                    tempFile.delete()
                }

                val requestBuilder = Request.Builder()
                    .url(url.trim())
                    .header("User-Agent", "curl/8.5.0")
                    .header("Accept", "*/*")

                if (hfAuthToken.isNotBlank() && url.contains("huggingface.co")) {
                    requestBuilder.header("Authorization", "Bearer ${hfAuthToken.trim()}")
                }

                okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
                    val code = response.code
                    if (!response.isSuccessful) {
                        val reasonMsg = when (code) {
                            401, 403 -> "HTTP $code: Este repositorio requiere un Token de Hugging Face o acceso autorizado."
                            404 -> "HTTP 404: El archivo '$fileName' no se encontró en el repositorio."
                            else -> "Error HTTP $code al descargar '$fileName'."
                        }
                        progressSnapshots[downloadId] = DownloadProgressSnapshot(
                            downloadId = downloadId,
                            status = DownloadManager.STATUS_FAILED,
                            bytesDownloaded = 0L,
                            totalBytes = 0L,
                            progressPercent = 0,
                            errorMessage = reasonMsg,
                            reason = code
                        )
                        return@use
                    }

                    val body = response.body ?: throw IllegalStateException("Cuerpo de respuesta vacío")
                    val totalBytes = body.contentLength()
                    var downloadedBytes = 0L
                    var lastTimeMs = System.currentTimeMillis()
                    var bytesSinceLastTick = 0L
                    var currentSpeedMbps = 0f

                    body.byteStream().use { input ->
                        FileOutputStream(tempFile, false).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                if (!isActive) return@use
                                output.write(buffer, 0, read)
                                downloadedBytes += read
                                bytesSinceLastTick += read

                                val now = System.currentTimeMillis()
                                val elapsedMs = now - lastTimeMs
                                if (elapsedMs >= 250L) {
                                    currentSpeedMbps = (bytesSinceLastTick.toFloat() / (1024f * 1024f)) / (elapsedMs / 1000f)
                                    val pct = if (totalBytes > 0L) {
                                        ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(1, 99)
                                    } else {
                                        50
                                    }

                                    progressSnapshots[downloadId] = DownloadProgressSnapshot(
                                        downloadId = downloadId,
                                        status = DownloadManager.STATUS_RUNNING,
                                        bytesDownloaded = downloadedBytes,
                                        totalBytes = totalBytes,
                                        progressPercent = pct,
                                        speedMbps = currentSpeedMbps,
                                        savedFilePath = destinationFile.absolutePath
                                    )
                                    lastTimeMs = now
                                    bytesSinceLastTick = 0L
                                }
                            }
                            output.flush()
                        }
                    }

                    if (!isActive) return@use

                    if (destinationFile.exists()) {
                        destinationFile.delete()
                    }
                    val renamed = tempFile.renameTo(destinationFile)
                    if (!renamed) {
                        tempFile.copyTo(destinationFile, overwrite = true)
                        tempFile.delete()
                    }

                    progressSnapshots[downloadId] = DownloadProgressSnapshot(
                        downloadId = downloadId,
                        status = DownloadManager.STATUS_SUCCESSFUL,
                        bytesDownloaded = destinationFile.length(),
                        totalBytes = destinationFile.length(),
                        progressPercent = 100,
                        speedMbps = currentSpeedMbps,
                        savedFilePath = destinationFile.absolutePath
                    )
                    onComplete(destinationFile)
                }
            } catch (e: Exception) {
                if (isActive) {
                    progressSnapshots[downloadId] = DownloadProgressSnapshot(
                        downloadId = downloadId,
                        status = DownloadManager.STATUS_FAILED,
                        bytesDownloaded = 0L,
                        totalBytes = 0L,
                        progressPercent = 0,
                        errorMessage = "Error de red al descargar '$fileName': ${e.localizedMessage ?: "Verifica tu conexión"}",
                        reason = -1
                    )
                }
            } finally {
                activeJobs.remove(downloadId)
            }
        }

        activeJobs[downloadId] = job
        return downloadId
    }

    fun getProgress(downloadId: Long): Int {
        if (downloadId == -1L) return 100
        return progressSnapshots[downloadId]?.progressPercent ?: 0
    }

    fun getDownloadSnapshot(downloadId: Long): DownloadProgressSnapshot? {
        if (downloadId == -1L) return null
        return progressSnapshots[downloadId]
    }

    fun cancelDownload(downloadId: Long, fileName: String? = null) {
        activeJobs.remove(downloadId)?.cancel()
        progressSnapshots.remove(downloadId)
        if (fileName != null) {
            val dir = resolveViableStorageDir(preferredStorage)
            File(dir, "$fileName.part").delete()
        }
    }

    fun deleteModelFile(fileName: String, customLocalPath: String? = null): Boolean {
        val file = getModelFile(fileName, customLocalPath)
        File(file.parentFile, "$fileName.part").delete()
        return if (file.exists()) file.delete() else false
    }
}
