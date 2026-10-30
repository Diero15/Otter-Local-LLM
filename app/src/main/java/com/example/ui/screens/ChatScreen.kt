package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.ChatMessageEntity
import com.example.data.local.ChatSessionEntity
import com.example.domain.EdgeModelCatalog
import com.example.domain.InferenceConfig
import com.example.domain.MemorySnapshot
import com.example.domain.ModelRuntimeInfo
import com.example.domain.SystemPersona
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.domain.AttachedImageData
import com.example.domain.ExecutionEngineMode
import com.example.domain.OpenCodeModelSpec
import com.example.domain.OpenCodeTier
import com.example.ui.AttachedSmallFile
import com.example.ui.StreamingUiState
import com.example.ui.components.CodeSandboxDialog
import com.example.ui.components.GeneratedImageCard
import com.example.ui.components.isExecutableCodeLanguage
import com.example.ui.components.isHtmlCodeFormat
import com.example.ui.components.isPythonCodeFormat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

sealed interface MessageContentSegment {
    data class PlainText(val text: String) : MessageContentSegment
    data class CodeBlock(val language: String, val code: String) : MessageContentSegment
    data class GeneratedImage(
        val prompt: String,
        val directUrl: String? = null,
        val svgMarkup: String? = null
    ) : MessageContentSegment
}

/**
 * Analiza el contenido del mensaje para separar:
 * 1) Etiquetas de generación de imágenes ([GENERAR_IMAGEN: ...], ![alt](url) o bloques <svg>)
 * 2) Bloques de código (```lenguaje ... ```) con sangría estructurada de 4 espacios
 * 3) Texto plano
 */
fun parseMessageSegments(rawContent: String): List<MessageContentSegment> {
    val segments = mutableListOf<MessageContentSegment>()

    // 1. Extraer etiqueta [GENERAR_IMAGEN: ...] si está presente al inicio o dentro del mensaje
    val imgTagRegex = Regex("""\[GENERAR_IMAGEN:\s*([^\]]+)\]""", RegexOption.IGNORE_CASE)
    var remainingContent = rawContent

    imgTagRegex.findAll(rawContent).forEach { match ->
        val imgPrompt = match.groupValues[1].trim()
        if (imgPrompt.isNotBlank()) {
            segments.add(MessageContentSegment.GeneratedImage(prompt = imgPrompt))
        }
    }
    remainingContent = remainingContent.replace(imgTagRegex, "").trim()

    // 2. Extraer imágenes Markdown ![alt](url) incluyendo data:image/base64 y content://
    val mdImgRegex = Regex("""!\[([^\]]*)\]\((https?://[^\)]+|data:image/[^\)]+|content://[^\)]+)\)""")
    mdImgRegex.findAll(remainingContent).forEach { match ->
        val alt = match.groupValues[1].ifBlank { "Foto adjunta" }
        val url = match.groupValues[2].trim()
        segments.add(MessageContentSegment.GeneratedImage(prompt = alt, directUrl = url))
    }
    remainingContent = remainingContent.replace(mdImgRegex, "").trim()

    if (!remainingContent.contains("```")) {
        // Si la respuesta contiene documento HTML directo sin bloque ```, extraerlo como bloque HTML ejecutable
        val rawHtmlRegex = Regex("""(?is)(<!DOCTYPE\s+html.*?</html\s*>|<html\b.*?</html\s*>)""")
        val htmlMatch = rawHtmlRegex.find(remainingContent)
        if (htmlMatch != null) {
            val beforeHtml = remainingContent.substring(0, htmlMatch.range.first).trim()
            val htmlCode = formatCodeWithProperIndentation(htmlMatch.value.trim(), "html")
            val afterHtml = remainingContent.substring(htmlMatch.range.last + 1).trim()
            if (beforeHtml.isNotBlank()) {
                segments.add(MessageContentSegment.PlainText(beforeHtml))
            }
            if (htmlCode.isNotBlank()) {
                segments.add(MessageContentSegment.CodeBlock(language = "html", code = htmlCode))
            }
            if (afterHtml.isNotBlank()) {
                segments.add(MessageContentSegment.PlainText(afterHtml))
            }
            return segments
        }

        if (remainingContent.isNotBlank()) {
            segments.add(MessageContentSegment.PlainText(remainingContent))
        }
        return if (segments.isEmpty()) listOf(MessageContentSegment.PlainText(rawContent)) else segments
    }

    val parts = remainingContent.split("```")
    for ((index, part) in parts.withIndex()) {
        if (index % 2 == 0) {
            if (part.isNotBlank()) {
                segments.add(MessageContentSegment.PlainText(part.trim('\n')))
            }
        } else {
            val lines = part.lines()
            val firstLine = lines.firstOrNull()?.trim().orEmpty()
            val hasLangHeader = firstLine.isNotEmpty() &&
                !firstLine.contains(" ") &&
                !firstLine.contains("(") &&
                !firstLine.contains("{") &&
                !firstLine.startsWith("<") &&
                lines.size > 1

            val rawCodeBody = if (hasLangHeader) {
                lines.drop(1).joinToString("\n")
            } else {
                part
            }

            val language = if (hasLangHeader) {
                val rawHeader = firstLine.lowercase().trim()
                when {
                    rawHeader == "html" || rawHeader == "htm" || rawHeader == ".html" || rawHeader == ".htm" ||
                        rawHeader.endsWith(".html") || rawHeader.endsWith(".htm") -> "html"
                    rawHeader == "py" || rawHeader == "python" || rawHeader == "python3" || rawHeader == ".py" ||
                        rawHeader.endsWith(".py") -> "python"
                    else -> rawHeader
                }
            } else {
                when {
                    isHtmlCodeFormat("", rawCodeBody) -> "html"
                    isPythonCodeFormat("", rawCodeBody) -> "python"
                    else -> "text"
                }
            }

            val formattedCode = formatCodeWithProperIndentation(rawCodeBody.trim('\n'), language)
            if (formattedCode.isNotBlank()) {
                // Si el bloque es un SVG, mostrar también su vista previa como imagen generada además del código
                if (language == "svg" || formattedCode.trimStart().startsWith("<svg", ignoreCase = true)) {
                    segments.add(
                        MessageContentSegment.GeneratedImage(
                            prompt = "Gráfico Vectorial SVG",
                            svgMarkup = formattedCode
                        )
                    )
                }
                segments.add(MessageContentSegment.CodeBlock(language = language, code = formattedCode))
            }
        }
    }

    return if (segments.isEmpty()) {
        listOf(MessageContentSegment.PlainText(rawContent))
    } else {
        segments
    }
}

