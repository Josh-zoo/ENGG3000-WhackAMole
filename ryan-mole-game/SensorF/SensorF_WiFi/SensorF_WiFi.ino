#include <Arduino.h>
#include <WiFi.h>
#include <WiFiUdp.h>

// Sensor Pins
const int TRIG_PIN_1 = 32;
const int ECHO_PIN_1 = 33;
const int TRIG_PIN_2 = 12;
const int ECHO_PIN_2 = 13;
// Assign appropriate ESP32 GPIO pins for Sensor 3 and 4
const int TRIG_PIN_3 = 25; 
const int ECHO_PIN_3 = 26; 
const int TRIG_PIN_4 = 27; 
const int ECHO_PIN_4 = 14; 

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

// Distance Measurement
float measureDistanceCM(int trigPin, int echoPin) {
  digitalWrite(trigPin, LOW);
  delayMicroseconds(2);

  digitalWrite(trigPin, HIGH);
  delayMicroseconds(10);
  digitalWrite(trigPin, LOW);

  unsigned long duration = pulseIn(echoPin, HIGH, ECHO_TIMEOUT);
  if (duration == 0) {
    return -1.0;
  }

  return duration * 0.0343 / 2.0;
}

// Format one sensor reading for the payload (-1 means no echo).
void formatReading(char *buffer, size_t size, float value) {
  if (value < 0) {
    snprintf(buffer, size, "No echo");
  } else {
    snprintf(buffer, size, "%.1f cm", value);
  }
}

// Send all four sensor readings in one packet alongside telemetry
void sendSensorValues(float distance1, float distance2, float distance3, float distance4) {
  char reading1[16];
  char reading2[16];
  char reading3[16];
  char reading4[16];

  formatReading(reading1, sizeof(reading1), distance1);
  formatReading(reading2, sizeof(reading2), distance2);
  formatReading(reading3, sizeof(reading3), distance3);
  formatReading(reading4, sizeof(reading4), distance4);

  // Increased buffer size to 128 to accommodate 4 sensors safely
  char payload[128];
  unsigned long espTime = millis();
  
  // Format includes Sequence Number and ESP32 Timestamp for packet loss/latency tracking
  snprintf(payload, sizeof(payload),
           "Seq:%lu|Time:%lu|S1:%s|S2:%s|S3:%s|S4:%s",
           sequenceNumber++, espTime, reading1, reading2, reading3, reading4);

  udp.beginPacket(PC_IP, PC_PORT);
  udp.write((const uint8_t *)payload, strlen(payload));

  int result = udp.endPacket();

  Serial.print("UDP result = ");
  Serial.println(result);

  Serial.println(payload);
}

void setup() {
  Serial.begin(115200);
  delay(2000);

  Serial.println("=====New Code ======");

  pinMode(TRIG_PIN_1, OUTPUT);
  pinMode(ECHO_PIN_1, INPUT);
  pinMode(TRIG_PIN_2, OUTPUT);
  pinMode(ECHO_PIN_2, INPUT);
  pinMode(TRIG_PIN_3, OUTPUT);
  pinMode(ECHO_PIN_3, INPUT);
  pinMode(TRIG_PIN_4, OUTPUT);
  pinMode(ECHO_PIN_4, INPUT);

  digitalWrite(TRIG_PIN_1, LOW);
  digitalWrite(TRIG_PIN_2, LOW);
  digitalWrite(TRIG_PIN_3, LOW);
  digitalWrite(TRIG_PIN_4, LOW);

  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

  Serial.print("Connecting to Wi-Fi");
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }
  Serial.println();
  Serial.print("Wi-Fi connected. ESP32 IP: ");
  Serial.println(WiFi.localIP());
  Serial.print("Sending UDP packets to ");
  Serial.print(PC_IP);
  Serial.print(":");
  Serial.println(PC_PORT);

  udp.begin(PC_PORT);
}

void loop() {
  float distance1 = measureDistanceCM(TRIG_PIN_1, ECHO_PIN_1);
  delay(60);
  float distance2 = measureDistanceCM(TRIG_PIN_2, ECHO_PIN_2);
  delay(60);
  float distance3 = measureDistanceCM(TRIG_PIN_3, ECHO_PIN_3);
  delay(60);
  float distance4 = measureDistanceCM(TRIG_PIN_4, ECHO_PIN_4);

  // Send ALL readings
  sendSensorValues(distance1, distance2, distance3, distance4);

  // Short cycle keeps the cursor responsive; at tabletop ranges the echoes
  // return in a few ms, and the 60 ms stagger above still prevents crosstalk.
  delay(100);
}