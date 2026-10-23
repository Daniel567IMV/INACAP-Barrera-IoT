package com.example.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.CommandFrame
import com.example.data.ConnectionState
import com.example.data.DeviceRole
import com.example.data.OfflineSyncEvent
import com.example.data.PairedBtDevice
import com.example.data.ProtocolAuditLog
import com.example.data.TelemetryFrame
import com.example.data.TransportMode
import com.example.network.WirelessConnectionManager
import com.example.security.SecurityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Estado unificado de la interfaz de usuario sobreviviente a rotación de pantalla (MVVM).
 */
data class MainUiState(
    // Estado de Sesión y Seguridad (ISO/IEC 27400)
    val isAuthenticated: Boolean = false,
    val activeOperator: String = "",
    val sessionToken: String = "",
    val sha256Fingerprint: String = "",
    val authFeedbackMessage: String? = null,
    val isAuthError: Boolean = false,

    // Gestión de Permisos en Runtime
    val hasWirelessPermissions: Boolean = false,
    val permissionDeniedMessage: String? = null,

    // Rol y Conectividad Inalámbrica
    val selectedRole: DeviceRole = DeviceRole.CONTROL_STATION,
    val selectedTransport: TransportMode = TransportMode.LOCAL_LOOPBACK,
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val pairedBtDevices: List<PairedBtDevice> = emptyList(),
    val selectedBtAddress: String = "00:11:22:33:AA:BB",
    val targetTcpIp: String = "192.168.4.1",
    val targetTcpPort: String = "8888",
    val localIpAddress: String = "127.0.0.1",

    // Telemetría en Tiempo Real (Estación de Control y Nodo Sensor)
    val distanceCm: Int = 24,
    val parkingStatus: String = "LIBRE",       // "LIBRE" o "OCUPADO"
    val barrierAngle: Int = 90,                // 90° (Abierta) o 0° (Cerrada)
    val operationMode: String = "AUTO",        // "AUTO" o "MANUAL"
    val lastCrcStatus: String = "OK-A4F2",

    // Simulador Nodo Sensor OT (ESP32 Hardware Virtual / 2° Android)
    val simulatedSensorDistanceCm: Float = 24f,
    val lcdLine1: String = "DIST: 24cm  AUTO",
    val lcdLine2: String = "LIBRE   BAR: 90G",

    // Cola de Sincronización Offline Cifrada y Auditoría
    val offlineSyncQueue: List<OfflineSyncEvent> = emptyList(),
    val syncedEventsCount: Int = 0,
    val auditLogs: List<ProtocolAuditLog> = emptyList(),
    val statusBannerMessage: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val securityManager = SecurityManager(application)
    private val connectionManager = WirelessConnectionManager(application)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        // Restaurar sesión cifrada si "Mantener sesión" estaba activo y cargar cola Offline cifrada
        val savedQueue = securityManager.loadOfflineSyncQueue()
        val restoredSession = securityManager.restoreActiveSession()
        val localIp = connectionManager.getLocalIpAddress()

        _uiState.update { state ->
            state.copy(
                isAuthenticated = restoredSession != null,
                activeOperator = restoredSession?.operatorName ?: "",
                sessionToken = restoredSession?.sessionToken ?: "",
                sha256Fingerprint = restoredSession?.sha256Preview
                    ?: securityManager.getStoredHashFingerprint(SecurityManager.DEFAULT_OPERATOR_USER),
                offlineSyncQueue = savedQueue,
                localIpAddress = localIp
            )
        }

        if (restoredSession != null) {
            appendAuditLog(
                "SEC #",
                "Sesión restaurada desde EncryptedSharedPreferences (AES256-GCM) para '${restoredSession.operatorName}'"
            )
        }

        observeConnectionState()
        observeIncomingSocketFrames()
        observeLoopbackSensorServerFrames()
        observeSystemEvents()
        startPeriodicSensorTelemetryLoop()
    }

    /**
     * Observa cambios en el estado del socket y ejecuta sincronización Offline automática al conectar.
     */
    private fun observeConnectionState() {
        viewModelScope.launch {
            connectionManager.connectionState.collect { connState ->
                _uiState.update { it.copy(connectionState = connState) }
                if (connState is ConnectionState.Connected) {
                    flushOfflineSyncQueue()
                }
            }
        }
    }

    /**
     * Procesa tramas JSON entrantes desde el socket activo (en rol Cliente o Servidor).
     */
    private fun observeIncomingSocketFrames() {
        viewModelScope.launch(Dispatchers.IO) {
            connectionManager.incomingFrames.collect { rawLine ->
                appendAuditLog("RX <-", rawLine.trim())

                // 1. Verificar si es trama de Telemetría {"dist":8,"estado":"OCUPADO","barrera":0,"modo":"AUTO","crc":"OK"}
                val telemetry = TelemetryFrame.fromJsonLine(rawLine)
                if (telemetry != null) {
                    val (line1, line2) = telemetry.toLcdLines()
                    _uiState.update { state ->
                        state.copy(
                            distanceCm = telemetry.dist,
                            parkingStatus = telemetry.estado,
                            barrierAngle = telemetry.barrera,
                            operationMode = telemetry.modo,
                            lastCrcStatus = telemetry.crc,
                            lcdLine1 = line1,
                            lcdLine2 = line2
                        )
                    }
                    return@collect
                }

                // 2. Verificar si es trama de Comando {"cmd":"SET_BARRERA","val":90,"modo":"MANUAL","token":"..."}
                val command = CommandFrame.fromJsonLine(rawLine)
                if (command != null) {
                    handleIncomingCommandOnSensorNode(command, fromLoopback = false)
                }
            }
        }
    }

    /**
     * Procesa comandos recibidos por el servidor Loopback embebido (prueba en 1 equipo).
     */
    private fun observeLoopbackSensorServerFrames() {
        viewModelScope.launch(Dispatchers.IO) {
            connectionManager.incomingLoopbackServerFrames.collect { rawLine ->
                val command = CommandFrame.fromJsonLine(rawLine)
                if (command != null) {
                    handleIncomingCommandOnSensorNode(command, fromLoopback = true)
                }
            }
        }
    }

    private fun observeSystemEvents() {
        viewModelScope.launch {
            connectionManager.systemEvents.collect { msg ->
                val isWarn = msg.contains("IOException", ignoreCase = true) ||
                    msg.contains("Reconexión", ignoreCase = true) ||
                    msg.contains("Error", ignoreCase = true) ||
                    msg.contains("abrupto", ignoreCase = true)
                appendAuditLog("SYS !", msg, isWarning = isWarn)
                _uiState.update { it.copy(statusBannerMessage = msg) }
            }
        }
    }

    /**
     * Bucle no bloqueante (equivalente a `millis()` en el ESP32) que emite telemetría cada 1.2s
     * cuando el Nodo Sensor o el Loopback está activo.
     */
    private fun startPeriodicSensorTelemetryLoop() {
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(1200L)
                val state = _uiState.value
                if (!state.isAuthenticated) continue

                val conn = state.connectionState
                if (conn is ConnectionState.Connected) {
                    val frame = buildCurrentSensorTelemetryFrame()
                    val jsonLine = frame.toJsonLine()
                    if (state.selectedTransport == TransportMode.LOCAL_LOOPBACK) {
                        connectionManager.sendFromLoopbackSensorServer(jsonLine)
                    } else if (state.selectedRole == DeviceRole.SENSOR_NODE) {
                        connectionManager.sendFrame(jsonLine)
                        appendAuditLog("TX ->", jsonLine.trim())
                    }
                }
            }
        }
    }

    // =========================================================================
    // SECCIÓN A: LOGIN Y CREDENCIALES SEGURAS (ISO/IEC 27400)
    // =========================================================================

    fun login(username: String, plainPassword: String, keepSession: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            when (val result = securityManager.authenticateOperator(username, plainPassword, keepSession)) {
                is SecurityManager.AuthResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isAuthenticated = true,
                            activeOperator = result.operatorName,
                            sessionToken = result.sessionToken,
                            sha256Fingerprint = result.sha256Preview,
                            authFeedbackMessage = null,
                            isAuthError = false
                        )
                    }
                    appendAuditLog(
                        "SEC #",
                        "Login exitoso SHA-256+Salt [${result.sha256Preview}...] Token=${result.sessionToken}"
                    )
                    // Enlazar automáticamente al canal seleccionado para experiencia inmediata
                    if (_uiState.value.connectionState is ConnectionState.Disconnected &&
                        _uiState.value.selectedTransport == TransportMode.LOCAL_LOOPBACK
                    ) {
                        connectOrStartRoleSocket()
                    }
                }

                is SecurityManager.AuthResult.Error -> {
                    _uiState.update {
                        it.copy(
                            authFeedbackMessage = result.message,
                            isAuthError = true
                        )
                    }
                }
            }
        }
    }

    fun registerOrUpdateOperator(username: String, plainPassword: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = securityManager.registerOrUpdateCredentials(username, plainPassword)
            if (ok) {
                val fingerprint = securityManager.getStoredHashFingerprint(username)
                _uiState.update {
                    it.copy(
                        sha256Fingerprint = fingerprint,
                        authFeedbackMessage = "Credencial cifrada con SHA-256 + Salt aleatorio en EncryptedSharedPreferences.",
                        isAuthError = false
                    )
                }
                appendAuditLog("SEC #", "Operador '$username' registrado con Hash SHA-256 ($fingerprint)")
            } else {
                _uiState.update {
                    it.copy(
                        authFeedbackMessage = "El usuario debe tener al menos 3 caracteres y la clave 4+ caracteres.",
                        isAuthError = true
                    )
                }
            }
        }
    }

    fun logout() {
        connectionManager.disconnectGracefully()
        securityManager.logoutSecurely()
        _uiState.update {
            it.copy(
                isAuthenticated = false,
                activeOperator = "",
                sessionToken = "",
                authFeedbackMessage = "Sesión cerrada de forma segura. Token revocado."
            )
        }
        appendAuditLog("SEC #", "Cierre de sesión seguro ejecutado. Token eliminado de EncryptedSharedPreferences.")
    }

    // =========================================================================
    // SECCIÓN B: GESTIÓN DE PERMISOS EN RUNTIME (SIN CRASHEOS)
    // =========================================================================

    fun onPermissionsResult(allGranted: Boolean, deniedList: List<String>) {
        val paired = connectionManager.getPairedBluetoothDevices(allGranted)
        val defaultAddress = paired.firstOrNull()?.address ?: _uiState.value.selectedBtAddress
        _uiState.update { state ->
            state.copy(
                hasWirelessPermissions = allGranted,
                pairedBtDevices = paired,
                selectedBtAddress = defaultAddress,
                permissionDeniedMessage = if (allGranted) {
                    null
                } else {
                    "Permisos rechazados (${deniedList.joinToString(", ") { it.substringAfterLast('.') }}). " +
                        "La app sigue operativa bajo ISO 27400; habilite los permisos para escaneo/conexión Bluetooth RFCOMM."
                }
            )
        }
    }

    fun refreshPairedDevices() {
        val hasPerm = _uiState.value.hasWirelessPermissions
        val paired = connectionManager.getPairedBluetoothDevices(hasPerm)
        val localIp = connectionManager.getLocalIpAddress()
        _uiState.update { state ->
            state.copy(
                pairedBtDevices = paired,
                localIpAddress = localIp,
                selectedBtAddress = paired.firstOrNull()?.address ?: state.selectedBtAddress
            )
        }
    }

    // =========================================================================
    // SECCIÓN C: ROLES Y CONECTIVIDAD INALÁMBRICA (2 DISPOSITIVOS O ESP32)
    // =========================================================================

    fun selectRole(role: DeviceRole) {
        _uiState.update { it.copy(selectedRole = role) }
        appendAuditLog("SYS !", "Rol cambiado a: ${role.title}")
    }

    fun selectTransport(transport: TransportMode) {
        _uiState.update {
            it.copy(
                selectedTransport = transport,
                localIpAddress = connectionManager.getLocalIpAddress()
            )
        }
    }

    fun updateSelectedBtAddress(address: String) {
        _uiState.update { it.copy(selectedBtAddress = address) }
    }

    fun updateTargetTcpEndpoint(ip: String, port: String) {
        _uiState.update { it.copy(targetTcpIp = ip, targetTcpPort = port) }
    }

    fun connectOrStartRoleSocket() {
        val state = _uiState.value
        val port = state.targetTcpPort.toIntOrNull() ?: WirelessConnectionManager.DEFAULT_TCP_PORT

        if (state.selectedTransport == TransportMode.BLUETOOTH_RFCOMM && !state.hasWirelessPermissions) {
            _uiState.update {
                it.copy(
                    statusBannerMessage = "Otorgue los permisos Bluetooth antes de iniciar el socket RFCOMM."
                )
            }
            return
        }

        if (state.selectedRole == DeviceRole.SENSOR_NODE &&
            state.selectedTransport != TransportMode.LOCAL_LOOPBACK
        ) {
            connectionManager.startSensorServer(state.selectedTransport, port)
        } else {
            val target = when (state.selectedTransport) {
                TransportMode.BLUETOOTH_RFCOMM -> state.selectedBtAddress
                TransportMode.TCP_WIFI_LAN -> state.targetTcpIp
                TransportMode.LOCAL_LOOPBACK -> "127.0.0.1"
            }
            connectionManager.connectClient(state.selectedTransport, target, port)
        }
    }

    fun disconnectSocket() {
        connectionManager.disconnectGracefully()
    }

    /**
     * Dispara una caída abrupta del socket para demostrar en vivo el manejo de `IOException`,
     * cierre en `finally`, encolamiento Offline y reconexión con Backoff.
     */
    fun triggerSimulatedDisconnectFault() {
        connectionManager.simulateAbruptHardwareDisconnect()
    }

    // =========================================================================
    // SECCIÓN C & D: COMANDOS DESDE ESTACIÓN DE CONTROL Y NODO SENSOR OT
    // =========================================================================

    fun sendOpenBarrierCommand() {
        dispatchControlCommand(cmd = "SET_BARRERA", value = 90, modo = "MANUAL")
    }

    fun sendCloseBarrierCommand() {
        dispatchControlCommand(cmd = "SET_BARRERA", value = 0, modo = "MANUAL")
    }

    fun sendAutoModeCommand() {
        dispatchControlCommand(cmd = "SET_MODO", value = -1, modo = "AUTO")
    }

    fun sendRequestStatusCommand() {
        dispatchControlCommand(cmd = "REQ_STATUS", value = -1, modo = _uiState.value.operationMode)
    }

    private fun dispatchControlCommand(cmd: String, value: Int, modo: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val state = _uiState.value
            val token = state.sessionToken.ifBlank { "INA-AUTH27400" }
            val commandFrame = CommandFrame(
                cmd = cmd,
                value = value,
                modo = modo,
                token = token
            )
            val jsonLine = commandFrame.toJsonLine()
            val sent = connectionManager.sendFrame(jsonLine)

            if (sent) {
                appendAuditLog("TX ->", jsonLine.trim())
                _uiState.update {
                    it.copy(statusBannerMessage = "Comando $cmd enviado con token firmado ($token)")
                }
            } else {
                // Sin conexión activa: guardar en cola temporal cifrada para Offline Sync (Requerimiento D)
                enqueueOfflineEvent(
                    type = "COMANDO_PENDIENTE",
                    payloadJson = jsonLine.trim()
                )
                // Actualizar vista previa local mientras sincroniza
                applyCommandToLocalHardwareModel(commandFrame)
            }
        }
    }

    /**
     * Slider del Simulador "Nodo Sensor / Simulador OT" (0 a 100 cm):
     * Aplica exactamente la lógica del hardware ESP32:
     * - En modo AUTO: si dist > 0 y < 10 cm -> OCUPADO y Barrera 0° (Abajo).
     *                 si dist >= 10 cm -> LIBRE y Barrera 90° (Arriba).
     */
    fun onSimulatedSensorDistanceChanged(newDistanceFloat: Float) {
        val distCm = newDistanceFloat.toInt().coerceIn(0, 100)
        val currentMode = _uiState.value.operationMode

        val newParkingStatus = if (distCm in 1..9) "OCUPADO" else "LIBRE"
        val newBarrierAngle = if (currentMode == "AUTO") {
            if (distCm in 1..9) 0 else 90
        } else {
            _uiState.value.barrierAngle
        }

        val frame = TelemetryFrame(
            dist = distCm,
            estado = newParkingStatus,
            barrera = newBarrierAngle,
            modo = currentMode
        )
        val (line1, line2) = frame.toLcdLines()

        _uiState.update { state ->
            state.copy(
                simulatedSensorDistanceCm = newDistanceFloat,
                distanceCm = distCm,
                parkingStatus = newParkingStatus,
                barrierAngle = newBarrierAngle,
                lastCrcStatus = TelemetryFrame.computeTelemetryCrc(distCm, newParkingStatus, newBarrierAngle, currentMode),
                lcdLine1 = line1,
                lcdLine2 = line2
            )
        }

        // Transmitir inmediatamente por el socket si está conectado, o encolar si está en reconexión
        viewModelScope.launch(Dispatchers.IO) {
            val jsonLine = frame.toJsonLine()
            val state = _uiState.value
            val sent = if (state.selectedTransport == TransportMode.LOCAL_LOOPBACK) {
                connectionManager.sendFromLoopbackSensorServer(jsonLine)
            } else {
                connectionManager.sendFrame(jsonLine)
            }
            if (!sent && state.connectionState is ConnectionState.Reconnecting) {
                enqueueOfflineEvent(
                    type = "TELEMETRIA_RETENIDA",
                    payloadJson = jsonLine.trim()
                )
            }
        }
    }

    /**
     * Ejecuta el comando recibido en el Nodo Sensor (2° Android o Loopback Server)
     * validando primero el token bajo ISO/IEC 27400.
     */
    private suspend fun handleIncomingCommandOnSensorNode(
        command: CommandFrame,
        fromLoopback: Boolean
    ) {
        if (!securityManager.isValidCommandToken(command.token)) {
            appendAuditLog(
                "SEC #",
                "Comando rechazado: Token inválido '${command.token}' (ISO/IEC 27400)",
                isWarning = true
            )
            return
        }

        applyCommandToLocalHardwareModel(command)
        val updatedTelemetry = buildCurrentSensorTelemetryFrame()
        val responseLine = updatedTelemetry.toJsonLine()

        if (fromLoopback) {
            connectionManager.sendFromLoopbackSensorServer(responseLine)
        } else {
            connectionManager.sendFrame(responseLine)
            appendAuditLog("TX ->", responseLine.trim())
        }
    }

    private fun applyCommandToLocalHardwareModel(command: CommandFrame) {
        val dist = _uiState.value.distanceCm
        val parkingState = if (dist in 1..9) "OCUPADO" else "LIBRE"

        val (newMode, newBarrier) = when (command.cmd) {
            "SET_BARRERA" -> {
                val targetAngle = if (command.value >= 45) 90 else 0
                "MANUAL" to targetAngle
            }
            "SET_MODO" -> {
                val autoAngle = if (dist in 1..9) 0 else 90
                "AUTO" to autoAngle
            }
            else -> {
                _uiState.value.operationMode to _uiState.value.barrierAngle
            }
        }

        val frame = TelemetryFrame(
            dist = dist,
            estado = parkingState,
            barrera = newBarrier,
            modo = newMode
        )
        val (line1, line2) = frame.toLcdLines()

        _uiState.update { state ->
            state.copy(
                parkingStatus = parkingState,
                barrierAngle = newBarrier,
                operationMode = newMode,
                lastCrcStatus = TelemetryFrame.computeTelemetryCrc(dist, parkingState, newBarrier, newMode),
                lcdLine1 = line1,
                lcdLine2 = line2
            )
        }
    }

    private fun buildCurrentSensorTelemetryFrame(): TelemetryFrame {
        val state = _uiState.value
        val dist = state.distanceCm
        val parkingState = if (dist in 1..9) "OCUPADO" else "LIBRE"
        val barrier = if (state.operationMode == "AUTO") {
            if (dist in 1..9) 0 else 90
        } else {
            state.barrierAngle
        }
        return TelemetryFrame(
            dist = dist,
            estado = parkingState,
            barrera = barrier,
            modo = state.operationMode,
            crc = TelemetryFrame.computeTelemetryCrc(dist, parkingState, barrier, state.operationMode)
        )
    }

    // =========================================================================
    // SECCIÓN D: COLA TEMPORAL CIFRADA Y SINCRONIZACIÓN AUTOMÁTICA (OFFLINE SYNC)
    // =========================================================================

    private fun enqueueOfflineEvent(type: String, payloadJson: String) {
        val newEvent = OfflineSyncEvent(
            id = UUID.randomUUID().toString().take(8),
            type = type,
            payloadJson = payloadJson,
            createdAt = System.currentTimeMillis(),
            synced = false
        )
        val updatedQueue = (_uiState.value.offlineSyncQueue + newEvent).takeLast(25)
        securityManager.saveOfflineSyncQueue(updatedQueue)

        _uiState.update { state ->
            state.copy(
                offlineSyncQueue = updatedQueue,
                statusBannerMessage = "Sin conexión activa: Trama guardada en Cola Offline Cifrada (${updatedQueue.size} pendientes)."
            )
        }
        appendAuditLog("SYNC *", "Encolado Offline cifrado [$type]: $payloadJson", isWarning = true)
    }

    /**
     * Sincroniza automáticamente todos los eventos pendientes en la cola cifrada tan pronto
     * como el socket inalámbrico se reconecta.
     */
    fun flushOfflineSyncQueue() {
        viewModelScope.launch(Dispatchers.IO) {
            val pending = _uiState.value.offlineSyncQueue
            if (pending.isEmpty()) return@launch

            appendAuditLog("SYNC *", "Iniciando sincronización automática de ${pending.size} tramas retenidas...")
            var syncedNow = 0
            val remaining = mutableListOf<OfflineSyncEvent>()

            for (event in pending) {
                val ok = connectionManager.sendFrame("${event.payloadJson}\n")
                if (ok) {
                    syncedNow++
                    appendAuditLog("SYNC *", "Trama sincronizada [${event.id}]: ${event.payloadJson}")
                    delay(80L)
                } else {
                    remaining.add(event)
                }
            }

            securityManager.saveOfflineSyncQueue(remaining)
            _uiState.update { state ->
                state.copy(
                    offlineSyncQueue = remaining,
                    syncedEventsCount = state.syncedEventsCount + syncedNow,
                    statusBannerMessage = if (syncedNow > 0) {
                        "Sincronización Offline completada: $syncedNow eventos transmitidos al reconectar."
                    } else {
                        state.statusBannerMessage
                    }
                )
            }
        }
    }

    fun clearAuditLogs() {
        _uiState.update { it.copy(auditLogs = emptyList()) }
    }

    private fun appendAuditLog(direction: String, rawFrame: String, isWarning: Boolean = false) {
        val entry = ProtocolAuditLog(
            direction = direction,
            rawFrame = rawFrame,
            isWarning = isWarning
        )
        _uiState.update { state ->
            state.copy(auditLogs = (listOf(entry) + state.auditLogs).take(40))
        }
    }

    override fun onCleared() {
        super.onCleared()
        connectionManager.disconnectGracefully()
    }
}
