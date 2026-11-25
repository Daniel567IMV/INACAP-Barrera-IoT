package com.example.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.ui.theme.InacapCharcoal
import com.example.ui.theme.InacapRed
import com.example.ui.theme.IoTFreeGreen
import com.example.ui.theme.IoTFreeGreenBg
import com.example.ui.theme.IoTManualAmber
import com.example.ui.theme.IoTManualAmberBg
import com.example.ui.theme.IoTOccupiedRed
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.viewmodel.MainUiState

/**
 * Tarjeta de Persistencia Local y Sincronización Room (SQLite) + Configuración Segura ISO 27400
 * + Bitácora en vivo con marca de hora local legible en cada telemetría, ACK y ERR.
 */
@Composable
fun RoomSyncAndAuditCard(
    uiState: MainUiState,
    onSyncPendingRoom: () -> Unit,
    onClearSyncedRoom: () -> Unit,
    onSaveSecureConfig: (syncEndpointUrl: String, sharedOtToken: String) -> Unit,
    onClearAuditLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showSecurityConfig by rememberSaveable { mutableStateOf(false) }
    var endpointUrlInput by rememberSaveable(uiState.configuredSyncEndpoint) {
        mutableStateOf(uiState.configuredSyncEndpoint)
    }
    var sharedTokenInput by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Tarjeta Room SQLite + Cola Offline con deduplicación (`synced: Boolean`)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudSync,
                            contentDescription = "Room SQLite Sync",
                            tint = InacapRed
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Almacenamiento Temporal Room (SQLite)",
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                text = "Pendientes (synced=false): ${uiState.roomPendingCount} • Sincronizados: ${uiState.roomSyncedCount}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("room_sync_counter")
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = onClearSyncedRoom,
                            modifier = Modifier.testTag("btn_clear_synced_room")
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = "Limpiar historial sincronizado"
                            )
                        }
                        Button(
                            onClick = onSyncPendingRoom,
                            enabled = !uiState.isSyncingRoom && uiState.roomPendingCount > 0,
                            colors = ButtonDefaults.buttonColors(containerColor = IoTFreeGreen),
                            modifier = Modifier.testTag("btn_sync_room_now")
                        ) {
                            Text(if (uiState.isSyncingRoom) "Sync..." else "Sincronizar")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (uiState.roomRecentRecords.isEmpty()) {
                    Text(
                        text = "Sin registros aún en Room SQLite. Cuando el enlace caiga o se envíen eventos se almacenarán con clave única deduplicada.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        uiState.roomRecentRecords.take(6).forEach { rec ->
                            val badgeBg = if (rec.synced) IoTFreeGreenBg else IoTManualAmberBg
                            val badgeColor = if (rec.synced) IoTFreeGreen else IoTManualAmber
                            Surface(
                                color = badgeBg,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "${rec.recordType} • ID:${rec.eventId.take(8)}",
                                            fontFamily = JetBrainsMonoFamily,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = InacapCharcoal
                                        )
                                        Text(
                                            text = if (rec.synced) {
                                                "SYNCED=true (${rec.syncTarget ?: "OK"})"
                                            } else {
                                                "SYNCED=false (reintentos:${rec.retryCount})"
                                            },
                                            fontFamily = JetBrainsMonoFamily,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = badgeColor
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "[${rec.formattedCreatedTime()}] ${rec.payloadJson.take(96)}",
                                        fontFamily = JetBrainsMonoFamily,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = InacapCharcoal
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showSecurityConfig = !showSecurityConfig }
                        .padding(vertical = 4.dp)
                        .testTag("toggle_secure_sync_config"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = "Configuración segura ISO 27400",
                            tint = InacapRed,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Destino de Sincronización y Token OT (ISO 27400)",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                    Text(
                        text = if (showSecurityConfig) "Ocultar" else "Configurar",
                        style = MaterialTheme.typography.labelLarge,
                        color = InacapRed
                    )
                }

                if (showSecurityConfig) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Valores opcionales almacenados exclusivamente en EncryptedSharedPreferences (AES256-GCM), sin secretos hardcodeados en el código.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = endpointUrlInput,
                        onValueChange = { endpointUrlInput = it },
                        label = { Text("URL Endpoint Sync HTTP/HTTPS (opcional)") },
                        placeholder = { Text("https://collector.local/api/parking-sync") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("sync_endpoint_input")
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = sharedTokenInput,
                        onValueChange = { sharedTokenInput = it },
                        label = {
                            Text(
                                if (uiState.hasCustomOtToken) {
                                    "Nuevo Token OT Compartido (ya configurado, mín. 8 chars)"
                                } else {
                                    "Token OT Compartido con ESP32 (opcional, mín. 8 chars)"
                                }
                            )
                        },
                        leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("shared_ot_token_input")
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            onSaveSecureConfig(endpointUrlInput, sharedTokenInput)
                            sharedTokenInput = ""
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("btn_save_secure_config")
                    ) {
                        Text("Guardar en Almacén Cifrado AES256-GCM")
                    }
                    if (!uiState.configFeedbackMessage.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = uiState.configFeedbackMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (uiState.configFeedbackMessage.contains("inválida", ignoreCase = true) ||
                                uiState.configFeedbackMessage.contains("mínimo", ignoreCase = true)
                            ) IoTOccupiedRed else IoTFreeGreen
                        )
                    }
                }
            }
        }

        // 2. Monitor de Protocolo en Vivo con Marca de Hora Local Legible
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = null,
                            tint = InacapCharcoal,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "BITÁCORA EN VIVO (TELEMETRÍA, ACK y ERR)",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                    Text(
                        text = "Limpiar",
                        style = MaterialTheme.typography.labelLarge,
                        color = InacapRed,
                        modifier = Modifier
                            .clickable { onClearAuditLogs() }
                            .padding(4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Surface(
                    color = InacapCharcoal,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("live_protocol_audit_log")
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        if (uiState.auditLogs.isEmpty()) {
                            Text(
                                text = "Esperando tramas JSON delimitadas por '\\n' con marca de hora local...",
                                fontFamily = JetBrainsMonoFamily,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF9E9E9E)
                            )
                        } else {
                            uiState.auditLogs.take(14).forEach { log ->
                                val logColor = when {
                                    log.isError || log.category == "ERR" -> Color(0xFFFF8A80)
                                    log.isAck || log.category == "ACK" -> Color(0xFF81C784)
                                    log.category == "TEL" -> Color(0xFF64B5F6)
                                    log.category == "SYNC" -> Color(0xFFFFD54F)
                                    else -> Color(0xFFE0E0E0)
                                }
                                Text(
                                    text = "[${log.timestamp}] [${log.category}] ${log.rawFrame}",
                                    fontFamily = JetBrainsMonoFamily,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = logColor,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
