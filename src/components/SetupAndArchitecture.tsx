import React from 'react';

const COMPATIBILITY_ROWS = [
  {
    app: 'Spotify',
    pkg: 'com.spotify.music',
    playPause: 'Supported',
    skip: 'Supported',
    stop: 'Unsupported (omits ACTION_STOP)',
    metadata: 'Title, Artist, Album, Cover Art',
  },
  {
    app: 'YouTube',
    pkg: 'com.google.android.youtube',
    playPause: 'Supported',
    skip: 'Playlist / Queue only',
    stop: 'Unsupported (omits ACTION_STOP)',
    metadata: 'Video Title, Channel Name, Thumbnail',
  },
  {
    app: 'VLC for Android',
    pkg: 'org.videolan.vlc',
    playPause: 'Supported',
    skip: 'Supported',
    stop: 'Supported',
    metadata: 'Filename / ID3 Title, Artist',
  },
  {
    app: 'Xiaomi MIUI Music',
    pkg: 'com.miui.player',
    playPause: 'Supported',
    skip: 'Supported',
    stop: 'Supported',
    metadata: 'Track Title, Artist, Embedded Art',
  },
];

const TEST_CASES = [
  'Scanning Android QR opens http://<ip>:8765/?pair=<token> in iPhone Safari and strips ?pair=.',
  'One-time pairing token swaps for a 256-bit deviceSecret stored in localStorage.',
  'Pure-JS HMAC-SHA256 challenge-response authenticates every WebSocket without crypto.subtle.',
  'Play and Pause dispatch via MediaController.TransportControls and report Sent -> Accepted -> State.',
  'Stop succeeds on VLC/MIUI Music and disables/rejects cleanly on Spotify/YouTube.',
  'Next and Previous track skips update metadata and artwork immediately.',
  'Multi-session picker switches active target app when Spotify, YouTube, or VLC run concurrently.',
  'Switching back to Safari or Home Screen app reconnects immediately via visibilitychange.',
  'Xiaomi receiver remains reachable with screen off after MIUI Autostart + Battery setup.',
  'Unauthenticated WebSocket clients are rejected with UNAUTHENTICATED before reaching media APIs.',
  'Stale sequence numbers or reused nonces are rejected by ReplayGuard.',
  'Revoking a browser on Xiaomi immediately closes its socket with DEVICE_REVOKED.',
];

