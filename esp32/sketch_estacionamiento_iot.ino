/**
 * ============================================================================
 * SISTEMA DE ESTACIONAMIENTO INTELIGENTE Y CONTROL DE BARRERA (ES3 — TI3042)
 * Arquitectura IoT/OT Segura bajo ISO/IEC 27000 / ISO/IEC 27400 — INACAP
 *
 * HARDWARE ESP32 (DOIT ESP32 DEVKIT V1 / DevKitC V4):
 *  - Sensor Ultrasónico HC-SR04 : trigPin = 5, echoPin = 18
 *  - Actuador Servomotor SG90   : servoPin = 15 (0° = Cerrada/Seguro, 90° = Abierta)
 *  - Pantalla LCD I2C (0x27)    : 16x2 (SDA = 21, SCL = 22) -> Plazas libres desde entrada
 *  - Conectividad Inalámbrica   : Bluetooth Clásico SPP + Wi-Fi TCP opcional
 *  - Almacenamiento Offline     : Flash interna NVS (`Preferences`, sin tarjeta SD)
 * ============================================================================
 */

#include <Wire.h>
#include <LiquidCrystal_I2C.h>
#include <ESP32Servo.h>
#include <Preferences.h>
#include <esp_task_wdt.h>
#include "BluetoothSerial.h"

#define WDT_TIMEOUT_SECONDS 8

// Carga credenciales fuera de Git (`esp32/secrets.h`, ver `esp32/secrets.example.h`)
#if __has_include("secrets.h")
  #include "secrets.h"
#elif __has_include("secrets_local.h")
  #include "secrets_local.h"
#endif

#ifndef BT_DEVICE_NAME
  #define BT_DEVICE_NAME "ESP32_Barrera_INACAP"
#endif

#ifndef ENABLE_WIFI_TCP
  #define ENABLE_WIFI_TCP 1
#endif

#ifndef WIFI_SSID
  #define WIFI_SSID "Daniel"
#endif

#ifndef WIFI_PASS
  #define WIFI_PASS "lukas123456"
#endif

#ifndef TCP_SERVER_PORT
  #define TCP_SERVER_PORT 8888
#endif

#if ENABLE_WIFI_TCP
  #include <WiFi.h>
  WiFiServer tcpServer(TCP_SERVER_PORT);
  WiFiClient tcpClient;
  unsigned long ultimoReintentoWifiMs = 0;
#endif

#if !defined(CONFIG_BT_ENABLED) || !defined(CONFIG_BLUEDROID_ENABLED)
#error "Bluetooth Clásico no está habilitado en la configuración de placa ESP32."
#endif

// Definición de Pines
const int trigPin  = 5;
const int echoPin  = 18;
const int servoPin = 15;

// Periféricos
LiquidCrystal_I2C lcd(0x27, 16, 2); // SDA = 21, SCL = 22
Servo barreraServo;
BluetoothSerial SerialBT;
Preferences prefs;

// ============================================================================
// BUFFER CIRCULAR EN FLASH INTERNA NVS (Preferences) — SIN SD (Criterio 3.1.1.4)
// Tamaño por lectura: 16 bytes empaquetados | Capacidad: 128 lecturas (2048 B)
// Protección de desgaste: escritura por lotes en RAM cada 4 muestras (~20 s)
// ============================================================================
struct __attribute__((packed)) BufferedReading {
  uint32_t seq;            // 4 B: secuencia monotónica
  uint32_t uptimeSec;      // 4 B: segundos desde encendido al registrar
  uint16_t distCm;         // 2 B: distancia HC-SR04 (cm)
  uint8_t  barreraAngle;   // 1 B: ángulo servo (0 o 90)
  uint8_t  plazasLibres;   // 1 B: plazas libres
  uint8_t  capacidadTotal; // 1 B: capacidad total
  uint8_t  flags;          // 1 B: bit0=ocupado, bit1=manual
  uint16_t checksum;       // 2 B: verificación de integridad del registro
}; // Total = 16 bytes exactos

static_assert(sizeof(BufferedReading) == 16, "BufferedReading debe medir exactamente 16 bytes");

const char* NVS_NAMESPACE       = "es3_buf";
const uint16_t MAX_NVS_RECORDS  = 128; // 128 * 16 B = 2048 B dentro de partición NVS 20 KB
const uint8_t  RAM_BATCH_SIZE   = 4;   // Lote RAM para minimizar escrituras flash

