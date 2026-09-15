# Project Context Session — 2026-09-15 — ESP32-S3 Live Transport Repair

## Physical test findings
After flashing the ESP32-S3-N16R8, the board entered AP mode at `192.168.4.1`. The Android app could capture a still image, proving local Wi-Fi/IP/HTTP connectivity and camera initialization. Live MJPEG failed, live analysis therefore failed, and camera controls were unreliable when the original stream was active. Serial output included `Connection reset by peer` during stream writes.

## Root cause
The original Firmware served `/stream` from the same port-80 Arduino `WebServer` using a blocking `while (client.connected())` loop. That monopolized the request handler and prevented timely processing of `/status`, `/capture`, and `/control`.

## Repair
Commit `0d8572a407bd65b4677a34d39913299e191e3a90` changes the transport architecture:
- Port 80 remains the HTTP API for status, capture, and camera controls.
- Port 81 is a dedicated `WiFiServer` for MJPEG `/stream`.
- The stream server runs in a dedicated FreeRTOS task, so it no longer blocks the port-80 HTTP loop.
- A FreeRTOS camera mutex serializes framebuffer acquisition and sensor setting changes.
- Only one MJPEG client is accepted at a time.
- `/status` now exposes the stream port and stream-client activity.
- The stream URL reported by firmware uses the active STA/AP address.

Android `DeviceSettings.streamUrl()` was updated to use port 81 while `/capture`, `/status`, and `/control` continue using the configured HTTP port (default 80). The existing MJPEG parser and bounded newest-frame `MjpegFrameSource` remain in place.

## Camera quality
The firmware still starts at HD 1280×720 and JPEG quality 10. Project firmware continues to expose VGA, SVGA, XGA, HD, FHD, QHD, SXGA, and UXGA; QHD remains the highest mode exposed by the bundled driver. Higher sensor capability must not be claimed without a corresponding verified driver build.

## Required physical validation
1. Connect phone to the `SmartTraffic-CAM` AP or configured STA network.
2. Test `/status` on port 80.
3. Test `/capture` on port 80.
4. Open `http://192.168.4.1:81/stream` from a direct MJPEG-capable client.
5. Use the app's Connect stream and confirm continuous live frames.
6. While streaming, change JPEG quality and frame size and verify successful responses plus visible frame changes.
7. Start live analysis and verify LiteRT/ByteTrack receives continuous frames.
8. Measure frame rate, JPEG byte size, dropped frames, and end-to-end latency.

## Known limitation
This transport repair does not itself guarantee sharp license-plate imagery. Focus, exposure, lighting, mounting distance, sensor tuning, JPEG quality, and frame size still require physical tuning.
