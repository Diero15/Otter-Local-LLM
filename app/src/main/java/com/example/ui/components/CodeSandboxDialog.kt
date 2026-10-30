package com.example.ui.components

import android.annotation.SuppressLint
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Html
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import java.net.URLEncoder

enum class SandboxEngineMode(val label: String) {
    HTML_PREVIEW("Vista HTML5 en Vivo"),
    PYTHON_CONSOLE("Resultado de Ejecución Python (.py)")
}

fun isHtmlCodeFormat(language: String, code: String): Boolean {
    val lang = language.lowercase().trim().removePrefix(".")
    val trimmed = code.trim()
    if (trimmed.isEmpty()) return false

    if (lang == "html" || lang == "htm" || lang.endsWith(".html") || lang.endsWith(".htm")) {
        return true
    }

    if (lang.isEmpty() || lang == "code") {
        return trimmed.startsWith("<!DOCTYPE html", ignoreCase = true) ||
            trimmed.startsWith("<html", ignoreCase = true) ||
            (trimmed.startsWith("<") &&
                (trimmed.contains("<body", ignoreCase = true) ||
                    trimmed.contains("<div", ignoreCase = true) ||
                    trimmed.contains("<head", ignoreCase = true) ||
                    trimmed.contains("<section", ignoreCase = true) ||
                    trimmed.contains("<main", ignoreCase = true) ||
                    trimmed.contains("<form", ignoreCase = true) ||
                    trimmed.contains("<table", ignoreCase = true) ||
                    trimmed.contains("<canvas", ignoreCase = true)) &&
                trimmed.contains("</"))
    }

    return false
}

fun isPythonCodeFormat(language: String, code: String): Boolean {
    val lang = language.lowercase().trim()
    val normalizedLang = lang.removePrefix(".")
    val trimmed = code.trim()
    if (trimmed.isEmpty()) return false

    if (normalizedLang == "py" ||
        normalizedLang == "python" ||
        normalizedLang == "python3" ||
        lang.endsWith(".py")
    ) {
        return true
    }

    if (normalizedLang.isEmpty() || normalizedLang == "code") {
        val hasPythonKeywords = Regex("""(?m)^\s*(def\s+\w+\s*\(|import\s+\w+|from\s+\w+\s+import\s+|print\s*\(|for\s+\w+\s+in\s+range\s*\(|if\s+__name__\s*==)""")
            .containsMatchIn(trimmed)
        val looksLikeOtherLang = trimmed.startsWith("<") ||
            trimmed.contains("fun main(") ||
            trimmed.contains("public static void") ||
            trimmed.contains("#include") ||
            trimmed.contains("console.log(") ||
            trimmed.contains("function ")
        return hasPythonKeywords && !looksLikeOtherLang
    }

    return false
}

/**
 * Habilita el botón Play únicamente cuando el bloque generado corresponde a código HTML
 * o a un script de Python (.py).
 */
fun isExecutableCodeLanguage(language: String, code: String): Boolean {
    return isHtmlCodeFormat(language, code) || isPythonCodeFormat(language, code)
}

fun detectSandboxMode(language: String, code: String): SandboxEngineMode {
    return if (isHtmlCodeFormat(language, code)) {
        SandboxEngineMode.HTML_PREVIEW
    } else {
        SandboxEngineMode.PYTHON_CONSOLE
    }
}

private class SandboxJsBridge(
    private val onConsoleOutput: (String) -> Unit,
    private val onExecutionDone: (Long) -> Unit
) {
    @JavascriptInterface
    fun postLog(message: String) {
        onConsoleOutput(message)
    }

    @JavascriptInterface
    fun postDone(elapsedMs: Long) {
        onExecutionDone(elapsedMs)
    }
}

