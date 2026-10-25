# 🦦 Otter Local LLM — APK Release (`v1.0.0-beta.1`)

En esta carpeta se encuentra el instalador compilado y firmado en modo **Release** (no debug):

- **Archivo:** `Otter-Local-LLM-v1.0.0-beta.1-release.apk`
- **Application ID:** `com.diero777.otterlocalllm`
- **Versión:** `1.0.0-beta.1` (Beta Test)
- **Variante de compilación:** `release` (firmada con certificado RSA de 2048 bits)

## ¿Cómo obtener el APK si no aparece en el visor web o para subirlo a GitHub Releases?

1. **Desde el menú superior de AI Studio (Exportar APK / AAB o ZIP):**
   - Haz clic en el icono de **Configuración / Exportar (arriba a la derecha)** en AI Studio:
     - **Download ZIP:** Descarga todo el proyecto incluyendo esta carpeta `release/` con el APK dentro.
     - **Generate APK / AAB:** Genera y descarga el instalador directamente en tu navegador.
2. **Automáticamente en GitHub con GitHub Actions (`.github/workflows/android-release.yml`):**
   - Al usar **Push to GitHub** desde AI Studio, ve a tu repositorio en GitHub → pestaña **Actions** → **Build & Publish Release APK (Beta)** → haz clic en **Run workflow**.
   - GitHub compilará el APK en modo `Release` y podrás descargarlo en 2 minutos desde la sección **Artifacts** o publicarlo automáticamente al crear el Release `v1.0.0-beta.1`.
