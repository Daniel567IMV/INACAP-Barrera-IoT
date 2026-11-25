package com.example.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AckFrame
import com.example.data.CommandFrame
import com.example.data.CommandValidationResult
import com.example.data.ConnectionState
import com.example.data.DeviceRole
import com.example.data.ErrFrame
import com.example.data.LocalTimeFormatter
import com.example.data.PairedBtDevice
import com.example.data.ParsedIncomingFrame
import com.example.data.ProtocolAuditLog
import com.example.data.ProtocolFrameParser
import com.example.data.TelemetryFrame
import com.example.data.TransportMode
import com.example.data.UiConnectionHealth
import com.example.data.local.ParkingDatabase
import com.example.data.local.ParkingSyncRepository
import com.example.data.local.SyncRecordEntity
import com.example.network.WirelessConnectionManager
import com.example.security.SecurityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

/**
 * Estado unificado MVVM sobreviviente a rotación de pantalla (ES3 - TI3042 INACAP).
 */
data class MainUiState(
    // Autenticación y Seguridad ISO/IEC 27000 / 27400 (cero credenciales hardcodeadas)
    val needsInitialSetup: Boolean = true,
    val isAuthenticated: Boolean = false,
    val activeOperator: String = "",
    val lastRegisteredOperator: String = "",
    val sessionTokenMasked: String = "",
    val sha256Fingerprint: String = "PENDIENTE_REGISTRO",
    val authFeedbackMessage: String? = null,
    val isAuthError: Boolean = false,

    // Configuración segura local (sin versionar secretos)
    val configuredSyncEndpoint: String = "",
    val hasCustomOtToken: Boolean = false,
    val configFeedbackMessage: String? = null,

    // Permisos en Runtime
    val hasWirelessPermissions: Boolean = false,
    val permissionDeniedMessage: String? = null,

    // Rol y Conectividad Inalámbrica
    val selectedRole: DeviceRole = DeviceRole.CONTROL_STATION,
    val selectedTransport: TransportMode = TransportMode.LOCAL_LOOPBACK,
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val connectionHealth: UiConnectionHealth = UiConnectionHealth.DISCONNECTED,
    val secondsSinceLastTelemetry: Long = 0L,
    val isTelemetryFeedPausedForTest: Boolean = false,
    val pairedBtDevices: List<PairedBtDevice> = emptyList(),
    val selectedBtAddress: String = "00:11:22:33:AA:BB",
    val targetTcpIp: String = "192.168.4.1",
    val targetTcpPort: String = "8888",
    val localIpAddress: String = "127.0.0.1",

    // Dominio Estacionamiento Inteligente (Entrada: plazas libres / capacidad + HC-SR04 + Servo + LCD)
    val freeSlots: Int = 11,
    val totalCapacity: Int = 12,
    val distanceCm: Int = 24,
    val parkingStatus: String = "LIBRE",       // "LIBRE" o "OCUPADO"
    val barrierAngle: Int = 90,                // 90° (Abierta) o 0° (Cerrada)
    val operationMode: String = "AUTO",        // "AUTO" o "MANUAL"
    val lastCrcStatus: String = "OK-0000",
    val lastTelemetryTimestamp: String = "Sin lecturas",

    // Respuestas Bidireccionales ACK y ERR con marca de hora local legible
    val lastAckSummary: String? = null,
    val lastAckTimestamp: String? = null,
    val lastErrSummary: String? = null,
    val lastErrTimestamp: String? = null,
    val commandValidationError: String? = null,

    // Simulador Nodo Sensor OT (ESP32 Virtual / 2° Android)
    val simulatedSensorDistanceCm: Float = 24f,
    val lcdLine1: String = "L:11/12 D: 24cm ",
    val lcdLine2: String = "LIBRE   90G AUT ",

    // Persistencia Local y Sincronización Room (SQLite)
    val roomRecentRecords: List<SyncRecordEntity> = emptyList(),
    val roomPendingCount: Int = 0,
    val roomSyncedCount: Int = 0,
    val isSyncingRoom: Boolean = false,

    // Bitácora en vivo con timestamp local legible
    val auditLogs: List<ProtocolAuditLog> = emptyList(),
    val statusBannerMessage: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val securityManager = SecurityManager(application)
    private val connectionManager = WirelessConnectionManager(application)
    private val database = ParkingDatabase.getInstance(application)
    private val syncRepository = ParkingSyncRepository(database.syncRecordDao())

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    @Volatile
    private var activeRawSessionToken: String = ""

    @Volatile
    private var lastTelemetryReceivedEpochMs: Long = 0L

    @Volatile
    private var lastSavedTelemetryEpochMs: Long = 0L

    init {
        val hasOperators = securityManager.hasRegisteredOperators()
        val lastUser = securityManager.getLastRegisteredOperator()
        val restoredSession = securityManager.restoreActiveSession()
        val localIp = connectionManager.getLocalIpAddress()
        val syncEndpoint = securityManager.getConfiguredSyncEndpoint()
        val hasCustomToken = securityManager.getConfiguredDeviceToken().isNotBlank()

        if (restoredSession != null) {
            activeRawSessionToken = restoredSession.sessionToken
        }

        _uiState.update { state ->
            state.copy(
                needsInitialSetup = !hasOperators,
                isAuthenticated = restoredSession != null,
                activeOperator = restoredSession?.operatorName ?: "",
                lastRegisteredOperator = lastUser,
                sessionTokenMasked = restoredSession?.let { securityManager.maskToken(it.sessionToken) } ?: "",
                sha256Fingerprint = restoredSession?.sha256Preview
                    ?: if (lastUser.isNotBlank()) securityManager.getStoredHashFingerprint(lastUser) else "PENDIENTE_REGISTRO",
                configuredSyncEndpoint = syncEndpoint,
                hasCustomOtToken = hasCustomToken,
                localIpAddress = localIp
            )
        }

        if (restoredSession != null) {
            appendAuditLog(
                category = "SEC",
                rawFrame = "Sesión restaurada desde EncryptedSharedPreferences (AES256-GCM) para '${restoredSession.operatorName}'"
            )
        }

        observeRoomSyncState()
        observeConnectionState()
        observeIncomingSocketFrames()
        observeLoopbackSensorServerFrames()
        observeSystemEvents()
        startTelemetryFreshnessWatchdog()
        startPeriodicSensorTelemetryLoop()
    }

    private fun observeRoomSyncState() {
        viewModelScope.launch {
            combine(
                syncRepository.recentRecords,
                syncRepository.pendingCount,
                syncRepository.syncedCount
            ) { records, pending, synced ->
                Triple(records, pending, synced)
            }.collect { (records, pending, synced) ->
                _uiState.update { state ->
                    state.copy(
                        roomRecentRecords = records,
                        roomPendingCount = pending,
                        roomSyncedCount = synced
                    )
                }
            }
        }
    }

    private fun observeConnectionState() {
        viewModelScope.launch {
            connectionManager.connectionState.collect { connState ->
                val computedHealth = computeConnectionHealth(connState, lastTelemetryReceivedEpochMs)
                _uiState.update {
                    it.copy(
                        connectionState = connState,
                        connectionHealth = computedHealth
                    )
                }
                if (connState is ConnectionState.Connected) {
                    // Sincronización automática de registros pendientes Room (`synced = false`) al reconectar
                    syncPendingRoomRecords()
                }
            }
        }
    }

    /**
     * Vigila cada 1 segundo si se superó el umbral sin recibir telemetría reciente
     * para reflejar claramente el estado `SIN DATOS RECIENTES` en la UI (Requerimiento 2).
     */
    private fun startTelemetryFreshnessWatchdog() {
        viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(1000L)
                val state = _uiState.value
                val now = System.currentTimeMillis()
                val ageSeconds = if (lastTelemetryReceivedEpochMs > 0L) {
                    ((now - lastTelemetryReceivedEpochMs) / 1000L).coerceAtLeast(0L)
                } else {
                    0L
                }
                val health = computeConnectionHealth(state.connectionState, lastTelemetryReceivedEpochMs)
                if (state.connectionHealth != health || state.secondsSinceLastTelemetry != ageSeconds) {
                    _uiState.update {
                        it.copy(
                            connectionHealth = health,
                            secondsSinceLastTelemetry = ageSeconds
                        )
                    }
                }
            }
        }
    }

    private fun computeConnectionHealth(
        connState: ConnectionState,
        lastTelemetryMs: Long
    ): UiConnectionHealth {
        return when (connState) {
            is ConnectionState.Disconnected -> UiConnectionHealth.DISCONNECTED
            is ConnectionState.Connecting,
            is ConnectionState.Reconnecting -> UiConnectionHealth.RECONNECTING
            is ConnectionState.ListeningServer -> UiConnectionHealth.NO_RECENT_DATA
            is ConnectionState.Connected -> {
                if (lastTelemetryMs == 0L) {
                    UiConnectionHealth.NO_RECENT_DATA
                } else {
                    val elapsed = System.currentTimeMillis() - lastTelemetryMs
                    if (elapsed >= STALE_TELEMETRY_THRESHOLD_MS) {
                        UiConnectionHealth.NO_RECENT_DATA
                    } else {
                        UiConnectionHealth.CONNECTED
                    }
                }
            }
        }
    }

    /**
     * Analiza TODA trama entrante mediante `ProtocolFrameParser.parseLine` descartando
     * de forma segura JSON incompletos o corruptos sin cerrar la app y registrando `ERR`.
     */
    private fun observeIncomingSocketFrames() {
        viewModelScope.launch(Dispatchers.IO) {
            connectionManager.incomingFrames.collect { rawLine ->
                processIncomingRawLine(rawLine, fromLoopbackServer = false)
            }
        }
    }

    private fun observeLoopbackSensorServerFrames() {
        viewModelScope.launch(Dispatchers.IO) {
            connectionManager.incomingLoopbackServerFrames.collect { rawLine ->
                processIncomingRawLine(rawLine, fromLoopbackServer = true)
            }
        }
    }

    private suspend fun processIncomingRawLine(rawLine: String, fromLoopbackServer: Boolean) {
        when (val parsed = ProtocolFrameParser.parseLine(rawLine)) {
            is ParsedIncomingFrame.Telemetry -> {
                val frame = parsed.frame
                lastTelemetryReceivedEpochMs = frame.timestamp
                val formattedTs = LocalTimeFormatter.formatFull(frame.timestamp)
                val (line1, line2) = frame.toLcdLines()

                _uiState.update { state ->
                    state.copy(
                        distanceCm = frame.dist,
                        parkingStatus = frame.estado,
                        barrierAngle = frame.barrera,
                        operationMode = frame.modo,
                        freeSlots = frame.plazasLibres,
                        totalCapacity = frame.capacidadTotal,
                        lastCrcStatus = frame.crc,
                        lastTelemetryTimestamp = formattedTs,
                        secondsSinceLastTelemetry = 0L,
                        connectionHealth = UiConnectionHealth.CONNECTED,
                        lcdLine1 = line1,
                        lcdLine2 = line2
                    )
                }

                appendAuditLog(
                    category = "TEL",
                    rawFrame = "dist=${frame.dist}cm plazas=${frame.plazasLibres}/${frame.capacidadTotal} " +
                        "estado=${frame.estado} barrera=${frame.barrera}° modo=${frame.modo} crc=${frame.crc}"
                )

                // Guardar muestra periódica o cambio de estado en Room con clave única deduplicada
                val shouldSnapshotToRoom = (frame.timestamp - lastSavedTelemetryEpochMs) >= 15_000L
                if (shouldSnapshotToRoom) {
                    lastSavedTelemetryEpochMs = frame.timestamp
                    val bucketKey = "TEL_${frame.timestamp / 5000L}_${frame.dist}_${frame.barrera}_${frame.plazasLibres}"
                    syncRepository.recordEvent(
                        SyncRecordEntity(
                            uniqueEventKey = bucketKey,
                            recordType = "TELEMETRIA",
                            payloadJson = frame.toJsonLine().trim(),
                            distanceCm = frame.dist,
                            freeSlots = frame.plazasLibres,
                            totalCapacity = frame.capacidadTotal,
                            parkingStatus = frame.estado,
                            barrierAngle = frame.barrera,
                            operationMode = frame.modo,
                            createdAtMillis = frame.timestamp,
                            synced = true,
                            syncedAtMillis = frame.timestamp,
                            syncTarget = "LIVE_STREAM"
                        )
                    )
                }
            }

            is ParsedIncomingFrame.Ack -> {
                val ack = parsed.frame
                val formattedTs = LocalTimeFormatter.formatFull(ack.timestamp)
                _uiState.update { state ->
                    state.copy(
                        lastAckSummary = "ACK [${ack.cmd}#${ack.reqId}]: ${ack.detail}",
                        lastAckTimestamp = formattedTs
                    )
                }
                appendAuditLog(
                    category = "ACK",
                    rawFrame = "cmd=${ack.cmd} reqId=${ack.reqId} • ${ack.detail}",
                    isAck = true
                )
            }

            is ParsedIncomingFrame.Err -> {
                val err = parsed.frame
                val formattedTs = LocalTimeFormatter.formatFull(err.timestamp)
                _uiState.update { state ->
                    state.copy(
                        lastErrSummary = "ERR [${err.code}#${err.reqId}]: ${err.detail}",
                        lastErrTimestamp = formattedTs
                    )
                }
                appendAuditLog(
                    category = "ERR",
                    rawFrame = "code=${err.code} reqId=${err.reqId} • ${err.detail}",
                    isError = true
                )
                persistErrToRoom(err)
            }

            is ParsedIncomingFrame.Command -> {
                handleIncomingCommandOnSensorNode(parsed.frame, fromLoopbackServer)
            }

            is ParsedIncomingFrame.Corrupt -> {
                val err = ErrFrame(
                    code = "MALFORMED_JSON",
                    reqId = "RX",
                    detail = parsed.reason
                )
                val formattedTs = LocalTimeFormatter.formatFull(err.timestamp)
                _uiState.update { state ->
                    state.copy(
                        lastErrSummary = "ERR [MALFORMED_JSON]: ${parsed.reason}",
                        lastErrTimestamp = formattedTs
                    )
                }
                appendAuditLog(
                    category = "ERR",
                    rawFrame = "Trama descartada (${parsed.reason}) -> ${parsed.rawLine.take(64)}",
                    isError = true
                )
                persistErrToRoom(err)

                // Si el nodo sensor recibió basura/trama truncada, responde con ERR estructurado
                if (fromLoopbackServer) {
                    connectionManager.sendFromLoopbackSensorServer(err.toJsonLine())
                } else if (_uiState.value.selectedRole == DeviceRole.SENSOR_NODE) {
                    connectionManager.sendFrame(err.toJsonLine())
                }
            }
        }
    }

    private suspend fun persistErrToRoom(err: ErrFrame) {
        syncRepository.recordEvent(
            SyncRecordEntity(
                uniqueEventKey = "ERR_${err.code}_${err.reqId}_${err.timestamp / 1000L}",
                recordType = "ERR",
                payloadJson = err.toJsonLine().trim(),
                createdAtMillis = err.timestamp,
                synced = _uiState.value.connectionState is ConnectionState.Connected,
                syncedAtMillis = if (_uiState.value.connectionState is ConnectionState.Connected) err.timestamp else null,
                syncTarget = if (_uiState.value.connectionState is ConnectionState.Connected) "LOCAL_AUDIT" else null
            )
        )
    }

    private fun observeSystemEvents() {
        viewModelScope.launch {
            connectionManager.systemEvents.collect { msg ->
                val isWarn = msg.contains("IOException", ignoreCase = true) ||
                    msg.contains("Reconexión", ignoreCase = true) ||
                    msg.contains("Error", ignoreCase = true) ||
                    msg.contains("Corte", ignoreCase = true)
                appendAuditLog("SYS", msg, isError = isWarn)
                _uiState.update { it.copy(statusBannerMessage = msg) }
            }
        }
    }

    /**
     * Emisión periódica no bloqueante de telemetría (equivalente a `millis()` en ESP32).
     */
    private fun startPeriodicSensorTelemetryLoop() {
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(1500L)
                val state = _uiState.value
                if (!state.isAuthenticated || state.isTelemetryFeedPausedForTest) continue

                if (state.connectionState is ConnectionState.Connected) {
                    val frame = buildCurrentSensorTelemetryFrame()
                    val jsonLine = frame.toJsonLine()
                    if (state.selectedTransport == TransportMode.LOCAL_LOOPBACK) {
                        connectionManager.sendFromLoopbackSensorServer(jsonLine)
                    } else if (state.selectedRole == DeviceRole.SENSOR_NODE) {
                        connectionManager.sendFrame(jsonLine)
                    }
                }
            }
        }
    }

    // =========================================================================
    // SEGURIDAD ISO 27000 / 27400 (SIN CREDENCIALES HARDCODEADAS)
    // =========================================================================

    fun login(username: String, plainPassword: String, keepSession: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            when (val result = securityManager.authenticateOperator(username, plainPassword, keepSession)) {
                is SecurityManager.AuthResult.Success -> {
                    activeRawSessionToken = result.sessionToken
                    _uiState.update {
                        it.copy(
                            needsInitialSetup = false,
                            isAuthenticated = true,
                            activeOperator = result.operatorName,
                            lastRegisteredOperator = result.operatorName,
                            sessionTokenMasked = securityManager.maskToken(result.sessionToken),
                            sha256Fingerprint = result.sha256Preview,
                            authFeedbackMessage = null,
                            isAuthError = false
                        )
                    }
                    appendAuditLog(
                        "SEC",
                        "Login SHA-256+Salt verificado [${result.sha256Preview}...] Token=${securityManager.maskToken(result.sessionToken)}"
                    )
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

    fun registerOrUpdateOperator(username: String, plainPassword: String, autoSignIn: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            when (val result = securityManager.registerOrUpdateCredentials(username, plainPassword)) {
                is SecurityManager.AuthResult.Success -> {
                    activeRawSessionToken = result.sessionToken
                    _uiState.update {
                        it.copy(
                            needsInitialSetup = false,
                            isAuthenticated = autoSignIn,
                            activeOperator = if (autoSignIn) result.operatorName else it.activeOperator,
                            lastRegisteredOperator = result.operatorName,
                            sessionTokenMasked = securityManager.maskToken(result.sessionToken),
                            sha256Fingerprint = result.sha256Preview,
                            authFeedbackMessage = if (autoSignIn) {
                                null
                            } else {
                                "Operador cifrado con SHA-256 + Salt aleatorio en EncryptedSharedPreferences (AES256-GCM)."
                            },
                            isAuthError = false
                        )
                    }
                    appendAuditLog(
                        "SEC",
                        "Credencial cifrada registrada para '${result.operatorName}' (Hash ${result.sha256Preview}...)"
                    )
                    if (autoSignIn && _uiState.value.selectedTransport == TransportMode.LOCAL_LOOPBACK) {
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

    fun saveSecurityAndSyncConfig(syncEndpointUrl: String, sharedOtToken: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val cleanUrl = syncEndpointUrl.trim()
            if (cleanUrl.isNotEmpty() && syncRepository.validateSyncEndpointUrl(cleanUrl) == null) {
                _uiState.update {
                    it.copy(configFeedbackMessage = "URL inválida. Use únicamente esquema https:// o http:// local.")
                }
                return@launch
            }
            val cleanToken = sharedOtToken.trim()
            if (cleanToken.isNotEmpty() && cleanToken.length < 8) {
                _uiState.update {
                    it.copy(configFeedbackMessage = "El token OT compartido debe tener mínimo 8 caracteres.")
                }
                return@launch
            }

            securityManager.saveSecureIntegrationConfig(cleanUrl, cleanToken)
            if (cleanToken.isNotEmpty()) {
                activeRawSessionToken = cleanToken
            }

            _uiState.update {
                it.copy(
                    configuredSyncEndpoint = cleanUrl,
                    hasCustomOtToken = cleanToken.isNotEmpty(),
                    sessionTokenMasked = if (activeRawSessionToken.isNotBlank()) {
                        securityManager.maskToken(activeRawSessionToken)
                    } else {
                        it.sessionTokenMasked
                    },
                    configFeedbackMessage = "Configuración almacenada de forma cifrada (AES256-GCM)."
                )
            }
            appendAuditLog("SEC", "Parámetros de sincronización/token actualizados en EncryptedSharedPreferences.")
        }
    }

    fun logout() {
        connectionManager.disconnectGracefully()
        securityManager.logoutSecurely()
        activeRawSessionToken = ""
        lastTelemetryReceivedEpochMs = 0L
        _uiState.update {
            it.copy(
                isAuthenticated = false,
                activeOperator = "",
                sessionTokenMasked = "",
                connectionHealth = UiConnectionHealth.DISCONNECTED,
                authFeedbackMessage = "Sesión cerrada de forma segura. Token revocado."
            )
        }
        appendAuditLog("SEC", "Cierre de sesión seguro ejecutado.")
    }

    // =========================================================================
    // PERMISOS EN RUNTIME
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
                        "Puede continuar por Wi-Fi TCP o habilitar permisos Bluetooth en Configuración."
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
    // ROLES, TRANSPORTE Y PRUEBAS DE RESILIENCIA
    // =========================================================================

    fun selectRole(role: DeviceRole) {
        _uiState.update { it.copy(selectedRole = role) }
        appendAuditLog("SYS", "Rol seleccionado: ${role.title}")
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
                it.copy(statusBannerMessage = "Otorgue los permisos Bluetooth antes de conectar por RFCOMM.")
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

    fun triggerSimulatedDisconnectFault() {
        connectionManager.simulateAbruptHardwareDisconnect()
    }

    /**
     * Pausa o reanuda la transmisión simulada de telemetría para comprobar visualmente
     * el paso automático al estado `SIN DATOS RECIENTES` tras superar el umbral de 8s.
     */
    fun toggleTelemetryPauseForStaleTest() {
        val nextPaused = !_uiState.value.isTelemetryFeedPausedForTest
        _uiState.update { it.copy(isTelemetryFeedPausedForTest = nextPaused) }
        appendAuditLog(
            "SYS",
            if (nextPaused) {
                "Telemetría simulada pausada: tras ${STALE_TELEMETRY_THRESHOLD_MS / 1000}s pasará a SIN DATOS RECIENTES."
            } else {
                "Telemetría simulada reanudada."
            }
        )
    }

    // =========================================================================
    // COMANDOS VALIDADOS EN UI + PROCESAMIENTO ACK / ERR + ROOM OFFLINE SYNC
    // =========================================================================

    fun sendOpenBarrierCommand() {
        validateAndDispatchCommand(cmd = "SET_BARRERA", value = 90, modo = "MANUAL")
    }

    fun sendCloseBarrierCommand() {
        validateAndDispatchCommand(cmd = "SET_BARRERA", value = 0, modo = "MANUAL")
    }

    fun sendAutoModeCommand() {
        validateAndDispatchCommand(cmd = "SET_MODO", value = -1, modo = "AUTO")
    }

    fun sendRequestStatusCommand() {
        validateAndDispatchCommand(cmd = "REQ_STATUS", value = -1, modo = _uiState.value.operationMode)
    }

    fun sendValidatedBarrierAngleInput(angleText: String) {
        val parsed = angleText.trim().toIntOrNull()
        if (parsed == null) {
            setUiValidationError("Ingrese un número entero válido para el ángulo (0° o 90°).")
            return
        }
        validateAndDispatchCommand(cmd = "SET_BARRERA", value = parsed, modo = "MANUAL")
    }

    fun sendValidatedCapacityUpdate(capacityText: String, freeSlotsText: String) {
        val cap = capacityText.trim().toIntOrNull()
        val free = freeSlotsText.trim().toIntOrNull()
        if (cap == null || free == null) {
            setUiValidationError("Capacidad total y plazas libres deben ser números enteros.")
            return
        }
        when (val capValidation = CommandFrame.validate("SET_CAPACIDAD", cap, "AUTO", cap)) {
            is CommandValidationResult.Invalid -> {
                setUiValidationError(capValidation.reason)
                return
            }
            CommandValidationResult.Valid -> Unit
        }
        when (val freeValidation = CommandFrame.validate("SET_PLAZAS", free, "AUTO", cap)) {
            is CommandValidationResult.Invalid -> {
                setUiValidationError(freeValidation.reason)
                return
            }
            CommandValidationResult.Valid -> Unit
        }

        validateAndDispatchCommand(cmd = "SET_CAPACIDAD", value = cap, modo = _uiState.value.operationMode)
        validateAndDispatchCommand(cmd = "SET_PLAZAS", value = free, modo = _uiState.value.operationMode)
    }

    private fun setUiValidationError(reason: String) {
        val ts = LocalTimeFormatter.formatFull()
        _uiState.update {
            it.copy(
                commandValidationError = reason,
                lastErrSummary = "VALIDACIÓN UI BLOQUEADA: $reason",
                lastErrTimestamp = ts
            )
        }
        appendAuditLog("ERR", "Validación previa al envío bloqueó comando: $reason", isError = true)
    }

    private fun validateAndDispatchCommand(cmd: String, value: Int, modo: String) {
        val currentCap = _uiState.value.totalCapacity
        when (val validation = CommandFrame.validate(cmd, value, modo, currentCap)) {
            is CommandValidationResult.Invalid -> {
                setUiValidationError(validation.reason)
                return
            }
            CommandValidationResult.Valid -> {
                _uiState.update { it.copy(commandValidationError = null) }
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            val token = activeRawSessionToken
            val commandFrame = CommandFrame(
                cmd = cmd,
                value = value,
                modo = modo,
                token = token
            )
            val jsonLine = commandFrame.toJsonLine()
            val redactedLine = ProtocolFrameParser.redactSensitiveFields(jsonLine.trim())
            val sent = connectionManager.sendFrame(jsonLine)

            if (sent) {
                appendAuditLog("CMD", "TX -> $redactedLine")
                // Persistir evento ya sincronizado en Room con clave única
                syncRepository.recordEvent(
                    SyncRecordEntity(
                        uniqueEventKey = "CMD_${commandFrame.reqId}",
                        recordType = "COMANDO",
                        payloadJson = redactedLine,
                        barrierAngle = if (cmd == "SET_BARRERA") value else _uiState.value.barrierAngle,
                        operationMode = modo,
                        freeSlots = _uiState.value.freeSlots,
                        totalCapacity = _uiState.value.totalCapacity,
                        createdAtMillis = commandFrame.timestamp,
                        synced = true,
                        syncedAtMillis = System.currentTimeMillis(),
                        syncTarget = "LIVE_SOCKET"
                    )
                )
            } else {
                // Sin conexión: guardar en Room SQLite con `synced = false` para sincronización automática
                val inserted = syncRepository.recordEvent(
                    SyncRecordEntity(
                        uniqueEventKey = "CMD_${commandFrame.reqId}",
                        recordType = "COMANDO",
                        payloadJson = redactedLine,
                        barrierAngle = if (cmd == "SET_BARRERA") value else _uiState.value.barrierAngle,
                        operationMode = modo,
                        freeSlots = _uiState.value.freeSlots,
                        totalCapacity = _uiState.value.totalCapacity,
                        createdAtMillis = commandFrame.timestamp,
                        synced = false
                    )
                )
                if (inserted) {
                    appendAuditLog(
                        "SYNC",
                        "Sin conexión: comando ${commandFrame.cmd}#${commandFrame.reqId} guardado en Room (synced=false).",
                        isError = true
                    )
                }
                _uiState.update {
                    it.copy(
                        statusBannerMessage = "Sin conexión: evento guardado en Room SQLite (synced=false) para sincronizar al reconectar."
                    )
                }
            }
        }
    }

    /**
     * Inyecta una prueba controlada de protocolo (comando desconocido o trama JSON truncada)
     * para demostrar en la defensa ES3 el manejo de `ERR` y descarte seguro sin crasheos.
     */
    fun sendProtocolFaultProbe(probeType: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val payload = when (probeType) {
                "UNKNOWN_CMD" -> CommandFrame(
                    cmd = "OPEN_ROOF_INVALID",
                    value = 999,
                    modo = "MANUAL",
                    token = activeRawSessionToken
                ).toJsonLine()

                "UNAUTHORIZED" -> CommandFrame(
                    cmd = "SET_BARRERA",
                    value = 90,
                    modo = "MANUAL",
                    token = "BAD"
                ).toJsonLine()

                else -> "{\"type\":\"TELEMETRY\",\"dist\":15,\"estado\":\"LIBRE\"" // JSON truncado deliberado
            }

            if (probeType == "CORRUPT_JSON") {
                // Alimenta directamente el parser defensivo para validar descarte seguro
                processIncomingRawLine(payload, fromLoopbackServer = false)
            } else {
                val sent = connectionManager.sendFrame(payload)
                if (!sent) {
                    // Evaluar localmente como si lo recibiera el nodo sensor
                    processIncomingRawLine(payload, fromLoopbackServer = false)
                } else {
                    appendAuditLog("CMD", "Prueba protocolo ($probeType) enviada.")
                }
            }
        }
    }

    /**
     * Slider del Nodo Sensor OT (0 a 100 cm):
     * Aplica lógica del ESP32 HC-SR04 y actualiza plazas libres desde la entrada al detectar vehículo.
     */
    fun onSimulatedSensorDistanceChanged(newDistanceFloat: Float) {
        val distCm = newDistanceFloat.toInt().coerceIn(0, 100)
        val prevState = _uiState.value
        val wasOccupied = prevState.parkingStatus.equals("OCUPADO", ignoreCase = true)
        val isNowOccupied = distCm in 1..9

        val newParkingStatus = if (isNowOccupied) "OCUPADO" else "LIBRE"
        val newBarrierAngle = if (prevState.operationMode == "AUTO") {
            if (isNowOccupied) 0 else 90
        } else {
            prevState.barrierAngle
        }

        // Actualizar plazas libres desde la entrada al entrar/salir un vehículo
        val adjustedFreeSlots = when {
            !wasOccupied && isNowOccupied -> (prevState.freeSlots - 1).coerceAtLeast(0)
            wasOccupied && !isNowOccupied -> (prevState.freeSlots + 1).coerceAtMost(prevState.totalCapacity)
            else -> prevState.freeSlots.coerceIn(0, prevState.totalCapacity)
        }

        val now = System.currentTimeMillis()
        val frame = TelemetryFrame(
            dist = distCm,
            estado = newParkingStatus,
            barrera = newBarrierAngle,
            modo = prevState.operationMode,
            plazasLibres = adjustedFreeSlots,
            capacidadTotal = prevState.totalCapacity,
            timestamp = now
        )
        val (line1, line2) = frame.toLcdLines()
        val crc = TelemetryFrame.computeTelemetryCrc(
            distCm,
            newParkingStatus,
            newBarrierAngle,
            prevState.operationMode,
            adjustedFreeSlots,
            prevState.totalCapacity
        )

        lastTelemetryReceivedEpochMs = now
        _uiState.update { state ->
            state.copy(
                simulatedSensorDistanceCm = newDistanceFloat,
                distanceCm = distCm,
                parkingStatus = newParkingStatus,
                barrierAngle = newBarrierAngle,
                freeSlots = adjustedFreeSlots,
                lastCrcStatus = crc,
                lastTelemetryTimestamp = LocalTimeFormatter.formatFull(now),
                lcdLine1 = line1,
                lcdLine2 = line2
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            val jsonLine = frame.toJsonLine()
            val state = _uiState.value
            val sent = if (state.selectedTransport == TransportMode.LOCAL_LOOPBACK) {
                connectionManager.sendFromLoopbackSensorServer(jsonLine)
            } else {
                connectionManager.sendFrame(jsonLine)
            }

            if (!sent) {
                // Guardar lectura offline en Room con clave única para sincronizar al reconectar
                val uniqueKey = "TEL_OFFLINE_${now / 1000L}_${distCm}_${adjustedFreeSlots}"
                syncRepository.recordEvent(
                    SyncRecordEntity(
                        uniqueEventKey = uniqueKey,
                        recordType = "TELEMETRIA",
                        payloadJson = jsonLine.trim(),
                        distanceCm = distCm,
                        freeSlots = adjustedFreeSlots,
                        totalCapacity = state.totalCapacity,
                        parkingStatus = newParkingStatus,
                        barrierAngle = newBarrierAngle,
                        operationMode = state.operationMode,
                        createdAtMillis = now,
                        synced = false
                    )
                )
            }
        }
    }

    /**
     * Procesa comandos en el Nodo Sensor OT respondiendo SIEMPRE con `ACK` o `ERR` estructurado.
     */
    private suspend fun handleIncomingCommandOnSensorNode(
        command: CommandFrame,
        fromLoopback: Boolean
    ) {
        suspend fun reply(frameLine: String) {
            if (fromLoopback) {
                connectionManager.sendFromLoopbackSensorServer(frameLine)
            } else {
                connectionManager.sendFrame(frameLine)
            }
        }

        // 1. Verificar autorización ISO/IEC 27400
        if (!securityManager.isValidCommandToken(command.token)) {
            val err = ErrFrame(
                code = "UNAUTHORIZED",
                reqId = command.reqId,
                detail = "Token de sesión rechazado por control ISO/IEC 27400."
            )
            reply(err.toJsonLine())
            return
        }

        // 2. Validar comando y rangos de parámetros
        val currentCap = _uiState.value.totalCapacity
        val cmdUpper = command.cmd.trim().uppercase(Locale.US)
        if (cmdUpper !in CommandFrame.ALLOWED_COMMANDS) {
            val err = ErrFrame(
                code = "UNKNOWN_CMD",
                reqId = command.reqId,
                detail = "Comando inexistente: '${command.cmd}'."
            )
            reply(err.toJsonLine())
            return
        }

        when (val validation = CommandFrame.validate(cmdUpper, command.value, command.modo, currentCap)) {
            is CommandValidationResult.Invalid -> {
                val err = ErrFrame(
                    code = "OUT_OF_RANGE",
                    reqId = command.reqId,
                    detail = validation.reason
                )
                reply(err.toJsonLine())
                return
            }
            CommandValidationResult.Valid -> Unit
        }

        // 3. Aplicar comando al modelo hardware OT y responder ACK + Telemetría actualizada
        val detailMessage = applyCommandToLocalHardwareModel(command.copy(cmd = cmdUpper))
        val ack = AckFrame(
            cmd = cmdUpper,
            reqId = command.reqId,
            detail = detailMessage
        )
        reply(ack.toJsonLine())

        val telemetry = buildCurrentSensorTelemetryFrame()
        reply(telemetry.toJsonLine())
    }

    private fun applyCommandToLocalHardwareModel(command: CommandFrame): String {
        val state = _uiState.value
        val dist = state.distanceCm
        val parkingState = if (dist in 1..9) "OCUPADO" else "LIBRE"

        var newMode = state.operationMode
        var newBarrier = state.barrierAngle
        var newCap = state.totalCapacity
        var newFree = state.freeSlots

        val detail = when (command.cmd) {
            "SET_BARRERA" -> {
                newMode = "MANUAL"
                newBarrier = if (command.value >= 45) 90 else 0
                "Barrera fijada a $newBarrier° en modo MANUAL"
            }
            "SET_MODO" -> {
                newMode = command.modo.uppercase(Locale.US)
                if (newMode == "AUTO") {
                    newBarrier = if (dist in 1..9) 0 else 90
                }
                "Modo actualizado a $newMode (barrera $newBarrier°)"
            }
            "SET_CAPACIDAD" -> {
                newCap = command.value.coerceIn(TelemetryFrame.MIN_CAPACITY, TelemetryFrame.MAX_CAPACITY)
                newFree = newFree.coerceAtMost(newCap)
                "Capacidad total actualizada a $newCap plazas"
            }
            "SET_PLAZAS" -> {
                newFree = command.value.coerceIn(0, newCap)
                "Plazas libres desde entrada ajustadas a $newFree/$newCap"
            }
            "SYNC_RECORD" -> {
                "Registro offline confirmado en nodo receptor"
            }
            else -> "Estado OT confirmado ($newFree/$newCap libres, ${dist}cm)"
        }

        val now = System.currentTimeMillis()
        val frame = TelemetryFrame(
            dist = dist,
            estado = parkingState,
            barrera = newBarrier,
            modo = newMode,
            plazasLibres = newFree,
            capacidadTotal = newCap,
            timestamp = now
        )
        val (line1, line2) = frame.toLcdLines()

        _uiState.update { current ->
            current.copy(
                parkingStatus = parkingState,
                barrierAngle = newBarrier,
                operationMode = newMode,
                totalCapacity = newCap,
                freeSlots = newFree,
                lastCrcStatus = TelemetryFrame.computeTelemetryCrc(
                    dist,
                    parkingState,
                    newBarrier,
                    newMode,
                    newFree,
                    newCap
                ),
                lcdLine1 = line1,
                lcdLine2 = line2
            )
        }
        return detail
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
        val safeFree = state.freeSlots.coerceIn(0, state.totalCapacity)
        return TelemetryFrame(
            dist = dist,
            estado = parkingState,
            barrera = barrier,
            modo = state.operationMode,
            plazasLibres = safeFree,
            capacidadTotal = state.totalCapacity,
            crc = TelemetryFrame.computeTelemetryCrc(
                dist,
                parkingState,
                barrier,
                state.operationMode,
                safeFree,
                state.totalCapacity
            )
        )
    }

    // =========================================================================
    // SINCRONIZACIÓN ROOM SQLITE (DEDUPLICADA Y TOLERANTE A FALLOS)
    // =========================================================================

    fun syncPendingRoomRecords() {
        if (_uiState.value.isSyncingRoom) return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isSyncingRoom = true) }
            try {
                val configuredEndpoint = securityManager.getConfiguredSyncEndpoint()
                val summary = syncRepository.syncAllPending(
                    configuredEndpointUrl = configuredEndpoint,
                    bearerToken = activeRawSessionToken,
                    deliverViaSocket = { record ->
                        if (_uiState.value.connectionState !is ConnectionState.Connected) {
                            false
                        } else {
                            val syncCmd = CommandFrame(
                                reqId = record.eventId.take(6).uppercase(Locale.US),
                                cmd = "SYNC_RECORD",
                                value = record.distanceCm ?: 0,
                                modo = record.operationMode ?: "AUTO",
                                token = activeRawSessionToken
                            )
                            connectionManager.sendFrame(syncCmd.toJsonLine())
                        }
                    }
                )

                if (summary.attempted > 0) {
                    val msg = "Room Sync: ${summary.synced}/${summary.attempted} registros sincronizados" +
                        if (summary.failed > 0) " (${summary.failed} retenidos para reintento)" else "."
                    appendAuditLog("SYNC", msg, isAck = summary.failed == 0, isError = summary.failed > 0)
                    _uiState.update { it.copy(statusBannerMessage = msg) }
                }
            } finally {
                _uiState.update { it.copy(isSyncingRoom = false) }
            }
        }
    }

    fun clearSyncedRoomHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            syncRepository.clearSyncedHistory()
            appendAuditLog("SYNC", "Historial ya sincronizado purgado de Room (pendientes intactos).")
        }
    }

    fun clearAuditLogs() {
        _uiState.update { it.copy(auditLogs = emptyList()) }
    }

    private fun appendAuditLog(
        category: String,
        rawFrame: String,
        isError: Boolean = false,
        isAck: Boolean = false
    ) {
        val redacted = ProtocolFrameParser.redactSensitiveFields(rawFrame)
        val entry = ProtocolAuditLog(
            category = category,
            rawFrame = redacted,
            isError = isError,
            isAck = isAck
        )
        _uiState.update { state ->
            state.copy(auditLogs = (listOf(entry) + state.auditLogs).take(50))
        }
    }

    override fun onCleared() {
        super.onCleared()
        connectionManager.disconnectGracefully()
    }

    companion object {
        // Umbral de segundos sin telemetría para activar `SIN DATOS RECIENTES`
        const val STALE_TELEMETRY_THRESHOLD_MS = 8_000L
    }
}
