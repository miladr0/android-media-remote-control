package com.mediaremote.receiver.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import android.view.KeyEvent
import com.mediaremote.receiver.protocol.MediaStatePayload
import com.mediaremote.receiver.protocol.ProtocolValidator
import com.mediaremote.receiver.protocol.SessionSummary
import com.mediaremote.receiver.protocol.VolumeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

sealed class CommandDispatchResult {
    data class Accepted(val action: String) : CommandDispatchResult()
    data class Rejected(val code: String, val message: String) : CommandDispatchResult()
}

@Serializable
data class LastPlayedSessionRecord(
    val packageName: String,
    val appName: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val artworkThumbnailB64: String? = null,
    val lastPlaybackState: String
)

private data class CachedOptionalText(val value: String?)

class MediaSessionCoordinator(
    private val context: Context,
    private val deviceId: String,
    private val deviceName: String
) {
    private val sessionManager =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val lastSessionPrefs =
        context.getSharedPreferences("mediaremote_last_session", Context.MODE_PRIVATE)
    private val json = ProtocolValidator.json

    private val registeredCallbacks = mutableMapOf<String, Pair<MediaController, MediaController.Callback>>()
    private var selectedSessionId: String? = null
    private var listening = false

    private val appLabelCache = ConcurrentHashMap<String, CachedOptionalText>()
    private val appIconCache = ConcurrentHashMap<String, CachedOptionalText>()
    private var lastPlayed: LastPlayedSessionRecord? = loadLastPlayed()

    private val _state = MutableStateFlow(buildInitialState())
    val state: StateFlow<MediaStatePayload> = _state.asStateFlow()

    private val activeSessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        syncControllers(controllers.orEmpty())
    }

    fun startMonitoring() {
        if (listening || !MediaNotificationListenerService.isPermissionGranted(context)) {
            refreshState()
            return
        }
        val component = MediaNotificationListenerService.componentName(context)
        runCatching {
            sessionManager.addOnActiveSessionsChangedListener(activeSessionsListener, component)
            syncControllers(sessionManager.getActiveSessions(component))
            listening = true
        }.onFailure {
            refreshState()
        }
    }

    fun stopMonitoring() {
        if (!listening) return
        runCatching { sessionManager.removeOnActiveSessionsChangedListener(activeSessionsListener) }
        synchronized(this) {
            registeredCallbacks.values.forEach { (controller, callback) ->
                runCatching { controller.unregisterCallback(callback) }
            }
            registeredCallbacks.clear()
        }
        listening = false
    }

    @Synchronized
    private fun syncControllers(controllers: List<MediaController>) {
        val incomingIds = controllers.map { controllerKey(it) }.toSet()

        val toRemove = registeredCallbacks.keys.filter { it !in incomingIds }
        toRemove.forEach { key ->
            registeredCallbacks.remove(key)?.let { (ctrl, cb) ->
                runCatching { ctrl.unregisterCallback(cb) }
            }
        }

        controllers.forEach { controller ->
            val key = controllerKey(controller)
            if (!registeredCallbacks.containsKey(key)) {
                val cb = object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: PlaybackState?) = refreshState()
                    override fun onMetadataChanged(metadata: MediaMetadata?) = refreshState()
                    override fun onAudioInfoChanged(info: MediaController.PlaybackInfo) = refreshState()
                    override fun onSessionDestroyed() {
                        synchronized(this@MediaSessionCoordinator) {
                            registeredCallbacks.remove(key)
                        }
                        refreshState()
                    }
                }
                controller.registerCallback(cb)
                registeredCallbacks[key] = controller to cb
            }
        }

        if (selectedSessionId == null || selectedSessionId !in registeredCallbacks.keys) {
            selectedSessionId = pickPreferredSessionKey(registeredCallbacks.values.map { it.first })
        }
        refreshState()
    }

    private fun resyncActiveSessions() {
        if (!MediaNotificationListenerService.isPermissionGranted(context)) return
        val component = MediaNotificationListenerService.componentName(context)
        runCatching {
            syncControllers(sessionManager.getActiveSessions(component))
        }
    }

    @Synchronized
    fun selectSession(sessionId: String): CommandDispatchResult {
        if (!registeredCallbacks.containsKey(sessionId)) {
            return CommandDispatchResult.Rejected("SESSION_NOT_FOUND", "Session $sessionId is no longer active")
        }
        selectedSessionId = sessionId
        refreshState()
        return CommandDispatchResult.Accepted("select_session")
    }

    suspend fun executeCommand(
        action: String,
        volumePercent: Int? = null,
        targetSessionId: String? = null
    ): CommandDispatchResult {
        if (action == "select_session") {
            return selectSession(targetSessionId.orEmpty())
        }
        if (action == "set_volume") {
            if (audioManager.isVolumeFixed) {
                return CommandDispatchResult.Rejected("VOLUME_FIXED", "Android device reports fixed output volume")
            }
            val pct = (volumePercent ?: 50).coerceIn(0, 100)
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val target = Math.round((pct / 100f) * max).coerceIn(0, max)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            refreshState()
            return CommandDispatchResult.Accepted("set_volume")
        }

        if (!MediaNotificationListenerService.isPermissionGranted(context)) {
            return CommandDispatchResult.Rejected(
                "PERMISSION_MISSING",
                "Notification Listener permission not granted on Android device"
            )
        }

        val activeController: MediaController?
        val remembered: LastPlayedSessionRecord?
        synchronized(this) {
            activeController = selectedSessionId?.let { registeredCallbacks[it]?.first }
            remembered = lastPlayed
        }

        if (activeController == null) {
            if (remembered == null) {
                return CommandDispatchResult.Rejected("NO_ACTIVE_SESSION", "No active media session available")
            }
            val appDisplay = resolveAppName(remembered.packageName) ?: remembered.appName ?: remembered.packageName
            if (action != "play") {
                return CommandDispatchResult.Rejected(
                    "NO_ACTIVE_SESSION",
                    "No active media session — press Play to resume $appDisplay"
                )
            }
            return resumeLastPlayed(remembered, appDisplay)
        }

        val supported = extractSupportedActions(activeController.playbackState)
        if (action !in supported) {
            val appDisplay = resolveAppName(activeController.packageName) ?: activeController.packageName
            return CommandDispatchResult.Rejected(
                "UNSUPPORTED_ACTION",
                "$appDisplay does not support '$action' in its PlaybackState actions"
            )
        }

        val controls = activeController.transportControls
        when (action) {
            "play" -> controls.play()
            "pause" -> controls.pause()
            "stop" -> controls.stop()
            "next" -> controls.skipToNext()
            "previous" -> controls.skipToPrevious()
            else -> return CommandDispatchResult.Rejected("UNSUPPORTED_ACTION", "Unknown action $action")
        }
        return CommandDispatchResult.Accepted(action)
    }

    private suspend fun resumeLastPlayed(
        last: LastPlayedSessionRecord,
        appDisplay: String
    ): CommandDispatchResult {
        // 1. Try platform android.media.browse.MediaBrowser (no MediaBrowserCompat)
        val browserResumed = tryResumeViaMediaBrowser(last.packageName)
        if (browserResumed) {
            resyncActiveSessions()
            refreshState()
            return CommandDispatchResult.Accepted("play")
        }

        // 2. Fallback: AudioManager.dispatchMediaKeyEvent with KEYCODE_MEDIA_PLAY (down + up)
        val keyEventResumed = tryResumeViaMediaKey(last.packageName)
        if (keyEventResumed) {
            resyncActiveSessions()
            refreshState()
            return CommandDispatchResult.Accepted("play")
        }

        // 3. Could not resume cleanly
        return CommandDispatchResult.Rejected(
            "NO_ACTIVE_SESSION",
            "Could not resume $appDisplay — open it once on the phone"
        )
    }

    private suspend fun tryResumeViaMediaBrowser(packageName: String): Boolean {
        val intent = Intent("android.media.browse.MediaBrowserService").setPackage(packageName)
        val resolveInfos = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.queryIntentServices(intent, 0)
            }
        }.getOrDefault(emptyList())

        val serviceInfo = resolveInfos.firstOrNull()?.serviceInfo ?: return false
        val component = ComponentName(serviceInfo.packageName, serviceInfo.name)

        return withContext(Dispatchers.Main) {
            var browser: MediaBrowser? = null
            try {
                val controller = withTimeoutOrNull(3500L) {
                    suspendCancellableCoroutine<MediaController?> { cont ->
                        val cb = object : MediaBrowser.ConnectionCallback() {
                            override fun onConnected() {
                                val token = browser?.sessionToken
                                val ctrl = if (token != null) MediaController(context, token) else null
                                if (cont.isActive) cont.resume(ctrl)
                            }

                            override fun onConnectionSuspended() {
                                if (cont.isActive) cont.resume(null)
                            }

                            override fun onConnectionFailed() {
                                if (cont.isActive) cont.resume(null)
                            }
                        }
                        browser = MediaBrowser(context, component, cb, null)
                        browser?.connect()
                    }
                } ?: return@withContext false

                val playDispatched = runCatching {
                    controller.transportControls.play()
                }.isSuccess
                if (!playDispatched) return@withContext false

                repeat(10) {
                    val stateCode = controller.playbackState?.state
                    if (stateCode == PlaybackState.STATE_PLAYING ||
                        stateCode == PlaybackState.STATE_BUFFERING ||
                        hasActivePlayingSession(packageName)
                    ) {
                        return@withContext true
                    }
                    delay(150L)
                }
                false
            } catch (_: Throwable) {
                false
            } finally {
                runCatching { browser?.disconnect() }
            }
        }
    }

    private suspend fun tryResumeViaMediaKey(targetPackage: String): Boolean {
        val dispatched = runCatching {
            val eventTime = SystemClock.uptimeMillis()
            audioManager.dispatchMediaKeyEvent(
                KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0)
            )
            audioManager.dispatchMediaKeyEvent(
                KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY, 0)
            )
        }.isSuccess
        if (!dispatched) return false

        repeat(10) {
            resyncActiveSessions()
            if (audioManager.isMusicActive || hasActivePlayingSession(targetPackage) || hasAnyPlayingSession()) {
                return true
            }
            delay(150L)
        }
        return false
    }

    @Synchronized
    private fun hasActivePlayingSession(packageName: String): Boolean =
        registeredCallbacks.values.any { (ctrl, _) ->
            ctrl.packageName == packageName &&
                (ctrl.playbackState?.state == PlaybackState.STATE_PLAYING ||
                    ctrl.playbackState?.state == PlaybackState.STATE_BUFFERING)
        }

    @Synchronized
    private fun hasAnyPlayingSession(): Boolean =
        registeredCallbacks.values.any { (ctrl, _) ->
            ctrl.playbackState?.state == PlaybackState.STATE_PLAYING ||
                ctrl.playbackState?.state == PlaybackState.STATE_BUFFERING
        }

    @Synchronized
    fun refreshState() {
        val controllers = registeredCallbacks.values.map { it.first }
        val active = selectedSessionId?.let { registeredCallbacks[it]?.first }

        val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val volPct = ((currentVol.toFloat() / maxVol.toFloat()) * 100f).toInt().coerceIn(0, 100)
        val volumeState = VolumeState(
            current = currentVol,
            max = maxVol,
            percent = volPct,
            isFixed = audioManager.isVolumeFixed
        )

        if (active == null) {
            val remembered = lastPlayed
            if (remembered != null) {
                val appLabel = resolveAppName(remembered.packageName) ?: remembered.appName
                val appIcon = resolveAppIconB64(remembered.packageName)
                val thumb = remembered.artworkThumbnailB64
                val thumbHash = thumb?.let { sha256Short(it.toByteArray()) }
                _state.value = MediaStatePayload(
                    deviceId = deviceId,
                    deviceName = deviceName,
                    sessionId = null,
                    packageName = remembered.packageName,
                    appName = appLabel,
                    appIconB64 = appIcon,
                    title = remembered.title,
                    artist = remembered.artist,
                    playbackState = "STOPPED",
                    isLastPlayed = true,
                    supportedActions = listOf("play"),
                    artworkBase64 = thumb,
                    artworkHash = thumbHash,
                    volume = volumeState,
                    sessions = emptyList()
                )
            } else {
                _state.value = buildEmptyState(volumeState)
            }
            return
        }

        val metadata = active.metadata
        val pbState = active.playbackState
        val artworkBitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val (artB64, artHash) = encodeArtwork(artworkBitmap, maxDim = 256f)

        val activePkg = active.packageName
        val activeAppLabel = resolveAppName(activePkg)
        val activeAppIcon = resolveAppIconB64(activePkg)
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        val mappedPlaybackState = mapPlaybackState(pbState?.state)

        // Persist last session that was playing
        if (pbState?.state == PlaybackState.STATE_PLAYING) {
            val (smallThumbB64, _) = encodeArtwork(artworkBitmap, maxDim = 96f)
            val record = LastPlayedSessionRecord(
                packageName = activePkg,
                appName = activeAppLabel,
                title = title,
                artist = artist,
                artworkThumbnailB64 = smallThumbB64,
                lastPlaybackState = mappedPlaybackState
            )
            if (record != lastPlayed) {
                lastPlayed = record
                saveLastPlayed(record)
            }
        }

        val summaries = controllers.map { ctrl ->
            val key = controllerKey(ctrl)
            val pkg = ctrl.packageName
            SessionSummary(
                sessionId = key,
                packageName = pkg,
                appName = resolveAppName(pkg),
                appIconB64 = resolveAppIconB64(pkg),
                title = ctrl.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
                playbackState = mapPlaybackState(ctrl.playbackState?.state),
                isSelected = key == selectedSessionId
            )
        }

        _state.value = MediaStatePayload(
            deviceId = deviceId,
            deviceName = deviceName,
            sessionId = controllerKey(active),
            packageName = activePkg,
            appName = activeAppLabel,
            appIconB64 = activeAppIcon,
            title = title,
            artist = artist,
            album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM),
            durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 },
            positionMs = pbState?.position?.takeIf { it >= 0 },
            playbackState = mappedPlaybackState,
            isLastPlayed = false,
            supportedActions = extractSupportedActions(pbState),
            artworkBase64 = artB64,
            artworkHash = artHash,
            volume = volumeState,
            sessions = summaries
        )
    }

    private fun buildInitialState(): MediaStatePayload {
        val currentVol = runCatching { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0)
        val maxVol = runCatching { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(15).coerceAtLeast(1)
        val volPct = ((currentVol.toFloat() / maxVol.toFloat()) * 100f).toInt().coerceIn(0, 100)
        val volumeState = VolumeState(
            current = currentVol,
            max = maxVol,
            percent = volPct,
            isFixed = runCatching { audioManager.isVolumeFixed }.getOrDefault(false)
        )
        val remembered = lastPlayed
        return if (remembered != null) {
            val thumb = remembered.artworkThumbnailB64
            MediaStatePayload(
                deviceId = deviceId,
                deviceName = deviceName,
                packageName = remembered.packageName,
                appName = resolveAppName(remembered.packageName) ?: remembered.appName,
                appIconB64 = resolveAppIconB64(remembered.packageName),
                title = remembered.title,
                artist = remembered.artist,
                playbackState = "STOPPED",
                isLastPlayed = true,
                supportedActions = listOf("play"),
                artworkBase64 = thumb,
                artworkHash = thumb?.let { sha256Short(it.toByteArray()) },
                volume = volumeState,
                sessions = emptyList()
            )
        } else {
            buildEmptyState(volumeState)
        }
    }

    private fun buildEmptyState(
        volumeState: VolumeState = VolumeState(current = 0, max = 15, percent = 0, isFixed = false)
    ) = MediaStatePayload(
        deviceId = deviceId,
        deviceName = deviceName,
        playbackState = "NONE",
        isLastPlayed = false,
        supportedActions = emptyList(),
        volume = volumeState,
        sessions = emptyList()
    )

    private fun controllerKey(controller: MediaController): String =
        "${controller.packageName}:${controller.sessionToken.hashCode()}"

    private fun pickPreferredSessionKey(controllers: List<MediaController>): String? {
        val playing = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        return (playing ?: controllers.firstOrNull())?.let { controllerKey(it) }
    }

    private fun resolveAppName(packageName: String): String? {
        appLabelCache[packageName]?.let { return it.value }
        val resolved = runCatching {
            val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(appInfo).toString().takeIf { it.isNotBlank() }
        }.getOrNull()
        appLabelCache[packageName] = CachedOptionalText(resolved)
        return resolved
    }

    private fun resolveAppIconB64(packageName: String): String? {
        appIconCache[packageName]?.let { return it.value }
        val encoded = runCatching {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, 96, 96)
                drawable.draw(canvas)
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            } finally {
                bitmap.recycle()
            }
        }.getOrNull()
        appIconCache[packageName] = CachedOptionalText(encoded)
        return encoded
    }

    private fun loadLastPlayed(): LastPlayedSessionRecord? {
        val raw = lastSessionPrefs.getString(KEY_LAST_PLAYED, null) ?: return null
        return runCatching { json.decodeFromString(LastPlayedSessionRecord.serializer(), raw) }.getOrNull()
    }

    private fun saveLastPlayed(record: LastPlayedSessionRecord) {
        lastSessionPrefs.edit()
            .putString(KEY_LAST_PLAYED, json.encodeToString(record))
            .apply()
    }

    private fun encodeArtwork(bitmap: Bitmap?, maxDim: Float): Pair<String?, String?> {
        if (bitmap == null) return null to null
        val scale = minOf(maxDim / bitmap.width.coerceAtLeast(1), maxDim / bitmap.height.coerceAtLeast(1), 1f)
        val targetW = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val targetH = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 76, out)
        val bytes = out.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP) to sha256Short(bytes)
    }

    private fun sha256Short(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .take(8).joinToString("") { "%02x".format(it) }

    companion object {
        private const val KEY_LAST_PLAYED = "last_played_v1"

        fun extractSupportedActions(state: PlaybackState?): List<String> =
            extractSupportedActionsFromMask(state?.actions ?: 0L)

        fun extractSupportedActionsFromMask(mask: Long): List<String> {
            if (mask == 0L) return emptyList()
            val list = mutableListOf<String>()
            if ((mask and PlaybackState.ACTION_PLAY) != 0L || (mask and PlaybackState.ACTION_PLAY_PAUSE) != 0L) {
                list.add("play")
            }
            if ((mask and PlaybackState.ACTION_PAUSE) != 0L || (mask and PlaybackState.ACTION_PLAY_PAUSE) != 0L) {
                list.add("pause")
            }
            if ((mask and PlaybackState.ACTION_STOP) != 0L) {
                list.add("stop")
            }
            if ((mask and PlaybackState.ACTION_SKIP_TO_NEXT) != 0L) {
                list.add("next")
            }
            if ((mask and PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0L) {
                list.add("previous")
            }
            return list
        }

        fun mapPlaybackState(state: Int?): String = when (state) {
            PlaybackState.STATE_PLAYING -> "PLAYING"
            PlaybackState.STATE_PAUSED -> "PAUSED"
            PlaybackState.STATE_STOPPED -> "STOPPED"
            PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> "BUFFERING"
            PlaybackState.STATE_ERROR -> "ERROR"
            else -> "NONE"
        }
    }
}
