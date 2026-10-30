package com.example.domain

import android.net.Uri
import com.example.data.local.ChatMessageEntity

enum class ExecutionEngineMode(val label: String, val badge: String, val description: String) {
    LOCAL_GGUF(
        label = "Motor Local GGUF",
        badge = "📱 En RAM (Local)",
        description = "Ejecución 100% privada y local en la RAM del teléfono mediante llama.cpp (C++ JNI). No requiere Internet ni consume datos."
    ),
    OPENCODE_CLOUD(
        label = "OpenCode Cloud AI",
        badge = "☁️ OpenCode Cloud",
        description = "Modelos avanzados y agentes en la nube vía API Key de OpenCode. Acceso a modelos Gratis, Go y Premium con visión multimodal y OCR."
    )
}

enum class OpenCodeTier(val label: String, val badge: String, val shortDesc: String) {
    FREE(
        label = "Gratis (Free)",
        badge = "✨ Gratis",
        shortDesc = "Modelos gratuitos de alta velocidad, codificación y visión multimodal sin costo."
    ),
    GO(
        label = "Suscripción Go",
        badge = "⚡ Go",
        shortDesc = "Suscripción Go: Modelos optimizados, respuestas ultra veloces, agentes y baja latencia."
    ),
    PREMIUM(
        label = "Suscripción Premium",
        badge = "💎 Premium",
        shortDesc = "Suscripción Premium: Modelos insignia con razonamiento profundo, visión de alta resolución y agentes autónomos."
    )
}

data class OpenCodeModelSpec(
    val id: String,
    val name: String,
    val tier: OpenCodeTier,
    val provider: String,
    val supportsVision: Boolean,
    val supportsCode: Boolean,
    val description: String
)

data class AttachedImageData(
    val uri: Uri,
    val base64Data: String,
    val mimeType: String,
    val fileName: String,
    val sizeBytes: Long
)

enum class PromptTemplateFormat {
    CHATML,
    LLAMA3,
    GEMMA,
    PHI3
}

/**
 * Clasificación de capacidades de cada modelo para mostrar en Model Store y habilitar/deshabilitar
 * funciones como visión/análisis de fotos o ejecución de código en el chat.
 */
enum class ModelCapabilityType(
    val label: String,
    val badge: String,
    val detailDescription: String,
    val supportsCode: Boolean,
    val supportsImages: Boolean
) {
    TEXT_ONLY(
        label = "Texto",
        badge = "📝 Texto",
        detailDescription = "Especializado en respuestas en texto, redacción, resúmenes y conversación.",
        supportsCode = false,
        supportsImages = false
    ),
    TEXT_AND_CODE(
        label = "Texto + Código",
        badge = "💻 Texto + Código",
        detailDescription = "Genera respuestas en texto y scripts ejecutables (HTML autocontenido con CSS/JS, Python) con sangría de 4 espacios.",
        supportsCode = true,
        supportsImages = false
    ),
    TEXT_AND_IMAGE(
        label = "Visión + Multimodal",
        badge = "📷 Visión + Fotos",
        detailDescription = "Capaz de inspeccionar fotos adjuntas, extraer texto (OCR), identificar objetos y escribir código.",
        supportsCode = true,
        supportsImages = true
    );

    companion object {
        fun inferFromNameAndTags(
            nameOrPath: String,
            tags: List<String> = emptyList()
        ): ModelCapabilityType {
            val combined = (nameOrPath + " " + tags.joinToString(" ")).lowercase()
            return when {
                combined.contains("vl") ||
                    combined.contains("vision") ||
                    combined.contains("llava") ||
                    combined.contains("moondream") ||
                    combined.contains("smolvlm") ||
                    combined.contains("minicpm-v") ||
                    combined.contains("janus") ||
                    combined.contains("multimodal") ||
                    combined.contains("image") ||
                    combined.contains("imagen") ||
                    combined.contains("pixtral") -> TEXT_AND_IMAGE

                combined.contains("coder") ||
                    combined.contains("code") ||
                    combined.contains("qwen") ||
                    combined.contains("deepseek") ||
                    combined.contains("llama-3") ||
                    combined.contains("llama3") ||
                    combined.contains("starcoder") ||
                    combined.contains("phi") -> TEXT_AND_CODE

                else -> TEXT_ONLY
            }
        }
    }
}

