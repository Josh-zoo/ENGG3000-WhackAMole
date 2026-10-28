#include <Arduino.h>
#include <WiFi.h>
#include <WiFiUdp.h>
#include <esp_now.h>
#include <esp_wifi.h>
#include <esp_arduino_version.h>
#include <WebServer.h>

// ===================== BOX 1 (HUB) =====================
// Reads S1/S2, receives S3/S4 from box 2 over ESP-NOW,
// sends all four to the laptop over UDP.

WebServer server(80);

// Local Sensor Pins
const int TRIG_PIN_1 = 32;
const int ECHO_PIN_1 = 33;
const int TRIG_PIN_2 = 12;
const int ECHO_PIN_2 = 13;

const unsigned long ECHO_TIMEOUT = 15000; // 15 ms (~2.5 m, past the back of the field)

// Wi-Fi name, password and laptop IP live in secrets.h (not on GitHub).
// First time: copy secrets.example.h to secrets.h and fill it in.
#include "secrets.h"
const uint16_t PC_PORT = 4210;

WiFiUDP udp;
unsigned long sequenceNumber = 0;

// Remote Sensor Data (from box 2)
float remoteDist3 = -1.0;
float remoteDist4 = -1.0;
volatile unsigned long lastRemoteMs = 0;     // when box 2 was last heard
volatile unsigned long remoteCount = 0;      // messages from box 2 this second
const unsigned long REMOTE_STALE_MS = 500;   // older than this = report "No echo"

float localDist1 = -1.0;
float localDist2 = -1.0;

typedef struct struct_message {
    float distance3;
    float distance4;
} struct_message;
struct_message incomingReadings;

// The receive callback changed shape in ESP32 core 3.x, so pick the right one.
#if ESP_ARDUINO_VERSION_MAJOR >= 3
void OnDataRecv(const esp_now_recv_info_t *info, const uint8_t *incomingData, int len) {
#else
void OnDataRecv(const uint8_t *mac, const uint8_t *incomingData, int len) {
#endif
  if (len != sizeof(incomingReadings)) return;   // ignore anything that isn't box 2's message
  memcpy(&incomingReadings, incomingData, sizeof(incomingReadings));
  remoteDist3 = incomingReadings.distance3;
  remoteDist4 = incomingReadings.distance4;
  lastRemoteMs = millis();
  remoteCount++;
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

void formatReading(char *buffer, size_t size, float value) {
  if (value < 0) snprintf(buffer, size, "No echo");
  else snprintf(buffer, size, "%.1f cm", value);
}

void sendSensorValues(float distance1, float distance2, float distance3, float distance4) {
  char reading1[16], reading2[16], reading3[16], reading4[16];

  formatReading(reading1, sizeof(reading1), distance1);
  formatReading(reading2, sizeof(reading2), distance2);
  formatReading(reading3, sizeof(reading3), distance3);
  formatReading(reading4, sizeof(reading4), distance4);

  char payload[128];
  unsigned long espTime = millis();

  snprintf(payload, sizeof(payload),
           "Seq:%lu|Time:%lu|S1:%s|S2:%s|S3:%s|S4:%s",
           sequenceNumber++, espTime, reading1, reading2, reading3, reading4);

  udp.beginPacket(PC_IP, PC_PORT);
  udp.write((const uint8_t *)payload, strlen(payload));
  udp.endPacket();

  Serial.println(payload);
}

String sensorText(float value) {
  if (value < 0) return "No echo";
  return String(value, 1) + " cm";
}

void handleRoot() {
  bool remoteFresh = (millis() - lastRemoteMs) < REMOTE_STALE_MS;

  String page = "<html><head>";
  page += "<meta name='viewport' content='width=device-width, initial-scale=1'>";
  page += "<meta http-equiv='refresh' content='0.5'>";
  page += "</head><body>";

  page += "<h2>ESP32 Sensor Monitor</h2>";
  page += "<p>Sensor 1: " + sensorText(localDist1) + "</p>";
  page += "<p>Sensor 2: " + sensorText(localDist2) + "</p>";
  page += "<p>Sensor 3: " + sensorText(remoteFresh ? remoteDist3 : -1.0) + "</p>";
  page += "<p>Sensor 4: " + sensorText(remoteFresh ? remoteDist4 : -1.0) + "</p>";

  page += "</body></html>";

  server.send(200, "text/html", page);
}

void setup() {
  Serial.begin(115200);
  delay(1000);

  pinMode(TRIG_PIN_1, OUTPUT);
  pinMode(ECHO_PIN_1, INPUT);
  pinMode(TRIG_PIN_2, OUTPUT);
  pinMode(ECHO_PIN_2, INPUT);
  digitalWrite(TRIG_PIN_1, LOW);
  digitalWrite(TRIG_PIN_2, LOW);

  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

  Serial.print("Connecting to Wi-Fi");
  unsigned long start = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - start < 15000) {
    delay(500);
    Serial.print(".");
  }
  Serial.println();

  if (WiFi.status() == WL_CONNECTED) {
    Serial.println("Wi-Fi connected!");
    Serial.print("IP Address: ");
    Serial.println(WiFi.localIP());
    server.on("/", handleRoot);
    server.begin();
    Serial.println("HTTP server started");
  } else {
    Serial.println("Wi-Fi FAILED!");
  }

  // KEY FIX: by default the ESP32 naps its radio between hotspot beacons
  // (modem sleep). While napping it can't hear box 2, so most ESP-NOW
  // messages fail. Keep the radio awake.
  WiFi.setSleep(false);

  Serial.print("Hub channel = ");
  Serial.println(WiFi.channel());
  Serial.print("HUB MAC ADDRESS: ");
  Serial.println(WiFi.macAddress());

  udp.begin(PC_PORT);

  esp_err_t status = esp_now_init();
  Serial.print("ESP-NOW init = ");
  Serial.println(status);
  if (status != ESP_OK) {
    Serial.println("Error initializing ESP-NOW");
    return;
  }
  esp_now_register_recv_cb(OnDataRecv);
  Serial.println("Receive callback registered");
}

unsigned long lastReportMs = 0;

void loop() {
  localDist1 = measureDistanceCM(TRIG_PIN_1, ECHO_PIN_1);
  delay(40);
  localDist2 = measureDistanceCM(TRIG_PIN_2, ECHO_PIN_2);

  // Only pass on box 2's readings if they're fresh; otherwise report No echo
  // so the game never tracks a frozen old value.
  bool remoteFresh = (millis() - lastRemoteMs) < REMOTE_STALE_MS;
  sendSensorValues(localDist1, localDist2,
                   remoteFresh ? remoteDist3 : -1.0,
                   remoteFresh ? remoteDist4 : -1.0);
  server.handleClient();

  if (millis() - lastReportMs >= 1000) {
    lastReportMs = millis();
    Serial.print("Heard from box 2 in last second: ");
    Serial.print(remoteCount);
    Serial.print("   (hub channel ");
    Serial.print(WiFi.channel());
    Serial.println(")");
    remoteCount = 0;
  }

  delay(10); // loop pacing only; the 40 ms gap between pings is what stops crosstalk
}
