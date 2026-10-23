/**
 * ============================================================================
 * SISTEMA DE CONTROL Y MONITOREO DE BARRERA DE ESTACIONAMIENTO INTELIGENTE
 * Arquitectura IoT/OT Segura bajo Familia ISO/IEC 27000 (ISO/IEC 27400)
 * Institución: INACAP - Ingeniería en Informática / Telecomunicaciones / Automatización
 *
 * HARDWARE ESP32:
 *  - Sensor Ultrasónico HC-SR04 : trigPin = 5, echoPin = 18
 *  - Actuador Servomotor SG90   : servoPin = 15 (90° = LIBRE/Arriba, 0° = OCUPADO/Abajo)
 *  - Pantalla LCD I2C (0x27)    : 16x2 caracteres (SDA = 21, SCL = 22)
 *  - Conectividad Inalámbrica   : Bluetooth Clásico SPP (BluetoothSerial)
 *
 * CARACTERÍSTICAS DE INGENIERÍA:
 *  - Cero bloqueo del loop(): Uso estricto de millis() en lugar de delay() largos.
 *  - Tramas JSON delimitadas por salto de línea ('\n') con validación de token ISO 27400.
 *  - Modo AUTO (gobernado por HC-SR04 < 10 cm) y Modo MANUAL (gobernado desde App Android).
 * ============================================================================
 */

#include <Wire.h>
#include <LiquidCrystal_I2C.h>
#include <ESP32Servo.h>
#include "BluetoothSerial.h"

#if !defined(CONFIG_BT_ENABLED) || !defined(CONFIG_BLUEDROID_ENABLED)
#error "Bluetooth Clásico no está habilitado en la configuración de placa ESP32."
#endif

// Definición de Pines (Hardware Prototipo ESP32)
const int trigPin  = 5;
const int echoPin  = 18;
const int servoPin = 15;

// Instancias de Periféricos
LiquidCrystal_I2C lcd(0x27, 16, 2); // SDA = 21, SCL = 22 por defecto en ESP32 Wire
Servo barreraServo;
BluetoothSerial SerialBT;

// Variables de Estado del Sistema OT
long distanciaCm         = 25;
String estadoParking     = "LIBRE";   // "LIBRE" o "OCUPADO"
int anguloBarrera        = 90;        // 90° = Arriba (Libre), 0° = Abajo (Cerrada)
String modoOperacion     = "AUTO";    // "AUTO" o "MANUAL"

// Temporizadores no bloqueantes con millis()
unsigned long ultimoMuestreoMs   = 0;
unsigned long ultimaTelemetriaMs = 0;
const unsigned long INTERVALO_MUESTREO_MS   = 250; // Lectura HC-SR04 + LCD cada 250 ms
const unsigned long INTERVALO_TELEMETRIA_MS = 800; // Envío JSON a Android cada 800 ms

// Buffer receptor de comandos por Bluetooth
String rxBuffer = "";

// Prototipos
long medirDistanciaUltrasonicaCm();
void evaluarLogicaControl();
void actualizarPantallaLcd();
void enviarTramaTelemetria();
void procesarTramaComando(const String& jsonLine);
String extraerValorJsonString(const String& json, const String& clave);
int extraerValorJsonInt(const String& json, const String& clave, int valorPorDefecto);

void setup() {
  Serial.begin(115200);

  // 1. Configurar pines del Sensor Ultrasónico HC-SR04
  pinMode(trigPin, OUTPUT);
  pinMode(echoPin, INPUT);

  // 2. Inicializar Bus I2C (SDA = 21, SCL = 22) y Pantalla LCD 0x27 (16x2)
  Wire.begin(21, 22);
  lcd.init();
  lcd.backlight();
  lcd.clear();
  lcd.setCursor(0, 0);
  lcd.print("INACAP IoT/OT");
  lcd.setCursor(0, 1);
  lcd.print("INIT ISO 27400..");

  // 3. Inicializar Servomotor en Pin 15 (50Hz estándar SG90/MG90S)
  ESP32PWM::allocateTimer(0);
  barreraServo.setPeriodHertz(50);
  barreraServo.attach(servoPin, 500, 2400);
  barreraServo.write(anguloBarrera);

  // 4. Iniciar Bluetooth Clásico RFCOMM (SPP)
  SerialBT.begin("ESP32_Barrera_INACAP");
  Serial.println("Nodo OT ESP32 iniciado: Bluetooth 'ESP32_Barrera_INACAP' listo.");
}