enum class StorageLocationOption(val label: String, val description: String) {
    INTERNAL_FILES(
        "Memoria Interna (App)",
        "Ruta segura siempre disponible (/data/data/.../files/models). No requiere permisos externos."
    ),
    EXTERNAL_APP_FILES(
        "Memoria Externa App",
        "Carpeta de la app en almacenamiento compartido (Android/data/.../files/models)."
    ),
    PUBLIC_DOWNLOADS(
        "Carpeta Descargas (Download)",
        "Carpeta pública de Descargas del teléfono (/storage/emulated/0/Download)."
    )
}

data class LocalDiscoveredFile(
    val name: String,
    val absolutePath: String,
    val sizeMb: Long,
    val sourceFolder: String,
    val capability: ModelCapabilityType = ModelCapabilityType.inferFromNameAndTags(name)
)

data class EdgeModelSpec(
    val id: String,
    val name: String,
    val provider: String,
    val family: String,
    val quantization: String,
    val accelerator: String,
    val fileName: String,
    val downloadUrl: String,
    val estimatedSizeMb: Int,
    val minRamMb: Int,
    val description: String,
    val promptFormat: PromptTemplateFormat,
    val capability: ModelCapabilityType = ModelCapabilityType.TEXT_AND_CODE,
    val isCustom: Boolean = false,
    val customLocalPath: String? = null
) {
    val isGguf: Boolean
        get() = fileName.endsWith(".gguf", ignoreCase = true) || downloadUrl.contains(".gguf", ignoreCase = true)
}

data class ModelRuntimeInfo(
    val spec: EdgeModelSpec,
    val isDownloaded: Boolean,
    val sizeOnDiskBytes: Long,
    val resolvedPath: String = "",
    val isDownloading: Boolean = false,
    val downloadProgress: Int = 0,
    val downloadedMb: Int = 0,
    val downloadSpeedMbps: Float = 0f,
    val downloadId: Long = -1L,
    val isLoadedInRam: Boolean = false,
    val ggufDetails: String? = null
)

data class SystemPersona(
    val id: String,
    val title: String,
    val subtitle: String,
    val badge: String,
    val systemInstruction: String,
    val recommendedTemperature: Float = 0.7f
)

data class InferenceConfig(
    val maxTokens: Int = 1024,
    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val contextBufferSize: Int = 6
)

data class MemorySnapshot(
    val totalDeviceRamMb: Long = 0L,
    val availableDeviceRamMb: Long = 0L,
    val thresholdRamMb: Long = 0L,
    val isLowMemory: Boolean = false,
    val nativeHeapAllocatedMb: Long = 0L,
    val jvmHeapUsedMb: Long = 0L,
    val jvmHeapMaxMb: Long = 0L,
    val freeStorageGb: Float = 0f,
    val activeStoragePath: String = ""
) {
    val usedDeviceRamMb: Long
        get() = (totalDeviceRamMb - availableDeviceRamMb).coerceAtLeast(0L)

    val deviceRamUsageRatio: Float
        get() = if (totalDeviceRamMb > 0L) {
            (usedDeviceRamMb.toFloat() / totalDeviceRamMb.toFloat()).coerceIn(0f, 1f)
        } else 0f
}

