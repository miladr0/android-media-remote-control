# Architecture — LocalCast Cross-Platform Media Remote

## 1. System Overview

LocalCast Remote lets any browser on the same local Wi-Fi (phone, tablet, or computer) control media playback on an Android smartphone (`minSdk 26`, `targetSdk 35`) without cloud infrastructure, internet access, or installing a dedicated remote app.

```
┌──────────────────────────────────┐          Local Wi-Fi (HTTP + WS)          ┌───────────────────────────────────────────┐
│ Any Mobile / Desktop Browser     │ ◄────────────────────────────────────────► │ Android Receiver (Kotlin + Compose)       │
│                                  │    GET http://<lan-ip>:8765/ (HTML/PNG)    │                                           │
│ • Static assets/web/index.html   │    WS  ws://<lan-ip>:8765/mediaremote      │ • MediaRemoteForegroundService            │
│ • Pure-JS HMAC-SHA256 auth       │                                            │ • NsdAdvertiser (_mediaremote._tcp.)      │
│ • localStorage deviceSecret      │                                            │ • MediaWebSocketServer (Ktor CIO)         │
│ • Auto-reconnect + visibility    │                                            │ • MediaSessionCoordinator                 │
│                                  │                                            │ • MediaNotificationListenerService        │
└──────────────────────────────────┘                                            └───────────────────────────────────────────┘
```

## 2. Pairing & Security Model (Plain HTTP LAN Trade-Off)

1. **QR-Code Pairing URL**: Android generates a 120-second single-use `pairingToken` and displays a QR code for `http://<android-ip>:8765/?pair=<one-time-token>`. Tapping **Pair Device** starts `MediaRemoteForegroundService` automatically if it was stopped.
2. **Token Swap & URL Scrubbing**: When a browser opens the URL, `index.html` extracts `pair`, strips it from the address bar via `history.replaceState`, and sends `pair_request` over WebSocket. Android verifies the token in constant time, marks it consumed, generates a unique 256-bit `deviceSecret` stored in `EncryptedSharedPreferences`, and returns `pair_ack { deviceSecret }`. The browser saves it in `localStorage`.
3. **Pure-JS HMAC-SHA256 Challenge-Response**: Because browsers (especially iOS Safari) disable `window.crypto.subtle` on non-HTTPS (`http://`) pages, `index.html` includes a zero-dependency FIPS 180-4 SHA-256 / RFC 2104 HMAC-SHA256 implementation paired with `window.crypto.getRandomValues`. On every WebSocket connection, Android sends `hello_ack { serverTime }` (used to compute `clockOffsetMs`) and `auth_challenge { serverNonce }`, and the browser proves possession of `deviceSecret` without transmitting the secret again.
4. **Plain-HTTP Local Network Trade-Off**: Transport-level TLS (`https://` / `wss://`) is intentionally omitted in this version because self-signed certificates on dynamic LAN DHCP IPs cannot be trusted seamlessly in mobile browsers without installing a custom root CA profile. As a result, traffic on the local Wi-Fi segment is unencrypted (`ws://`), while command endpoints remain strictly protected against unauthorized clients, token reuse, and packet replay (`ReplayGuard` monotonic `seq` + unique `nonce` + ±30s `ts`). Assume a trusted home/private Wi-Fi network (WPA2/WPA3).
5. **Device Revocation & Service Lifecycle**: Revoking any paired browser from the Android UI immediately sends `DEVICE_REVOKED`, closes its active WebSocket(s), and deletes its `deviceSecret`. When the paired list becomes empty and no client sockets remain connected, Android stops `MediaRemoteForegroundService` (Ktor, NSD, and Wi-Fi lock).

## 3. Android Background Reliability & Lifecycle

- **Foreground Service (`connectedDevice`)**: Declares `CHANGE_WIFI_STATE` and `CHANGE_NETWORK_STATE` (required on Android 14+/API 34+) and holds `WIFI_MODE_FULL_LOW_LATENCY` so Ktor CIO remains reachable with the screen off.
- **Notification "Exit" Action & Boot Receiver**: The persistent notification includes an explicit `FLAG_IMMUTABLE` **Exit** action (`ACTION_EXIT`) that shuts down Ktor, NSD, and locks, removes the notification (`STOP_FOREGROUND_REMOVE`), stops the service returning `START_NOT_STICKY`, and records an explicit exit flag so `BootReceiver` does not auto-start the service on reboot until the user opens the app again.
- **MulticastLock**: Keeps `NsdManager` `_mediaremote._tcp.` discovery active.
- **BackgroundReliabilityDialog**: Generic `NotificationListenerService` + `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` setup paired with manufacturer-aware shortcuts (`Build.MANUFACTURER`) for Xiaomi/Redmi/POCO, Samsung, Huawei/Honor, Oppo/Realme/OnePlus, Vivo/iQOO, and Asus, falling back to `ACTION_APPLICATION_DETAILS_SETTINGS`.

## 4. App Discovery, Metadata & Last-Played Resume

- **Android 11+ Package Visibility (`<queries>`)**: Declares intent queries for `MAIN`+`LAUNCHER` and `android.media.browse.MediaBrowserService` (without requesting `QUERY_ALL_PACKAGES`), resolving cached human-readable app labels and 96x96 Base64 PNG icons (`appIconB64`).
- **Last-Played Persistence & Safe Resume**: Whenever a session plays, `MediaSessionCoordinator` persists `(packageName, appName, title, artist, small thumbnail, lastPlaybackState)` in `SharedPreferences`. When no active session is running, Android emits `isLastPlayed = true` with `supportedActions = ["play"]`. Pressing Play resumes via platform `android.media.browse.MediaBrowser` -> `transportControls.play()`, falling back to `AudioManager.dispatchMediaKeyEvent` (`KEYCODE_MEDIA_PLAY`), and only returns `ack { status: "accepted" }` once playback actually begins.
