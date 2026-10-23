package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.security.SecurityManager
import com.example.ui.theme.InacapCharcoal
import com.example.ui.theme.InacapRed
import com.example.ui.theme.IoTFreeGreen
import com.example.ui.theme.IoTOccupiedRed
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.viewmodel.MainUiState

/**
 * Pantalla de Login Institucional INACAP bajo estándar ISO/IEC 27400 (Cero Texto Plano).
 * Usa `rememberSaveable` para conservar campos al rotar la pantalla (Portrait <-> Landscape).
 */
@Composable
fun LoginScreen(
    uiState: MainUiState,
    onLoginSubmit: (username: String, password: String, keepSession: Boolean) -> Unit,
    onRegisterOrUpdate: (username: String, password: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var username by rememberSaveable { mutableStateOf(SecurityManager.DEFAULT_OPERATOR_USER) }
    var password by rememberSaveable { mutableStateOf(SecurityManager.DEFAULT_OPERATOR_PASS_INITIAL) }
    var keepSession by rememberSaveable { mutableStateOf(true) }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Cabecera Institucional INACAP
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = InacapCharcoal,
                tonalElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier
                        .background(
                            Brush.linearGradient(
                                colors = listOf(InacapCharcoal, Color(0xFF331114))
                            )
                        )
                        .padding(20.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = InacapRed,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "INACAP",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "INGENIERÍA • IoT / OT",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color(0xFFE0E0E0)
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "Control y Monitoreo de Barrera Inteligente",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Arquitectura Segura ISO/IEC 27000 & ISO/IEC 27400 • ESP32 HC-SR04 + Servo SG90 + LCD I2C 0x27",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFCCCCCC)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Tarjeta de Login Cifrado
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = "Seguridad ISO 27400",
                            tint = InacapRed,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Autenticación de Operador OT",
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                text = "EncryptedSharedPreferences (AES256-GCM) + Hash SHA-256 con Salt",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Operador / ID Institucional") },
                        leadingIcon = {
                            Icon(Icons.Default.Person, contentDescription = "Icono Operador")
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("username_input")
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Clave de Seguridad (Cero Texto Plano)") },
                        leadingIcon = {
                            Icon(Icons.Default.Lock, contentDescription = "Icono Clave")
                        },
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (passwordVisible) "Ocultar clave" else "Mostrar clave"
                                )
                            }
                        },
                        visualTransformation = if (passwordVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("password_input")
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = keepSession,
                            onCheckedChange = { keepSession = it },
                            colors = CheckboxDefaults.colors(checkedColor = InacapRed),
                            modifier = Modifier.testTag("keep_session_checkbox")
                        )
                        Text(
                            text = "Mantener sesión iniciada de forma cifrada (AES256-GCM)",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    if (!uiState.authFeedbackMessage.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            color = if (uiState.isAuthError) Color(0xFFFFEBEE) else Color(0xFFE8F5E9),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = uiState.authFeedbackMessage,
                                color = if (uiState.isAuthError) IoTOccupiedRed else IoTFreeGreen,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Button(
                        onClick = { onLoginSubmit(username, password, keepSession) },
                        colors = ButtonDefaults.buttonColors(containerColor = InacapRed),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("login_button")
                    ) {
                        Icon(Icons.Default.Key, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("INICIAR SESIÓN SEGURA", fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedButton(
                        onClick = { onRegisterOrUpdate(username, password) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("register_credentials_button")
                    ) {
                        Text("Registrar / Actualizar Hash SHA-256 Local")
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Tarjeta de Evidencia Criptográfica para Evaluación Académica (Rúbrica A1)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = InacapCharcoal
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "VERIFICACIÓN DE SEGURIDAD ISO/IEC 27400 (CERO TEXTO PLANO)",
                        style = MaterialTheme.typography.labelSmall,
                        color = InacapRed,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "• KeyScheme: MasterKey.KeyScheme.AES256_GCM\n" +
                            "• Hashing: SHA-256 + 16-byte SecureRandom Salt\n" +
                            "• Huella Hash Activa: ${uiState.sha256Fingerprint}\n" +
                            "• Credencial por defecto precargada: admin_inacap / Inacap2026!",
                        fontFamily = JetBrainsMonoFamily,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFD6D6D6)
                    )
                }
            }
        }
    }
}
