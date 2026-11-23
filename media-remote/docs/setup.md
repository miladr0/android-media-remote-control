# Setup & Installation Guide (Android Receiver + Local Browser)

## Part 1: Android Receiver Setup

### Install without Android Studio
1. Open this repository's **Releases** page in a browser on your Android phone.
2. Download `app-debug.apk` from the newest `LocalCast Receiver build <run>` pre-release.
3. Open the downloaded file and allow installing unknown apps from your browser if Android prompts you.
4. Launch **LocalCast Receiver** and tap **Setup** in the top bar to grant required permissions.

> **Note on APK Updates:** GitHub Actions caches the debug keystore (`~/.android/debug.keystore`) so CI builds keep a consistent debug signing key across runs once cached. If upgrading from an older APK built with a different key, uninstall the previous build once before installing the new one.

### Build & Install
1. Open `/media-remote/android` in **Android Studio Koala (2024.1+)** with JDK 17 (Android Studio resolves Gradle 8.7 directly from `gradle/wrapper/gradle-wrapper.properties`).
2. **Command-line `./gradlew` note**: `gradle/wrapper/gradle-wrapper.jar` is omitted from the repository so no binary JAR is checked in. If you want to build or run `./gradlew` from a terminal outside Android Studio, install Gradle once and run:
   ```bash
   cd media-remote/android
   gradle wrapper --gradle-version 8.7
   ./gradlew testDebugUnitTest assembleDebug
   ```
3. Connect your Android smartphone (`minSdk 26`, `targetSdk 35`) via USB with **USB Debugging** enabled.
4. Run the `app` configuration on your device.

### Background Reliability & Permissions
Inside **LocalCast Receiver**, tap **Setup** to open **Background Reliability**:

1. **Notification Access (Required by Android OS)**
   - Path: *Settings -> Apps / Privacy -> Special app access -> Notification access -> LocalCast Media Session Bridge -> Allow*.
   - Why: `MediaSessionManager.getActiveSessions()` throws `SecurityException` without an active `NotificationListenerService`.
2. **Unrestricted Battery Usage**
   - Tap **Allow Unrestricted Battery** (uses Android's `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, falling back to Battery Optimization settings or App Info).
   - Why: Prevents Doze / aggressive OEM battery savers from suspending background WebSockets when the screen turns off.
3. **OEM Background / Autostart Settings**
   - The **Background Reliability** dialog detects `Build.MANUFACTURER` / `Build.BRAND` and offers OEM shortcuts (with automatic App Info fallback) for **Xiaomi / Redmi / POCO (MIUI & HyperOS)**, **Samsung (One UI)**, **Huawei / Honor**, **Oppo / Realme / OnePlus (ColorOS & OxygenOS)**, **Vivo / iQOO**, and **Asus**, plus a generic **Open App Info** shortcut on stock Android / other devices.
4. **Notification "Exit" Button & Revoke Auto-Stop**
   - Tapping **Exit** in the foreground service notification immediately closes active WebSockets, stops Ktor and NSD, removes the notification, and keeps the server stopped (`START_NOT_STICKY`, and boot auto-start stays disabled until you open **LocalCast Receiver** again).
   - Revoking the last paired device when no sockets remain connected also stops the foreground service cleanly; tapping **Pair Device** or **Start Local Server** starts it again automatically.

---

## Part 2: Web Remote Setup (Any Browser, No App Install)

### First-Time Pairing
1. Ensure both your controller device (phone, tablet, or computer) and the Android receiver are on the same local Wi-Fi subnet (disable AP/Client Isolation on your router if enabled).
2. On Android, tap **Pair Device** to display a QR code containing `http://<android-ip>:8765/?pair=<one-time-token>`.
3. Scan the QR code with your phone's camera or open the URL in any browser on the same Wi-Fi.
4. The page loads directly from the Android receiver (`assets/web/index.html`), removes `?pair=...` from the URL bar immediately via `history.replaceState`, swaps the one-time token for a 256-bit `deviceSecret` stored in `localStorage`, and connects over `ws://<android-ip>:8765/mediaremote`.

### Add to Home Screen (Full-Screen App Mode)
1. In your mobile browser (**Share -> Add to Home Screen** on iOS Safari, or **Menu -> Install / Add to Home screen** on Android/desktop browsers), add the remote to your Home Screen.
2. Launching **LocalCast** from your Home Screen opens it full-screen (`display: standalone`, `apple-mobile-web-app-capable`) and automatically authenticates using the stored `deviceSecret`.
3. Returning to the web app after locking your screen automatically triggers a `visibilitychange` WebSocket reconnect and state sync.
