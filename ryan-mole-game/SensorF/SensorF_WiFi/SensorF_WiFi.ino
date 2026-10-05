#include <Arduino.h>
#include <WiFi.h>
#include <WiFiUdp.h>
#include <esp_now.h>

#include <WebServer.h>

WebServer server(80);

// Local Sensor Pins
const int TRIG_PIN_1 = 32;
const int ECHO_PIN_1 = 33;
const int TRIG_PIN_2 = 12;
const int ECHO_PIN_2 = 13;

const unsigned long ECHO_TIMEOUT = 30000; // 30 ms

// Wifi Settings
const char *WIFI_SSID = "iPhone (2)";
const char *WIFI_PASSWORD = "theultrasaim";
const char *PC_IP = "172.20.10.11";
const uint16_t PC_PORT = 4210;

WiFiUDP udp;
unsigned long sequenceNumber = 0;

// Remote Sensor Data
float remoteDist3 = -1.0;
float remoteDist4 = -1.0;

float localDist1 = -1.0;
float localDist2 = -1.0;

typedef struct struct_message {
    float distance3;
    float distance4;
} struct_message;
struct_message incomingReadings;

// ESP-NOW Receive Callback
//void OnDataRecv(const uint8_t * mac, const uint8_t *incomingData, int len) {
  //memcpy(&incomingReadings, incomingData, sizeof(incomingReadings));
  //remoteDist3 = incomingReadings.distance3;
  //remoteDist4 = incomingReadings.distance4;
//}

void OnDataRecv(const uint8_t *mac, const uint8_t *incomingData, int len) {

    memcpy(&incomingReadings, incomingData, sizeof(incomingReadings));

    remoteDist3 = incomingReadings.distance3;
    remoteDist4 = incomingReadings.distance4;
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
  if (value < 0)
    return "No echo";
  return String(value, 1) + " cm";
}

void handleRoot() {
  String page = "<html><head>";
  page += "<meta http-equiv='refresh' content='0.5'>";
  page += "</head><body>";

  page += "<h2>ESP32 Sensor Monitor</h2>";

  page += "<p>Sensor 1: " + sensorText(localDist1) + "</p>";
  page += "<p>Sensor 2: " + sensorText(localDist2) + "</p>";
  page += "<p>Sensor 3: " + sensorText(remoteDist3) + "</p>";
  page += "<p>Sensor 4: " + sensorText(remoteDist4) + "</p>";

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

  // Must be AP_STA for simultaneous Wi-Fi UDP and ESP-NOW
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
  
  // PRINT MAC ADDRESS FOR THE NODE
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

void loop() {
  localDist1 = measureDistanceCM(TRIG_PIN_1, ECHO_PIN_1);
  delay(60);
  localDist2 = measureDistanceCM(TRIG_PIN_2, ECHO_PIN_2);

  // Send local + latest remote data
  sendSensorValues(localDist1, localDist2, remoteDist3, remoteDist4);
  server.handleClient();
  
  delay(100);
}

//void loop() {

   // float d1 = measureDistanceCM(TRIG_PIN_1, ECHO_PIN_1);
    //float d2 = measureDistanceCM(TRIG_PIN_2, ECHO_PIN_2);

    //Serial.print("S1 = ");
    //Serial.print(d1);

    //Serial.print("   S2 = ");
    //Serial.println(d2);

    //delay(500);
//}