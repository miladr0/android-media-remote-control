import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';

const rootDir = path.resolve(import.meta.dirname, '..', '..');
const htmlPath = path.join(rootDir, 'media-remote/android/app/src/main/assets/web/index.html');
const fixturesDir = path.join(rootDir, 'media-remote/shared/fixtures');

export function loadIndexHtmlCore() {
  const html = fs.readFileSync(htmlPath, 'utf8');
  const match = html.match(/<script id="localcast-protocol-core">([\s\S]*?)<\/script>/);
  if (!match) {
    throw new Error('Could not find <script id="localcast-protocol-core"> in assets/web/index.html');
  }
  const sandbox = {
    TextEncoder,
    atob: (s) => Buffer.from(s, 'base64').toString('binary'),
    btoa: (s) => Buffer.from(s, 'binary').toString('base64'),
    Uint8Array,
    Int32Array,
    DataView,
    Math,
    String,
    Object,
    crypto: {
      getRandomValues: (arr) => crypto.randomFillSync(arr),
    },
  };
  sandbox.globalThis = sandbox;
  sandbox.window = sandbox;
  vm.runInNewContext(match[1], sandbox, { filename: 'index.html#localcast-protocol-core' });
  return sandbox.__LOCALCAST_CORE__;
}

export function buildAllFixturesFromHtmlCore(core = loadIndexHtmlCore()) {
  const fixedTs = 1763712000000;
  const fixedClientId = 'web-ab12cd34';
  const fixedClientName = 'iPhone Safari';
  const fixedNonce = 'AQIDBAUGBwgJCgsM'; // 12 bytes base64
  const fixedSecretB64 = 'AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8='; // 32 bytes base64
  const fixedServerNonce = 'U2VydmVyTm9uY2UxMjM0NTY3ODkwMTIzNA==';
  const fixedClientNonce = 'Q2xpZW50Tm9uY2UxMjM0NTY3OA==';

  const frames = {
    hello: core.buildEnvelope(
      'hello',
      core.buildHelloPayload(fixedClientId, fixedClientName),
      0,
      fixedTs,
      'req-hello001',
      fixedNonce
    ),
    pair_request: core.buildEnvelope(
      'pair_request',
      core.buildPairRequestPayload(fixedClientId, fixedClientName, 'tok_0123456789abcdef'),
      1,
      fixedTs + 100,
      'req-pair0001',
      'AgMEBQYHCAkKCwwN'
    ),
    auth_response: core.buildEnvelope(
      'auth_response',
      core.buildAuthResponsePayload(fixedClientId, fixedServerNonce, fixedClientNonce, fixedSecretB64),
      2,
      fixedTs + 200,
      'req-auth0001',
      'AwQFBgcICQoLDA0O'
    ),
    command_play: core.buildEnvelope(
      'command',
      core.buildCommandPayload('play'),
      3,
      fixedTs + 300,
      'req-cmd00001',
      'BAUGBwgJCgsMDQ4P'
    ),
    command_pause: core.buildEnvelope(
      'command',
      core.buildCommandPayload('pause'),
      4,
      fixedTs + 400,
      'req-cmd00002',
      'BQYHCAkKCwwNDg8Q'
    ),
    command_stop: core.buildEnvelope(
      'command',
      core.buildCommandPayload('stop'),
      5,
      fixedTs + 500,
      'req-cmd00003',
      'BgcICQoLDA0ODxAR'
    ),
    command_next: core.buildEnvelope(
      'command',
      core.buildCommandPayload('next'),
      6,
      fixedTs + 600,
      'req-cmd00004',
      'BwgJCgsMDQ4PEBES'
    ),
    command_previous: core.buildEnvelope(
      'command',
      core.buildCommandPayload('previous'),
      7,
      fixedTs + 700,
      'req-cmd00005',
      'CAkKCwwNDg8QERIT'
    ),
    command_set_volume: core.buildEnvelope(
      'command',
      core.buildCommandPayload('set_volume', { volume: 65 }),
      8,
      fixedTs + 800,
      'req-cmd00006',
      'CQoLDA0ODxAREhMU'
    ),
    command_select_session: core.buildEnvelope(
      'command',
      core.buildCommandPayload('select_session', { targetSessionId: 'com.spotify.music:1042' }),
      9,
      fixedTs + 900,
      'req-cmd00007',
      'CgsMDQ4PEBESExQV'
    ),
    ping: core.buildEnvelope(
      'ping',
      core.buildPingPayload(fixedTs + 1000),
      10,
      fixedTs + 1000,
      'req-ping0001',
      'CwwNDg8QERITFBUW'
    ),
    sync_request: core.buildEnvelope(
      'sync_request',
      core.buildSyncRequestPayload('initial'),
      11,
      fixedTs + 1100,
      'req-sync0001',
      'DA0ODxAREhMUFRYX'
    ),
  };

  const rawVectors = [
    {
      name: 'auth_response_standard',
      secretBase64: fixedSecretB64,
      message: `${fixedServerNonce}:${fixedClientNonce}:${fixedClientId}`,
    },
    {
      name: 'empty_message',
      secretBase64: Buffer.from('simple-local-secret-key-32-bytes!').toString('base64'),
      message: '',
    },
    {
      name: 'long_key_over_64_bytes',
      secretBase64: Buffer.alloc(80, 0x5a).toString('base64'),
      message: 'long-key:nonce-abc:web-99887766',
    },
    {
      name: 'utf8_device_message',
      secretBase64: crypto.createHash('sha256').update('xiaomi-test-seed').digest().toString('base64'),
      message: 'nonce+1/=:cNonce==:Xiaomi-14-Pro-🎵',
    },
  ];

  const hmac_vectors = rawVectors.map((v) => {
    const jsHex = core.hmacSha256Hex(v.secretBase64, v.message);
    const jsB64 = core.hmacSha256Base64(v.secretBase64, v.message);
    const nodeHmac = crypto
      .createHmac('sha256', Buffer.from(v.secretBase64, 'base64'))
      .update(v.message, 'utf8');
    const nodeHex = nodeHmac.digest('hex');
    const nodeB64 = Buffer.from(nodeHex, 'hex').toString('base64');
    assert.equal(jsHex, nodeHex, `Hex mismatch for vector ${v.name}`);
    assert.equal(jsB64, nodeB64, `Base64 mismatch for vector ${v.name}`);
    return {
      name: v.name,
      secretBase64: v.secretBase64,
      message: v.message,
      expectedHmacHex: jsHex,
      expectedHmacBase64: jsB64,
    };
  });

  return { frames, hmac_vectors };
}

if (import.meta.url === `file://${process.argv[1]}`) {
  fs.mkdirSync(fixturesDir, { recursive: true });
  const { frames, hmac_vectors } = buildAllFixturesFromHtmlCore();
  for (const [name, payload] of Object.entries(frames)) {
    fs.writeFileSync(path.join(fixturesDir, `${name}.json`), JSON.stringify(payload, null, 2) + '\n');
  }
  fs.writeFileSync(
    path.join(fixturesDir, 'hmac_vectors.json'),
    JSON.stringify({ vectors: hmac_vectors }, null, 2) + '\n'
  );
  console.log(`Generated ${Object.keys(frames).length} frame fixtures + ${hmac_vectors.length} HMAC vectors in ${fixturesDir}`);
}
