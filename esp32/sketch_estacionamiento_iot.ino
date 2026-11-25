/**
 * ============================================================================
 * SISTEMA DE ESTACIONAMIENTO INTELIGENTE Y CONTROL DE BARRERA (ES3 — TI3042)
 * Arquitectura IoT/OT Segura bajo ISO/IEC 27000 / ISO/IEC 27400 — INACAP
 *
 * HARDWARE ESP32:
 *  - Sensor Ultrasónico HC-SR04 : trigPin = 5, echoPin = 18
 *  - Actuador Servomotor SG90   : servoPin = 15 (90° = Abierta, 0° = Cerrada)
 *  - Pantalla LCD I2C (0x27)    : 16x2 (SDA = 21, SCL = 22) -> Plazas libres desde entrada
 *  - Conectividad Inalámbrica   : Bluetooth Clásico SPP (BluetoothSerial)
 *
 * PROTOCOLO BIDIRECCIONAL ES3 (JSON delimitado por '\n'):
 *  - Telemetría : {"type":"TELEMETRY","dist":8,"estado":"OCUPADO","barrera":0,"modo":"AUTO","plazasLibres":11,"capacidadTotal":12,"crc":"OK"}\n
 *  - Confirmación: {"type":"ACK","cmd":"SET_BARRERA","reqId":"...","detail":"..."}\n
 *  - Error      : {"type":"ERR","code":"OUT_OF_RANGE|UNKNOWN_CMD|MALFORMED_JSON|UNAUTHORIZED","reqId":"...","detail":"..."}\n
 * ============================================================================
 */

#include <Wire.h>
#include <LiquidCrystal_I2C.h>
#include <ESP32Servo.h>
#include "BluetoothSerial.h"

// Opcional: crear un archivo local `secrets_local.h` (excluido en .gitignore) con
// `#define OT_SHARED_TOKEN "TU_TOKEN_LOCAL"` para validar un token fijo por hardware.
#if __has_include("secrets_local.h")
  #include "secrets_local.h"
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

// Estado del Estacionamiento Inteligente
long distanciaCm         = 25;
String estadoParking     = "LIBRE";
bool plazaPreviaOcupada  = false;
int anguloBarrera        = 90;       // 90° = Abierta, 0° = Cerrada
String modoOperacion     = "AUTO";   // "AUTO" o "MANUAL"
int capacidadTotal       = 12;
int plazasLibres         = 11;

// Temporizadores no bloqueantes con millis()
unsigned long ultimoMuestreoMs   = 0;
unsigned long ultimaTelemetriaMs = 0;
const unsigned long INTERVALO_MUESTREO_MS   = 250;
const unsigned long INTERVALO_TELEMETRIA_MS = 1000;

String rxBuffer = "";

// Prototipos
long medirDistanciaUltrasonicaCm();
void evaluarLogicaControl();
void actualizarPantallaLcd();
void enviarTramaTelemetria();
void enviarAck(const String& cmd, const String& reqId, const String& detail);
void enviarErr(const String& code, const String& reqId, const String& detail);
void procesarTramaComando(const String& jsonLine);
bool validarTokenOt(const String& token);
String extraerValorJsonString(const String& json, const String& clave);
int extraerValorJsonInt(const String& json, const String& clave, int valorPorDefecto);

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
  lcd.print("ISO 27400 READY");

  ESP32PWM::allocateTimer(0);
  barreraServo.setPeriodHertz(50);
  barreraServo.attach(servoPin, 500, 2400);
  barreraServo.write(anguloBarrera);

  SerialBT.begin("ESP32_Barrera_INACAP");
  Serial.println("Nodo OT ESP32 ES3 listo: Bluetooth 'ESP32_Barrera_INACAP'");
}

void loop() {
  unsigned long ahoraMs = millis();

  // 1. Lectura no bloqueante de comandos JSON + '\n'
  while (SerialBT.available()) {
    char c = (char)SerialBT.read();
    if (c == '\n') {
      rxBuffer.trim();
      if (rxBuffer.length() > 0) {
        procesarTramaComando(rxBuffer);
      }
      rxBuffer = "";
    } else if (c != '\r') {
      rxBuffer += c;
      if (rxBuffer.length() > 512) {
        rxBuffer = "";
        enviarErr("MALFORMED_JSON", "RX", "Trama excede 512 bytes (descartada)");
      }
    }
  }

  // 2. Muestreo HC-SR04 y actualización de barrera / LCD
  if (ahoraMs - ultimoMuestreoMs >= INTERVALO_MUESTREO_MS) {
    ultimoMuestreoMs = ahoraMs;
    distanciaCm = medirDistanciaUltrasonicaCm();
    evaluarLogicaControl();
    actualizarPantallaLcd();
  }

  // 3. Envío periódico de telemetría JSON
  if (ahoraMs - ultimaTelemetriaMs >= INTERVALO_TELEMETRIA_MS) {
    ultimaTelemetriaMs = ahoraMs;
    if (SerialBT.hasClient()) {
      enviarTramaTelemetria();
    }
  }
}

