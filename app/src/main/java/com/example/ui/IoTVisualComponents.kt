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
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Icon
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
import com.example.ui.theme.InacapCharcoal
import com.example.ui.theme.InacapRed
import com.example.ui.theme.IoTFreeGreen
import com.example.ui.theme.IoTManualAmber
import com.example.ui.theme.IoTOccupiedRed
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LcdBacklightGreen
import com.example.ui.theme.LcdBezelDark
import com.example.ui.theme.LcdCharBright
import com.example.ui.theme.LcdMatrixBg

/**
 * Componente visual interactivo que dibuja en Canvas la Barrera Inteligente (Servomotor Pin 15:
 * 90° Arriba/Abierta vs 0° Abajo/Cerrada) y el haz del Sensor Ultrasónico HC-SR04 (Trig=5, Echo=18).
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
                    .height(145.dp)
            ) {
                val w = size.width
                val h = size.height
                val groundY = h - 22.dp.toPx()

                // Línea de suelo / calzada de estacionamiento
                drawLine(
                    color = Color(0xFF424240),
                    start = Offset(12.dp.toPx(), groundY),
                    end = Offset(w - 12.dp.toPx(), groundY),
                    strokeWidth = 3.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 10f), 0f)
                )

                // Pedestal de la barrera (izquierda)
                val pillarX = 44.dp.toPx()
                val pillarWidth = 30.dp.toPx()
                val pillarHeight = 68.dp.toPx()
                val pillarTopY = groundY - pillarHeight

                // Base de concreto
                drawRoundRect(
                    color = Color(0xFF52524E),
                    topLeft = Offset(pillarX - 6.dp.toPx(), groundY - 10.dp.toPx()),
                    size = Size(pillarWidth + 12.dp.toPx(), 10.dp.toPx()),
                    cornerRadius = CornerRadius(4f, 4f)
                )

                // Torre Roja INACAP
                drawRoundRect(
                    color = InacapRed,
                    topLeft = Offset(pillarX, pillarTopY),
                    size = Size(pillarWidth, pillarHeight - 8.dp.toPx()),
                    cornerRadius = CornerRadius(12f, 12f)
                )

                // Pivote del Servomotor (Pin 15)
                val pivotCenter = Offset(pillarX + pillarWidth / 2f, pillarTopY + 18.dp.toPx())
                val armLength = (w * 0.50f).coerceAtMost(195.dp.toPx())
                val armThickness = 11.dp.toPx()

                // Rotación: 0° = horizontal (cerrada), -90° = vertical hacia arriba (abierta)
                rotate(degrees = -animatedAngle, pivot = pivotCenter) {
                    // Brazo blanco reflectante
                    drawRoundRect(
                        color = Color.White,
                        topLeft = Offset(pivotCenter.x, pivotCenter.y - armThickness / 2f),
                        size = Size(armLength, armThickness),
                        cornerRadius = CornerRadius(8f, 8f)
                    )
                    // Franjas rojas de seguridad vial
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

                // Eje central metálico del servomotor
                drawCircle(
                    color = InacapCharcoal,
                    radius = 7.dp.toPx(),
                    center = pivotCenter
                )
                drawCircle(
                    color = Color.White,
                    radius = 3.dp.toPx(),
                    center = pivotCenter
                )

                // Sensor Ultrasónico HC-SR04 (derecha) y ondas acústicas
                val sensorX = w - 28.dp.toPx()
                val sensorY = groundY - 36.dp.toPx()
                drawRoundRect(
                    color = Color(0xFF0277BD),
                    topLeft = Offset(sensorX - 10.dp.toPx(), sensorY - 16.dp.toPx()),
                    size = Size(16.dp.toPx(), 32.dp.toPx()),
                    cornerRadius = CornerRadius(4f, 4f)
                )

                // Ondas ultrasónicas hacia el vehículo
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

                // Representación de vehículo / objeto según distancia (0..100 cm)
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
                    // Ruedas del vehículo
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
 * Emulación fiel del módulo físico Pantalla LCD I2C (`0x27`, 16 columnas x 2 filas, `SDA=21`, `SCL=22`).
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
                    text = "LCD I2C 0x27 (16x2) • SDA:21 SCL:22",
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

/**
 * Indicador semántico de conectividad IoT (Verde = Conectado, Ámbar = Reconectando/Escuchando, Rojo = Desconectado).
 */
@Composable
fun ConnectionStatusBanner(
    connectionState: ConnectionState,
    modifier: Modifier = Modifier
) {
    val (bgColor, accentColor, icon, text) = when (connectionState) {
        is ConnectionState.Connected -> Quadruple(
            Color(0xFFE8F5E9),
            IoTFreeGreen,
            Icons.Default.BluetoothConnected,
            "CONECTADO • ${connectionState.endpointName}"
        )
        is ConnectionState.Reconnecting -> Quadruple(
            Color(0xFFFFF8E1),
            IoTManualAmber,
            Icons.Default.Sync,
            "${connectionState.reason} (Intento ${connectionState.attempt}/${connectionState.maxAttempts} • Backoff ${connectionState.nextRetryMs}ms)"
        )
        is ConnectionState.ListeningServer -> Quadruple(
            Color(0xFFFFF8E1),
            IoTManualAmber,
            Icons.Default.WifiTethering,
            "SERVIDOR OT ACTIVO • Escuchando en ${connectionState.endpointInfo}"
        )
        is ConnectionState.Connecting -> Quadruple(
            Color(0xFFFFF8E1),
            IoTManualAmber,
            Icons.Default.Sync,
            "Conectando socket en Dispatchers.IO hacia ${connectionState.targetName}..."
        )
        is ConnectionState.Disconnected -> Quadruple(
            Color(0xFFFFEBEE),
            IoTOccupiedRed,
            Icons.Default.ErrorOutline,
            "DESCONECTADO • Modo Offline Cifrado listo para encolar eventos"
        )
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
                .border(1.dp, accentColor.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = "Estado de conexión",
                tint = accentColor,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF1D1D1B),
                fontWeight = FontWeight.Bold
            )
        }
    }
}

private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)
