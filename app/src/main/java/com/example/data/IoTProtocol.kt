package com.example.data

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Rol del dispositivo en la arquitectura inalámbrica (2 teléfonos físicos o Teléfono <-> ESP32).
 */
enum class DeviceRole(val title: String, val subtitle: String) {
    CONTROL_STATION(
        title = "Estación de Control / Monitoreo",
        subtitle = "Cliente OT • Plazas desde entrada, telemetría y comandos validados"
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
        description = "Socket TCP 127.0.0.1:8888 (Prueba Cliente/Servidor real en 1 equipo)"
    )
}

/**
 * Estados de conexión inalámbrica resiliente con soporte de reconexión automática con backoff
 * y detección de "sin datos recientes".
 */
sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data class Connecting(val targetName: String) : ConnectionState()
    data class ListeningServer(val endpointInfo: String) : ConnectionState()
    data class Connected(val endpointName: String, val transport: TransportMode) : ConnectionState()
    data class Reconnecting(
        val attempt: Int,
        val nextRetryMs: Long,
        val reason: String
    ) : ConnectionState()
}

/**
 * Estado consolidado visible en la UI (Requerimiento 2 ES3):
 * `CONECTADO`, `RECONECTANDO`, `DESCONECTADO` y `SIN DATOS RECIENTES`.
 */
enum class UiConnectionHealth(val displayLabel: String) {
    CONNECTED("CONECTADO"),
    RECONNECTING("RECONECTANDO"),
    DISCONNECTED("DESCONECTADO"),
    NO_RECENT_DATA("SIN DATOS RECIENTES")
}

/**
 * Dispositivo Bluetooth emparejado para mostrar en el selector.
 */
data class PairedBtDevice(
    val name: String,
    val address: String
)

/**
 * Resultado de validación de parámetros/rangos en la UI antes de enviar un comando.
 */
sealed class CommandValidationResult {
    data object Valid : CommandValidationResult()
    data class Invalid(val reason: String) : CommandValidationResult()
}

/**
 * Trama de Telemetría bidireccional del Estacionamiento Inteligente:
 * {"type":"TELEMETRY","dist":8,"estado":"OCUPADO","barrera":0,"modo":"AUTO","plazasLibres":11,"capacidadTotal":12,"crc":"OK-4F2A","ts":1732550000000}\n
 */
data class TelemetryFrame(
    val dist: Int,
    val estado: String,          // "LIBRE" o "OCUPADO"
    val barrera: Int,            // 90 (Abierta/Arriba) o 0 (Cerrada/Abajo)
    val modo: String,            // "AUTO" o "MANUAL"
    val plazasLibres: Int = 11,  // Plazas disponibles desde la entrada
    val capacidadTotal: Int = 12,// Capacidad total del estacionamiento
    val crc: String = "OK",
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJsonLine(): String {
        val safeCap = capacidadTotal.coerceIn(MIN_CAPACITY, MAX_CAPACITY)
        val safeFree = plazasLibres.coerceIn(0, safeCap)
        val computedCrc = computeTelemetryCrc(dist, estado, barrera, modo, safeFree, safeCap)
        val json = JSONObject().apply {
            put("type", "TELEMETRY")
            put("dist", dist)
            put("estado", estado)
            put("barrera", barrera)
            put("modo", modo)
            put("plazasLibres", safeFree)
            put("capacidadTotal", safeCap)
            put("crc", computedCrc)
            put("ts", timestamp)
        }
        return "$json\n"
    }

    /**
     * Formatea las 2 líneas exactas de 16 caracteres del Display LCD I2C (`0x27`, 16x2).
     * Fila 1: Plazas libres/total desde la entrada + distancia HC-SR04.
     * Fila 2: Estado de plaza + ángulo de barrera + modo.
     */
    fun toLcdLines(): Pair<String, String> {
        val line1 = String.format(
            Locale.US,
            "L:%2d/%2d D:%3dcm",
            plazasLibres.coerceIn(0, 99),
            capacidadTotal.coerceIn(1, 99),
            dist.coerceIn(0, 999)
        ).padEnd(16, ' ').take(16)

        val modeShort = if (modo.equals("MANUAL", ignoreCase = true)) "MAN" else "AUT"
        val line2 = String.format(
            Locale.US,
            "%-7s %2dG %3s",
            estado.take(7),
            barrera.coerceIn(0, 90),
            modeShort
        ).padEnd(16, ' ').take(16)

        return line1 to line2
    }

    companion object {
        const val MIN_DISTANCE_CM = 0
        const val MAX_DISTANCE_CM = 400
        const val MIN_BARRIER_ANGLE = 0
        const val MAX_BARRIER_ANGLE = 90
        const val MIN_CAPACITY = 1
        const val MAX_CAPACITY = 200

        fun computeTelemetryCrc(
            dist: Int,
            estado: String,
            barrera: Int,
            modo: String,
            plazasLibres: Int,
            capacidadTotal: Int
        ): String {
            val raw = "$dist|$estado|$barrera|$modo|$plazasLibres/$capacidadTotal|ES3"
            var sum = 0
            for (ch in raw) {
                sum = (sum * 31 + ch.code) and 0xFFFF
            }
            return String.format(Locale.US, "OK-%04X", sum)
        }
    }
}

