/**
 * SISTEMA DE ESTACIONAMIENTO INTELIGENTE Y CONTROL DE BARRERA (ES3 — TI3042)
 * Hardware: ESP32 DevKit + HC-SR04 + Servo SG90 + LCD 1602 I2C (0x27)
 * Conectividad: Bluetooth Clásico SPP (UUID 00001101-0000-1000-8000-00805F9B34FB)
 * Almacenamiento Offline: Memoria Flash NVS (Preferences)
 */

#include <Wire.h>
#include <LiquidCrystal_I2C.h>
#include <ESP32Servo.h>
#include <Preferences.h>
#include <esp_task_wdt.h>
#include "BluetoothSerial.h"

#define WDT_TIMEOUT_SECONDS 8

#if __has_include("secrets.h")
  #include "secrets.h"
#elif __has_include("secrets_local.h")
  #include "secrets_local.h"
#endif

#ifndef BT_DEVICE_NAME
  #define BT_DEVICE_NAME "ESP32_Barrera_INACAP"
#endif

#ifndef ENABLE_WIFI_TCP
  #define ENABLE_WIFI_TCP 0
#endif

#if ENABLE_WIFI_TCP
  #include <WiFi.h>
  #ifndef TCP_SERVER_PORT
    #define TCP_SERVER_PORT 8888
  #endif
  WiFiServer tcpServer(TCP_SERVER_PORT);
  WiFiClient tcpClient;
  unsigned long ultimoReintentoWifiMs = 0;
#endif

// Pines de Hardware
const int trigPin  = 5;
const int echoPin  = 18;
const int servoPin = 15;

// Periféricos
LiquidCrystal_I2C lcd(0x27, 16, 2);
Servo barreraServo;
BluetoothSerial SerialBT;
Preferences prefs;

// Estructura de registro offline en Flash NVS (16 bytes exactos)
struct __attribute__((packed)) BufferedReading {
  uint32_t seq;
  uint32_t uptimeSec;
  uint16_t distCm;
  uint8_t  barreraAngle;
  uint8_t  plazasLibres;
  uint8_t  capacidadTotal;
  uint8_t  flags;
  uint16_t checksum;
};

const char* NVS_NAMESPACE      = "es3_buf";
const uint16_t MAX_NVS_RECORDS = 128;
uint16_t nvsHead       = 0;
uint16_t nvsCount      = 0;
uint32_t nextSeqNumber = 1;

// Variables de estado del estacionamiento
long distanciaCm        = 25;
int  umbralOcupacionCm  = 10;
String estadoParking    = "LIBRE";
int  anguloBarrera      = 0;        // Estado Seguro: 0° = Cerrada
String modoOperacion    = "AUTO";   // "AUTO" o "MANUAL"
int  capacidadTotal     = 12;
int  plazasLibres       = 12;
bool plazaPreviaOcupada = false;

// Temporizadores no bloqueantes
unsigned long ultimoMuestreoMs    = 0;
unsigned long ultimaTelemetriaMs  = 0;
unsigned long ultimoMuestreoNvsMs = 0;
const unsigned long INTERVALO_MUESTREO_MS    = 250;
const unsigned long INTERVALO_TELEMETRIA_MS  = 1000;
const unsigned long INTERVALO_NVS_OFFLINE_MS = 5000;

String rxBufferBt = "";
#if ENABLE_WIFI_TCP
String rxBufferTcp = "";
#endif

// Declaración de funciones
long medirDistanciaCm();
void evaluarLogicaControl();
void actualizarLcd();
void enviarLinea(const String& linea);
bool hayClienteActivo();
void enviarTelemetria();
void enviarAck(const String& cmd, const String& reqId, const String& detail);
void enviarErr(const String& code, const String& reqId, const String& detail);
void procesarComando(const String& json);
void inicializarNvs();
void registrarOffline();
void flushNvsAlCliente(const String& reqId);
String extraerJsonString(const String& json, const String& clave);
bool extraerJsonInt(const String& json, const String& clave, int& outVal);

