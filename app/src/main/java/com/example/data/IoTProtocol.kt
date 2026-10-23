package com.example.data

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rol del dispositivo en la arquitectura inalámbrica (2 teléfonos físicos o Teléfono <-> ESP32).
 */
enum class DeviceRole(val title: String, val subtitle: String) {
    CONTROL_STATION(
        title = "Estación de Control / Monitoreo",
        subtitle = "Cliente OT • Telemetría en vivo y envío de comandos seguros"
    ),
    SENSOR_NODE(
        title = "Nodo Sensor / Simulador OT",
        subtitle = "Servidor ESP32 / 2° Android • HC-SR04 + Servo SG90 + LCD 16x2"
    )
}

/**
 * Canal de transporte físico / lógico para conectar el dispositivo.
 */
enum class TransportMode(val label: String, val description: String) {
    BLUETOOTH_RFCOMM(
        label = "Bluetooth RFCOMM",
        description = "SPP UUID 00001101-0000-1000-8000-00805F9B34FB (ESP32 o 2° Android)"
    ),
    TCP_WIFI_LAN(
        label = "Sockets TCP Wi-Fi",
        description = "Socket TCP/IP en red local o Hotspot (Puerto 8888)"
    ),
    LOCAL_LOOPBACK(
        label = "Loopback TCP Local",
        description = "Socket TCP 127.0.0.1:8888 (Prueba en vivo Cliente/Servidor en 1 equipo)"
    )
}

/**
 * Estados de conexión inalámbrica resiliente con soporte de reconexión automática.
 */
sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data class Connecting(val targetName: String) : ConnectionState()
    data class ListeningServer(val endpointInfo: String) : ConnectionState()
    data class Connected(val endpointName: String, val transport: TransportMode) : ConnectionState()
    data class Reconnecting(
        val attempt: Int,
        val maxAttempts: Int,
        val nextRetryMs: Long,
        val reason: String
    ) : ConnectionState()
}

/**
 * Dispositivo Bluetooth emparejado para mostrar en el selector.
 */
data class PairedBtDevice(
    val name: String,
    val address: String
)

/**
 * Trama de Telemetría enviada por el ESP32 o el 2° Android (Nodo Sensor) hacia la Estación de Control:
 * Ejemplo: {"dist":8,"estado":"OCUPADO","barrera":0,"modo":"AUTO","crc":"OK"}\n
 */
