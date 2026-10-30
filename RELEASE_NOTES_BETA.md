# 🦦 Publicación en GitHub Releases — Versión `v1.0.1-beta`

Aquí tienes los datos exactos listos para copiar y pegar en tu repositorio de GitHub para publicar el nuevo **Release (`v1.0.1-beta`)**:

---

### 📋 Campos del Formulario de GitHub Release

- **Tag version:** `v1.0.1-beta`
- **Target:** `main` (o `master`)
- **Release title:** `🦦 Otter Local LLM v1.0.1-beta — Visión Multimodal / OCR & OpenCode Cloud Engine`
- **Casillas a marcar:** 
  - ✅ **Set as a pre-release** *(Muy importante: marca esto para indicar que es versión beta de pruebas).*
- **Archivo adjunto (Binario):** 
  - Arrastra el archivo: `release/Otter-Local-LLM-v1.0.1-beta-release.apk` (o descárgalo desde la carpeta `release/`).

---

### 📝 Descripción del Release (Markdown para copiar y pegar en GitHub):

```markdown
# 🦦 Otter Local LLM — v1.0.1-beta (Compilación Release Optimizada)

¡Segunda versión beta pública de **Otter Local LLM** (`com.diero777.otterlocalllm`)! Compilada en modo **`Release`** con firma digital RSA y optimizaciones nativas para evaluar el rendimiento real en dispositivos Android.

Esta versión introduce dos grandes saltos evolutivos: **visión artificial multimodal con OCR** y soporte para **OpenCode Cloud AI**, permitiendo correr tanto modelos 100% offline en la RAM del teléfono como potentes modelos en la nube mediante API key.

---

### 🌟 Novedades Principales en `v1.0.1-beta`

#### 📷 1. Inspección Visual Multimodal & OCR
- **Adjuntado de Fotos Directo:** El botón de fotos en el chat ahora se utiliza para seleccionar imágenes de la galería o tomar fotos con la cámara mediante el selector seguro de Android (cero permisos intrusivos).
- **Extracción de Texto (OCR) y Análisis:** Los modelos de IA con soporte de visión analizan la foto para transcribir recibos, carteles o códigos, describir detalles visuales y resolver dudas paso a paso sobre la imagen.
- **Previsualización con Miniatura:** Muestra tarjeta flotante con miniatura, tamaño en KB y botón rápido para descartar antes del envío.

#### 🌐 2. Generación de Código Web Unificado (HTML5 + CSS + JS)
- **Documento Completo Autocontenido:** El agente de programación genera todo el proyecto web (`<!DOCTYPE html>`, estilos `<style>` y scripts `<script>`) dentro de un único bloque de código estructurado.
- **Ejecución Instantánea con `▶ Play`:** El visor integrado ejecuta la aplicación frontend completa al instante con sandbox seguro, pestañas de inspección y consola JavaScript en vivo.

#### ☁️ 3. Motor OpenCode Cloud AI vía API Key (Gratis, Go & Premium)
- **Alternador de Motor en 1 Toque:** Alterna cómodamente entre el motor **Local GGUF (en RAM física)** y la nube **OpenCode Cloud AI**.
- **Modelos Integrados por Tier:**
  - 🆓 **Gratis (Free):** `opencode/free`, `google/gemini-2.0-flash-exp:free` (Visión/OCR), `qwen/qwen-2.5-coder-32b-instruct:free` (Código), `meta-llama/llama-3.2-3b-instruct:free`, `deepseek/deepseek-r1:free` (Razonamiento analítico).
  - ⚡ **Go:** `opencode/go`, `deepseek/deepseek-chat`, `openai/gpt-4o-mini`, `anthropic/claude-3-haiku`, `meta-llama/llama-3.3-70b-instruct`.
  - 👑 **Premium:** `opencode/premium`, `anthropic/claude-3.5-sonnet`, `openai/gpt-4o`, `openai/o1`, `google/gemini-2.0-pro-exp-02-05`.
- **Streaming SSE y Control Total:** Flujo continuo de respuestas con contador de velocidad (tokens/s) y botón **Detener (Stop)** inmediato.

#### 🧠 4. Motor Local GGUF (llama.cpp JNI en RAM)
- Carga modelos `.gguf` directamente en la RAM física del teléfono sin depender de servidores ni internet.
- Monitor de memoria RAM en tiempo real con sistema de descarga rápida y gestión inteligente ante eventos de memoria baja del sistema.

---

### 📱 Especificaciones Técnicas y Compatibilidad

| Modo de Uso | RAM Mínima | Procesador Sugerido | Espacio Libre |
|---|---|---|---|
| **OpenCode Cloud AI** | 3 GB RAM | Cualquier CPU (Snapdragon, Helio, Exynos, Unisoc) | 200 MB |
| **GGUF Local (0.5B a 1.5B)** | 4 GB RAM | Snapdragon 680 / Helio G85 o superior | 1.5 GB a 2.5 GB |
| **GGUF Local (2B a 3.8B)** | 6 GB a 8 GB RAM | Snapdragon 7+ Gen 2 / 8 Gen 1 / Dimensity 8000+ | 3 GB a 5 GB |
| **GGUF Local (7B a 8B)** | 12 GB a 16 GB RAM | Snapdragon 8 Gen 2 / 8 Gen 3 / Dimensity 9200+ | 6 GB a 10 GB |

> **Arquitectura requerida:** Android 7.0+ (API 24 o superior) en arquitectura de 64 bits (`arm64-v8a` o `x86_64`).

---

### 📥 Instalación

1. Descarga el archivo `Otter-Local-LLM-v1.0.1-beta-release.apk` adjunto en este release.
2. Ábrelo en tu teléfono Android y autoriza la instalación de fuentes desconocidas si es necesario.
3. ¡Comienza a chatear, ejecutar scripts o probar tus modelos locales y en la nube!
```
