import test from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import Ajv from 'ajv';
import { loadIndexHtmlCore, buildAllFixturesFromHtmlCore } from './generate-fixtures.mjs';

const rootDir = path.resolve(import.meta.dirname, '..', '..');
const schemasDir = path.join(rootDir, 'media-remote/shared/schemas');
const fixturesDir = path.join(rootDir, 'media-remote/shared/fixtures');
const webHtmlPath = path.join(rootDir, 'media-remote/android/app/src/main/assets/web/index.html');

const ajv = new Ajv({ allErrors: true, strict: false });
const loadSchema = (name) => JSON.parse(fs.readFileSync(path.join(schemasDir, name), 'utf8'));

const helloSchema = loadSchema('hello.schema.json');
const authSchema = loadSchema('auth.schema.json');
const heartbeatSchema = loadSchema('heartbeat.schema.json');

const validateEnvelope = ajv.compile(loadSchema('envelope.schema.json'));
const validateHello = ajv.compile(helloSchema.oneOf[0]);
const validateHelloAck = ajv.compile(helloSchema.oneOf[1]);
const validatePairRequest = ajv.compile(authSchema.oneOf[0]);
const validatePairAck = ajv.compile(authSchema.oneOf[1]);
const validateAuthResponse = ajv.compile(authSchema.oneOf[3]);
const validatePing = ajv.compile(heartbeatSchema.oneOf[0]);
const validateSyncRequest = ajv.compile(heartbeatSchema.oneOf[2]);
const validateCommand = ajv.compile(loadSchema('command.schema.json'));
const validateState = ajv.compile(loadSchema('state.schema.json'));
const validatePairingQr = ajv.compile(loadSchema('pairing-qr.schema.json'));
const validateAck = ajv.compile(loadSchema('ack.schema.json'));
const validateError = ajv.compile(loadSchema('error.schema.json'));

test('Shared JSON schemas validate canonical envelope, hello_ack serverTime, strict auth, command, state, ack, error, and reject unknown keys', () => {
  const now = Date.now();
  const env = {
    v: 1,
    type: 'command',
    id: 'req-00000001',
    seq: 1,
    ts: now,
    nonce: 'nonce-1234567890',
    data: { action: 'play' },
  };
  assert.equal(validateEnvelope(env), true);
  assert.equal(validateCommand(env.data), true);
  assert.equal(validateCommand({ action: 'play', extraKey: 'bad' }), false);
  assert.equal(validateEnvelope({ ...env, extraEnvelopeKey: true }), false);

  assert.equal(
    validateHelloAck({
      selectedVersion: 1,
      deviceId: 'and-xiaomi14',
      deviceName: 'Xiaomi 14',
      requiresPairing: false,
      serverTime: now,
    }),
    true
  );
  // Missing serverTime or unknown keys must fail
  assert.equal(
    validateHelloAck({
      selectedVersion: 1,
      deviceId: 'and-xiaomi14',
      deviceName: 'Xiaomi 14',
      requiresPairing: false,
    }),
    false
  );

  assert.equal(
    validatePairRequest({
      clientId: 'web-a1b2c3d4',
      clientName: 'iPhone Safari',
      pairingToken: 'tok_0123456789abcdef',
    }),
    true
  );

  assert.equal(
    validatePairAck({
      deviceId: 'and-xiaomi14',
      deviceName: 'Xiaomi 14',
      deviceSecret: crypto.randomBytes(32).toString('base64'),
      keyFingerprint: '0123456789abcdef',
    }),
    true
  );

  assert.equal(validateCommand({ action: 'set_volume' }), false);
  assert.equal(validateCommand({ action: 'set_volume', volume: 65 }), true);

  const statePayload = {
    deviceId: 'and-xiaomi14',
    deviceName: 'Xiaomi 14',
    sessionId: 'com.spotify.music:1',
    packageName: 'com.spotify.music',
    appName: 'Spotify',
    title: 'Midnight Signal',
    artist: 'Kiasmos',
    album: 'Blurred',
    durationMs: 245000,
    positionMs: 12000,
    playbackState: 'PLAYING',
    supportedActions: ['play', 'pause', 'next', 'previous'],
    artworkBase64: null,
    artworkHash: null,
    volume: { current: 9, max: 15, percent: 60, isFixed: false },
    sessions: [
      {
        sessionId: 'com.spotify.music:1',
        packageName: 'com.spotify.music',
        appName: 'Spotify',
        title: 'Midnight Signal',
        playbackState: 'PLAYING',
        isSelected: true,
      },
    ],
  };
  assert.equal(validateState(statePayload), true);

  assert.equal(
    validatePairingQr({
      url: 'http://192.168.1.42:8765/?pair=tok_0123456789abcdef',
      deviceId: 'and-xiaomi14',
      deviceName: 'Xiaomi 14',
      ip: '192.168.1.42',
      port: 8765,
      pairingToken: 'tok_0123456789abcdef',
      expiresAt: now + 120000,
    }),
    true
  );

  assert.equal(validateAck({ requestId: 'req-00000001', action: 'play', status: 'accepted' }), true);
  assert.equal(
    validateError({
      requestId: 'req-00000001',
      code: 'UNSUPPORTED_ACTION',
      message: 'Spotify does not expose ACTION_STOP',
      recoverable: true,
    }),
    true
  );
});

