# Sistema de Control y Monitoreo de Barrera de Estacionamiento Inteligente (IoT/OT) — INACAP

Aplicación nativa Android en **Kotlin + Jetpack Compose (Material Design 3)** con arquitectura **MVVM** y firmware C++ para **ESP32**, diseñada bajo los lineamientos de ciberseguridad **ISO/IEC 27000 e ISO/IEC 27400 (Seguridad y Privacidad en IoT)**.

---

## 1. Problemática Resuelta

Los sistemas tradicionales de control de acceso vehicular y barreras de estacionamiento suelen operar en lazos cerrados aislados o mediante enlaces inalámbricos sin autenticación ni tolerancia a fallos, exponiendo la infraestructura OT (Operational Technology) a accesos no autorizados, pérdida de trazabilidad durante cortes de red y bloqueos en los controladores por rutinas sincrónicas (`delay()`).

Este proyecto resuelve dicha problemática implementando:
1. **Control y Telemetría en Tiempo Real No Bloqueante:** Monitoreo continuo de distancia ultrasónica (`HC-SR04`), accionamiento de servomotor (`ESP32Servo` 0°/90°) y visualización local/remota en pantalla `LCD I2C 0x27 (16x2)` empleando `millis()` en el ESP32 y `Dispatchers.IO` en Android.
2. **Seguridad Criptográfica ISO/IEC 27400 (Cero Texto Plano):** Almacenamiento exclusivo de credenciales mediante hashes `SHA-256` con Salt aleatorio de 16 bytes (`SecureRandom`) y tokens de sesión resguardados en `EncryptedSharedPreferences` con llave maestra `AES256_GCM`.
3. **Arquitectura Dual de Roles (2 Dispositivos Físicos o Celular ↔ ESP32):** Permite operar un teléfono como **Estación de Control / Monitoreo (Cliente)** y un segundo teléfono Android (o el **ESP32**) como **Nodo Sensor / Simulador OT (Servidor)** a través de **Bluetooth RFCOMM** o **Sockets TCP por Wi-Fi Local**.
4. **Resiliencia y Sincronización Offline:** Captura limpia de excepciones `IOException` con cierre garantizado en bloques `finally`, reconexión automática con *Exponential Backoff* y cola temporal cifrada que sincroniza automáticamente comandos/eventos al restablecer el enlace.

---

## 2. Justificación de Arquitectura (MVVM + Clean Concurrency)

* **Capa de Presentación (`MainActivity.kt`, `LoginScreen.kt`, `DashboardScreen.kt`, `IoTVisualComponents.kt`):** Construida 100% en **Jetpack Compose** con Material Design 3 y la identidad institucional **INACAP** (`#E30613`, `#1D1D1B`, `#F5F5F5`, `#FFFFFF`). Consume un único `StateFlow<MainUiState>` inmutable usando `collectAsStateWithLifecycle()`.
* **Capa de Gestión de Estado (`MainViewModel.kt`):** Extiende `AndroidViewModel`, manteniendo vivos la sesión autenticada, el socket inalámbrico, los indicadores del sensor y la cola de sincronización durante rotaciones de pantalla (`Portrait <-> Landscape`).
* **Capa de Red y Sockets (`WirelessConnectionManager.kt`):** Ejecuta todas las lecturas/escrituras de `BluetoothSocket`, `BluetoothServerSocket`, `Socket` y `ServerSocket` estrictamente en `Dispatchers.IO`. Implementa reconexión automática con *Exponential Backoff* y cierre seguro de recursos en bloques `finally`.
* **Capa de Seguridad (`SecurityManager.kt`):** Gestiona `EncryptedSharedPreferences` (`MasterKey.KeyScheme.AES256_GCM`), generación de Salt criptográfico, hashing `SHA-256`, firma de tokens de sesión (`INA-...`) y persistencia cifrada de la cola Offline.

---

## 3. Especificación del Protocolo Inalámbrico Seguro (JSON + `\n`)

La comunicación entre la **Estación de Control** y el **Nodo Sensor / ESP32** utiliza tramas JSON ligeras delimitadas por salto de línea (`\n`) sobre RFCOMM (UUID SPP `00001101-0000-1000-8000-00805F9B34FB`) o TCP (Puerto `8888`):