BufferedReading ramBatch[RAM_BATCH_SIZE];
uint8_t  ramBatchCount = 0;
uint16_t nvsHead       = 0; // Próximo índice circular (0..127)
uint16_t nvsCount      = 0; // Cantidad de lecturas almacenadas en NVS (0..128)
uint32_t nextSeqNumber = 1;

// Estado del Estacionamiento Inteligente (arranque en ESTADO SEGURO = 0°)
long distanciaCm              = 25;
int  umbralOcupacionCm        = 10;       // Configurable vía SET_UMBRAL (5..150 cm)
String estadoParking          = "LIBRE";
bool plazaPreviaOcupada       = false;
int  anguloBarrera            = 0;        // ESTADO SEGURO INICIAL: 0° = Barrera Cerrada
String modoOperacion          = "AUTO";   // "AUTO" o "MANUAL"
int  capacidadTotal           = 12;
int  plazasLibres             = 12;
uint8_t fallosConsecutivosSensor = 0;
bool enlaceActivoPrevio       = false;
unsigned long ultimoEnlaceActivoMs = 0;

// Temporizadores no bloqueantes con millis()
unsigned long ultimoMuestreoMs      = 0;
unsigned long ultimaTelemetriaMs    = 0;
unsigned long ultimoMuestreoNvsMs   = 0;
const unsigned long INTERVALO_MUESTREO_MS     = 250;
const unsigned long INTERVALO_TELEMETRIA_MS   = 1000;
const unsigned long INTERVALO_NVS_OFFLINE_MS  = 5000;  // 1 muestra offline cada 5 s -> flush cada ~20 s
const unsigned long FAILSAFE_LINK_TIMEOUT_MS  = 15000; // Cierra barrera si cae enlace en MANUAL >15 s
const size_t MAX_FRAME_BYTES                  = 512;

String rxBufferBt     = "";
String rxBufferTcp    = "";
String rxBufferSerial = "";

// Prototipos
long medirDistanciaUltrasonicaCm(bool& lecturaValida);
void evaluarLogicaControl();
void aplicarEstadoSeguro(const char* motivo);
void actualizarPantallaLcd();
void enviarLineaCliente(const String& linea);
bool hayClienteActivo();
String calcularCrcTrama(long dist, const String& estado, int barrera, const String& modo, int libres, int cap);
void enviarTramaTelemetria();
void enviarAck(const String& cmd, const String& reqId, const String& detail);
void enviarErr(const String& code, const String& reqId, const String& detail);
void procesarTramaComando(const String& jsonLine);
bool validarTokenOt(const String& token);
String extraerValorJsonString(const String& json, const String& clave);
bool extraerValorJsonIntEstricto(const String& json, const String& clave, int& outVal);

// Prototipos Buffer NVS
void inicializarNvsBuffer();
void cargarMetadatosNvs();
void guardarMetadatosNvs();
uint16_t calcularChecksumRegistro(const BufferedReading& r);
void registrarMuestraOffline(bool forzarFlush);
void volcarRamBatchANvs();
uint16_t contarLecturasPendientesTotal();
void flushBufferAlCliente(const String& reqId);

