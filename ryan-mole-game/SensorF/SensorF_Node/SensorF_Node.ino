#include <Arduino.h>
#include <WiFi.h>
#include <esp_now.h>
#include <esp_wifi.h>

// Remote Sensor Pins
const int TRIG_PIN_3 = 32; 
const int ECHO_PIN_3 = 33; 
const int TRIG_PIN_4 = 12; 
const int ECHO_PIN_4 = 13; 

const unsigned long ECHO_TIMEOUT = 30000; // 30 ms

// REPLACE WITH MAC ADDRESS PRINTED BY THE HUB
uint8_t hubAddress[] = {
    0x00, 0x70, 0x07, 0x7D, 0x32, 0x08
};

typedef struct struct_message {
    float distance3;
    float distance4;
} struct_message;
struct_message myData;

esp_now_peer_info_t peerInfo;

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

  WiFi.disconnect();
  esp_wifi_set_channel(6, WIFI_SECOND_CHAN_NONE);

  Serial.print("Node channel = ");
  Serial.println(WiFi.channel());

  Serial.print("Node MAC: ");
  Serial.println(WiFi.macAddress());

  if (esp_now_init() != ESP_OK) {
    Serial.println("Error initializing ESP-NOW");
    return;
  }

  // Register the Hub as a peer
  memcpy(peerInfo.peer_addr, hubAddress, 6);
  peerInfo.channel = 0; 
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
  delay(60);
  myData.distance4 = measureDistanceCM(TRIG_PIN_4, ECHO_PIN_4);

  Serial.print("S3 = ");
  Serial.print(myData.distance3);
  Serial.print("   S4 = ");
  Serial.println(myData.distance4);
  
  esp_err_t result = esp_now_send(hubAddress, (uint8_t *) &myData, sizeof(myData));
   
  //if (result == ESP_OK) {
    //Serial.println("Sent S3/S4 successfully");
  //} else {
    //Serial.println("Error sending data");
  //}

  Serial.print("Send result = ");
  Serial.println(result);
  
  delay(100);
}