/**
 * Trama de Comando enviada por la Estación de Control hacia el ESP32 o el Nodo Sensor:
 * {"type":"CMD","reqId":"A1B2C3","cmd":"SET_BARRERA","val":90,"modo":"MANUAL","token":"INA-..."}\n
 */
data class CommandFrame(
    val reqId: String = UUID.randomUUID().toString().take(6).uppercase(Locale.US),
    val cmd: String,       // "SET_BARRERA", "SET_MODO", "SET_CAPACIDAD", "SET_PLAZAS", "REQ_STATUS", "SYNC_RECORD"
    val value: Int,        // 90, 0, capacidad o plazas
    val modo: String,      // "MANUAL" o "AUTO"
    val token: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJsonLine(): String {
        val json = JSONObject().apply {
            put("type", "CMD")
            put("reqId", reqId)
            put("cmd", cmd)
            put("val", value)
            put("modo", modo)
            put("token", token)
            put("ts", timestamp)
        }
        return "$json\n"
    }

    companion object {
        val ALLOWED_COMMANDS = setOf(
            "SET_BARRERA",
            "SET_MODO",
            "SET_CAPACIDAD",
            "SET_PLAZAS",
            "REQ_STATUS",
            "SYNC_RECORD"
        )

        /**
         * Valida estrictamente los comandos y rangos antes de transmitir desde la UI
         * o al procesarlos en el Nodo Sensor OT.
         */
        fun validate(
            cmd: String,
            value: Int,
            modo: String,
            currentCapacity: Int
        ): CommandValidationResult {
            val normalizedCmd = cmd.trim().uppercase(Locale.US)
            if (normalizedCmd !in ALLOWED_COMMANDS) {
                return CommandValidationResult.Invalid("Comando '$cmd' no reconocido por el protocolo OT.")
            }
            return when (normalizedCmd) {
                "SET_BARRERA" -> {
                    if (value !in TelemetryFrame.MIN_BARRIER_ANGLE..TelemetryFrame.MAX_BARRIER_ANGLE) {
                        CommandValidationResult.Invalid(
                            "Ángulo de barrera fuera de rango ($value°). Rango válido: 0° a 90°."
                        )
                    } else if (value != 0 && value != 90) {
                        CommandValidationResult.Invalid(
                            "Para seguridad vehicular la barrera solo admite 0° (CERRADA) o 90° (ABIERTA)."
                        )
                    } else {
                        CommandValidationResult.Valid
                    }
                }
                "SET_MODO" -> {
                    val m = modo.trim().uppercase(Locale.US)
                    if (m != "AUTO" && m != "MANUAL") {
                        CommandValidationResult.Invalid("Modo inválido '$modo'. Solo se permite AUTO o MANUAL.")
                    } else {
                        CommandValidationResult.Valid
                    }
                }
                "SET_CAPACIDAD" -> {
                    if (value !in TelemetryFrame.MIN_CAPACITY..TelemetryFrame.MAX_CAPACITY) {
                        CommandValidationResult.Invalid(
                            "Capacidad total fuera de rango ($value). Debe estar entre 1 y 200 plazas."
                        )
                    } else {
                        CommandValidationResult.Valid
                    }
                }
                "SET_PLAZAS" -> {
                    val maxCap = currentCapacity.coerceIn(TelemetryFrame.MIN_CAPACITY, TelemetryFrame.MAX_CAPACITY)
                    if (value !in 0..maxCap) {
                        CommandValidationResult.Invalid(
                            "Plazas libres fuera de rango ($value). Debe estar entre 0 y $maxCap."
                        )
                    } else {
                        CommandValidationResult.Valid
                    }
                }
                else -> CommandValidationResult.Valid
            }
        }
    }
}

