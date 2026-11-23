export type PlaybackState = 'NONE' | 'STOPPED' | 'PAUSED' | 'PLAYING' | 'BUFFERING' | 'ERROR';
export type TransportAction = 'play' | 'pause' | 'stop' | 'next' | 'previous';

export interface VolumeState {
  current: number;
  max: number;
  percent: number;
  isFixed: boolean;
}

export interface SessionSummary {
  sessionId: string;
  packageName: string;
  appName: string | null;
  appIconB64: string | null;
  title: string | null;
  playbackState: PlaybackState;
  isSelected: boolean;
}

export interface MediaStatePayload {
  deviceId: string;
  deviceName: string;
  sessionId: string | null;
  packageName: string | null;
  appName: string | null;
  appIconB64: string | null;
  title: string | null;
  artist: string | null;
  album: string | null;
  durationMs: number | null;
  positionMs: number | null;
  playbackState: PlaybackState;
  isLastPlayed: boolean;
  supportedActions: TransportAction[];
  artworkBase64: string | null;
  artworkHash: string | null;
  volume: VolumeState;
  sessions: SessionSummary[];
}

export interface PairingQrPayload {
  url: string;
  deviceId: string;
  deviceName: string;
  ip: string;
  port: number;
  pairingToken: string;
  expiresAt: number;
}

export interface PairedClientSummary {
  clientId: string;
  clientName: string;
  fingerprint: string;
  pairedAt: number;
}

export interface WireLogEntry {
  uid: string;
  ts: number;
  direction: 'outbound' | 'inbound';
  type: string;
  seq: number;
  summary: string;
  raw: string;
}

export interface MonorepoFileEntry {
  path: string;
  size: number;
  category: string;
}