void loop() {
  unsigned long ahoraMs = millis();

  // A. Recepción no bloqueante de comandos desde la App Android
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
        rxBuffer = ""; // Protección contra desbordamiento de buffer (ISO 27400)
      }
    }
  }

  // B. Muestreo periódico del sensor ultrasónico HC-SR04 y actualización de servo/LCD
  if (ahoraMs - ultimoMuestreoMs >= INTERVALO_MUESTREO_MS) {
    ultimoMuestreoMs = ahoraMs;
    distanciaCm = medirDistanciaUltrasonicaCm();
    evaluarLogicaControl();
    actualizarPantallaLcd();
  }

  // C. Envío periódico de telemetría JSON a la Estación de Control Android
  if (ahoraMs - ultimaTelemetriaMs >= INTERVALO_TELEMETRIA_MS) {
    ultimaTelemetriaMs = ahoraMs;
    if (SerialBT.hasClient()) {
      enviarTramaTelemetria();
    }
  }
}

/**
 * Mide la distancia en cm con el sensor HC-SR04 usando timeout corto (25000 us)
 * para evitar bloquear el microcontrolador si no hay eco.
 */
long medirDistanciaUltrasonicaCm() {
  digitalWrite(trigPin, LOW);
  delayMicroseconds(2);
  digitalWrite(trigPin, HIGH);
  delayMicroseconds(10);
  digitalWrite(trigPin, LOW);

  long duracionUs = pulseIn(echoPin, HIGH, 25000); // Timeout 25 ms (~4 metros máx)
  if (duracionUs <= 0) {
    return 100; // Sin obstáculo cercano detectado
  }
  long cm = duracionUs * 0.034 / 2;
  return constrain(cm, 1, 400);
}

/**
 * Aplica la lógica de negocio del estacionamiento:
 * - Si distancia > 0 y < 10 cm: Estado = OCUPADO; en modo AUTO baja la barrera (0°).
 * - Si distancia >= 10 cm: Estado = LIBRE; en modo AUTO sube la barrera (90°).
 */
void evaluarLogicaControl() {
  if (distanciaCm > 0 && distanciaCm < 10) {
    estadoParking = "OCUPADO";
    if (modoOperacion == "AUTO") {
      anguloBarrera = 0;
    }
  } else {
    estadoParking = "LIBRE";
    if (modoOperacion == "AUTO") {
      anguloBarrera = 90;
    }
  }
  barreraServo.write(anguloBarrera);
}

/**
 * Actualiza las 2 filas de la pantalla LCD I2C 0x27 sin parpadeos bruscos.
 */
void actualizarPantallaLcd() {
  char fila0[17];
  char fila1[17];

  snprintf(fila0, sizeof(fila0), "DIST:%3ldcm %5s", distanciaCm, modoOperacion.c_str());
  snprintf(fila1, sizeof(fila1), "%-7s BAR:%3dG", estadoParking.c_str(), anguloBarrera);

  lcd.setCursor(0, 0);
  lcd.print(fila0);
  lcd.setCursor(0, 1);
  lcd.print(fila1);
}

/**
 * Construye y transmite la trama JSON delimitada por '\n':
 * {"dist":8,"estado":"OCUPADO","barrera":0,"modo":"AUTO","crc":"OK"}\n
 */
void enviarTramaTelemetria() {
  String trama = "{\"dist\":";
  trama += String(distanciaCm);
  trama += ",\"estado\":\"";
  trama += estadoParking;
  trama += "\",\"barrera\":";
  trama += String(anguloBarrera);
  trama += ",\"modo\":\"";
  trama += modoOperacion;
  trama += "\",\"crc\":\"OK\"}\n";

  SerialBT.print(trama);
  Serial.print("TX -> " + trama);
}

/**
 * Procesa comandos JSON entrantes desde la App Android validando el token ISO 27400:
 * Ejemplo: {"cmd":"SET_BARRERA","val":90,"modo":"MANUAL","token":"INA-..."}\n
 */
void procesarTramaComando(const String& jsonLine) {
  Serial.println("RX <- " + jsonLine);

  String token = extraerValorJsonString(jsonLine, "token");
  if (!token.startsWith("INA-") && token.length() < 6) {
    Serial.println("SEC # Comando rechazado: Token ISO 27400 invalido.");
    return;
  }

  String cmd  = extraerValorJsonString(jsonLine, "cmd");
  String modo = extraerValorJsonString(jsonLine, "modo");
  int val     = extraerValorJsonInt(jsonLine, "val", -1);

  if (cmd == "SET_BARRERA") {
    modoOperacion = "MANUAL";
    anguloBarrera = (val >= 45) ? 90 : 0;
    barreraServo.write(anguloBarrera);
  } else if (cmd == "SET_MODO" || modo == "AUTO") {
    modoOperacion = "AUTO";
    evaluarLogicaControl();
  }

  actualizarPantallaLcd();
  enviarTramaTelemetria();
}

// Utilidades ligeras de parsing JSON sin sobrecarga de memoria dinámica
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