data class TelemetryFrame(
    val dist: Int,
    val estado: String,    // "LIBRE" o "OCUPADO"
    val barrera: Int,      // 90 (Abierta/Arriba) o 0 (Cerrada/Abajo)
    val modo: String,      // "AUTO" o "MANUAL"
    val crc: String = "OK",
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJsonLine(): String {
        val computedCrc = computeTelemetryCrc(dist, estado, barrera, modo)
        val json = JSONObject().apply {
            put("dist", dist)
            put("estado", estado)
            put("barrera", barrera)
            put("modo", modo)
            put("crc", computedCrc)
        }
        return "$json\n"
    }

    /**
     * Formatea las 2 líneas exactas de 16 caracteres de la pantalla física LCD I2C (0x27, 16x2).
     */
    fun toLcdLines(): Pair<String, String> {
        val line1 = String.format(Locale.US, "DIST:%3dcm %5s", dist.coerceIn(0, 999), modo.take(5))
            .padEnd(16, ' ').take(16)
        val gateText = if (barrera >= 45) "90G" else " 0G"
        val line2 = String.format(Locale.US, "%-7s BAR:%4s", estado.take(7), gateText)
            .padEnd(16, ' ').take(16)
        return line1 to line2
    }

    companion object {
        fun computeTelemetryCrc(dist: Int, estado: String, barrera: Int, modo: String): String {
            val raw = "$dist|$estado|$barrera|$modo|ISO27400"
            var sum = 0
            for (ch in raw) {
                sum = (sum * 31 + ch.code) and 0xFFFF
            }
            return String.format(Locale.US, "OK-%04X", sum)
        }

        fun fromJsonLine(line: String): TelemetryFrame? {
            return try {
                val clean = line.trim()
                if (!clean.startsWith("{") || !clean.contains("\"dist\"")) return null
                val obj = JSONObject(clean)
                val dist = obj.optInt("dist", 25).coerceIn(0, 400)
                val estado = obj.optString("estado", if (dist in 1..9) "OCUPADO" else "LIBRE")
                val barrera = obj.optInt("barrera", if (dist in 1..9) 0 else 90)
                val modo = obj.optString("modo", "AUTO")
                val crc = obj.optString("crc", "OK")
                TelemetryFrame(
                    dist = dist,
                    estado = estado,
                    barrera = barrera,
                    modo = modo,
                    crc = crc
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * Trama de Comando enviada por la Estación de Control hacia el ESP32 o el Nodo Sensor:
 * Ejemplo: {"cmd":"SET_BARRERA","val":90,"modo":"MANUAL","token":"..."}\n
 */
data class CommandFrame(
    val cmd: String,       // "SET_BARRERA", "SET_MODO", "REQ_STATUS", "SYNC_EVENT"
    val value: Int,        // 90, 0, o -1
    val modo: String,      // "MANUAL" o "AUTO"
    val token: String
) {
    fun toJsonLine(): String {
        val json = JSONObject().apply {
            put("cmd", cmd)
            put("val", value)
            put("modo", modo)
            put("token", token)
        }
        return "$json\n"
    }

    companion object {
        fun fromJsonLine(line: String): CommandFrame? {
            return try {
                val clean = line.trim()
                if (!clean.startsWith("{") || !clean.contains("\"cmd\"")) return null
                val obj = JSONObject(clean)
                CommandFrame(
                    cmd = obj.optString("cmd", "REQ_STATUS"),
                    value = obj.optInt("val", 0),
                    modo = obj.optString("modo", "AUTO"),
                    token = obj.optString("token", "")
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * Evento almacenado en la cola cifrada local cuando la conexión inalámbrica cae y
 * sincronizado automáticamente al reconectar (Requerimiento D - Offline Sync).
 */
data class OfflineSyncEvent(
    val id: String,
    val type: String,          // "COMANDO_PENDIENTE" o "TELEMETRIA_RETENIDA"
    val payloadJson: String,
    val createdAt: Long,
    val synced: Boolean = false
) {
    fun formattedTime(): String {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(createdAt))
    }

    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type)
        put("payloadJson", payloadJson.trim())
        put("createdAt", createdAt)
        put("synced", synced)
    }

    companion object {
        fun fromJsonObject(obj: JSONObject): OfflineSyncEvent = OfflineSyncEvent(
            id = obj.optString("id", System.currentTimeMillis().toString()),
            type = obj.optString("type", "EVENTO"),
            payloadJson = obj.optString("payloadJson", "{}"),
            createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
            synced = obj.optBoolean("synced", false)
        )

        fun toJsonArrayString(list: List<OfflineSyncEvent>): String {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJsonObject()) }
            return arr.toString()
        }

        fun fromJsonArrayString(raw: String?): List<OfflineSyncEvent> {
            if (raw.isNullOrBlank()) return emptyList()
            return try {
                val arr = JSONArray(raw)
                val result = mutableListOf<OfflineSyncEvent>()
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    result.add(fromJsonObject(item))
                }
                result
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}

/**
 * Entrada en la bitácora de auditoría en tiempo real (ISO/IEC 27400).
 */
data class ProtocolAuditLog(
    val id: Long = System.nanoTime(),
    val timestamp: String = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date()),
    val direction: String, // "TX ->", "RX <-", "SEC #", "SYNC *"
    val rawFrame: String,
    val isWarning: Boolean = false
)