/**
 * Trama de Confirmación Positiva (`ACK`) recibida desde el ESP32 o Nodo Sensor:
 * {"type":"ACK","cmd":"SET_BARRERA","reqId":"A1B2C3","detail":"Barrera 90° aplicada","ts":...}\n
 */
data class AckFrame(
    val cmd: String,
    val reqId: String,
    val detail: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJsonLine(): String {
        val json = JSONObject().apply {
            put("type", "ACK")
            put("cmd", cmd)
            put("reqId", reqId)
            put("detail", detail)
            put("ts", timestamp)
        }
        return "$json\n"
    }
}

/**
 * Trama de Error (`ERR`) estructurada ante comandos inexistentes, valores fuera de rango,
 * tramas corruptas o tokens no autorizados:
 * {"type":"ERR","code":"OUT_OF_RANGE","reqId":"A1B2C3","detail":"...","ts":...}\n
 */
data class ErrFrame(
    val code: String,      // "UNKNOWN_CMD", "OUT_OF_RANGE", "MALFORMED_JSON", "UNAUTHORIZED"
    val reqId: String,
    val detail: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJsonLine(): String {
        val json = JSONObject().apply {
            put("type", "ERR")
            put("code", code)
            put("reqId", reqId)
            put("detail", detail)
            put("ts", timestamp)
        }
        return "$json\n"
    }
}

/**
 * Resultado tipado del analizador seguro de tramas JSON delimitadas por `\n`.
 */
sealed class ParsedIncomingFrame {
    data class Telemetry(val frame: TelemetryFrame) : ParsedIncomingFrame()
    data class Command(val frame: CommandFrame) : ParsedIncomingFrame()
    data class Ack(val frame: AckFrame) : ParsedIncomingFrame()
    data class Err(val frame: ErrFrame) : ParsedIncomingFrame()
    data class Corrupt(val rawLine: String, val reason: String) : ParsedIncomingFrame()
}

/**
 * Parser robusto y defensivo que descarta de forma segura tramas JSON incompletas,
 * corruptas o con campos fuera de rango sin lanzar excepciones hacia la UI.
 */
object ProtocolFrameParser {
    private const val MAX_FRAME_LENGTH = 1024

