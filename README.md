# 🦦 Otter Local LLM (`v1.0.0-beta.1`)

<p align="center">
  <img src="public/assets/aistudio/otterLLMblanco_af27c1ec.png" alt="Otter Local LLM Logo" width="160" />
</p>

<p align="center">
  <b>Estudio de Inteligencia Artificial Local (Edge AI) para Android</b><br/>
  Ejecuta modelos <b>GGUF</b> y <b>MediaPipe</b> directamente en tu teléfono, 100% privado, con visor de ejecución en vivo para <b>HTML5</b> y <b>Python (.py)</b>.
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Estado-BETA%20(Testing)-f59e0b?style=for-the-badge" alt="Estado Beta" />
  <img src="https://img.shields.io/badge/Build-Release%20APK-10b981?style=for-the-badge" alt="Build Release" />
  <img src="https://img.shields.io/badge/Android-7.0%2B%20(API%2024%2B)-3ddc84?style=for-the-badge&logo=android&logoColor=white" alt="Android 7.0+" />
  <img src="https://img.shields.io/badge/Package-com.diero777.otterlocalllm-0ea5e9?style=for-the-badge" alt="Package ID" />
</p>

---

## ⚠️ Aviso Importante: Versión BETA para Pruebas (Testing)

> **Estado actual del proyecto:** **Otter Local LLM (`v1.0.0-beta.1`)** es una versión **Beta pública de pruebas**.  
> Su propósito principal es validar el rendimiento de inferencia local en procesadores móviles ARM64, medir el consumo real de memoria RAM con distintos niveles de cuantización (`Q2_K`, `Q4_K_M`, `Q8_0`) y probar la ejecución interactiva de código en diferentes dispositivos Android.
>
> **Importante sobre la distribución:** Esta aplicación se distribuye y publica en compilación **`Release`** (optimizada, sin sobrecarga de depuración `debug`) para ofrecer mediciones reales de velocidad de generación de tokens y uso de memoria RAM.

---

## 🧠 ¿Qué es y qué hace Otter Local LLM?

**Otter Local LLM** es una aplicación Android desarrollada en **Kotlin** y **Jetpack Compose (Material Design 3)** diseñada para descargar, gestionar y ejecutar Grandes Modelos de Lenguaje (LLMs) directamente en el hardware del dispositivo (*On-Device Edge AI*), sin depender de servidores externos ni suscripciones.

### Características Principales

1. **Inferencia Local Multimotor (`GGUF` & `MediaPipe`):**
   - Soporte para modelos en formato **`.gguf`** (arquitecturas Llama 3, Qwen 2.5, Gemma 2, Phi-3.5, DeepSeek, Mistral, TinyLlama) y paquetes **`.task` / `.bin` / `.litertlm` / `.tflite`**.
   - Inspección real de cabeceras binarias GGUF (arquitectura, cuantización, tensores y longitud de contexto).
   - Generación de respuestas en *streaming* token por token con métricas de velocidad en vivo (**tokens/seg**).

2. **Tienda de Modelos Integrada + Buscador de Hugging Face en Vivo:**
   - Catálogo curado clasificado por capacidades:
     - **Solo Texto:** Conversación, resumen, redacción y preguntas generales.
     - **Texto + Código:** Especializados en programación **HTML5** y **Python (`.py`)**.
     - **Texto + Imagen:** Asistentes enfocados en diseño visual, diagramas SVG y generación en Canvas.
   - **Explorador directo de Hugging Face:** Busca cualquier repositorio GGUF en tiempo real, inspecciona el peso exacto de cada archivo cuantizado (`Q2_K`, `Q3_K_M`, `Q4_K_M`, `Q5_K_M`, `Q8_0`) y descárgalo con soporte de reanudación (`HTTP Range`).

3. **Almacenamiento Flexible (Memoria Interna o Tarjeta SD) y Escáner Local:**
   - Permite elegir entre **Almacenamiento Interno** o **Tarjeta MicroSD**.
   - Escáner automático de la carpeta `Descargas` (`Downloads`) y selector de archivos para importar modelos `.gguf` ya descargados en el teléfono sin duplicar espacio.

4. **Botón `▶ Play` con Visor de Ejecución para HTML y Python (`.py`):**
   - Cuando la IA genera código en el chat, **Otter Local LLM** detecta automáticamente los bloques ejecutables y habilita el botón **`▶ Play`** exclusivamente para:
     - **HTML (`HTML5`):** Abre un visor web interactivo para renderizar e interactuar con la página, calculadora o interfaz generada en tiempo real.
     - **Python (`.py`):** Ejecuta el script en el motor Python integrado y muestra la salida estándar (`stdout`) y errores en una consola interactiva.
   - Incluye botones rápidos para **Copiar** cualquier mensaje o bloque de código y **Editar** mensajes enviados para regenerar respuestas.

