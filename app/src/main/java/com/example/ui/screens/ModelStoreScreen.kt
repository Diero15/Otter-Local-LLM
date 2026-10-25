package com.example.ui.screens

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.remote.HfGgufFileVariant
import com.example.data.remote.HfRepoSearchResult
import com.example.domain.EdgeModelSpec
import com.example.domain.LocalDiscoveredFile
import com.example.domain.MemorySnapshot
import com.example.domain.ModelCapabilityType
import com.example.domain.ModelRuntimeInfo
import com.example.domain.PromptTemplateFormat
import com.example.domain.StorageLocationOption
import com.example.ui.HfSearchUiState
import java.util.Locale

@Composable
fun ModelCapabilityBadge(
    capability: ModelCapabilityType,
    modifier: Modifier = Modifier
) {
    val containerColor = when (capability) {
        ModelCapabilityType.TEXT_ONLY -> MaterialTheme.colorScheme.secondaryContainer
        ModelCapabilityType.TEXT_AND_CODE -> MaterialTheme.colorScheme.primaryContainer
        ModelCapabilityType.TEXT_AND_IMAGE -> MaterialTheme.colorScheme.tertiaryContainer
    }
    val contentColor = when (capability) {
        ModelCapabilityType.TEXT_ONLY -> MaterialTheme.colorScheme.onSecondaryContainer
        ModelCapabilityType.TEXT_AND_CODE -> MaterialTheme.colorScheme.onPrimaryContainer
        ModelCapabilityType.TEXT_AND_IMAGE -> MaterialTheme.colorScheme.onTertiaryContainer
    }
    val icon = when (capability) {
        ModelCapabilityType.TEXT_ONLY -> Icons.Default.Description
        ModelCapabilityType.TEXT_AND_CODE -> Icons.Default.Code
        ModelCapabilityType.TEXT_AND_IMAGE -> Icons.Default.Image
    }

    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = capability.label,
                modifier = Modifier.size(13.dp)
            )
            Text(
                text = capability.badge,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun ModelStoreScreen(
    models: List<ModelRuntimeInfo>,
    memorySnapshot: MemorySnapshot,
    storageLocation: StorageLocationOption,
    activeStoragePath: String,
    discoveredLocalFiles: List<LocalDiscoveredFile>,
    hfSearchState: HfSearchUiState,
    hfToken: String,
    onUpdateStorageLocation: (StorageLocationOption) -> Unit,
    onImportModelUri: (Uri) -> Unit,
    onLoadFromLocalPath: (String) -> Unit,
    onScanLocalFiles: () -> Unit,
    onPermissionResult: (Boolean) -> Unit,
    onDownloadModel: (EdgeModelSpec) -> Unit,
    onCancelDownload: (EdgeModelSpec) -> Unit,
    onLoadModel: (EdgeModelSpec) -> Unit,
    onUnloadModel: () -> Unit,
    onDeleteModel: (EdgeModelSpec) -> Unit,
    onAddCustomModel: (name: String, url: String, fileName: String, format: PromptTemplateFormat) -> Unit,
    onSearchHf: (String) -> Unit,
    onInspectHfRepo: (HfRepoSearchResult) -> Unit,
    onCloseRepoInspector: () -> Unit,
    onDownloadHfVariant: (HfRepoSearchResult, HfGgufFileVariant) -> Unit,
    onUpdateHfToken: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // 0 = Catálogo GGUF, 1 = Cargar del Teléfono / Descargas, 2 = Buscar en HuggingFace
    var selectedSubTab by remember { mutableIntStateOf(0) }
    // Filtro por tipo de modelo en el Catálogo (null = Todos)
    var selectedCapabilityFilter by remember { mutableStateOf<ModelCapabilityType?>(null) }
    var showCustomDialog by remember { mutableStateOf(false) }
    var showTokenDialog by remember { mutableStateOf(false) }

    val filteredModels = remember(models, selectedCapabilityFilter) {
        if (selectedCapabilityFilter == null) {
            models
        } else {
            models.filter { it.spec.capability == selectedCapabilityFilter }
        }
    }

    // Selector nativo de documentos del teléfono (Carpeta Descargas, Memoria Interna o SD)
    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            onImportModelUri(uri)
        }
    }

    // Lanzador de permisos de lectura/escritura de archivos
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsMap ->
        val granted = permissionsMap.values.any { it } || Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        onPermissionResult(granted)
    }

    LaunchedEffect(selectedSubTab) {
        if (selectedSubTab == 1) {
            onScanLocalFiles()
        } else if (selectedSubTab == 2 && hfSearchState.results.isEmpty() && !hfSearchState.isSearching) {
            onSearchHf(hfSearchState.query)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        // Cabecera con estado de Ruta de Guardado, RAM y Botón Rápido para Importar del Teléfono
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 8.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Model Store • Capacidades IA",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = String.format(
                                Locale.US,
                                "RAM libre: %d MB • 📝 Texto | 💻 Código | 🎨 Imágenes",
                                memorySnapshot.availableDeviceRamMb
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedIconButton(
                            onClick = { showTokenDialog = true },
                            modifier = Modifier.testTag("hf_token_button")
                        ) {
                            Icon(
                                Icons.Default.Key,
                                contentDescription = "Token Hugging Face",
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        FilledTonalButton(
                            onClick = {
                                documentPickerLauncher.launch(arrayOf("*/*"))
                            },
                            modifier = Modifier.testTag("quick_pick_file_button"),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                Icons.Default.UploadFile,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Abrir Archivo")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 3 pestañas: Catálogo | Cargar del Teléfono / Descargas | HuggingFace
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = selectedSubTab == 0,
                        onClick = { selectedSubTab = 0 },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                        modifier = Modifier.testTag("tab_verified_catalog")
                    ) {
                        Text("Catálogo", maxLines = 1)
                    }
                    SegmentedButton(
                        selected = selectedSubTab == 1,
                        onClick = { selectedSubTab = 1 },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                        modifier = Modifier.testTag("tab_local_storage")
                    ) {
                        Text("En Teléfono", maxLines = 1)
                    }
                    SegmentedButton(
                        selected = selectedSubTab == 2,
                        onClick = { selectedSubTab = 2 },
                        shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                        modifier = Modifier.testTag("tab_hf_search")
                    ) {
                        Text("Buscador HF", maxLines = 1)
                    }
                }
            }
        }

        when (selectedSubTab) {
            0 -> {
                // Barra de filtro por capacidad: Todos | 📝 Texto | 💻 Texto + Código | 🎨 Texto + Imágenes
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        FilterChip(
                            selected = selectedCapabilityFilter == null,
                            onClick = { selectedCapabilityFilter = null },
                            label = { Text("Todos (${models.size})", fontSize = 12.sp) },
                            modifier = Modifier.testTag("filter_all_capabilities")
                        )
                    }
                    items(ModelCapabilityType.entries) { cap ->
                        val count = models.count { it.spec.capability == cap }
                        FilterChip(
                            selected = selectedCapabilityFilter == cap,
                            onClick = {
                                selectedCapabilityFilter = if (selectedCapabilityFilter == cap) null else cap
                            },
                            label = { Text("${cap.badge} ($count)", fontSize = 12.sp) },
                            modifier = Modifier.testTag("filter_cap_${cap.name.lowercase()}")
                        )
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filteredModels, key = { it.spec.id }) { item ->
                        ModelStoreCard(
                            info = item,
                            availableRamMb = memorySnapshot.availableDeviceRamMb,
                            onDownload = { onDownloadModel(item.spec) },
                            onCancel = { onCancelDownload(item.spec) },
                            onLoad = { onLoadModel(item.spec) },
                            onUnload = onUnloadModel,
                            onDelete = { onDeleteModel(item.spec) }
                        )
                    }
                }
            }

            1 -> {
                LocalPhoneStorageAndImportSection(
                    storageLocation = storageLocation,
                    activeStoragePath = activeStoragePath,
                    discoveredFiles = discoveredLocalFiles,
                    onSelectStorageLocation = onUpdateStorageLocation,
                    onOpenSystemFilePicker = {
                        documentPickerLauncher.launch(arrayOf("*/*"))
                    },
                    onRequestStoragePermissions = {
                        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                            storagePermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.READ_EXTERNAL_STORAGE,
                                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                                )
                            )
                        } else {
                            onPermissionResult(true)
                        }
                    },
                    onLoadFromPathInput = onLoadFromLocalPath,
                    onRescan = onScanLocalFiles,
                    onAddCustomUrl = { showCustomDialog = true }
                )
            }

            2 -> {
                HuggingFaceLiveSearchSection(
                    state = hfSearchState,
                    onSearch = onSearchHf,
                    onInspectRepo = onInspectHfRepo
                )
            }
        }
    }

    hfSearchState.selectedRepo?.let { selectedRepo ->
        HfRepoVariantsDialog(
            repo = selectedRepo,
            isLoading = hfSearchState.isLoadingFiles,
            variants = hfSearchState.repoVariants,
            errorMessage = hfSearchState.errorMessage,
            onDismiss = onCloseRepoInspector,
            onSelectVariant = { variant ->
                onDownloadHfVariant(selectedRepo, variant)
                selectedSubTab = 0
            }
        )
    }

    if (showCustomDialog) {
        AddCustomModelDialog(
            onDismiss = { showCustomDialog = false },
            onConfirm = { name, url, file, format ->
                onAddCustomModel(name, url, file, format)
                showCustomDialog = false
                selectedSubTab = 0
            }
        )
    }

    if (showTokenDialog) {
        HfTokenDialog(
            currentToken = hfToken,
            onDismiss = { showTokenDialog = false },
            onSave = { token ->
                onUpdateHfToken(token)
                showTokenDialog = false
            }
        )
    }
}

