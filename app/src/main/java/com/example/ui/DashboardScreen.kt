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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terminal
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.ConnectionState
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
import com.example.ui.theme.JetBrainsMonoFamily
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
    onRefreshDevices: () -> Unit,
    onOpenBarrier: () -> Unit,
    onCloseBarrier: () -> Unit,
    onSetAutoMode: () -> Unit,
    onRequestStatus: () -> Unit,
    onSensorDistanceSliderChange: (Float) -> Unit,
    onFlushOfflineQueue: () -> Unit,
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
                                text = "INACAP • BARRERA IoT/OT",
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
                                    text = "ISO 27400",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = "Operador: ${uiState.activeOperator} • Token: ${uiState.sessionToken}",
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
                // 1. Estado Semántico de Conexión Inalámbrica
                item {
                    ConnectionStatusBanner(connectionState = uiState.connectionState)
                }

                // 2. Banner de Permisos en Runtime (Rúbrica B: Manejo de denegación sin crasheos)
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

                // 3. Selector de Rol del Dispositivo (2 Teléfonos Físicos o Celular <-> ESP32)
                item {
                    RoleSelectorCard(
                        selectedRole = uiState.selectedRole,
                        onSelectRole = onSelectRole
                    )
                }

                // 4. Gemelo Digital Animado (Barrera + Sensor Ultrasónico HC-SR04) + LCD 16x2 I2C
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

                // 5. Panel de Telemetría y Control (según Rol activo)
                item {
                    TelemetryIndicatorsGrid(uiState = uiState)
                }

                if (uiState.selectedRole == DeviceRole.CONTROL_STATION) {
                    item {
                        ControlStationActuatorCard(
                            uiState = uiState,
                            onOpenBarrier = onOpenBarrier,
                            onCloseBarrier = onCloseBarrier,
                            onSetAutoMode = onSetAutoMode,
                            onRequestStatus = onRequestStatus
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

                // 6. Configuración de Transporte Inalámbrico y Tolerancia a Fallos (IOException / Backoff)
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
                        onRefreshDevices = onRefreshDevices
                    )
                }

                // 7. Cola de Sincronización Temporal Cifrada (Offline Sync) y Auditoría de Tramas JSON
                item {
                    OfflineSyncAndAuditCard(
                        uiState = uiState,
                        onFlushOfflineQueue = onFlushOfflineQueue,
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
                                    "Cliente: Monitorea y envía comandos"
                                } else {
                                    "Servidor / ESP32: Simula HC-SR04 y Servo"
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
            // 1. Distancia HC-SR04
            TelemetryMetricCard(
                title = "DISTANCIA HC-SR04",
                value = "${uiState.distanceCm} cm",
                subtitle = "Trig: Pin 5 • Echo: Pin 18",
                accentColor = if (uiState.distanceCm in 1..9) IoTOccupiedRed else IoTFreeGreen,
                bgColor = MaterialTheme.colorScheme.surface,
                valueTestTag = "distance_value_text",
                modifier = Modifier.weight(1f)
            )

            // 2. Estado del Estacionamiento
            TelemetryMetricCard(
                title = "ESTACIONAMIENTO",
                value = uiState.parkingStatus,
                subtitle = if (isOccupied) "Vehículo < 10 cm" else "Espacio disponible ≥ 10 cm",
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
            // 3. Estado del Servomotor (Barrera)
            TelemetryMetricCard(
                title = "SERVOMOTOR (PIN 15)",
                value = if (isBarrierOpen) "ABIERTA (90°)" else "CERRADA (0°)",
                subtitle = "PWM ESP32Servo • ${uiState.barrierAngle}°",
                accentColor = if (isBarrierOpen) IoTFreeGreen else IoTOccupiedRed,
                bgColor = MaterialTheme.colorScheme.surface,
                valueTestTag = "barrier_status_text",
                modifier = Modifier.weight(1f)
            )

            // 4. Modo de Operación
            TelemetryMetricCard(
                title = "MODO OPERACIÓN",
                value = uiState.operationMode,
                subtitle = if (isManual) "Forzado por Operador" else "Controlado por HC-SR04",
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

@Composable
private fun ControlStationActuatorCard(
    uiState: MainUiState,
    onOpenBarrier: () -> Unit,
    onCloseBarrier: () -> Unit,
    onSetAutoMode: () -> Unit,
    onRequestStatus: () -> Unit
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
                Column {
                    Text(
                        text = "Comandos de Actuador Remoto (Estación de Control)",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = "Envía tramas JSON firmadas con token ${uiState.sessionToken} en Dispatchers.IO",
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
                    Text("MODO AUTOMÁTICO", fontWeight = FontWeight.Bold)
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
                    Text("SOLICITAR ESTADO", fontWeight = FontWeight.Bold)
                }
            }

            // Control rápido del sensor ultrasónico integrado para pruebas ágiles en 1 o 2 equipos
            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Nota: Cambia a la pestaña '2. Nodo Sensor OT' arriba para mover el Slider del sensor ultrasónico HC-SR04 (0-100 cm) o usar un 2° Android como servidor.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
                        text = "Regla física: En modo AUTO, >0 y <10 cm -> OCUPADO (0°) | ≥10 cm -> LIBRE (90°)",
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
                    label = { Text("5 cm (Vehículo <10cm -> OCUPADO 0°)") },
                    modifier = Modifier.testTag("preset_5cm_chip")
                )
                AssistChip(
                    onClick = { onDistanceChange(24f) },
                    label = { Text("24 cm (Estacionamiento LIBRE 90°)") },
                    modifier = Modifier.testTag("preset_24cm_chip")
                )
                AssistChip(
                    onClick = { onDistanceChange(75f) },
                    label = { Text("75 cm (Despejado)") }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Override Local del Nodo Sensor:",
                style = MaterialTheme.typography.labelLarge
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onOpenBarrier,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("90° Manual")
                }
                OutlinedButton(
                    onClick = onCloseBarrier,
                    modifier = Modifier.weight(1f)
                ) {
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Router,
                        contentDescription = "Conectividad Inalámbrica",
                        tint = InacapRed
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Enlace de Sockets Inalámbricos (Dispatchers.IO)",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = "IP Local de este equipo: ${uiState.localIpAddress}:8888",
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
            Text(
                text = uiState.selectedTransport.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(10.dp))

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
                        Text(
                            text = "Dispositivos Emparejados Detectados:",
                            style = MaterialTheme.typography.labelLarge
                        )
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
                            text = "Modo Loopback activo: Abre un ServerSocket TCP real en 127.0.0.1:8888 y conecta el cliente en vivo para probar comandos, LCD, rotación y reconexión automática en 1 solo dispositivo.",
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
                    "INICIAR SERVIDOR OT"
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
                    Text("CERRAR SOCKET")
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Botón de prueba de Tolerancia a Fallos (Rúbrica E: Desconexión forzada y Reconexión con Backoff)
            OutlinedButton(
                onClick = onSimulateAbruptDisconnect,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("btn_simulate_disconnect")
            ) {
                Icon(
                    imageVector = Icons.Default.Bolt,
                    contentDescription = null,
                    tint = IoTManualAmber
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Simular Desconexión Forzada (IOException + Backoff)",
                    color = InacapCharcoal,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun OfflineSyncAndAuditCard(
    uiState: MainUiState,
    onFlushOfflineQueue: () -> Unit,
    onClearAuditLogs: () -> Unit
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CloudSync,
                        contentDescription = "Sincronización Offline",
                        tint = InacapRed
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Cola Temporal Cifrada (Offline Sync) & Tramas JSON",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = "Pendientes: ${uiState.offlineSyncQueue.size} • Sincronizados al reconectar: ${uiState.syncedEventsCount}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("offline_queue_counter")
                        )
                    }
                }

                if (uiState.offlineSyncQueue.isNotEmpty()) {
                    Button(
                        onClick = onFlushOfflineQueue,
                        colors = ButtonDefaults.buttonColors(containerColor = IoTFreeGreen),
                        modifier = Modifier.testTag("btn_flush_offline_queue")
                    ) {
                        Text("Sincronizar")
                    }
                }
            }

            if (uiState.offlineSyncQueue.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    color = IoTManualAmberBg,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "Eventos Retenidos en EncryptedSharedPreferences:",
                            style = MaterialTheme.typography.labelLarge,
                            color = InacapCharcoal
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        uiState.offlineSyncQueue.takeLast(4).forEach { item ->
                            Text(
                                text = "• [${item.formattedTime()}] ${item.type}: ${item.payloadJson}",
                                fontFamily = JetBrainsMonoFamily,
                                style = MaterialTheme.typography.labelSmall,
                                color = InacapCharcoal
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

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
                        text = "MONITOR DE PROTOCOLO SEGURO (JSON + \\n)",
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
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    if (uiState.auditLogs.isEmpty()) {
                        Text(
                            text = "Esperando tramas JSON delimitadas por '\\n'...",
                            fontFamily = JetBrainsMonoFamily,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF9E9E9E)
                        )
                    } else {
                        uiState.auditLogs.take(10).forEach { log ->
                            val logColor = when {
                                log.isWarning -> Color(0xFFFFB74D)
                                log.direction.startsWith("TX") -> Color(0xFF81C784)
                                log.direction.startsWith("RX") -> Color(0xFF64B5F6)
                                else -> Color(0xFFE0E0E0)
                            }
                            Text(
                                text = "[${log.timestamp}] ${log.direction} ${log.rawFrame}",
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