export function SetupAndArchitecture() {
  return (
    <div className="space-y-8">
      <div className="border-b border-slate-200 pb-6 space-y-1.5 max-w-3xl">
        <h1 className="text-2xl font-semibold tracking-tight text-slate-900">
          Architecture, Xiaomi Setup &amp; Safari Web Remote
        </h1>
        <p className="text-sm text-slate-600 leading-relaxed">
          The Xiaomi Android receiver serves a zero-dependency Safari web remote directly from Ktor (<span className="font-mono text-xs">http://&lt;android-ip&gt;:8765/</span>) over your local Wi-Fi, with Add to Home Screen full-screen support and no iOS app install required.
        </p>
      </div>

      {/* Security & Architecture Grid */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
        <div className="bg-white border border-slate-200 rounded-xl p-5 space-y-2">
          <h2 className="text-sm font-semibold text-slate-900">01. Android-Hosted Web Remote</h2>
          <p className="text-xs text-slate-600 leading-relaxed">
            Ktor CIO serves <span className="font-mono text-slate-800">assets/web/index.html</span>, <span className="font-mono text-slate-800">manifest.webmanifest</span>, and PNG icons on port 8765 alongside <span className="font-mono text-slate-800">/mediaremote</span> WebSocket frames. Zero build step or CDN dependencies.
          </p>
        </div>
        <div className="bg-white border border-slate-200 rounded-xl p-5 space-y-2">
          <h2 className="text-sm font-semibold text-slate-900">02. Plain-HTTP HMAC Auth &amp; Trade-Off</h2>
          <p className="text-xs text-slate-600 leading-relaxed">
            Because Safari blocks <span className="font-mono text-slate-800">crypto.subtle</span> on plain <span className="font-mono text-slate-800">http://</span> LAN URLs, pairing swaps a one-time QR token for a 256-bit secret stored in <span className="font-mono text-slate-800">localStorage</span> and authenticates every socket with pure-JS HMAC-SHA256 + <span className="font-mono text-slate-800">ReplayGuard</span>. TLS is omitted to avoid self-signed cert friction on DHCP IPs; use on trusted Wi-Fi.
          </p>
        </div>
        <div className="bg-white border border-slate-200 rounded-xl p-5 space-y-2">
          <h2 className="text-sm font-semibold text-slate-900">03. Honest Command Confirmation</h2>
          <p className="text-xs text-slate-600 leading-relaxed">
            Controls display real states: <span className="font-mono text-slate-800">Sent</span> on socket write, <span className="font-mono text-slate-800">Accepted by Android</span> on <span className="font-mono text-slate-800">ack</span>, or <span className="font-mono text-slate-800">Unsupported / Error</span> when <span className="font-mono text-slate-800">PlaybackState.actions</span> omits an action.
          </p>
        </div>
      </div>

      {/* Xiaomi & iPhone Safari Setup Checklist */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <section className="bg-white border border-slate-200 rounded-xl p-6 space-y-4">
          <h2 className="text-base font-semibold text-slate-900">Xiaomi MIUI / HyperOS Setup</h2>
          <ol className="space-y-3 text-xs text-slate-700 leading-relaxed list-decimal pl-4">
            <li>
              Open <span className="font-mono text-slate-900">media-remote/android</span> in Android Studio Koala+ and run on your Xiaomi device (<span className="font-mono">minSdk 26</span>, <span className="font-mono">targetSdk 35</span>).
            </li>
            <li>
              <span className="font-medium text-slate-900">Notification Listener Access:</span> Enable <span className="font-mono">LocalCast Media Session Bridge</span> so <span className="font-mono">MediaSessionManager.getActiveSessions()</span> is authorized.
            </li>
            <li>
              <span className="font-medium text-slate-900">Battery Saver &amp; Autostart:</span> Set Battery Saver to <span className="font-medium">No restrictions</span> and enable <span className="font-medium">Autostart</span> in MIUI Security Center.
            </li>
          </ol>
        </section>

        <section className="bg-white border border-slate-200 rounded-xl p-6 space-y-4">
          <h2 className="text-base font-semibold text-slate-900">iPhone Safari &amp; Add to Home Screen</h2>
          <ol className="space-y-3 text-xs text-slate-700 leading-relaxed list-decimal pl-4">
            <li>
              Tap <span className="font-medium text-slate-900">Pair iPhone</span> on Xiaomi to display the QR code (<span className="font-mono">http://&lt;ip&gt;:8765/?pair=&lt;token&gt;</span>).
            </li>
            <li>
              Scan with the iPhone Camera app and open in Safari. The page swaps the one-time token for a per-device secret, stores it in <span className="font-mono">localStorage</span>, and strips <span className="font-mono">?pair=</span> from the URL.
            </li>
            <li>
              Tap Safari&rsquo;s <span className="font-medium text-slate-900">Share -&gt; Add to Home Screen</span> to launch LocalCast full-screen with <span className="font-mono">visibilitychange</span> auto-reconnect.
            </li>
          </ol>
        </section>
      </div>

      {/* Compatibility Matrix Table */}
      <section className="bg-white border border-slate-200 rounded-xl p-6 space-y-4">
        <div>
          <h2 className="text-base font-semibold text-slate-900">Xiaomi Media App Capability Matrix</h2>
          <p className="text-xs text-slate-500 mt-0.5">
            Android apps expose differing PlaybackState bitmasks; LocalCast disables unsupported buttons and returns explicit errors instead of faking control.
          </p>
        </div>
        <div className="overflow-x-auto border border-slate-200 rounded-lg">
          <table className="w-full text-left border-collapse text-xs">
            <thead>
              <tr className="border-b border-slate-200 bg-slate-50 text-slate-600">
                <th className="py-2.5 px-3 font-medium">App</th>
                <th className="py-2.5 px-3 font-medium">Package</th>
                <th className="py-2.5 px-3 font-medium">Play / Pause</th>
                <th className="py-2.5 px-3 font-medium">Next / Prev</th>
                <th className="py-2.5 px-3 font-medium">Stop</th>
                <th className="py-2.5 px-3 font-medium">Metadata</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-200">
              {COMPATIBILITY_ROWS.map((row) => (
                <tr key={row.pkg} className="hover:bg-slate-50">
                  <td className="py-2.5 px-3 font-medium text-slate-900">{row.app}</td>
                  <td className="py-2.5 px-3 font-mono text-slate-600">{row.pkg}</td>
                  <td className="py-2.5 px-3 text-emerald-700">{row.playPause}</td>
                  <td className="py-2.5 px-3 text-slate-700">{row.skip}</td>
                  <td className="py-2.5 px-3 text-slate-700">{row.stop}</td>
                  <td className="py-2.5 px-3 text-slate-600">{row.metadata}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      {/* Verification Matrix */}
      <section className="bg-white border border-slate-200 rounded-xl p-6 space-y-4">
        <h2 className="text-base font-semibold text-slate-900">12-Point Verification Suite</h2>
        <div className="grid grid-cols-1 md:grid-cols-2 gap-x-8 gap-y-2.5 text-xs text-slate-700">
          {TEST_CASES.map((item, idx) => (
            <div key={idx} className="flex items-start gap-2.5 py-1 border-b border-slate-100">
              <span className="font-mono text-slate-400 shrink-0">{String(idx + 1).padStart(2, '0')}.</span>
              <span>{item}</span>
            </div>
          ))}
        </div>
      </section>
    </div>
  );
}
