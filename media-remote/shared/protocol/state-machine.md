# MediaRemote v1 Connection & Command State Machine

## 1. Browser WebSocket Connection & Pairing Lifecycle

```
USER SCANS ANDROID QR: http://<android-ip>:8765/?pair=<one-time-token>
   │
   ▼ Safari loads static web remote from Android Ktor assets (/)
   ├─► Extracts ?pair=<token> and strips it from URL via history.replaceState
   │
   ▼ Opens ws://<android-ip>:8765/mediaremote
TCP_OPEN ──► browser sends `hello` { clientId, clientName, supportedVersions: [1] }
   │
   ▼ server sends `hello_ack` { selectedVersion: 1, requiresPairing }
   ├──► (if ?pair token present or requiresPairing == true)
   │         browser sends `pair_request` { clientId, clientName, pairingToken }
   │         └─► server validates one-time token, generates 256-bit `deviceSecret`,
   │             stores it in EncryptedSharedPreferences, replies `pair_ack` { deviceSecret }
   │             └─► browser stores { deviceId, deviceName, clientId, deviceSecret } in localStorage
   │
   ▼ server sends `auth_challenge` { serverNonce, sessionSalt }
   │
   ▼ browser computes pure-JS HMAC-SHA256(deviceSecret, serverNonce:clientNonce:clientId)
   │   (no crypto.subtle used, compatible with plain HTTP in iOS Safari)
   │   sends `auth_response` { clientId, clientNonce, proofHmac }
   │
   ▼ server verifies proofHmac in constant time, replies `auth_ok` { serverProofHmac }
   │
   ▼
AUTHENTICATED
   ├──► server immediately pushes `state` snapshot
   ├──► browser/server exchange `ping` / `pong` every 10s
   └──► browser auto-reconnects on socket drop & `visibilitychange` (page visible)
```

## 2. Command Acceptance vs Playback-State Confirmation

1. Browser sends `command` (`id = cli-1234`, `action = "stop"`), UI shows `"Sent: stop"`.
2. Android server validates:
   - Session is `AUTHENTICATED` (else `UNAUTHENTICATED`).
   - `seq > lastInboundSeq` and `|now - ts| <= 30_000ms` and unique `nonce` (else `REPLAY_DETECTED`).
   - Active `MediaController` exists (else `NO_ACTIVE_SESSION`).
   - Action is present in `supportedActions` derived from `PlaybackState.actions` (else `ack(rejected)` + `error(UNSUPPORTED_ACTION)`).
3. Android dispatches `controller.transportControls.stop()` and replies `ack { status: "accepted" }`. Browser updates feedback to `"Accepted by Android: stop"`.
4. Only when `MediaController.Callback.onPlaybackStateChanged()` fires does Android push `state`, updating the active transport buttons and clearing the pending notice.