5. **Herramientas Avanzadas en el Botón de Prompt (`+`):**
   - **Adjuntar Archivos Ligeros (hasta 512 KB):** Adjunta archivos `.txt`, `.md`, `.py`, `.html`, `.json`, `.csv`, `.xml` o `.kt` para que el modelo local los analice, resuma o corrija.
   - **Búsqueda Rápida en Internet (Opcional):** Enriquecimiento de contexto consultando fuentes abiertas (Wikipedia ES + DuckDuckGo) antes de responder.
   - **Modo Generación de Imágenes:** Síntesis visual local con previsualización instantánea en el chat y botón para **guardar en la Galería (`.png`)**.
   - **Cambio Rápido de Personalidad (System Prompt):** Alterna entre *Asistente General*, *Programador Experto*, *Profesor Didáctico*, *Escritor Creativo* o tu propio prompt personalizado.

6. **Monitor de RAM en Tiempo Real y Autolimpieza (`onTrimMemory`):**
   - Panel dedicado para supervisar la RAM libre, usada y ocupada por el modelo activo.
   - Botón **Liberar RAM** en un toque y protección automática ante presión de memoria del sistema operativo Android para evitar cierres inesperados (`OutOfMemoryError`).

---

## 📱 Teléfonos Compatibles y Especificaciones Requeridas

Debido a que los modelos de inteligencia artificial se cargan directamente en la **memoria RAM** del teléfono y realizan cálculos intensivos en la **CPU/GPU**, el rendimiento depende del hardware del dispositivo.

### 1. Requisitos Técnicos Generales

| Componente | Requisito Mínimo | Requisito Recomendado | Nivel Óptimo (Avanzado) |
| :--- | :--- | :--- | :--- |
| **Sistema Operativo** | Android 7.0 (API 24) | Android 12+ (API 31+) | Android 14 / 15 / 16 |
| **Arquitectura CPU** | **ARM64 (`arm64-v8a` - 64 bits)** | ARM64 Octa-Core (Cortex-A78 / X2 o superior) | ARM64 con núcleos Cortex-X3 / X4 / Oryon |
| **Memoria RAM Total** | **4 GB RAM** | **6 GB – 8 GB RAM** | **12 GB – 16 GB+ RAM (LPDDR5 / LPDDR5X)** |
| **Almacenamiento Libre** | 2 GB libres | 5 GB – 10 GB libres (UFS 2.2 / 3.1) | 15 GB+ libres (UFS 4.0) |

> **Nota:** Dispositivos antiguos de 32 bits (`armeabi-v7a`) no son compatibles con la carga nativa de tensores de gran tamaño.

---

### 2. ¿Qué teléfonos pueden ejecutar la app y qué modelos usar según tu gama?

#### 🟢 Gama de Entrada / Económica (4 GB de RAM)
- **Ejemplos de teléfonos:** Samsung Galaxy A14 / A15 / A24 (4GB), Xiaomi Redmi Note 11 / 12 / 13 (4GB), Motorola Moto G52 / G53 / G54 (4GB), Realme C55.
- **Procesadores típicos:** Snapdragon 680 / 685, MediaTek Helio G85 / G99, Dimensity 6020 / 6100+.
- **Modelos recomendados (0.5B a 1.5B parámetros):**
  - `Qwen 2.5 (0.5B Instruct) - Q4_K_M` (~400 MB en disco / ~650 MB en RAM)
  - `TinyLlama 1.1B Chat - Q4_K_M` (~670 MB en disco / ~950 MB en RAM)
  - `Llama 3.2 (1B Instruct) - Q4_K_M` (~800 MB en disco / ~1.1 GB en RAM)
- **Consejo de prueba Beta:** Cierra otras aplicaciones en segundo plano antes de cargar el modelo en RAM.

#### 🔵 Gama Media / Media-Alta (6 GB a 8 GB de RAM)
- **Ejemplos de teléfonos:** Samsung Galaxy A34 / A54 / A55, Google Pixel 6a / 7 / 7a / 8a, POCO X6 Pro / F5, Xiaomi 12T / 13T, Motorola Edge 40 / 50 Fusion, Nothing Phone (1) / (2a).
- **Procesadores típicos:** Snapdragon 778G, Snapdragon 7+ Gen 2, Snapdragon 7 Gen 3, MediaTek Dimensity 8100 / 8200 / 8300-Ultra, Google Tensor G2 / G3, Exynos 1380 / 1480.
- **Modelos recomendados (1.5B a 3.8B parámetros):**
  - `Qwen 2.5 Coder (1.5B / 3B) - Q4_K_M` (Excelente para generar **HTML5** y **Python `.py`**)
  - `Gemma 2 (2B Instruct) - Q4_K_M` (~1.6 GB en disco / ~2.2 GB en RAM)
  - `Llama 3.2 (3B Instruct) - Q4_K_M` (~2.0 GB en disco / ~2.6 GB en RAM)
  - `Phi-3.5 Mini (3.8B) - Q4_K_M` (~2.3 GB en disco / ~3.0 GB en RAM)