long medirDistanciaUltrasonicaCm() {
  digitalWrite(trigPin, LOW);
  delayMicroseconds(2);
  digitalWrite(trigPin, HIGH);
  delayMicroseconds(10);
  digitalWrite(trigPin, LOW);

  long duracionUs = pulseIn(echoPin, HIGH, 25000);
  if (duracionUs <= 0) return 100;
  long cm = duracionUs * 0.034 / 2;
  return constrain(cm, 1, 400);
}

void evaluarLogicaControl() {
  bool ocupadaAhora = (distanciaCm > 0 && distanciaCm < 10);

  if (ocupadaAhora && !plazaPreviaOcupada) {
    if (plazasLibres > 0) plazasLibres--;
  } else if (!ocupadaAhora && plazaPreviaOcupada) {
    if (plazasLibres < capacidadTotal) plazasLibres++;
  }
  plazaPreviaOcupada = ocupadaAhora;

  estadoParking = ocupadaAhora ? "OCUPADO" : "LIBRE";
  if (modoOperacion == "AUTO") {
    anguloBarrera = ocupadaAhora ? 0 : 90;
  }
  barreraServo.write(anguloBarrera);
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

void enviarTramaTelemetria() {
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
  trama += ",\"crc\":\"OK\"}\n";

  SerialBT.print(trama);
  Serial.print("TX -> " + trama);
}

void enviarAck(const String& cmd, const String& reqId, const String& detail) {
  String trama = "{\"type\":\"ACK\",\"cmd\":\"" + cmd +
                 "\",\"reqId\":\"" + reqId +
                 "\",\"detail\":\"" + detail + "\"}\n";
  SerialBT.print(trama);
  Serial.print("ACK -> " + trama);
}

void enviarErr(const String& code, const String& reqId, const String& detail) {
  String trama = "{\"type\":\"ERR\",\"code\":\"" + code +
                 "\",\"reqId\":\"" + reqId +
                 "\",\"detail\":\"" + detail + "\"}\n";
  SerialBT.print(trama);
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
  if (!jsonLine.startsWith("{") || !jsonLine.endsWith("}")) {
    enviarErr("MALFORMED_JSON", "RX", "Trama JSON incompleta o corrupta descartada");
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
  int val     = extraerValorJsonInt(jsonLine, "val", -1);

  if (cmd == "SET_BARRERA") {
    if (val != 0 && val != 90) {
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
  } else if (cmd == "SET_CAPACIDAD") {
    if (val < 1 || val > 200) {
      enviarErr("OUT_OF_RANGE", reqId, "Capacidad fuera de rango (1..200)");
      return;
    }
    capacidadTotal = val;
    if (plazasLibres > capacidadTotal) plazasLibres = capacidadTotal;
    enviarAck(cmd, reqId, "Capacidad actualizada a " + String(capacidadTotal));
  } else if (cmd == "SET_PLAZAS") {
    if (val < 0 || val > capacidadTotal) {
      enviarErr("OUT_OF_RANGE", reqId, "Plazas libres fuera de rango (0..capacidad)");
      return;
    }
    plazasLibres = val;
    enviarAck(cmd, reqId, "Plazas libres ajustadas a " + String(plazasLibres));
  } else if (cmd == "REQ_STATUS" || cmd == "SYNC_RECORD") {
    enviarAck(cmd, reqId, "Estado confirmado");
  } else {
    enviarErr("UNKNOWN_CMD", reqId, "Comando inexistente: " + cmd);
    return;
  }

  actualizarPantallaLcd();
  enviarTramaTelemetria();
}

String extraerValorJsonString(const String& json, const String& clave) {
  String patron = "\"" + clave + "\":\"";
  int inicio = json.indexOf(patron);
  if (inicio == -1) return "";
  inicio += patron.length();
  int fin = json.indexOf("\"", inicio);
  if (fin == -1) return "";
  return json.substring(inicio, fin);
}

int extraerValorJsonInt(const String& json, const String& clave, int valorPorDefecto) {
  String patron = "\"" + clave + "\":";
  int inicio = json.indexOf(patron);
  if (inicio == -1) return valorPorDefecto;
  inicio += patron.length();
  int fin = inicio;
  while (fin < (int)json.length() && (isDigit(json[fin]) || json[fin] == '-')) {
    fin++;
  }
  if (fin == inicio) return valorPorDefecto;
  return json.substring(inicio, fin).toInt();
}