void setup() {
  Serial.begin(115200);
  delay(100);
  Serial.println("\n--- INICIO SISTEMA ESTACIONAMIENTO IOT (ES3) ---");

  // Sensor ultrasónico
  pinMode(trigPin, OUTPUT);
  pinMode(echoPin, INPUT);
  digitalWrite(trigPin, LOW);

  // Pantalla LCD I2C
  Wire.begin(21, 22);
  lcd.init();
  lcd.backlight();
  lcd.clear();
  lcd.setCursor(0, 0);
  lcd.print("ESP32 IoT INACAP");
  lcd.setCursor(0, 1);
  lcd.print("Iniciando...");

  // Servomotor con timers de ESP32Servo
  ESP32PWM::allocateTimer(0);
  ESP32PWM::allocateTimer(1);
  ESP32PWM::allocateTimer(2);
  ESP32PWM::allocateTimer(3);
  barreraServo.setPeriodHertz(50);
  barreraServo.attach(servoPin, 500, 2400);

  // Auto-calibración inicial: Barrera sube a 90° y baja a 0° (Failsafe)
  barreraServo.write(90);
  delay(500);
  anguloBarrera = 0;
  barreraServo.write(0);
  delay(300);

  // Cargar registros NVS
  inicializarNvs();

  // Bluetooth Clásico SPP
#ifdef BT_PIN_CODE
  #if defined(ESP_ARDUINO_VERSION_MAJOR) && (ESP_ARDUINO_VERSION_MAJOR >= 3)
    SerialBT.setPin(BT_PIN_CODE, (uint8_t)strlen(BT_PIN_CODE));
  #else
    SerialBT.setPin(BT_PIN_CODE);
  #endif
#endif
  SerialBT.begin(BT_DEVICE_NAME);
  Serial.printf("[BT] Servidor listo como '%s' | Pendientes NVS: %u\n", BT_DEVICE_NAME, nvsCount);

#if ENABLE_WIFI_TCP
  #ifdef WIFI_SSID
    WiFi.mode(WIFI_STA);
    WiFi.setAutoReconnect(true);
    WiFi.begin(WIFI_SSID, WIFI_PASS);
    Serial.printf("[WIFI] Conectando a '%s'...\n", WIFI_SSID);
  #endif
  tcpServer.begin();
  tcpServer.setNoDelay(true);
#endif

  // Watchdog de Hardware (8 segundos de timeout)
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
  Serial.printf("[WDT] Watchdog de hardware activo (%d s)\n", WDT_TIMEOUT_SECONDS);

  actualizarLcd();
}

void loop() {
  esp_task_wdt_reset();
  unsigned long ahora = millis();

  // 1. Recepción de comandos por Bluetooth SPP
  while (SerialBT.available()) {
    char c = (char)SerialBT.read();
    if (c == '\n') {
      rxBufferBt.trim();
      if (rxBufferBt.length() > 0) procesarComando(rxBufferBt);
      rxBufferBt = "";
    } else if (c != '\r' && rxBufferBt.length() < 256) {
      rxBufferBt += c;
    }
  }

#if ENABLE_WIFI_TCP
  // Recepción Wi-Fi opcional
  if (WiFi.status() == WL_CONNECTED) {
    if (!tcpClient || !tcpClient.connected()) {
      WiFiClient nuevo = tcpServer.available();
      if (nuevo) { tcpClient = nuevo; tcpClient.setNoDelay(true); }
    }
    while (tcpClient && tcpClient.connected() && tcpClient.available()) {
      char c = (char)tcpClient.read();
      if (c == '\n') {
        rxBufferTcp.trim();
        if (rxBufferTcp.length() > 0) procesarComando(rxBufferTcp);
        rxBufferTcp = "";
      } else if (c != '\r' && rxBufferTcp.length() < 256) {
        rxBufferTcp += c;
      }
    }
  }
#endif

  // 2. Muestreo del sensor ultrasónico cada 250 ms
  if (ahora - ultimoMuestreoMs >= INTERVALO_MUESTREO_MS) {
    ultimoMuestreoMs = ahora;
    distanciaCm = medirDistanciaCm();
    evaluarLogicaControl();
    actualizarLcd();
  }

  // 3. Envío de telemetría periódica cada 1 segundo
  if (ahora - ultimaTelemetriaMs >= INTERVALO_TELEMETRIA_MS) {
    ultimaTelemetriaMs = ahora;
    enviarTelemetria();
  }

  // 4. Registro offline en Flash NVS si no hay enlace activo (cada 5 segundos)
  if (!hayClienteActivo()) {
    if (ahora - ultimoMuestreoNvsMs >= INTERVALO_NVS_OFFLINE_MS) {
      ultimoMuestreoNvsMs = ahora;
      registrarOffline();
    }
  }
}

// Medición HC-SR04
long medirDistanciaCm() {
  digitalWrite(trigPin, LOW);
  delayMicroseconds(2);
  digitalWrite(trigPin, HIGH);
  delayMicroseconds(10);
  digitalWrite(trigPin, LOW);

  long duracion = pulseIn(echoPin, HIGH, 30000); // Timeout 30ms (~5 metros)
  if (duracion <= 0) return 999;
  long dist = duracion / 58;
  return constrain(dist, 0, 400);
}