void setup() {
  Serial.begin(115200);
  pinMode(trigPin, OUTPUT);
  pinMode(echoPin, INPUT);

  Wire.begin(21, 22);
  lcd.init();
  lcd.backlight();
  lcd.clear();
  lcd.setCursor(0, 0);
  lcd.print("INACAP ES3 IoT");
  lcd.setCursor(0, 1);
  lcd.print("BARRERA SEG 0G");

  // Permitir asignación de todos los temporizadores PWM para compatibilidad con ESP32-WROOM-32D y Bluetooth
  ESP32PWM::allocateTimer(0);
  ESP32PWM::allocateTimer(1);
  ESP32PWM::allocateTimer(2);
  ESP32PWM::allocateTimer(3);
  barreraServo.setPeriodHertz(50);
  barreraServo.attach(servoPin, 500, 2400);

  // Auto-calibración inicial visible: barrera sube a 90° y baja a 0° (Estado Seguro Failsafe)
  barreraServo.write(90);
  delay(500);
  anguloBarrera = 0;
  barreraServo.write(anguloBarrera);
  delay(300);

  inicializarNvsBuffer();

#ifdef BT_PIN_CODE
  #if defined(ESP_ARDUINO_VERSION_MAJOR) && (ESP_ARDUINO_VERSION_MAJOR >= 3)
    SerialBT.setPin(BT_PIN_CODE, (uint8_t)strlen(BT_PIN_CODE));
  #else
    SerialBT.setPin(BT_PIN_CODE);
  #endif
#endif
  SerialBT.begin(BT_DEVICE_NAME);
  Serial.printf("Nodo OT ESP32 listo: Bluetooth '%s' | NVS pendientes=%u\n",
                BT_DEVICE_NAME, nvsCount);

#if ENABLE_WIFI_TCP
  WiFi.mode(WIFI_STA);
  WiFi.setAutoReconnect(true);
  #ifdef WIFI_SSID
    WiFi.begin(WIFI_SSID, WIFI_PASS);
    Serial.printf("[WIFI] Iniciando conexión a SSID '%s' (puerto %d)...\n",
                  WIFI_SSID, TCP_SERVER_PORT);
  #endif
  tcpServer.begin();
  tcpServer.setNoDelay(true);
#endif

  // Watchdog de Hardware (Clase 15: "Que no se caiga")
  // Si el microcontrolador deja de responder por > 8s, se reinicia y vuelve a Estado Seguro (0°)
  #if defined(ESP_IDF_VERSION_MAJOR) && (ESP_IDF_VERSION_MAJOR >= 5)
    esp_task_wdt_config_t twdt_config = {
      .timeout_ms = WDT_TIMEOUT_SECONDS * 1000,
      .idle_core_mask = (1 << portNUM_PROCESSORS) - 1,
      .trigger_panic = true
    };
    esp_task_wdt_init(&twdt_config);
  #else
    esp_task_wdt_init(WDT_TIMEOUT_SECONDS, true);
  #endif
  esp_task_wdt_add(NULL);
  Serial.printf("[WDT] Hardware Watchdog activado (%d segundos timeout)\n", WDT_TIMEOUT_SECONDS);
}

