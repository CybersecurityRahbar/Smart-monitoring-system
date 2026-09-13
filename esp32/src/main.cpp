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
WebServer server(kHttpPort);
volatile bool apMode = false;
uint32_t lastFrameMs = 0;
uint32_t framesServed = 0;
framesize_t currentFrameSize = FRAMESIZE_HD;
int currentJpegQuality = 10;

#if defined(SMARTTRAFFIC_CAMERA_AI_THINKER)
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

const char* frameSizeName(framesize_t value) {
  switch (value) {
    case FRAMESIZE_VGA: return "VGA";
    case FRAMESIZE_SVGA: return "SVGA";
    case FRAMESIZE_XGA: return "XGA";
    case FRAMESIZE_HD: return "HD";
    case FRAMESIZE_FHD: return "FHD";
    case FRAMESIZE_QHD: return "QHD";
    case FRAMESIZE_SXGA: return "SXGA";
    case FRAMESIZE_UXGA: return "UXGA";
    default: return "CUSTOM";
  }
}

bool parseFrameSize(const String& value, framesize_t* result) {
  if (!result) return false;
  String v = value;
  v.toLowerCase();
  if (v == "vga") { *result = FRAMESIZE_VGA; return true; }
  if (v == "svga") { *result = FRAMESIZE_SVGA; return true; }
  if (v == "xga") { *result = FRAMESIZE_XGA; return true; }
  if (v == "hd") { *result = FRAMESIZE_HD; return true; }
  if (v == "fhd") { *result = FRAMESIZE_FHD; return true; }
  if (v == "qhd") { *result = FRAMESIZE_QHD; return true; }
  if (v == "sxga") { *result = FRAMESIZE_SXGA; return true; }
  if (v == "uxga") { *result = FRAMESIZE_UXGA; return true; }
  return false;
}

void frameDimensions(framesize_t value, uint16_t* width, uint16_t* height) {
  if (!width || !height) return;
  switch (value) {
    case FRAMESIZE_VGA: *width = 640; *height = 480; return;
    case FRAMESIZE_SVGA: *width = 800; *height = 600; return;
    case FRAMESIZE_XGA: *width = 1024; *height = 768; return;
    case FRAMESIZE_HD: *width = 1280; *height = 720; return;
    case FRAMESIZE_FHD: *width = 1920; *height = 1080; return;
    case FRAMESIZE_QHD: *width = 2560; *height = 1440; return;
    case FRAMESIZE_SXGA: *width = 1280; *height = 1024; return;
    case FRAMESIZE_UXGA: *width = 1600; *height = 1200; return;
    default: *width = 0; *height = 0; return;
  }
}

uint32_t streamIntervalMs() {
  switch (currentFrameSize) {
    case FRAMESIZE_QHD: return 140;
    case FRAMESIZE_FHD: return 95;
    case FRAMESIZE_UXGA: return 120;
    default: return 66;
  }
}

String jsonStatus() {
  uint16_t width = 0, height = 0;
  frameDimensions(currentFrameSize, &width, &height);
  String json = "{";
  json += "\"service\":\"smart-traffic-camera\",";
  json += "\"stream\":\"/stream\",\"capture\":\"/capture\",\"control\":\"/control\",";
  json += "\"uptime_ms\":" + String(millis()) + ",";
  json += "\"free_heap\":" + String(ESP.getFreeHeap()) + ",";
  json += "\"free_psram\":" + String(ESP.getFreePsram()) + ",";
  json += "\"frames_served\":" + String(framesServed) + ",";
  json += "\"framesize\":\"" + String(frameSizeName(currentFrameSize)) + "\",";
  json += "\"width\":" + String(width) + ",\"height\":" + String(height) + ",";
  json += "\"jpeg_quality\":" + String(currentJpegQuality) + ",";
#if defined(SMARTTRAFFIC_CAMERA_AI_THINKER)
  json += "\"flash_supported\":true,";
#else
  json += "\"flash_supported\":false,";
#endif
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
  config.jpeg_quality = currentJpegQuality;
  config.fb_count = psramFound() ? 2 : 1;
  config.grab_mode = CAMERA_GRAB_LATEST;
  if (psramFound()) config.fb_location = CAMERA_FB_IN_PSRAM;
  const esp_err_t error = esp_camera_init(&config);
  if (error != ESP_OK) {
    Serial.printf("Camera init failed: 0x%08x\n", error);
    return false;
  }
  sensor_t* sensor = esp_camera_sensor_get();
  if (!sensor) {
    Serial.println("Camera sensor handle unavailable");
    esp_camera_deinit();
    return false;
  }
  if (sensor->set_framesize(sensor, currentFrameSize) != 0 || sensor->set_quality(sensor, currentJpegQuality) != 0) {
    Serial.println("Camera rejected initial settings");
    esp_camera_deinit();
    return false;
  }
  return true;
}

bool writeFully(WiFiClient& client, const uint8_t* data, size_t length) {
  size_t offset = 0;
  while (offset < length && client.connected()) {
    const size_t written = client.write(data + offset, length - offset);
    if (written == 0) { delay(1); continue; }
    offset += written;
  }
  return offset == length;
}