@Composable
private fun LocalPhoneStorageAndImportSection(
    storageLocation: StorageLocationOption,
    activeStoragePath: String,
    discoveredFiles: List<LocalDiscoveredFile>,
    onSelectStorageLocation: (StorageLocationOption) -> Unit,
    onOpenSystemFilePicker: () -> Unit,
    onRequestStoragePermissions: () -> Unit,
    onLoadFromPathInput: (String) -> Unit,
    onRescan: () -> Unit,
    onAddCustomUrl: () -> Unit
) {
    var localPathInput by remember { mutableStateOf("/storage/emulated/0/Download/") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Tarjeta para Cargar Modelos Descargados en el Teléfono o Carpeta Descargas
        item {
            ElevatedCard(
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "1. Cargar Modelo desde el Teléfono o Descargas",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Si ya descargaste un archivo .gguf, .bin o .task en tu teléfono (ej. en la carpeta Download), selecciónalo o escribe su ruta abajo. Se detectará automáticamente si es de Texto, Código o Texto + Imágenes:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onOpenSystemFilePicker,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("browse_phone_files_button")
                        ) {
                            Icon(
                                Icons.Default.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Explorar Teléfono / Descargas")
                        }

                        OutlinedButton(
                            onClick = onRequestStoragePermissions,
                            modifier = Modifier.testTag("grant_storage_permissions_button")
                        ) {
                            Icon(
                                Icons.Default.LockOpen,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Permisos")
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    // Input manual de ruta local o nombre de archivo en Download
                    OutlinedTextField(
                        value = localPathInput,
                        onValueChange = { localPathInput = it },
                        label = { Text("Ruta local o nombre de archivo en Download") },
                        placeholder = { Text("/storage/emulated/0/Download/modelo.gguf") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("local_path_input")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = onAddCustomUrl) {
                            Icon(
                                Icons.Default.AddLink,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Descargar desde URL")
                        }

                        Button(
                            onClick = { onLoadFromPathInput(localPathInput) },
                            enabled = localPathInput.isNotBlank(),
                            modifier = Modifier.testTag("load_local_path_button")
                        ) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Cargar desde Ruta")
                        }
                    }
                }
            }
        }

        // 2. Tarjeta para elegir dónde se guardan los modelos (Memoria Interna vs Externa vs Descargas)
        item {
            ElevatedCard(
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Save,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "2. Ruta de Guardado de Modelos",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = "Ruta activa: $activeStoragePath",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )

                    StorageLocationOption.entries.forEach { option ->
                        val selected = option == storageLocation
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectStorageLocation(option) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = { onSelectStorageLocation(option) }
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column {
                                Text(
                                    text = option.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = option.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. Archivos encontrados automáticamente en la carpeta Download y Memoria Interna
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Archivos detectados en el dispositivo (${discoveredFiles.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onRescan) {
                    Icon(Icons.Default.Refresh, contentDescription = "Actualizar escaneo")
                }
            }
        }

        if (discoveredFiles.isEmpty()) {
            item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "No se detectaron archivos .gguf o .bin adicionales aún. Usa 'Explorar Teléfono / Descargas' arriba para seleccionar cualquier archivo o descarga uno del Catálogo.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }
        } else {
            items(discoveredFiles, key = { it.absolutePath }) { fileItem ->
                OutlinedCard(
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = fileItem.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                ModelCapabilityBadge(capability = fileItem.capability)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${fileItem.sourceFolder} • ${fileItem.sizeMb} MB",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = fileItem.absolutePath,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { onLoadFromPathInput(fileItem.absolutePath) }
                        ) {
                            Text("Usar")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HuggingFaceLiveSearchSection(
    state: HfSearchUiState,
    onSearch: (String) -> Unit,
    onInspectRepo: (HfRepoSearchResult) -> Unit
) {
    var searchInput by remember(state.query) { mutableStateOf(state.query) }
    val quickTags = listOf(
        "Qwen2.5",
        "SmolVLM",
        "Qwen2.5-Coder",
        "Llama-3.2",
        "SmolLM2",
        "Moondream",
        "gemma-2",
        "DeepSeek"
    )

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = searchInput,
                onValueChange = { searchInput = it },
                modifier = Modifier
                    .weight(1f)
                    .testTag("hf_search_input"),
                placeholder = { Text("Buscar GGUF (Texto, Coder, Vision/Imágenes)...") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch(searchInput) })
            )
            Button(
                onClick = { onSearch(searchInput) },
                modifier = Modifier
                    .height(54.dp)
                    .testTag("hf_search_submit_button"),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.Search, contentDescription = "Buscar")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(quickTags) { tag ->
                FilterChip(
                    selected = searchInput.equals(tag, ignoreCase = true),
                    onClick = {
                        searchInput = tag
                        onSearch(tag)
                    },
                    label = { Text(tag) }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (state.isSearching) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Buscando modelos GGUF en Hugging Face...")
                }
            }
        } else if (state.errorMessage != null && state.results.isEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
            ) {
                Text(
                    text = state.errorMessage,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(state.results, key = { it.repoId }) { repo ->
                    ElevatedCard(
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onInspectRepo(repo) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = repo.modelName,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    ModelCapabilityBadge(capability = repo.capability)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Autor: ${repo.author} • ⬇ ${repo.downloads} • ♥ ${repo.likes}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (repo.isGated) "Requiere Token HF (Gated) • ${repo.capability.label}"
                                    else "Público Directo • ${repo.capability.detailDescription}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (repo.isGated) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.secondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            FilledTonalButton(onClick = { onInspectRepo(repo) }) {
                                Icon(
                                    Icons.Default.FolderOpen,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Ver .gguf")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HfRepoVariantsDialog(
    repo: HfRepoSearchResult,
    isLoading: Boolean,
    variants: List<HfGgufFileVariant>,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSelectVariant: (HfGgufFileVariant) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = repo.modelName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    ModelCapabilityBadge(capability = repo.capability)
                }
                Text(
                    text = "Selecciona una cuantización .gguf para guardar y cargar",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 380.dp)
            ) {
                if (isLoading) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("Leyendo archivos .gguf del repositorio...")
                    }
                } else if (variants.isEmpty()) {
                    Text(
                        text = errorMessage ?: "No se hallaron archivos .gguf en este repositorio.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(variants, key = { it.fileName }) { variant ->
                            OutlinedCard(
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = variant.quantization,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = variant.fileName,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "~${variant.estimatedSizeMb} MB • ${repo.capability.badge}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    Button(
                                        onClick = { onSelectVariant(variant) },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.CloudDownload,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Guardar")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cerrar")
            }
        }
    )
}

@Composable
private fun ModelStoreCard(
    info: ModelRuntimeInfo,
    availableRamMb: Long,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onLoad: () -> Unit,
    onUnload: () -> Unit,
    onDelete: () -> Unit
) {
    val spec = info.spec
    val hasEnoughRam = availableRamMb >= (spec.minRamMb * 0.65)

    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("model_card_${spec.id}")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = spec.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (info.isLoadedInRam) {
                            SuggestionChip(
                                onClick = {},
                                label = { Text("EN RAM") },
                                icon = {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.secondary
                                    )
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Etiqueta destacada de Capacidad del Modelo: Texto | Texto + Código | Texto + Imágenes
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ModelCapabilityBadge(capability = spec.capability)
                        Text(
                            text = "${spec.provider} • ${spec.quantization}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            info.ggufDetails?.let { details ->
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "Motor activo: $details",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            if (info.isDownloaded && info.resolvedPath.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Guardado en: ${info.resolvedPath}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = spec.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Resumen de funciones habilitadas por este modelo en el chat
            val enabledFeaturesText = when (spec.capability) {
                ModelCapabilityType.TEXT_ONLY -> "Funciones en Chat: Respuestas en Texto + Búsqueda Web opcional"
                ModelCapabilityType.TEXT_AND_CODE -> "Funciones en Chat: Respuestas en Texto + Visor ▶ Play (HTML / Python) + Búsqueda Web"
                ModelCapabilityType.TEXT_AND_IMAGE -> "Funciones en Chat: Habilita 'Creación de imágenes' 🎨 + Respuestas en Texto + Código"
            }
            Text(
                text = enabledFeaturesText,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (spec.capability.supportsImages) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val diskText = if (info.isDownloaded && info.sizeOnDiskBytes > 0L) {
                    val mb = (info.sizeOnDiskBytes / (1024L * 1024L)).coerceAtLeast(1L)
                    "En disco: $mb MB"
                } else {
                    "Tamaño: ~${spec.estimatedSizeMb} MB"
                }

                AssistChip(
                    onClick = {},
                    label = { Text(diskText, style = MaterialTheme.typography.labelSmall) }
                )

                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            "RAM Rec: ${spec.minRamMb} MB",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (hasEnoughRam) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.error
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Memory,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                )
            }

            if (info.isDownloading) {
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { info.downloadProgress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val speedStr = if (info.downloadSpeedMbps > 0f) {
                        String.format(Locale.US, " • %.1f MB/s", info.downloadSpeedMbps)
                    } else ""
                    Text(
                        text = "Guardando: ${info.downloadedMb} MB (${info.downloadProgress}%)$speedStr",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    TextButton(onClick = onCancel) {
                        Text("Cancelar")
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!info.isDownloaded) {
                        Button(
                            onClick = onDownload,
                            modifier = Modifier.testTag("download_btn_${spec.id}")
                        ) {
                            Icon(
                                Icons.Default.CloudDownload,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Descargar y Guardar (~${spec.estimatedSizeMb} MB)")
                        }
                    } else {
                        OutlinedIconButton(
                            onClick = onDelete,
                            modifier = Modifier.testTag("delete_btn_${spec.id}")
                        ) {
                            Icon(
                                Icons.Default.DeleteOutline,
                                contentDescription = "Eliminar archivo",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))

                        if (info.isLoadedInRam) {
                            FilledTonalButton(
                                onClick = onUnload,
                                modifier = Modifier.testTag("unload_btn_${spec.id}")
                            ) {
                                Icon(
                                    Icons.Default.StopCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Liberar RAM")
                            }
                        } else {
                            Button(
                                onClick = onLoad,
                                modifier = Modifier.testTag("load_btn_${spec.id}")
                            ) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Cargar en Motor")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddCustomModelDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, url: String, fileName: String, format: PromptTemplateFormat) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("https://huggingface.co/") }
    var fileName by remember { mutableStateOf("custom-model.gguf") }
    var selectedFormat by remember { mutableStateOf(PromptTemplateFormat.LLAMA3) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Descargar Modelo desde URL (.gguf / .bin)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nombre del modelo") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        val candidate = it.substringBefore("?").substringAfterLast("/")
                        if (candidate.endsWith(".gguf", true) || candidate.endsWith(".bin", true)) {
                            fileName = candidate
                        }
                    },
                    label = { Text("URL directa del archivo") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    label = { Text("Nombre con el que se guardará") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Plantilla de Conversación:",
                    style = MaterialTheme.typography.labelMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PromptTemplateFormat.entries.forEach { format ->
                        FilterChip(
                            selected = selectedFormat == format,
                            onClick = { selectedFormat = format },
                            label = { Text(format.name) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name, url, fileName, selectedFormat) },
                enabled = url.startsWith("http")
            ) {
                Text("Descargar y Guardar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

@Composable
private fun HfTokenDialog(
    currentToken: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var tokenText by remember { mutableStateOf(currentToken) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Token de Hugging Face (Opcional)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Los modelos GGUF del catálogo son públicos y NO requieren token. Solo añade un token (hf_...) si deseas bajar modelos de repositorios privados.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = tokenText,
                    onValueChange = { tokenText = it },
                    label = { Text("Bearer Token (hf_...)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(tokenText) }) {
                Text("Guardar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cerrar")
            }
        }
    )
}
