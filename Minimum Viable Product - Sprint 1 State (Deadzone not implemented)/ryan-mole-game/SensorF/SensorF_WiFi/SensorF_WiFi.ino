#include <Arduino.h>
#include <WiFi.h>
#include <WiFiUdp.h>

// Sensor Pins
const int TRIG_PIN_1 = 22;
const int ECHO_PIN_1 = 23;
const int TRIG_PIN_2 = 12;
const int ECHO_PIN_2 = 13;

const unsigned long ECHO_TIMEOUT = 30000; // 30 ms

// Wifi Settings
const char *WIFI_SSID = "Galaxy S25 Ultra C297";
const char *WIFI_PASSWORD = "testing1234";

// IP Address (of Laptop connected to Hotspot)
const char *PC_IP = "10.140.219.33";
const uint16_t PC_PORT = 4210;

WiFiUDP udp;

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

// Send both sensor readings in one packet so the game can tell
// WHICH sensor is picking up the player, not just how far away they are.
void sendSensorValues(float distance1, float distance2) {
  char reading1[16];
  char reading2[16];
  formatReading(reading1, sizeof(reading1), distance1);
  formatReading(reading2, sizeof(reading2), distance2);

  char payload[64];
  snprintf(payload, sizeof(payload), "Sensor 1: %s Sensor 2: %s", reading1, reading2);

  udp.beginPacket(PC_IP, PC_PORT);
  udp.write((const uint8_t *)payload, strlen(payload));
  udp.endPacket();

  Serial.println(payload);
}

void setup() {
  Serial.begin(115200);

  pinMode(TRIG_PIN_1, OUTPUT);
  pinMode(ECHO_PIN_1, INPUT);
  pinMode(TRIG_PIN_2, OUTPUT);
  pinMode(ECHO_PIN_2, INPUT);

  digitalWrite(TRIG_PIN_1, LOW);
  digitalWrite(TRIG_PIN_2, LOW);

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

  // Send BOTH readings so the game can triangulate the player position.
  sendSensorValues(distance1, distance2);

  // Short cycle keeps the cursor responsive; at tabletop ranges the echoes
  // return in a few ms, and the 60 ms stagger above still prevents crosstalk.
  delay(100);
}