void loop() {
  esp_task_wdt_reset(); // Alimenta el Watchdog en cada ciclo
  unsigned long ahoraMs = millis();

  // 1A. Lectura no bloqueante de comandos JSON + '\n' por Bluetooth SPP
  while (SerialBT.available()) {
    char c = (char)SerialBT.read();
    if (c == '\n') {
      rxBufferBt.trim();
      if (rxBufferBt.length() > 0) {
        procesarTramaComando(rxBufferBt);
      }
      rxBufferBt = "";
    } else if (c != '\r') {
      rxBufferBt += c;
      if (rxBufferBt.length() > MAX_FRAME_BYTES) {
        rxBufferBt = "";
        enviarErr("CORRUPT_FRAME", "RX", "Trama excede 512 bytes (descartada)");
      }
    }
  }

  // 1B. Lectura no bloqueante por Wi-Fi TCP si ENABLE_WIFI_TCP == 1
#if ENABLE_WIFI_TCP
  static bool wifiConectadoPrevio = false;
  wl_status_t wifiEstadoActual = WiFi.status();

  if (wifiEstadoActual == WL_CONNECTED) {
    if (!wifiConectadoPrevio) {
      wifiConectadoPrevio = true;
      Serial.print("[WIFI] ¡Conectado exitosamente! IP asignada: ");
      Serial.println(WiFi.localIP());
      Serial.printf("[WIFI] Servidor TCP escuchando en %s:%d\n", WiFi.localIP().toString().c_str(), TCP_SERVER_PORT);
    }
    if (!tcpClient || !tcpClient.connected()) {
      WiFiClient nuevo = tcpServer.available();
      if (nuevo) {
        tcpClient = nuevo;
        tcpClient.setNoDelay(true);
        Serial.println("[TCP] Cliente Android conectado.");
      }
    }
    while (tcpClient && tcpClient.connected() && tcpClient.available()) {
      char c = (char)tcpClient.read();
      if (c == '\n') {
        rxBufferTcp.trim();
        if (rxBufferTcp.length() > 0) {
          procesarTramaComando(rxBufferTcp);
        }
        rxBufferTcp = "";
      } else if (c != '\r') {
        rxBufferTcp += c;
        if (rxBufferTcp.length() > MAX_FRAME_BYTES) {
          rxBufferTcp = "";
          enviarErr("CORRUPT_FRAME", "RX", "Trama TCP excede 512 bytes (descartada)");
        }
      }
    }
  } else {
    wifiConectadoPrevio = false;
    if (ahoraMs - ultimoReintentoWifiMs >= 8000) {
      ultimoReintentoWifiMs = ahoraMs;
      const char* desc = "Conectando...";
      if (wifiEstadoActual == WL_NO_SSID_AVAIL) {
        desc = "SSID no encontrado (verificar banda 2.4 GHz o nombre de red)";
      } else if (wifiEstadoActual == WL_CONNECT_FAILED) {
        desc = "Fallo de autenticacion (verificar contrasena)";
      } else if (wifiEstadoActual == WL_DISCONNECTED) {
        desc = "Desconectado (asociando con router/hotspot...)";
      }
      Serial.printf("[WIFI] Estado actual: %d (%s) - Reintentando conexión a '%s'...\n",
                    (int)wifiEstadoActual, desc, WIFI_SSID);
      WiFi.begin(WIFI_SSID, WIFI_PASS);
    }
  }
#endif

  // 1C. Lectura no bloqueante por Monitor Serie USB (útil para simulación Wokwi o pruebas por cable)
  while (Serial.available()) {
    char c = (char)Serial.read();
    if (c == '\n') {
      rxBufferSerial.trim();
      if (rxBufferSerial.length() > 0) {
        procesarTramaComando(rxBufferSerial);
      }
      rxBufferSerial = "";
    } else if (c != '\r') {
      rxBufferSerial += c;
      if (rxBufferSerial.length() > MAX_FRAME_BYTES) {
        rxBufferSerial = "";
        enviarErr("CORRUPT_FRAME", "RX", "Trama Serial excede 512 bytes (descartada)");
      }
    }
  }

  // 2. Muestreo HC-SR04 y actualización de barrera / LCD
  if (ahoraMs - ultimoMuestreoMs >= INTERVALO_MUESTREO_MS) {
    ultimoMuestreoMs = ahoraMs;
    bool lecturaValida = false;
    long medicion = medirDistanciaUltrasonicaCm(lecturaValida);

    if (!lecturaValida) {
      if (fallosConsecutivosSensor < 255) fallosConsecutivosSensor++;
      if (fallosConsecutivosSensor >= 3) {
        aplicarEstadoSeguro("Fallo consecutivo HC-SR04 (sin eco)");
      }
    } else {
      fallosConsecutivosSensor = 0;
      distanciaCm = medicion;
      evaluarLogicaControl();
    }
    actualizarPantallaLcd();
  }

  // 3. Monitoreo de enlace activo y failsafe en modo MANUAL
  bool conectado = hayClienteActivo();
  if (conectado) {
    ultimoEnlaceActivoMs = ahoraMs;
    if (!enlaceActivoPrevio) {
      enlaceActivoPrevio = true;
      Serial.printf("[LINK] Enlace establecido. Lecturas retenidas en flash NVS: %u\n",
                    contarLecturasPendientesTotal());
    }
  } else {
    if (enlaceActivoPrevio) {
      enlaceActivoPrevio = false;
      Serial.println("[LINK] Enlace perdido: iniciando almacenamiento offline en NVS.");
    }
    if (modoOperacion == "MANUAL" && (ahoraMs - ultimoEnlaceActivoMs >= FAILSAFE_LINK_TIMEOUT_MS)) {
      aplicarEstadoSeguro("Timeout sin enlace en MANUAL -> retorno seguro");
    }
  }

  // 4. Envío periódico de telemetría (online) O buffering en Flash NVS (offline)
  if (conectado) {
    if (ahoraMs - ultimaTelemetriaMs >= INTERVALO_TELEMETRIA_MS) {
      ultimaTelemetriaMs = ahoraMs;
      enviarTramaTelemetria();
    }
  } else {
    if (ahoraMs - ultimoMuestreoNvsMs >= INTERVALO_NVS_OFFLINE_MS) {
      ultimoMuestreoNvsMs = ahoraMs;
      registrarMuestraOffline(false);
    }
  }
}

