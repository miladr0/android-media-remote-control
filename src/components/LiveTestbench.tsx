import React, { useEffect, useState } from 'react';
import {
  QrCode,
  ShieldAlert,
  Trash2,
  Check,
  AlertTriangle,
  ExternalLink,
  RefreshCw,
} from 'lucide-react';
import type {
  MediaStatePayload,
  PairedClientSummary,
  PairingQrPayload,
  WireLogEntry,
} from '../types/protocol';

export function LiveTestbench() {
  const [mediaState, setMediaState] = useState<MediaStatePayload | null>(null);
  const [pairedClients, setPairedClients] = useState<PairedClientSummary[]>([]);
  const [qrDataUrl, setQrDataUrl] = useState<string | null>(null);
  const [qrPayload, setQrPayload] = useState<PairingQrPayload | null>(null);
  const [iframeSrc, setIframeSrc] = useState<string>('/remote/');
  const [iframeKey, setIframeKey] = useState<number>(1);
  const [wireLogs, setWireLogs] = useState<WireLogEntry[]>([]);
  const [securityBanner, setSecurityBanner] = useState<string | null>(null);

  const refreshReceiverState = async () => {
    try {
      const [stateRes, clientsRes] = await Promise.all([
        fetch('/api/receiver/state'),
        fetch('/api/receiver/paired-clients'),
      ]);
      if (stateRes.ok) {
        const { state } = await stateRes.json();
        setMediaState(state);
      }
      if (clientsRes.ok) {
        const { clients } = await clientsRes.json();
        setPairedClients(clients);
      }
    } catch {
      // Ignore transient load error
    }
  };

  useEffect(() => {
    refreshReceiverState();
    const es = new EventSource('/api/receiver/wire-events');
    es.onmessage = (evt) => {
      try {
        const entry: WireLogEntry = JSON.parse(evt.data);
        setWireLogs((prev) => [entry, ...prev].slice(0, 50));
        if (entry.type === 'pair_ack' || entry.type === 'auth_ok' || entry.type === 'state' || entry.type === 'error') {
          refreshReceiverState();
        }
      } catch {
        // Ignore malformed SSE message
      }
    };
    return () => {
      es.close();
    };
  }, []);

  const issueQrCode = async (): Promise<{ payload: PairingQrPayload; qrDataUrl: string } | null> => {
    setSecurityBanner(null);
    const res = await fetch('/api/receiver/pairing-qr', { method: 'POST' });
    if (!res.ok) return null;
    const data = await res.json();
    setQrPayload(data.payload);
    setQrDataUrl(data.qrDataUrl);
    return data;
  };

  const handlePairInPreview = async () => {
    const issued = await issueQrCode();
    if (issued?.payload?.pairingToken) {
      setIframeSrc(`/remote/?pair=${encodeURIComponent(issued.payload.pairingToken)}`);
      setIframeKey((k) => k + 1);
    }
  };

  const handleSelectSession = async (sessionId: string) => {
    const res = await fetch('/api/receiver/switch-session', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sessionId }),
    });
    if (res.ok) {
      const { state } = await res.json();
      setMediaState(state);
    }
  };

  const handleRevoke = async (clientId: string) => {
    await fetch(`/api/receiver/revoke/${encodeURIComponent(clientId)}`, { method: 'POST' });
    refreshReceiverState();
  };

  // Security verification tests directly against /ws/mediaremote
  const testUnauthenticatedCommand = () => {
    setSecurityBanner(null);
    const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const rawWs = new WebSocket(`${proto}//${window.location.host}/ws/mediaremote`);
    rawWs.onopen = () => {
      rawWs.send(
        JSON.stringify({
          v: 1,
          type: 'command',
          id: `rogue-${Math.random().toString(36).slice(2, 8)}`,
          seq: 1,
          ts: Date.now(),
          nonce: btoa(String(Date.now())).slice(0, 16),
          data: { action: 'pause' },
        })
      );
    };
    rawWs.onmessage = (evt) => {
      const resp = JSON.parse(evt.data);
      setSecurityBanner(`${resp.data.code}: ${resp.data.message}`);
      rawWs.close();
    };
  };

  const testReplayAttack = () => {
    setSecurityBanner(null);
    const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const rawWs = new WebSocket(`${proto}//${window.location.host}/ws/mediaremote`);
    rawWs.onopen = () => {
      const nonce = btoa('replay-fixed-nonce-123');
      // First hello seq=0, then ping seq=2, then replay seq=1 (stale seq rejected)
      rawWs.send(
        JSON.stringify({
          v: 1,
          type: 'hello',
          id: 'rep-00000001',
          seq: 0,
          ts: Date.now(),
          nonce: nonce,
          data: { clientId: 'test-probe', clientName: 'Replay Probe', supportedVersions: [1] },
        })
      );
      setTimeout(() => {
        rawWs.send(
          JSON.stringify({
            v: 1,
            type: 'ping',
            id: 'rep-00000002',
            seq: 2,
            ts: Date.now(),
            nonce: btoa('unique-nonce-22222'),
            data: { sentAt: Date.now() },
          })
        );
      }, 60);
      setTimeout(() => {
        rawWs.send(
          JSON.stringify({
            v: 1,
            type: 'ping',
            id: 'rep-00000003',
            seq: 1,
            ts: Date.now(),
            nonce: btoa('unique-nonce-33333'),
            data: { sentAt: Date.now() },
          })
        );
      }, 120);
    };
    rawWs.onmessage = (evt) => {
      const resp = JSON.parse(evt.data);
      if (resp.type === 'error') {
        setSecurityBanner(`${resp.data.code}: ${resp.data.message}`);
        rawWs.close();
      }
    };
  };

  return (
    <div className="space-y-8">
      <div className="flex flex-col lg:flex-row lg:items-end justify-between gap-4 border-b border-slate-200 pb-6">
        <div className="space-y-1.5 max-w-2xl">
          <h1 className="text-2xl font-semibold tracking-tight text-slate-900">
            Safari Web Remote &amp; Receiver Testbench
          </h1>
          <p className="text-sm text-slate-600 leading-relaxed">
            Runs the exact static HTML/CSS/JS web remote served by the Android Ktor receiver (<span className="font-mono text-xs">/assets/web/index.html</span>) against the simulated Xiaomi host over <span className="font-mono text-xs">/ws/mediaremote</span> with one-time QR token swap, pure-JS HMAC-SHA256 auth, and Ajv schema validation.
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2 shrink-0">
          <button
            onClick={handlePairInPreview}
            className="px-4 py-2 text-xs font-medium bg-blue-600 text-white rounded-lg hover:bg-blue-700 transition-colors whitespace-nowrap shrink-0 cursor-pointer"
          >
            Pair &amp; Open Web Remote
          </button>
          <a
            href="/remote/"
            target="_blank"
            rel="noreferrer"
            className="inline-flex items-center gap-1.5 px-3.5 py-2 text-xs font-medium bg-white text-slate-700 border border-slate-300 rounded-lg hover:bg-slate-50 transition-colors whitespace-nowrap shrink-0"
          >
            <ExternalLink className="w-3.5 h-3.5" />
            Open Standalone
          </a>
        </div>
      </div>

      {/* Main 2-column workbench */}
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 items-start">
        {/* Column 1: Embedded Android-served Safari Web Remote */}
        <section className="lg:col-span-5 bg-white border border-slate-200 rounded-xl p-6 space-y-4">
          <div className="flex items-center justify-between">
            <div>
              <h2 className="text-base font-semibold text-slate-900">01. Browser Web Remote</h2>
              <p className="text-xs text-slate-500 mt-0.5">
                Served from Android assets · Plain HTTP + WS · Zero crypto.subtle
              </p>
            </div>
            <button
              onClick={() => setIframeKey((k) => k + 1)}
              className="inline-flex items-center gap-1 px-2.5 py-1.5 text-xs font-medium text-slate-700 border border-slate-200 rounded-md hover:bg-slate-50 cursor-pointer"
              title="Reload browser remote iframe"
            >
              <RefreshCw className="w-3.5 h-3.5" />
              Reload
            </button>
          </div>

          <div className="bg-slate-950 rounded-2xl p-3 border border-slate-800">
            <iframe
              key={iframeKey}
              src={iframeSrc}
              title="LocalCast Browser Remote"
              className="w-full h-[640px] rounded-xl bg-[#0b1120] border-0"
            />
          </div>
        </section>

        {/* Column 2: Xiaomi Android Receiver Host */}
        <section className="lg:col-span-7 bg-white border border-slate-200 rounded-xl p-6 space-y-6">
          <div className="flex flex-wrap items-center justify-between gap-4">
            <div>
              <h2 className="text-base font-semibold text-slate-900">02. Xiaomi Android Receiver Host</h2>
              <p className="text-xs text-slate-500 mt-0.5">
                MediaSessionManager · NotificationListenerService · Ktor CIO :8765
              </p>
            </div>
            <button
              onClick={issueQrCode}
              className="inline-flex items-center gap-1.5 px-3.5 py-2 text-xs font-medium bg-slate-900 text-white rounded-lg hover:bg-slate-800 transition-colors cursor-pointer whitespace-nowrap shrink-0"
            >
              <QrCode className="w-3.5 h-3.5" />
              Generate Pairing QR
            </button>
          </div>

          {/* Active Sessions Table */}
          <div className="border-t border-slate-200 pt-5 space-y-3">
            <div className="flex items-center justify-between">
              <h3 className="text-sm font-medium text-slate-900">Detected Android Media Sessions</h3>
              <span className="text-xs text-slate-500">Click row to switch target session</span>
            </div>
            <div className="overflow-x-auto border border-slate-200 rounded-lg">
              <table className="w-full text-left border-collapse text-xs">
                <thead>
                  <tr className="border-b border-slate-200 bg-slate-50 text-slate-600">
                    <th className="py-2.5 px-3 font-medium">Application</th>
                    <th className="py-2.5 px-3 font-medium">Current Media</th>
                    <th className="py-2.5 px-3 font-medium">State</th>
                    <th className="py-2.5 px-3 font-medium text-right">Target</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-200">
                  {mediaState?.sessions.map((s) => (
                    <tr
                      key={s.sessionId}
                      onClick={() => handleSelectSession(s.sessionId)}
                      className={`cursor-pointer transition-colors ${
                        s.isSelected ? 'bg-blue-50/70' : 'hover:bg-slate-50'
                      }`}
                    >
                      <td className="py-2.5 px-3">
                        <div className="font-medium text-slate-900">{s.appName}</div>
                        <div className="font-mono text-[11px] text-slate-500">{s.packageName}</div>
                      </td>
                      <td className="py-2.5 px-3 text-slate-700 max-w-[220px] truncate">{s.title ?? '—'}</td>
                      <td className="py-2.5 px-3 font-mono text-slate-700">{s.playbackState}</td>
                      <td className="py-2.5 px-3 text-right">
                        {s.isSelected ? (
                          <span className="inline-flex items-center gap-1 font-medium text-blue-700">
                            <Check className="w-3.5 h-3.5" /> Selected
                          </span>
                        ) : (
                          <span className="text-slate-500">Select</span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>

          {/* QR Pairing & Paired Browsers Split */}
          <div className="grid grid-cols-1 md:grid-cols-2 gap-6 border-t border-slate-200 pt-5">
            <div className="space-y-3">
              <h3 className="text-sm font-medium text-slate-900">One-Time QR Pairing URL</h3>
              {qrDataUrl && qrPayload ? (
                <div className="flex items-start gap-4">
                  <img
                    src={qrDataUrl}
                    alt="MediaRemote pairing QR code"
                    className="w-28 h-28 border border-slate-200 rounded-lg bg-white shrink-0"
                  />
                  <div className="space-y-1.5 text-xs text-slate-600 min-w-0">
                    <div className="font-medium text-slate-900">{qrPayload.deviceName}</div>
                    <div className="font-mono text-[11px] break-all text-slate-600">
                      {qrPayload.url}
                    </div>
                    <button
                      onClick={() => {
                        setIframeSrc(`/remote/?pair=${encodeURIComponent(qrPayload.pairingToken)}`);
                        setIframeKey((k) => k + 1);
                      }}
                      className="mt-1 px-3 py-1.5 text-xs font-medium bg-blue-600 text-white rounded-md hover:bg-blue-700 cursor-pointer"
                    >
                      Open in Remote Preview
                    </button>
                  </div>
                </div>
              ) : (
                <div className="text-xs text-slate-500 py-3">
                  No active pairing token. Click &ldquo;Generate Pairing QR&rdquo; or &ldquo;Pair &amp; Open Web Remote&rdquo;.
                </div>
              )}
            </div>

            <div className="space-y-3">
              <h3 className="text-sm font-medium text-slate-900">Paired Browsers ({pairedClients.length})</h3>
              {pairedClients.length === 0 ? (
                <p className="text-xs text-slate-500 py-3">
                  No paired browsers stored yet.
                </p>
              ) : (
                <div className="divide-y divide-slate-200 border border-slate-200 rounded-lg">
                  {pairedClients.map((client) => (
                    <div key={client.clientId} className="flex items-center justify-between px-3 py-2.5 text-xs">
                      <div className="min-w-0">
                        <div className="font-medium text-slate-900 truncate">{client.clientName}</div>
                        <div className="font-mono text-[11px] text-slate-500">
                          {client.clientId} · fp {client.fingerprint}
                        </div>
                      </div>
                      <button
                        onClick={() => handleRevoke(client.clientId)}
                        className="inline-flex items-center gap-1 px-2.5 py-1 text-xs font-medium text-rose-700 hover:bg-rose-50 rounded-md cursor-pointer shrink-0"
                      >
                        <Trash2 className="w-3.5 h-3.5" />
                        Revoke
                      </button>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>
        </section>
      </div>

      {/* Section 3: Live Server Wire Protocol & Security Verification */}
      <section className="bg-white border border-slate-200 rounded-xl p-6 space-y-5">
        <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
          <div>
            <h2 className="text-base font-semibold text-slate-900">03. Live WebSocket Wire Inspector &amp; Security Probes</h2>
            <p className="text-xs text-slate-500 mt-0.5">
              Captures live frames between the embedded Android web remote and the server, validated against /media-remote/shared/schemas/*.json.
            </p>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <button
              onClick={testUnauthenticatedCommand}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium bg-white border border-slate-300 text-slate-700 rounded-md hover:bg-slate-50 cursor-pointer"
            >
              <ShieldAlert className="w-3.5 h-3.5 text-rose-600" />
              Test Unauthenticated Command
            </button>
            <button
              onClick={testReplayAttack}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium bg-white border border-slate-300 text-slate-700 rounded-md hover:bg-slate-50 cursor-pointer"
            >
              <AlertTriangle className="w-3.5 h-3.5 text-amber-600" />
              Test Replay Attack
            </button>
          </div>
        </div>

        {securityBanner && (
          <div className="px-3.5 py-2.5 bg-slate-50 border border-slate-200 rounded-lg text-xs font-mono text-slate-800">
            Probe result: {securityBanner}
          </div>
        )}

        {wireLogs.length === 0 ? (
          <div className="border border-dashed border-slate-300 rounded-lg py-10 text-center text-xs text-slate-500">
            No WebSocket frames captured yet. Click &ldquo;Pair &amp; Open Web Remote&rdquo; or run a security probe above.
          </div>
        ) : (
          <div className="overflow-x-auto border border-slate-200 rounded-lg max-h-80 overflow-y-auto">
            <table className="w-full text-left border-collapse text-xs">
              <thead className="sticky top-0 bg-slate-50 border-b border-slate-200 text-slate-600">
                <tr>
                  <th className="py-2 px-3 font-medium">Time</th>
                  <th className="py-2 px-3 font-medium">Direction</th>
                  <th className="py-2 px-3 font-medium">Type</th>
                  <th className="py-2 px-3 font-medium text-right">Seq</th>
                  <th className="py-2 px-3 font-medium">Summary</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-200 font-mono">
                {wireLogs.map((log) => (
                  <tr key={log.uid} className="hover:bg-slate-50">
                    <td className="py-2 px-3 text-slate-500 whitespace-nowrap">
                      {new Date(log.ts).toLocaleTimeString()}
                    </td>
                    <td className="py-2 px-3 whitespace-nowrap">
                      <span className={log.direction === 'outbound' ? 'text-emerald-700' : 'text-blue-700'}>
                        {log.direction === 'outbound' ? 'Android -> Browser' : 'Browser -> Android'}
                      </span>
                    </td>
                    <td className="py-2 px-3 font-medium text-slate-900 whitespace-nowrap">{log.type}</td>
                    <td className="py-2 px-3 text-right text-slate-600">{log.seq}</td>
                    <td className="py-2 px-3 font-sans text-slate-700">{log.summary}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  );
}
