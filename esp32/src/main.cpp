#include <Arduino.h>
#include <WebServer.h>
#include <WiFi.h>
#include <esp_camera.h>

#if __has_include("secrets.h")
#include "secrets.h"
#else
#define SMARTTRAFFIC_WIFI_SSID ""
#define SMARTTRAFFIC_WIFI_PASSWORD ""
#define SMARTTRAFFIC_AP_SSID "SmartTraffic-CAM"
#define SMARTTRAFFIC_AP_PASSWORD "smarttraffic"
#endif

namespace {

constexpr uint16_t kHttpPort = 80;
constexpr uint32_t kFrameIntervalMs = 66;  // ~15 FPS request ceiling.

WebServer server(kHttpPort);
volatile bool apMode = false;
uint32_t lastFrameMs = 0;
uint32_t framesServed = 0;
framesize_t currentFrameSize = FRAMESIZE_VGA;

#if defined(SMARTTRAFFIC_CAMERA_AI_THINKER)
// AI-Thinker ESP32-CAM / OV2640 pin map.
#define PWDN_GPIO_NUM 32
#define RESET_GPIO_NUM -1
#define XCLK_GPIO_NUM 0
#define SIOD_GPIO_NUM 26
#define SIOC_GPIO_NUM 27
#define Y9_GPIO_NUM 35
#define Y8_GPIO_NUM 34
#define Y7_GPIO_NUM 39
#define Y6_GPIO_NUM 36
#define Y5_GPIO_NUM 21
#define Y4_GPIO_NUM 19
#define Y3_GPIO_NUM 18
#define Y2_GPIO_NUM 5
#define VSYNC_GPIO_NUM 25
#define HREF_GPIO_NUM 23
#define PCLK_GPIO_NUM 22
#elif defined(SMARTTRAFFIC_CAMERA_S3_N16R8)
// Common ESP32-S3-CAM N16R8 / OV5640-style pin map.
// Verify the exact PCB revision before wiring hardware; third-party S3-CAM boards are not universal.
#define PWDN_GPIO_NUM -1
#define RESET_GPIO_NUM -1
#define XCLK_GPIO_NUM 15
#define SIOD_GPIO_NUM 4
#define SIOC_GPIO_NUM 5
#define Y9_GPIO_NUM 16
#define Y8_GPIO_NUM 17
#define Y7_GPIO_NUM 18
#define Y6_GPIO_NUM 12
#define Y5_GPIO_NUM 10
#define Y4_GPIO_NUM 8
#define Y3_GPIO_NUM 9
#define Y2_GPIO_NUM 11
#define VSYNC_GPIO_NUM 6
#define HREF_GPIO_NUM 7
#define PCLK_GPIO_NUM 13
#else
#error "Select SMARTTRAFFIC_CAMERA_AI_THINKER or SMARTTRAFFIC_CAMERA_S3_N16R8"
#endif

String jsonStatus() {
  const sensor_t* sensor = esp_camera_sensor_get();
  String json = "{";
  json += "\"service\":\"smart-traffic-camera\",";
  json += "\"stream\":\"/stream\",";
  json += "\"capture\":\"/capture\",";
  json += "\"control\":\"/control\",";
  json += "\"uptime_ms\":" + String(millis()) + ",";
  json += "\"free_heap\":" + String(ESP.getFreeHeap()) + ",";
  json += "\"free_psram\":" + String(ESP.getFreePsram()) + ",";
  json += "\"frames_served\":" + String(framesServed) + ",";
  json += "\"framesize\":" + String(static_cast<int>(currentFrameSize)) + ",";
  json += "\"width\":" + String(sensor ? sensor->status.width : 0) + ",";
  json += "\"height\":" + String(sensor ? sensor->status.height : 0) + ",";
  json += "\"ap_mode\":" + String(apMode ? "true" : "false");
  json += "}";
  return json;
}

bool initializeCamera() {
  camera_config_t config{};
  config.ledc_channel = LEDC_CHANNEL_0;
  config.ledc_timer = LEDC_TIMER_0;
  config.pin_d0 = Y2_GPIO_NUM;
  config.pin_d1 = Y3_GPIO_NUM;
  config.pin_d2 = Y4_GPIO_NUM;
  config.pin_d3 = Y5_GPIO_NUM;
  config.pin_d4 = Y6_GPIO_NUM;
  config.pin_d5 = Y7_GPIO_NUM;
  config.pin_d6 = Y8_GPIO_NUM;
  config.pin_d7 = Y9_GPIO_NUM;
  config.pin_xclk = XCLK_GPIO_NUM;
  config.pin_pclk = PCLK_GPIO_NUM;
  config.pin_vsync = VSYNC_GPIO_NUM;
  config.pin_href = HREF_GPIO_NUM;
  config.pin_sccb_sda = SIOD_GPIO_NUM;
  config.pin_sccb_scl = SIOC_GPIO_NUM;
  config.pin_pwdn = PWDN_GPIO_NUM;
  config.pin_reset = RESET_GPIO_NUM;
  config.xclk_freq_hz = 20000000;
  config.pixel_format = PIXFORMAT_JPEG;
  config.frame_size = currentFrameSize;
  config.jpeg_quality = 10;
  config.fb_count = psramFound() ? 2 : 1;
  config.grab_mode = CAMERA_GRAB_LATEST;

  const esp_err_t error = esp_camera_init(&config);
  if (error != ESP_OK) {
    Serial.printf("Camera init failed: 0x%08x\n", error);
    return false;
  }

  sensor_t* sensor = esp_camera_sensor_get();
  if (sensor == nullptr) {
    Serial.println("Camera sensor handle unavailable");
    esp_camera_deinit();
    return false;
  }
  sensor->set_framesize(sensor, FRAMESIZE_VGA);
  sensor->set_quality(sensor, 10);
  currentFrameSize = FRAMESIZE_VGA;
  return true;
}

bool writeFully(WiFiClient& client, const uint8_t* data, size_t length) {
  size_t offset = 0;
  while (offset < length && client.connected()) {
    const size_t written = client.write(data + offset, length - offset);
    if (written == 0) {
      delay(1);
      continue;
    }
    offset += written;
  }
  return offset == length;
}

void sendJpegFrame() {
  camera_fb_t* frame = esp_camera_fb_get();
  if (frame == nullptr || frame->format != PIXFORMAT_JPEG) {
    if (frame != nullptr) esp_camera_fb_return(frame);
    server.send(503, "text/plain", "camera frame unavailable");
    return;
  }

  const size_t length = frame->len;
  server.sendHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
  server.sendHeader("Pragma", "no-cache");
  server.send_P(200, "image/jpeg", reinterpret_cast<const char*>(frame->buf), length);
  esp_camera_fb_return(frame);
  framesServed++;
}

void handleCapture() { sendJpegFrame(); }

void handleStatus() {
  server.send(200, "application/json; charset=utf-8", jsonStatus());
}

void handleControl() {
  if (!server.hasArg("action")) {
    server.send(400, "application/json", "{\"error\":\"missing action\"}");
    return;
  }
  const String action = server.arg("action");
  if (action == "flash") {
#if defined(SMARTTRAFFIC_CAMERA_AI_THINKER)
    constexpr int flashPin = 4;
    const bool on = server.hasArg("on") && server.arg("on") == "1";
    pinMode(flashPin, OUTPUT);
    digitalWrite(flashPin, on ? HIGH : LOW);
    server.send(200, "application/json", String("{\"ok\":true,\"flash\":") + (on ? "true}" : "false}"));
#else
    server.send(501, "application/json", "{\"ok\":false,\"error\":\"flash pin is not defined for this board\"}");
#endif
    return;
  }
  if (action == "quality") {
    if (!server.hasArg("value")) {
      server.send(400, "application/json", "{\"error\":\"missing value\"}");
      return;
    }
    const int quality = server.arg("value").toInt();
    if (quality < 5 || quality > 63) {
      server.send(400, "application/json", "{\"error\":\"quality must be 5..63\"}");
      return;
    }
    sensor_t* sensor = esp_camera_sensor_get();
    if (sensor == nullptr) {
      server.send(503, "application/json", "{\"error\":\"camera sensor unavailable\"}");
      return;
    }
    sensor->set_quality(sensor, quality);
    server.send(200, "application/json", "{\"ok\":true}");
    return;
  }
  server.send(400, "application/json", "{\"error\":\"unsupported action\"}");
}

void handleStream() {
  WiFiClient client = server.client();
  client.print("HTTP/1.1 200 OK\r\n");
  client.print("Content-Type: multipart/x-mixed-replace; boundary=frame\r\n");
  client.print("Cache-Control: no-cache, no-store, must-revalidate\r\n");
  client.print("Pragma: no-cache\r\n");
  client.print("Connection: close\r\n\r\n");

  while (client.connected()) {
    const uint32_t now = millis();
    if (now - lastFrameMs < kFrameIntervalMs) {
      delay(2);
      continue;
    }
    lastFrameMs = now;

    camera_fb_t* frame = esp_camera_fb_get();
    if (frame == nullptr || frame->format != PIXFORMAT_JPEG) {
      if (frame != nullptr) esp_camera_fb_return(frame);
      break;
    }

    const size_t length = frame->len;
    client.printf("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: %u\r\n\r\n", static_cast<unsigned>(length));
    const bool bodyWritten = writeFully(client, frame->buf, length);
    if (bodyWritten) client.print("\r\n");
    esp_camera_fb_return(frame);
    framesServed++;
    if (!bodyWritten) break;
  }
}

void connectNetwork() {
  if (strlen(SMARTTRAFFIC_WIFI_SSID) > 0) {
    WiFi.mode(WIFI_STA);
    WiFi.begin(SMARTTRAFFIC_WIFI_SSID, SMARTTRAFFIC_WIFI_PASSWORD);
    const uint32_t deadline = millis() + 12000;
    while (WiFi.status() != WL_CONNECTED && millis() < deadline) {
      delay(250);
      Serial.print('.');
    }
    Serial.println();

    if (WiFi.status() == WL_CONNECTED) {
      apMode = false;
      Serial.print("STA IP: ");
      Serial.println(WiFi.localIP());
      return;
    }
  }

  WiFi.disconnect(true, true);
  delay(100);
  WiFi.mode(WIFI_AP);
  const bool started = WiFi.softAP(SMARTTRAFFIC_AP_SSID, SMARTTRAFFIC_AP_PASSWORD);
  apMode = started;
  Serial.print("AP mode: ");
  Serial.println(started ? WiFi.softAPIP() : IPAddress(0, 0, 0, 0));
}

}  // namespace

void setup() {
  Serial.begin(115200);
  delay(200);

  if (!initializeCamera()) {
    delay(1000);
    ESP.restart();
  }

  connectNetwork();

  server.on("/", HTTP_GET, []() {
    server.send(200, "text/plain; charset=utf-8", "Smart Traffic Camera\n/stream\n/capture\n/status\n/control?action=flash&on=1\n/control?action=quality&value=10\n");
  });
  server.on("/status", HTTP_GET, handleStatus);
  server.on("/capture", HTTP_GET, handleCapture);
  server.on("/control", HTTP_GET, handleControl);
  server.on("/stream", HTTP_GET, handleStream);
  server.begin();
  Serial.println("HTTP server started");
}

void loop() {
  server.handleClient();
  delay(1);
}
