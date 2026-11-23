# LocalCast Remote — Local Wi-Fi Android Media Remote

A zero-cloud, local-Wi-Fi remote control system where your Android phone serves a mobile web remote directly over your local Wi-Fi network—no client app install or internet connection required:

- **Android Receiver & Web Remote (`/android`)**: Native Kotlin + Jetpack Compose app using `MediaSessionManager`, `NotificationListenerService`, `AudioManager`, `NsdManager`, and Ktor CIO serving both the static mobile web remote (`http://<android-ip>:8765/`) and the WebSocket control endpoint (`ws://<android-ip>:8765/mediaremote`). Compatible with Android phones (`minSdk 26`, `targetSdk 35`) and any modern browser on the same Wi-Fi (phone, tablet, or computer).
- **Shared Protocol (`/shared`)**: Versioned JSON Schemas (Draft-07), shared wire fixtures, and protocol specification (`_mediaremote._tcp.`, port `8765`).
- **Legacy iOS Native Project (`/ios`)**: Retained in the repository for reference; the primary remote workflow now runs directly in any mobile or desktop browser served by the Android receiver.

## Quick Links
- [Architecture & Security Trade-Offs](docs/architecture.md)
- [WebSocket Protocol v1](docs/protocol.md)
- [Android & Browser Setup Guide](docs/setup.md)
- [Testing & App Compatibility Matrix](docs/testing.md)