#### 🟣 Gama Alta / Flagship (12 GB a 16 GB+ de RAM)
- **Ejemplos de teléfonos:** Samsung Galaxy S23 / S24 / S25 (Plus & Ultra), Google Pixel 8 Pro / 9 Pro, Xiaomi 13 / 14 Ultra, OnePlus 11 / 12 / 13, ASUS ROG Phone / RedMagic.
- **Procesadores típicos:** Snapdragon 8 Gen 2, Snapdragon 8 Gen 3, Snapdragon 8 Elite, Dimensity 9200 / 9300 / 9400.
- **Modelos recomendados (3B en alta precisión `Q8_0` hasta 7B–8B en `Q4_K_M`):**
  - `Qwen 2.5 Coder (7B Instruct) - Q4_K_M`
  - `Llama 3.1 (8B Instruct) - Q4_K_M`
  - `Mistral 7B Instruct - Q4_K_M`
  - Cualquier modelo de 1.5B a 3B con cuantización `Q8_0` a máxima velocidad.

---

## 🚀 Publicación y Compilación en Modo `Release` (No Debug)

Para garantizar el máximo rendimiento del motor de inferencia y de Jetpack Compose (sin la sobrecarga de inspección de una compilación `debug`), **Otter Local LLM debe distribuirse y probarse en modo `Release`**.

### Opción 1: Descargar / Generar desde AI Studio
1. Abre el menú de configuración (**Settings / Export**) en AI Studio.
2. Selecciona **Push to GitHub** para subir este repositorio directamente a tu cuenta.
3. Para obtener el instalador optimizado, genera el paquete desde la opción de exportación o mediante el flujo de **GitHub Actions** incluido en `.github/workflows/android-release.yml`.

### Opción 2: Publicación Automática en GitHub Releases (GitHub Actions)
Este repositorio incluye el flujo automatizado `.github/workflows/android-release.yml` que:
- Compila exclusivamente la variante **`Release`** (`gradle :app:assembleRelease :app:bundleRelease`).
- Se activa automáticamente al publicar un tag con formato `v*` (por ejemplo, `v1.0.0-beta.1`) o manualmente desde la pestaña **Actions → Build & Publish Otter Local LLM Release (Beta)**.
- Sube el archivo **`Otter-Local-LLM-v1.0.0-beta.1-release.apk`** listo para instalar en dispositivos de prueba.

### Opción 3: Compilación Local por Línea de Comandos
```bash
# Generar APK en modo Release
gradle :app:assembleRelease

# Generar Android App Bundle (AAB) en modo Release
gradle :app:bundleRelease
```
Los archivos generados se ubicarán en:
- **APK Release:** `app/build/outputs/apk/release/app-release.apk`
- **AAB Release:** `app/build/outputs/bundle/release/app-release.aab`

---

## 🧪 Guía Rápida para Testers de la Beta

1. **Instala el APK `Release`** de **Otter Local LLM** (`com.diero777.otterlocalllm`).
2. Ve a la pestaña **Model Store** y revisa la barra superior de **RAM disponible** en tu teléfono.
3. Descarga un modelo acorde a la RAM de tu dispositivo (para una primera prueba rápida recomendamos un modelo de **0.5B a 1.5B en `Q4_K_M`**) o usa el botón **Escanear dispositivo** si ya tienes archivos `.gguf` en tu carpeta `Descargas`.
4. Pulsa **Cargar en RAM** y abre la pestaña **Chat IA**.
5. Prueba pedirle:
   - Una pregunta en texto general.
   - *"Crea una calculadora en HTML5"* o *"Escribe un script en Python (.py) para calcular números primos"* y pulsa el botón **`▶ Play`** que aparecerá en la respuesta de la IA.
6. Comparte tus comentarios en la sección **Issues** del repositorio indicando el **modelo de tu teléfono**, **cantidad de RAM** y **modelo `.gguf` probado**.

---

## 📄 Identificación del Proyecto

- **Nombre de la aplicación:** `Otter Local LLM`
- **Application ID (Package Name):** `com.diero777.otterlocalllm`
- **Versión actual:** `1.0.0-beta.1` (Release de pruebas)
- **Autor / Desarrollador:** `diero777`