long medirDistanciaUltrasonicaCm(bool& lecturaValida) {
  digitalWrite(trigPin, LOW);
  delayMicroseconds(2);
  digitalWrite(trigPin, HIGH);
  delayMicroseconds(10);
  digitalWrite(trigPin, LOW);

  long duracionUs = pulseIn(echoPin, HIGH, 25000); // Timeout 25 ms (~4 m)
  if (duracionUs <= 0) {
    lecturaValida = false;
    return distanciaCm;
  }
  long cm = (long)(duracionUs * 0.034 / 2.0);
  if (cm < 2 || cm > 400) {
    lecturaValida = false;
    return distanciaCm;
  }
  lecturaValida = true;
  return cm;
}

void evaluarLogicaControl() {
  bool ocupadaAhora = (distanciaCm > 0 && distanciaCm < umbralOcupacionCm);

  if (ocupadaAhora != plazaPreviaOcupada) {
    if (ocupadaAhora) {
      if (plazasLibres > 0) plazasLibres--;
    } else {
      if (plazasLibres < capacidadTotal) plazasLibres++;
    }
    plazaPreviaOcupada = ocupadaAhora;
    if (!hayClienteActivo()) {
      registrarMuestraOffline(true); // Guardar cambio de evento inmediatamente
    }
  }

  estadoParking = ocupadaAhora ? "OCUPADO" : "LIBRE";
  if (modoOperacion == "AUTO") {
    anguloBarrera = ocupadaAhora ? 0 : 90;
  }
  barreraServo.write(anguloBarrera);
}

void aplicarEstadoSeguro(const char* motivo) {
  if (anguloBarrera != 0 || modoOperacion != "AUTO") {
    anguloBarrera = 0; // Barrera cerrada = estado seguro
    modoOperacion = "AUTO";
    barreraServo.write(anguloBarrera);
    Serial.printf("[FAILSAFE] %s -> Barrera cerrada a 0°.\n", motivo);
  }
}

void actualizarPantallaLcd() {
  char fila0[17];
  char fila1[17];
  const char* modoCorto = (modoOperacion == "MANUAL") ? "MAN" : "AUT";

  snprintf(fila0, sizeof(fila0), "L:%2d/%2d D:%3ldcm", plazasLibres, capacidadTotal, distanciaCm);
  snprintf(fila1, sizeof(fila1), "%-7s %2dG %3s", estadoParking.c_str(), anguloBarrera, modoCorto);

  lcd.setCursor(0, 0);
  lcd.print(fila0);
  lcd.setCursor(0, 1);
  lcd.print(fila1);
}

bool hayClienteActivo() {
  if (SerialBT.hasClient()) return true;
#if ENABLE_WIFI_TCP
  if (tcpClient && tcpClient.connected()) return true;
#endif
  return false;
}

void enviarLineaCliente(const String& linea) {
  if (SerialBT.hasClient()) {
    SerialBT.print(linea);
  }
#if ENABLE_WIFI_TCP
  if (tcpClient && tcpClient.connected()) {
    tcpClient.print(linea);
  }
#endif
}

String calcularCrcTrama(long dist, const String& estado, int barrera, const String& modo, int libres, int cap) {
  String raw = String(dist) + "|" + estado + "|" + String(barrera) + "|" + modo + "|" +
               String(libres) + "/" + String(cap) + "|ES3";
  uint32_t sum = 0;
  for (size_t i = 0; i < raw.length(); i++) {
    sum = (sum * 31u + (uint8_t)raw[i]) & 0xFFFFu;
  }
  char buf[10];
  snprintf(buf, sizeof(buf), "OK-%04X", (unsigned int)sum);
  return String(buf);
}

void enviarTramaTelemetria() {
  String crc = calcularCrcTrama(distanciaCm, estadoParking, anguloBarrera, modoOperacion, plazasLibres, capacidadTotal);
  unsigned long uptimeSec = millis() / 1000UL;
  uint16_t pendientes = contarLecturasPendientesTotal();

  String trama = "{\"type\":\"TELEMETRY\",\"dist\":";
  trama += String(distanciaCm);
  trama += ",\"estado\":\"";
  trama += estadoParking;
  trama += "\",\"barrera\":";
  trama += String(anguloBarrera);
  trama += ",\"modo\":\"";
  trama += modoOperacion;
  trama += "\",\"plazasLibres\":";
  trama += String(plazasLibres);
  trama += ",\"capacidadTotal\":";
  trama += String(capacidadTotal);
  trama += ",\"umbral\":";
  trama += String(umbralOcupacionCm);
  trama += ",\"uptime_s\":";
  trama += String(uptimeSec);
  trama += ",\"buffered\":false,\"pendingNvs\":";
  trama += String(pendientes);
  trama += ",\"crc\":\"";
  trama += crc;
  trama += "\"}\n";

  enviarLineaCliente(trama);
  Serial.print("TX -> " + trama);
}

