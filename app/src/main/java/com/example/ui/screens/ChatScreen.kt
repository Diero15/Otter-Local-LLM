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

    // 2. Extraer imágenes Markdown ![alt](https://...)
    val mdImgRegex = Regex("""!\[([^\]]*)\]\((https?://[^\)]+)\)""")
    mdImgRegex.findAll(remainingContent).forEach { match ->
        val alt = match.groupValues[1].ifBlank { "Imagen generada" }
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
    onToggleWebSearch: (Boolean) -> Unit = {},
    onToggleImageGeneration: (Boolean) -> Unit = {},
    onSelectPersonaQuick: (SystemPersona) -> Unit = {},
    onOpenModelStore: () -> Unit,
    onOpenPersonaConfig: () -> Unit,
    modifier: Modifier = Modifier
) {
    var prompt by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Visor de ejecución de código (se activa únicamente al pulsar Play en un bloque de código o mensaje con código)
    var sandboxRequest by remember { mutableStateOf<Pair<String, String>?>(null) }

    // Menú desplegable de opciones en el botón de Prompt encima del input
    var showPromptOptionsMenu by remember { mutableStateOf(false) }

    // Determinar si el modelo cargado actualmente soporta creación de imágenes (TEXT_AND_IMAGE)
    val modelSupportsImages = loadedModel?.spec?.capability?.supportsImages == true
    val effectiveImageCreationEnabled = modelSupportsImages && imageGenerationEnabled

    // Detectar apertura del teclado en pantalla para mantener el último mensaje y el input visibles a la altura del teclado
    val density = androidx.compose.ui.platform.LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)

    // Selector de archivos pequeños (.txt, .md, código fuente)
    val smallFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            onAttachSmallFile(uri)
        }
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
                // Pastilla visual si el usuario adjuntó un archivo pequeño (.txt, .md, .py, .js, etc.)
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
                                        text = "Archivo .${attachedSmallFile.extension} listo para enviar al modelo (${(attachedSmallFile.sizeBytes / 1024L).coerceAtLeast(1L)} KB)",
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

                // Fila de controles ubicada encima del input de mensaje
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 1. Chip de Modelo Activo en RAM mostrando su capacidad (Texto, Código o Texto+Imágenes)
                    AssistChip(
                        onClick = onOpenModelStore,
                        label = {
                            val modelChipText = loadedModel?.let {
                                "${it.spec.capability.badge} • ${it.spec.name}"
                            } ?: "Cargar Modelo"
                            Text(
                                text = modelChipText,
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
                                        if (loadedModel != null) MaterialTheme.colorScheme.secondary
                                        else MaterialTheme.colorScheme.error
                                    )
                            )
                        },
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .height(32.dp)
                            .testTag("active_model_chip")
                    )

                    // 2. Botón para cargar archivos pequeños de código, .txt o .md
                    AssistChip(
                        onClick = {
                            smallFilePickerLauncher.launch(arrayOf("*/*"))
                        },
                        label = {
                            Text(
                                text = if (attachedSmallFile != null) "1 Archivo" else ".txt / Código",
                                fontSize = 11.sp,
                                maxLines = 1
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.AttachFile,
                                contentDescription = "Cargar archivo pequeño de texto, markdown o código",
                                modifier = Modifier.size(14.dp)
                            )
                        },
                        modifier = Modifier
                            .height(32.dp)
                            .testTag("attach_small_file_chip")
                    )

                    // 3. Botón de Prompt con menú de opciones (Búsqueda en Internet, Creación de Imágenes condicional y Personalidad)
                    Box {
                        AssistChip(
                            onClick = { showPromptOptionsMenu = true },
                            label = {
                                val indicators = buildString {
                                    append(activePersona.badge)
                                    if (webSearchEnabled) append(" • 🌐")
                                    if (effectiveImageCreationEnabled) append(" • 🎨")
                                }
                                Text(
                                    text = indicators,
                                    fontSize = 11.sp,
                                    maxLines = 1
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Psychology,
                                    contentDescription = "Opciones de Prompt y Búsqueda en Internet",
                                    modifier = Modifier.size(15.dp)
                                )
                            },
                            modifier = Modifier
                                .height(32.dp)
                                .testTag("persona_chip")
                        )

                        DropdownMenu(
                            expanded = showPromptOptionsMenu,
                            onDismissRequest = { showPromptOptionsMenu = false }
                        ) {
                            Text(
                                text = "Opciones del Modelo (${loadedModel?.spec?.capability?.badge ?: "📝 Texto"})",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )

                            // Opción 1: Búsqueda de información adicional en Internet
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            text = "Búsqueda de información adicional",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = "Consulta Internet (Wikipedia / DuckDuckGo) para una mejor respuesta en texto",
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

                            // Opción 2: Creación de imágenes (SOLO se habilita cuando el modelo descargado tiene capacidad TEXT_AND_IMAGE)
                            DropdownMenuItem(
                                enabled = modelSupportsImages,
                                text = {
                                    Column {
                                        Text(
                                            text = "Creación de imágenes",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (modelSupportsImages) MaterialTheme.colorScheme.onSurface
                                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                                        )
                                        Text(
                                            text = if (modelSupportsImages) {
                                                if (effectiveImageCreationEnabled) "Activo: el input creará imágenes + descripción"
                                                else "Desactivado: la IA local responderá en texto"
                                            } else {
                                                "Requiere modelo '🎨 Texto + Imágenes' en Model Store (La IA responderá en texto)"
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (modelSupportsImages) MaterialTheme.colorScheme.onSurfaceVariant
                                            else MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                                        )
                                    }
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Image,
                                        contentDescription = null,
                                        tint = if (effectiveImageCreationEnabled) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (modelSupportsImages) 1f else 0.4f)
                                    )
                                },
                                trailingIcon = {
                                    Switch(
                                        checked = effectiveImageCreationEnabled,
                                        enabled = modelSupportsImages,
                                        onCheckedChange = {
                                            if (modelSupportsImages) {
                                                onToggleImageGeneration(it)
                                            }
                                        }
                                    )
                                },
                                onClick = {
                                    if (modelSupportsImages) {
                                        onToggleImageGeneration(!effectiveImageCreationEnabled)
                                    }
                                }
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

                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "Editar System Prompt y Parámetros...",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Tune,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                },
                                onClick = {
                                    showPromptOptionsMenu = false
                                    onOpenPersonaConfig()
                                }
                            )
                        }
                    }

                    // 4. Indicador rápido de RAM libre
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Memory,
                                contentDescription = "RAM disponible",
                                modifier = Modifier.size(13.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "${memorySnapshot.availableDeviceRamMb}MB",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Fila del Input de mensaje + Botón dinámico Enviar / Stop
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
                                    effectiveImageCreationEnabled -> "Creación de imágenes"
                                    webSearchEnabled -> "Búsqueda de información adicional activa 🌐: escribe tu mensaje..."
                                    else -> "Escribe un mensaje para generar respuesta en texto o código..."
                                },
                                fontSize = 14.sp
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
                        val canSend = prompt.isNotBlank() || attachedSmallFile != null
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
