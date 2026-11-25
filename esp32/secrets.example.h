/**
 * Plantilla de credenciales y secretos locales para ESP32 (ES3 — TI3042).
 *
 * INSTRUCCIONES:
 *   1. Copiar este archivo como `esp32/secrets.h` (ignorado por .gitignore).
 *   2. Reemplazar los valores de ejemplo por sus credenciales reales.
 *   3. NUNCA subir `secrets.h` al repositorio Git.
 */

#ifndef SECRETS_H
#define SECRETS_H

// Token compartido con la app Android (ISO/IEC 27400 - mínimo 12 caracteres)
// Debe coincidir con el "Token OT compartido" configurado en la app Android.
#define OT_SHARED_TOKEN "INA-EXAMPLE00000"

// Identidad Bluetooth Clásico SPP (RFCOMM) y PIN de emparejamiento
#define BT_DEVICE_NAME  "ESP32_Barrera_INACAP"
#define BT_PIN_CODE     "4829"

// Transporte Wi-Fi TCP local opcional: 0 = solo Bluetooth SPP, 1 = Bluetooth + Wi-Fi TCP
#define ENABLE_WIFI_TCP 0
#define WIFI_SSID       "MI_RED_WIFI_EJEMPLO"
#define WIFI_PASS       "CAMBIAR_CLAVE_WIFI_1234"
#define TCP_SERVER_PORT 8888

#endif // SECRETS_H
