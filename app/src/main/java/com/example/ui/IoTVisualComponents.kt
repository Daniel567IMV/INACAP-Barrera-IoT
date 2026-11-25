package com.example.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ConnectionState
import com.example.data.UiConnectionHealth
import com.example.ui.theme.InacapCharcoal
import com.example.ui.theme.InacapRed
import com.example.ui.theme.IoTFreeGreen
import com.example.ui.theme.IoTFreeGreenBg
import com.example.ui.theme.IoTManualAmber
import com.example.ui.theme.IoTManualAmberBg
import com.example.ui.theme.IoTOccupiedRed
import com.example.ui.theme.IoTOccupiedRedBg
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LcdBacklightGreen
import com.example.ui.theme.LcdBezelDark
import com.example.ui.theme.LcdCharBright
import com.example.ui.theme.LcdMatrixBg

/**
 * Letrero Inteligente de Entrada del Estacionamiento:
 * Muestra plazas libres y capacidad total desde la entrada + marca de hora local de telemetría.
 */
@Composable
fun ParkingEntranceHeaderCard(
    freeSlots: Int,
    totalCapacity: Int,
    parkingStatus: String,
    lastTelemetryTimestamp: String,
    modifier: Modifier = Modifier
) {
    val safeCapacity = totalCapacity.coerceAtLeast(1)
    val safeFree = freeSlots.coerceIn(0, safeCapacity)
    val occupiedCount = safeCapacity - safeFree
    val availabilityRatio = safeFree.toFloat() / safeCapacity.toFloat()
    val isFull = safeFree == 0
    val accentColor = when {
        isFull -> IoTOccupiedRed
        availabilityRatio <= 0.25f -> IoTManualAmber
        else -> IoTFreeGreen
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("parking_entrance_card"),
        shape = RoundedCornerShape(18.dp),
        color = InacapCharcoal,
        tonalElevation = 5.dp
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.linearGradient(
                        colors = listOf(InacapCharcoal, Color(0xFF282020))
                    )
                )
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = accentColor,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.LocalParking,
                                contentDescription = "Estacionamiento Inteligente",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "PANEL DE ENTRADA • ESTACIONAMIENTO",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFBDBDBD),
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isFull) "COMPLETO — SIN PLAZAS" else "PLAZAS DISPONIBLES",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Surface(
                    color = accentColor.copy(alpha = 0.22f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .border(1.dp, accentColor, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Text(
                            text = "$safeFree",
                            style = MaterialTheme.typography.headlineLarge,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.testTag("free_slots_value")
                        )
                        Text(
                            text = " / $safeCapacity",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color(0xFFE0E0E0),
                            modifier = Modifier.padding(bottom = 3.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            LinearProgressIndicator(
                progress = { availabilityRatio },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = accentColor,
                trackColor = Color(0xFF424240)
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Ocupadas: $occupiedCount • Acceso actual: $parkingStatus",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFD6D6D6)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = "Hora de última telemetría",
                        tint = Color(0xFFBDBDBD),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = lastTelemetryTimestamp,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFE0E0E0),
                        modifier = Modifier.testTag("last_telemetry_timestamp")
                    )
                }
            }
        }
    }
}

/**
 * Indicador explícito de los 4 estados de salud de conexión requeridos en ES3:
 * `CONECTADO`, `RECONECTANDO`, `DESCONECTADO` y `SIN DATOS RECIENTES`.
 */
@Composable
fun ConnectionStatusBanner(
    connectionState: ConnectionState,
    connectionHealth: UiConnectionHealth,
    secondsSinceLastTelemetry: Long,
    modifier: Modifier = Modifier
) {
    val detailText = when (connectionHealth) {
        UiConnectionHealth.CONNECTED -> {
            val endpoint = (connectionState as? ConnectionState.Connected)?.endpointName ?: "Nodo OT"
            "CONECTADO • $endpoint (telemetría activa hace ${secondsSinceLastTelemetry}s)"
        }
        UiConnectionHealth.RECONNECTING -> {
            when (connectionState) {
                is ConnectionState.Reconnecting ->
                    "RECONECTANDO • Intento #${connectionState.attempt} (próximo reintento en ${connectionState.nextRetryMs / 1000}s • tope 30s)"
                is ConnectionState.Connecting ->
                    "RECONECTANDO • Abriendo socket en Dispatchers.IO hacia ${connectionState.targetName}..."
                else -> "RECONECTANDO • Restableciendo enlace en segundo plano..."
            }
        }
        UiConnectionHealth.NO_RECENT_DATA -> {
            val suffix = if (secondsSinceLastTelemetry > 0L) {
                "Hace ${secondsSinceLastTelemetry}s sin telemetría válida"
            } else {
                "Esperando primera trama JSON del microcontrolador / nodo"
            }
            "SIN DATOS RECIENTES • $suffix"
        }
        UiConnectionHealth.DISCONNECTED -> {
            "DESCONECTADO • Registros guardándose localmente en Room SQLite (synced=false)"
        }
    }

    val (bgColor, accentColor, icon) = when (connectionHealth) {
        UiConnectionHealth.CONNECTED -> Triple(IoTFreeGreenBg, IoTFreeGreen, Icons.Default.BluetoothConnected)
        UiConnectionHealth.RECONNECTING -> Triple(IoTManualAmberBg, IoTManualAmber, Icons.Default.Sync)
        UiConnectionHealth.NO_RECENT_DATA -> Triple(Color(0xFFFFF3E0), Color(0xFFE65100), Icons.Default.HourglassEmpty)
        UiConnectionHealth.DISCONNECTED -> Triple(IoTOccupiedRedBg, IoTOccupiedRed, Icons.Default.ErrorOutline)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("connection_status_banner"),
        color = bgColor,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.5.dp, accentColor.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = connectionHealth.displayLabel,
                tint = accentColor,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = connectionHealth.displayLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = accentColor,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("connection_health_label")
                )
                Text(
                    text = detailText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = InacapCharcoal
                )
            }
        }
    }
}