### A. Trama de Telemetría (Nodo Sensor / ESP32 $\rightarrow$ Estación de Control)
```json
{"dist":8,"estado":"OCUPADO","barrera":0,"modo":"AUTO","crc":"OK-4B1E"}\n
```
* `dist` (`Int`): Distancia medida por el sensor HC-SR04 en centímetros (`0` a `400`).
* `estado` (`String`): `"OCUPADO"` si `0 < dist < 10 cm`; `"LIBRE"` si `dist >= 10 cm`.
* `barrera` (`Int`): Ángulo actual del servomotor en el Pin 15 (`90` = Barrera Abierta, `0` = Barrera Cerrada).
* `modo` (`String`): `"AUTO"` o `"MANUAL"`.
* `crc` (`String`): Suma de verificación de integridad de trama.

### B. Trama de Comando Autenticado (Estación de Control $\rightarrow$ Nodo Sensor / ESP32)
```json
{"cmd":"SET_BARRERA","val":90,"modo":"MANUAL","token":"INA-8F3A9C12D04E"}\n
```
* `cmd` (`String`): `"SET_BARRERA"` (forzar apertura/cierre), `"SET_MODO"` (volver a modo automático) o `"REQ_STATUS"` (solicitar telemetría inmediata).
* `val` (`Int`): `90` (Abrir barrera), `0` (Cerrar barrera) o `-1` (No aplica).
* `modo` (`String`): `"MANUAL"` o `"AUTO"`.
* `token` (`String`): Token de sesión efímero firmado con SHA-256 requerido por el receptor para autorizar la operación bajo ISO/IEC 27400.

---

## 4. Mapeo de Pines del Hardware ESP32

| Componente Físico | Pin ESP32 | Función en el Sistema |
| :--- | :--- | :--- |
| **HC-SR04 `TRIG`** | `GPIO 5` | Disparo de pulso ultrasónico de 10 µs |
| **HC-SR04 `ECHO`** | `GPIO 18` | Medición de ancho de pulso reflejado |
| **Servomotor SG90** | `GPIO 15` | PWM 50 Hz (`90°` = Barrera Arriba, `0°` = Barrera Abajo) |
| **LCD I2C `0x27` `SDA`** | `GPIO 21` | Datos I2C (Pantalla 16x2 caracteres) |
| **LCD I2C `0x27` `SCL`** | `GPIO 22` | Reloj I2C (Pantalla 16x2 caracteres) |

---

## 5. Instrucciones de Compilación y Ejecución

### App Android (Kotlin / Jetpack Compose)
1. Clonar el repositorio y abrir el proyecto en **Android Studio Ladybug / Meerkat** o superior (JDK 11+, `compileSdk = 36`, `minSdk = 24`).
2. Compilar e instalar el APK Debug mediante línea de comandos:
   ```bash
   gradle assembleDebug
   ```
3. Iniciar sesión con la credencial institucional precargada (almacenada únicamente como Hash SHA-256 + Salt):
   * **Operador:** `admin_inacap`
   * **Clave:** `Inacap2026!`
4. **Modos de Prueba Soportados:**
   * **1 Dispositivo (Loopback TCP Local):** Conecta automáticamente a `127.0.0.1:8888` para probar comandos, LCD virtual, rotación de pantalla y desconexión forzada (`IOException` + Backoff).
   * **2 Dispositivos Android Físicos:** En el Teléfono A elige el rol **"2. Nodo Sensor OT"** y pulsa **"INICIAR SERVIDOR OT"** (por Bluetooth RFCOMM o Wi-Fi TCP). En el Teléfono B elige el rol **"1. Estación Control"** y pulsa **"CONECTAR SOCKET"**.
   * **Teléfono Android ↔ ESP32 Físico:** Empareja el Bluetooth `"ESP32_Barrera_INACAP"`, selecciona `Bluetooth RFCOMM` en la app y pulsa **"CONECTAR SOCKET"**.

### Firmware ESP32 (`esp32/sketch_estacionamiento_iot.ino`)
1. Abrir `esp32/sketch_estacionamiento_iot.ino` en **Arduino IDE 2.x** o **PlatformIO**.
2. Instalar las librerías `ESP32Servo` (de Kevin Harrington) y `LiquidCrystal I2C` (de Frank de Brabander).
3. Seleccionar la placa **DOIT ESP32 DEVKIT V1** (o módulo ESP32 equivalente) y cargar el firmware a `115200 baud`.
