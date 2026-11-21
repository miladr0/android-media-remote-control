package com.mediaremote.receiver.media

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Base64
import com.mediaremote.receiver.protocol.MediaStatePayload
import com.mediaremote.receiver.protocol.SessionSummary
import com.mediaremote.receiver.protocol.VolumeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

sealed class CommandDispatchResult {
    data class Accepted(val action: String) : CommandDispatchResult()
    data class Rejected(val code: String, val message: String) : CommandDispatchResult()
}

class MediaSessionCoordinator(
    private val context: Context,
    private val deviceId: String,
    private val deviceName: String
) {
    private val sessionManager =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val registeredCallbacks = mutableMapOf<String, Pair<MediaController, MediaController.Callback>>()
    private var selectedSessionId: String? = null
    private var listening = false

    private val _state = MutableStateFlow(buildEmptyState())
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
        registeredCallbacks.values.forEach { (controller, callback) ->
            runCatching { controller.unregisterCallback(callback) }
        }
        registeredCallbacks.clear()
        listening = false
    }

    @Synchronized
    private fun syncControllers(controllers: List<MediaController>) {
        val incomingIds = controllers.map { controllerKey(it) }.toSet()

        // Unregister removed sessions
        val toRemove = registeredCallbacks.keys.filter { it !in incomingIds }
        toRemove.forEach { key ->
            registeredCallbacks.remove(key)?.let { (ctrl, cb) ->
                runCatching { ctrl.unregisterCallback(cb) }
            }
        }

        // Register new sessions
        controllers.forEach { controller ->
            val key = controllerKey(controller)
            if (!registeredCallbacks.containsKey(key)) {
                val cb = object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: PlaybackState?) = refreshState()
                    override fun onMetadataChanged(metadata: MediaMetadata?) = refreshState()
                    override fun onAudioInfoChanged(info: MediaController.PlaybackInfo?) = refreshState()
                    override fun onSessionDestroyed() {
                        registeredCallbacks.remove(key)
                        refreshState()
                    }
                }
                controller.registerCallback(cb)
                registeredCallbacks[key] = controller to cb
            }
        }

        // Auto-select playing session if none selected or selected was destroyed
        if (selectedSessionId == null || selectedSessionId !in registeredCallbacks.keys) {
            selectedSessionId = pickPreferredSessionKey(registeredCallbacks.values.map { it.first })
        }
        refreshState()
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

    @Synchronized
    fun executeCommand(action: String, volumePercent: Int? = null, targetSessionId: String? = null): CommandDispatchResult {
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
            return CommandDispatchResult.Rejected("PERMISSION_MISSING", "Notification Listener permission not granted on Xiaomi device")
        }

        val controller = selectedSessionId?.let { registeredCallbacks[it]?.first }
            ?: return CommandDispatchResult.Rejected("NO_ACTIVE_SESSION", "No active media session available")

        val supported = extractSupportedActions(controller.playbackState)
        if (action !in supported) {
            return CommandDispatchResult.Rejected(
                "UNSUPPORTED_ACTION",
                "${resolveAppName(controller.packageName)} does not support '$action' in its PlaybackState actions"
            )
        }

        val controls = controller.transportControls
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

    @Synchronized
    fun refreshState() {
        val controllers = registeredCallbacks.values.map { it.first }
        val active = selectedSessionId?.let { registeredCallbacks[it]?.first }
        val metadata = active?.metadata
        val pbState = active?.playbackState
        val artworkBitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val (artB64, artHash) = encodeArtwork(artworkBitmap)

        val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val volPct = ((currentVol.toFloat() / maxVol.toFloat()) * 100f).toInt().coerceIn(0, 100)

        val summaries = controllers.map { ctrl ->
            val key = controllerKey(ctrl)
            SessionSummary(
                sessionId = key,
                packageName = ctrl.packageName,
                appName = resolveAppName(ctrl.packageName),
                title = ctrl.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
                playbackState = mapPlaybackState(ctrl.playbackState?.state),
                isSelected = key == selectedSessionId
            )
        }

        _state.value = MediaStatePayload(
            deviceId = deviceId,
            deviceName = deviceName,
            sessionId = active?.let { controllerKey(it) },
            packageName = active?.packageName,
            appName = active?.let { resolveAppName(it.packageName) },
            title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
            artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM),
            durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 },
            positionMs = pbState?.position?.takeIf { it >= 0 },
            playbackState = mapPlaybackState(pbState?.state),
            supportedActions = extractSupportedActions(pbState),
            artworkBase64 = artB64,
            artworkHash = artHash,
            volume = VolumeState(
                current = currentVol,
                max = maxVol,
                percent = volPct,
                isFixed = audioManager.isVolumeFixed
            ),
            sessions = summaries
        )
    }

    private fun buildEmptyState() = MediaStatePayload(
        deviceId = deviceId,
        deviceName = deviceName,
        playbackState = "NONE",
        supportedActions = emptyList(),
        volume = VolumeState(current = 0, max = 15, percent = 0, isFixed = false),
        sessions = emptyList()
    )

    private fun controllerKey(controller: MediaController): String =
        "${controller.packageName}:${controller.sessionToken.hashCode()}"

    private fun pickPreferredSessionKey(controllers: List<MediaController>): String? {
        val playing = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        return (playing ?: controllers.firstOrNull())?.let { controllerKey(it) }
    }

    private fun resolveAppName(packageName: String): String = runCatching {
        val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(appInfo).toString()
    }.getOrDefault(packageName)

    private fun encodeArtwork(bitmap: Bitmap?): Pair<String?, String?> {
        if (bitmap == null) return null to null
        val maxDim = 256f
        val scale = minOf(maxDim / bitmap.width.coerceAtLeast(1), maxDim / bitmap.height.coerceAtLeast(1), 1f)
        val targetW = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val targetH = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 76, out)
        val bytes = out.toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
            .take(8).joinToString("") { "%02x".format(it) }
        return Base64.encodeToString(bytes, Base64.NO_WRAP) to hash
    }

    companion object {
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