test('assets/web/index.html pure-JS HMAC-SHA256 matches fixed test vectors and Node crypto', () => {
  const core = loadIndexHtmlCore();
  const vectorFile = JSON.parse(fs.readFileSync(path.join(fixturesDir, 'hmac_vectors.json'), 'utf8'));
  assert.ok(Array.isArray(vectorFile.vectors) && vectorFile.vectors.length >= 3);

  for (const vec of vectorFile.vectors) {
    const jsHex = core.hmacSha256Hex(vec.secretBase64, vec.message);
    const jsB64 = core.hmacSha256Base64(vec.secretBase64, vec.message);
    const nodeHex = crypto
      .createHmac('sha256', Buffer.from(vec.secretBase64, 'base64'))
      .update(vec.message, 'utf8')
      .digest('hex');
    const nodeB64 = Buffer.from(nodeHex, 'hex').toString('base64');

    assert.equal(jsHex, vec.expectedHmacHex, `JS hex mismatch for ${vec.name}`);
    assert.equal(jsB64, vec.expectedHmacBase64, `JS base64 mismatch for ${vec.name}`);
    assert.equal(jsHex, nodeHex, `Node crypto hex mismatch for ${vec.name}`);
    assert.equal(jsB64, nodeB64, `Node crypto base64 mismatch for ${vec.name}`);
  }
});

test('shared/fixtures/*.json match exact frames emitted by index.html JS core and pass strict schemas', () => {
  const core = loadIndexHtmlCore();
  const { frames } = buildAllFixturesFromHtmlCore(core);

  const validatorsByType = {
    hello: validateHello,
    pair_request: validatePairRequest,
    auth_response: validateAuthResponse,
    command: validateCommand,
    ping: validatePing,
    sync_request: validateSyncRequest,
  };

  for (const [name, generatedFrame] of Object.entries(frames)) {
    const filePath = path.join(fixturesDir, `${name}.json`);
    assert.equal(fs.existsSync(filePath), true, `Missing fixture file ${name}.json`);
    const diskFrame = JSON.parse(fs.readFileSync(filePath, 'utf8'));
    assert.deepEqual(
      diskFrame,
      JSON.parse(JSON.stringify(generatedFrame)),
      `Fixture ${name}.json drifted from index.html JS code path`
    );

    assert.equal(validateEnvelope(diskFrame), true, `Envelope schema failed for ${name}.json`);
    const payloadValidator = validatorsByType[diskFrame.type];
    assert.ok(payloadValidator, `No payload validator mapped for type ${diskFrame.type}`);
    assert.equal(
      payloadValidator(diskFrame.data),
      true,
      `Payload schema failed for ${name}.json: ${ajv.errorsText(payloadValidator.errors)}`
    );
  }
});

test('assets/web/index.html avoids crypto.subtle, supports serverTime clock offset, and shows unpaired prompt on revocation', () => {
  const html = fs.readFileSync(webHtmlPath, 'utf8');
  assert.equal(html.includes('crypto.subtle'), false, 'index.html must not depend on crypto.subtle');
  assert.equal(html.includes('clockOffsetMs = env.data.serverTime - Date.now()'), true);
  assert.equal(html.includes('This device was unpaired - scan the QR again'), true);
});
