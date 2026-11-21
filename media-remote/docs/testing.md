# Testing & Media App Compatibility Matrix

## 1. Automated Test Suites

| Suite | Location | How to Run |
| --- | --- | --- |
| Protocol Schema & HMAC Integration | `/tests/protocol.integration.test.mjs` | `node --test media-remote/tests/protocol.integration.test.mjs` |
| Android Unit Tests | `/android/app/src/test/...` | `./gradlew test` inside `/media-remote/android` |
| Legacy iOS Unit Tests | `/ios/MediaRemoteTests/...` | `Cmd + U` in Xcode (`MediaRemoteTests`) |

## 2. Media App Behavior on Xiaomi (Android MediaSession)

| App | Package | `play` / `pause` | `next` / `previous` | `stop` | Metadata & Artwork | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| **Spotify** | `com.spotify.music` | Supported | Supported | **Unsupported** | Title, Artist, Album, Art | Spotify omits `ACTION_STOP`; Web Remote disables Stop button & Android returns `UNSUPPORTED_ACTION` if sent. |
| **YouTube** | `com.google.android.youtube` | Supported | Supported (Playlist) | **Unsupported** | Title, Channel (as Artist), Thumbnail | `next`/`previous` may be absent on single non-playlist videos. |
| **VLC** | `org.videolan.vlc` | Supported | Supported | Supported | Title, Artist/Album (when tagged) | Exposes `ACTION_STOP`; issuing `stop` clears playback state to `STOPPED`. |
| **MIUI Music** | `com.miui.player` | Supported | Supported | Supported | Title, Artist, Embedded Art | Requires Notification Listener access to expose session cleanly on HyperOS. |

## 3. End-to-End Verification Checklist (All 12 Cases)

1. **QR URL Pairing**: Scanning Android QR opens `http://<ip>:8765/?pair=<token>` in Safari and strips `?pair=` from the URL bar.
2. **Secret Swap & Storage**: `pair_request` swaps the one-time token for a 256-bit `deviceSecret` saved in `localStorage`.
3. **Pure-JS HMAC Auth**: WebSocket authenticates on plain HTTP using pure-JS HMAC-SHA256 without `crypto.subtle`.
4. **Play & Pause**: Tapping Play/Pause reports `Sent` -> `Accepted by Android`, then updates UI strictly on `state`.
5. **Next & Previous**: Advances tracks on Spotify, YouTube playlists, VLC, and MIUI Music.
6. **Stop**: Works on VLC and MIUI Music; disabled & reports `UNSUPPORTED_ACTION` if invoked against Spotify/YouTube.
7. **Metadata & Artwork**: Track skips push updated `title`, `artist`, and aspect-preserved `artworkBase64` immediately.
8. **Multi-Session Picker**: Opening YouTube while Spotify is paused displays session switcher pills in the web remote.
9. **Visibility & Wi-Fi Recovery**: Returning to Safari or Home Screen app triggers `visibilitychange` reconnect and `sync_request`.
10. **Background Availability**: With Notification Access + No Battery Restrictions + Autostart enabled, Xiaomi serves HTTP & WS with screen off.
11. **Unauthorized & Replay Rejection**: Unpaired clients receive `UNAUTHENTICATED`; stale `seq` or duplicate `nonce` receives `REPLAY_DETECTED`.
12. **Revocation**: Tapping **Revoke** in Android UI drops the browser socket immediately with `DEVICE_REVOKED`.
