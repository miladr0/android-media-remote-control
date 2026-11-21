# MediaRemote WebSocket Protocol v1

- **Web Remote**: `http://<android-ip>:8765/` (`index.html`, `manifest.webmanifest`, `apple-touch-icon.png`)
- **WebSocket Transport**: `ws://<android-ip>:8765/mediaremote`
- **Service Discovery**: mDNS `_mediaremote._tcp.`
- **Encoding**: UTF-8 JSON validated against `/shared/schemas/*.json`

## 1. Frame Envelope

```json
{
  "v": 1,
  "type": "command",
  "id": "req-91d2b1a0",
  "seq": 4,
  "ts": 1763712000000,
  "nonce": "3q2+7w==",
  "data": { "action": "play" }
}
```

## 2. Handshake, Pairing & Challenge-Response Auth

1. `browser -> android`: `hello` `{ clientId, clientName, supportedVersions: [1] }`
2. `android -> browser`: `hello_ack` `{ selectedVersion: 1, deviceId, deviceName, requiresPairing, serverTime }` (the browser computes `clockOffset = serverTime - Date.now()` and sends `ts = Date.now() + clockOffset` on all subsequent frames so `ReplayGuard` tolerates phone clock skew while enforcing strict `seq` and `nonce` checks)
3. *(First-time pairing)* `browser -> android`: `pair_request` `{ clientId, clientName, pairingToken }` -> `android -> browser`: `pair_ack` `{ deviceId, deviceName, deviceSecret, keyFingerprint }`
4. `android -> browser`: `auth_challenge` `{ serverNonce, sessionSalt }`
5. `browser -> android`: `auth_response` `{ clientId, clientNonce, proofHmac }` where `proofHmac = Base64(HMAC-SHA256(deviceSecret, "$serverNonce:$clientNonce:$clientId"))`
6. `android -> browser`: `auth_ok` `{ clientId, serverProofHmac, encryptionEnabled: false }`
7. `android -> browser`: initial `state` snapshot

## 3. Commands & State

- Commands: `play`, `pause`, `stop`, `next`, `previous`, `set_volume` (`0..100`), `select_session` (`targetSessionId`).
- Heartbeat: `ping` every 10s -> `pong`, 25s timeout triggers reconnect + `visibilitychange` immediate recovery.
- Errors: `UNSUPPORTED_VERSION`, `UNAUTHENTICATED`, `PAIRING_EXPIRED`, `INVALID_TOKEN`, `DEVICE_REVOKED`, `REPLAY_DETECTED`, `INVALID_SCHEMA`, `UNSUPPORTED_ACTION`, `NO_ACTIVE_SESSION`, `SESSION_NOT_FOUND`, `VOLUME_FIXED`, `PERMISSION_MISSING`, `RATE_LIMITED`.
