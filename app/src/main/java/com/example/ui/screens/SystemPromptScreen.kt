package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.domain.InferenceConfig
import com.example.domain.SystemPersona
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SystemPromptScreen(
    personas: List<SystemPersona>,
    selectedPersona: SystemPersona,
    customSystemPrompt: String,
    inferenceConfig: InferenceConfig,
    onSelectPersona: (SystemPersona) -> Unit,
    onUpdateCustomPrompt: (String) -> Unit,
    onUpdateConfig: (temperature: Float, maxTokens: Int, topK: Int, contextBufferSize: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Cabecera
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Psychology,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = "System Prompts & Contexto",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Define la personalidad de la IA y el tamaño del buffer de memoria a corto plazo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Lista de Perfiles Predefinidos
        Text(
            text = "Perfiles de Personalidad",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )

        personas.forEach { persona ->
            val isSelected = persona.id == selectedPersona.id
            OutlinedCard(
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(
                    width = if (isSelected) 2.dp else 1.dp,
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectPersona(persona) }
                    .testTag("persona_card_${persona.id}")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = persona.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = persona.badge,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = persona.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (isSelected) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "Seleccionado",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        // Editor de System Prompt en vivo
        ElevatedCard(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Instrucción de Sistema Activa (System Prompt)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = customSystemPrompt,
                    onValueChange = onUpdateCustomPrompt,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 110.dp)
                        .testTag("system_prompt_input"),
                    label = { Text("Se inyecta al inicio del prompt antes del historial") }
                )
            }
        }

        // Hiperparámetros y Buffer de Contexto
        ElevatedCard(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Parámetros de Inferencia y Memoria",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Buffer de Contexto (Últimos N mensajes)
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Buffer de Contexto (Memoria a corto plazo)",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "${inferenceConfig.contextBufferSize} mensajes",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = inferenceConfig.contextBufferSize.toFloat(),
                        onValueChange = {
                            onUpdateConfig(
                                inferenceConfig.temperature,
                                inferenceConfig.maxTokens,
                                inferenceConfig.topK,
                                it.roundToInt()
                            )
                        },
                        valueRange = 2f..12f,
                        steps = 9
                    )
                }

                // Temperature
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Temperatura (Creatividad vs Precisión)",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = String.format(Locale.US, "%.2f", inferenceConfig.temperature),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = inferenceConfig.temperature,
                        onValueChange = {
                            onUpdateConfig(
                                it,
                                inferenceConfig.maxTokens,
                                inferenceConfig.topK,
                                inferenceConfig.contextBufferSize
                            )
                        },
                        valueRange = 0.0f..1.2f
                    )
                }

                // Max Tokens
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Límite de Contexto / Respuesta (Max Tokens)",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "${inferenceConfig.maxTokens} tokens",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = inferenceConfig.maxTokens.toFloat(),
                        onValueChange = {
                            onUpdateConfig(
                                inferenceConfig.temperature,
                                (it / 128f).roundToInt() * 128,
                                inferenceConfig.topK,
                                inferenceConfig.contextBufferSize
                            )
                        },
                        valueRange = 256f..2048f,
                        steps = 13
                    )
                }
            }
        }
    }
}