void enviarAck(const String& cmd, const String& reqId, const String& detail) {
  String trama = "{\"type\":\"ACK\",\"status\":\"ACK\",\"cmd\":\"" + cmd +
                 "\",\"reqId\":\"" + reqId +
                 "\",\"detail\":\"" + detail +
                 "\",\"msg\":\"" + detail + "\"}\n";
  enviarLineaCliente(trama);
  Serial.print("ACK -> " + trama);
}

void enviarErr(const String& code, const String& reqId, const String& detail) {
  String trama = "{\"type\":\"ERR\",\"status\":\"ERR\",\"code\":\"" + code +
                 "\",\"reqId\":\"" + reqId +
                 "\",\"detail\":\"" + detail +
                 "\",\"msg\":\"" + detail + "\"}\n";
  enviarLineaCliente(trama);
  Serial.print("ERR -> " + trama);
}

bool validarTokenOt(const String& token) {
#ifdef OT_SHARED_TOKEN
  return token == String(OT_SHARED_TOKEN);
#else
  return token.startsWith("INA-") && token.length() >= 12;
#endif
}

void procesarTramaComando(const String& jsonLine) {
  if (jsonLine.length() > MAX_FRAME_BYTES || !jsonLine.startsWith("{") || !jsonLine.endsWith("}")) {
    enviarErr("CORRUPT_FRAME", "RX", "Trama JSON incompleta o corrupta descartada");
    return;
  }

  String reqId = extraerValorJsonString(jsonLine, "reqId");
  if (reqId.length() == 0) reqId = "ESP";

  String token = extraerValorJsonString(jsonLine, "token");
  if (!validarTokenOt(token)) {
    enviarErr("UNAUTHORIZED", reqId, "Token ISO 27400 invalido o ausente");
    return;
  }

  String cmd  = extraerValorJsonString(jsonLine, "cmd");
  String modo = extraerValorJsonString(jsonLine, "modo");
  int val     = -1;
  bool hasNumericVal = extraerValorJsonIntEstricto(jsonLine, "val", val);
  if (!hasNumericVal) {
    hasNumericVal = extraerValorJsonIntEstricto(jsonLine, "angle", val);
  }

  if (cmd.length() == 0) {
    enviarErr("CORRUPT_FRAME", reqId, "Campo 'cmd' ausente en trama JSON");
    return;
  }

  if (cmd == "SET_BARRERA") {
    if (!hasNumericVal || (val != 0 && val != 90)) {
      enviarErr("OUT_OF_RANGE", reqId, "Angulo invalido; solo se permite 0 o 90 grados");
      return;
    }
    modoOperacion = "MANUAL";
    anguloBarrera = val;
    barreraServo.write(anguloBarrera);
    enviarAck(cmd, reqId, "Barrera ajustada a " + String(anguloBarrera) + "G (MANUAL)");
  } else if (cmd == "SET_MODO") {
    if (modo != "AUTO" && modo != "MANUAL") {
      enviarErr("OUT_OF_RANGE", reqId, "Modo invalido; use AUTO o MANUAL");
      return;
    }
    modoOperacion = modo;
    evaluarLogicaControl();
    enviarAck(cmd, reqId, "Modo establecido en " + modoOperacion);
  } else if (cmd == "SET_UMBRAL") {
    if (!hasNumericVal || val < 5 || val > 150) {
      enviarErr("OUT_OF_RANGE", reqId, "Umbral fuera de rango (5..150 cm)");
      return;
    }
    umbralOcupacionCm = val;
    evaluarLogicaControl();
    enviarAck(cmd, reqId, "Umbral de deteccion actualizado a " + String(umbralOcupacionCm) + " cm");
  } else if (cmd == "SET_CAPACIDAD") {
    if (!hasNumericVal || val < 1 || val > 200) {
      enviarErr("OUT_OF_RANGE", reqId, "Capacidad fuera de rango (1..200)");
      return;
    }
    capacidadTotal = val;
    if (plazasLibres > capacidadTotal) plazasLibres = capacidadTotal;
    enviarAck(cmd, reqId, "Capacidad actualizada a " + String(capacidadTotal));
  } else if (cmd == "SET_PLAZAS") {
    if (!hasNumericVal || val < 0 || val > capacidadTotal) {
      enviarErr("OUT_OF_RANGE", reqId, "Plazas libres fuera de rango (0..capacidad)");
      return;
    }
    plazasLibres = val;
    enviarAck(cmd, reqId, "Plazas libres ajustadas a " + String(plazasLibres));
  } else if (cmd == "REQ_STATUS" || cmd == "SYNC_RECORD") {
    enviarAck(cmd, reqId, "Estado confirmado");
  } else if (cmd == "FLUSH_BUFFER") {
    flushBufferAlCliente(reqId);
    return;
  } else {
    enviarErr("UNKNOWN_CMD", reqId, "Comando inexistente: " + cmd);
    return;
  }

  actualizarPantallaLcd();
  enviarTramaTelemetria();
}