/**
 * Asegura que el código fuente mantenga su estructura con sangrías limpias de 4 espacios ("    ").
 */
fun formatCodeWithProperIndentation(rawCode: String, language: String): String {
    val normalizedLines = rawCode
        .replace("\r\n", "\n")
        .replace("\t", "    ")
        .lines()

    if (normalizedLines.isEmpty()) return rawCode

    val nonEmptyLines = normalizedLines.filter { it.isNotBlank() }
    val hasExistingIndentation = nonEmptyLines.count { it.startsWith("  ") } >= 1

    if (hasExistingIndentation) {
        val minIndent = nonEmptyLines
            .drop(1)
            .map { line -> line.takeWhile { it == ' ' }.length }
            .filter { it > 0 }
            .minOrNull() ?: 4

        return normalizedLines.joinToString("\n") { line ->
            if (line.isBlank()) {
                ""
            } else {
                val leadingSpaces = line.takeWhile { it == ' ' }.length
                val content = line.trimStart()
                val adjustedSpaces = if (minIndent == 2) leadingSpaces * 2 else leadingSpaces
                " ".repeat(adjustedSpaces) + content
            }
        }
    }

    val isPythonLike = language in setOf("py", "python", "ruby", "yaml")
    val result = mutableListOf<String>()
    var indentLevel = 0

    for (rawLine in normalizedLines) {
        val trimmed = rawLine.trim()
        if (trimmed.isEmpty()) {
            result.add("")
            continue
        }

        if (isPythonLike) {
            if (trimmed.startsWith("elif ") || trimmed.startsWith("else:") ||
                trimmed.startsWith("except") || trimmed.startsWith("finally:")
            ) {
                indentLevel = (indentLevel - 1).coerceAtLeast(0)
            }

            result.add("    ".repeat(indentLevel) + trimmed)

            if (trimmed.endsWith(":")) {
                indentLevel++
            } else if (trimmed.startsWith("return") || trimmed == "pass" || trimmed == "break" || trimmed == "continue") {
                indentLevel = (indentLevel - 1).coerceAtLeast(0)
            }
        } else {
            if (trimmed.startsWith("}") || trimmed.startsWith("]") || trimmed.startsWith(")")) {
                indentLevel = (indentLevel - 1).coerceAtLeast(0)
            }

            result.add("    ".repeat(indentLevel) + trimmed)

            val openBraces = trimmed.count { it == '{' }
            val closeBraces = trimmed.count { it == '}' }
            val netChange = openBraces - closeBraces

            if (trimmed.startsWith("}") && trimmed.endsWith("{")) {
                indentLevel += 1
            } else if (netChange > 0) {
                indentLevel += netChange
            } else if (netChange < 0 && !trimmed.startsWith("}")) {
                indentLevel = (indentLevel + netChange).coerceAtLeast(0)
            }
        }
    }

    return result.joinToString("\n")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenCodeConfigDialog(
    currentEngineMode: ExecutionEngineMode,
    currentTier: OpenCodeTier,
    currentModel: OpenCodeModelSpec,
    apiKey: String,
    baseUrl: String,
    onSelectEngineMode: (ExecutionEngineMode) -> Unit,
    onSelectTier: (OpenCodeTier) -> Unit,
    onSelectModel: (OpenCodeModelSpec) -> Unit,
    onSaveApiKey: (String) -> Unit,
    onSaveBaseUrl: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var tempKey by remember(apiKey) { mutableStateOf(apiKey) }
    var tempUrl by remember(baseUrl) { mutableStateOf(baseUrl) }
    var keyVisible by remember { mutableStateOf(false) }
    var selectedTabTier by remember(currentTier) { mutableStateOf(currentTier) }
    var selectedEngine by remember(currentEngineMode) { mutableStateOf(currentEngineMode) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.88f),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Cabecera del diálogo
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            Icons.Default.Cloud,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column {
                            Text(
                                text = "Motor IA & OpenCode Cloud",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Inferencia Local en RAM o Nube con API Key",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Cerrar")
                    }
                }

                // Contenido desplazable
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // 1. Selector de Motor: Local GGUF vs OpenCode Cloud
                    Text(
                        text = "1. Modo de Inferencia Activo",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            onClick = { selectedEngine = ExecutionEngineMode.LOCAL_GGUF },
                            shape = RoundedCornerShape(12.dp),
                            color = if (selectedEngine == ExecutionEngineMode.LOCAL_GGUF)
                                MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                            border = if (selectedEngine == ExecutionEngineMode.LOCAL_GGUF)
                                androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                            else null,
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "📱 Local (GGUF)",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "100% privado en RAM mediante llama.cpp. Sin Internet ni API Key.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Surface(
                            onClick = { selectedEngine = ExecutionEngineMode.OPENCODE_CLOUD },
                            shape = RoundedCornerShape(12.dp),
                            color = if (selectedEngine == ExecutionEngineMode.OPENCODE_CLOUD)
                                MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                            border = if (selectedEngine == ExecutionEngineMode.OPENCODE_CLOUD)
                                androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                            else null,
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "☁️ OpenCode Cloud",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Visión multimodal, OCR, modelos 32B/70B y agentes en la nube.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    if (selectedEngine == ExecutionEngineMode.OPENCODE_CLOUD) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                        // 2. Selector de Niveles OpenCode
                        Text(
                            text = "2. Nivel de Suscripción OpenCode",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OpenCodeTier.entries.forEach { tier ->
                                val isSelected = selectedTabTier == tier
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { selectedTabTier = tier },
                                    label = { Text(tier.badge, fontSize = 12.sp) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        Text(
                            text = selectedTabTier.shortDesc,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // 3. Catálogo de Modelos del nivel
                        Text(
                            text = "3. Seleccionar Modelo (${selectedTabTier.label})",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        val tierModels = remember(selectedTabTier) {
                            EdgeModelCatalog.openCodeModels.filter { it.tier == selectedTabTier }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            tierModels.forEach { m ->
                                val isChosen = currentModel.id == m.id
                                Surface(
                                    onClick = { onSelectModel(m) },
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isChosen) MaterialTheme.colorScheme.secondaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                                    border = if (isChosen) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.secondary) else null,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        RadioButton(
                                            selected = isChosen,
                                            onClick = { onSelectModel(m) }
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Text(
                                                    text = m.name,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                if (m.supportsVision) {
                                                    Surface(
                                                        color = MaterialTheme.colorScheme.tertiaryContainer,
                                                        shape = RoundedCornerShape(4.dp)
                                                    ) {
                                                        Text(
                                                            text = "📷 Visión / OCR",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                                            fontSize = 9.sp
                                                        )
                                                    }
                                                }
                                                if (m.supportsCode) {
                                                    Surface(
                                                        color = MaterialTheme.colorScheme.primaryContainer,
                                                        shape = RoundedCornerShape(4.dp)
                                                    ) {
                                                        Text(
                                                            text = "💻 Código",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                                            fontSize = 9.sp
                                                        )
                                                    }
                                                }
                                            }
                                            Text(
                                                text = "${m.provider} • ${m.description}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                        // 4. API Key de OpenCode
                        Text(
                            text = "4. Clave API de OpenCode",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        OutlinedTextField(
                            value = tempKey,
                            onValueChange = { tempKey = it },
                            label = { Text("OpenCode / OpenRouter API Key") },
                            placeholder = { Text("sk-or-v1-...") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { keyVisible = !keyVisible }) {
                                    Icon(
                                        imageVector = if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = "Mostrar u ocultar clave"
                                    )
                                }
                            }
                        )

                        Text(
                            text = "💡 Para los modelos con etiqueta 'Gratis (Free)' puedes probarlos sin costo. Para la suscripción Go o Premium ingresa tu clave API.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // 5. Endpoint Base URL
                        OutlinedTextField(
                            value = tempUrl,
                            onValueChange = { tempUrl = it },
                            label = { Text("Endpoint Base URL (Opcional)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                }

                // Barra inferior de acciones
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text("Cancelar")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                onSelectEngineMode(selectedEngine)
                                onSelectTier(selectedTabTier)
                                onSaveApiKey(tempKey)
                                onSaveBaseUrl(tempUrl)
                                onDismiss()
                            }
                        ) {
                            Text("Guardar y Aplicar")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChatScreen(
    sessions: List<ChatSessionEntity>,
    currentSessionId: Long?,
    messages: List<ChatMessageEntity>,
    streamingState: StreamingUiState,
    activePersona: SystemPersona,
    personas: List<SystemPersona> = EdgeModelCatalog.defaultPersonas,
    inferenceConfig: InferenceConfig,
    loadedModel: ModelRuntimeInfo?,
    memorySnapshot: MemorySnapshot,
    attachedSmallFile: AttachedSmallFile? = null,
    attachedImage: AttachedImageData? = null,
    executionEngineMode: ExecutionEngineMode = ExecutionEngineMode.LOCAL_GGUF,
    openCodeApiKey: String = "",
    openCodeBaseUrl: String = "https://openrouter.ai/api/v1/chat/completions",
    selectedOpenCodeTier: OpenCodeTier = OpenCodeTier.FREE,
    selectedOpenCodeModel: OpenCodeModelSpec = EdgeModelCatalog.openCodeModels.first(),
    webSearchEnabled: Boolean = false,
    imageGenerationEnabled: Boolean = false,
    onSelectSession: (ChatSessionEntity) -> Unit,
    onNewSession: () -> Unit,
    onDeleteSession: (Long) -> Unit,
    onClearChat: () -> Unit,
    onSendMessage: (String) -> Unit,
    onEditAndResendMessage: (ChatMessageEntity, String) -> Unit = { _, _ -> },
    onStopGeneration: () -> Unit = {},
    onAttachSmallFile: (Uri) -> Unit = {},
    onClearAttachedFile: () -> Unit = {},
    onAttachImage: (Uri) -> Unit = {},
    onClearAttachedImage: () -> Unit = {},
    onSelectEngineMode: (ExecutionEngineMode) -> Unit = {},
    onUpdateOpenCodeApiKey: (String) -> Unit = {},
    onUpdateOpenCodeBaseUrl: (String) -> Unit = {},
    onSelectOpenCodeTier: (OpenCodeTier) -> Unit = {},
    onSelectOpenCodeModel: (OpenCodeModelSpec) -> Unit = {},
    onToggleWebSearch: (Boolean) -> Unit = {},
    onToggleImageGeneration: (Boolean) -> Unit = {},
    onSelectPersonaQuick: (SystemPersona) -> Unit = {},
    onOpenModelStore: () -> Unit,
    onOpenPersonaConfig: () -> Unit,
    modifier: Modifier = Modifier
) {
    var prompt by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Visor de ejecución de código (HTML Sandbox / Python runner)
    var sandboxRequest by remember { mutableStateOf<Pair<String, String>?>(null) }

    // Diálogo de configuración de OpenCode Cloud y Motor
    var showOpenCodeConfigDialog by remember { mutableStateOf(false) }

    // Menú desplegable de opciones en el botón de Prompt
    var showPromptOptionsMenu by remember { mutableStateOf(false) }

    // Determinar si el modelo cargado actualmente soporta visión/imágenes
    val modelSupportsImages = if (executionEngineMode == ExecutionEngineMode.OPENCODE_CLOUD) {
        selectedOpenCodeModel.supportsVision
    } else {
        loadedModel?.spec?.capability?.supportsImages == true
    }

    // Detectar apertura del teclado en pantalla
    val density = androidx.compose.ui.platform.LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)

    // Selector de archivos pequeños (.txt, .md, código)
    val smallFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            onAttachSmallFile(uri)
        }
    }

    // Selector de Fotos / Galería para análisis visual y OCR Multimodal (Zero-permission Android Photo Picker)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            onAttachImage(uri)
        }
    }

    if (showOpenCodeConfigDialog) {
        OpenCodeConfigDialog(
            currentEngineMode = executionEngineMode,
            currentTier = selectedOpenCodeTier,
            currentModel = selectedOpenCodeModel,
            apiKey = openCodeApiKey,
            baseUrl = openCodeBaseUrl,
            onSelectEngineMode = onSelectEngineMode,
            onSelectTier = onSelectOpenCodeTier,
            onSelectModel = onSelectOpenCodeModel,
            onSaveApiKey = onUpdateOpenCodeApiKey,
            onSaveBaseUrl = onUpdateOpenCodeBaseUrl,
            onDismiss = { showOpenCodeConfigDialog = false }
        )
    }

    // Auto-scroll al último mensaje cuando llega un nuevo mensaje, durante el streaming o al desplegarse el teclado
    LaunchedEffect(messages.size, streamingState.streamingText, imeBottomPx) {
        val totalItems = messages.size + if (streamingState.isGenerating) 1 else 0
        if (totalItems > 0) {
            listState.animateScrollToItem(totalItems - 1)
        }
    }

    sandboxRequest?.let { (lang, code) ->
        CodeSandboxDialog(
            initialLanguage = lang,
            initialCode = code,
            onDismiss = { sandboxRequest = null }
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Barra superior: Selector horizontal de Sesiones (Room Database)
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LazyRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(sessions, key = { it.id }) { session ->
                        val isSelected = session.id == currentSessionId
                        FilterChip(
                            selected = isSelected,
                            onClick = { onSelectSession(session) },
                            label = {
                                Text(
                                    text = session.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 12.sp
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.Forum,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                            },
                            trailingIcon = if (sessions.size > 1 && isSelected) {
                                {
                                    Icon(
                                        Icons.Outlined.Close,
                                        contentDescription = "Eliminar sesión",
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clickable { onDeleteSession(session.id) }
                                    )
                                }
                            } else null
                        )
                    }
                }

                IconButton(
                    onClick = onNewSession,
                    modifier = Modifier.testTag("new_session_button")
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Nueva sesión de chat"
                    )
                }

                IconButton(
                    onClick = onClearChat,
                    modifier = Modifier.testTag("clear_chat_button")
                ) {
                    Icon(
                        Icons.Default.DeleteSweep,
                        contentDescription = "Limpiar mensajes"
                    )
                }
            }
        }

        // Lista de Mensajes Persistidos + Burbuja de Streaming en vivo
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                ChatBubbleItem(
                    message = msg,
                    onRunCode = { lang, code -> sandboxRequest = lang to code },
                    onEditAndResend = { updatedText ->
                        onEditAndResendMessage(msg, updatedText)
                    }
                )
            }

            if (streamingState.isGenerating) {
                item(key = "streaming_bubble") {
                    StreamingChatBubble(
                        state = streamingState,
                        onRunCode = { lang, code -> sandboxRequest = lang to code }
                    )
                }
            }
        }

        // Panel inferior unificado encima del input (se posiciona automáticamente a la altura del teclado):
        // 1) Pastilla de archivo adjunto (.txt / .md / código) si hay uno cargado
        // 2) Controles: Modelo en RAM (con su capacidad) + Carga de archivo pequeño + Botón de Prompt + RAM libre
        // 3) Input de texto (dice "Creación de imágenes" cuando esa opción está activa) + Botón Enviar / Stop
        Surface(
            tonalElevation = 8.dp,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                // 1. Pastilla visual de Foto Adjunta para Análisis Visual / OCR
                if (attachedImage != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                AsyncImage(
                                    model = attachedImage.uri,
                                    contentDescription = "Foto adjunta para análisis visual u OCR",
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop
                                )
                                Column {
                                    Text(
                                        text = attachedImage.fileName,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "📷 Foto lista para análisis visual / OCR (${(attachedImage.sizeBytes / 1024L).coerceAtLeast(1L)} KB)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                                    )
                                }
                            }

                            IconButton(
                                onClick = onClearAttachedImage,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Quitar foto adjunta",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }
                    }
                }

                // 2. Pastilla visual si el usuario adjuntó un archivo pequeño (.txt, .md, código)
                if (attachedSmallFile != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    Icons.Outlined.Description,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Column {
                                    Text(
                                        text = attachedSmallFile.fileName,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "Archivo .${attachedSmallFile.extension} (${(attachedSmallFile.sizeBytes / 1024L).coerceAtLeast(1L)} KB)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                                    )
                                }
                            }

                            IconButton(
                                onClick = onClearAttachedFile,
                                modifier = Modifier.size(26.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Quitar archivo adjunto",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }

                // 3. Fila de controles ubicada encima del input de mensaje
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Chip de Modo / Modelo Activo (Local GGUF vs OpenCode Cloud)
                    AssistChip(
                        onClick = {
                            if (executionEngineMode == ExecutionEngineMode.OPENCODE_CLOUD) {
                                showOpenCodeConfigDialog = true
                            } else {
                                onOpenModelStore()
                            }
                        },
                        label = {
                            val chipText = if (executionEngineMode == ExecutionEngineMode.OPENCODE_CLOUD) {
                                "☁️ ${selectedOpenCodeModel.tier.badge} • ${selectedOpenCodeModel.name}"
                            } else {
                                loadedModel?.let {
                                    "📱 ${it.spec.capability.badge} • ${it.spec.name}"
                                } ?: "Cargar Modelo RAM"
                            }
                            Text(
                                text = chipText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 11.sp
                            )
                        },
                        leadingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (executionEngineMode == ExecutionEngineMode.OPENCODE_CLOUD) Color(0xFF7AA2F7)
                                        else if (loadedModel != null) MaterialTheme.colorScheme.secondary
                                        else MaterialTheme.colorScheme.error
                                    )
                            )
                        },
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .height(32.dp)
                            .testTag("active_engine_chip")
                    )

                    // Botón FOTO / VISIÓN / OCR (Android Photo Picker directo)
                    AssistChip(
                        onClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(PickVisualMedia.ImageOnly)
                            )
                        },
                        label = {
                            Text(
                                text = if (attachedImage != null) "1 Foto" else "Foto/OCR",
                                fontSize = 11.sp,
                                maxLines = 1
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.AddPhotoAlternate,
                                contentDescription = "Adjuntar foto para análisis visual u OCR",
                                modifier = Modifier.size(14.dp),
                                tint = if (attachedImage != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        modifier = Modifier
                            .height(32.dp)
                            .testTag("attach_photo_chip")
                    )

                    // Botón para cargar archivos de texto o código
                    AssistChip(
                        onClick = {
                            smallFilePickerLauncher.launch(arrayOf("*/*"))
                        },
                        label = {
                            Text(
                                text = if (attachedSmallFile != null) "1 Archivo" else "Código",
                                fontSize = 11.sp,
                                maxLines = 1
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.AttachFile,
                                contentDescription = "Cargar archivo pequeño de texto o código",
                                modifier = Modifier.size(14.dp)
                            )
                        },
                        modifier = Modifier
                            .height(32.dp)
                            .testTag("attach_small_file_chip")
                    )

                    // Botón de Opciones del Motor IA / OpenCode / System Prompt
                    Box {
                        AssistChip(
                            onClick = { showPromptOptionsMenu = true },
                            label = {
                                val labelText = buildString {
                                    if (executionEngineMode == ExecutionEngineMode.OPENCODE_CLOUD) {
                                        append("☁️ Nube")
                                    } else {
                                        append(activePersona.badge)
                                    }
                                    if (webSearchEnabled) append(" • 🌐")
                                }
                                Text(
                                    text = labelText,
                                    fontSize = 11.sp,
                                    maxLines = 1
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (executionEngineMode == ExecutionEngineMode.OPENCODE_CLOUD) Icons.Default.Cloud else Icons.Default.Psychology,
                                    contentDescription = "Opciones del Motor y Prompt",
                                    modifier = Modifier.size(14.dp)
                                )
                            },
                            modifier = Modifier
                                .height(32.dp)
                                .testTag("engine_options_chip")
                        )

                        DropdownMenu(
                            expanded = showPromptOptionsMenu,
                            onDismissRequest = { showPromptOptionsMenu = false }
                        ) {
                            Text(
                                text = "Motor de Inferencia",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )

                            // Opción A: Motor Local GGUF
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            text = "📱 Motor Local GGUF (en RAM)",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = if (executionEngineMode == ExecutionEngineMode.LOCAL_GGUF) FontWeight.Bold else FontWeight.Normal
                                        )
                                        Text(
                                            text = "100% privado en tu teléfono, sin Internet",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                trailingIcon = if (executionEngineMode == ExecutionEngineMode.LOCAL_GGUF) {
                                    {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                } else null,
                                onClick = {
                                    onSelectEngineMode(ExecutionEngineMode.LOCAL_GGUF)
                                    showPromptOptionsMenu = false
                                }
                            )

                            // Opción B: OpenCode Cloud AI
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            text = "☁️ OpenCode Cloud AI",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = if (executionEngineMode == ExecutionEngineMode.OPENCODE_CLOUD) FontWeight.Bold else FontWeight.Normal
                                        )
                                        Text(
                                            text = "Modelos Gratis, Go y Premium con Visión & OCR",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                trailingIcon = if (executionEngineMode == ExecutionEngineMode.OPENCODE_CLOUD) {
                                    {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                } else null,
                                onClick = {
                                    onSelectEngineMode(ExecutionEngineMode.OPENCODE_CLOUD)
                                    showPromptOptionsMenu = false
                                }
                            )

                            // Opción C: Configurar OpenCode Cloud (API Key, modelos, tiers)
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "⚙️ Configurar OpenCode (API Key & Modelos)...",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Tune,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                onClick = {
                                    showPromptOptionsMenu = false
                                    showOpenCodeConfigDialog = true
                                }
                            )

                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                            // Búsqueda de información adicional en Internet
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            text = "Búsqueda en Internet",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = "Wikipedia / DuckDuckGo para complementar la respuesta",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Public,
                                        contentDescription = null,
                                        tint = if (webSearchEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                trailingIcon = {
                                    Switch(
                                        checked = webSearchEnabled,
                                        onCheckedChange = { onToggleWebSearch(it) }
                                    )
                                },
                                onClick = { onToggleWebSearch(!webSearchEnabled) }
                            )

                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                            Text(
                                text = "Personalidad Activa (System Prompt)",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )

                            personas.forEach { persona ->
                                val isCurrent = persona.id == activePersona.id
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = "${persona.title} (${persona.badge})",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    trailingIcon = if (isCurrent) {
                                        {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    } else null,
                                    onClick = {
                                        onSelectPersonaQuick(persona)
                                        showPromptOptionsMenu = false
                                    }
                                )
                            }
                        }
                    }

                    // 4. Indicador rápido de RAM o Nube
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (executionEngineMode == ExecutionEngineMode.OPENCODE_CLOUD) Icons.Default.Cloud else Icons.Default.Memory,
                                contentDescription = "Estado del motor",
                                modifier = Modifier.size(13.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = if (executionEngineMode == ExecutionEngineMode.OPENCODE_CLOUD)
                                    selectedOpenCodeTier.badge
                                else
                                    "${memorySnapshot.availableDeviceRamMb}MB",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 4. Fila del Input de mensaje + Botón dinámico Enviar / Stop
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("prompt_input"),
                        placeholder = {
                            Text(
                                text = when {
                                    streamingState.isGenerating -> "Generando respuesta... Pulsa Stop para detener"
                                    attachedImage != null -> "Pregunta sobre la foto o pide extraer el texto (OCR)..."
                                    executionEngineMode == ExecutionEngineMode.OPENCODE_CLOUD -> "Escribe a ${selectedOpenCodeModel.name}... (Genera HTML5 autocontenido)"
                                    webSearchEnabled -> "Búsqueda en Internet activa 🌐: escribe tu mensaje..."
                                    else -> "Escribe tu mensaje (Genera HTML5 completo con JS/CSS)..."
                                },
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        shape = RoundedCornerShape(24.dp),
                        maxLines = 4,
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent
                        )
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    if (streamingState.isGenerating) {
                        // Botón STOP activo mientras se genera la respuesta
                        Button(
                            onClick = onStopGeneration,
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            ),
                            modifier = Modifier
                                .size(50.dp)
                                .testTag("stop_button"),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Icon(
                                Icons.Default.Stop,
                                contentDescription = "Detener solicitud"
                            )
                        }
                    } else {
                        // Botón ENVIAR normal
                        val canSend = prompt.isNotBlank() || attachedSmallFile != null || attachedImage != null
                        Button(
                            onClick = {
                                if (canSend) {
                                    val textToSend = prompt
                                    prompt = ""
                                    onSendMessage(textToSend)
                                }
                            },
                            enabled = canSend,
                            shape = CircleShape,
                            modifier = Modifier
                                .size(50.dp)
                                .testTag("send_button"),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Enviar mensaje"
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChatBubbleItem(
    message: ChatMessageEntity,
    onRunCode: (language: String, code: String) -> Unit = { _, _ -> },
    onEditAndResend: (String) -> Unit = {}
) {
    val isUser = message.isUser
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var copiedMessage by remember { mutableStateOf(false) }
    var isEditing by remember { mutableStateOf(false) }
    var editedText by remember(message.content) { mutableStateOf(message.content) }

    // El botón Play solo se habilita cuando el mensaje es una respuesta generada en el chat (!isUser)
    // y contiene formato de código HTML o un script en Python (.py)
    val segments = remember(message.content) { parseMessageSegments(message.content) }
    val firstExecutableBlock = remember(segments, isUser) {
        if (isUser) {
            null
        } else {
            segments.filterIsInstance<MessageContentSegment.CodeBlock>()
                .firstOrNull { isExecutableCodeLanguage(it.language, it.code) }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            color = if (isUser) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            modifier = Modifier.widthIn(max = 360.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (isEditing && isUser) {
                    // Editor en línea para modificar el mensaje enviado y retomar la conversación
                    OutlinedTextField(
                        value = editedText,
                        onValueChange = { editedText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("edit_message_input_${message.id}"),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface
                        ),
                        maxLines = 6
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = {
                                editedText = message.content
                                isEditing = false
                            }
                        ) {
                            Text("Cancelar", color = MaterialTheme.colorScheme.onPrimary)
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Button(
                            onClick = {
                                val updated = editedText.trim()
                                if (updated.isNotEmpty()) {
                                    isEditing = false
                                    onEditAndResend(updated)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.testTag("confirm_edit_message_${message.id}")
                        ) {
                            Text("Retomar con cambios", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                } else {
                    SelectionContainer {
                        FormattedMessageContent(
                            segments = segments,
                            textColor = if (isUser) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            },
                            allowCodeExecution = !isUser,
                            onRunCode = onRunCode
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Fila inferior de acciones del mensaje:
                // - Telemetría (si es IA)
                // - Botón Play (▶ Ejecutar) cuando el mensaje tiene código HTML / Python / JS
                // - Botón Editar (si es un mensaje enviado por el usuario)
                // - Botón Copiar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        if (!isUser) {
                            message.modelUsed?.let { model ->
                                Text(
                                    text = model,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.65f)
                                )
                            }
                            message.tokensPerSec?.let { tps ->
                                Text(
                                    text = String.format(Locale.US, "%.1f tok/s", tps),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Botón PLAY junto a Copiar (habilitado solo en respuestas de IA con código HTML o Python .py)
                        if (!isUser && firstExecutableBlock != null) {
                            val playLabel = if (isHtmlCodeFormat(firstExecutableBlock.language, firstExecutableBlock.code)) {
                                "Play (HTML)"
                            } else {
                                "Play (Python .py)"
                            }
                            Surface(
                                color = Color(0xFF1F3A2D),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        onRunCode(firstExecutableBlock.language, firstExecutableBlock.code)
                                    }
                                    .testTag("play_message_code_${message.id}")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "Ejecutar código HTML o Python del mensaje",
                                        modifier = Modifier.size(14.dp),
                                        tint = Color(0xFF9ECE6A)
                                    )
                                    Text(
                                        text = playLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF9ECE6A)
                                    )
                                }
                            }
                        }

                        // Botón EDITAR en mensajes enviados por el usuario
                        if (isUser && !isEditing) {
                            Surface(
                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.16f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        editedText = message.content
                                        isEditing = true
                                    }
                                    .testTag("edit_message_button_${message.id}")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = "Editar mensaje enviado",
                                        modifier = Modifier.size(13.dp),
                                        tint = MaterialTheme.colorScheme.onPrimary
                                    )
                                    Text(
                                        text = "Editar",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                            }
                        }

                        // Botón COPIAR Mensaje Completo
                        Surface(
                            color = if (isUser) {
                                MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.16f)
                            } else {
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)
                            },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    clipboardManager.setText(AnnotatedString(message.content))
                                    copiedMessage = true
                                    scope.launch {
                                        delay(1800L)
                                        copiedMessage = false
                                    }
                                }
                                .testTag("copy_message_button_${message.id}")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = if (copiedMessage) Icons.Default.Check else Icons.Default.ContentCopy,
                                    contentDescription = "Copiar texto del mensaje",
                                    modifier = Modifier.size(13.dp),
                                    tint = if (isUser) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else if (copiedMessage) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    }
                                )
                                Text(
                                    text = if (copiedMessage) "¡Copiado!" else "Copiar",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = if (isUser) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else if (copiedMessage) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FormattedMessageContent(
    segments: List<MessageContentSegment>,
    textColor: Color,
    allowCodeExecution: Boolean = true,
    onRunCode: (language: String, code: String) -> Unit = { _, _ -> }
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        segments.forEach { segment ->
            when (segment) {
                is MessageContentSegment.PlainText -> {
                    Text(
                        text = segment.text,
                        color = textColor,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                is MessageContentSegment.GeneratedImage -> {
                    GeneratedImageCard(
                        promptOrDescription = segment.prompt,
                        directImageUrl = segment.directUrl,
                        svgMarkup = segment.svgMarkup
                    )
                }

                is MessageContentSegment.CodeBlock -> {
                    IndentedCodeBlockCard(
                        language = segment.language,
                        code = segment.code,
                        allowCodeExecution = allowCodeExecution,
                        onRunCode = { onRunCode(segment.language, segment.code) }
                    )
                }
            }
        }
    }
}

@Composable
fun IndentedCodeBlockCard(
    language: String,
    code: String,
    allowCodeExecution: Boolean = true,
    onRunCode: () -> Unit = {}
) {
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var copiedCode by remember { mutableStateOf(false) }
    val horizontalScrollState = rememberScrollState()
    val codeLines = remember(code) { code.lines() }
    val canExecute = remember(language, code, allowCodeExecution) {
        allowCodeExecution && isExecutableCodeLanguage(language, code)
    }
    val headerBadgeText = remember(language, code) {
        when {
            isHtmlCodeFormat(language, code) -> "HTML"
            isPythonCodeFormat(language, code) -> "PYTHON (.PY)"
            else -> language.uppercase()
        }
    }

    Surface(
        color = Color(0xFF121620),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF2B3245), RoundedCornerShape(10.dp))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Cabecera del bloque de código con etiqueta del lenguaje + Botón Play (solo para HTML o Python .py) + Botón Copiar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1D2333))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Default.Code,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = Color(0xFF7AA2F7)
                    )
                    Text(
                        text = headerBadgeText,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF7AA2F7)
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (canExecute) {
                        val blockPlayText = if (isHtmlCodeFormat(language, code)) "Play HTML" else "Play .py"
                        Surface(
                            color = Color(0xFF1F3A2D),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onRunCode() }
                                .testTag("run_code_block_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Ejecutar código HTML o Python",
                                    modifier = Modifier.size(13.dp),
                                    tint = Color(0xFF9ECE6A)
                                )
                                Text(
                                    text = blockPlayText,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF9ECE6A)
                                )
                            }
                        }
                    }

                    // Botón Copiar Código
                    Surface(
                        color = Color(0xFF293147),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                clipboardManager.setText(AnnotatedString(code))
                                copiedCode = true
                                scope.launch {
                                    delay(1800L)
                                    copiedCode = false
                                }
                            }
                            .testTag("copy_code_block_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = if (copiedCode) Icons.Default.Check else Icons.Default.ContentCopy,
                                contentDescription = "Copiar código con sangría",
                                modifier = Modifier.size(12.dp),
                                tint = if (copiedCode) Color(0xFF9ECE6A) else Color(0xFFC0CAF5)
                            )
                            Text(
                                text = if (copiedCode) "¡Copiado!" else "Copiar",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (copiedCode) Color(0xFF9ECE6A) else Color(0xFFC0CAF5)
                            )
                        }
                    }
                }
            }

            // Cuerpo del código con scroll horizontal para no romper nunca las sangrías
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(horizontalScrollState)
                    .padding(horizontal = 10.dp, vertical = 10.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier.padding(end = 10.dp)
                ) {
                    codeLines.forEachIndexed { idx, _ ->
                        Text(
                            text = "${idx + 1}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = Color(0xFF565F89)
                        )
                    }
                }

                Text(
                    text = code,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    color = Color(0xFFE0E6FF),
                    softWrap = false
                )
            }
        }
    }
}

@Composable
fun StreamingChatBubble(
    state: StreamingUiState,
    onRunCode: (language: String, code: String) -> Unit = { _, _ -> }
) {
    val segments = remember(state.streamingText) { parseMessageSegments(state.streamingText) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = 4.dp,
                bottomEnd = 16.dp
            ),
            modifier = Modifier.widthIn(max = 360.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (state.streamingText.isEmpty()) {
                    Text(
                        text = "Ejecutando modelo GGUF en RAM (llama.cpp JNI)...",
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    FormattedMessageContent(
                        segments = segments,
                        textColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        allowCodeExecution = false,
                        onRunCode = onRunCode
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Icon(
                        Icons.Default.Speed,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = String.format(Locale.US, "%.1f tok/s", state.liveTokensPerSec),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    state.ttftMs?.let { ttft ->
                        Text(
                            text = "• TTFT ${ttft}ms",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}