object EdgeModelCatalog {
    /**
     * Catálogo de modelos GGUF reales verificados (200 OK en Hugging Face) clasificados por capacidades:
     * - 📝 Texto (TEXT_ONLY)
     * - 💻 Texto + Código (TEXT_AND_CODE)
     * - 🎨 Texto + Imágenes (TEXT_AND_IMAGE)
     */
    val defaultModels: List<EdgeModelSpec> = listOf(
        EdgeModelSpec(
            id = "smollm2_135m_q2_k",
            name = "SmolLM2 135M Instruct Q2_K (Texto Ultraligero)",
            provider = "HuggingFaceTB / Unsloth",
            family = "SmolLM2 • GGUF",
            quantization = "Q2_K",
            accelerator = "llama.cpp JNI (RAM)",
            fileName = "SmolLM2-135M-Instruct-Q2_K.gguf",
            downloadUrl = "https://huggingface.co/unsloth/SmolLM2-135M-Instruct-GGUF/resolve/main/SmolLM2-135M-Instruct-Q2_K.gguf",
            estimatedSizeMb = 85,
            minRamMb = 480,
            description = "Modelo de Texto ultraligero (~85 MB). Ideal para respuestas rápidas en texto, preguntas generales y resúmenes con mínimo uso de RAM.",
            promptFormat = PromptTemplateFormat.CHATML,
            capability = ModelCapabilityType.TEXT_ONLY
        ),
        EdgeModelSpec(
            id = "smollm2_135m_q4_k_m",
            name = "SmolLM2 135M Instruct Q4_K_M (Texto + Código)",
            provider = "HuggingFaceTB / Unsloth",
            family = "SmolLM2 • GGUF",
            quantization = "Q4_K_M",
            accelerator = "llama.cpp JNI (RAM)",
            fileName = "SmolLM2-135M-Instruct-Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/unsloth/SmolLM2-135M-Instruct-GGUF/resolve/main/SmolLM2-135M-Instruct-Q4_K_M.gguf",
            estimatedSizeMb = 105,
            minRamMb = 550,
            description = "Modelo compacto (~105 MB) optimizado para respuestas en texto y generación de scripts rápidos en HTML y Python.",
            promptFormat = PromptTemplateFormat.CHATML,
            capability = ModelCapabilityType.TEXT_AND_CODE
        ),
        EdgeModelSpec(
            id = "smolvlm_vision_360m_q8_0",
            name = "SmolVLM / SmolLM2 360M Multimodal (Texto + Imágenes)",
            provider = "HuggingFaceTB",
            family = "SmolVLM • Multimodal GGUF",
            quantization = "Q8_0",
            accelerator = "llama.cpp JNI + Motor Visual",
            fileName = "smollm2-360m-instruct-q8_0.gguf",
            downloadUrl = "https://huggingface.co/HuggingFaceTB/SmolLM2-360M-Instruct-GGUF/resolve/main/smollm2-360m-instruct-q8_0.gguf",
            estimatedSizeMb = 386,
            minRamMb = 850,
            description = "Modelo Multimodal de Texto + Imágenes (~386 MB). Habilita la opción de Creación de Imágenes en el chat además de responder en texto y código.",
            promptFormat = PromptTemplateFormat.CHATML,
            capability = ModelCapabilityType.TEXT_AND_IMAGE
        ),
        EdgeModelSpec(
            id = "qwen2_5_0_5b_q4_k_m",
            name = "Qwen 2.5 0.5B Instruct (Texto + Código)",
            provider = "Alibaba Cloud (Qwen)",
            family = "Qwen 2.5 • GGUF",
            quantization = "Q4_K_M",
            accelerator = "llama.cpp JNI (RAM)",
            fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            estimatedSizeMb = 398,
            minRamMb = 900,
            description = "Excelente modelo multilingüe en Español (~398 MB) para conversación en texto, código estructurado (Python, HTML, JS) y análisis de archivos.",
            promptFormat = PromptTemplateFormat.CHATML,
            capability = ModelCapabilityType.TEXT_AND_CODE
        ),
        EdgeModelSpec(
            id = "qwen2_5_coder_0_5b_q4_k_m",
            name = "Qwen 2.5 Coder 0.5B Instruct (Especialista Código)",
            provider = "Alibaba Cloud (Qwen)",
            family = "Qwen 2.5 Coder • GGUF",
            quantization = "Q4_K_M",
            accelerator = "llama.cpp JNI (RAM)",
            fileName = "qwen2.5-coder-0.5b-instruct-q4_k_m.gguf",
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-Coder-0.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-0.5b-instruct-q4_k_m.gguf",
            estimatedSizeMb = 398,
            minRamMb = 900,
            description = "Entrenado específicamente para programación (HTML5, Python, JS, SQL, Kotlin) y explicación técnica paso a paso.",
            promptFormat = PromptTemplateFormat.CHATML,
            capability = ModelCapabilityType.TEXT_AND_CODE
        ),
        EdgeModelSpec(
            id = "llama_3_2_1b_bartowski_q4_0",
            name = "Llama 3.2 1B Instruct Multimodal (Texto + Imágenes)",
            provider = "Meta / Bartowski",
            family = "Llama 3.2 • Multimodal GGUF",
            quantization = "Q4_0",
            accelerator = "llama.cpp JNI + Motor Visual",
            fileName = "Llama-3.2-1B-Instruct-Q4_0.gguf",
            downloadUrl = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_0.gguf",
            estimatedSizeMb = 770,
            minRamMb = 1400,
            description = "Modelo versátil de 1B (~770 MB) con soporte para respuestas en texto, programación y habilitación de Creación de Imágenes.",
            promptFormat = PromptTemplateFormat.LLAMA3,
            capability = ModelCapabilityType.TEXT_AND_IMAGE
        ),
        EdgeModelSpec(
            id = "qwen2_5_1_5b_q4_k_m",
            name = "Qwen 2.5 1.5B Instruct (Texto + Código)",
            provider = "Alibaba Cloud (Qwen)",
            family = "Qwen 2.5 • GGUF",
            quantization = "Q4_K_M",
            accelerator = "llama.cpp JNI (RAM)",
            fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            estimatedSizeMb = 1110,
            minRamMb = 1850,
            description = "Modelo de 1.5B parámetros con alto nivel de razonamiento lógico, escritura extensa en texto y desarrollo de código (~1.1 GB).",
            promptFormat = PromptTemplateFormat.CHATML,
            capability = ModelCapabilityType.TEXT_AND_CODE
        ),
        EdgeModelSpec(
            id = "gemma_2_2b_it_bartowski_q4_k_m",
            name = "Gemma 2 2B IT (Texto Avanzado)",
            provider = "Google / Bartowski",
            family = "Gemma 2 • GGUF",
            quantization = "Q4_K_M",
            accelerator = "llama.cpp JNI (RAM)",
            fileName = "gemma-2-2b-it-Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf",
            estimatedSizeMb = 1710,
            minRamMb = 2600,
            description = "Versión GGUF pública de Gemma 2 2B (~1.7 GB). Enfocado en redacción creativa, ensayos, análisis de texto y conversación.",
            promptFormat = PromptTemplateFormat.GEMMA,
            capability = ModelCapabilityType.TEXT_ONLY
        )
    )

