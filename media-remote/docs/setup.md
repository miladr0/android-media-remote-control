# Setup & Installation Guide (Xiaomi Android + iPhone Safari)

## Part 1: Xiaomi Android Receiver Setup

### Install without Android Studio
1. Open this repository's **Releases** page in a browser on your Xiaomi phone.
2. Download `app-debug.apk` from the newest `LocalCast Receiver build <run>` pre-release.
3. Open the downloaded file and allow installing unknown apps from the browser when MIUI asks.
4. Launch **LocalCast Receiver** and run the **Xiaomi Setup** wizard to grant required permissions.

> **Note:** Each CI build uses a new debug signing key, so uninstall the previous version from your phone before installing a newer CI build.

### Build & Install
1. Open `/media-remote/android` in **Android Studio Koala (2024.1+)** with JDK 17 (Android Studio resolves Gradle 8.7 directly from `gradle/wrapper/gradle-wrapper.properties`).
2. **Command-line `./gradlew` note**: `gradle/wrapper/gradle-wrapper.jar` is omitted from the repository so no binary JAR is checked in. If you want to build or run `./gradlew` from a terminal outside Android Studio, install Gradle once and run:
   ```bash
   cd media-remote/android
   gradle wrapper --gradle-version 8.7
   ./gradlew testDebugUnitTest assembleDebug
   ```
3. Connect your Xiaomi smartphone via USB with **USB Debugging** (and **Install via USB** in MIUI Developer Options) enabled.
4. Run the `app` configuration on your Xiaomi device (`minSdk 26`, `targetSdk 35`, tested for MIUI / HyperOS).

### Mandatory Xiaomi MIUI / HyperOS Permissions
Inside **LocalCast Receiver**, tap **Xiaomi Setup** and configure all three items:

1. **Notification Access (Required by Android OS)**
   - Path: *Settings -> Privacy protection / Apps -> Special app access -> Notification access -> LocalCast Media Session Bridge -> Allow*.
   - Why: `MediaSessionManager.getActiveSessions()` throws `SecurityException` without an active `NotificationListenerService`.
2. **Battery Saver: No Restrictions**
   - Path: *Settings -> Apps -> Manage apps -> LocalCast Receiver -> Battery saver -> No restrictions*.
   - Why: MIUI/HyperOS kills background sockets within ~3 minutes on default "Battery saver (recommended)" mode.
3. **MIUI Autostart**
   - Path: *Settings -> Apps -> Manage apps -> LocalCast Receiver -> Autostart -> Enable*.
   - Why: Prevents Xiaomi's Security Center from blocking service restarts or screen-off sockets.
4. *(Optional)* **Lock App in Recents**
   - Open the Xiaomi Recent Apps tray, long-press **LocalCast Receiver**, and tap the **Lock** icon so swiping away recent tasks does not kill the Foreground Service.

---

## Part 2: iPhone Safari Web Remote Setup (No App Install)

### First-Time Pairing
1. Ensure both iPhone and Xiaomi are on the same local Wi-Fi subnet (disable AP/Client Isolation on your router if enabled).
2. On Xiaomi, tap **Pair iPhone** to display a QR code containing `http://<android-ip>:8765/?pair=<one-time-token>`.
3. Open the **iPhone Camera** app and scan the QR code, then tap the Safari banner.
4. The page loads directly from the Xiaomi phone (`assets/web/index.html`), removes `?pair=...` from the URL bar immediately via `history.replaceState`, swaps the one-time token for a 256-bit `deviceSecret` stored in `localStorage`, and connects over `ws://<android-ip>:8765/mediaremote`.

### Add to Home Screen (Full-Screen App Mode)
1. In Safari on your iPhone, tap the **Share** button.
2. Scroll down and tap **Add to Home Screen** -> **Add**.
3. Launching **LocalCast** from your iPhone Home Screen opens it full-screen (`apple-mobile-web-app-capable`) with the custom icon (`apple-touch-icon.png`) and automatically authenticates using the stored `deviceSecret`.
4. Returning to the web app after locking your iPhone automatically triggers a `visibilitychange` WebSocket reconnect and state sync.
