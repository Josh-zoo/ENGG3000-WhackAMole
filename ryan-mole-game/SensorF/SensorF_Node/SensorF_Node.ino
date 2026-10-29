#include <Arduino.h>
#include <WiFi.h>
#include <esp_now.h>
#include <esp_wifi.h>
#include <esp_arduino_version.h>

// ===================== BOX 2 (NODE) =====================
// Reads S3/S4 and sends them to box 1 over ESP-NOW.
//
// Channel fix: instead of guessing or scanning for the hotspot's channel,
// box 2 now JOINS the same hotspot. That forces it onto exactly the same
// channel as box 1, every time, even if the phone picks a new channel.

const int TRIG_PIN_3 = 32;
const int ECHO_PIN_3 = 33;
const int TRIG_PIN_4 = 12;   // GPIO 12 is a boot-strapping pin. If box 2 ever refuses
                             // to start after a power cycle, move this wire to 18.
const int ECHO_PIN_4 = 13;

const unsigned long ECHO_TIMEOUT = 20000; // 15 ms (~2.5 m, past the back of the field)

// Wi-Fi name/password (must match box 1) and box 1's MAC address live in
// secrets.h (not on GitHub). First time: copy secrets.example.h to secrets.h.
#include "secrets.h"

typedef struct struct_message {
    float distance3;
    float distance4;
} struct_message;
struct_message myData;

esp_now_peer_info_t peerInfo = {};

volatile unsigned long sentOk = 0;
volatile unsigned long sentFail = 0;
unsigned long lastReportMs = 0;

#if ESP_ARDUINO_VERSION >= ESP_ARDUINO_VERSION_VAL(3, 3, 0)
void OnDataSent(const wifi_tx_info_t *info, esp_now_send_status_t status) {
#else
void OnDataSent(const uint8_t *mac, esp_now_send_status_t status) {
#endif
  if (status == ESP_NOW_SEND_SUCCESS) sentOk++;
  else sentFail++;
}

float measureDistanceCM(int trigPin, int echoPin) {
  digitalWrite(trigPin, LOW);
  delayMicroseconds(2);
  digitalWrite(trigPin, HIGH);
  delayMicroseconds(10);
  digitalWrite(trigPin, LOW);

  unsigned long duration = pulseIn(echoPin, HIGH, ECHO_TIMEOUT);
  if (duration == 0) return -1.0;
  return duration * 0.0343 / 2.0;
}

void setup() {
  Serial.begin(115200);
  delay(1000);

  pinMode(TRIG_PIN_3, OUTPUT);
  pinMode(ECHO_PIN_3, INPUT);
  pinMode(TRIG_PIN_4, OUTPUT);
  pinMode(ECHO_PIN_4, INPUT);
  digitalWrite(TRIG_PIN_3, LOW);
  digitalWrite(TRIG_PIN_4, LOW);

  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  Serial.print("Joining hotspot to match box 1's channel");
  unsigned long start = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - start < 15000) {
    delay(500);
    Serial.print(".");
  }
  Serial.println();
  if (WiFi.status() == WL_CONNECTED) {
    Serial.println("Joined hotspot.");
  } else {
    // Backup plan: couldn't join, so look for the hotspot and copy its channel.
    Serial.println("WARNING: could not join hotspot (check name/password). Scanning for its channel instead...");
    WiFi.disconnect();
    int found = WiFi.scanNetworks();
    int ch = -1;
    for (int i = 0; i < found; i++) {
      if (WiFi.SSID(i) == WIFI_SSID) { ch = WiFi.channel(i); break; }
    }
    WiFi.scanDelete();
    if (ch > 0) {
      esp_wifi_set_channel(ch, WIFI_SECOND_CHAN_NONE);
      Serial.print("Found hotspot on channel ");
      Serial.println(ch);
    } else {
      Serial.println("Hotspot not visible. Open Personal Hotspot on the phone and press EN on this board.");
    }
  }
  WiFi.setSleep(false);   // keep the radio awake too

  Serial.print("Node channel = ");
  Serial.println(WiFi.channel());
  Serial.print("Node MAC: ");
  Serial.println(WiFi.macAddress());

  if (esp_now_init() != ESP_OK) {
    Serial.println("Error initializing ESP-NOW");
    return;
  }
  esp_now_register_send_cb(OnDataSent);

  memcpy(peerInfo.peer_addr, hubAddress, 6);
  peerInfo.channel = 0;          // 0 = use the channel we're on (the hotspot's)
  peerInfo.ifidx = WIFI_IF_STA;
  peerInfo.encrypt = false;

  esp_err_t addStatus = esp_now_add_peer(&peerInfo);
  Serial.print("Peer add result = ");
  Serial.println(addStatus);
  if (addStatus != ESP_OK) {
    Serial.println("Failed to add peer");
    return;
  }
}

void loop() {
  myData.distance3 = measureDistanceCM(TRIG_PIN_3, ECHO_PIN_3);
  delay(40);
  myData.distance4 = measureDistanceCM(TRIG_PIN_4, ECHO_PIN_4);

  esp_now_send(hubAddress, (uint8_t *) &myData, sizeof(myData));

  Serial.print("S3 = ");
  Serial.print(myData.distance3);
  Serial.print("   S4 = ");
  Serial.println(myData.distance4);

  if (millis() - lastReportMs >= 1000) {
    lastReportMs = millis();
    unsigned long ok = sentOk, fail = sentFail;
    sentOk = 0;
    sentFail = 0;
    Serial.print("Delivered to hub in last second: ");
    Serial.print(ok);
    Serial.print(" ok, ");
    Serial.print(fail);
    Serial.print(" failed   (node channel ");
    Serial.print(WiFi.channel());
    Serial.println(")");
  }

  delay(10); // loop pacing only; the 40 ms gap between pings is what stops crosstalk
}