// ============================================================================
// IMPLEMENTACIÓN DEL BUFFER CIRCULAR EN NVS (Preferences) SIN SD
// ============================================================================

uint16_t calcularChecksumRegistro(const BufferedReading& r) {
  const uint8_t* ptr = reinterpret_cast<const uint8_t*>(&r);
  uint16_t sum = 0x5A3C;
  for (size_t i = 0; i < sizeof(BufferedReading) - sizeof(uint16_t); i++) {
    sum = (uint16_t)((sum * 33u + ptr[i]) & 0xFFFFu);
  }
  return sum;
}

void inicializarNvsBuffer() {
  cargarMetadatosNvs();
}

void cargarMetadatosNvs() {
  prefs.begin(NVS_NAMESPACE, true);
  nvsHead       = prefs.getUShort("head", 0);
  nvsCount      = prefs.getUShort("cnt", 0);
  nextSeqNumber = prefs.getUInt("seq", 1);
  prefs.end();

  if (nvsHead >= MAX_NVS_RECORDS) nvsHead = 0;
  if (nvsCount > MAX_NVS_RECORDS) nvsCount = MAX_NVS_RECORDS;
  if (nextSeqNumber == 0) nextSeqNumber = 1;
}

void guardarMetadatosNvs() {
  prefs.putUShort("head", nvsHead);
  prefs.putUShort("cnt", nvsCount);
  prefs.putUInt("seq", nextSeqNumber);
}

void registrarMuestraOffline(bool forzarFlush) {
  BufferedReading reg;
  reg.seq            = nextSeqNumber++;
  reg.uptimeSec      = millis() / 1000UL;
  reg.distCm         = (uint16_t)constrain(distanciaCm, 0L, 400L);
  reg.barreraAngle   = (uint8_t)constrain(anguloBarrera, 0, 90);
  reg.plazasLibres   = (uint8_t)constrain(plazasLibres, 0, 200);
  reg.capacidadTotal = (uint8_t)constrain(capacidadTotal, 1, 200);
  reg.flags          = (estadoParking == "OCUPADO" ? 0x01 : 0x00) |
                       (modoOperacion == "MANUAL" ? 0x02 : 0x00);
  reg.checksum       = calcularChecksumRegistro(reg);

  ramBatch[ramBatchCount++] = reg;
  if (ramBatchCount >= RAM_BATCH_SIZE || forzarFlush) {
    volcarRamBatchANvs();
  }
}

void volcarRamBatchANvs() {
  if (ramBatchCount == 0) return;
  prefs.begin(NVS_NAMESPACE, false);
  for (uint8_t i = 0; i < ramBatchCount; i++) {
    char key[8];
    snprintf(key, sizeof(key), "r%03u", nvsHead);
    prefs.putBytes(key, &ramBatch[i], sizeof(BufferedReading));
    nvsHead = (uint16_t)((nvsHead + 1) % MAX_NVS_RECORDS);
    if (nvsCount < MAX_NVS_RECORDS) {
      nvsCount++;
    }
  }
  guardarMetadatosNvs();
  prefs.end();

  Serial.printf("[NVS] Lote de %u lecturas guardado en flash (total NVS=%u/%u)\n",
                ramBatchCount, nvsCount, MAX_NVS_RECORDS);
  ramBatchCount = 0;
}

