package com.example.ui

import android.app.Application
import android.content.ComponentCallbacks2
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.AIEngine
import com.example.ModelDownloader
import com.example.data.local.AppDatabase
import com.example.data.local.ChatMessageEntity
import com.example.data.local.ChatSessionEntity
import com.example.data.remote.HfGgufFileVariant
import com.example.data.remote.HfModelSearchService
import com.example.data.remote.HfRepoSearchResult
import com.example.data.repository.ChatRepository
import com.example.domain.EdgeModelCatalog
import com.example.domain.EdgeModelSpec
import com.example.domain.InferenceConfig
import com.example.domain.LocalDiscoveredFile
import com.example.domain.MemorySnapshot
import com.example.domain.ModelCapabilityType
import com.example.domain.ModelRuntimeInfo
import com.example.domain.PromptTemplateFormat
import com.example.domain.StorageLocationOption
import com.example.domain.SystemPersona
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import android.provider.OpenableColumns
import com.example.data.remote.WebSearchService
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.nio.charset.StandardCharsets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import com.example.data.remote.OpenCodeService
import com.example.domain.AttachedImageData
import com.example.domain.ExecutionEngineMode
import com.example.domain.OpenCodeModelSpec
import com.example.domain.OpenCodeTier

data class AttachedSmallFile(
    val fileName: String,
    val extension: String,
    val sizeBytes: Long,
    val content: String
)

data class StreamingUiState(
    val isGenerating: Boolean = false,
    val streamingText: String = "",
    val ttftMs: Long? = null,
    val liveTokensPerSec: Float = 0f,
    val tokenCount: Int = 0
)

data class EngineStatusBanner(
    val message: String,
    val isError: Boolean = false,
    val isLoading: Boolean = false
)

data class HfSearchUiState(
    val query: String = "Qwen2.5",
    val isSearching: Boolean = false,
    val results: List<HfRepoSearchResult> = emptyList(),
    val errorMessage: String? = null,
    val selectedRepo: HfRepoSearchResult? = null,
    val isLoadingFiles: Boolean = false,
    val repoVariants: List<HfGgufFileVariant> = emptyList()
)

