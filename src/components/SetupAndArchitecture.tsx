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
    app: 'Local Music Players',
    pkg: 'com.android.music',
    playPause: 'Supported',
    skip: 'Supported',
    stop: 'Supported',
    metadata: 'Track Title, Artist, Embedded Art',
  },
];

const TEST_CASES = [
  'Opening http://<ip>:8765/?pair=<token> in any browser pairs cleanly and strips ?pair=.',
  'One-time pairing token swaps for a 256-bit deviceSecret stored in localStorage.',
  'Pure-JS HMAC-SHA256 challenge-response authenticates every WebSocket without crypto.subtle.',
  'Play and Pause dispatch via MediaController.TransportControls and report Sent -> Accepted -> State.',
  'Stop succeeds on VLC/local players and disables/rejects cleanly on Spotify/YouTube.',
  'Next and Previous track skips update metadata and artwork immediately.',
  'App name & 96x96 icon resolve via <queries> package visibility and render alongside the player and session picker.',
  'Last-playing player persists in SharedPreferences and resumes via MediaBrowser / KEYCODE_MEDIA_PLAY when idle.',
  'Switching back to any browser or Home Screen PWA reconnects immediately via visibilitychange.',
  'Unauthenticated and replayed WebSocket frames are rejected (UNAUTHENTICATED / REPLAY_DETECTED).',
  'Revoking the last device with zero connections stops the foreground server automatically.',
  'Notification Exit stops the server cleanly (START_NOT_STICKY) without auto-restarting on boot.',
];

export function SetupAndArchitecture() {
  return (
    <div className="space-y-8">
      <div className="border-b border-slate-200 pb-6 space-y-1.5 max-w-3xl">
        <h1 className="text-2xl font-semibold tracking-tight text-slate-900">
          Architecture, Android Reliability &amp; Browser Web Remote
        </h1>
        <p className="text-sm text-slate-600 leading-relaxed">
          The Android receiver serves a zero-dependency web remote directly from Ktor (<span className="font-mono text-xs">http://&lt;android-ip&gt;:8765/</span>) over your local Wi-Fi for any browser (phone, tablet, or computer), with full-screen Home Screen support.
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
            Because mobile browsers disable <span className="font-mono text-slate-800">crypto.subtle</span> on plain <span className="font-mono text-slate-800">http://</span> LAN URLs, pairing swaps a one-time QR token for a 256-bit secret stored in <span className="font-mono text-slate-800">localStorage</span> and authenticates every socket with pure-JS HMAC-SHA256 + <span className="font-mono text-slate-800">ReplayGuard</span>. Use on trusted Wi-Fi.
          </p>
        </div>
        <div className="bg-white border border-slate-200 rounded-xl p-5 space-y-2">
          <h2 className="text-sm font-semibold text-slate-900">03. Honest Command Confirmation</h2>
          <p className="text-xs text-slate-600 leading-relaxed">
            Controls display real states: <span className="font-mono text-slate-800">Sent</span> on socket write, <span className="font-mono text-slate-800">Accepted by Android</span> on <span className="font-mono text-slate-800">ack</span>, or <span className="font-mono text-slate-800">Unsupported / Error</span> when <span className="font-mono text-slate-800">PlaybackState.actions</span> omits an action or resume fails.
          </p>
        </div>
      </div>

      {/* Android & Browser Setup Checklist */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <section className="bg-white border border-slate-200 rounded-xl p-6 space-y-4">
          <h2 className="text-base font-semibold text-slate-900">Android Background Reliability Setup</h2>
          <ol className="space-y-3 text-xs text-slate-700 leading-relaxed list-decimal pl-4">
            <li>
              Install <span className="font-mono text-slate-900">app-debug.apk</span> from GitHub Releases or build <span className="font-mono text-slate-900">media-remote/android</span> (<span className="font-mono">minSdk 26</span>, <span className="font-mono">targetSdk 35</span>).
            </li>
            <li>
              <span className="font-medium text-slate-900">Notification Access:</span> Enable <span className="font-mono">LocalCast Media Session Bridge</span> so <span className="font-mono">MediaSessionManager.getActiveSessions()</span> is authorized.
            </li>
            <li>
              <span className="font-medium text-slate-900">Battery &amp; OEM Background Rules:</span> Set Battery to <span className="font-medium">Unrestricted</span> and use the built-in Setup dialog for Xiaomi, Samsung, Huawei/Honor, Oppo/Realme/OnePlus, Vivo, or Asus shortcuts.
            </li>
          </ol>
        </section>

        <section className="bg-white border border-slate-200 rounded-xl p-6 space-y-4">
          <h2 className="text-base font-semibold text-slate-900">Any Browser on Same Wi-Fi</h2>
          <ol className="space-y-3 text-xs text-slate-700 leading-relaxed list-decimal pl-4">
            <li>
              Tap <span className="font-medium text-slate-900">Pair Device</span> on Android to display the QR code (<span className="font-mono">http://&lt;ip&gt;:8765/?pair=&lt;token&gt;</span>), or open that address from any phone, tablet, or computer browser.
            </li>
            <li>
              The page swaps the one-time token for a per-device secret stored in <span className="font-mono">localStorage</span> and strips <span className="font-mono">?pair=</span> from the URL.
            </li>
            <li>
              Add to Home Screen (iOS Safari or Android Chrome) for a full-screen remote with <span className="font-mono">visibilitychange</span> auto-reconnect.
            </li>
          </ol>
        </section>
      </div>

      {/* Compatibility Matrix Table */}
      <section className="bg-white border border-slate-200 rounded-xl p-6 space-y-4">
        <div>
          <h2 className="text-base font-semibold text-slate-900">Android Media App Capability Matrix</h2>
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