uint16_t contarLecturasPendientesTotal() {
  return (uint16_t)(nvsCount + ramBatchCount);
}

void flushBufferAlCliente(const String& reqId) {
  volcarRamBatchANvs();
  if (nvsCount == 0) {
    enviarAck("FLUSH_BUFFER", reqId, "Buffer NVS vacio (0 lecturas pendientes)");
    return;
  }

  uint16_t enviadas = 0;
  uint16_t startIdx = (nvsHead + MAX_NVS_RECORDS - nvsCount) % MAX_NVS_RECORDS;
  unsigned long ahoraSeg = millis() / 1000UL;

  prefs.begin(NVS_NAMESPACE, false);
  for (uint16_t i = 0; i < nvsCount; i++) {
    uint16_t idx = (startIdx + i) % MAX_NVS_RECORDS;
    char key[8];
    snprintf(key, sizeof(key), "r%03u", idx);

    BufferedReading reg;
    size_t readLen = prefs.getBytes(key, &reg, sizeof(BufferedReading));
    if (readLen != sizeof(BufferedReading)) continue;
    if (reg.checksum != calcularChecksumRegistro(reg)) continue;

    String est = (reg.flags & 0x01) ? "OCUPADO" : "LIBRE";
    String mod = (reg.flags & 0x02) ? "MANUAL" : "AUTO";
    unsigned long ageSec = (ahoraSeg >= reg.uptimeSec) ? (ahoraSeg - reg.uptimeSec) : 0UL;
    String crc = calcularCrcTrama(reg.distCm, est, reg.barreraAngle, mod, reg.plazasLibres, reg.capacidadTotal);

    String trama = "{\"type\":\"TELEMETRY\",\"dist\":";
    trama += String(reg.distCm);
    trama += ",\"estado\":\"";
    trama += est;
    trama += "\",\"barrera\":";
    trama += String(reg.barreraAngle);
    trama += ",\"modo\":\"";
    trama += mod;
    trama += "\",\"plazasLibres\":";
    trama += String(reg.plazasLibres);
    trama += ",\"capacidadTotal\":";
    trama += String(reg.capacidadTotal);
    trama += ",\"umbral\":";
    trama += String(umbralOcupacionCm);
    trama += ",\"uptime_s\":";
    trama += String(reg.uptimeSec);
    trama += ",\"buffered\":true,\"seq\":";
    trama += String(reg.seq);
    trama += ",\"age_s\":";
    trama += String(ageSec);
    trama += ",\"crc\":\"";
    trama += crc;
    trama += "\"}\n";

    enviarLineaCliente(trama);
    enviadas++;
  }

  // Vaciar índices tras enviar
  nvsCount = 0;
  nvsHead  = 0;
  guardarMetadatosNvs();
  prefs.end();

  enviarAck("FLUSH_BUFFER", reqId, "Buffer NVS sincronizado: " + String(enviadas) + " lecturas");
}

// ============================================================================
// UTILIDADES DE PARSING JSON SIN BLOQUEO
// ============================================================================

String extraerValorJsonString(const String& json, const String& clave) {
  String patron = "\"" + clave + "\":\"";
  int inicio = json.indexOf(patron);
  if (inicio == -1) return "";
  inicio += patron.length();
  int fin = json.indexOf("\"", inicio);
  if (fin == -1) return "";
  return json.substring(inicio, fin);
}

bool extraerValorJsonIntEstricto(const String& json, const String& clave, int& outVal) {
  String patron = "\"" + clave + "\":";
  int inicio = json.indexOf(patron);
  if (inicio == -1) return false;
  inicio += patron.length();
  while (inicio < (int)json.length() && json[inicio] == ' ') inicio++;
  if (inicio >= (int)json.length()) return false;

  int fin = inicio;
  if (json[fin] == '-') fin++;
  int digitos = 0;
  while (fin < (int)json.length() && isDigit(json[fin])) {
    fin++;
    digitos++;
  }
  if (digitos == 0) return false;
  if (fin < (int)json.length() && json[fin] != ',' && json[fin] != '}' && json[fin] != ' ') {
    return false;
  }
  outVal = json.substring(inicio, fin).toInt();
  return true;
}