/**
 * Tira dedicada que muestra la última respuesta `ACK` y el último `ERR` con su marca de hora local.
 */
@Composable
fun AckErrStatusStrip(
    lastAckSummary: String?,
    lastAckTimestamp: String?,
    lastErrSummary: String?,
    lastErrTimestamp: String?,
    modifier: Modifier = Modifier
) {
    if (lastAckSummary == null && lastErrSummary == null) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (lastAckSummary != null) {
            Surface(
                color = IoTFreeGreenBg,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("last_ack_card")
            ) {
                Row(
                    modifier = Modifier
                        .border(1.dp, IoTFreeGreen.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Último ACK",
                        tint = IoTFreeGreen,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = lastAckSummary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = InacapCharcoal,
                            fontWeight = FontWeight.Bold
                        )
                        if (lastAckTimestamp != null) {
                            Text(
                                text = "Hora local: $lastAckTimestamp",
                                style = MaterialTheme.typography.labelSmall,
                                color = IoTFreeGreen
                            )
                        }
                    }
                }
            }
        }

        if (lastErrSummary != null) {
            Surface(
                color = IoTOccupiedRedBg,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("last_err_card")
            ) {
                Row(
                    modifier = Modifier
                        .border(1.dp, IoTOccupiedRed.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.ReportProblem,
                        contentDescription = "Último ERR",
                        tint = IoTOccupiedRed,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = lastErrSummary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = InacapCharcoal,
                            fontWeight = FontWeight.Bold
                        )
                        if (lastErrTimestamp != null) {
                            Text(
                                text = "Hora local: $lastErrTimestamp",
                                style = MaterialTheme.typography.labelSmall,
                                color = IoTOccupiedRed
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Gemelo digital animado en Canvas de la Barrera Inteligente (Servomotor Pin 15: 0° / 90°)
 * y el Sensor Ultrasónico HC-SR04 (Trig=5, Echo=18).
 */
@Composable
fun AnimatedSmartBarrierCanvas(
    distanceCm: Int,
    barrierAngle: Int,
    parkingStatus: String,
    operationMode: String,
    modifier: Modifier = Modifier
) {
    val animatedAngle by animateFloatAsState(
        targetValue = barrierAngle.toFloat().coerceIn(0f, 90f),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "servo_angle_anim"
    )

    val isOccupied = parkingStatus.equals("OCUPADO", ignoreCase = true)
    val statusColor = if (isOccupied) IoTOccupiedRed else IoTFreeGreen
    val modeColor = if (operationMode.equals("MANUAL", ignoreCase = true)) IoTManualAmber else IoTFreeGreen

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("smart_barrier_visualizer"),
        shape = RoundedCornerShape(16.dp),
        color = InacapCharcoal,
        tonalElevation = 4.dp
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "GEMELO DIGITAL OT • ESP32 + SERVO PIN 15",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFE0E0E0),
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "MODO $operationMode",
                    style = MaterialTheme.typography.labelSmall,
                    color = modeColor,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
            ) {
                val w = size.width
                val h = size.height
                val groundY = h - 22.dp.toPx()

                drawLine(
                    color = Color(0xFF424240),
                    start = Offset(12.dp.toPx(), groundY),
                    end = Offset(w - 12.dp.toPx(), groundY),
                    strokeWidth = 3.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 10f), 0f)
                )

                val pillarX = 44.dp.toPx()
                val pillarWidth = 30.dp.toPx()
                val pillarHeight = 68.dp.toPx()
                val pillarTopY = groundY - pillarHeight

                drawRoundRect(
                    color = Color(0xFF52524E),
                    topLeft = Offset(pillarX - 6.dp.toPx(), groundY - 10.dp.toPx()),
                    size = Size(pillarWidth + 12.dp.toPx(), 10.dp.toPx()),
                    cornerRadius = CornerRadius(4f, 4f)
                )

                drawRoundRect(
                    color = InacapRed,
                    topLeft = Offset(pillarX, pillarTopY),
                    size = Size(pillarWidth, pillarHeight - 8.dp.toPx()),
                    cornerRadius = CornerRadius(12f, 12f)
                )

                val pivotCenter = Offset(pillarX + pillarWidth / 2f, pillarTopY + 18.dp.toPx())
                val armLength = (w * 0.50f).coerceAtMost(195.dp.toPx())
                val armThickness = 11.dp.toPx()

                rotate(degrees = -animatedAngle, pivot = pivotCenter) {
                    drawRoundRect(
                        color = Color.White,
                        topLeft = Offset(pivotCenter.x, pivotCenter.y - armThickness / 2f),
                        size = Size(armLength, armThickness),
                        cornerRadius = CornerRadius(8f, 8f)
                    )
                    val stripeWidth = armLength / 6f
                    for (i in 0..2) {
                        val startX = pivotCenter.x + (i * 2 + 1) * stripeWidth
                        drawRect(
                            color = InacapRed,
                            topLeft = Offset(startX, pivotCenter.y - armThickness / 2f),
                            size = Size(stripeWidth, armThickness)
                        )
                    }
                }

                drawCircle(color = InacapCharcoal, radius = 7.dp.toPx(), center = pivotCenter)
                drawCircle(color = Color.White, radius = 3.dp.toPx(), center = pivotCenter)

                val sensorX = w - 28.dp.toPx()
                val sensorY = groundY - 36.dp.toPx()
                drawRoundRect(
                    color = Color(0xFF0277BD),
                    topLeft = Offset(sensorX - 10.dp.toPx(), sensorY - 16.dp.toPx()),
                    size = Size(16.dp.toPx(), 32.dp.toPx()),
                    cornerRadius = CornerRadius(4f, 4f)
                )

                val waveColor = if (distanceCm in 1..9) IoTOccupiedRed else IoTFreeGreen
                for (waveIdx in 1..3) {
                    val arcRadius = (waveIdx * 16).dp.toPx()
                    drawArc(
                        color = waveColor.copy(alpha = 0.85f - waveIdx * 0.22f),
                        startAngle = 135f,
                        sweepAngle = 90f,
                        useCenter = false,
                        topLeft = Offset(sensorX - arcRadius - 8.dp.toPx(), sensorY - arcRadius),
                        size = Size(arcRadius * 2, arcRadius * 2),
                        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                    )
                }

                val normalizedDist = (distanceCm.coerceIn(0, 100) / 100f)
                val maxTravel = (sensorX - pillarX - pillarWidth - 70.dp.toPx()).coerceAtLeast(30f)
                val carRightX = sensorX - 24.dp.toPx() - (normalizedDist * maxTravel)
                val carWidth = 48.dp.toPx()
                val carHeight = 24.dp.toPx()

                if (distanceCm > 0) {
                    drawRoundRect(
                        color = if (distanceCm < 10) IoTOccupiedRed else Color(0xFF90CAF9),
                        topLeft = Offset(carRightX - carWidth, groundY - carHeight - 4.dp.toPx()),
                        size = Size(carWidth, carHeight),
                        cornerRadius = CornerRadius(10f, 10f)
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 4.5.dp.toPx(),
                        center = Offset(carRightX - carWidth + 11.dp.toPx(), groundY - 4.dp.toPx())
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 4.5.dp.toPx(),
                        center = Offset(carRightX - 11.dp.toPx(), groundY - 4.dp.toPx())
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "HC-SR04 (Trig:5 / Echo:18): $distanceCm cm",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFBDBDBD)
                )
                Text(
                    text = if (distanceCm in 1..9) "Umbral <10cm: OCUPADO (0°)" else "Umbral ≥10cm: LIBRE (90°)",
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * Emulación fiel del módulo físico Display LCD I2C (`0x27`, 16x2, `SDA=21`, `SCL=22`).
 */
@Composable
fun VirtualI2cLcdDisplay(
    line1: String,
    line2: String,
    crcStatus: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("virtual_lcd_display"),
        shape = RoundedCornerShape(14.dp),
        color = LcdBezelDark,
        tonalElevation = 4.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "LCD I2C 0x27 (16x2) • PLAZAS + DISTANCIA",
                    style = MaterialTheme.typography.labelSmall,
                    color = LcdBacklightGreen,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "CRC:$crcStatus",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF9E9E9E)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(LcdMatrixBg, Color(0xFF162211))
                        )
                    )
                    .border(2.dp, LcdBacklightGreen.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Column {
                    Text(
                        text = line1.padEnd(16, ' ').take(16),
                        fontFamily = JetBrainsMonoFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        letterSpacing = 1.8.sp,
                        color = LcdCharBright,
                        modifier = Modifier.testTag("lcd_line_1")
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = line2.padEnd(16, ' ').take(16),
                        fontFamily = JetBrainsMonoFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        letterSpacing = 1.8.sp,
                        color = LcdCharBright,
                        modifier = Modifier.testTag("lcd_line_2")
                    )
                }
            }
        }
    }
}
