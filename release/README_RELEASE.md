# 🦦 Otter Local LLM — APK Release (`v1.0.1-beta`)

Bienvenido a la versión **v1.0.1-beta** de **Otter Local LLM** (Application ID: `com.diero777.otterlocalllm`), compilada en modo **Release** (no debug), optimizada con firma criptográfica y lista para pruebas de campo en dispositivos Android.

---

## 📦 Contenido de esta Entrega

- **APK de Producción / Release:** `release/Otter-Local-LLM-v1.0.1-beta-release.apk`
- **Application ID:** `com.diero777.otterlocalllm`
- **Versión de la App:** `1.0.1-beta` (versionCode: `3`)
- **Firma:** Certificado RSA de 2048 bits (`upload`)
- **Tipo de Compilación:** `Release` (sin flags de debug, compatible con instalación directa en Android)

---

## 🚀 Nuevas Funcionalidades en `v1.0.1-beta`

### 1. 📷 Entrada Multimodal de Imágenes & OCR
- **Botón de Foto / OCR:** El botón de imágenes ahora permite seleccionar fotos desde la galería o capturarlas con la cámara mediante el selector nativo de Android (zero permissions).
- **Inspección Visual y OCR:** El modelo de IA analiza la imagen adjunta para extraer texto de recibos, documentos o capturas de pantalla, describir objetos y responder preguntas sobre la fotografía.
- **Previsualización interactiva:** Muestra una miniatura de la imagen adjunta con nombre de archivo, peso en KB y botón para retirar antes de enviar.

### 2. 🌐 Generación de Código Web Unificado (HTML + CSS + JS)
- **Documento autocontenido:** Cuando el agente genera código frontend, estructura todo el documento (`<!DOCTYPE html>`, etiquetas `<style>` y scripts `<script>`) dentro de un único bloque de código delimitado.
- **Ejecución Instantánea con Botón Play:** El visor interactivo HTML5 ejecuta el documento completo en un sandbox seguro con captura de consola en tiempo real y selector de pestañas (Vista Previa / Código / Consola).

### 3. ☁️ Motor OpenCode Cloud AI vía API Key (Gratis, Go & Premium)
- **Alternador Rápido:** Cambia entre el motor **Local GGUF (en RAM)** y la nube **OpenCode Cloud AI** directamente desde el control encima del teclado.
- **Suscripciones y Tiers Soportados:**
  - 🆓 **Tier Gratis (Sin costo):**
    - `opencode/free` — Modelo general optimizado gratis.
    - `google/gemini-2.0-flash-exp:free` — Razonamiento multimodal y visión.
    - `qwen/qwen-2.5-coder-32b-instruct:free` — Especialista en código y desarrollo.
    - `meta-llama/llama-3.2-3b-instruct:free` — Respuestas ultra rápidas.
    - `deepseek/deepseek-r1:free` — Razonamiento analítico profundo paso a paso.
  - ⚡ **Tier Go (Velocidad y Eficiencia):**
    - `opencode/go`, `deepseek/deepseek-chat`, `openai/gpt-4o-mini`, `anthropic/claude-3-haiku`, `meta-llama/llama-3.3-70b-instruct`.
  - 👑 **Tier Premium (Máxima Potencia y Razonamiento):**
    - `opencode/premium`, `anthropic/claude-3.5-sonnet`, `openai/gpt-4o`, `openai/o1`, `google/gemini-2.0-pro-exp-02-05`.
- **Streaming SSE en Tiempo Real:** Respuestas fluidas con contador de tokens por segundo y botón de **Detener (Stop)** en cualquier momento.

### 4. 🧠 Motor Local GGUF (llama.cpp JNI en RAM)
- Carga modelos `.gguf` directamente en la RAM física del teléfono.
- Privacidad 100% offline (sin conexión a internet requerida para inferencia local).
- Monitoreo en vivo de RAM asignada, memoria libre del sistema y advertencias automáticas ante niveles críticos de memoria (`onTrimMemory`).

---

## 📱 Especificaciones Técnicas y Compatibilidad de Teléfonos

| Característica | Requisito Mínimo | Requisito Recomendado (GGUF Local) | OpenCode Cloud AI |
|---|---|---|---|
| **Sistema Operativo** | Android 7.0 (Nougat, API 24+) | Android 11+ (API 30+) | Android 7.0+ |
| **Arquitectura de CPU** | ARM64 (arm64-v8a) o x86_64 | ARM64 con núcleos Cortex-A77/A78/X1+ | Cualquier arquitectura |
| **Memoria RAM** | 3 GB RAM (Modo Nube) | 6 GB a 12 GB RAM | 3 GB RAM |
| **Espacio Libre en Disco** | 500 MB (para cache) | 4 GB a 8 GB (para modelos GGUF) | 200 MB |
| **Procesador sugerido** | Snapdragon 680 / Helio G88 | Snapdragon 7+ Gen 2 / 8 Gen 1 / 8 Gen 2 / Dimensity 8200+ | Cualquier procesador |
