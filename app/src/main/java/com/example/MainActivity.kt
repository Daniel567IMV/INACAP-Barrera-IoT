package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.DashboardScreen
import com.example.ui.LoginScreen
import com.example.ui.theme.InacapIoTTheme
import com.example.viewmodel.MainViewModel

/**
 * Actividad Principal del Sistema de Control y Monitoreo de Barrera Inteligente (INACAP IoT/OT).
 *
 * Implementa:
 * - Gestión dinámica de permisos en Runtime según nivel de API (Android 12+ vs Android 11 o inferior)
 *   bajo ISO/IEC 27400 sin crasheos ante denegación.
 * - Retención de estado y socket activo en `MainViewModel` ante rotación de pantalla.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            InacapIoTTheme {
                SmartBarrierApp()
            }
        }
    }
}

@Composable
fun SmartBarrierApp(
    mainViewModel: MainViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by mainViewModel.uiState.collectAsStateWithLifecycle()

    // Permisos requeridos según versión de Android (Rúbrica B: API 31+ vs API <= 30)
    val requiredWirelessPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    } else {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }

    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { resultMap ->
        val denied = resultMap.filterValues { granted -> !granted }.keys.toList()
        mainViewModel.onPermissionsResult(
            allGranted = denied.isEmpty(),
            deniedList = denied
        )
    }

    // Verificar estado actual de permisos al iniciar o rotar pantalla
    LaunchedEffect(Unit) {
        val missing = requiredWirelessPermissions.filter { perm ->
            ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            mainViewModel.onPermissionsResult(allGranted = true, deniedList = emptyList())
        } else {
            permissionsLauncher.launch(requiredWirelessPermissions)
        }
    }

    Crossfade(
        targetState = uiState.isAuthenticated,
        modifier = Modifier.fillMaxSize(),
        label = "auth_dashboard_transition"
    ) { authenticated ->
        if (!authenticated) {
            LoginScreen(
                uiState = uiState,
                onLoginSubmit = { user, pass, keep ->
                    mainViewModel.login(user, pass, keep)
                },
                onRegisterOrUpdate = { user, pass ->
                    mainViewModel.registerOrUpdateOperator(user, pass)
                }
            )
        } else {
            DashboardScreen(
                uiState = uiState,
                onRequestPermissions = {
                    permissionsLauncher.launch(requiredWirelessPermissions)
                },
                onOpenAppSettings = {
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null)
                    ).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                },
                onSelectRole = mainViewModel::selectRole,
                onSelectTransport = mainViewModel::selectTransport,
                onSelectBtAddress = mainViewModel::updateSelectedBtAddress,
                onUpdateTcpEndpoint = mainViewModel::updateTargetTcpEndpoint,
                onConnectOrStartSocket = mainViewModel::connectOrStartRoleSocket,
                onDisconnectSocket = mainViewModel::disconnectSocket,
                onSimulateAbruptDisconnect = mainViewModel::triggerSimulatedDisconnectFault,
                onRefreshDevices = mainViewModel::refreshPairedDevices,
                onOpenBarrier = mainViewModel::sendOpenBarrierCommand,
                onCloseBarrier = mainViewModel::sendCloseBarrierCommand,
                onSetAutoMode = mainViewModel::sendAutoModeCommand,
                onRequestStatus = mainViewModel::sendRequestStatusCommand,
                onSensorDistanceSliderChange = mainViewModel::onSimulatedSensorDistanceChanged,
                onFlushOfflineQueue = mainViewModel::flushOfflineSyncQueue,
                onClearAuditLogs = mainViewModel::clearAuditLogs,
                onLogout = mainViewModel::logout
            )
        }
    }
}