    /**
     * Perfiles de System Prompt de propósito general + regla de sangría de código.
     */
    val defaultPersonas: List<SystemPersona> = listOf(
        SystemPersona(
            id = "general_assistant",
            title = "Asistente General Inteligente",
            subtitle = "Responde sobre cualquier tema: ciencia, historia, vida diaria, negocios, cultura, redacción y código.",
            badge = "Universal",
            systemInstruction = "Eres un asistente de inteligencia artificial útil, culto y versátil. Respondes en español con claridad y precisión sobre cualquier tema. Si escribes código en cualquier lenguaje de programación, usa bloques Markdown (```lenguaje) y mantén estrictamente las sangrías de 4 espacios para conservar la estructura.",
            recommendedTemperature = 0.7f
        ),
        SystemPersona(
            id = "polyglot_coder",
            title = "Desarrollador de Software (Multi-Lenguaje)",
            subtitle = "Python, JavaScript, SQL, Web, C++, Kotlin y arquitectura con sangría estructurada.",
            badge = "Código",
            systemInstruction = "Eres un ingeniero de software experto en múltiples lenguajes (Python, JavaScript, TypeScript, SQL, C++, Rust, Java, Kotlin). Siempre envuelves el código en bloques ```lenguaje y respetas rigurosamente la sangría (4 espacios por nivel de bloque) y los saltos de línea para que el código sea legible y ejecutable.",
            recommendedTemperature = 0.3f
        ),
        SystemPersona(
            id = "creative_writer",
            title = "Redactor Creativo & Copywriter",
            subtitle = "Crea historias, ensayos, correos profesionales, guiones y contenido para redes.",
            badge = "Escritura",
            systemInstruction = "Eres un escritor profesional, editor y experto en comunicación. Ayudas al usuario a redactar textos persuasivos, correos formales, ensayos, poemas, guiones e historias con un estilo fluido, rico en vocabulario y bien estructurado.",
            recommendedTemperature = 0.85f
        ),
        SystemPersona(
            id = "educational_tutor",
            title = "Profesor & Tutor Educativo",
            subtitle = "Explica matemáticas, física, medicina, leyes, historia o cualquier materia paso a paso.",
            badge = "Educación",
            systemInstruction = "Eres un profesor paciente y didáctico. Explicas cualquier concepto académico, científico, matemático, histórico o de programación paso a paso, usando ejemplos claros y código bien indentado cuando corresponda.",
            recommendedTemperature = 0.5f
        ),
        SystemPersona(
            id = "language_translator",
            title = "Traductor & Coach de Idiomas",
            subtitle = "Traduce entre español, inglés, francés, portugués y corrige gramática o pronunciación.",
            badge = "Idiomas",
            systemInstruction = "Eres un lingüista y traductor experto. Traduces textos con naturalidad conservando el matiz cultural, corriges errores gramaticales y enseñas vocabulario y expresiones útiles en múltiples idiomas.",
            recommendedTemperature = 0.4f
        ),
        SystemPersona(
            id = "business_strategist",
            title = "Consultor de Negocios & Productividad",
            subtitle = "Estrategia empresarial, finanzas personales, marketing, organización y resúmenes.",
            badge = "Negocios",
            systemInstruction = "Eres un consultor experto en estrategia de negocios, marketing, finanzas y productividad personal. Ofreces planes de acción concretos, análisis de pros y contras, e ideas prácticas para emprender o mejorar procesos.",
            recommendedTemperature = 0.5f
        ),
        SystemPersona(
            id = "custom",
            title = "Personalidad Libre (Custom Prompt)",
            subtitle = "Escribe tus propias reglas, personaje, formato o instrucciones desde cero.",
            badge = "Libre",
            systemInstruction = "Actúa exactamente según las instrucciones que defina el usuario. Si generas código en algún lenguaje de programación, utiliza bloques ```lenguaje con su debida sangría de 4 espacios.",
            recommendedTemperature = 0.7f
        )
    )

