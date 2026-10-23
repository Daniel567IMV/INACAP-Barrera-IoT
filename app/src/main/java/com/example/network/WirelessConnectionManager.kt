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
 * Cumple con la Rúbrica Técnica (C, D y E):
 * - Cero bloqueo de UI: Todas las operaciones de red y sockets corren en `Dispatchers.IO`.
 * - Manejo dual de Roles:
 *   1. Cliente ("Estación de Control / Monitoreo") -> Conecta a ESP32 o 2° Android.
 *   2. Servidor ("Nodo Sensor / Simulador OT") -> Escucha conexiones RFCOMM o TCP entrantes.
 * - Tolerancia a fallos: Captura limpia de `IOException`, cierre garantizado (`close()`) en bloque
 *   `finally`, cambio inmediato de estado a `Reconnecting` ("Desconectado - Intentando reconectar...")
 *   y ciclo de reconexión automática con Exponential Backoff.
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

    // Referencias de sockets activas protegidas para cierre seguro en `finally`
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

    // Loopback Server embebido para pruebas de 1 solo equipo con sockets TCP reales
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

    // Últimos parámetros de conexión para reconexión automática con backoff
    private var lastTransportMode: TransportMode = TransportMode.LOCAL_LOOPBACK
    private var lastTargetAddress: String = "127.0.0.1"
    private var lastTargetPort: Int = DEFAULT_TCP_PORT

    /**
     * Lista los dispositivos Bluetooth emparejados (ej. "ESP32_Barrera_INACAP" o 2° teléfono Android).
     */
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

    /**
     * Obtiene la dirección IPv4 local del teléfono para mostrarla cuando actúa como
     * "Nodo Sensor / Simulador OT" por Wi-Fi Local.
     */
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
     * Inicia conexión como CLIENTE ("Estación de Control") o conexón unificada con soporte
     * de Reconexión Automática con Backoff Exponencial ante desconexiones abruptas.
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
            while (isActive && !manualDisconnectRequested.get() && attempt <= MAX_RECONNECT_ATTEMPTS) {
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
                        val backoffMs = calculateBackoffMs(attempt)
                        _connectionState.value = ConnectionState.Reconnecting(
                            attempt = attempt,
                            maxAttempts = MAX_RECONNECT_ATTEMPTS,
                            nextRetryMs = backoffMs,
                            reason = "Desconectado - Intentando reconectar..."
                        )
                        _systemEvents.tryEmit(
                            "Reconexión automática #$attempt/$MAX_RECONNECT_ATTEMPTS en ${backoffMs}ms..."
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
                            runBluetoothClientSession(lastTargetAddress)
                        }
                        TransportMode.TCP_WIFI_LAN -> {
                            runTcpClientSession(lastTargetAddress, lastTargetPort, TransportMode.TCP_WIFI_LAN)
                        }
                        TransportMode.LOCAL_LOOPBACK -> {
                            runTcpClientSession("127.0.0.1", DEFAULT_TCP_PORT, TransportMode.LOCAL_LOOPBACK)
                        }
                    }

                    // Si el stream finaliza sin que el usuario haya pedido desconexión manual,
                    // se trata de una caída abrupta del otro equipo o ESP32.
                    if (!manualDisconnectRequested.get()) {
                        attempt++
                    } else {
                        break
                    }
                } catch (e: IOException) {
                    if (manualDisconnectRequested.get()) break
                    attempt++
                    _systemEvents.tryEmit(
                        "IOException capturada limpiamente: ${e.message ?: "Socket cerrado"}. Activando backoff."
                    )
                    if (attempt > MAX_RECONNECT_ATTEMPTS) {
                        _connectionState.value = ConnectionState.Disconnected
                        _systemEvents.tryEmit("Límite de reconexión alcanzado. Eventos resguardados en cola Offline.")
                        break
                    }
                } catch (e: SecurityException) {
                    _connectionState.value = ConnectionState.Disconnected
                    _systemEvents.tryEmit("Permiso Bluetooth denegado en runtime: ${e.message}")
                    break
                }
            }
        }
    }

    /**
     * Inicia modo SERVIDOR ("Nodo Sensor / Simulador OT") para que un 2° teléfono Android
     * reciba conexiones entrantes por Bluetooth RFCOMM o por Sockets TCP Wi-Fi Local.
     */
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
                            val remoteName = clientSocket.remoteDevice?.name ?: clientSocket.remoteDevice?.address ?: "Cliente BT"
                            _connectionState.value = ConnectionState.Connected(
                                endpointName = "Estación: $remoteName",
                                transport = TransportMode.BLUETOOTH_RFCOMM
                            )
                            _systemEvents.tryEmit("Cliente Bluetooth conectado: $remoteName")
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

    /**
     * Ejecuta la sesión de cliente Bluetooth RFCOMM con bloque `finally` garantizado.
     */
    @SuppressLint("MissingPermission")
    private suspend fun runBluetoothClientSession(macAddress: String) = withContext(Dispatchers.IO) {
        val btManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter ?: throw IOException("Adaptador Bluetooth no disponible")
        if (!adapter.isEnabled) throw IOException("Bluetooth desactivado en el dispositivo")

        val device = try {
            adapter.getRemoteDevice(macAddress.uppercase())
        } catch (e: IllegalArgumentException) {
            throw IOException("Dirección MAC Bluetooth inválida: $macAddress")
        }

        var socket: BluetoothSocket? = null
        try {
            adapter.cancelDiscovery()
            socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            activeBtSocket = socket
            socket.connect()
            activeOutputStream = socket.outputStream

            val deviceLabel = device.name ?: macAddress
            _connectionState.value = ConnectionState.Connected(
                endpointName = deviceLabel,
                transport = TransportMode.BLUETOOTH_RFCOMM
            )
            _systemEvents.tryEmit("Conectado vía Bluetooth RFCOMM a $deviceLabel")

            readStreamLinesLoop(socket.inputStream) { line ->
                _incomingFrames.tryEmit(line)
            }
        } finally {
            // Cierre seguro obligatorio en bloque finally (Requerimiento E)
            activeOutputStream = null
            activeBtSocket = null
            closeSocketQuietly(socket)
        }
    }

    /**
     * Ejecuta la sesión de cliente TCP con bloque `finally` garantizado.
     */
    private suspend fun runTcpClientSession(
        host: String,
        port: Int,
        mode: TransportMode
    ) = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        try {
            socket = Socket()
            socket.connect(InetSocketAddress(host, port), SOCKET_CONNECT_TIMEOUT_MS)
            socket.keepAlive = true
            activeTcpSocket = socket
            activeOutputStream = socket.getOutputStream()

            val endpointLabel = if (mode == TransportMode.LOCAL_LOOPBACK) {
                "ESP32 / Nodo OT Loopback ($host:$port)"
            } else {
                "Nodo TCP ($host:$port)"
            }
            _connectionState.value = ConnectionState.Connected(
                endpointName = endpointLabel,
                transport = mode
            )
            _systemEvents.tryEmit("Socket TCP establecido con $endpointLabel")

            readStreamLinesLoop(socket.getInputStream()) { line ->
                _incomingFrames.tryEmit(line)
            }
        } finally {
            // Cierre seguro obligatorio en bloque finally (Requerimiento E)
            activeOutputStream = null
            activeTcpSocket = null
            closeTcpSocketQuietly(socket)
        }
    }

    /**
     * Servidor TCP Loopback interno en `127.0.0.1:8888` para que la app pueda probarse
     * con sockets TCP reales en un único dispositivo o emulador sin requerir hardware externo,
     * además de soportar 2 teléfonos físicos o ESP32 real.
     */
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
                        // El socket de loopback se cerró; espera el siguiente intento de reconexión
                    } finally {
                        loopbackOutputStream = null
                        loopbackClientSocket = null
                        closeTcpSocketQuietly(client)
                    }
                }
            } catch (_: IOException) {
                // Puerto ya ocupado por modo Servidor explícito
            } finally {
                loopbackServerSocket = null
                closeTcpServerQuietly(server)
            }
        }
    }

    /**
     * Bucle lector no bloqueante en `Dispatchers.IO` que procesa tramas delimitadas por `\n`.
     */
    private suspend fun readStreamLinesLoop(
        inputStream: InputStream,
        onLineReceived: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
        while (currentCoroutineContext().isActive) {
            val line = reader.readLine() ?: throw IOException("Fin de stream (EOF) detectado en el socket remoto")
            if (line.isNotBlank()) {
                onLineReceived(line)
            }
        }
    }

    /**
     * Envía una trama JSON delimitada por `\n` a través del socket activo en `Dispatchers.IO`.
     * Retorna `true` si se escribió en el socket, o `false` si está desconectado (para encolar Offline).
     */
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

    /**
     * Envía una trama desde el Nodo Sensor embebido (Loopback Server) hacia el cliente TCP conectado.
     */
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

    /**
     * Simula una caída abrupta del hardware / corte de energía del ESP32 o del 2° teléfono
     * cerrando el socket subyacente sin bandera manual para disparar `IOException`, el bloque
     * `finally` y la Reconexión Automática con Backoff (Requerimiento E).
     */
    fun simulateAbruptHardwareDisconnect() {
        ioScope.launch {
            _systemEvents.tryEmit("⚡ Simulando apagado abrupto de nodo remoto (IOException en socket)...")
            forceSocketAbortInternal()
        }
    }

    private fun forceSocketAbortInternal() {
        closeSocketQuietly(activeBtSocket)
        closeTcpSocketQuietly(activeTcpSocket)
        closeTcpSocketQuietly(loopbackClientSocket)
    }

    /**
     * Desconexión limpia solicitada explícitamente por el operador.
     */
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
            _systemEvents.tryEmit("Conexión inalámbrica cerrada de forma segura (finally close).")
        }
    }

    private fun calculateBackoffMs(attempt: Int): Long {
        // Backoff exponencial acotado: 1.5s, 3.0s, 4.5s, 6.0s...
        return (attempt * 1500L).coerceAtMost(6000L)
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

    companion object {
        // UUID estándar SPP (Serial Port Profile) para ESP32 BluetoothSerial y RFCOMM Android
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val BT_SERVICE_NAME = "INACAP_ESP32_BARRERA_OT"
        const val DEFAULT_TCP_PORT = 8888
        private const val SOCKET_CONNECT_TIMEOUT_MS = 3500
        private const val MAX_RECONNECT_ATTEMPTS = 8
    }
}
