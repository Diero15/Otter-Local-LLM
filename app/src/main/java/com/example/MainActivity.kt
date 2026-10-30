package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.EdgeAIViewModel
import com.example.ui.screens.ChatScreen
import com.example.ui.screens.ModelStoreScreen
import com.example.ui.screens.RamMonitorScreen
import com.example.ui.screens.SystemPromptScreen
import com.example.ui.theme.MyApplicationTheme

enum class StudioDestination(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    CHAT("chat", "Chat IA", Icons.Filled.ChatBubble, Icons.Outlined.ChatBubbleOutline),
    MODEL_STORE("models", "Model Store", Icons.Filled.Storefront, Icons.Outlined.Storefront),
    PERSONA("persona", "System Prompt", Icons.Filled.Psychology, Icons.Outlined.Psychology),
    RAM_MONITOR("ram", "RAM & Motor", Icons.Filled.Memory, Icons.Outlined.Memory)
}

class MainActivity : ComponentActivity() {

    private val viewModel: EdgeAIViewModel by viewModels {
        EdgeAIViewModel.Factory(application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                EdgeLLMStudioApp(viewModel = viewModel)
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        viewModel.onSystemTrimMemory(level)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EdgeLLMStudioApp(viewModel: EdgeAIViewModel) {
    var currentTab by remember { mutableStateOf(StudioDestination.CHAT) }

    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val currentSessionId by viewModel.currentSessionId.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val streamingState by viewModel.streamingState.collectAsStateWithLifecycle()
    val models by viewModel.modelsUiState.collectAsStateWithLifecycle()
    val loadedModelId by viewModel.loadedModelId.collectAsStateWithLifecycle()
    val storageLocation by viewModel.storageLocation.collectAsStateWithLifecycle()
    val activeStoragePath by viewModel.activeStoragePath.collectAsStateWithLifecycle()
    val discoveredLocalFiles by viewModel.discoveredLocalFiles.collectAsStateWithLifecycle()
    val hfSearchState by viewModel.hfSearchState.collectAsStateWithLifecycle()
    val hfToken by viewModel.hfToken.collectAsStateWithLifecycle()
    val selectedPersona by viewModel.selectedPersona.collectAsStateWithLifecycle()
    val customSystemPrompt by viewModel.customSystemPrompt.collectAsStateWithLifecycle()
    val inferenceConfig by viewModel.inferenceConfig.collectAsStateWithLifecycle()
    val memorySnapshot by viewModel.memorySnapshot.collectAsStateWithLifecycle()
    val attachedSmallFile by viewModel.attachedSmallFile.collectAsStateWithLifecycle()
    val attachedImage by viewModel.attachedImage.collectAsStateWithLifecycle()
    val executionEngineMode by viewModel.executionEngineMode.collectAsStateWithLifecycle()
    val openCodeApiKey by viewModel.openCodeApiKey.collectAsStateWithLifecycle()
    val openCodeBaseUrl by viewModel.openCodeBaseUrl.collectAsStateWithLifecycle()
    val selectedOpenCodeTier by viewModel.selectedOpenCodeTier.collectAsStateWithLifecycle()
    val selectedOpenCodeModel by viewModel.selectedOpenCodeModel.collectAsStateWithLifecycle()
    val webSearchEnabled by viewModel.webSearchEnabled.collectAsStateWithLifecycle()
    val imageGenerationEnabled by viewModel.imageGenerationEnabled.collectAsStateWithLifecycle()
    val statusBanner by viewModel.statusBanner.collectAsStateWithLifecycle()

    val loadedModelInfo = remember(models, loadedModelId) {
        models.find { it.spec.id == loadedModelId && it.isLoadedInRam }
    }

    val density = androidx.compose.ui.platform.LocalDensity.current
    val isKeyboardOpen = WindowInsets.ime.getBottom(density) > 0

    if (currentTab != StudioDestination.CHAT) {
        BackHandler {
            currentTab = StudioDestination.CHAT
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_otter_logo),
                            contentDescription = "Logo Otter Local LLM",
                            modifier = Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                        Text(
                            text = "Otter Local LLM",
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        bottomBar = {
            // Al desplegar el teclado en el Chat, ocultamos la barra inferior de pestañas
            // para que el input de mensaje quede exactamente a la altura del teclado.
            if (!(isKeyboardOpen && currentTab == StudioDestination.CHAT)) {
                NavigationBar {
                    StudioDestination.entries.forEach { destination ->
                        val selected = currentTab == destination
                        NavigationBarItem(
                            selected = selected,
                            onClick = { currentTab = destination },
                            icon = {
                                Icon(
                                    imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
                                    contentDescription = destination.label
                                )
                            },
                            label = { Text(destination.label) },
                            modifier = Modifier.testTag("nav_${destination.route}")
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AnimatedVisibility(visible = statusBanner != null) {
                statusBanner?.let { banner ->
                    Surface(
                        color = if (banner.isError) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            if (banner.isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                            }
                            Text(
                                text = banner.message,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (banner.isError) MaterialTheme.colorScheme.onErrorContainer
                                else MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { viewModel.dismissBanner() },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Cerrar aviso",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                when (currentTab) {
                    StudioDestination.CHAT -> {
                        ChatScreen(
                            sessions = sessions,
                            currentSessionId = currentSessionId,
                            messages = messages,
                            streamingState = streamingState,
                            activePersona = selectedPersona,
                            personas = viewModel.personas,
                            inferenceConfig = inferenceConfig,
                            loadedModel = loadedModelInfo,
                            memorySnapshot = memorySnapshot,
                            attachedSmallFile = attachedSmallFile,
                            attachedImage = attachedImage,
                            executionEngineMode = executionEngineMode,
                            openCodeApiKey = openCodeApiKey,
                            openCodeBaseUrl = openCodeBaseUrl,
                            selectedOpenCodeTier = selectedOpenCodeTier,
                            selectedOpenCodeModel = selectedOpenCodeModel,
                            webSearchEnabled = webSearchEnabled,
                            imageGenerationEnabled = imageGenerationEnabled,
                            onSelectSession = viewModel::selectSession,
                            onNewSession = viewModel::createNewChatSession,
                            onDeleteSession = viewModel::deleteChatSession,
                            onClearChat = viewModel::clearCurrentChatHistory,
                            onSendMessage = viewModel::sendUserMessage,
                            onEditAndResendMessage = viewModel::editAndResendMessage,
                            onStopGeneration = viewModel::stopGeneration,
                            onAttachSmallFile = viewModel::attachSmallFileFromUri,
                            onClearAttachedFile = viewModel::clearAttachedSmallFile,
                            onAttachImage = viewModel::attachImageFromUri,
                            onClearAttachedImage = viewModel::clearAttachedImage,
                            onSelectEngineMode = viewModel::setExecutionEngineMode,
                            onUpdateOpenCodeApiKey = viewModel::setOpenCodeApiKey,
                            onUpdateOpenCodeBaseUrl = viewModel::setOpenCodeBaseUrl,
                            onSelectOpenCodeTier = viewModel::selectOpenCodeTier,
                            onSelectOpenCodeModel = viewModel::selectOpenCodeModel,
                            onToggleWebSearch = viewModel::toggleWebSearch,
                            onToggleImageGeneration = viewModel::toggleImageGeneration,
                            onSelectPersonaQuick = viewModel::selectPersona,
                            onOpenModelStore = { currentTab = StudioDestination.MODEL_STORE },
                            onOpenPersonaConfig = { currentTab = StudioDestination.PERSONA }
                        )
                    }

                    StudioDestination.MODEL_STORE -> {
                        ModelStoreScreen(
                            models = models,
                            memorySnapshot = memorySnapshot,
                            storageLocation = storageLocation,
                            activeStoragePath = activeStoragePath,
                            discoveredLocalFiles = discoveredLocalFiles,
                            hfSearchState = hfSearchState,
                            hfToken = hfToken,
                            onUpdateStorageLocation = viewModel::updateStorageLocation,
                            onImportModelUri = viewModel::importModelFromUri,
                            onLoadFromLocalPath = viewModel::loadModelFromLocalPathInput,
                            onScanLocalFiles = viewModel::scanLocalDeviceModels,
                            onPermissionResult = viewModel::notifyPermissionResult,
                            onDownloadModel = viewModel::startModelDownload,
                            onCancelDownload = viewModel::cancelModelDownload,
                            onLoadModel = viewModel::loadModelIntoRam,
                            onUnloadModel = { viewModel.unloadModelFromRam() },
                            onDeleteModel = viewModel::deleteModelFromDisk,
                            onAddCustomModel = viewModel::addCustomModel,
                            onSearchHf = viewModel::searchHuggingFaceModels,
                            onInspectHfRepo = viewModel::inspectHuggingFaceRepo,
                            onCloseRepoInspector = viewModel::closeRepoInspector,
                            onDownloadHfVariant = viewModel::downloadHfVariant,
                            onUpdateHfToken = viewModel::updateHfToken
                        )
                    }

                    StudioDestination.PERSONA -> {
                        SystemPromptScreen(
                            personas = viewModel.personas,
                            selectedPersona = selectedPersona,
                            customSystemPrompt = customSystemPrompt,
                            inferenceConfig = inferenceConfig,
                            onSelectPersona = viewModel::selectPersona,
                            onUpdateCustomPrompt = viewModel::updateCustomSystemPrompt,
                            onUpdateConfig = viewModel::updateInferenceConfig
                        )
                    }

                    StudioDestination.RAM_MONITOR -> {
                        RamMonitorScreen(
                            memorySnapshot = memorySnapshot,
                            loadedModel = loadedModelInfo,
                            onUnloadModel = { viewModel.unloadModelFromRam() },
                            onForceGc = {
                                System.gc()
                                viewModel.refreshModelStoreState()
                            }
                        )
                    }
                }
            }
        }
    }
}