    fun parseLine(rawLine: String): ParsedIncomingFrame {
        val clean = rawLine.trim()
        if (clean.isEmpty()) {
            return ParsedIncomingFrame.Corrupt(rawLine, "Trama vacía recibida.")
        }
        if (clean.length > MAX_FRAME_LENGTH) {
            return ParsedIncomingFrame.Corrupt(
                clean.take(80) + "...",
                "Trama excede el límite seguro de $MAX_FRAME_LENGTH bytes."
            )
        }
        if (!clean.startsWith("{") || !clean.endsWith("}")) {
            return ParsedIncomingFrame.Corrupt(
                clean.take(120),
                "Trama JSON incompleta o truncada (faltan delimitadores '{' / '}')."
            )
        }

        return try {
            val obj = JSONObject(clean)
            val explicitType = obj.optString("type", "").uppercase(Locale.US)
            val now = System.currentTimeMillis()

            when {
                explicitType == "ACK" -> {
                    ParsedIncomingFrame.Ack(
                        AckFrame(
                            cmd = obj.optString("cmd", "UNKNOWN"),
                            reqId = obj.optString("reqId", "-"),
                            detail = obj.optString("detail", "Comando ejecutado"),
                            timestamp = now
                        )
                    )
                }

                explicitType == "ERR" -> {
                    ParsedIncomingFrame.Err(
                        ErrFrame(
                            code = obj.optString("code", "ERR_GENERAL"),
                            reqId = obj.optString("reqId", "-"),
                            detail = obj.optString("detail", "Error reportado por nodo OT"),
                            timestamp = now
                        )
                    )
                }

                explicitType == "CMD" || obj.has("cmd") -> {
                    val cmd = obj.optString("cmd", "").trim()
                    if (cmd.isEmpty()) {
                        return ParsedIncomingFrame.Corrupt(clean, "Campo 'cmd' faltante en trama de comando.")
                    }
                    ParsedIncomingFrame.Command(
                        CommandFrame(
                            reqId = obj.optString("reqId", UUID.randomUUID().toString().take(6).uppercase(Locale.US)),
                            cmd = cmd,
                            value = obj.optInt("val", -1),
                            modo = obj.optString("modo", "AUTO"),
                            token = obj.optString("token", ""),
                            timestamp = now
                        )
                    )
                }

                explicitType == "TELEMETRY" || obj.has("dist") -> {
                    if (!obj.has("dist")) {
                        return ParsedIncomingFrame.Corrupt(clean, "Trama de telemetría sin campo obligatorio 'dist'.")
                    }
                    val dist = obj.getInt("dist")
                    if (dist !in TelemetryFrame.MIN_DISTANCE_CM..TelemetryFrame.MAX_DISTANCE_CM) {
                        return ParsedIncomingFrame.Corrupt(
                            clean,
                            "Distancia fuera de rango físico HC-SR04 ($dist cm)."
                        )
                    }
                    val barrera = obj.optInt("barrera", if (dist in 1..9) 0 else 90)
                    if (barrera !in TelemetryFrame.MIN_BARRIER_ANGLE..TelemetryFrame.MAX_BARRIER_ANGLE) {
                        return ParsedIncomingFrame.Corrupt(
                            clean,
                            "Ángulo de barrera fuera de rango ($barrera°)."
                        )
                    }
                    val estado = obj.optString("estado", if (dist in 1..9) "OCUPADO" else "LIBRE").uppercase(Locale.US)
                    if (estado != "LIBRE" && estado != "OCUPADO") {
                        return ParsedIncomingFrame.Corrupt(clean, "Estado de plaza inválido: '$estado'.")
                    }
                    val modo = obj.optString("modo", "AUTO").uppercase(Locale.US)
                    if (modo != "AUTO" && modo != "MANUAL") {
                        return ParsedIncomingFrame.Corrupt(clean, "Modo de operación inválido: '$modo'.")
                    }
                    val capTotal = obj.optInt("capacidadTotal", 12)
                        .coerceIn(TelemetryFrame.MIN_CAPACITY, TelemetryFrame.MAX_CAPACITY)
                    val defaultFree = if (estado == "OCUPADO") (capTotal - 1).coerceAtLeast(0) else capTotal
                    val plazasLibres = obj.optInt("plazasLibres", defaultFree).coerceIn(0, capTotal)
                    val crc = obj.optString("crc", "OK")

                    ParsedIncomingFrame.Telemetry(
                        TelemetryFrame(
                            dist = dist,
                            estado = estado,
                            barrera = barrera,
                            modo = modo,
                            plazasLibres = plazasLibres,
                            capacidadTotal = capTotal,
                            crc = crc,
                            timestamp = now
                        )
                    )
                }

                else -> ParsedIncomingFrame.Corrupt(
                    clean,
                    "Tipo de trama JSON no soportado por el protocolo."
                )
            }
        } catch (e: Exception) {
            ParsedIncomingFrame.Corrupt(
                clean.take(120),
                "JSON malformado descartado de forma segura: ${e.message ?: "error de sintaxis"}"
            )
        }
    }

    /**
     * Enmascara tokens sensibles antes de imprimir o almacenar una trama en la bitácora visible.
     */
    fun redactSensitiveFields(rawJson: String): String {
        return rawJson.replace(
            Regex("\"token\"\\s*:\\s*\"([^\"]{0,4})([^\"]*)\"")
        ) { match ->
            val prefix = match.groupValues[1]
            "\"token\":\"${prefix}****\""
        }
    }
}

/**
 * Formateador centralizado de marca de hora local legible para cada telemetría, ACK y ERR.
 */
object LocalTimeFormatter {
    fun formatFull(epochMillis: Long = System.currentTimeMillis()): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(epochMillis))
    }

    fun formatClock(epochMillis: Long = System.currentTimeMillis()): String {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(epochMillis))
    }
}

/**
 * Entrada en la bitácora de auditoría en tiempo real (ISO/IEC 27400) con marca de hora local legible.
 */
data class ProtocolAuditLog(
    val id: Long = System.nanoTime(),
    val epochMillis: Long = System.currentTimeMillis(),
    val timestamp: String = LocalTimeFormatter.formatFull(epochMillis),
    val category: String, // "TEL", "ACK", "ERR", "CMD", "SEC", "SYNC", "SYS"
    val rawFrame: String,
    val isError: Boolean = false,
    val isAck: Boolean = false
)
