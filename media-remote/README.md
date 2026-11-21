# LocalCast Remote — Local Wi-Fi Android Media Remote

A zero-cloud, local-Wi-Fi remote control system where your Xiaomi Android phone serves a Safari-ready mobile web remote directly over your local Wi-Fi network—no App Store app or internet connection required:

- **Android Receiver & Web Remote (`/android`)**: Native Kotlin + Jetpack Compose app using `MediaSessionManager`, `NotificationListenerService`, `AudioManager`, `NsdManager`, and Ktor CIO serving both the static mobile web remote (`http://<android-ip>:8765/`) and the WebSocket control endpoint (`ws://<android-ip>:8765/mediaremote`).
- **Shared Protocol (`/shared`)**: Versioned JSON Schemas (Draft-07) and protocol specification (`_mediaremote._tcp.`, port `8765`).
- **Legacy iOS Native Project (`/ios`)**: Retained in the repository for reference; the primary iPhone workflow now uses Safari + Add to Home Screen directly from the Android receiver.

## Quick Links
- [Architecture & Security Trade-Offs](docs/architecture.md)
- [WebSocket Protocol v1](docs/protocol.md)
- [Xiaomi & iPhone Safari Setup Guide](docs/setup.md)
- [Testing & App Compatibility Matrix](docs/testing.md)
