package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.PauseCircleOutline
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.DeviceRole
import com.example.data.TransportMode
import com.example.ui.theme.InacapCharcoal
import com.example.ui.theme.InacapRed
import com.example.ui.theme.InacapRedDark
import com.example.ui.theme.IoTFreeGreen
import com.example.ui.theme.IoTFreeGreenBg
import com.example.ui.theme.IoTManualAmber
import com.example.ui.theme.IoTManualAmberBg
import com.example.ui.theme.IoTOccupiedRed
import com.example.ui.theme.IoTOccupiedRedBg
import com.example.viewmodel.MainUiState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(
    uiState: MainUiState,
    onRequestPermissions: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onSelectRole: (DeviceRole) -> Unit,
    onSelectTransport: (TransportMode) -> Unit,
    onSelectBtAddress: (String) -> Unit,
    onUpdateTcpEndpoint: (ip: String, port: String) -> Unit,
    onConnectOrStartSocket: () -> Unit,
    onDisconnectSocket: () -> Unit,
    onSimulateAbruptDisconnect: () -> Unit,
    onToggleTelemetryPause: () -> Unit,
    onRefreshDevices: () -> Unit,
    onOpenBarrier: () -> Unit,
    onCloseBarrier: () -> Unit,
    onSetAutoMode: () -> Unit,
    onRequestStatus: () -> Unit,
    onSendValidatedAngle: (String) -> Unit,
    onSendValidatedCapacity: (capacity: String, freeSlots: String) -> Unit,
    onSendProtocolFaultProbe: (String) -> Unit,
    onFlushEsp32Buffer: () -> Unit,
    onSensorDistanceSliderChange: (Float) -> Unit,
    onSyncPendingRoom: () -> Unit,
    onClearSyncedRoom: () -> Unit,
    onSaveSecureConfig: (syncEndpointUrl: String, sharedOtToken: String) -> Unit,
    onClearAuditLogs: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isBluetoothWithoutPermission =
        uiState.selectedTransport == TransportMode.BLUETOOTH_RFCOMM && !uiState.hasWirelessPermissions

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = InacapRed,
                    titleContentColor = Color.White,
                    actionIconContentColor = Color.White
                ),
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "INACAP • BARRERA IoT",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                color = InacapCharcoal,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = "ES3 • ISO 27400",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = "Operador: ${uiState.activeOperator} • Token: ${uiState.sessionTokenMasked}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.9f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onLogout,
                        modifier = Modifier.testTag("logout_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Logout,
                            contentDescription = "Cerrar Sesión Segura"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 840.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. Panel de Entrada: Plazas Libres / Capacidad Total + Marca de Hora de Telemetría
                item {
                    ParkingEntranceHeaderCard(
                        freeSlots = uiState.freeSlots,
                        totalCapacity = uiState.totalCapacity,
                        parkingStatus = uiState.parkingStatus,
                        lastTelemetryTimestamp = uiState.lastTelemetryTimestamp
                    )
                }

                // 2. Estado de Salud de Conexión (4 estados: CONECTADO, RECONECTANDO, DESCONECTADO, SIN DATOS RECIENTES)
                item {
                    ConnectionStatusBanner(
                        connectionState = uiState.connectionState,
                        connectionHealth = uiState.connectionHealth,
                        secondsSinceLastTelemetry = uiState.secondsSinceLastTelemetry
                    )
                }

                // 3. Tira de Respuestas ACK y ERR con Marca de Hora Local Legible
                if (uiState.lastAckSummary != null || uiState.lastErrSummary != null) {
                    item {
                        AckErrStatusStrip(
                            lastAckSummary = uiState.lastAckSummary,
                            lastAckTimestamp = uiState.lastAckTimestamp,
                            lastErrSummary = uiState.lastErrSummary,
                            lastErrTimestamp = uiState.lastErrTimestamp
                        )
                    }
                }

                // 4. Banner de Permisos en Runtime (Sin crasheos ante denegación)
                if (!uiState.hasWirelessPermissions || uiState.permissionDeniedMessage != null) {
                    item {
                        PermissionsRuntimeCard(
                            message = uiState.permissionDeniedMessage
                                ?: "Permisos inalámbricos Bluetooth pendientes de autorización en Runtime.",
                            onRequestPermissions = onRequestPermissions,
                            onOpenAppSettings = onOpenAppSettings
                        )
                    }
                }

                // 5. Selector de Rol del Dispositivo (Estación de Control vs Nodo Sensor OT)
                item {
                    RoleSelectorCard(
                        selectedRole = uiState.selectedRole,
                        onSelectRole = onSelectRole
                    )
                }

                // 6. Gemelo Digital Animado (Barrera + HC-SR04) + Display LCD I2C 16x2
                item {
                    AnimatedSmartBarrierCanvas(
                        distanceCm = uiState.distanceCm,
                        barrierAngle = uiState.barrierAngle,
                        parkingStatus = uiState.parkingStatus,
                        operationMode = uiState.operationMode
                    )
                }

                item {
                    VirtualI2cLcdDisplay(
                        line1 = uiState.lcdLine1,
                        line2 = uiState.lcdLine2,
                        crcStatus = uiState.lastCrcStatus
                    )
                }

                // 7. Grilla de Indicadores de Estacionamiento Inteligente
                item {
                    TelemetryIndicatorsGrid(uiState = uiState)
                }

                // 8. Control de Actuador con Validación de Parámetros / Rangos OR Simulador Nodo Sensor
                if (uiState.selectedRole == DeviceRole.CONTROL_STATION) {
                    item {
                        ControlStationActuatorCard(
                            uiState = uiState,
                            onOpenBarrier = onOpenBarrier,
                            onCloseBarrier = onCloseBarrier,
                            onSetAutoMode = onSetAutoMode,
                            onRequestStatus = onRequestStatus,
                            onSendValidatedAngle = onSendValidatedAngle,
                            onSendValidatedCapacity = onSendValidatedCapacity,
                            onSendProtocolFaultProbe = onSendProtocolFaultProbe,
                            onFlushEsp32Buffer = onFlushEsp32Buffer
                        )
                    }
                } else {
                    item {
                        SensorNodeSimulatorCard(
                            uiState = uiState,
                            onDistanceChange = onSensorDistanceSliderChange,
                            onOpenBarrier = onOpenBarrier,
                            onCloseBarrier = onCloseBarrier,
                            onSetAutoMode = onSetAutoMode
                        )
                    }
                }

                // 9. Conectividad Inalámbrica y Pruebas de Reconexión Backoff / Sin Datos Recientes
                item {
                    WirelessTransportCard(
                        uiState = uiState,
                        isConnectionDisabledByPermissions = isBluetoothWithoutPermission,
                        onSelectTransport = onSelectTransport,
                        onSelectBtAddress = onSelectBtAddress,
                        onUpdateTcpEndpoint = onUpdateTcpEndpoint,
                        onConnectOrStartSocket = onConnectOrStartSocket,
                        onDisconnectSocket = onDisconnectSocket,
                        onSimulateAbruptDisconnect = onSimulateAbruptDisconnect,
                        onToggleTelemetryPause = onToggleTelemetryPause,
                        onRefreshDevices = onRefreshDevices
                    )
                }

                // 10. Base de Datos Room SQLite (Offline Sync deduplicado) + Bitácora con Timestamp
                item {
                    RoomSyncAndAuditCard(
                        uiState = uiState,
                        onSyncPendingRoom = onSyncPendingRoom,
                        onClearSyncedRoom = onClearSyncedRoom,
                        onSaveSecureConfig = onSaveSecureConfig,
                        onClearAuditLogs = onClearAuditLogs
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionsRuntimeCard(
    message: String,
    onRequestPermissions: () -> Unit,
    onOpenAppSettings: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("permissions_warning_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = IoTManualAmberBg)
    ) {
        Column(
            modifier = Modifier
                .border(1.5.dp, IoTManualAmber, RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.WarningAmber,
                    contentDescription = "Advertencia de Permisos",
                    tint = IoTManualAmber,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Gestión de Permisos en Runtime (ISO/IEC 27400)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = InacapCharcoal
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = InacapCharcoal
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onRequestPermissions,
                    colors = ButtonDefaults.buttonColors(containerColor = InacapRed),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("retry_permissions_button")
                ) {
                    Text("Solicitar Permisos")
                }
                OutlinedButton(
                    onClick = onOpenAppSettings,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("open_settings_button")
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Configuración")
                }
            }
        }
    }
}

@Composable
private fun RoleSelectorCard(
    selectedRole: DeviceRole,
    onSelectRole: (DeviceRole) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = InacapCharcoal),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "ROL DEL DISPOSITIVO EN LA ARQUITECTURA (2 TELÉFONOS O ESP32)",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFBDBDBD),
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                DeviceRole.entries.forEach { role ->
                    val isSelected = selectedRole == role
                    val tag = if (role == DeviceRole.CONTROL_STATION) {
                        "role_control_station_tab"
                    } else {
                        "role_sensor_node_tab"
                    }
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSelectRole(role) }
                            .testTag(tag),
                        color = if (isSelected) InacapRed else Color(0xFF2C2C2A),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (role == DeviceRole.CONTROL_STATION) {
                                        Icons.Default.SettingsRemote
                                    } else {
                                        Icons.Default.DeveloperBoard
                                    },
                                    contentDescription = role.title,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (role == DeviceRole.CONTROL_STATION) {
                                        "1. Estación Control"
                                    } else {
                                        "2. Nodo Sensor OT"
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (role == DeviceRole.CONTROL_STATION) {
                                    "Cliente: Monitorea y valida comandos"
                                } else {
                                    "Servidor / ESP32: HC-SR04 y Servo"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.85f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TelemetryIndicatorsGrid(uiState: MainUiState) {
    val isOccupied = uiState.parkingStatus.equals("OCUPADO", ignoreCase = true)
    val isManual = uiState.operationMode.equals("MANUAL", ignoreCase = true)
    val isBarrierOpen = uiState.barrierAngle >= 45

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TelemetryMetricCard(
                title = "DISTANCIA HC-SR04",
                value = "${uiState.distanceCm} cm",
                subtitle = "Trig: Pin 5 • Echo: Pin 18",
                accentColor = if (uiState.distanceCm in 1..9) IoTOccupiedRed else IoTFreeGreen,
                bgColor = MaterialTheme.colorScheme.surface,
                valueTestTag = "distance_value_text",
                modifier = Modifier.weight(1f)
            )
            TelemetryMetricCard(
                title = "ESTADO DE PLAZA",
                value = uiState.parkingStatus,
                subtitle = if (isOccupied) "Vehículo < 10 cm" else "Despejada ≥ 10 cm",
                accentColor = if (isOccupied) IoTOccupiedRed else IoTFreeGreen,
                bgColor = if (isOccupied) IoTOccupiedRedBg else IoTFreeGreenBg,
                valueTestTag = "parking_status_text",
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TelemetryMetricCard(
                title = "BARRERA SERVO (PIN 15)",
                value = if (isBarrierOpen) "ABIERTA (90°)" else "CERRADA (0°)",
                subtitle = "Ángulo actual: ${uiState.barrierAngle}°",
                accentColor = if (isBarrierOpen) IoTFreeGreen else IoTOccupiedRed,
                bgColor = MaterialTheme.colorScheme.surface,
                valueTestTag = "barrier_status_text",
                modifier = Modifier.weight(1f)
            )
            TelemetryMetricCard(
                title = "MODO OPERACIÓN",
                value = uiState.operationMode,
                subtitle = if (isManual) "Forzado por Operador" else "Automático HC-SR04",
                accentColor = if (isManual) IoTManualAmber else IoTFreeGreen,
                bgColor = if (isManual) IoTManualAmberBg else MaterialTheme.colorScheme.surface,
                valueTestTag = "mode_status_text",
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun TelemetryMetricCard(
    title: String,
    value: String,
    subtitle: String,
    accentColor: Color,
    bgColor: Color,
    valueTestTag: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(accentColor)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF424242),
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                color = accentColor,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag(valueTestTag)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF616161)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlStationActuatorCard(
    uiState: MainUiState,
    onOpenBarrier: () -> Unit,
    onCloseBarrier: () -> Unit,
    onSetAutoMode: () -> Unit,
    onRequestStatus: () -> Unit,
    onSendValidatedAngle: (String) -> Unit,
    onSendValidatedCapacity: (capacity: String, freeSlots: String) -> Unit,
    onSendProtocolFaultProbe: (String) -> Unit,
    onFlushEsp32Buffer: () -> Unit
) {
    var angleInput by rememberSaveable { mutableStateOf("90") }
    var capacityInput by rememberSaveable(uiState.totalCapacity) {
        mutableStateOf(uiState.totalCapacity.toString())
    }
    var freeSlotsInput by rememberSaveable(uiState.freeSlots) {
        mutableStateOf(uiState.freeSlots.toString())
    }

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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Control de Barrera y Comandos Validados",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = "Tramas JSON + '\\n' con validación de rangos en UI y verificación ACK/ERR",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = "Comandos firmados",
                    tint = InacapRed
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onOpenBarrier,
                    colors = ButtonDefaults.buttonColors(containerColor = IoTFreeGreen),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .testTag("btn_open_barrier")
                ) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("ABRIR (90°)", fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = onCloseBarrier,
                    colors = ButtonDefaults.buttonColors(containerColor = IoTOccupiedRed),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .testTag("btn_close_barrier")
                ) {
                    Icon(Icons.Default.ArrowDownward, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("CERRAR (0°)", fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onSetAutoMode,
                    colors = ButtonDefaults.buttonColors(containerColor = InacapCharcoal),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("btn_auto_mode")
                ) {
                    Icon(Icons.Default.AutoMode, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("MODO AUTO", fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = onRequestStatus,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("btn_request_status")
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("ESTADO", fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))

            // Validación previa en UI de parámetros/rangos (Requerimiento 1 ES3)
            Text(
                text = "Ajuste Validado de Ángulo y Plazas de Entrada",
                style = MaterialTheme.typography.labelLarge
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = angleInput,
                    onValueChange = { angleInput = it },
                    label = { Text("Ángulo (0° / 90°)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("custom_angle_input")
                )
                Button(
                    onClick = { onSendValidatedAngle(angleInput) },
                    colors = ButtonDefaults.buttonColors(containerColor = InacapRed),
                    modifier = Modifier
                        .height(52.dp)
                        .testTag("btn_send_custom_angle")
                ) {
                    Text("Validar y Enviar")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = freeSlotsInput,
                    onValueChange = { freeSlotsInput = it },
                    label = { Text("Plazas Libres") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("free_slots_input")
                )
                OutlinedTextField(
                    value = capacityInput,
                    onValueChange = { capacityInput = it },
                    label = { Text("Capacidad (1-200)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("capacity_input")
                )
                OutlinedButton(
                    onClick = { onSendValidatedCapacity(capacityInput, freeSlotsInput) },
                    modifier = Modifier
                        .height(52.dp)
                        .testTag("btn_update_capacity")
                ) {
                    Text("Aplicar")
                }
            }

            if (!uiState.commandValidationError.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = IoTOccupiedRedBg,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ui_validation_error_banner")
                ) {
                    Text(
                        text = "Validación UI: ${uiState.commandValidationError}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = IoTOccupiedRed,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Pruebas de Robustez ES3 (ERR y Descarte Seguro):",
                style = MaterialTheme.typography.labelLarge
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = { onSendProtocolFaultProbe("UNKNOWN_CMD") },
                    label = { Text("Probar ERR: Cmd inválido") },
                    modifier = Modifier.testTag("probe_unknown_cmd")
                )
                AssistChip(
                    onClick = { onSendProtocolFaultProbe("OUT_OF_RANGE") },
                    label = { Text("Probar ERR: Fuera de rango (180°)") },
                    modifier = Modifier.testTag("probe_out_of_range")
                )
                AssistChip(
                    onClick = { onSendProtocolFaultProbe("UNAUTHORIZED") },
                    label = { Text("Probar ERR: Token inválido") },
                    modifier = Modifier.testTag("probe_unauthorized")
                )
                AssistChip(
                    onClick = { onSendProtocolFaultProbe("CORRUPT_JSON") },
                    label = { Text("Probar Trama JSON corrupta") },
                    modifier = Modifier.testTag("probe_corrupt_json")
                )
                AssistChip(
                    onClick = onFlushEsp32Buffer,
                    label = { Text("Sincronizar Buffer ESP32") },
                    modifier = Modifier.testTag("btn_flush_esp32_buffer")
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SensorNodeSimulatorCard(
    uiState: MainUiState,
    onDistanceChange: (Float) -> Unit,
    onOpenBarrier: () -> Unit,
    onCloseBarrier: () -> Unit,
    onSetAutoMode: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Speed,
                    contentDescription = "Simulador HC-SR04",
                    tint = InacapRed,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Simulador de Nodo Sensor / Actuador OT (ESP32)",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = "Modo AUTO: >0 y <10 cm -> OCUPADO (0°) | ≥10 cm -> LIBRE (90°) + conteo de plazas",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Distancia Sensor Ultrasónico HC-SR04:",
                    style = MaterialTheme.typography.titleMedium
                )
                Surface(
                    color = if (uiState.distanceCm in 1..9) IoTOccupiedRed else IoTFreeGreen,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "${uiState.distanceCm} cm",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Slider(
                value = uiState.simulatedSensorDistanceCm,
                onValueChange = onDistanceChange,
                valueRange = 0f..100f,
                colors = SliderDefaults.colors(
                    thumbColor = InacapRed,
                    activeTrackColor = InacapRed
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("sensor_distance_slider")
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = { onDistanceChange(5f) },
                    label = { Text("5 cm (Vehículo <10cm -> OCUPADO 0°)") }
                )
                AssistChip(
                    onClick = { onDistanceChange(24f) },
                    label = { Text("24 cm (Plaza LIBRE 90°)") }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onOpenBarrier, modifier = Modifier.weight(1f)) {
                    Text("90° Manual")
                }
                OutlinedButton(onClick = onCloseBarrier, modifier = Modifier.weight(1f)) {
                    Text("0° Manual")
                }
                Button(
                    onClick = onSetAutoMode,
                    colors = ButtonDefaults.buttonColors(containerColor = InacapCharcoal),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Modo AUTO")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WirelessTransportCard(
    uiState: MainUiState,
    isConnectionDisabledByPermissions: Boolean,
    onSelectTransport: (TransportMode) -> Unit,
    onSelectBtAddress: (String) -> Unit,
    onUpdateTcpEndpoint: (ip: String, port: String) -> Unit,
    onConnectOrStartSocket: () -> Unit,
    onDisconnectSocket: () -> Unit,
    onSimulateAbruptDisconnect: () -> Unit,
    onToggleTelemetryPause: () -> Unit,
    onRefreshDevices: () -> Unit
) {
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
                        imageVector = Icons.Default.Router,
                        contentDescription = "Conectividad Inalámbrica",
                        tint = InacapRed
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Enlace Inalámbrico y Backoff (2s→4s→8s→16s→30s)",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = "IP Local: ${uiState.localIpAddress}:8888 • Reconexión en Dispatchers.IO sin reiniciar app",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = onRefreshDevices) {
                    Icon(Icons.Default.Refresh, contentDescription = "Actualizar dispositivos")
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TransportMode.entries.forEach { mode ->
                    FilterChip(
                        selected = uiState.selectedTransport == mode,
                        onClick = { onSelectTransport(mode) },
                        label = { Text(mode.label) },
                        leadingIcon = {
                            Icon(
                                imageVector = if (mode == TransportMode.BLUETOOTH_RFCOMM) {
                                    Icons.Default.Bluetooth
                                } else {
                                    Icons.Default.Router
                                },
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = InacapRed.copy(alpha = 0.14f),
                            selectedLabelColor = InacapRedDark
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            when (uiState.selectedTransport) {
                TransportMode.BLUETOOTH_RFCOMM -> {
                    OutlinedTextField(
                        value = uiState.selectedBtAddress,
                        onValueChange = onSelectBtAddress,
                        label = { Text("MAC Bluetooth ESP32 o 2° Android (SPP RFCOMM)") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("bt_mac_input")
                    )
                    if (uiState.pairedBtDevices.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            uiState.pairedBtDevices.forEach { dev ->
                                AssistChip(
                                    onClick = { onSelectBtAddress(dev.address) },
                                    label = { Text("${dev.name} (${dev.address})") }
                                )
                            }
                        }
                    }
                }

                TransportMode.TCP_WIFI_LAN -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = uiState.targetTcpIp,
                            onValueChange = { onUpdateTcpEndpoint(it, uiState.targetTcpPort) },
                            label = { Text("IP del ESP32 o 2° Teléfono") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(2f)
                                .testTag("tcp_ip_input")
                        )
                        OutlinedTextField(
                            value = uiState.targetTcpPort,
                            onValueChange = { onUpdateTcpEndpoint(uiState.targetTcpIp, it) },
                            label = { Text("Puerto") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("tcp_port_input")
                        )
                    }
                }

                TransportMode.LOCAL_LOOPBACK -> {
                    Surface(
                        color = Color(0xFFF0F4C3).copy(alpha = 0.45f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Loopback TCP (127.0.0.1:8888): Ejecuta sockets reales Cliente/Servidor en este equipo para probar telemetría, ACK/ERR, Room Sync y backoff exponencial.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = InacapCharcoal,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val actionLabel = if (uiState.selectedRole == DeviceRole.SENSOR_NODE &&
                    uiState.selectedTransport != TransportMode.LOCAL_LOOPBACK
                ) {
                    "INICIAR SERVIDOR"
                } else {
                    "CONECTAR SOCKET"
                }

                Button(
                    onClick = onConnectOrStartSocket,
                    enabled = !isConnectionDisabledByPermissions,
                    colors = ButtonDefaults.buttonColors(containerColor = InacapRed),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("btn_connect_socket")
                ) {
                    Icon(Icons.Default.Link, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(actionLabel, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = onDisconnectSocket,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("btn_disconnect_socket")
                ) {
                    Icon(Icons.Default.LinkOff, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("DESCONECTAR")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onSimulateAbruptDisconnect,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("btn_simulate_disconnect")
                ) {
                    Icon(Icons.Default.Bolt, contentDescription = null, tint = IoTManualAmber)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Corte Backoff", fontWeight = FontWeight.Bold, color = InacapCharcoal)
                }

                OutlinedButton(
                    onClick = onToggleTelemetryPause,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("btn_toggle_stale_test")
                ) {
                    Icon(
                        imageVector = if (uiState.isTelemetryFeedPausedForTest) {
                            Icons.Default.PlayCircleOutline
                        } else {
                            Icons.Default.PauseCircleOutline
                        },
                        contentDescription = null,
                        tint = InacapRed
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (uiState.isTelemetryFeedPausedForTest) "Reanudar Feed" else "Pausar (Sin Datos)",
                        fontWeight = FontWeight.Bold,
                        color = InacapCharcoal
                    )
                }
            }
        }
    }
}
