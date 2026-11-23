import express from 'express';
import http from 'http';
import fs from 'fs';
import path from 'path';
import crypto from 'crypto';
import zlib from 'zlib';
import { WebSocketServer, WebSocket } from 'ws';
import { createRequire } from 'module';
import QRCode from 'qrcode';
import Ajv from 'ajv';
import { createServer as createViteServer } from 'vite';

const require = createRequire(import.meta.url);
const { ZipArchive } = require('archiver') as {
  ZipArchive: new (options?: Record<string, unknown>) => any;
};

const PORT = 3000;
const ROOT_DIR = process.cwd();
const MONOREPO_DIR = path.join(ROOT_DIR, 'media-remote');
const SCHEMAS_DIR = path.join(MONOREPO_DIR, 'shared', 'schemas');

// Compile shared JSON Schemas with Ajv so the live WebSocket workbench enforces the exact same contract
const ajv = new Ajv({ allErrors: true, strict: false });
const loadSchema = (filename: string) =>
  JSON.parse(fs.readFileSync(path.join(SCHEMAS_DIR, filename), 'utf8'));

const helloSchema = loadSchema('hello.schema.json');
const authSchema = loadSchema('auth.schema.json');
const heartbeatSchema = loadSchema('heartbeat.schema.json');

const validators = {
  envelope: ajv.compile(loadSchema('envelope.schema.json')),
  hello: ajv.compile(helloSchema.oneOf[0]),
  pairRequest: ajv.compile(authSchema.oneOf[0]),
  authResponse: ajv.compile(authSchema.oneOf[3]),
  ping: ajv.compile(heartbeatSchema.oneOf[0]),
  syncRequest: ajv.compile(heartbeatSchema.oneOf[2]),
  command: ajv.compile(loadSchema('command.schema.json')),
  state: ajv.compile(loadSchema('state.schema.json')),
  ack: ajv.compile(loadSchema('ack.schema.json')),
  error: ajv.compile(loadSchema('error.schema.json')),
  pairingQr: ajv.compile(loadSchema('pairing-qr.schema.json')),
};

interface MediaSessionSim {
  sessionId: string;
  packageName: string;
  appName: string;
  title: string;
  artist: string;
  album: string;
  durationMs: number;
  positionMs: number;
  playbackState: 'PLAYING' | 'PAUSED' | 'STOPPED';
  supportedActions: Array<'play' | 'pause' | 'stop' | 'next' | 'previous'>;
  artworkUrl: string | null;
  tracks: Array<{ title: string; artist: string; album: string; durationMs: number; artworkUrl: string | null }>;
  trackIndex: number;
}

const DEVICE_ID = 'and-localcast01';
const DEVICE_NAME = 'Android Phone (Simulated)';

// Generate real ECDH P-256 identity keypair for the simulated Android host
const hostKeyPair = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
const hostPubSpkiB64 = hostKeyPair.publicKey.export({ type: 'spki', format: 'der' }).toString('base64');

let activePairingToken: { token: string; expiresAt: number; consumed: boolean } | null = null;
const pairedClients = new Map<string, { clientId: string; clientName: string; sharedSecret: Buffer; fingerprint: string; pairedAt: number }>();

const appIconCache = new Map<string, string>();
function makeSolidPngB64(packageName: string, r: number, g: number, b: number): string {
  const cached = appIconCache.get(packageName);
  if (cached) return cached;
  const size = 24;
  const raw = Buffer.alloc(size * (1 + size * 4));
  for (let y = 0; y < size; y++) {
    const rowOff = y * (1 + size * 4);
    raw[rowOff] = 0;
    for (let x = 0; x < size; x++) {
      const px = rowOff + 1 + x * 4;
      raw[px] = r;
      raw[px + 1] = g;
      raw[px + 2] = b;
      raw[px + 3] = 255;
    }
  }
  const crc32 = (buf: Buffer) => {
    let c = 0xffffffff;
    for (let i = 0; i < buf.length; i++) {
      c ^= buf[i];
      for (let k = 0; k < 8; k++) c = (c >>> 1) ^ (c & 1 ? 0xedb88320 : 0);
    }
    return (c ^ 0xffffffff) >>> 0;
  };
  const chunk = (type: string, data: Buffer) => {
    const len = Buffer.alloc(4);
    len.writeUInt32BE(data.length, 0);
    const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
    const crc = Buffer.alloc(4);
    crc.writeUInt32BE(crc32(body), 0);
    return Buffer.concat([len, body, crc]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0);
  ihdr.writeUInt32BE(size, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 6; // RGBA
  const png = Buffer.concat([
    Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw)),
    chunk('IEND', Buffer.alloc(0)),
  ]);
  const b64 = png.toString('base64');
  appIconCache.set(packageName, b64);
  return b64;
}

