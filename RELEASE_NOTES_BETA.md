# 🦦 Guía para Subir a GitHub y Publicar el Release Beta (`v1.0.0-beta.2`)

Aquí tienes los textos listos para copiar y pegar al subir tu repositorio a GitHub y al crear tu nuevo **Release (`v1.0.0-beta.2`)**.

---

## 1. Descripción corta para el repositorio de GitHub (Sección "About")

Copia y pega esta línea en el campo **Description** de tu repositorio en GitHub:

```text
🦦 Otter Local LLM (Beta 2) — Inferencia local GGUF en RAM y Cloud AI OpenCode con visión multimodal/OCR, ejecución de código HTML5 unificado y scripts Python en Android. (com.diero777.otterlocalllm)
```

**Topics sugeridos para GitHub:**
`android` `llm` `gguf` `edge-ai` `opencode` `local-llm` `kotlin` `jetpack-compose` `vision-ocr` `html5-sandbox` `offline-ai` `otter-llm`

---

## 2. Plantilla para la Publicación en "GitHub Releases" (`v1.0.0-beta.2` - MODO RELEASE)

Al crear el Release en GitHub:
- **Tag version:** `v1.0.0-beta.2`
- **Release title:** `🦦 Otter Local LLM v1.0.0-beta.2 (Beta de Pruebas - Multimodal OCR & OpenCode Cloud)`
- **Marca la casilla:** ✅ *Set as a pre-release* (versión Beta para testers).
- **Archivo adjunto:** `release/Otter-Local-LLM-v1.0.0-beta.2-release.apk` (o descárgalo desde la carpeta `release/` del proyecto).

### Descripción del Release (Copiar y pegar en GitHub):

```markdown
## ⚠️ Versión Beta Pública para Pruebas (`v1.0.0-beta.2` — Compilación Release)

Esta es la segunda versión **Beta de pruebas** de **Otter Local LLM** (`com.diero777.otterlocalllm`), compilada en modo **`Release`** (sin sobrecarga de depuración) con firma digital RSA para probar tanto la inferencia local en RAM como la ejecución en la nube.

### 🌟 Novedades Destacadas en esta Versión (`v1.0.0-beta.2`):
1. **📷 Entrada Multimodal de Fotos & OCR (Visión Artificial):**
   - El botón de imágenes ahora se utiliza para **adjuntar fotos o capturas de la galería/cámara** (zero permissions, Android Photo Picker).
   - Los modelos de IA con soporte de visión analizan la foto para **extraer texto (OCR de documentos, facturas, letreros), describir objetos y responder dudas sobre la imagen**.
2. **💻 Código Web Unificado (HTML + CSS + JS en un solo mensaje):**
   - El agente de IA genera páginas web completas autocontenidas (`<!DOCTYPE html>`, estilos `<style>` y lógica `<script>`) dentro de un único bloque de código.
   - El visor interactivo `▶ Play` ejecuta la aplicación web completa al instante en el sandbox seguro.
3. **☁️ Integración de OpenCode Cloud AI vía API Key:**
   - Permite alternar en un toque entre el motor **Local GGUF (en RAM)** y la **Nube de OpenCode** con streaming en tiempo real.
   - Soporte para 3 niveles de suscripción:
     - 🆓 **Gratis (Free):** `opencode/free`, `google/gemini-2.0-flash-exp:free`, `qwen/qwen-2.5-coder-32b-instruct:free`, `meta-llama/llama-3.2-3b-instruct:free`, `deepseek/deepseek-r1:free`.
     - ⚡ **Go:** `opencode/go`, `deepseek/deepseek-chat`, `openai/gpt-4o-mini`, `anthropic/claude-3-haiku`, `meta-llama/llama-3.3-70b-instruct`.
     - 👑 **Premium:** `opencode/premium`, `anthropic/claude-3.5-sonnet`, `openai/gpt-4o`, `openai/o1`, `google/gemini-2.0-pro-exp-02-05`.
4. **🛑 Control de Solicitud en Tiempo Real:** Botón de Stop para cancelar la respuesta en cualquier momento tanto en la nube como en local.

### 📱 Teléfonos Compatibles y Requisitos para Testers
- **Arquitectura obligatoria:** Procesador de 64 bits (`arm64-v8a` o `x86_64`) con **Android 7.0+ (API 24+)** (recomendado Android 11+).
- **Modo OpenCode Cloud:** Funciona en cualquier teléfono Android con al menos 3 GB de RAM y conexión a internet.
- **Modo Local GGUF (en RAM):**
  - **4 GB de RAM:** Modelos de 0.5B a 1.5B (ej. *Qwen 2.5 0.5B*, *TinyLlama 1.1B*).
  - **6 GB a 8 GB de RAM:** Modelos de 1.5B a 3.8B en `Q4_K_M` (ej. *Qwen 2.5 Coder 1.5B/3B*, *Gemma 2 2B*, *Llama 3.2 3B*).
  - **12 GB a 16 GB+ de RAM:** Modelos de 7B a 8B en `Q4_K_M`.
- **Almacenamiento:** Mínimo 2 GB libres para almacenar modelos `.gguf` en memoria interna o tarjeta SD.
```
