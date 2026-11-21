# Architecture — LocalCast Cross-Platform Media Remote

## 1. System Overview

LocalCast Remote lets an iPhone (or any mobile browser on the same local Wi-Fi) control media playback on a Xiaomi Android smartphone without cloud infrastructure, internet access, or installing an iOS app.

```
┌──────────────────────────────────┐          Local Wi-Fi (HTTP + WS)          ┌───────────────────────────────────────────┐
│ iPhone Safari / Home Screen PWA  │ ◄────────────────────────────────────────► │ Xiaomi Android (Kotlin + Jetpack Compose) │
│                                  │    GET http://<lan-ip>:8765/ (HTML/PNG)    │                                           │
│ • Static assets/web/index.html   │    WS  ws://<lan-ip>:8765/mediaremote      │ • MediaRemoteForegroundService            │
│ • Pure-JS HMAC-SHA256 auth       │                                            │ • NsdAdvertiser (_mediaremote._tcp.)      │
│ • localStorage deviceSecret      │                                            │ • MediaWebSocketServer (Ktor CIO)         │
│ • Auto-reconnect + visibility    │                                            │ • MediaSessionCoordinator                 │
│                                  │                                            │ • MediaNotificationListenerService        │
└──────────────────────────────────┘                                            └───────────────────────────────────────────┘
```

## 2. Pairing & Security Model (Plain HTTP LAN Trade-Off)

1. **QR-Code Pairing URL**: Android generates a 120-second single-use `pairingToken` and displays a QR code for `http://<android-ip>:8765/?pair=<one-time-token>`.
2. **Token Swap & URL Scrubbing**: When Safari opens the URL, `index.html` extracts `pair`, strips it from the address bar via `history.replaceState`, and sends `pair_request` over WebSocket. Android verifies the token in constant time, marks it consumed, generates a unique 256-bit `deviceSecret` stored in `EncryptedSharedPreferences`, and returns `pair_ack { deviceSecret }`. Safari saves it in `localStorage`.
3. **Pure-JS HMAC-SHA256 Challenge-Response**: Because iOS Safari disables `window.crypto.subtle` on non-HTTPS (`http://`) pages, `index.html` includes a zero-dependency FIPS 180-4 SHA-256 / RFC 2104 HMAC-SHA256 implementation paired with `window.crypto.getRandomValues`. On every WebSocket connection, Android sends `auth_challenge { serverNonce }`, and the browser proves possession of `deviceSecret` without ever transmitting the secret again.
4. **Plain-HTTP Local Network Trade-Off**: Transport-level TLS (`https://` / `wss://`) is intentionally omitted in this version because self-signed certificates on dynamic LAN DHCP IPs cannot be trusted seamlessly in iOS Safari without installing a custom root CA profile. As a result, traffic on the local Wi-Fi segment is unencrypted (`ws://`), while command endpoints remain strictly protected against unauthorized clients, token reuse, and packet replay (`ReplayGuard` monotonic `seq` + unique `nonce` + ±30s `ts`). Assume a trusted home/private Wi-Fi network (WPA2/WPA3).
5. **Device Revocation**: Revoking any paired browser from the Android UI immediately sends `DEVICE_REVOKED`, closes its active WebSocket, and deletes its `deviceSecret`.

## 3. Xiaomi MIUI / HyperOS Reliability Architecture

- **Foreground Service (`connectedDevice`)**: Declares `CHANGE_WIFI_STATE` and `CHANGE_NETWORK_STATE` (required on Android 14+/API 34+) and holds `WIFI_MODE_FULL_LOW_LATENCY` so Ktor CIO remains reachable with the screen off.
- **MulticastLock**: Keeps `NsdManager` `_mediaremote._tcp.` discovery active.
- **XiaomiSetupWizard**: Direct deep links to Notification Listener Access, Battery Optimization (`No restrictions`), and MIUI Autostart (`AutoStartManagementActivity`).

## 4. Command Acceptance vs Playback-State Confirmation

1. Browser shows `"Sent: <action>"` on dispatch.
2. Android validates `PlaybackState.actions` bitmask before invoking `MediaController.TransportControls`.
3. Android sends `ack { status: "accepted" }` (or `"rejected"` + `error { code: "UNSUPPORTED_ACTION" }`).
4. Authoritative playback icon and metadata update strictly when `MediaController.Callback.onPlaybackStateChanged` pushes a new `state` message.
