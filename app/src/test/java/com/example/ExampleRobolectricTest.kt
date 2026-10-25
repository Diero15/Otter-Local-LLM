package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.domain.EdgeModelCatalog
import com.example.domain.ModelCapabilityType
import com.example.ui.components.SandboxEngineMode
import com.example.ui.components.detectSandboxMode
import com.example.ui.components.isExecutableCodeLanguage
import com.example.ui.screens.MessageContentSegment
import com.example.ui.screens.parseMessageSegments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Otter Local LLM", appName)
    }

    @Test
    fun `catalog includes text, code, and image model capabilities`() {
        val capabilities = EdgeModelCatalog.defaultModels.map { it.capability }.toSet()
        assertTrue(capabilities.contains(ModelCapabilityType.TEXT_ONLY))
        assertTrue(capabilities.contains(ModelCapabilityType.TEXT_AND_CODE))
        assertTrue(capabilities.contains(ModelCapabilityType.TEXT_AND_IMAGE))
    }

    @Test
    fun `play button only enabled for html and python py code formats`() {
        assertTrue(isExecutableCodeLanguage("html", "<!DOCTYPE html><html><body><h1>Hola</h1></body></html>"))
        assertTrue(isExecutableCodeLanguage("python", "def saludo():\n    print('Hola')"))
        assertTrue(isExecutableCodeLanguage("py", "print(1 + 2)"))
        assertTrue(isExecutableCodeLanguage("script.py", "for i in range(3):\n    print(i)"))

        assertEquals(
            SandboxEngineMode.HTML_PREVIEW,
            detectSandboxMode("html", "<!DOCTYPE html><html><body>Hola</body></html>")
        )
        assertEquals(
            SandboxEngineMode.PYTHON_CONSOLE,
            detectSandboxMode("python", "print('Hola mundo')")
        )

        // Formatos que no son HTML ni Python (.py) no deben habilitar el botón Play
        assertFalse(isExecutableCodeLanguage("text", "Resumen de documento adjunto"))
        assertFalse(isExecutableCodeLanguage("kotlin", "fun main() { println(\"Hola\") }"))
        assertFalse(isExecutableCodeLanguage("json", "{\"ok\": true}"))
        assertFalse(isExecutableCodeLanguage("bash", "echo hola"))

        // Bloques sin etiqueta de lenguaje con texto plano se clasifican como text (no ejecutables)
        val plainFenceSegments = parseMessageSegments("```\n línea 1\n línea 2\n```")
        val plainCodeBlock = plainFenceSegments.filterIsInstance<MessageContentSegment.CodeBlock>().first()
        assertEquals("text", plainCodeBlock.language)
        assertFalse(isExecutableCodeLanguage(plainCodeBlock.language, plainCodeBlock.code))
    }
}
