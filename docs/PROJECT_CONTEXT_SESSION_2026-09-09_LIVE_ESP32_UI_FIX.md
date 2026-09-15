# Project Context Session — 2026-09-09 — ESP32 Control + Live UI

## Scope
This session completed an Android-side integration pass for the ESP32-S3-N16R8 camera workflow and repaired the Live Camera screen behavior in portrait/landscape/fullscreen modes. Physical testing on 2026-09-15 then exposed a firmware transport/concurrency defect in the original MJPEG design.

## Verified ESP32 contract in repository
The repaired `esp32/src/main.cpp` now defines two transport planes:
- HTTP control plane on port `80`: `GET /`, `/status`, `/capture`, `/control?action=...`.
- MJPEG data plane on port `81`: `GET /stream`.

The S3 target is `SMARTTRAFFIC_CAMERA_S3_N16R8` in `esp32/platformio.ini` and uses the repository's current OV5640-style pin map. The source comments/architecture still require verification against the exact PCB revision before treating the pin map as final.

## Physical test findings — 2026-09-15
After flashing the ESP32-S3-N16R8, the serial monitor showed `AP mode: 192.168.4.1`, `HTTP server started`, and later `write(): ... errno: 104, "Connection reset by peer"`. From the real Android phone, `/capture` succeeded, proving local Wi-Fi/IP/HTTP connectivity and camera initialization. `/stream` did not establish usable live video, live analysis consequently did not run, and camera control requests were unreliable when the original stream path was involved. Captured image quality was visibly poor and had not yet been tested at the intended high-resolution/high-quality setting.

## Root cause of the original stream failure
The original firmware implemented `/stream` as a blocking `while (client.connected())` loop inside the single Arduino `WebServer` instance used for port 80. While that handler was active, `server.handleClient()` could not service `/capture`, `/status`, or `/control`. This made the HTTP API and MJPEG stream contend for one request-processing loop.

The Android client already contained a pause-before-capture/control workaround, but that only mitigated the blocking firmware behavior; it did not make the transport architecture concurrent.

## Firmware transport repair — commit 0d8572a407bd65b4677a34d39913299e191e3a90
The firmware was changed so that:
1. Port 80 remains the HTTP control/capture plane.
2. Port 81 is a separate `WiFiServer` used exclusively for MJPEG.
3. The MJPEG server runs in a dedicated FreeRTOS task, so a stream no longer blocks the port-80 `WebServer` loop.
4. A FreeRTOS mutex serializes camera framebuffer acquisition and sensor setting changes, preventing stream/capture/control from concurrently touching camera state.
5. Only one MJPEG client is accepted at a time.
6. Firmware logs stream connect/disconnect and exposes `stream_port` and `stream_client_active` in `/status`.
7. `/status` reports the active STA or AP IP when constructing the stream URL.

## Android transport change
`DeviceSettings.streamUrl()` now targets the fixed firmware MJPEG port `81`, while `httpPort` remains the configurable port for `/capture`, `/status`, and `/control`. The existing `MjpegStreamClient` and `MjpegFrameSource` remain protocol-compatible with the multipart response and continue to use bounded newest-frame buffering for live analysis.

## Camera quality state
Current firmware defaults remain frame size `HD` (1280×720) and JPEG quality `10`. Supported project driver modes remain VGA, SVGA, XGA, HD, FHD, QHD, SXGA, UXGA. `QHD` is still the highest mode exposed by the bundled camera driver; do not claim 5MP availability in this firmware snapshot. JPEG quality uses the existing camera-driver scale where smaller numbers request higher quality/larger JPEGs.

## Live analysis path
The intended live path remains `LiveCameraScreen -> MjpegFrameSource -> LiteRT -> ByteTrack -> geometry/speed -> preview`. Live analysis now consumes frames from `http://<camera-host>:81/stream`, while camera controls continue to use port 80. The analysis architecture itself was not modified by this transport repair.

## Validation required after flashing the repaired firmware
1. Connect phone to `SmartTraffic-CAM` AP (or verify configured STA network).
2. Confirm `http://192.168.4.1/status` responds.
3. Confirm `http://192.168.4.1/capture` returns a JPEG.
4. Confirm a direct stream client can open `http://192.168.4.1:81/stream`.
5. From the app, connect live stream and verify continuous frames.
6. While stream is active, change JPEG quality and frame size and verify successful responses plus visible frame changes.
7. Start live analysis and verify LiteRT/ByteTrack receives continuous frames.
8. Measure frame rate, JPEG byte size, dropped frames, and end-to-end latency.

## Known limitation
This transport repair does not itself guarantee sharp long-distance license-plate imagery. Optical focus, lighting, mounting distance, sensor configuration, JPEG quality, and frame size still require physical tuning.