void sendJpegFrame() {
  camera_fb_t* frame = esp_camera_fb_get();
  if (!frame || frame->format != PIXFORMAT_JPEG) {
    if (frame) esp_camera_fb_return(frame);
    server.send(503, "text/plain", "camera frame unavailable");
    return;
  }
  server.sendHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
  server.sendHeader("Pragma", "no-cache");
  server.send_P(200, "image/jpeg", reinterpret_cast<const char*>(frame->buf), frame->len);
  esp_camera_fb_return(frame);
  framesServed++;
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
    server.send(501, "application/json", "{\"ok\":false,\"flash_supported\":false,\"error\":\"This ESP32-S3 carrier has no direct camera flash GPIO\"}");
#endif
    return;
  }
  if (action == "quality") {
    if (!server.hasArg("value")) { server.send(400, "application/json", "{\"error\":\"missing value\"}"); return; }
    const int quality = server.arg("value").toInt();
    if (quality < 5 || quality > 63) { server.send(400, "application/json", "{\"error\":\"quality must be 5..63\"}"); return; }
    sensor_t* sensor = esp_camera_sensor_get();
    if (!sensor) { server.send(503, "application/json", "{\"error\":\"camera sensor unavailable\"}"); return; }
    if (sensor->set_quality(sensor, quality) != 0) { server.send(500, "application/json", "{\"error\":\"camera rejected quality\"}"); return; }
    currentJpegQuality = quality;
    server.send(200, "application/json", String("{\"ok\":true,\"jpeg_quality\":") + String(currentJpegQuality) + "}");
    return;
  }
  if (action == "framesize") {
    if (!server.hasArg("value")) { server.send(400, "application/json", "{\"error\":\"missing value\"}"); return; }
    framesize_t nextSize = currentFrameSize;
    if (!parseFrameSize(server.arg("value"), &nextSize)) {
      server.send(400, "application/json", "{\"error\":\"framesize must be VGA, SVGA, XGA, HD, FHD, QHD, SXGA, or UXGA\"}");
      return;
    }
    sensor_t* sensor = esp_camera_sensor_get();
    if (!sensor) { server.send(503, "application/json", "{\"error\":\"camera sensor unavailable\"}"); return; }
    if (sensor->set_framesize(sensor, nextSize) != 0) { server.send(500, "application/json", "{\"error\":\"camera rejected framesize\"}"); return; }
    currentFrameSize = nextSize;
    uint16_t width = 0, height = 0;
    frameDimensions(currentFrameSize, &width, &height);
    server.send(200, "application/json", String("{\"ok\":true,\"framesize\":\"") + frameSizeName(currentFrameSize) + "\",\"width\":" + String(width) + ",\"height\":" + String(height) + "}");
    return;
  }
  server.send(400, "application/json", "{\"error\":\"unsupported action\"}");
}

void handleStream() {
  WiFiClient client = server.client();
  client.setNoDelay(true);
  client.print("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=frame\r\nCache-Control: no-cache, no-store, must-revalidate\r\nPragma: no-cache\r\nConnection: keep-alive\r\n\r\n");
  while (client.connected()) {
    const uint32_t now = millis();
    if (now - lastFrameMs < streamIntervalMs()) { delay(2); continue; }
    lastFrameMs = now;
    camera_fb_t* frame = esp_camera_fb_get();
    if (!frame || frame->format != PIXFORMAT_JPEG) {
      if (frame) esp_camera_fb_return(frame);
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
    while (WiFi.status() != WL_CONNECTED && millis() < deadline) { delay(250); Serial.print('.'); }
    Serial.println();
    if (WiFi.status() == WL_CONNECTED) {
      apMode = false;
      Serial.print("STA IP: "); Serial.println(WiFi.localIP());
      return;
    }
  }
  WiFi.disconnect(true, true);
  delay(100);
  WiFi.mode(WIFI_AP);
  const bool started = WiFi.softAP(SMARTTRAFFIC_AP_SSID, SMARTTRAFFIC_AP_PASSWORD);
  apMode = started;
  Serial.print("AP mode: "); Serial.println(started ? WiFi.softAPIP() : IPAddress(0,0,0,0));
}

} // namespace

void setup() {
  Serial.begin(115200);
  delay(200);
  if (!initializeCamera()) { delay(1000); ESP.restart(); }
  connectNetwork();
  server.on("/", HTTP_GET, []() {
    server.send(200, "text/plain; charset=utf-8", "Smart Traffic Camera\n/stream\n/capture\n/status\n/control?action=quality&value=10\n/control?action=framesize&value=HD\n");
  });
  server.on("/status", HTTP_GET, []() { server.send(200, "application/json; charset=utf-8", jsonStatus()); });
  server.on("/capture", HTTP_GET, sendJpegFrame);
  server.on("/control", HTTP_GET, handleControl);
  server.on("/stream", HTTP_GET, handleStream);
  server.begin();
  Serial.println("HTTP server started");
}

void loop() { server.handleClient(); delay(1); }