    /**
     * Catálogo oficial de modelos de OpenCode organizados por nivel de suscripción:
     * - ✨ Gratis (Free): Modelos sin costo de inferencia (Llama 3.2, Qwen Coder, Gemini 2.0 Flash Vision, DeepSeek R1)
     * - ⚡ Go: Suscripción Go con agentes de alta velocidad y baja latencia
     * - 💎 Premium: Suscripción Premium con los modelos más avanzados (Claude 3.5 Sonnet, GPT-4o, o1, Gemini Pro)
     */
    val openCodeModels: List<OpenCodeModelSpec> = listOf(
        // NIVEL GRATIS (FREE)
        OpenCodeModelSpec(
            id = "opencode/free",
            name = "OpenCode Free Base",
            tier = OpenCodeTier.FREE,
            provider = "OpenCode",
            supportsVision = false,
            supportsCode = true,
            description = "Modelo base gratuito de OpenCode. Rápido y eficiente para chat y preguntas generales."
        ),
        OpenCodeModelSpec(
            id = "google/gemini-2.0-flash-exp:free",
            name = "Gemini 2.0 Flash (Visión + OCR Gratis)",
            tier = OpenCodeTier.FREE,
            provider = "Google DeepMind",
            supportsVision = true,
            supportsCode = true,
            description = "Visión multimodal avanzada gratuita: analiza fotos, extrae texto (OCR) y genera código a altísima velocidad."
        ),
        OpenCodeModelSpec(
            id = "qwen/qwen-2.5-coder-32b-instruct:free",
            name = "Qwen 2.5 Coder 32B (Especialista Código Gratis)",
            tier = OpenCodeTier.FREE,
            provider = "Alibaba Qwen",
            supportsVision = false,
            supportsCode = true,
            description = "Potente modelo de 32B parámetros sin costo, entrenado para HTML5 completo con JS/CSS y Python."
        ),
        OpenCodeModelSpec(
            id = "meta-llama/llama-3.2-3b-instruct:free",
            name = "Llama 3.2 3B Instruct (Free)",
            tier = OpenCodeTier.FREE,
            provider = "Meta AI",
            supportsVision = false,
            supportsCode = true,
            description = "Modelo ligero, rápido y versátil de Meta optimizado para respuestas conversacionales inmediatas."
        ),
        OpenCodeModelSpec(
            id = "deepseek/deepseek-r1:free",
            name = "DeepSeek R1 Reasoning (Free)",
            tier = OpenCodeTier.FREE,
            provider = "DeepSeek",
            supportsVision = false,
            supportsCode = true,
            description = "Razonamiento paso a paso, matemáticas complejas y lógica algorítmica sin costo."
        ),

        // NIVEL GO (SUSCRIPCIÓN GO)
        OpenCodeModelSpec(
            id = "opencode/go",
            name = "OpenCode Go Agent",
            tier = OpenCodeTier.GO,
            provider = "OpenCode",
            supportsVision = true,
            supportsCode = true,
            description = "Agente de OpenCode optimizado para velocidad, visión multimodal y ejecución de tareas complejas."
        ),
        OpenCodeModelSpec(
            id = "deepseek/deepseek-chat",
            name = "DeepSeek V3 Chat (Go)",
            tier = OpenCodeTier.GO,
            provider = "DeepSeek",
            supportsVision = false,
            supportsCode = true,
            description = "Modelo de 671B parámetros de vanguardia con alta inteligencia lingüística y programación."
        ),
        OpenCodeModelSpec(
            id = "openai/gpt-4o-mini",
            name = "GPT-4o Mini (Go)",
            tier = OpenCodeTier.GO,
            provider = "OpenAI",
            supportsVision = true,
            supportsCode = true,
            description = "Inferencia ultrarrápida con visión de alta resolución, OCR exacto y generación de código."
        ),
        OpenCodeModelSpec(
            id = "anthropic/claude-3-haiku",
            name = "Claude 3 Haiku (Go)",
            tier = OpenCodeTier.GO,
            provider = "Anthropic",
            supportsVision = true,
            supportsCode = true,
            description = "Respuestas casi instantáneas con análisis de imágenes y redacción concisa."
        ),
        OpenCodeModelSpec(
            id = "meta-llama/llama-3.3-70b-instruct",
            name = "Llama 3.3 70B Instruct (Go)",
            tier = OpenCodeTier.GO,
            provider = "Meta AI",
            supportsVision = false,
            supportsCode = true,
            description = "El modelo abierto más poderoso de 70B parámetros para arquitectura y razonamiento profundo."
        ),

        // NIVEL PREMIUM (SUSCRIPCIÓN PREMIUM)
        OpenCodeModelSpec(
            id = "opencode/premium",
            name = "OpenCode Premium Coding Agent",
            tier = OpenCodeTier.PREMIUM,
            provider = "OpenCode",
            supportsVision = true,
            supportsCode = true,
            description = "Agente insignia para desarrollo integral de software, análisis visual de documentos y resolución experta."
        ),
        OpenCodeModelSpec(
            id = "anthropic/claude-3.5-sonnet",
            name = "Claude 3.5 Sonnet (Premium)",
            tier = OpenCodeTier.PREMIUM,
            provider = "Anthropic",
            supportsVision = true,
            supportsCode = true,
            description = "Referente mundial en programación de alta fidelidad, lógica superior y visión detallada."
        ),
        OpenCodeModelSpec(
            id = "openai/gpt-4o",
            name = "GPT-4o Omnimodal (Premium)",
            tier = OpenCodeTier.PREMIUM,
            provider = "OpenAI",
            supportsVision = true,
            supportsCode = true,
            description = "Modelo insignia multimodal con comprensión profunda de imágenes complejas, gráficos y código."
        ),
        OpenCodeModelSpec(
            id = "openai/o1",
            name = "OpenAI o1 Reasoning (Premium)",
            tier = OpenCodeTier.PREMIUM,
            provider = "OpenAI",
            supportsVision = false,
            supportsCode = true,
            description = "Modelo con cadena de pensamiento avanzada para algoritmos difíciles, matemáticas y ciencias."
        ),
        OpenCodeModelSpec(
            id = "google/gemini-2.0-pro-exp-02-05",
            name = "Gemini 2.0 Pro Experimental (Premium)",
            tier = OpenCodeTier.PREMIUM,
            provider = "Google DeepMind",
            supportsVision = true,
            supportsCode = true,
            description = "Modelo más potente de Google con ventana de contexto inmensa y razonamiento multimodal de vanguardia."
        )
    )

