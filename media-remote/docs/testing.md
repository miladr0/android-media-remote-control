# Testing & Media App Compatibility Matrix

## 1. Automated Test Suites

| Suite | Location | How to Run |
| --- | --- | --- |
| Protocol Schema, Fixtures & HMAC Integration | `/tests/protocol.integration.test.mjs` | `node --test media-remote/tests/protocol.integration.test.mjs` |
| Android Contract & Unit Tests | `/android/app/src/test/...` | `./gradlew test` inside `/media-remote/android` |
| Legacy iOS Unit Tests | `/ios/MediaRemoteTests/...` | `Cmd + U` in Xcode (`MediaRemoteTests`) |

## 2. Typical Media App Behavior on Android (`MediaSession`)

| App | Package | `play` / `pause` | `next` / `previous` | `stop` | Metadata & Artwork | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| **Spotify** | `com.spotify.music` | Supported | Supported | **Unsupported** | Title, Artist, Album, Art | Spotify omits `ACTION_STOP`; Web Remote disables Stop button & Android returns `UNSUPPORTED_ACTION` if sent. |
| **YouTube** | `com.google.android.youtube` | Supported | Supported (Playlist) | **Unsupported** | Title, Channel (as Artist), Thumbnail | `next`/`previous` may be absent on single non-playlist videos. |
| **VLC** | `org.videolan.vlc` | Supported | Supported | Supported | Title, Artist/Album (when tagged) | Exposes `ACTION_STOP`; issuing `stop` clears playback state to `STOPPED`. |
| **OEM Music Players** | e.g. `com.miui.player`, `com.sec.android.app.music` | Supported | Supported | Often Supported | Title, Artist, Embedded Art | Human-readable app name and icon are resolved via `<queries>` + `PackageManager`. |

## 3. End-to-End Verification Checklist (Device & Browser)

1. **QR URL Pairing**: Scanning Android QR (or opening the URL in any browser) loads `http://<ip>:8765/?pair=<token>` and strips `?pair=` from the URL bar.
2. **Secret Swap & Storage**: `pair_request` swaps the one-time token for a 256-bit `deviceSecret` saved in `localStorage`.
3. **Pure-JS HMAC Auth**: WebSocket authenticates on plain HTTP using pure-JS HMAC-SHA256 without `crypto.subtle`, adjusting timestamps via `serverTime` from `hello_ack`.
4. **Play & Pause**: Tapping Play/Pause reports `Sent` -> `Accepted by Android`, then updates UI strictly on `state`.
5. **Next & Previous**: Advances tracks on active media sessions.
6. **Stop**: Works when `ACTION_STOP` is exposed; disabled & reports `UNSUPPORTED_ACTION` if invoked when unsupported.
7. **App Name, Icon, Metadata & Artwork**: Displays resolved app label + cached 96x96 PNG app icon (`appIconB64`), track `title`, `artist`, and aspect-preserved `artworkBase64` (falling back to `packageName` only when no label exists).
8. **Last Played Resume**: When active sessions clear after playback stops, remote shows the persisted last played app/track with `"Last played"` and Play enabled; tapping Play resumes via platform `MediaBrowser` or `KEYCODE_MEDIA_PLAY` fallback (or reports a clear error if the player must be opened once on the phone).
9. **Multi-Session Picker**: Having multiple active players displays session switcher chips with app icons and names in the web remote.
10. **Visibility & Wi-Fi Recovery**: Returning to the browser or Home Screen app triggers `visibilitychange` reconnect and `sync_request`.
11. **Revocation & Notification Exit**: Tapping **Revoke** closes that browser immediately with `DEVICE_REVOKED` and stops the foreground service if no paired devices or sockets remain; tapping **Exit** in the notification shuts down the server cleanly without auto-restarting until the app is opened again.
12. **Unauthorized & Replay Rejection**: Unpaired clients receive `UNAUTHENTICATED`; stale `seq` or duplicate `nonce` receives `REPLAY_DETECTED`.
