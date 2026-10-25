# 🦦 Guía para Subir a GitHub y Publicar el Release Beta

Aquí tienes los textos listos para copiar y pegar al subir tu repositorio a GitHub y al crear tu primer **Release (`v1.0.0-beta.1`)**.

---

## 1. Descripción corta para el repositorio de GitHub (Sección "About")

Copia y pega esta línea en el campo **Description** de tu repositorio en GitHub:

```text
🦦 Otter Local LLM (Beta) — Ejecuta modelos de IA GGUF y MediaPipe 100% local en Android con visor interactivo para código HTML5 y Python (.py). (com.diero777.otterlocalllm)
```

**Topics sugeridos para GitHub:**
`android` `llm` `gguf` `edge-ai` `local-llm` `kotlin` `jetpack-compose` `mediapipe` `offline-ai` `otter-llm`

---

## 2. Plantilla para la Publicación en "GitHub Releases" (Versión `v1.0.0-beta.1` - MODO RELEASE)

Cuando crees el Release en GitHub:
- **Tag version:** `v1.0.0-beta.1`
- **Release title:** `🦦 Otter Local LLM v1.0.0-beta.1 (Beta de Pruebas - Build Release)`
- **Marca la casilla:** ✅ *Set as a pre-release* (indica en GitHub que es una versión Beta de prueba).
- **Adjunta el archivo APK compilado en modo Release** (no subas el APK debug).

### Descripción del Release (Copiar y pegar):

```markdown
## ⚠️ Versión Beta Pública para Pruebas (`v1.0.0-beta.1` — Compilación Release)

Esta es la versión **Beta de pruebas** de **Otter Local LLM** (`com.diero777.otterlocalllm`), compilada en modo **`Release`** (sin sobrecarga de depuración) para medir el rendimiento real de inferencia local en teléfonos Android.

### ✨ ¿Qué es y qué incluye esta versión?
- **Motor Local GGUF y MediaPipe:** Carga modelos `.gguf` directamente en la memoria RAM del teléfono sin conexión a servidores externos.
- **Model Store + Buscador de Hugging Face:** Descarga modelos clasificados en *Solo Texto*, *Texto + Código* y *Texto + Imagen*, o busca cualquier repositorio GGUF en Hugging Face.
- **Carga desde Memoria Interna o Tarjeta SD:** Escanea tu carpeta `Descargas` o selecciona archivos `.gguf` locales.
- **Visor `▶ Play` exclusivo para HTML y Python (`.py`):** Ejecuta código HTML5 y scripts Python `.py` generados por la IA directamente dentro del chat.
- **Herramientas en el botón de Prompt (`+`):** Adjunta archivos ligeros (`.txt`, `.md`, `.py`, `.json`, `.csv`), activa la búsqueda rápida en Internet o genera ilustraciones descargables en `.png`.
- **Monitor de RAM en vivo:** Supervisión de memoria en tiempo real y liberación de RAM en un toque.

### 📱 Teléfonos Compatibles y Requisitos para Testers
- **Arquitectura obligatoria:** Procesador de 64 bits (`arm64-v8a`) con **Android 7.0+ (API 24+)** (recomendado Android 12+).
- **Teléfonos de 4 GB de RAM (Gama de entrada):** Usar modelos ligeros de **0.5B a 1.5B** en cuantización `Q2_K` o `Q4_K_M` (ej. *Qwen 2.5 0.5B*, *TinyLlama 1.1B*).
- **Teléfonos de 6 GB a 8 GB de RAM (Gama media):** Soporta modelos de **1.5B a 3.8B** en `Q4_K_M` (ej. *Qwen 2.5 Coder 1.5B/3B*, *Gemma 2 2B*, *Llama 3.2 3B*).
- **Teléfonos de 12 GB a 16 GB+ de RAM (Gama alta):** Soporta modelos de **7B a 8B** en `Q4_K_M` o modelos de 3B en `Q8_0`.
- **Espacio libre:** Entre **2 GB y 6 GB** de espacio libre en almacenamiento interno o tarjeta SD.
```
