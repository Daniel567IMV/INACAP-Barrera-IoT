package com.example.network

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.example.data.ConnectionState
import com.example.data.PairedBtDevice
import com.example.data.TransportMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gestor de Conectividad Inalámbrica Resiliente (Bluetooth RFCOMM + Sockets TCP LAN/Loopback).
 *
 * Cumple con los requisitos de ES3 (TI3042):
 * - Ejecución exclusiva en `Dispatchers.IO` (cero bloqueo del hilo principal).
 * - Reconexión automática continua en segundo plano sin reiniciar la app, con espera
 *   exponencial creciente: `2s -> 4s -> 8s -> 16s -> 30s` (tope de 30s).
 * - Reinicio automático del contador de backoff a `0` al restablecer la conexión.
 * - Cierre garantizado de sockets en bloques `finally` y protección de lectura ante tramas corruptas.
 */
class WirelessConnectionManager(context: Context) {

    private val appContext = context.applicationContext
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _incomingFrames = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val incomingFrames: SharedFlow<String> = _incomingFrames.asSharedFlow()

    private val _systemEvents = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val systemEvents: SharedFlow<String> = _systemEvents.asSharedFlow()

    @Volatile
    private var activeBtSocket: BluetoothSocket? = null

    @Volatile
    private var activeBtServerSocket: BluetoothServerSocket? = null

    @Volatile
    private var activeTcpSocket: Socket? = null

    @Volatile
    private var activeTcpServerSocket: ServerSocket? = null

    @Volatile
    private var activeOutputStream: OutputStream? = null

    // Loopback Server embebido para pruebas con sockets TCP reales en 1 dispositivo
    @Volatile
    private var loopbackServerSocket: ServerSocket? = null

    @Volatile
    private var loopbackClientSocket: Socket? = null

    @Volatile
    private var loopbackOutputStream: OutputStream? = null

    private val _incomingLoopbackServerFrames = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val incomingLoopbackServerFrames: SharedFlow<String> = _incomingLoopbackServerFrames.asSharedFlow()

    private var connectionJob: Job? = null
    private var serverAcceptJob: Job? = null
    private var loopbackServerJob: Job? = null

    private val manualDisconnectRequested = AtomicBoolean(false)

    private var lastTransportMode: TransportMode = TransportMode.LOCAL_LOOPBACK
    private var lastTargetAddress: String = "127.0.0.1"
    private var lastTargetPort: Int = DEFAULT_TCP_PORT

