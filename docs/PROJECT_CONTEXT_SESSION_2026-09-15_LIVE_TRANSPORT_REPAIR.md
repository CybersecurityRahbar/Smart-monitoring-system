# Project Context Session — 2026-09-15 — ESP32-S3 Live Transport and Resolution Repair

## Physical test findings
After flashing the ESP32-S3-N16R8, the board entered AP mode at `192.168.4.1`. The Android app could capture a still image and save it successfully, proving local Wi-Fi/IP/HTTP connectivity and camera initialization. Live analysis also started successfully. The remaining failures were specifically the manual **Connect stream** action and several high-resolution camera modes.

## Issue A — Connect stream
The repaired architecture keeps the camera HTTP API on port 80 and MJPEG on port 81. The Android client was still using `HttpURLConnection` to consume a long-lived multipart response. Although that API can handle ordinary HTTP, it adds another transport layer for this very small local TCP server and made the failure surface only as a generic stream/network error.

### Repair
Android `MjpegStreamClient` now uses a direct TCP `Socket` for the local MJPEG endpoint:
- Parses the configured `http://host:81/stream` URL.
- Connects directly to the ESP32 TCP port with an explicit timeout.
- Sends an HTTP GET request over the socket.
- Reads and validates the HTTP status and `Content-Type` boundary.
- Parses multipart frame headers and `Content-Length` directly from the same stream.
- Keeps the socket open for continuous JPEG frames.
- Closes the socket when the coroutine is cancelled, so reconnect/stop is deterministic.
- Reports transport-level errors such as socket timeout/reset instead of collapsing everything into a generic HTTP exception.

## Issue B — FHD / QHD / UXGA / SXGA resolution changes
The previous firmware called `sensor->set_framesize()` while the camera driver had already allocated its frame/DMA buffers for the startup configuration. The UI therefore offered the high modes, but a successful sensor-register update did not guarantee that subsequent frame acquisition was correctly reconfigured.

Espressif's camera driver allocates and configures framebuffer/DMA resources as part of `esp_camera_init()`, and the official examples initialize the driver with the intended JPEG frame size. The OV5640 driver also exposes high-resolution modes, so the requested FHD/QHD/UXGA/SXGA modes are not rejected merely because their enum names exist. citeturn816156search1turn816156search0

### Repair
Firmware frame-size changes now perform a controlled full camera reconfiguration:
1. Request the current MJPEG client to stop.
2. Wait until the stream task has released its active client.
3. Acquire the camera mutex.
4. Call `esp_camera_deinit()`.
5. Reinitialize the driver with the requested `frame_size`, current JPEG quality, PSRAM framebuffer location, and current pin map.
6. Verify the sensor handle and requested frame size.
7. If initialization fails, restore the previous resolution automatically.
8. Return a structured HTTP error explaining whether the requested mode was rejected and whether the old mode was restored.

JPEG quality changes also stop the stream first, change the sensor setting under the same camera mutex, and then allow the stream to reconnect.

## Current transport contract
- Port 80: `/status`, `/capture`, `/control`
- Port 81: `/stream`
- Android `DeviceSettings.streamUrl()` targets port 81.
- Android capture/status/control continue to use port 80.
- Only one MJPEG client is accepted at a time.
- Stream and camera-control operations are serialized through the firmware camera mutex.

## Current camera defaults and exposed modes
Startup remains HD `1280×720` with JPEG quality `10`. The firmware exposes:
- VGA `640×480`
- SVGA `800×600`
- XGA `1024×768`
- HD `1280×720`
- SXGA `1280×1024`
- UXGA `1600×1200`
- FHD `1920×1080`
- QHD `2560×1440`

The project must continue to distinguish the currently bundled driver capabilities from the physical OV5640 sensor's broader theoretical capabilities.

## Required physical validation after this repair
1. Flash the updated S3 firmware.
2. Rebuild/install the updated Android APK containing the raw-TCP MJPEG client.
3. Connect the phone to `SmartTraffic-CAM`.
4. Verify `/status` at `http://192.168.4.1/status`.
5. Verify `/capture` at `http://192.168.4.1/capture`.
6. Press **Connect stream** in Live and confirm continuous visible frames.
7. Confirm Serial Monitor shows `STREAM client connected` and repeated frame activity without immediate disconnect.
8. Test VGA → SVGA → XGA → HD, then SXGA → UXGA → FHD → QHD individually. After every change, verify that capture still works and the returned image dimensions match the requested mode.
9. Start live analysis after confirming the stream.
10. Record any exact Serial Monitor error (`Camera init failed`, `Setting framesize ... failed`, `STREAM client write failed`, or socket disconnect details) if a particular high-resolution mode still fails.

## Known limitation
A successful high-resolution capture does not by itself guarantee useful license-plate imagery. Focus, exposure, lighting, mounting distance, sensor tuning, JPEG quality, and physical vibration still need tuning for ANPR quality.