// Lógica de control de estacionamiento
void evaluarLogicaControl() {
  bool ocupado = (distanciaCm <= umbralOcupacionCm && distanciaCm > 0);
  estadoParking = ocupado ? "OCUPADO" : "LIBRE";

  // Transición de LIBRE a OCUPADO (ingreso de vehículo)
  if (ocupado && !plazaPreviaOcupada) {
    if (plazasLibres > 0) plazasLibres--;
    plazaPreviaOcupada = true;
  } else if (!ocupado && plazaPreviaOcupada) {
    plazaPreviaOcupada = false;
  }

  // En modo AUTO, la barrera abre si detecta vehículo y hay plazas
  if (modoOperacion == "AUTO") {
    int nuevoAngulo = (ocupado && plazasLibres > 0) ? 90 : 0;
    if (nuevoAngulo != anguloBarrera) {
      anguloBarrera = nuevoAngulo;
      barreraServo.write(anguloBarrera);
    }
  }
}

// Actualización LCD 1602 I2C
void actualizarLcd() {
  char l1[17], l2[17];
  snprintf(l1, sizeof(l1), "L:%2d/%2d D:%3ldcm", plazasLibres, capacidadTotal, distanciaCm);
  const char* mStr = (modoOperacion == "MANUAL") ? "MAN" : "AUT";
  snprintf(l2, sizeof(l2), "%-7s %2dG %3s", estadoParking.c_str(), anguloBarrera, mStr);

  lcd.setCursor(0, 0);
  lcd.print(l1);
  lcd.setCursor(0, 1);
  lcd.print(l2);
}

bool hayClienteActivo() {
  if (SerialBT.hasClient()) return true;
#if ENABLE_WIFI_TCP
  if (tcpClient && tcpClient.connected()) return true;
#endif
  return false;
}

void enviarLinea(const String& linea) {
  if (SerialBT.hasClient()) SerialBT.println(linea);
#if ENABLE_WIFI_TCP
  if (tcpClient && tcpClient.connected()) tcpClient.println(linea);
#endif
  Serial.println(linea);
}

void enviarTelemetria() {
  String t = "{\"type\":\"TELEMETRY\",\"dist\":" + String(distanciaCm) +
             ",\"estado\":\"" + estadoParking + "\"" +
             ",\"barrera\":" + String(anguloBarrera) +
             ",\"modo\":\"" + modoOperacion + "\"" +
             ",\"plazasLibres\":" + String(plazasLibres) +
             ",\"capacidadTotal\":" + String(capacidadTotal) +
             ",\"pendingNvs\":" + String(nvsCount) +
             ",\"umbralCm\":" + String(umbralOcupacionCm) +
             ",\"crc\":\"OK\"}";
  enviarLinea(t);
}

void enviarAck(const String& cmd, const String& reqId, const String& detail) {
  String a = "{\"ack\":\"" + cmd + "\",\"id\":\"" + reqId + "\",\"status\":\"OK\",\"detail\":\"" + detail + "\"}";
  enviarLinea(a);
}

void enviarErr(const String& code, const String& reqId, const String& detail) {
  String e = "{\"error\":\"" + code + "\",\"id\":\"" + reqId + "\",\"detail\":\"" + detail + "\"}";
  enviarLinea(e);
}

// Procesador de comandos JSON
void procesarComando(const String& json) {
  String cmd   = extraerJsonString(json, "cmd");
  String reqId = extraerJsonString(json, "id");
  if (reqId == "") reqId = "N/A";

  if (cmd == "ABRIR") {
    modoOperacion = "MANUAL";
    anguloBarrera = 90;
    barreraServo.write(anguloBarrera);
    enviarAck("ABRIR", reqId, "Barrera abierta a 90° (Modo MANUAL)");
  }
  else if (cmd == "CERRAR") {
    modoOperacion = "MANUAL";
    anguloBarrera = 0;
    barreraServo.write(anguloBarrera);
    enviarAck("CERRAR", reqId, "Barrera cerrada a 0° (Modo MANUAL)");
  }
  else if (cmd == "AUTO") {
    modoOperacion = "AUTO";
    evaluarLogicaControl();
    enviarAck("AUTO", reqId, "Modo automático activado");
  }
  else if (cmd == "MANUAL") {
    modoOperacion = "MANUAL";
    enviarAck("MANUAL", reqId, "Modo manual activado");
  }
  else if (cmd == "SET_UMBRAL") {
    int nuevoUmb = 0;
    if (extraerJsonInt(json, "valor", nuevoUmb) && nuevoUmb >= 5 && nuevoUmb <= 150) {
      umbralOcupacionCm = nuevoUmb;
      enviarAck("SET_UMBRAL", reqId, "Umbral actualizado a " + String(umbralOcupacionCm) + " cm");
    } else {
      enviarErr("VAL_OUT_OF_RANGE", reqId, "Umbral debe estar entre 5 y 150 cm");
    }
  }
  else if (cmd == "FLUSH_BUFFER") {
    flushNvsAlCliente(reqId);
  }
  else if (cmd == "PING") {
    enviarAck("PING", reqId, "PONG_OK");
  }
  else {
    enviarErr("UNKNOWN_CMD", reqId, "Comando desconocido: " + cmd);
  }
  actualizarLcd();
}