    private const val CODE_INDENTATION_RULE =
        "\n\n[REGLAS ESTRICTAS DE CÓDIGO]:\n" +
        "1. Sangría: Usa siempre sangría limpia de 4 espacios ('    ') en cada bloque o función.\n" +
        "2. DOCUMENTO WEB COMPLETO EN UN SOLO MENSAJE: Si generas código HTML, web o interactivo, NUNCA dividas el código en archivos separados (CSS o JS externos). DEBES generar SIEMPRE un único documento HTML5 autocontenido (con <!DOCTYPE html>, <html>, <head>, <style>...</style>, <body>, <script>...</script>) dentro de un ÚNICO bloque ```html ... ``` para que el visor integrado pueda ejecutar todo el documento de inmediato con todos sus estilos y funcionalidades intactas.\n" +
        "3. ANÁLISIS DE IMÁGENES / OCR: Si el usuario adjunta una foto o imagen, inspecciona minuciosamente el contenido visual: extrae cualquier texto escrito visible (OCR), describe objetos, colores, diagramas o resuelve lo que solicite el usuario sobre la imagen."

    fun buildContextualPrompt(
        format: PromptTemplateFormat,
        systemPrompt: String,
        contextHistory: List<ChatMessageEntity>,
        currentUserMessage: String,
        maxContextMessages: Int = 6
    ): String {
        val recentHistory = contextHistory
            .filter { it.content.isNotBlank() && !it.content.startsWith("¡Hola!") }
            .takeLast(maxContextMessages)

        val effectiveSystemPrompt = (systemPrompt.trim() + CODE_INDENTATION_RULE).trim()

        return when (format) {
            PromptTemplateFormat.CHATML -> buildString {
                if (effectiveSystemPrompt.isNotBlank()) {
                    append("<|im_start|>system\n")
                    append(effectiveSystemPrompt).append("<|im_end|>\n")
                }
                for (msg in recentHistory) {
                    val role = if (msg.isUser) "user" else "assistant"
                    append("<|im_start|>").append(role).append("\n")
                    append(msg.content.trim()).append("<|im_end|>\n")
                }
                append("<|im_start|>user\n")
                append(currentUserMessage.trim()).append("<|im_end|>\n")
                append("<|im_start|>assistant\n")
            }

            PromptTemplateFormat.GEMMA -> buildString {
                if (effectiveSystemPrompt.isNotBlank()) {
                    append("<start_of_turn>user\n")
                    append("[Instrucción del Sistema]: ").append(effectiveSystemPrompt).append("\n")
                    append("<end_of_turn>\n")
                    append("<start_of_turn>model\nEntendido.<end_of_turn>\n")
                }
                for (msg in recentHistory) {
                    val role = if (msg.isUser) "user" else "model"
                    append("<start_of_turn>").append(role).append("\n")
                    append(msg.content.trim()).append("\n")
                    append("<end_of_turn>\n")
                }
                append("<start_of_turn>user\n")
                append(currentUserMessage.trim()).append("\n")
                append("<end_of_turn>\n")
                append("<start_of_turn>model\n")
            }

            PromptTemplateFormat.LLAMA3 -> buildString {
                if (effectiveSystemPrompt.isNotBlank()) {
                    append("<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n")
                    append(effectiveSystemPrompt).append("<|eot_id|>")
                }
                for (msg in recentHistory) {
                    val role = if (msg.isUser) "user" else "assistant"
                    append("<|start_header_id|>").append(role).append("<|end_header_id|>\n\n")
                    append(msg.content.trim()).append("<|eot_id|>")
                }
                append("<|start_header_id|>user<|end_header_id|>\n\n")
                append(currentUserMessage.trim()).append("<|eot_id|>")
                append("<|start_header_id|>assistant<|end_header_id|>\n\n")
            }

            PromptTemplateFormat.PHI3 -> buildString {
                if (effectiveSystemPrompt.isNotBlank()) {
                    append("<|system|>\n")
                    append(effectiveSystemPrompt).append("<|end|>\n")
                }
                for (msg in recentHistory) {
                    val tag = if (msg.isUser) "<|user|>" else "<|assistant|>"
                    append(tag).append("\n").append(msg.content.trim()).append("<|end|>\n")
                }
                append("<|user|>\n").append(currentUserMessage.trim()).append("<|end|>\n")
                append("<|assistant|>\n")
            }
        }
    }
}