const APP_ICONS: Record<string, string> = {
  'com.spotify.music': makeSolidPngB64('com.spotify.music', 29, 185, 84),
  'com.google.android.youtube': makeSolidPngB64('com.google.android.youtube', 220, 38, 38),
  'org.videolan.vlc': makeSolidPngB64('org.videolan.vlc', 234, 88, 12),
  'com.android.music': makeSolidPngB64('com.android.music', 37, 99, 235),
};

const mediaSessions: MediaSessionSim[] = [
  {
    sessionId: 'com.spotify.music:1042',
    packageName: 'com.spotify.music',
    appName: 'Spotify',
    title: 'Amber Resonance',
    artist: 'Echostream',
    album: 'Minimalist Studio Sessions',
    durationMs: 248000,
    positionMs: 64000,
    playbackState: 'PLAYING',
    // Real Android Spotify behavior: ACTION_STOP is intentionally NOT exposed
    supportedActions: ['play', 'pause', 'next', 'previous'],
    artworkUrl: '/src/assets/images/album_art_ambient_1795257543936.jpg',
    trackIndex: 0,
    tracks: [
      {
        title: 'Amber Resonance',
        artist: 'Echostream',
        album: 'Minimalist Studio Sessions',
        durationMs: 248000,
        artworkUrl: '/src/assets/images/album_art_ambient_1795257543936.jpg',
      },
      {
        title: 'Afternoon Cedar',
        artist: 'Guild Acoustic',
        album: 'Linen & Window Light',
        durationMs: 212000,
        artworkUrl: '/src/assets/images/album_art_acoustic_1795257544586.jpg',
      },
    ],
  },
  {
    sessionId: 'com.google.android.youtube:2088',
    packageName: 'com.google.android.youtube',
    appName: 'YouTube',
    title: 'Building Zero-Cloud Local Networks with mDNS & WebSockets',
    artist: 'Systems Engineering Lab',
    album: 'Playlist · Local Networking',
    durationMs: 912000,
    positionMs: 185000,
    playbackState: 'PAUSED',
    // YouTube omits ACTION_STOP
    supportedActions: ['play', 'pause', 'next', 'previous'],
    artworkUrl: null,
    trackIndex: 0,
    tracks: [
      {
        title: 'Building Zero-Cloud Local Networks with mDNS & WebSockets',
        artist: 'Systems Engineering Lab',
        album: 'Playlist · Local Networking',
        durationMs: 912000,
        artworkUrl: null,
      },
      {
        title: 'Android MediaSessionManager Deep Dive',
        artist: 'Android Internals',
        album: 'Playlist · Local Networking',
        durationMs: 640000,
        artworkUrl: null,
      },
    ],
  },
  {
    sessionId: 'org.videolan.vlc:3011',
    packageName: 'org.videolan.vlc',
    appName: 'VLC',
    title: 'Concert_4K_FLAC_Master.mkv',
    artist: 'Local Storage · /Movies',
    album: 'VLC Media Player',
    durationMs: 1840000,
    positionMs: 420000,
    playbackState: 'PAUSED',
    // VLC exposes ACTION_STOP
    supportedActions: ['play', 'pause', 'stop', 'next', 'previous'],
    artworkUrl: '/src/assets/images/album_art_acoustic_1795257544586.jpg',
    trackIndex: 0,
    tracks: [
      {
        title: 'Concert_4K_FLAC_Master.mkv',
        artist: 'Local Storage · /Movies',
        album: 'VLC Media Player',
        durationMs: 1840000,
        artworkUrl: '/src/assets/images/album_art_acoustic_1795257544586.jpg',
      },
      {
        title: 'Studio_Session_24bit.flac',
        artist: 'Echostream',
        album: 'VLC Audio Library',
        durationMs: 315000,
        artworkUrl: '/src/assets/images/album_art_ambient_1795257543936.jpg',
      },
    ],
  },
  {
    sessionId: 'com.android.music:4005',
    packageName: 'com.android.music',
    appName: 'Local Music',
    title: 'Linen Shadows (24-bit FLAC)',
    artist: 'Guild Acoustic',
    album: 'Local Library',
    durationMs: 212000,
    positionMs: 28000,
    playbackState: 'PAUSED',
    supportedActions: ['play', 'pause', 'stop', 'next', 'previous'],
    artworkUrl: '/src/assets/images/album_art_acoustic_1795257544586.jpg',
    trackIndex: 0,
    tracks: [
      {
        title: 'Linen Shadows (24-bit FLAC)',
        artist: 'Guild Acoustic',
        album: 'Local Library',
        durationMs: 212000,
        artworkUrl: '/src/assets/images/album_art_acoustic_1795257544586.jpg',
      },
    ],
  },
];