@OptIn(ExperimentalCoroutinesApi::class)
class EdgeAIViewModel(
    application: Application,
    private val repository: ChatRepository,
    val aiEngine: AIEngine,
    val downloader: ModelDownloader,
    private val hfSearchService: HfModelSearchService = HfModelSearchService(),
    private val webSearchService: WebSearchService = WebSearchService(),
    private val openCodeService: OpenCodeService = OpenCodeService()
) : AndroidViewModel(application) {

    val sessions: StateFlow<List<ChatSessionEntity>> = repository.allSessions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _currentSessionId = MutableStateFlow<Long?>(null)
    val currentSessionId: StateFlow<Long?> = _currentSessionId.asStateFlow()

    val messages: StateFlow<List<ChatMessageEntity>> = _currentSessionId
        .flatMapLatest { sessionId ->
            if (sessionId == null) flowOf(emptyList())
            else repository.getMessagesForSession(sessionId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _streamingState = MutableStateFlow(StreamingUiState())
    val streamingState: StateFlow<StreamingUiState> = _streamingState.asStateFlow()

    private val _catalogModels = MutableStateFlow(EdgeModelCatalog.defaultModels)
    private val _activeDownloads = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val _downloadProgressMap = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val _downloadSpeedMap = MutableStateFlow<Map<String, Float>>(emptyMap())
    private val _downloadedMbMap = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val _loadedModelId = MutableStateFlow<String?>(null)
    val loadedModelId: StateFlow<String?> = _loadedModelId.asStateFlow()

    private val _loadedGgufSummary = MutableStateFlow<String?>(null)

    private val _modelsUiState = MutableStateFlow<List<ModelRuntimeInfo>>(emptyList())
    val modelsUiState: StateFlow<List<ModelRuntimeInfo>> = _modelsUiState.asStateFlow()

    // Ubicación de almacenamiento activa (Memoria Interna por defecto para máxima compatibilidad)
    private val _storageLocation = MutableStateFlow(StorageLocationOption.INTERNAL_FILES)
    val storageLocation: StateFlow<StorageLocationOption> = _storageLocation.asStateFlow()

    private val _activeStoragePath = MutableStateFlow("")
    val activeStoragePath: StateFlow<String> = _activeStoragePath.asStateFlow()

    // Archivos descubiertos localmente en el teléfono / carpeta Descargas
    private val _discoveredLocalFiles = MutableStateFlow<List<LocalDiscoveredFile>>(emptyList())
    val discoveredLocalFiles: StateFlow<List<LocalDiscoveredFile>> = _discoveredLocalFiles.asStateFlow()

    private val _hfSearchState = MutableStateFlow(HfSearchUiState())
    val hfSearchState: StateFlow<HfSearchUiState> = _hfSearchState.asStateFlow()

    private val _hfToken = MutableStateFlow("")
    val hfToken: StateFlow<String> = _hfToken.asStateFlow()

    val personas: List<SystemPersona> = EdgeModelCatalog.defaultPersonas

    private val _selectedPersona = MutableStateFlow(EdgeModelCatalog.defaultPersonas.first())
    val selectedPersona: StateFlow<SystemPersona> = _selectedPersona.asStateFlow()

    private val _customSystemPrompt = MutableStateFlow(EdgeModelCatalog.defaultPersonas.first().systemInstruction)
    val customSystemPrompt: StateFlow<String> = _customSystemPrompt.asStateFlow()

    private val _inferenceConfig = MutableStateFlow(InferenceConfig())
    val inferenceConfig: StateFlow<InferenceConfig> = _inferenceConfig.asStateFlow()

    private val _memorySnapshot = MutableStateFlow(aiEngine.captureMemorySnapshot())
    val memorySnapshot: StateFlow<MemorySnapshot> = _memorySnapshot.asStateFlow()

    // Archivo pequeño adjunto (.txt, .md, .py, .js, .kt, .html, .json, etc.)
    private val _attachedSmallFile = MutableStateFlow<AttachedSmallFile?>(null)
    val attachedSmallFile: StateFlow<AttachedSmallFile?> = _attachedSmallFile.asStateFlow()

    // Foto o imagen adjunta para Análisis Visual / OCR Multimodal
    private val _attachedImage = MutableStateFlow<AttachedImageData?>(null)
    val attachedImage: StateFlow<AttachedImageData?> = _attachedImage.asStateFlow()

    // Modo de Ejecución: Motor Local (GGUF en RAM) vs OpenCode Cloud AI
    private val _executionEngineMode = MutableStateFlow(ExecutionEngineMode.LOCAL_GGUF)
    val executionEngineMode: StateFlow<ExecutionEngineMode> = _executionEngineMode.asStateFlow()

    // Configuración de OpenCode Cloud (API Key, Tier, Modelo y Endpoint)
    private val _openCodeApiKey = MutableStateFlow("")
    val openCodeApiKey: StateFlow<String> = _openCodeApiKey.asStateFlow()

    private val _openCodeBaseUrl = MutableStateFlow("https://openrouter.ai/api/v1/chat/completions")
    val openCodeBaseUrl: StateFlow<String> = _openCodeBaseUrl.asStateFlow()

    private val _selectedOpenCodeTier = MutableStateFlow(OpenCodeTier.FREE)
    val selectedOpenCodeTier: StateFlow<OpenCodeTier> = _selectedOpenCodeTier.asStateFlow()

    private val _selectedOpenCodeModel = MutableStateFlow(EdgeModelCatalog.openCodeModels.first())
    val selectedOpenCodeModel: StateFlow<OpenCodeModelSpec> = _selectedOpenCodeModel.asStateFlow()

    // Opciones del botón de Prompt: Búsqueda rápida en Internet y Generación de Imágenes
    private val _webSearchEnabled = MutableStateFlow(false)
    val webSearchEnabled: StateFlow<Boolean> = _webSearchEnabled.asStateFlow()

    private val _imageGenerationEnabled = MutableStateFlow(false)
    val imageGenerationEnabled: StateFlow<Boolean> = _imageGenerationEnabled.asStateFlow()

    private val _statusBanner = MutableStateFlow<EngineStatusBanner?>(
        EngineStatusBanner("Motor nativo llama.cpp (C++ JNI) listo. Verificando modelos GGUF reales en memoria...")
    )
    val statusBanner: StateFlow<EngineStatusBanner?> = _statusBanner.asStateFlow()

    private var telemetryJob: Job? = null

    private val prefs by lazy {
        getApplication<Application>().getSharedPreferences("otter_ai_settings", Context.MODE_PRIVATE)
    }

    init {
        loadPersistedSettings()
        initializeDefaultSessionAndStorage()
        startTelemetryAndDownloadMonitor()
    }

    private fun loadPersistedSettings() {
        val savedModeStr = prefs.getString("engine_mode", ExecutionEngineMode.LOCAL_GGUF.name)
        val loadedMode = try {
            ExecutionEngineMode.valueOf(savedModeStr ?: ExecutionEngineMode.LOCAL_GGUF.name)
        } catch (e: Exception) {
            ExecutionEngineMode.LOCAL_GGUF
        }
        _executionEngineMode.value = loadedMode

        val savedApiKey = prefs.getString("opencode_api_key", "").orEmpty()
        _openCodeApiKey.value = savedApiKey

        val savedBaseUrl = prefs.getString("opencode_base_url", "https://openrouter.ai/api/v1/chat/completions")
            ?: "https://openrouter.ai/api/v1/chat/completions"
        _openCodeBaseUrl.value = savedBaseUrl

        val savedTierStr = prefs.getString("opencode_tier", OpenCodeTier.FREE.name)
        val loadedTier = try {
            OpenCodeTier.valueOf(savedTierStr ?: OpenCodeTier.FREE.name)
        } catch (e: Exception) {
            OpenCodeTier.FREE
        }
        _selectedOpenCodeTier.value = loadedTier

        val savedModelId = prefs.getString("opencode_model_id", "google/gemini-2.0-flash-exp:free")
        val foundModel = EdgeModelCatalog.openCodeModels.find { it.id == savedModelId }
            ?: EdgeModelCatalog.openCodeModels.firstOrNull { it.tier == loadedTier }
            ?: EdgeModelCatalog.openCodeModels.first()
        _selectedOpenCodeModel.value = foundModel
    }

    private fun initializeDefaultSessionAndStorage() {
        viewModelScope.launch(Dispatchers.IO) {
            // Limpiar cualquier archivo sintético antiguo de 2MB para que solo se ejecuten modelos GGUF reales
            downloader.cleanupSyntheticFiles()

            val dir = downloader.resolveViableStorageDir(_storageLocation.value)
            _activeStoragePath.value = dir.absolutePath

            val localFiles = downloader.scanDeviceForModelFiles()
            _discoveredLocalFiles.value = localFiles

            // 1. Verificar si ya existe algún modelo del catálogo descargado en el dispositivo
            val existingCatalogSpec = _catalogModels.value.firstOrNull {
                downloader.isModelDownloaded(it.fileName, it.customLocalPath)
            }

            if (existingCatalogSpec != null) {
                loadModelIntoRam(existingCatalogSpec)
            } else if (localFiles.isNotEmpty()) {
                // 2. Si el usuario ya tiene un archivo .gguf en su carpeta Descargas o Memoria Interna, cargarlo
                val firstLocal = localFiles.first()
                registerAndLoadLocalFile(File(firstLocal.absolutePath), firstLocal.sourceFolder)
            } else {
                // 3. Si no hay ningún modelo real aún, iniciar automáticamente la descarga del GGUF real ultraligero (~85 MB)
                val starterSpec = _catalogModels.value.find { it.id == "smollm2_135m_q2_k" }
                    ?: _catalogModels.value.first()
                startModelDownload(starterSpec)
            }

            if (_currentSessionId.value == null) {
                val defaultSpec = existingCatalogSpec ?: _catalogModels.value.first()
                val newId = repository.createSession(
                    title = "Conversación General",
                    personaId = _selectedPersona.value.id,
                    customSystemPrompt = _customSystemPrompt.value,
                    modelId = defaultSpec.id
                )
                _currentSessionId.value = newId
                repository.addMessage(
                    sessionId = newId,
                    content = "¡Hola! Ahora el motor utiliza **llama.cpp nativo (JNI en RAM)** sin respuestas predeterminadas.\n\n" +
                        "• Todos los mensajes y bloques de código incluyen un botón **Copiar**.\n" +
                        "• El código generado respeta automáticamente las **sangrías de 4 espacios** y formato monoespaciado.",
                    isUser = false,
                    modelUsed = "llama.cpp JNI"
                )
            }

            refreshModelStoreState()
        }
    }

    private fun startTelemetryAndDownloadMonitor() {
        telemetryJob?.cancel()
        telemetryJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val resolvedDir = downloader.resolveViableStorageDir(_storageLocation.value)
                _activeStoragePath.value = resolvedDir.absolutePath
                _memorySnapshot.value = aiEngine.captureMemorySnapshot().copy(
                    activeStoragePath = resolvedDir.absolutePath
                )
                pollActiveDownloads()
                refreshModelStoreState()
                delay(400L)
            }
        }
    }

    private suspend fun pollActiveDownloads() {
        val currentMap = _activeDownloads.value
        if (currentMap.isEmpty()) return

        val updatedActive = currentMap.toMutableMap()
        val updatedProgress = _downloadProgressMap.value.toMutableMap()
        val updatedSpeed = _downloadSpeedMap.value.toMutableMap()
        val updatedMb = _downloadedMbMap.value.toMutableMap()

        for ((modelId, downloadId) in currentMap) {
            val snapshot = downloader.getDownloadSnapshot(downloadId)
            if (snapshot == null) {
                updatedActive.remove(modelId)
                updatedProgress.remove(modelId)
                updatedSpeed.remove(modelId)
                updatedMb.remove(modelId)
                continue
            }

            updatedProgress[modelId] = snapshot.progressPercent
            updatedSpeed[modelId] = snapshot.speedMbps
            updatedMb[modelId] = (snapshot.bytesDownloaded / (1024L * 1024L)).toInt()

            if (snapshot.isCompleted) {
                updatedActive.remove(modelId)
                updatedProgress[modelId] = 100
                updatedSpeed.remove(modelId)
                val spec = _catalogModels.value.find { it.id == modelId }
                if (spec != null) {
                    _statusBanner.value = EngineStatusBanner(
                        "Descarga completada (${snapshot.savedFilePath ?: "Memoria Interna"}). Cargando en RAM con llama.cpp...",
                        isLoading = true
                    )
                    loadModelIntoRam(spec)
                }
            } else if (snapshot.isFailed) {
                updatedActive.remove(modelId)
                updatedProgress.remove(modelId)
                updatedSpeed.remove(modelId)
                val spec = _catalogModels.value.find { it.id == modelId }
                val errMsg = snapshot.errorMessage
                    ?: "Falló la descarga de ${spec?.name ?: modelId}."
                _statusBanner.value = EngineStatusBanner(errMsg, isError = true)
            }
        }

        _activeDownloads.value = updatedActive
        _downloadProgressMap.value = updatedProgress
        _downloadSpeedMap.value = updatedSpeed
        _downloadedMbMap.value = updatedMb
    }

    fun updateStorageLocation(option: StorageLocationOption) {
        _storageLocation.value = option
        downloader.preferredStorage = option
        val dir = downloader.resolveViableStorageDir(option)
        _activeStoragePath.value = dir.absolutePath
        _statusBanner.value = EngineStatusBanner(
            "Ruta de guardado actualizada: ${dir.absolutePath}"
        )
        refreshModelStoreState()
    }

    /**
     * Escanea la carpeta Download del teléfono y el almacenamiento interno en busca de modelos (.gguf / .bin / .task).
     */
    fun scanLocalDeviceModels() {
        viewModelScope.launch(Dispatchers.IO) {
            val found = downloader.scanDeviceForModelFiles()
            _discoveredLocalFiles.value = found
        }
    }

    /**
     * Importa un archivo seleccionado por el usuario mediante el explorador de archivos del sistema (SAF Uri).
     */
    fun importModelFromUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _statusBanner.value = EngineStatusBanner(
                "Copiando modelo desde el teléfono hacia almacenamiento viable...",
                isLoading = true
            )
            val result = downloader.importModelFromUri(uri)
            result.fold(
                onSuccess = { savedFile ->
                    registerAndLoadLocalFile(savedFile, "Importado del Teléfono")
                },
                onFailure = { err ->
                    _statusBanner.value = EngineStatusBanner(
                        "Error al importar archivo: ${err.message}",
                        isError = true
                    )
                }
            )
        }
    }

    /**
     * Carga o importa un modelo a partir de una ruta local o nombre de archivo en la carpeta Descargas.
     */
    fun loadModelFromLocalPathInput(rawPath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _statusBanner.value = EngineStatusBanner(
                "Buscando archivo en '$rawPath'...",
                isLoading = true
            )
            val result = downloader.resolveOrImportLocalPath(rawPath)
            result.fold(
                onSuccess = { file ->
                    registerAndLoadLocalFile(file, "Ruta Local / Descargas")
                },
                onFailure = { err ->
                    _statusBanner.value = EngineStatusBanner(
                        err.message ?: "No se pudo abrir la ruta indicada.",
                        isError = true
                    )
                }
            )
        }
    }

    private fun registerAndLoadLocalFile(file: File, originLabel: String) {
        val sizeMb = (file.length() / (1024L * 1024L)).toInt().coerceAtLeast(1)
        val lower = file.name.lowercase()
        val format = when {
            "gemma" in lower -> PromptTemplateFormat.GEMMA
            "phi" in lower -> PromptTemplateFormat.PHI3
            "qwen" in lower || "smollm" in lower || "deepseek" in lower -> PromptTemplateFormat.CHATML
            else -> PromptTemplateFormat.LLAMA3
        }
        val capability = ModelCapabilityType.inferFromNameAndTags(file.name)
        val spec = EdgeModelSpec(
            id = "local_${file.name.replace("[^a-zA-Z0-9_]".toRegex(), "_")}",
            name = file.nameWithoutExtension,
            provider = originLabel,
            family = if (file.name.endsWith(".gguf", true)) "Local • GGUF" else "Local • MediaPipe",
            quantization = "Local File",
            accelerator = "llama.cpp JNI (RAM)",
            fileName = file.name,
            downloadUrl = file.absolutePath,
            estimatedSizeMb = sizeMb,
            minRamMb = (sizeMb * 1.4).toInt().coerceAtLeast(512),
            description = "Archivo local (${capability.badge}) cargado desde: ${file.absolutePath}",
            promptFormat = format,
            capability = capability,
            isCustom = true,
            customLocalPath = file.absolutePath
        )

        _catalogModels.update { list ->
            val filtered = list.filterNot { it.fileName.equals(file.name, ignoreCase = true) }
            listOf(spec) + filtered
        }
        scanLocalDeviceModels()
        refreshModelStoreState()
        loadModelIntoRam(spec)
    }

    fun refreshModelStoreState() {
        val activeMap = _activeDownloads.value
        val progressMap = _downloadProgressMap.value
        val speedMap = _downloadSpeedMap.value
        val mbMap = _downloadedMbMap.value
        val activeRamId = _loadedModelId.value
        val ggufSummary = _loadedGgufSummary.value

        val list = _catalogModels.value.map { spec ->
            val file = downloader.getModelFile(spec.fileName, spec.customLocalPath)
            val downloaded = downloader.isModelDownloaded(spec.fileName, spec.customLocalPath)
            val diskSize = if (downloaded) file.length() else 0L
            val isDownloading = activeMap.containsKey(spec.id)
            val progress = when {
                isDownloading -> progressMap[spec.id] ?: 0
                downloaded -> 100
                else -> 0
            }
            val isLoaded = (activeRamId == spec.id && aiEngine.isLoaded)
            ModelRuntimeInfo(
                spec = spec,
                isDownloaded = downloaded && !isDownloading,
                sizeOnDiskBytes = diskSize,
                resolvedPath = if (downloaded) file.absolutePath else "",
                isDownloading = isDownloading,
                downloadProgress = progress,
                downloadedMb = mbMap[spec.id] ?: (diskSize / (1024L * 1024L)).toInt(),
                downloadSpeedMbps = speedMap[spec.id] ?: 0f,
                downloadId = activeMap[spec.id] ?: -1L,
                isLoadedInRam = isLoaded,
                ggufDetails = if (isLoaded) ggufSummary else null
            )
        }
        _modelsUiState.value = list
    }

    fun searchHuggingFaceModels(query: String) {
        _hfSearchState.update {
            it.copy(
                query = query,
                isSearching = true,
                errorMessage = null,
                selectedRepo = null,
                repoVariants = emptyList()
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = hfSearchService.searchGgufRepositories(query, _hfToken.value)
            result.fold(
                onSuccess = { repos ->
                    _hfSearchState.update {
                        it.copy(
                            isSearching = false,
                            results = repos,
                            errorMessage = if (repos.isEmpty()) "No se encontraron repositorios GGUF para '$query'." else null
                        )
                    }
                },
                onFailure = { err ->
                    _hfSearchState.update {
                        it.copy(
                            isSearching = false,
                            errorMessage = "Error buscando en Hugging Face: ${err.message}"
                        )
                    }
                }
            )
        }
    }

    fun inspectHuggingFaceRepo(repo: HfRepoSearchResult) {
        _hfSearchState.update {
            it.copy(
                selectedRepo = repo,
                isLoadingFiles = true,
                repoVariants = emptyList(),
                errorMessage = null
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = hfSearchService.fetchRepoGgufFiles(repo.repoId, _hfToken.value)
            result.fold(
                onSuccess = { files ->
                    _hfSearchState.update {
                        it.copy(
                            isLoadingFiles = false,
                            repoVariants = files,
                            errorMessage = if (files.isEmpty()) "Este repositorio no contiene archivos .gguf directos en la rama main." else null
                        )
                    }
                },
                onFailure = { err ->
                    _hfSearchState.update {
                        it.copy(
                            isLoadingFiles = false,
                            errorMessage = "No se pudieron listar los archivos de ${repo.repoId}: ${err.message}"
                        )
                    }
                }
            )
        }
    }

    fun closeRepoInspector() {
        _hfSearchState.update { it.copy(selectedRepo = null, repoVariants = emptyList()) }
    }

    fun downloadHfVariant(repo: HfRepoSearchResult, variant: HfGgufFileVariant) {
        val spec = hfSearchService.toEdgeModelSpec(repo, variant)
        _catalogModels.update { current ->
            if (current.any { it.id == spec.id || it.fileName == spec.fileName }) {
                current
            } else {
                listOf(spec) + current
            }
        }
        _hfSearchState.update { it.copy(selectedRepo = null, repoVariants = emptyList()) }
        refreshModelStoreState()
        startModelDownload(spec)
    }

    fun updateHfToken(token: String) {
        _hfToken.value = token
        downloader.hfAuthToken = token.trim()
    }

    fun selectSession(session: ChatSessionEntity) {
        _currentSessionId.value = session.id
        val persona = personas.find { it.id == session.personaId } ?: personas.first()
        _selectedPersona.value = persona
        _customSystemPrompt.value = session.customSystemPrompt.ifBlank { persona.systemInstruction }
    }

    fun createNewChatSession() {
        viewModelScope.launch(Dispatchers.IO) {
            val activeModel = _loadedModelId.value ?: _catalogModels.value.first().id
            val newId = repository.createSession(
                title = "Nueva conversación",
                personaId = _selectedPersona.value.id,
                customSystemPrompt = _customSystemPrompt.value,
                modelId = activeModel
            )
            _currentSessionId.value = newId
            repository.addMessage(
                sessionId = newId,
                content = "Nueva sesión iniciada con personalidad **${_selectedPersona.value.title}**. ¿Sobre qué tema quieres conversar?",
                isUser = false,
                modelUsed = activeModel
            )
        }
    }

    fun deleteChatSession(sessionId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteSession(sessionId)
            if (_currentSessionId.value == sessionId) {
                val fallback = sessions.value.firstOrNull { it.id != sessionId }
                if (fallback != null) {
                    _currentSessionId.value = fallback.id
                } else {
                    createNewChatSession()
                }
            }
        }
    }

    fun clearCurrentChatHistory() {
        val sessionId = _currentSessionId.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            repository.clearSessionMessages(sessionId)
            repository.addMessage(
                sessionId = sessionId,
                content = "Historial y buffer de contexto reiniciados.",
                isUser = false,
                modelUsed = _loadedModelId.value ?: "Sistema"
            )
        }
    }

    fun selectPersona(persona: SystemPersona) {
        _selectedPersona.value = persona
        if (persona.id != "custom") {
            _customSystemPrompt.value = persona.systemInstruction
        }
        _inferenceConfig.update { it.copy(temperature = persona.recommendedTemperature) }
        savePersonaToCurrentSession()
    }

    fun updateCustomSystemPrompt(newPrompt: String) {
        _customSystemPrompt.value = newPrompt
        savePersonaToCurrentSession()
    }

    private fun savePersonaToCurrentSession() {
        val sessionId = _currentSessionId.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            repository.getSessionById(sessionId)?.let { current ->
                repository.updateSession(
                    current.copy(
                        personaId = _selectedPersona.value.id,
                        customSystemPrompt = _customSystemPrompt.value
                    )
                )
            }
        }
    }

    fun updateInferenceConfig(
        temperature: Float = _inferenceConfig.value.temperature,
        maxTokens: Int = _inferenceConfig.value.maxTokens,
        topK: Int = _inferenceConfig.value.topK,
        contextBufferSize: Int = _inferenceConfig.value.contextBufferSize
    ) {
        _inferenceConfig.value = InferenceConfig(
            maxTokens = maxTokens.coerceIn(128, 2048),
            temperature = temperature.coerceIn(0.0f, 1.5f),
            topK = topK.coerceIn(1, 100),
            contextBufferSize = contextBufferSize.coerceIn(2, 12)
        )
    }

    fun startModelDownload(spec: EdgeModelSpec) {
        viewModelScope.launch(Dispatchers.IO) {
            val targetDir = downloader.resolveViableStorageDir(_storageLocation.value)
            _statusBanner.value = EngineStatusBanner(
                "Descargando ${spec.name} (~${spec.estimatedSizeMb} MB) en ${targetDir.absolutePath}...",
                isLoading = true
            )

            val downloadId = downloader.downloadModel(spec.downloadUrl, spec.fileName) {
                viewModelScope.launch(Dispatchers.IO) {
                    scanLocalDeviceModels()
                    loadModelIntoRam(spec)
                }
            }

            if (downloadId != -1L) {
                _activeDownloads.update { it + (spec.id to downloadId) }
                _downloadProgressMap.update { it + (spec.id to 1) }
                refreshModelStoreState()
            } else {
                loadModelIntoRam(spec)
            }
        }
    }

    fun cancelModelDownload(spec: EdgeModelSpec) {
        viewModelScope.launch(Dispatchers.IO) {
            val downloadId = _activeDownloads.value[spec.id] ?: return@launch
            downloader.cancelDownload(downloadId, spec.fileName)
            _activeDownloads.update { it - spec.id }
            _downloadProgressMap.update { it - spec.id }
            _downloadSpeedMap.update { it - spec.id }
            _downloadedMbMap.update { it - spec.id }
            _statusBanner.value = EngineStatusBanner("Descarga de ${spec.name} cancelada.")
            refreshModelStoreState()
        }
    }

    fun deleteModelFromDisk(spec: EdgeModelSpec) {
        viewModelScope.launch(Dispatchers.IO) {
            if (_loadedModelId.value == spec.id) {
                aiEngine.unloadModelSafe()
                _loadedModelId.value = null
                _loadedGgufSummary.value = null
            }
            downloader.deleteModelFile(spec.fileName, spec.customLocalPath)
            _statusBanner.value = EngineStatusBanner("Archivo ${spec.fileName} eliminado.")
            scanLocalDeviceModels()
            refreshModelStoreState()
        }
    }

    fun addCustomModel(
        name: String,
        url: String,
        fileName: String,
        format: PromptTemplateFormat,
        capability: ModelCapabilityType = ModelCapabilityType.inferFromNameAndTags("$name $fileName")
    ) {
        val cleanName = name.trim().ifEmpty { "Modelo Personalizado" }
        val cleanFile = fileName.trim().ifEmpty { "custom-model.gguf" }
        val isGguf = cleanFile.endsWith(".gguf", true) || url.contains(".gguf", true)
        val customSpec = EdgeModelSpec(
            id = "custom_${System.currentTimeMillis()}",
            name = cleanName,
            provider = "Custom URL",
            family = if (isGguf) "${format.name} • GGUF" else format.name,
            quantization = if (isGguf) "GGUF" else "Custom",
            accelerator = if (isGguf) "llama.cpp JNI (RAM)" else "GPU / CPU",
            fileName = cleanFile,
            downloadUrl = url.trim(),
            estimatedSizeMb = 500,
            minRamMb = 1000,
            description = "Modelo (${capability.badge}) añadido desde URL: $url",
            promptFormat = format,
            capability = capability,
            isCustom = true
        )
        _catalogModels.update { listOf(customSpec) + it }
        refreshModelStoreState()
        startModelDownload(customSpec)
    }

    fun loadModelIntoRam(spec: EdgeModelSpec) {
        viewModelScope.launch(Dispatchers.IO) {
            val file = downloader.getModelFile(spec.fileName, spec.customLocalPath)
            if (!file.exists() || file.length() == 0L) {
                _statusBanner.value = EngineStatusBanner(
                    "El archivo ${spec.fileName} aún no está descargado en disco.",
                    isError = true
                )
                return@launch
            }

            _statusBanner.value = EngineStatusBanner(
                "Cargando ${spec.name} (${spec.capability.badge}) en RAM con llama.cpp...",
                isLoading = true
            )

            val result = aiEngine.initEngineSafe(file, _inferenceConfig.value)
            result.fold(
                onSuccess = { details ->
                    _loadedModelId.value = spec.id
                    _loadedGgufSummary.value = details
                    // Si el modelo cargado NO soporta imágenes, desactivar automáticamente la creación de imágenes
                    if (!spec.capability.supportsImages && _imageGenerationEnabled.value) {
                        _imageGenerationEnabled.value = false
                    }
                    _statusBanner.value = EngineStatusBanner(
                        "Modelo activo: ${spec.name} • ${spec.capability.badge}"
                    )
                },
                onFailure = { error ->
                    _loadedModelId.value = null
                    _loadedGgufSummary.value = null
                    _imageGenerationEnabled.value = false
                    _statusBanner.value = EngineStatusBanner(
                        "Error al cargar ${spec.name}: ${error.message}",
                        isError = true
                    )
                }
            )
            refreshModelStoreState()
        }
    }

    fun unloadModelFromRam(reason: String = "Memoria nativa liberada manualmente.") {
        viewModelScope.launch(Dispatchers.IO) {
            aiEngine.unloadModelSafe()
            _loadedModelId.value = null
            _loadedGgufSummary.value = null
            _imageGenerationEnabled.value = false
            _statusBanner.value = EngineStatusBanner(reason)
            refreshModelStoreState()
        }
    }

    fun onSystemTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            if (aiEngine.isLoaded && !aiEngine.isGenerating) {
                unloadModelFromRam(
                    "Protector OOM activado: Android reportó RAM crítica. Se liberó el modelo de la RAM automáticamente."
                )
            }
        }
    }

    fun notifyPermissionResult(granted: Boolean) {
        scanLocalDeviceModels()
        _statusBanner.value = if (granted) {
            EngineStatusBanner("Permisos de lectura y escritura de archivos concedidos correctamente.")
        } else {
            EngineStatusBanner("Usando Selector Seguro de Archivos y Memoria Interna de la app.")
        }
    }

    @Volatile
    private var currentStreamingStopHandler: (() -> Unit)? = null

    fun stopGeneration() {
        openCodeService.cancelActiveCall()
        currentStreamingStopHandler?.invoke()
        currentStreamingStopHandler = null
        aiEngine.stopCurrentGeneration()
    }

    fun toggleWebSearch(enabled: Boolean) {
        _webSearchEnabled.value = enabled
        _statusBanner.value = EngineStatusBanner(
            if (enabled) "Búsqueda de información adicional en Internet activada 🌐."
            else "Búsqueda en Internet desactivada (Modo 100% Local)."
        )
    }

    fun toggleImageGeneration(enabled: Boolean) {
        val activeSpec = _catalogModels.value.find { it.id == _loadedModelId.value }
        val supportsImages = activeSpec?.capability?.supportsImages == true
        if (enabled && !supportsImages) {
            _imageGenerationEnabled.value = false
            _statusBanner.value = EngineStatusBanner(
                "El modelo actual (${activeSpec?.capability?.badge ?: "Solo Texto"}) no incluye creación de imágenes. Carga un modelo '🎨 Texto + Imágenes' en Model Store.",
                isError = true
            )
            return
        }
        _imageGenerationEnabled.value = enabled
        _statusBanner.value = EngineStatusBanner(
            if (enabled) "Modo Creación de Imágenes activado (${activeSpec?.name ?: "Multimodal"})."
            else "Creación de imágenes desactivada. La IA generará respuestas en texto."
        )
    }

    fun setExecutionEngineMode(mode: ExecutionEngineMode) {
        _executionEngineMode.value = mode
        prefs.edit().putString("engine_mode", mode.name).apply()
        _statusBanner.value = EngineStatusBanner(
            if (mode == ExecutionEngineMode.OPENCODE_CLOUD)
                "Modo Nube activado: OpenCode Cloud AI (${_selectedOpenCodeModel.value.name})."
            else
                "Modo Local activado: llama.cpp JNI en RAM (100% privado en el teléfono)."
        )
    }

    fun setOpenCodeApiKey(key: String) {
        val trimmed = key.trim()
        _openCodeApiKey.value = trimmed
        prefs.edit().putString("opencode_api_key", trimmed).apply()
        _statusBanner.value = EngineStatusBanner(
            if (trimmed.isNotBlank()) "API Key de OpenCode guardada correctamente."
            else "API Key borrada. Los modelos gratis seguirán funcionando."
        )
    }

    fun setOpenCodeBaseUrl(url: String) {
        val trimmed = url.trim()
        _openCodeBaseUrl.value = trimmed
        prefs.edit().putString("opencode_base_url", trimmed).apply()
    }

    fun selectOpenCodeTier(tier: OpenCodeTier) {
        _selectedOpenCodeTier.value = tier
        prefs.edit().putString("opencode_tier", tier.name).apply()
        val firstInTier = EdgeModelCatalog.openCodeModels.firstOrNull { it.tier == tier }
        if (firstInTier != null) {
            selectOpenCodeModel(firstInTier)
        }
    }

    fun selectOpenCodeModel(model: OpenCodeModelSpec) {
        _selectedOpenCodeModel.value = model
        _selectedOpenCodeTier.value = model.tier
        prefs.edit()
            .putString("opencode_model_id", model.id)
            .putString("opencode_tier", model.tier.name)
            .apply()
        _statusBanner.value = EngineStatusBanner(
            "Modelo seleccionado: ${model.name} (${model.tier.badge})"
        )
    }

    /**
     * Procesa una imagen seleccionada por el usuario (Android Photo Picker),
     * la escala de forma segura a máx 1280px para evitar OOM, y la codifica en Base64
     * para análisis visual y OCR multimodal.
     */
    fun attachImageFromUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resolver = getApplication<Application>().contentResolver
                var displayName = "foto_${System.currentTimeMillis()}.jpg"
                var fileSize = 0L

                resolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIdx >= 0) displayName = cursor.getString(nameIdx) ?: displayName
                        if (sizeIdx >= 0) fileSize = cursor.getLong(sizeIdx)
                    }
                }

                val inputStream = resolver.openInputStream(uri)
                if (inputStream == null) {
                    _statusBanner.value = EngineStatusBanner("No se pudo abrir la foto seleccionada.", isError = true)
                    return@launch
                }

                val originalBitmap = BitmapFactory.decodeStream(inputStream)
                inputStream.close()

                if (originalBitmap == null) {
                    _statusBanner.value = EngineStatusBanner("No se pudo decodificar la imagen seleccionada.", isError = true)
                    return@launch
                }

                // Redimensionar si supera 1280x1280 para agilizar el procesamiento y la red
                val maxDim = 1280
                val width = originalBitmap.width
                val height = originalBitmap.height
                val scaledBitmap = if (width > maxDim || height > maxDim) {
                    val ratio = width.toFloat() / height.toFloat()
                    val targetW = if (ratio > 1f) maxDim else (maxDim * ratio).toInt().coerceAtLeast(1)
                    val targetH = if (ratio > 1f) (maxDim / ratio).toInt().coerceAtLeast(1) else maxDim
                    Bitmap.createScaledBitmap(originalBitmap, targetW, targetH, true)
                } else {
                    originalBitmap
                }

                val baos = ByteArrayOutputStream()
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
                val imageBytes = baos.toByteArray()
                val base64Str = Base64.encodeToString(imageBytes, Base64.NO_WRAP)

                _attachedImage.value = AttachedImageData(
                    uri = uri,
                    base64Data = base64Str,
                    mimeType = "image/jpeg",
                    fileName = displayName,
                    sizeBytes = if (fileSize > 0L) fileSize else imageBytes.size.toLong()
                )

                _statusBanner.value = EngineStatusBanner(
                    "📷 Foto lista para análisis visual / OCR: $displayName (${imageBytes.size / 1024} KB)"
                )
            } catch (e: Exception) {
                _statusBanner.value = EngineStatusBanner(
                    "Error al procesar la imagen: ${e.message}",
                    isError = true
                )
            }
        }
    }

    fun clearAttachedImage() {
        _attachedImage.value = null
    }

    /**
     * Carga un archivo pequeño de texto o código (.txt, .md, .py, .js, .kt, .html, .json, .csv, etc.)
     * para adjuntarlo al mensaje actual.
     */
    fun attachSmallFileFromUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resolver = getApplication<Application>().contentResolver
                var displayName = "archivo_adjunto.txt"
                var fileSize = 0L

                resolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIdx >= 0) displayName = cursor.getString(nameIdx) ?: displayName
                        if (sizeIdx >= 0) fileSize = cursor.getLong(sizeIdx)
                    }
                }

                // Límite de lectura segura (48 KB) para no desbordar la ventana de contexto del modelo en RAM
                val maxBytes = 48 * 1024
                val bytes = resolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(maxBytes)
                    val read = input.read(buffer)
                    if (read > 0) buffer.copyOf(read) else ByteArray(0)
                } ?: ByteArray(0)

                if (bytes.isEmpty()) {
                    _statusBanner.value = EngineStatusBanner(
                        "El archivo seleccionado está vacío o no se pudo leer.",
                        isError = true
                    )
                    return@launch
                }

                val textContent = String(bytes, StandardCharsets.UTF_8).take(12_000)
                val ext = displayName.substringAfterLast('.', "txt").lowercase()

                _attachedSmallFile.value = AttachedSmallFile(
                    fileName = displayName,
                    extension = ext,
                    sizeBytes = if (fileSize > 0L) fileSize else bytes.size.toLong(),
                    content = textContent
                )
                _statusBanner.value = EngineStatusBanner(
                    "Archivo adjunto listo: $displayName (${bytes.size / 1024} KB)"
                )
            } catch (e: Exception) {
                _statusBanner.value = EngineStatusBanner(
                    "No se pudo leer el archivo adjunto: ${e.message}",
                    isError = true
                )
            }
        }
    }

    fun clearAttachedSmallFile() {
        _attachedSmallFile.value = null
    }

    /**
     * Permite editar el texto de un mensaje ya enviado por el usuario, truncando los mensajes
     * posteriores de esa sesión y retomando la generación inmediatamente con los cambios hechos.
     */
    fun editAndResendMessage(message: ChatMessageEntity, updatedText: String) {
        val cleanUpdated = updatedText.trim()
        if (cleanUpdated.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            if (_streamingState.value.isGenerating) {
                stopGeneration()
                delay(150L)
            }
            repository.truncateMessagesFrom(message.sessionId, message.id)
            sendUserMessage(cleanUpdated)
        }
    }

    fun sendUserMessage(userText: String) {
        val cleanText = userText.trim()
        val attachedFile = _attachedSmallFile.value
        val attachedImg = _attachedImage.value

        if ((cleanText.isEmpty() && attachedFile == null && attachedImg == null) || _streamingState.value.isGenerating) {
            return
        }

        val sessionId = _currentSessionId.value ?: return
        _attachedSmallFile.value = null
        _attachedImage.value = null

        viewModelScope.launch(Dispatchers.IO) {
            val bufferLimit = _inferenceConfig.value.contextBufferSize
            val recentContext = repository.getRecentContextMessages(sessionId, bufferLimit)

            // Construir el mensaje visible del usuario en el chat (con foto y archivo adjunto)
            val userDisplayMessage = buildString {
                if (attachedImg != null) {
                    append("📷 **Foto adjunta para análisis visual / OCR:** `${attachedImg.fileName}`\n")
                    // Insertamos el tag de imagen con base64 para que el chat bubble la renderice directamente
                    append("![${attachedImg.fileName}](data:image/jpeg;base64,${attachedImg.base64Data})\n\n")
                }
                if (attachedFile != null) {
                    append("📎 **Archivo adjunto:** `${attachedFile.fileName}`\n")
                    append("```").append(attachedFile.extension).append("\n")
                    append(attachedFile.content.trim()).append("\n```\n\n")
                }
                append(cleanText.ifEmpty {
                    if (attachedImg != null) "Analiza esta imagen detalladamente, extrae cualquier texto escrito (OCR) y describe los detalles visuales."
                    else "Analiza el archivo adjunto."
                })
            }

            repository.addMessage(
                sessionId = sessionId,
                content = userDisplayMessage,
                isUser = true
            )

            // Si está activada la búsqueda de información adicional en Internet
            val shouldSearchWeb = _webSearchEnabled.value ||
                cleanText.lowercase().startsWith("busca en internet") ||
                cleanText.lowercase().startsWith("buscar en internet")

            var webContextBlock = ""
            if (shouldSearchWeb && cleanText.isNotBlank()) {
                _statusBanner.value = EngineStatusBanner(
                    "Buscando información adicional en Internet para '${cleanText.take(35)}'...",
                    isLoading = true
                )
                val snippets = webSearchService.searchInternetSnippets(cleanText)
                if (snippets.isNotEmpty()) {
                    webContextBlock = buildString {
                        append("[Información verificada de Internet]:\n")
                        snippets.forEachIndexed { idx, item ->
                            append("${idx + 1}. ${item.title}: ${item.snippet}\n")
                        }
                        append("\nUsa esta información actualizada para responder a la consulta del usuario:\n")
                    }
                    _statusBanner.value = EngineStatusBanner(
                        "Información adicional de Internet integrada (${snippets.size} fuentes)."
                    )
                } else {
                    _statusBanner.value = EngineStatusBanner(
                        "Sin resultados web directos; respondiendo con conocimiento local en texto."
                    )
                }
            }

            val effectiveUserQuery = buildString {
                if (webContextBlock.isNotBlank()) {
                    append(webContextBlock).append("\n")
                }
                append(cleanText.ifEmpty {
                    if (attachedImg != null) "Analiza la imagen adjunta en detalle (OCR y elementos visuales)."
                    else "Analiza el archivo adjunto."
                })
            }

            // =========================================================================
            // RUTEO 1: EJECUCIÓN EN LA NUBE VÍA OPENCODE CLOUD AI (API KEY)
            // =========================================================================
            if (_executionEngineMode.value == ExecutionEngineMode.OPENCODE_CLOUD) {
                val cloudModel = _selectedOpenCodeModel.value
                val startTimeMs = System.currentTimeMillis()
                var firstTokenTimeMs: Long? = null
                var accumulatedText = ""
                var tokenChunks = 0
                val savedAtomic = java.util.concurrent.atomic.AtomicBoolean(false)

                _streamingState.value = StreamingUiState(
                    isGenerating = true,
                    streamingText = "",
                    ttftMs = null,
                    liveTokensPerSec = 0f,
                    tokenCount = 0
                )

                fun finalizeCloudResponse(wasStoppedByUser: Boolean, errorMsg: String? = null) {
                    if (!savedAtomic.compareAndSet(false, true)) return
                    currentStreamingStopHandler = null
                    val now = System.currentTimeMillis()
                    val elapsedSec = ((now - (firstTokenTimeMs?.let { startTimeMs + it } ?: startTimeMs)).coerceAtLeast(50L)) / 1000f
                    val finalTps = if (tokenChunks > 0) (tokenChunks / elapsedSec) else 0f
                    val finalTtft = firstTokenTimeMs

                    val finalContent = when {
                        errorMsg != null -> if (accumulatedText.isNotBlank()) "$accumulatedText\n\n⚠️ *$errorMsg*" else "⚠️ $errorMsg"
                        accumulatedText.isNotBlank() && wasStoppedByUser -> "$accumulatedText\n\n*[Generación detenida]*"
                        accumulatedText.isNotBlank() -> accumulatedText.trimEnd()
                        wasStoppedByUser -> "*[Solicitud detenida por el usuario]*"
                        else -> "Sin respuesta generada por el modelo de OpenCode."
                    }

                    _streamingState.value = StreamingUiState(isGenerating = false)
                    viewModelScope.launch(Dispatchers.IO) {
                        repository.addMessage(
                            sessionId = sessionId,
                            content = finalContent,
                            isUser = false,
                            tokensPerSec = if (finalTps > 0f) finalTps else null,
                            ttftMs = finalTtft,
                            modelUsed = "${cloudModel.name} (${cloudModel.tier.badge})"
                        )
                    }
                }

                currentStreamingStopHandler = {
                    openCodeService.cancelActiveCall()
                    finalizeCloudResponse(wasStoppedByUser = true)
                }

                _statusBanner.value = EngineStatusBanner(
                    "Conectando con OpenCode Cloud (${cloudModel.name})...",
                    isLoading = true
                )

                openCodeService.streamChatCompletion(
                    apiKey = _openCodeApiKey.value,
                    baseUrl = _openCodeBaseUrl.value,
                    model = cloudModel,
                    systemPrompt = _customSystemPrompt.value,
                    contextHistory = recentContext,
                    currentUserText = effectiveUserQuery,
                    attachedImage = attachedImg,
                    temperature = _inferenceConfig.value.temperature,
                    maxTokens = _inferenceConfig.value.maxTokens,
                    onToken = { token ->
                        if (savedAtomic.get()) return@streamChatCompletion
                        val now = System.currentTimeMillis()
                        if (firstTokenTimeMs == null) {
                            firstTokenTimeMs = (now - startTimeMs).coerceAtLeast(1L)
                        }
                        accumulatedText += token
                        tokenChunks++

                        val elapsedSec = ((now - (firstTokenTimeMs?.let { startTimeMs + it } ?: startTimeMs)).coerceAtLeast(50L)) / 1000f
                        val tps = if (tokenChunks > 0) (tokenChunks / elapsedSec) else 0f

                        _streamingState.value = StreamingUiState(
                            isGenerating = true,
                            streamingText = accumulatedText,
                            ttftMs = firstTokenTimeMs,
                            liveTokensPerSec = tps,
                            tokenCount = tokenChunks
                        )
                    },
                    onError = { err ->
                        _statusBanner.value = EngineStatusBanner(err, isError = true)
                        finalizeCloudResponse(wasStoppedByUser = false, errorMsg = err)
                    },
                    onDone = {
                        _statusBanner.value = EngineStatusBanner("Respuesta completada por ${cloudModel.name}.")
                        finalizeCloudResponse(wasStoppedByUser = false)
                    }
                )
                return@launch
            }

            // =========================================================================
            // RUTEO 2: EJECUCIÓN LOCAL EN RAM MEDIANTE LLAMA.CPP JNI
            // =========================================================================
            if (!aiEngine.isLoaded) {
                val downloadedSpec = _catalogModels.value.firstOrNull {
                    downloader.isModelDownloaded(it.fileName, it.customLocalPath)
                }

                if (downloadedSpec != null) {
                    _statusBanner.value = EngineStatusBanner(
                        "Inicializando ${downloadedSpec.name} (${downloadedSpec.capability.badge}) en RAM...",
                        isLoading = true
                    )
                    val initRes = aiEngine.initEngineSafe(
                        downloader.getModelFile(downloadedSpec.fileName, downloadedSpec.customLocalPath),
                        _inferenceConfig.value
                    )
                    initRes.onSuccess { details ->
                        _loadedModelId.value = downloadedSpec.id
                        _loadedGgufSummary.value = details
                        _statusBanner.value = EngineStatusBanner("Modelo en RAM: ${downloadedSpec.name} (${downloadedSpec.capability.badge})")
                        refreshModelStoreState()
                    }.onFailure { err ->
                        _statusBanner.value = EngineStatusBanner(
                            "Aviso cargando modelo: ${err.message}",
                            isError = true
                        )
                    }
                }
            }

            val activeModelSpec = _catalogModels.value.find { it.id == _loadedModelId.value }
                ?: _catalogModels.value.first()

            val effectiveLocalQuery = buildString {
                if (attachedImg != null) {
                    append("[Nota: El usuario adjuntó una foto '${attachedImg.fileName}'].\n")
                    append("Para análisis neuronal visual y OCR de alta fidelidad, te sugerimos activar el Modo Nube (OpenCode).\n\n")
                }
                append(effectiveUserQuery)
            }

            val fullContextualPrompt = EdgeModelCatalog.buildContextualPrompt(
                format = activeModelSpec.promptFormat,
                systemPrompt = _customSystemPrompt.value,
                contextHistory = recentContext,
                currentUserMessage = effectiveLocalQuery,
                maxContextMessages = bufferLimit
            )

            val startTimeMs = System.currentTimeMillis()
            var firstTokenTimeMs: Long? = null
            var accumulatedText = ""
            var tokenChunks = 0
            val savedAtomic = java.util.concurrent.atomic.AtomicBoolean(false)

            _streamingState.value = StreamingUiState(
                isGenerating = true,
                streamingText = accumulatedText,
                ttftMs = null,
                liveTokensPerSec = 0f,
                tokenCount = 0
            )

            fun finalizeAndSaveResponse(wasStoppedByUser: Boolean) {
                if (!savedAtomic.compareAndSet(false, true)) return
                currentStreamingStopHandler = null
                val now = System.currentTimeMillis()
                val elapsedSec = ((now - (firstTokenTimeMs?.let { startTimeMs + it } ?: startTimeMs)).coerceAtLeast(50L)) / 1000f
                val finalTps = if (tokenChunks > 0) (tokenChunks / elapsedSec) else 0f
                val finalTtft = firstTokenTimeMs
                val modelLabel = _catalogModels.value.find { it.id == _loadedModelId.value }?.name ?: "llama.cpp JNI"

                val trimmedContent = accumulatedText.trimEnd()
                val finalContent = when {
                    trimmedContent.isNotEmpty() && wasStoppedByUser -> "$trimmedContent\n\n*[Generación detenida]*"
                    trimmedContent.isNotEmpty() -> trimmedContent
                    wasStoppedByUser -> "*[Solicitud detenida por el usuario]*"
                    else -> "Sin respuesta generada por el modelo."
                }

                _streamingState.value = StreamingUiState(isGenerating = false)
                viewModelScope.launch(Dispatchers.IO) {
                    repository.addMessage(
                        sessionId = sessionId,
                        content = finalContent,
                        isUser = false,
                        tokensPerSec = if (finalTps > 0f) finalTps else null,
                        ttftMs = finalTtft,
                        modelUsed = modelLabel
                    )
                }
            }

            currentStreamingStopHandler = {
                aiEngine.stopCurrentGeneration()
                finalizeAndSaveResponse(wasStoppedByUser = true)
            }

            aiEngine.generateResponseStreaming(fullContextualPrompt) { partialToken, isDone ->
                if (savedAtomic.get()) return@generateResponseStreaming

                val now = System.currentTimeMillis()
                if (partialToken.isNotEmpty() && firstTokenTimeMs == null) {
                    firstTokenTimeMs = (now - startTimeMs).coerceAtLeast(1L)
                }
                accumulatedText += partialToken
                if (partialToken.isNotEmpty()) {
                    tokenChunks++
                }

                val elapsedSec = ((now - (firstTokenTimeMs?.let { startTimeMs + it } ?: startTimeMs)).coerceAtLeast(50L)) / 1000f
                val tps = if (tokenChunks > 0) (tokenChunks / elapsedSec) else 0f

                _streamingState.value = StreamingUiState(
                    isGenerating = !isDone,
                    streamingText = accumulatedText,
                    ttftMs = firstTokenTimeMs,
                    liveTokensPerSec = tps,
                    tokenCount = tokenChunks
                )

                if (isDone) {
                    finalizeAndSaveResponse(wasStoppedByUser = false)
                }
            }
        }
    }

    fun dismissBanner() {
        _statusBanner.value = null
    }

    override fun onCleared() {
        super.onCleared()
        telemetryJob?.cancel()
        aiEngine.close()
    }

    class Factory(
        private val application: Application
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val db = AppDatabase.getInstance(application)
            val repo = ChatRepository(db.chatDao())
            val engine = AIEngine(application)
            val modelDownloader = ModelDownloader(application)
            return EdgeAIViewModel(application, repo, engine, modelDownloader) as T
        }
    }
}