/**
 * Visor organizado y directo que ejecuta exclusivamente el código HTML, Python o JS generado
 * y muestra inmediatamente el resultado visual o de consola.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CodeSandboxDialog(
    initialLanguage: String,
    initialCode: String,
    onDismiss: () -> Unit
) {
    val mode = remember(initialLanguage, initialCode) {
        detectSandboxMode(initialLanguage, initialCode)
    }
    var consoleLogs by remember { mutableStateOf(listOf<String>()) }
    var lastExecTimeMs by remember { mutableStateOf<Long?>(null) }
    var runTrigger by remember { mutableIntStateOf(1) }
    var copiedOutput by remember { mutableStateOf(false) }

    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.84f),
            shape = RoundedCornerShape(18.dp),
            color = Color(0xFF0F131C),
            tonalElevation = 10.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Cabecera limpia con título del motor, tiempo de ejecución, botón Re-ejecutar y Cerrar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF191F2E))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = if (mode == SandboxEngineMode.HTML_PREVIEW) Icons.Default.Html else Icons.Default.Terminal,
                            contentDescription = null,
                            tint = if (mode == SandboxEngineMode.HTML_PREVIEW) Color(0xFF7AA2F7) else Color(0xFF9ECE6A),
                            modifier = Modifier.size(22.dp)
                        )
                        Column {
                            Text(
                                text = mode.label,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE0E6FF),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "Lenguaje: ${initialLanguage.uppercase()}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF9AA5CE)
                                )
                                lastExecTimeMs?.let { ms ->
                                    Text(
                                        text = "• Ejecutado en ${ms}ms",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF9ECE6A)
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Botón Re-ejecutar
                        Surface(
                            color = Color(0xFF252D42),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    consoleLogs = emptyList()
                                    runTrigger++
                                }
                                .testTag("rerun_sandbox_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "Volver a ejecutar",
                                    modifier = Modifier.size(14.dp),
                                    tint = Color(0xFF7DCFFF)
                                )
                                Text(
                                    text = "Re-ejecutar",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF7DCFFF)
                                )
                            }
                        }

                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(32.dp)
                                .testTag("close_sandbox_button")
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Cerrar visor",
                                tint = Color(0xFFC0CAF5)
                            )
                        }
                    }
                }

                // Cuerpo Principal:
                // - Si es HTML_PREVIEW: Muestra el resultado visual renderizado a pantalla completa en el WebView
                //   y un panel inferior compacto sólo si el HTML emitió logs en consola.
                // - Si es PYTHON_CONSOLE o JS_CONSOLE: Muestra directamente la terminal de resultados limpia y organizada.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(if (mode == SandboxEngineMode.HTML_PREVIEW) 1f else 0.01f)
                ) {
                    AndroidView(
                        modifier = if (mode == SandboxEngineMode.HTML_PREVIEW) {
                            Modifier
                                .fillMaxSize()
                                .padding(12.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.dp, Color(0xFF2B3245), RoundedCornerShape(12.dp))
                        } else {
                            Modifier.size(1.dp)
                        },
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.cacheMode = WebSettings.LOAD_DEFAULT
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                webViewClient = WebViewClient()
                                webChromeClient = object : WebChromeClient() {
                                    override fun onConsoleMessage(cm: ConsoleMessage?): Boolean {
                                        cm?.let {
                                            val prefix = if (it.messageLevel() == ConsoleMessage.MessageLevel.ERROR) "[Error] " else ""
                                            scope.launch {
                                                consoleLogs = consoleLogs + "$prefix${it.message()}"
                                            }
                                        }
                                        return true
                                    }
                                }
                                addJavascriptInterface(
                                    SandboxJsBridge(
                                        onConsoleOutput = { line ->
                                            scope.launch {
                                                consoleLogs = consoleLogs + line
                                            }
                                        },
                                        onExecutionDone = { ms ->
                                            scope.launch {
                                                lastExecTimeMs = ms
                                            }
                                        }
                                    ),
                                    "AndroidSandbox"
                                )
                            }
                        },
                        update = { webView ->
                            val payloadKey = "${mode.name}_$runTrigger"
                            if (webView.tag != payloadKey) {
                                webView.tag = payloadKey
                                val htmlPayload = when (mode) {
                                    SandboxEngineMode.HTML_PREVIEW -> buildHtmlPreviewDocument(initialCode)
                                    SandboxEngineMode.PYTHON_CONSOLE -> buildPythonRunnerDocument(initialCode)
                                }
                                webView.loadDataWithBaseURL(
                                    "https://localhost/",
                                    htmlPayload,
                                    "text/html",
                                    "UTF-8",
                                    null
                                )
                            }
                        }
                    )
                }

                // Panel de Resultado de Consola (Principal para Python/JS, o auxiliar si el HTML imprimió en consola)
                if (mode != SandboxEngineMode.HTML_PREVIEW || consoleLogs.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(if (mode == SandboxEngineMode.HTML_PREVIEW) 0.28f else 0.99f)
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .border(1.dp, Color(0xFF2B3245), RoundedCornerShape(12.dp))
                            .background(Color(0xFF0B0E14), RoundedCornerShape(12.dp))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF161B26))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "SALIDA DEL SCRIPT (STDOUT)",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF9ECE6A)
                            )

                            Surface(
                                color = Color(0xFF242B3D),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        val fullOut = consoleLogs.joinToString("\n")
                                        clipboardManager.setText(AnnotatedString(fullOut))
                                        copiedOutput = true
                                        scope.launch {
                                            delay(1500L)
                                            copiedOutput = false
                                        }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = if (copiedOutput) Icons.Default.Check else Icons.Default.ContentCopy,
                                        contentDescription = "Copiar resultado",
                                        modifier = Modifier.size(12.dp),
                                        tint = Color(0xFFC0CAF5)
                                    )
                                    Text(
                                        text = if (copiedOutput) "¡Resultado copiado!" else "Copiar resultado",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFFC0CAF5)
                                    )
                                }
                            }
                        }

                        val scrollState = rememberScrollState()
                        LaunchedEffect(consoleLogs.size) {
                            scrollState.animateScrollTo(scrollState.maxValue)
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState)
                                .horizontalScroll(rememberScrollState())
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (consoleLogs.isEmpty()) {
                                Text(
                                    text = "> Script ejecutado (sin salida en print/console).",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                    color = Color(0xFF787C99)
                                )
                            } else {
                                consoleLogs.forEach { logLine ->
                                    val isErr = logLine.startsWith("[Error") || logLine.startsWith("Traceback")
                                    Text(
                                        text = logLine,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 13.sp,
                                        lineHeight = 19.sp,
                                        color = if (isErr) Color(0xFFF7768E) else Color(0xFFE0E6FF)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Tarjeta visual que renderiza imágenes generadas por el modelo (mediante URL generativa, SVG o Canvas procedural)
 * directamente dentro de la burbuja del chat.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun GeneratedImageCard(
    promptOrDescription: String,
    directImageUrl: String? = null,
    svgMarkup: String? = null
) {
    val cleanPrompt = promptOrDescription.trim().ifEmpty { "Arte Digital IA" }
    val resolvedUrl = remember(cleanPrompt, directImageUrl) {
        if (!directImageUrl.isNullOrBlank() && (directImageUrl.startsWith("http://") || directImageUrl.startsWith("https://"))) {
            directImageUrl
        } else {
            val encoded = URLEncoder.encode(cleanPrompt, "UTF-8")
            "https://image.pollinations.ai/prompt/$encoded?width=768&height=512&nologo=true"
        }
    }

    Surface(
        color = Color(0xFF141824),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF2E3750), RoundedCornerShape(12.dp))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1D2333))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    Icons.Default.Image,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = Color(0xFFBB9AF7)
                )
                Text(
                    text = "IMAGEN GENERADA • ${cleanPrompt.take(40)}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFBB9AF7),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (!svgMarkup.isNullOrBlank()) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.loadWithOverviewMode = true
                                settings.useWideViewPort = true
                                webViewClient = WebViewClient()
                            }
                        },
                        update = { webView ->
                            if (webView.tag != svgMarkup) {
                                webView.tag = svgMarkup
                                val html = buildImageRenderHtml(cleanPrompt, resolvedUrl, svgMarkup)
                                webView.loadDataWithBaseURL(
                                    "https://localhost/",
                                    html,
                                    "text/html",
                                    "UTF-8",
                                    null
                                )
                            }
                        }
                    )
                } else {
                    AsyncImage(
                        model = resolvedUrl,
                        contentDescription = cleanPrompt,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

private fun buildImageRenderHtml(prompt: String, imageUrl: String, svgMarkup: String?): String {
    val safePrompt = JSONObject.quote(prompt)
    val safeUrl = JSONObject.quote(imageUrl)

    if (!svgMarkup.isNullOrBlank()) {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    body { margin: 0; background: #0f131c; display: flex; align-items: center; justify-content: center; height: 100vh; overflow: hidden; }
                    svg { max-width: 100%; max-height: 100%; width: auto; height: auto; }
                </style>
            </head>
            <body>$svgMarkup</body>
            </html>
        """.trimIndent()
    }

    // Intenta cargar la imagen generada desde la URL; si el dispositivo está offline,
    // dibuja automáticamente una ilustración generativa procedural en HTML5 Canvas basada en el prompt
    return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <style>
                body, html { margin: 0; padding: 0; width: 100%; height: 100%; background: #0f131c; overflow: hidden; display: flex; align-items: center; justify-content: center; font-family: system-ui, sans-serif; }
                img { width: 100%; height: 100%; object-fit: cover; display: block; }
                canvas { width: 100%; height: 100%; display: none; }
            </style>
        </head>
        <body>
            <img id="genImg" alt="Imagen generada" />
            <canvas id="fallbackCanvas" width="640" height="420"></canvas>
            <script>
                const promptText = $safePrompt;
                const imgUrl = $safeUrl;
                const imgEl = document.getElementById('genImg');
                const canvas = document.getElementById('fallbackCanvas');

                function renderProceduralArt(text) {
                    imgEl.style.display = 'none';
                    canvas.style.display = 'block';
                    const ctx = canvas.getContext('2d');
                    let hash = 0;
                    for (let i = 0; i < text.length; i++) {
                        hash = ((hash << 5) - hash) + text.charCodeAt(i);
                        hash |= 0;
                    }
                    const hue1 = Math.abs(hash % 360);
                    const hue2 = (hue1 + 75) % 360;
                    const hue3 = (hue1 + 195) % 360;

                    const grad = ctx.createLinearGradient(0, 0, canvas.width, canvas.height);
                    grad.addColorStop(0, 'hsl(' + hue1 + ', 65%, 16%)');
                    grad.addColorStop(0.5, 'hsl(' + hue2 + ', 60%, 24%)');
                    grad.addColorStop(1, 'hsl(' + hue3 + ', 70%, 14%)');
                    ctx.fillStyle = grad;
                    ctx.fillRect(0, 0, canvas.width, canvas.height);

                    for (let i = 0; i < 18; i++) {
                        const r = 20 + ((Math.abs(hash * (i + 3)) % 95));
                        const x = (Math.abs((hash >> (i % 8)) * (i + 11)) % canvas.width);
                        const y = (Math.abs((hash >> (i % 5)) * (i + 7)) % canvas.height);
                        const h = (hue1 + i * 22) % 360;
                        ctx.beginPath();
                        ctx.arc(x, y, r, 0, Math.PI * 2);
                        ctx.fillStyle = 'hsla(' + h + ', 80%, 60%, 0.18)';
                        ctx.fill();
                        ctx.lineWidth = 1.5;
                        ctx.strokeStyle = 'hsla(' + h + ', 90%, 75%, 0.32)';
                        ctx.stroke();
                    }

                    ctx.fillStyle = 'rgba(15, 19, 28, 0.72)';
                    ctx.fillRect(24, canvas.height - 84, canvas.width - 48, 60);
                    ctx.strokeStyle = 'rgba(187, 154, 247, 0.55)';
                    ctx.strokeRect(24, canvas.height - 84, canvas.width - 48, 60);

                    ctx.fillStyle = '#e0e6ff';
                    ctx.font = 'bold 18px system-ui, sans-serif';
                    ctx.fillText('Arte Generativo Local (Canvas)', 40, canvas.height - 56);
                    ctx.fillStyle = '#a9b1d6';
                    ctx.font = '14px system-ui, sans-serif';
                    ctx.fillText(text.slice(0, 58), 40, canvas.height - 34);
                }

                imgEl.onerror = function() {
                    renderProceduralArt(promptText);
                };
                imgEl.src = imgUrl;
            </script>
        </body>
        </html>
    """.trimIndent()
}

private fun buildHtmlPreviewDocument(rawCode: String): String {
    val hasHtmlTag = rawCode.contains("<html", ignoreCase = true) || rawCode.contains("<!DOCTYPE", ignoreCase = true)
    val consoleHook = """
        <script>
            (function() {
                const origLog = console.log;
                const origErr = console.error;
                function formatArgs(args) {
                    return Array.from(args).map(a => {
                        try {
                            return typeof a === 'object' ? JSON.stringify(a) : String(a);
                        } catch(e) {
                            return String(a);
                        }
                    }).join(' ');
                }
                console.log = function() {
                    if (window.AndroidSandbox) window.AndroidSandbox.postLog(formatArgs(arguments));
                    origLog.apply(console, arguments);
                };
                console.error = function() {
                    if (window.AndroidSandbox) window.AndroidSandbox.postLog('[Error] ' + formatArgs(arguments));
                    origErr.apply(console, arguments);
                };
                window.onerror = function(msg, url, line) {
                    if (window.AndroidSandbox) window.AndroidSandbox.postLog('[Error Línea ' + line + ']: ' + msg);
                };
            })();
        </script>
    """.trimIndent()

    return if (hasHtmlTag) {
        "$consoleHook\n$rawCode"
    } else {
        """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <style>
                body { font-family: system-ui, -apple-system, sans-serif; margin: 12px; background: #ffffff; color: #111827; }
            </style>
            $consoleHook
        </head>
        <body>
            $rawCode
        </body>
        </html>
        """.trimIndent()
    }
}

private fun buildPythonRunnerDocument(pythonCode: String): String {
    val escapedPy = JSONObject.quote(pythonCode)
    return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="utf-8">
        </head>
        <body>
        <script>
            (function() {
                const t0 = performance.now();
                const pySource = $escapedPy;

                function emitLine(text) {
                    if (window.AndroidSandbox) window.AndroidSandbox.postLog(String(text));
                }

                function reprPy(val) {
                    if (val === null || val === undefined) return 'None';
                    if (val === true) return 'True';
                    if (val === false) return 'False';
                    if (Array.isArray(val)) return '[' + val.map(reprPy).join(', ') + ']';
                    if (typeof val === 'object') {
                        try { return JSON.stringify(val); } catch (e) { return String(val); }
                    }
                    return String(val);
                }

                const print = function() {
                    const out = Array.from(arguments).map(a => typeof a === 'string' ? a : reprPy(a)).join(' ');
                    emitLine(out);
                };
                const range = function(a, b, step) {
                    let start = 0, stop = a, st = step || 1;
                    if (b !== undefined) { start = a; stop = b; }
                    const arr = [];
                    if (st > 0) {
                        for (let i = start; i < stop; i += st) arr.push(i);
                    } else if (st < 0) {
                        for (let i = start; i > stop; i += st) arr.push(i);
                    }
                    return arr;
                };
                const enumerate = function(arr, start) {
                    const s = start || 0;
                    return Array.from(arr).map((v, idx) => [idx + s, v]);
                };
                const len = function(v) {
                    if (v == null) return 0;
                    if (typeof v === 'string' || Array.isArray(v)) return v.length;
                    if (typeof v === 'object') return Object.keys(v).length;
                    return 0;
                };
                const sum = function(arr) { return Array.from(arr).reduce((a, b) => a + b, 0); };
                const min = function(a, b) { return Array.isArray(a) ? Math.min.apply(null, a) : Math.min(a, b); };
                const max = function(a, b) { return Array.isArray(a) ? Math.max.apply(null, a) : Math.max(a, b); };
                const abs = Math.abs;
                const int = function(v) { return parseInt(v, 10); };
                const float = function(v) { return parseFloat(v); };
                const str = function(v) { return reprPy(v); };
                const list = function(v) { return v ? Array.from(v) : []; };

                if (!Array.prototype.append) {
                    Object.defineProperty(Array.prototype, 'append', {
                        value: function(item) { this.push(item); },
                        enumerable: false
                    });
                }
                if (!Array.prototype.extend) {
                    Object.defineProperty(Array.prototype, 'extend', {
                        value: function(items) { this.push.apply(this, Array.from(items)); },
                        enumerable: false
                    });
                }

                function transpilePythonToJs(src) {
                    const rawLines = src.replace(/\r\n/g, '\n').replace(/\t/g, '    ').split('\n');
                    const indentStack = [0];
                    const jsLines = [];
                    const declaredVars = new Set();

                    function transformExpressions(expr) {
                        let s = expr;
                        s = s.replace(/\bf"([^"]*)"/g, function(_, inner) {
                            return '`' + inner.replace(/\{([^}]+)\}/g, '${'$'}{$1}') + '`';
                        });
                        s = s.replace(/\bf'([^']*)'/g, function(_, inner) {
                            return '`' + inner.replace(/\{([^}]+)\}/g, '${'$'}{$1}') + '`';
                        });
                        s = s.replace(/\bTrue\b/g, 'true')
                             .replace(/\bFalse\b/g, 'false')
                             .replace(/\bNone\b/g, 'null')
                             .replace(/\band\b/g, '&&')
                             .replace(/\bor\b/g, '||')
                             .replace(/\bnot\s+/g, '!')
                             .replace(/\/\//g, '/');
                        return s;
                    }

                    for (let i = 0; i < rawLines.length; i++) {
                        const raw = rawLines[i];
                        const codeOnly = raw.replace(/(^|\s)#.*$/, '');
                        if (codeOnly.trim() === '') continue;

                        const indent = codeOnly.match(/^ */)[0].length;
                        let line = codeOnly.trim();

                        while (indent < indentStack[indentStack.length - 1]) {
                            indentStack.pop();
                            jsLines.push('}');
                        }

                        if (line === 'pass') {
                            jsLines.push('// pass');
                            continue;
                        }

                        if (line.endsWith(':')) {
                            const header = line.slice(0, -1).trim();
                            if (header.startsWith('def ')) {
                                const fnBody = header.slice(4).trim();
                                jsLines.push('function ' + transformExpressions(fnBody) + ' {');
                            } else if (header.startsWith('class ')) {
                                const clsName = header.slice(6).split('(')[0].trim();
                                jsLines.push('class ' + clsName + ' {');
                            } else if (header.startsWith('elif ')) {
                                const cond = transformExpressions(header.slice(5).trim());
                                jsLines.push('else if (' + cond + ') {');
                            } else if (header === 'else') {
                                jsLines.push('else {');
                            } else if (header.startsWith('if ')) {
                                const cond = transformExpressions(header.slice(3).trim());
                                jsLines.push('if (' + cond + ') {');
                            } else if (header.startsWith('while ')) {
                                const cond = transformExpressions(header.slice(6).trim());
                                jsLines.push('while (' + cond + ') {');
                            } else if (header.startsWith('for ')) {
                                const forMatch = header.match(/^for\s+(.+?)\s+in\s+(.+)$/);
                                if (forMatch) {
                                    const target = forMatch[1].trim();
                                    const iter = transformExpressions(forMatch[2].trim());
                                    if (target.includes(',')) {
                                        jsLines.push('for (const [' + target + '] of ' + iter + ') {');
                                    } else {
                                        jsLines.push('for (const ' + target + ' of ' + iter + ') {');
                                    }
                                } else {
                                    jsLines.push('for (' + transformExpressions(header.slice(4)) + ') {');
                                }
                            } else if (header === 'try') {
                                jsLines.push('try {');
                            } else if (header.startsWith('except')) {
                                jsLines.push('catch (_pyErr) {');
                            } else if (header === 'finally') {
                                jsLines.push('finally {');
                            }
                            indentStack.push(indent + 4);
                            continue;
                        }

                        const assignMatch = line.match(/^([a-zA-Z_][a-zA-Z0-9_]*)\s*=\s*([^=].*)$/);
                        if (assignMatch && !line.startsWith('return ')) {
                            const varName = assignMatch[1];
                            const rhs = transformExpressions(assignMatch[2]);
                            if (!declaredVars.has(varName)) {
                                declaredVars.add(varName);
                                jsLines.push('let ' + varName + ' = ' + rhs + ';');
                            } else {
                                jsLines.push(varName + ' = ' + rhs + ';');
                            }
                        } else {
                            jsLines.push(transformExpressions(line) + ';');
                        }
                    }

                    while (indentStack.length > 1) {
                        indentStack.pop();
                        jsLines.push('}');
                    }

                    return jsLines.join('\n');
                }

                try {
                    const compiledJs = transpilePythonToJs(pySource);
                    const runner = new Function(
                        'print', 'range', 'enumerate', 'len', 'sum', 'min', 'max', 'abs', 'int', 'float', 'str', 'list',
                        compiledJs
                    );
                    runner(print, range, enumerate, len, sum, min, max, abs, int, float, str, list);
                } catch (err) {
                    emitLine('Traceback (most recent call last):');
                    emitLine('  [Error de ejecución Python]: ' + (err && err.message ? err.message : String(err)));
                }

                const elapsed = Math.max(1, Math.round(performance.now() - t0));
                if (window.AndroidSandbox) window.AndroidSandbox.postDone(elapsed);
            })();
        </script>
        </body>
        </html>
    """.trimIndent()
}