let selectedSessionId = mediaSessions[0].sessionId;
let volumePercent = 60;
const volumeMax = 15;

function loadArtworkBase64(artworkUrl: string | null): string | null {
  if (!artworkUrl) return null;
  const diskPath = path.join(ROOT_DIR, artworkUrl.replace(/^\//, ''));
  if (!fs.existsSync(diskPath)) return null;
  return fs.readFileSync(diskPath).toString('base64');
}

function buildStatePayload() {
  const active = mediaSessions.find((s) => s.sessionId === selectedSessionId) ?? mediaSessions[0];
  const artworkBase64 = loadArtworkBase64(active.artworkUrl);
  const artworkHash = artworkBase64
    ? crypto.createHash('sha256').update(artworkBase64).digest('hex').slice(0, 16)
    : null;

  const payload = {
    deviceId: DEVICE_ID,
    deviceName: DEVICE_NAME,
    sessionId: active.sessionId,
    packageName: active.packageName,
    appName: active.appName,
    appIconB64: APP_ICONS[active.packageName] ?? null,
    title: active.title,
    artist: active.artist,
    album: active.album,
    durationMs: active.durationMs,
    positionMs: active.positionMs,
    playbackState: active.playbackState,
    isLastPlayed: false,
    supportedActions: active.supportedActions,
    artworkBase64,
    artworkHash,
    volume: {
      current: Math.round((volumePercent / 100) * volumeMax),
      max: volumeMax,
      percent: volumePercent,
      isFixed: false,
    },
    sessions: mediaSessions.map((s) => ({
      sessionId: s.sessionId,
      packageName: s.packageName,
      appName: s.appName,
      appIconB64: APP_ICONS[s.packageName] ?? null,
      title: s.title,
      playbackState: s.playbackState,
      isSelected: s.sessionId === active.sessionId,
    })),
  };
  return payload;
}

function listMonorepoFiles(dir: string, base = ''): Array<{ path: string; size: number; category: string }> {
  const results: Array<{ path: string; size: number; category: string }> = [];
  if (!fs.existsSync(dir)) return results;
  const entries = fs.readdirSync(dir, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name));
  for (const entry of entries) {
    const rel = base ? `${base}/${entry.name}` : entry.name;
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      results.push(...listMonorepoFiles(full, rel));
    } else {
      const stat = fs.statSync(full);
      const top = rel.split('/')[0] || 'root';
      results.push({ path: rel, size: stat.size, category: top });
    }
  }
  return results;
}

interface WsClientConn {
  ws: WebSocket;
  clientId: string | null;
  clientName: string | null;
  authenticated: boolean;
  lastInboundSeq: number;
  seenNonces: Set<string>;
  outboundSeq: number;
  pendingServerNonce: string | null;
  pendingSessionSalt: string | null;
  sessionKey: Buffer | null;
}