// Almacenamiento Offline en Flash NVS (Preferences)
void inicializarNvs() {
  prefs.begin(NVS_NAMESPACE, false);
  nvsHead = prefs.getUShort("head", 0);
  nvsCount = prefs.getUShort("count", 0);
  nextSeqNumber = prefs.getUInt("seq", 1);
  if (nvsHead >= MAX_NVS_RECORDS) nvsHead = 0;
  if (nvsCount > MAX_NVS_RECORDS) nvsCount = 0;
}

void registrarOffline() {
  BufferedReading r;
  r.seq = nextSeqNumber++;
  r.uptimeSec = (uint32_t)(millis() / 1000);
  r.distCm = (uint16_t)distanciaCm;
  r.barreraAngle = (uint8_t)anguloBarrera;
  r.plazasLibres = (uint8_t)plazasLibres;
  r.capacidadTotal = (uint8_t)capacidadTotal;
  r.flags = (estadoParking == "OCUPADO" ? 1 : 0);
  r.checksum = (uint16_t)(r.seq ^ r.distCm ^ r.barreraAngle ^ r.plazasLibres);

  char key[16];
  snprintf(key, sizeof(key), "r_%u", nvsHead);
  prefs.putBytes(key, &r, sizeof(BufferedReading));

  nvsHead = (nvsHead + 1) % MAX_NVS_RECORDS;
  if (nvsCount < MAX_NVS_RECORDS) nvsCount++;

  prefs.putUShort("head", nvsHead);
  prefs.putUShort("count", nvsCount);
  prefs.putUInt("seq", nextSeqNumber);
}

void flushNvsAlCliente(const String& reqId) {
  uint16_t total = nvsCount;
  if (total == 0) {
    enviarAck("FLUSH_BUFFER", reqId, "Sin lecturas offline pendientes");
    return;
  }

  uint16_t startIdx = (nvsHead >= total) ? (nvsHead - total) : (MAX_NVS_RECORDS - (total - nvsHead));
  for (uint16_t i = 0; i < total; i++) {
    uint16_t slot = (startIdx + i) % MAX_NVS_RECORDS;
    char key[16];
    snprintf(key, sizeof(key), "r_%u", slot);
    BufferedReading r;
    if (prefs.getBytes(key, &r, sizeof(BufferedReading)) == sizeof(BufferedReading)) {
      String json = "{\"type\":\"BUFFER_DUMP\",\"seq\":" + String(r.seq) +
                    ",\"dist\":" + String(r.distCm) +
                    ",\"barrera\":" + String(r.barreraAngle) +
                    ",\"estado\":\"" + ((r.flags & 1) ? "OCUPADO" : "LIBRE") + "\"" +
                    ",\"plazasLibres\":" + String(r.plazasLibres) +
                    ",\"capacidadTotal\":" + String(r.capacidadTotal) +
                    ",\"uptime\":" + String(r.uptimeSec) + "}";
      enviarLinea(json);
      delay(15);
    }
  }

  // Limpiar registros volcados
  nvsHead = 0;
  nvsCount = 0;
  prefs.putUShort("head", 0);
  prefs.putUShort("count", 0);
  enviarAck("FLUSH_BUFFER", reqId, "Descargados " + String(total) + " registros offline exitosamente");
}

// Helpers JSON ligeros (sin librerías pesadas)
String extraerJsonString(const String& json, const String& clave) {
  String token = "\"" + clave + "\":";
  int idx = json.indexOf(token);
  if (idx < 0) return "";
  idx += token.length();
  while (idx < json.length() && (json[idx] == ' ' || json[idx] == '\"')) idx++;
  int end = idx;
  while (end < json.length() && json[end] != '\"' && json[end] != ',' && json[end] != '}') end++;
  return json.substring(idx, end);
}

bool extraerJsonInt(const String& json, const String& clave, int& outVal) {
  String token = "\"" + clave + "\":";
  int idx = json.indexOf(token);
  if (idx < 0) return false;
  idx += token.length();
  while (idx < json.length() && json[idx] == ' ') idx++;
  int end = idx;
  while (end < json.length() && (isDigit(json[end]) || json[end] == '-')) end++;
  if (end == idx) return false;
  outVal = json.substring(idx, end).toInt();
  return true;
}
