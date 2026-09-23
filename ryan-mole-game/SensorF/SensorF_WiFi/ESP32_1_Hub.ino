#include <Arduino.h>
#include <WiFi.h>
#include <WiFiUdp.h>
#include <esp_now.h>

// Local Sensor Pins (S1 & S2)
const int TRIG_PIN_1 = 32;
const int ECHO_PIN_1 = 33;
const int TRIG_PIN_2 = 12;
const int ECHO_PIN_2 = 13;

const unsigned long ECHO_TIMEOUT = 30000; // 30 ms

// Wifi Settings
const char *WIFI_SSID = "iPhone (2)";
const char *WIFI_PASSWORD = "theultrasaim";

// IP Address (of Laptop connected to Hotspot)
const char *PC_IP = "172.20.10.11";
const uint16_t PC_PORT = 4210;

WiFiUDP udp;

// Telemetry Logging Variable
unsigned long sequenceNumber = 0;

// Variables to store incoming ESP-NOW data from ESP32 #2
float remoteDist3 = -1.0;
float remoteDist4 = -1.0;

// Structure to receive data
typedef struct struct_message {
    float distance3;
    float distance4;
} struct_message;
struct_message incomingReadings;

// Callback when ESP-NOW data is received
void OnDataRecv(const uint8_t * mac, const uint8_t *incomingData, int len) {
  memcpy(&incomingReadings, incomingData, sizeof(incomingReadings));
  remoteDist3 = incomingReadings.distance3;
  remoteDist4 = incomingReadings.distance4;
}

// Distance Measurement Helper
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

// Formatting Helper
void formatReading(char *buffer, size_t size, float value) {
  if (value < 0) snprintf(buffer, size, "No echo");
  else snprintf(buffer, size, "%.1f cm", value);
}

// UDP Transmission
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

void setup() {
  Serial.begin(115200);
  delay(1000);
  
  pinMode(TRIG_PIN_1, OUTPUT);
  pinMode(ECHO_PIN_1, INPUT);
  pinMode(TRIG_PIN_2, OUTPUT);
  pinMode(ECHO_PIN_2, INPUT);
  digitalWrite(TRIG_PIN_1, LOW);
  digitalWrite(TRIG_PIN_2, LOW);

  // WIFI_AP_STA is required to use standard Wi-Fi and ESP-NOW concurrently
  WiFi.mode(WIFI_AP_STA); 
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

  Serial.print("Connecting to Wi-Fi");
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }
  Serial.println("\nWi-Fi connected.");
  
  // Print this board's MAC Address so you can paste it into ESP32 #2
  Serial.print("HUB MAC ADDRESS: ");
  Serial.println(WiFi.macAddress());

  udp.begin(PC_PORT);

  // Initialize ESP-NOW
  if (esp_now_init() != ESP_OK) {
    Serial.println("Error initializing ESP-NOW");
    return;
  }
  esp_now_register_recv_cb(esp_now_recv_cb_t(OnDataRecv));
}

void loop() {
  // Read local sensors with 60ms stagger
  float localDist1 = measureDistanceCM(TRIG_PIN_1, ECHO_PIN_1);
  delay(60);
  float localDist2 = measureDistanceCM(TRIG_PIN_2, ECHO_PIN_2);

  // Transmit local data + latest remote data
  sendSensorValues(localDist1, localDist2, remoteDist3, remoteDist4);
  
  delay(100);
}