    @SuppressLint("MissingPermission")
    fun getPairedBluetoothDevices(hasPermission: Boolean): List<PairedBtDevice> {
        if (!hasPermission) return emptyList()
        return try {
            val btManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter: BluetoothAdapter? = btManager?.adapter
            if (adapter == null || !adapter.isEnabled) return emptyList()
            adapter.bondedDevices?.map { device ->
                PairedBtDevice(
                    name = device.name ?: "Dispositivo BT",
                    address = device.address
                )
            }?.sortedByDescending {
                it.name.contains("ESP32", ignoreCase = true) ||
                    it.name.contains("INACAP", ignoreCase = true)
            } ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun getLocalIpAddress(): String {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
            for (intf in interfaces) {
                val addrs = intf.inetAddresses
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
            "127.0.0.1"
        } catch (_: Exception) {
            "127.0.0.1"
        }
    }

    /**
     * Inicia conexión como CLIENTE ("Estación de Control") con reconexión automática indefinida
     * en segundo plano (`Dispatchers.IO`) sin necesidad de reiniciar la app:
     * Backoff creciente: 2s -> 4s -> 8s -> 16s -> 30s (tope).
     * El contador `attempt` se reinicia a 0 cada vez que se logra establecer la conexión.
     */
    fun connectClient(
        transportMode: TransportMode,
        targetAddress: String,
        targetPort: Int = DEFAULT_TCP_PORT
    ) {
        manualDisconnectRequested.set(false)
        lastTransportMode = transportMode
        lastTargetAddress = targetAddress.ifBlank { "127.0.0.1" }
        lastTargetPort = targetPort

        connectionJob?.cancel()
        connectionJob = ioScope.launch {
            if (transportMode == TransportMode.LOCAL_LOOPBACK) {
                ensureLoopbackSensorServerRunning()
                delay(120)
            }

            var attempt = 0
            while (isActive && !manualDisconnectRequested.get()) {
                var wasConnectedThisCycle = false
                try {
                    if (attempt == 0) {
                        val label = when (transportMode) {
                            TransportMode.BLUETOOTH_RFCOMM -> "BT $lastTargetAddress"
                            TransportMode.TCP_WIFI_LAN -> "TCP $lastTargetAddress:$lastTargetPort"
                            TransportMode.LOCAL_LOOPBACK -> "Nodo OT Loopback (127.0.0.1:$DEFAULT_TCP_PORT)"
                        }
                        _connectionState.value = ConnectionState.Connecting(label)
                        _systemEvents.tryEmit("Iniciando socket en Dispatchers.IO hacia $label...")
                    } else {
                        val backoffMs = calculateExponentialBackoffMs(attempt)
                        _connectionState.value = ConnectionState.Reconnecting(
                            attempt = attempt,
                            nextRetryMs = backoffMs,
                            reason = "Reconectando en ${backoffMs / 1000}s (backoff exponencial)"
                        )
                        _systemEvents.tryEmit(
                            "Reconexión automática #$attempt programada en ${backoffMs / 1000}s (sin reiniciar app)..."
                        )
                        delay(backoffMs)
                        if (manualDisconnectRequested.get()) break
                        if (transportMode == TransportMode.LOCAL_LOOPBACK) {
                            ensureLoopbackSensorServerRunning()
                            delay(100)
                        }
                    }

                    when (transportMode) {
                        TransportMode.BLUETOOTH_RFCOMM -> {
                            runBluetoothClientSession(lastTargetAddress) {
                                wasConnectedThisCycle = true
                                attempt = 0 // Reinicio inmediato del contador al reconectar
                            }
                        }
                        TransportMode.TCP_WIFI_LAN -> {
                            runTcpClientSession(lastTargetAddress, lastTargetPort, TransportMode.TCP_WIFI_LAN) {
                                wasConnectedThisCycle = true
                                attempt = 0 // Reinicio inmediato del contador al reconectar
                            }
                        }
                        TransportMode.LOCAL_LOOPBACK -> {
                            runTcpClientSession("127.0.0.1", DEFAULT_TCP_PORT, TransportMode.LOCAL_LOOPBACK) {
                                wasConnectedThisCycle = true
                                attempt = 0 // Reinicio inmediato del contador al reconectar
                            }
                        }
                    }

                    if (manualDisconnectRequested.get()) break
                    // Si el enlace se perdió tras haber estado conectado, reiniciamos el backoff desde intento #1 (2s)
                    attempt = if (wasConnectedThisCycle) 1 else (attempt + 1)
                } catch (e: IOException) {
                    if (manualDisconnectRequested.get()) break
                    attempt = if (wasConnectedThisCycle) 1 else (attempt + 1)
                    _systemEvents.tryEmit(
                        "Corte de socket capturado (${e.message ?: "IOException"}). Activando reconexión backoff."
                    )
                } catch (e: SecurityException) {
                    _connectionState.value = ConnectionState.Disconnected
                    _systemEvents.tryEmit("Permiso Bluetooth denegado en runtime: ${e.message}")
                    break
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startSensorServer(transportMode: TransportMode, port: Int = DEFAULT_TCP_PORT) {
        manualDisconnectRequested.set(false)
        connectionJob?.cancel()
        serverAcceptJob?.cancel()

        serverAcceptJob = ioScope.launch {
            when (transportMode) {
                TransportMode.BLUETOOTH_RFCOMM -> {
                    val btManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                    val adapter = btManager?.adapter
                    if (adapter == null || !adapter.isEnabled) {
                        _systemEvents.tryEmit("Bluetooth apagado o no disponible para modo Servidor.")
                        _connectionState.value = ConnectionState.Disconnected
                        return@launch
                    }
                    try {
                        adapter.cancelDiscovery()
                        val serverSocket = adapter.listenUsingRfcommWithServiceRecord(
                            BT_SERVICE_NAME,
                            SPP_UUID
                        )
                        activeBtServerSocket = serverSocket
                        _connectionState.value = ConnectionState.ListeningServer("BT RFCOMM ($BT_SERVICE_NAME)")
                        _systemEvents.tryEmit("Servidor Bluetooth RFCOMM escuchando UUID SPP...")

                        while (isActive && !manualDisconnectRequested.get()) {
                            val clientSocket = serverSocket.accept()
                            activeBtSocket = clientSocket
                            activeOutputStream = clientSocket.outputStream
                            val remoteName = clientSocket.remoteDevice?.name
                                ?: clientSocket.remoteDevice?.address
                                ?: "Cliente BT"
                            _connectionState.value = ConnectionState.Connected(
                                endpointName = "Estación: $remoteName",
                                transport = TransportMode.BLUETOOTH_RFCOMM
                            )
                            _systemEvents.tryEmit("Estación de Control conectada por Bluetooth: $remoteName")
                            try {
                                readStreamLinesLoop(clientSocket.inputStream) { line ->
                                    _incomingFrames.tryEmit(line)
                                }
                            } catch (e: IOException) {
                                _systemEvents.tryEmit("Cliente BT desconectado (${e.message}). Reanudando escucha...")
                            } finally {
                                closeSocketQuietly(clientSocket)
                                activeBtSocket = null
                                activeOutputStream = null
                                if (!manualDisconnectRequested.get()) {
                                    _connectionState.value = ConnectionState.ListeningServer("BT RFCOMM ($BT_SERVICE_NAME)")
                                }
                            }
                        }
                    } catch (e: IOException) {
                        if (!manualDisconnectRequested.get()) {
                            _systemEvents.tryEmit("Error en BluetoothServerSocket: ${e.message}")
                            _connectionState.value = ConnectionState.Disconnected
                        }
                    } catch (e: SecurityException) {
                        _systemEvents.tryEmit("Permisos Bluetooth insuficientes para abrir servidor RFCOMM.")
                        _connectionState.value = ConnectionState.Disconnected
                    } finally {
                        closeBtServerQuietly(activeBtServerSocket)
                        activeBtServerSocket = null
                    }
                }

                TransportMode.TCP_WIFI_LAN, TransportMode.LOCAL_LOOPBACK -> {
                    val localIp = getLocalIpAddress()
                    try {
                        val serverSocket = ServerSocket()
                        serverSocket.reuseAddress = true
                        serverSocket.bind(InetSocketAddress(port))
                        activeTcpServerSocket = serverSocket
                        _connectionState.value = ConnectionState.ListeningServer("TCP $localIp:$port")
                        _systemEvents.tryEmit("Nodo Sensor OT escuchando en $localIp:$port (Dispatchers.IO)")

                        while (isActive && !manualDisconnectRequested.get()) {
                            val clientSocket = serverSocket.accept()
                            clientSocket.keepAlive = true
                            activeTcpSocket = clientSocket
                            activeOutputStream = clientSocket.getOutputStream()
                            val remoteHost = clientSocket.inetAddress?.hostAddress ?: "Cliente TCP"
                            _connectionState.value = ConnectionState.Connected(
                                endpointName = "Estación: $remoteHost",
                                transport = transportMode
                            )
                            _systemEvents.tryEmit("Estación de Control conectada desde $remoteHost")
                            try {
                                readStreamLinesLoop(clientSocket.getInputStream()) { line ->
                                    _incomingFrames.tryEmit(line)
                                }
                            } catch (e: IOException) {
                                _systemEvents.tryEmit("Estación desconectada (${e.message}). Esperando reconexión...")
                            } finally {
                                closeTcpSocketQuietly(clientSocket)
                                activeTcpSocket = null
                                activeOutputStream = null
                                if (!manualDisconnectRequested.get()) {
                                    _connectionState.value = ConnectionState.ListeningServer("TCP $localIp:$port")
                                }
                            }
                        }
                    } catch (e: IOException) {
                        if (!manualDisconnectRequested.get()) {
                            _systemEvents.tryEmit("Error en ServerSocket TCP: ${e.message}")
                            _connectionState.value = ConnectionState.Disconnected
                        }
                    } finally {
                        closeTcpServerQuietly(activeTcpServerSocket)
                        activeTcpServerSocket = null
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun runBluetoothClientSession(
        macAddress: String,
        onConnectedCallback: () -> Unit
    ) = withContext(Dispatchers.IO) {
        val btManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter ?: throw IOException("Adaptador Bluetooth no disponible")
        if (!adapter.isEnabled) throw IOException("Bluetooth desactivado en el dispositivo")

        val device = try {
            adapter.getRemoteDevice(macAddress.uppercase())
        } catch (_: IllegalArgumentException) {
            throw IOException("Dirección MAC Bluetooth inválida: $macAddress")
        }

        var socket: BluetoothSocket? = null
        try {
            adapter.cancelDiscovery()
            socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            activeBtSocket = socket
            socket.connect()
            activeOutputStream = socket.outputStream
            onConnectedCallback()

            val deviceLabel = device.name ?: macAddress
            _connectionState.value = ConnectionState.Connected(
                endpointName = deviceLabel,
                transport = TransportMode.BLUETOOTH_RFCOMM
            )
            _systemEvents.tryEmit("Conectado vía Bluetooth RFCOMM a $deviceLabel (contador backoff reiniciado)")

            readStreamLinesLoop(socket.inputStream) { line ->
                _incomingFrames.tryEmit(line)
            }
        } finally {
            activeOutputStream = null
            activeBtSocket = null
            closeSocketQuietly(socket)
        }
    }

    private suspend fun runTcpClientSession(
        host: String,
        port: Int,
        mode: TransportMode,
        onConnectedCallback: () -> Unit
    ) = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        try {
            socket = Socket()
            socket.connect(InetSocketAddress(host, port), SOCKET_CONNECT_TIMEOUT_MS)
            socket.keepAlive = true
            activeTcpSocket = socket
            activeOutputStream = socket.getOutputStream()
            onConnectedCallback()

            val endpointLabel = if (mode == TransportMode.LOCAL_LOOPBACK) {
                "ESP32 / Nodo OT Loopback ($host:$port)"
            } else {
                "Nodo TCP ($host:$port)"
            }
            _connectionState.value = ConnectionState.Connected(
                endpointName = endpointLabel,
                transport = mode
            )
            _systemEvents.tryEmit("Socket TCP establecido con $endpointLabel (contador backoff reiniciado)")

            readStreamLinesLoop(socket.getInputStream()) { line ->
                _incomingFrames.tryEmit(line)
            }
        } finally {
            activeOutputStream = null
            activeTcpSocket = null
            closeTcpSocketQuietly(socket)
        }
    }

    private fun ensureLoopbackSensorServerRunning() {
        if (loopbackServerJob?.isActive == true && loopbackServerSocket?.isClosed == false) return
        loopbackServerJob?.cancel()
        loopbackServerJob = ioScope.launch {
            var server: ServerSocket? = null
            try {
                server = ServerSocket()
                server.reuseAddress = true
                server.bind(InetSocketAddress("127.0.0.1", DEFAULT_TCP_PORT))
                loopbackServerSocket = server
                while (isActive) {
                    val client = server.accept()
                    loopbackClientSocket = client
                    loopbackOutputStream = client.getOutputStream()
                    try {
                        readStreamLinesLoop(client.getInputStream()) { line ->
                            _incomingLoopbackServerFrames.tryEmit(line)
                        }
                    } catch (_: IOException) {
                        // Espera reconexión automática
                    } finally {
                        loopbackOutputStream = null
                        loopbackClientSocket = null
                        closeTcpSocketQuietly(client)
                    }
                }
            } catch (_: IOException) {
                // Puerto ocupado por modo Servidor explícito
            } finally {
                loopbackServerSocket = null
                closeTcpServerQuietly(server)
            }
        }
    }

    private suspend fun readStreamLinesLoop(
        inputStream: InputStream,
        onLineReceived: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
        while (currentCoroutineContext().isActive) {
            val line = reader.readLine()
                ?: throw IOException("Fin de stream (EOF) detectado en el socket remoto")
            if (line.isNotBlank()) {
                onLineReceived(line)
            }
        }
    }

    suspend fun sendFrame(rawLineWithNewline: String): Boolean = withContext(Dispatchers.IO) {
        val out = activeOutputStream ?: return@withContext false
        return@withContext try {
            val formatted = if (rawLineWithNewline.endsWith("\n")) {
                rawLineWithNewline
            } else {
                "$rawLineWithNewline\n"
            }
            synchronized(out) {
                out.write(formatted.toByteArray(Charsets.UTF_8))
                out.flush()
            }
            true
        } catch (e: IOException) {
            _systemEvents.tryEmit("Error al transmitir trama (${e.message}). Socket interrumpido.")
            forceSocketAbortInternal()
            false
        }
    }

    suspend fun sendFromLoopbackSensorServer(rawLineWithNewline: String): Boolean = withContext(Dispatchers.IO) {
        val out = loopbackOutputStream ?: activeOutputStream ?: return@withContext false
        return@withContext try {
            val formatted = if (rawLineWithNewline.endsWith("\n")) {
                rawLineWithNewline
            } else {
                "$rawLineWithNewline\n"
            }
            synchronized(out) {
                out.write(formatted.toByteArray(Charsets.UTF_8))
                out.flush()
            }
            true
        } catch (_: IOException) {
            false
        }
    }

    fun simulateAbruptHardwareDisconnect() {
        ioScope.launch {
            _systemEvents.tryEmit("Corte abrupto simulado (IOException). Activando backoff 2s->4s->8s->16s->30s...")
            forceSocketAbortInternal()
        }
    }

    private fun forceSocketAbortInternal() {
        closeSocketQuietly(activeBtSocket)
        closeTcpSocketQuietly(activeTcpSocket)
        closeTcpSocketQuietly(loopbackClientSocket)
    }

    fun disconnectGracefully() {
        manualDisconnectRequested.set(true)
        connectionJob?.cancel()
        serverAcceptJob?.cancel()
        ioScope.launch {
            activeOutputStream = null
            closeSocketQuietly(activeBtSocket)
            closeBtServerQuietly(activeBtServerSocket)
            closeTcpSocketQuietly(activeTcpSocket)
            closeTcpServerQuietly(activeTcpServerSocket)
            closeTcpSocketQuietly(loopbackClientSocket)
            activeBtSocket = null
            activeBtServerSocket = null
            activeTcpSocket = null
            activeTcpServerSocket = null
            loopbackClientSocket = null
            _connectionState.value = ConnectionState.Disconnected
            _systemEvents.tryEmit("Conexión inalámbrica cerrada limpiamente (finally close).")
        }
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val BT_SERVICE_NAME = "INACAP_ESP32_BARRERA_OT"
        const val DEFAULT_TCP_PORT = 8888
        private const val SOCKET_CONNECT_TIMEOUT_MS = 3500
        const val MAX_BACKOFF_MS = 30_000L

        /**
         * Backoff exponencial: 2s -> 4s -> 8s -> 16s -> tope 30s.
         */
        fun calculateExponentialBackoffMs(attempt: Int): Long {
            val safeAttempt = attempt.coerceAtLeast(1)
            val shift = (safeAttempt - 1).coerceAtMost(4) // 2^0..2^4 => 2s, 4s, 8s, 16s, 32s
            val computed = 2_000L shl shift
            return computed.coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    private fun closeSocketQuietly(socket: BluetoothSocket?) {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
    }

    private fun closeBtServerQuietly(server: BluetoothServerSocket?) {
        try {
            server?.close()
        } catch (_: IOException) {
        }
    }

    private fun closeTcpSocketQuietly(socket: Socket?) {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
    }

    private fun closeTcpServerQuietly(server: ServerSocket?) {
        try {
            server?.close()
        } catch (_: IOException) {
        }
    }
}
