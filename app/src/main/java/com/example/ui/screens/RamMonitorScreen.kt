package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.domain.MemorySnapshot
import com.example.domain.ModelRuntimeInfo
import java.util.Locale

@Composable
fun RamMonitorScreen(
    memorySnapshot: MemorySnapshot,
    loadedModel: ModelRuntimeInfo?,
    onUnloadModel: () -> Unit,
    onForceGc: () -> Unit,
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
                Icons.Default.Memory,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = "Optimización de RAM & Telemetría",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Monitoreo en vivo de RAM física, Heap Nativo C++ (JNI/MediaPipe) y prevención de OOM.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Tarjeta Principal de RAM del Dispositivo
        ElevatedCard(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "RAM Física del Dispositivo",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = String.format(
                            Locale.US,
                            "%.0f%% en uso",
                            memorySnapshot.deviceRamUsageRatio * 100f
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (memorySnapshot.isLowMemory) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                LinearProgressIndicator(
                    progress = { memorySnapshot.deviceRamUsageRatio },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp),
                    color = if (memorySnapshot.isLowMemory) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MetricColumn(
                        label = "Disponible",
                        value = "${memorySnapshot.availableDeviceRamMb} MB"
                    )
                    MetricColumn(
                        label = "En Uso",
                        value = "${memorySnapshot.usedDeviceRamMb} MB"
                    )
                    MetricColumn(
                        label = "Total Dispositivo",
                        value = "${memorySnapshot.totalDeviceRamMb} MB"
                    )
                }
            }
        }

        // Tarjeta de Heap Nativo C++ (MediaPipe) vs Heap JVM
        ElevatedCard(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Memoria del Proceso (Native C++ & JVM)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MetricColumn(
                        label = "Native Heap (C++/LLM)",
                        value = "${memorySnapshot.nativeHeapAllocatedMb} MB"
                    )
                    MetricColumn(
                        label = "JVM Heap Usado",
                        value = "${memorySnapshot.jvmHeapUsedMb} / ${memorySnapshot.jvmHeapMaxMb} MB"
                    )
                    MetricColumn(
                        label = "Umbral LMK OS",
                        value = "${memorySnapshot.thresholdRamMb} MB"
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                Text(
                    text = if (loadedModel != null) {
                        "Modelo cargado en RAM: ${loadedModel.spec.name} (${loadedModel.spec.quantization})"
                    } else {
                        "Ningún modelo cargado actualmente en memoria nativa."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onUnloadModel,
                        enabled = loadedModel != null,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("unload_ram_action_button")
                    ) {
                        Icon(
                            Icons.Default.StopCircle,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Liberar Modelo")
                    }

                    FilledTonalButton(
                        onClick = onForceGc,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("force_gc_button")
                    ) {
                        Icon(
                            Icons.Default.CleaningServices,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Compactar GC")
                    }
                }
            }
        }

        // Tarjeta de Guardias de Protección Activos
        OutlinedCard(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Protecciones Anti-OOM Activas",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = "• Mutex Nativo (engineMutex): Serializa la inicialización y cierre de LlmInference para evitar doble reserva de RAM (+2.5 GB simultáneos).",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "• ComponentCallbacks2 (onTrimMemory): Si Android reporta TRIM_MEMORY_RUNNING_CRITICAL, el motor descarga automáticamente los pesos en C++ si no está generando.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "• Streaming Desacoplado: El flujo de tokens se emite sobre un StateFlow ligero sin escribir en SQLite/Room hasta completar la respuesta (done = true).",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun MetricColumn(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
    }
}