async function startServer() {
  const app = express();
  app.use(express.json());

  const server = http.createServer(app);
  const wss = new WebSocketServer({ server, path: '/ws/mediaremote' });
  const connections = new Set<WsClientConn>();

  const wireSubscribers = new Set<express.Response>();

  function emitWireLog(direction: 'inbound' | 'outbound', env: Record<string, any>) {
    const summary = (() => {
      switch (env.type) {
        case 'hello':
          return `hello from ${env.data?.clientName ?? env.data?.clientId}`;
        case 'hello_ack':
          return `v${env.data?.selectedVersion} negotiated · requiresPairing=${String(env.data?.requiresPairing)}`;
        case 'pair_request':
          return `pair_request (${env.data?.clientName ?? env.data?.clientId})`;
        case 'pair_ack':
          return `Issued deviceSecret (fp: ${env.data?.keyFingerprint})`;
        case 'auth_challenge':
          return 'Issued fresh serverNonce challenge';
        case 'auth_response':
          return `HMAC proof submitted for ${env.data?.clientId}`;
        case 'auth_ok':
          return 'HMAC verified · Session authenticated';
        case 'command':
          return `command (${env.data?.action}${env.data?.volume !== undefined ? ` ${env.data.volume}%` : ''})`;
        case 'ack':
          return `${env.data?.action} -> ${env.data?.status}${env.data?.reason ? ` (${env.data.reason})` : ''}`;
        case 'state':
          return `${env.data?.appName ?? 'Idle'} · ${env.data?.playbackState} · actions=[${(env.data?.supportedActions ?? []).join(', ')}]`;
        case 'error':
          return `${env.data?.code}: ${env.data?.message}`;
        case 'ping':
          return 'Heartbeat ping';
        case 'pong':
          return `Heartbeat RTT ${Math.max(0, Date.now() - (env.data?.echoSentAt ?? Date.now()))}ms`;
        default:
          return env.type;
      }
    })();

    const payload = JSON.stringify({
      uid: `${Date.now()}-${Math.random().toString(36).slice(2, 7)}`,
      ts: Date.now(),
      direction,
      type: env.type,
      seq: env.seq,
      summary,
      raw: JSON.stringify(env, null, 2),
    });
    for (const res of wireSubscribers) {
      res.write(`data: ${payload}\n\n`);
    }
  }

  function sendEnvelope(conn: WsClientConn, type: string, data: Record<string, unknown>) {
    if (conn.ws.readyState !== WebSocket.OPEN) return;
    const env = {
      v: 1,
      type,
      id: `srv-${crypto.randomUUID().slice(0, 8)}`,
      seq: conn.outboundSeq++,
      ts: Date.now(),
      nonce: crypto.randomBytes(12).toString('base64'),
      data,
    };
    if (type === 'state' && !validators.state(data)) {
      console.error('State schema validation error:', validators.state.errors);
    }
    emitWireLog('outbound', env);
    conn.ws.send(JSON.stringify(env));
  }

  function broadcastState() {
    const state = buildStatePayload();
    for (const conn of connections) {
      if (conn.authenticated) {
        sendEnvelope(conn, 'state', state);
      }
    }
  }

  wss.on('connection', (ws) => {
    const conn: WsClientConn = {
      ws,
      clientId: null,
      clientName: null,
      authenticated: false,
      lastInboundSeq: -1,
      seenNonces: new Set(),
      outboundSeq: 1,
      pendingServerNonce: null,
      pendingSessionSalt: null,
      sessionKey: null,
    };
    connections.add(conn);

    ws.on('message', (raw) => {
      let parsedRaw: Record<string, any>;
      try {
        parsedRaw = JSON.parse(raw.toString('utf8'));
      } catch {
        sendEnvelope(conn, 'error', {
          requestId: null,
          code: 'INVALID_SCHEMA',
          message: 'Malformed JSON frame',
          recoverable: true,
        });
        return;
      }

      if (!validators.envelope(parsedRaw)) {
        const badEnv = parsedRaw as Record<string, any>;
        const isVerErr = badEnv && badEnv.v !== 1;
        sendEnvelope(conn, 'error', {
          requestId: badEnv?.id ?? null,
          code: isVerErr ? 'UNSUPPORTED_VERSION' : 'INVALID_SCHEMA',
          message: isVerErr
            ? `Protocol version ${badEnv?.v} is unsupported; expected v1`
            : `Schema violation: ${ajv.errorsText(validators.envelope.errors)}`,
          recoverable: !isVerErr,
        });
        return;
      }

      const env = parsedRaw as Record<string, any>;
      emitWireLog('inbound', env);

      // Enforce replay protection on every frame post-hello
      if (env.type !== 'hello') {
        const now = Date.now();
        if (env.seq <= conn.lastInboundSeq || Math.abs(now - env.ts) > 30_000) {
          sendEnvelope(conn, 'error', {
            requestId: env.id,
            code: 'REPLAY_DETECTED',
            message: `Replay protection rejected seq=${env.seq} (last=${conn.lastInboundSeq})`,
            recoverable: false,
          });
          return;
        }
        if (env.nonce && conn.seenNonces.has(env.nonce)) {
          sendEnvelope(conn, 'error', {
            requestId: env.id,
            code: 'REPLAY_DETECTED',
            message: 'Duplicate nonce rejected',
            recoverable: false,
          });
          return;
        }
        if (env.nonce) conn.seenNonces.add(env.nonce);
        conn.lastInboundSeq = env.seq;
      }

      switch (env.type) {
        case 'hello': {
          if (!validators.hello(env.data)) {
            sendEnvelope(conn, 'error', {
              requestId: env.id,
              code: 'INVALID_SCHEMA',
              message: `Invalid hello payload: ${ajv.errorsText(validators.hello.errors)}`,
              recoverable: false,
            });
            return;
          }
          const { clientId, clientName, supportedVersions } = env.data as {
            clientId: string;
            clientName: string;
            supportedVersions: number[];
          };
          if (!Array.isArray(supportedVersions) || !supportedVersions.includes(1)) {
            sendEnvelope(conn, 'error', {
              requestId: env.id,
              code: 'UNSUPPORTED_VERSION',
              message: 'Requires MediaRemote protocol v1',
              recoverable: false,
            });
            ws.close();
            return;
          }
          conn.authenticated = false;
          conn.pendingServerNonce = null;
          conn.pendingSessionSalt = null;
          conn.clientId = clientId;
          conn.clientName = clientName;
          const existing = pairedClients.get(clientId);
          sendEnvelope(conn, 'hello_ack', {
            selectedVersion: 1,
            deviceId: DEVICE_ID,
            deviceName: DEVICE_NAME,
            requiresPairing: !existing,
            serverTime: Date.now(),
          });
          if (existing) {
            conn.pendingServerNonce = crypto.randomBytes(24).toString('base64');
            conn.pendingSessionSalt = crypto.randomBytes(24).toString('base64');
            sendEnvelope(conn, 'auth_challenge', {
              serverNonce: conn.pendingServerNonce,
              sessionSalt: conn.pendingSessionSalt,
            });
          }
          break;
        }

        case 'pair_request': {
          if (conn.authenticated) {
            sendEnvelope(conn, 'error', {
              requestId: env.id,
              code: 'UNAUTHENTICATED',
              message: 'Session is already authenticated',
              recoverable: false,
            });
            return;
          }
          if (!validators.pairRequest(env.data)) {
            sendEnvelope(conn, 'error', {
              requestId: env.id,
              code: 'INVALID_SCHEMA',
              message: `Invalid pair_request payload: ${ajv.errorsText(validators.pairRequest.errors)}`,
              recoverable: false,
            });
            return;
          }
          const { clientId, clientName, pairingToken } = env.data as {
            clientId: string;
            clientName: string;
            pairingToken: string;
          };
          if (conn.clientId && clientId !== conn.clientId) {
            sendEnvelope(conn, 'error', {
              requestId: env.id,
              code: 'UNAUTHENTICATED',
              message: 'pair_request clientId does not match hello',
              recoverable: false,
            });
            ws.close(1008, 'Client ID mismatch');
            return;
          }
          if (
            !activePairingToken ||
            activePairingToken.consumed ||
            Date.now() > activePairingToken.expiresAt
          ) {
            sendEnvelope(conn, 'error', {
              requestId: env.id,
              code: 'PAIRING_EXPIRED',
              message: 'One-time QR pairing token has expired or was not issued',
              recoverable: false,
            });
            return;
          }
          if (pairingToken !== activePairingToken.token) {
            sendEnvelope(conn, 'error', {
              requestId: env.id,
              code: 'INVALID_TOKEN',
              message: 'Invalid one-time pairing token',
              recoverable: false,
            });
            return;
          }
          activePairingToken.consumed = true;

          const deviceSecretBuf = crypto.randomBytes(32);
          const deviceSecretB64 = deviceSecretBuf.toString('base64');
          const fingerprint = crypto
            .createHash('sha256')
            .update(deviceSecretBuf)
            .digest('hex')
            .slice(0, 16);

          pairedClients.set(clientId, {
            clientId,
            clientName,
            sharedSecret: deviceSecretBuf,
            fingerprint,
            pairedAt: Date.now(),
          });
          conn.clientId = clientId;
          conn.clientName = clientName;

          sendEnvelope(conn, 'pair_ack', {
            deviceId: DEVICE_ID,
            deviceName: DEVICE_NAME,
            deviceSecret: deviceSecretB64,
            keyFingerprint: fingerprint,
          });

          conn.pendingServerNonce = crypto.randomBytes(24).toString('base64');
          conn.pendingSessionSalt = crypto.randomBytes(24).toString('base64');
          sendEnvelope(conn, 'auth_challenge', {
            serverNonce: conn.pendingServerNonce,
            sessionSalt: conn.pendingSessionSalt,
          });
          break;
        }

        case 'auth_response': {
          if (conn.authenticated) {
            conn.pendingServerNonce = null;
            conn.pendingSessionSalt = null;
            sendEnvelope(conn, 'error', {
              requestId: env.id,
              code: 'UNAUTHENTICATED',
              message: 'Session is already authenticated',
              recoverable: false,
            });
            return;
          }

          // Consume single-use challenge immediately on any auth_response attempt
          const serverNonce = conn.pendingServerNonce;
          conn.pendingServerNonce = null;
          conn.pendingSessionSalt = null;

          if (!validators.authResponse(env.data)) {
            sendEnvelope(conn, 'error', {
              requestId: env.id,
              code: 'INVALID_SCHEMA',
              message: `Invalid auth_response payload: ${ajv.errorsText(validators.authResponse.errors)}`,
              recoverable: false,
            });
            ws.close(1008, 'Invalid auth_response');
            return;
          }

          const { clientId, clientNonce, proofHmac } = env.data as {
            clientId: string;
            clientNonce: string;
            proofHmac: string;
          };
          if (!conn.clientId || clientId !== conn.clientId) {
            sendEnvelope(conn, 'error', {
              requestId: env.id,
              code: 'UNAUTHENTICATED',
              message: 'clientId differs from hello/pair_request',
              recoverable: false,
            });
            ws.close(1008, 'Client ID mismatch');
            return;
          }

          if (!serverNonce) {
            sendEnvelope(conn, 'error', {
              requestId: env.id,
              code: 'UNAUTHENTICATED',
              message: 'No active authentication challenge',
              recoverable: false,
            });
            ws.close(1008, 'Missing challenge');
            return;
          }

          const record = pairedClients.get(clientId);
          if (!record) {
            sendEnvelope(conn, 'error', {
              requestId: env.id,
              code: 'DEVICE_REVOKED',
              message: 'Client is not paired or has been revoked',
              recoverable: false,
            });
            ws.close(1008, 'Not paired');
            return;
          }

          const expectedProof = crypto
            .createHmac('sha256', record.sharedSecret)
            .update(`${serverNonce}:${clientNonce}:${clientId}`)
            .digest();
          const actualProof = Buffer.from(String(proofHmac), 'base64');

          if (
            actualProof.length !== expectedProof.length ||
            !crypto.timingSafeEqual(expectedProof, actualProof)
          ) {
            sendEnvelope(conn, 'error', {
              requestId: env.id,
              code: 'UNAUTHENTICATED',
              message: 'HMAC challenge-response verification failed',
              recoverable: false,
            });
            ws.close(1008, 'Auth failed');
            return;
          }

          conn.authenticated = true;
          const serverProof = crypto
            .createHmac('sha256', record.sharedSecret)
            .update(`${clientNonce}:${serverNonce}:${DEVICE_ID}`)
            .digest('base64');

          sendEnvelope(conn, 'auth_ok', {
            clientId,
            serverProofHmac: serverProof,
            encryptionEnabled: false,
          });
          sendEnvelope(conn, 'state', buildStatePayload());
          break;
        }

        case 'ping': {
          if (!validators.ping(env.data)) {
            sendEnvelope(conn, 'error', {
              requestId: env.id,
              code: 'INVALID_SCHEMA',
              message: `Invalid ping payload: ${ajv.errorsText(validators.ping.errors)}`,
              recoverable: true,
            });
            return;
          }
          const { sentAt } = env.data as { sentAt: number };
          sendEnvelope(conn, 'pong', {
            echoSentAt: sentAt,
            serverTime: Date.now(),
          });
          break;
        }

        case 'sync_request': {
          if (!conn.authenticated) {
            sendEnvelope(conn, 'error', {
              requestId: env.id,
              code: 'UNAUTHENTICATED',
              message: 'Authentication required before sync_request',
              recoverable: false,
            });
            return;
          }
          if (!validators.syncRequest(env.data)) {
            sendEnvelope(conn, 'error', {
              requestId: env.id,
              code: 'INVALID_SCHEMA',
              message: `Invalid sync_request payload: ${ajv.errorsText(validators.syncRequest.errors)}`,
              recoverable: true,
            });
            return;
          }
          sendEnvelope(conn, 'state', buildStatePayload());
          break;
        }

        case 'command': {
          if (!conn.authenticated) {
            sendEnvelope(conn, 'error', {
              requestId: env.id,
              code: 'UNAUTHENTICATED',
              message: 'Unauthenticated command blocked by Android receiver',
              recoverable: false,
            });
            return;
          }
          if (!validators.command(env.data)) {
            sendEnvelope(conn, 'error', {
              requestId: env.id,
              code: 'INVALID_SCHEMA',
              message: ajv.errorsText(validators.command.errors),
              recoverable: true,
            });
            return;
          }

          const { action, targetSessionId, volume } = env.data as {
            action: 'play' | 'pause' | 'stop' | 'next' | 'previous' | 'set_volume' | 'select_session';
            targetSessionId?: string;
            volume?: number;
          };
          if (action === 'select_session') {
            const target = mediaSessions.find((s) => s.sessionId === targetSessionId);
            if (!target) {
              sendEnvelope(conn, 'ack', { requestId: env.id, action, status: 'rejected', reason: 'Session not found' });
              sendEnvelope(conn, 'error', {
                requestId: env.id,
                code: 'SESSION_NOT_FOUND',
                message: `Session ${targetSessionId} is no longer active`,
                recoverable: true,
              });
              return;
            }
            selectedSessionId = target.sessionId;
            sendEnvelope(conn, 'ack', { requestId: env.id, action, status: 'accepted', reason: null });
            broadcastState();
            return;
          }

          if (action === 'set_volume') {
            volumePercent = Math.max(0, Math.min(100, Number(volume)));
            sendEnvelope(conn, 'ack', { requestId: env.id, action, status: 'accepted', reason: null });
            broadcastState();
            return;
          }

          const active = mediaSessions.find((s) => s.sessionId === selectedSessionId) ?? mediaSessions[0];
          if (!active.supportedActions.includes(action)) {
            const reason = `${active.appName} (${active.packageName}) does not expose ACTION_${action.toUpperCase()} in PlaybackState`;
            sendEnvelope(conn, 'ack', { requestId: env.id, action, status: 'rejected', reason });
            sendEnvelope(conn, 'error', {
              requestId: env.id,
              code: 'UNSUPPORTED_ACTION',
              message: reason,
              recoverable: true,
            });
            return;
          }

          // Accept first, then asynchronously simulate MediaController.Callback state transition
          sendEnvelope(conn, 'ack', { requestId: env.id, action, status: 'accepted', reason: null });
          setTimeout(() => {
            if (action === 'play') active.playbackState = 'PLAYING';
            else if (action === 'pause') active.playbackState = 'PAUSED';
            else if (action === 'stop') {
              active.playbackState = 'STOPPED';
              active.positionMs = 0;
            } else if (action === 'next' || action === 'previous') {
              const delta = action === 'next' ? 1 : -1;
              active.trackIndex = (active.trackIndex + delta + active.tracks.length) % active.tracks.length;
              const nextTrack = active.tracks[active.trackIndex];
              active.title = nextTrack.title;
              active.artist = nextTrack.artist;
              active.album = nextTrack.album;
              active.durationMs = nextTrack.durationMs;
              active.artworkUrl = nextTrack.artworkUrl;
              active.positionMs = 0;
            }
            broadcastState();
          }, 90);
          break;
        }
      }
    });

    ws.on('close', () => {
      connections.delete(conn);
    });
  });

  // REST API for Monorepo Inspection, ZIP Export, QR Pairing Issuance, & Revocation
  app.get('/api/monorepo/tree', (_req, res) => {
    res.json({ files: listMonorepoFiles(MONOREPO_DIR) });
  });

  app.get('/api/monorepo/file', (req, res) => {
    const rel = String(req.query.path ?? '');
    const resolved = path.resolve(MONOREPO_DIR, rel);
    if (!resolved.startsWith(MONOREPO_DIR) || !fs.existsSync(resolved) || fs.statSync(resolved).isDirectory()) {
      res.status(404).json({ error: 'File not found' });
      return;
    }
    res.json({ path: rel, content: fs.readFileSync(resolved, 'utf8') });
  });

  app.get('/api/export-zip', (_req, res) => {
    res.setHeader('Content-Type', 'application/zip');
    res.setHeader('Content-Disposition', 'attachment; filename="media-remote-monorepo.zip"');
    const archive = new ZipArchive({ zlib: { level: 9 } });
    archive.on('error', (err: Error) => res.status(500).send(err.message));
    archive.pipe(res);
    archive.directory(MONOREPO_DIR, 'media-remote');
    archive.finalize();
  });

  // Serve exact Android static web remote assets at /remote/
  const webAssetsDir = path.join(MONOREPO_DIR, 'android', 'app', 'src', 'main', 'assets', 'web');
  app.use(
    '/remote',
    express.static(webAssetsDir, {
      setHeaders: (res, filePath) => {
        if (filePath.endsWith('.webmanifest')) {
          res.setHeader('Content-Type', 'application/manifest+json');
        }
      },
    })
  );

  app.get('/api/receiver/wire-events', (req, res) => {
    res.setHeader('Content-Type', 'text/event-stream');
    res.setHeader('Cache-Control', 'no-cache');
    res.setHeader('Connection', 'keep-alive');
    res.flushHeaders();
    wireSubscribers.add(res);
    req.on('close', () => {
      wireSubscribers.delete(res);
    });
  });

  app.post('/api/receiver/pairing-qr', async (_req, res) => {
    const token = crypto.randomBytes(24).toString('base64url');
    const expiresAt = Date.now() + 120_000;
    activePairingToken = { token, expiresAt, consumed: false };
    const url = `http://192.168.1.42:8765/?pair=${encodeURIComponent(token)}`;
    const payload = {
      url,
      deviceId: DEVICE_ID,
      deviceName: DEVICE_NAME,
      ip: '192.168.1.42',
      port: 8765,
      pairingToken: token,
      expiresAt,
    };
    const qrDataUrl = await QRCode.toDataURL(url, { width: 220, margin: 1 });
    res.json({ payload, qrDataUrl });
  });

  app.get('/api/receiver/paired-clients', (_req, res) => {
    const list = Array.from(pairedClients.values()).map((c) => ({
      clientId: c.clientId,
      clientName: c.clientName,
      fingerprint: c.fingerprint,
      pairedAt: c.pairedAt,
    }));
    res.json({ clients: list });
  });

  app.post('/api/receiver/revoke/:clientId', (req, res) => {
    const { clientId } = req.params;
    pairedClients.delete(clientId);
    for (const conn of connections) {
      if (conn.clientId === clientId) {
        conn.authenticated = false;
        conn.pendingServerNonce = null;
        conn.pendingSessionSalt = null;
        sendEnvelope(conn, 'error', {
          requestId: null,
          code: 'DEVICE_REVOKED',
          message: 'Device pairing revoked by Android receiver host',
          recoverable: false,
        });
        conn.ws.close(1008, 'Revoked');
      }
    }
    res.json({ revoked: clientId });
  });

  app.post('/api/receiver/switch-session', (req, res) => {
    const { sessionId } = req.body as { sessionId?: string };
    if (sessionId && mediaSessions.some((s) => s.sessionId === sessionId)) {
      selectedSessionId = sessionId;
      broadcastState();
    }
    res.json({ state: buildStatePayload() });
  });

  app.get('/api/receiver/state', (_req, res) => {
    res.json({ state: buildStatePayload() });
  });

  // Mount Vite middleware in dev, or serve dist in production
  if (process.env.NODE_ENV !== 'production') {
    const vite = await createViteServer({
      server: { middlewareMode: true },
      appType: 'spa',
    });
    app.use(vite.middlewares);
  } else {
    app.use(express.static(path.join(ROOT_DIR, 'dist')));
    app.get('*', (_req, res) => {
      res.sendFile(path.join(ROOT_DIR, 'dist', 'index.html'));
    });
  }

  server.listen(PORT, '0.0.0.0', () => {
    console.log(`LocalCast Remote workbench listening on http://0.0.0.0:${PORT}`);
  });
}

startServer();
