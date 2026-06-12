//package com.example.controlloopmusic
package org.lighilit.control_loop_music

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.res.Configuration
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.database.Cursor
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.provider.OpenableColumns
import android.content.pm.PackageManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity()
{
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var playbackWakeLock: PowerManager.WakeLock? = null
    private lateinit var notificationManager: NotificationManager
    private lateinit var mediaSession: MediaSession
    private lateinit var loopRangeView: LoopRangeView
    private lateinit var statusText: TextView
    private lateinit var positionText: TextView
    private lateinit var rangeLabel: TextView
    private lateinit var rangeInput: EditText
    private lateinit var playButton: Button
    private var currentUri: Uri? = null
    private var loopStartMs = 0L
    private var loopEndMs = 0L
    private var restorePositionMs = 0L
    private var restoreLoopStartMs = 0L
    private var restoreLoopEndMs = 0L
    private var restoreShouldPlay = false
    private var restoringState = false
    private var prepared = false
    private var currentTitle = "Control Loop Music"
    private var currentWaveform = FloatArray(0)
    private var waveformRequestId = 0

    private val notificationActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_TO_START -> seekToLoopStart()
                ACTION_TOGGLE_PLAY -> togglePlayback()
                ACTION_TO_END -> seekToLoopEnd()
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            updatePosition()
            handler.postDelayed(this, 50)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        mediaSession = MediaSession(this, "ControlLoopMusic").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    player?.takeIf { prepared && !it.isPlaying }?.let {
                        it.start()
                        acquirePlaybackWakeLock()
                        playButton.text = "Pause"
                        updateMediaNotification()
                    }
                }

                override fun onPause() {
                    player?.takeIf { it.isPlaying }?.pause()
                    releasePlaybackWakeLock()
                    playButton.text = "Play"
                    updateMediaNotification()
                }

                override fun onSeekTo(pos: Long) {
                    player?.takeIf { prepared }?.seekTo(pos.coerceIn(loopStartMs, loopEndMs).toInt())
                }

                override fun onSkipToPrevious() {
                    seekToLoopStart()
                }

                override fun onSkipToNext() {
                    seekToLoopEnd()
                }
            })
            isActive = true
        }
        val actionFilter = IntentFilter().apply {
            addAction(ACTION_TO_START)
            addAction(ACTION_TOGGLE_PLAY)
            addAction(ACTION_TO_END)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(notificationActionReceiver, actionFilter, Context.RECEIVER_NOT_EXPORTED)
            requestNotificationPermission()
        } else {
            registerReceiver(notificationActionReceiver, actionFilter)
        }
        setContentView(buildUi())
        if (savedInstanceState == null) {
            openLastAudioOrPicker()
        } else {
            restoreFromBundle(savedInstanceState)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val current = player
        outState.putString(KEY_URI, currentUri?.toString())
        outState.putLong(KEY_LOOP_START, loopStartMs)
        outState.putLong(KEY_LOOP_END, loopEndMs)
        outState.putLong(KEY_POSITION, current?.takeIf { prepared }?.currentPosition?.toLong() ?: restorePositionMs)
        outState.putBoolean(KEY_SHOULD_PLAY, current?.isPlaying == true)
        outState.putFloatArray(KEY_WAVEFORM, currentWaveform)
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        unregisterReceiver(notificationActionReceiver)
        notificationManager.cancel(NOTIFICATION_ID)
        mediaSession.release()
        releasePlaybackWakeLock()
        player?.release()
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == PICK_AUDIO && resultCode == RESULT_OK && uri != null) {
            persistReadPermission(uri)
            loadAudio(uri)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_POST_NOTIFICATIONS && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            updateMediaNotification()
        }
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this).apply {
            setFillViewport(true)
            clipToPadding = false
            applySystemBarPadding(this)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        statusText = TextView(this).apply { text = "Choose an audio file" }
        root.addView(statusText, LinearLayout.LayoutParams(-1, -2))

        loopRangeView = LoopRangeView(this).apply {
            setListener(object : LoopRangeView.Listener {
                override fun onRangeChanged(startMs: Long, endMs: Long) {
                    loopStartMs = startMs
                    loopEndMs = endMs
                    updateRangeLabel()
                }

                override fun onPositionRequested(positionMs: Long) {
                    player?.takeIf { prepared }?.seekTo(positionMs.toInt())
                }
            })
        }
        root.addView(loopRangeView, LinearLayout.LayoutParams(-1, waveformViewHeight()))

        val rangeEditor = FrameLayout(this)
        rangeLabel = TextView(this).apply {
            text = "Loop: 0:00 - 0:00"
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = makeRaisedBackground()
            setOnClickListener { startRangeEdit() }
        }
        rangeInput = EditText(this).apply {
            setSingleLine(true)
            hint = "00:00-00:28"
            visibility = View.GONE
            setOnEditorActionListener { _, _, _ ->
                commitRangeEdit()
                true
            }
            setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) cancelRangeEdit()
            }
        }
        rangeEditor.addView(rangeLabel, FrameLayout.LayoutParams(-1, -2))
        rangeEditor.addView(rangeInput, FrameLayout.LayoutParams(-1, -2))
        root.addView(rangeEditor, LinearLayout.LayoutParams(-1, -2))

        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        playButton = Button(this).apply {
            text = "Play"
            setOnClickListener { togglePlayback() }
        }
        positionText = TextView(this).apply { text = "0:00 / 0:00" }
        val openButton = Button(this).apply {
            text = "Open"
            setOnClickListener { openAudioPicker() }
        }
        controls.addView(playButton)
        controls.addView(positionText, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(openButton)
        root.addView(controls, LinearLayout.LayoutParams(-1, -2))
        scroll.addView(root, FrameLayout.LayoutParams(-1, -2))
        return scroll
    }

    private fun waveformViewHeight(): Int {
        return if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            dp(132)
        } else {
            dp(260)
        }
    }

    private fun openAudioPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "audio/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, PICK_AUDIO)
    }

    private fun openLastAudioOrPicker() {
        val uriText = preferences().getString(PREF_LAST_URI, null)
        if (uriText.isNullOrBlank()) {
            openAudioPicker()
            return
        }
        if (!loadAudio(Uri.parse(uriText), openPickerOnFailure = true)) {
            clearLastOpenedUri()
            openAudioPicker()
        }
    }

    private fun loadAudio(
        uri: Uri,
        restorePositionMs: Long = 0,
        restoreStartMs: Long? = null,
        restoreEndMs: Long? = null,
        shouldPlay: Boolean = false,
        restoredWaveform: FloatArray? = null,
        openPickerOnFailure: Boolean = false,
    ): Boolean {
        handler.removeCallbacks(tick)
        prepared = false
        currentUri = uri
        val hasRestoredWaveform = restoredWaveform?.isNotEmpty() == true
        currentWaveform = restoredWaveform?.takeIf { hasRestoredWaveform } ?: FloatArray(0)
        loopRangeView.setWaveform(currentWaveform)
        this.restorePositionMs = restorePositionMs
        restoreLoopStartMs = restoreStartMs ?: 0
        restoreLoopEndMs = restoreEndMs ?: 0
        restoreShouldPlay = shouldPlay
        restoringState = restoreStartMs != null && restoreEndMs != null
        player?.release()
        player = MediaPlayer()
        try {
            player?.apply {
                setWakeMode(this@MainActivity, PowerManager.PARTIAL_WAKE_LOCK)
                setDataSource(this@MainActivity, uri)
                setOnPreparedListener {
                    prepared = true
                    val duration = it.duration.toLong()
                    loopRangeView.setDuration(duration)
                    if (restoringState) {
                        val restoredEnd = restoreLoopEndMs.takeIf { value -> value > 0 } ?: duration
                        try {
                            loopRangeView.setRange(restoreLoopStartMs, restoredEnd.coerceAtMost(duration))
                        } catch (_: IllegalArgumentException) {
                            loopRangeView.setRange(0, duration)
                        }
                        val restoredPosition = restorePositionMs.coerceIn(loopStartMs, loopEndMs)
                        it.seekTo(restoredPosition.toInt())
                        loopRangeView.setPosition(restoredPosition)
                    } else {
                        loopEndMs = duration
                    }
                    updateRangeLabel()
                    updatePosition()
                    if (restoreShouldPlay) {
                        it.start()
                        acquirePlaybackWakeLock()
                        playButton.text = "Pause"
                    } else {
                        releasePlaybackWakeLock()
                        playButton.text = "Play"
                    }
                    restoringState = false
                    restoreShouldPlay = false
                    updateMediaNotification()
                    handler.post(tick)
                }
                prepareAsync()
            }
            saveLastOpenedUri(uri)
            statusText.text = displayNameForUri(uri)
            if (!hasRestoredWaveform) {
                val requestId = ++waveformRequestId
                WaveformExtractor.extract(this, uri) { peaks ->
                    if (requestId == waveformRequestId && uri == currentUri) {
                        currentWaveform = peaks
                        loopRangeView.setWaveform(peaks)
                    }
                }
            }
            currentTitle = displayNameForUri(uri)
            updateMediaMetadata()
            updateMediaNotification()
            return true
        } catch (_: Exception) {
            Toast.makeText(this, "Could not open audio file", Toast.LENGTH_LONG).show()
            if (openPickerOnFailure) clearLastOpenedUri()
            return false
        }
    }

    private fun restoreFromBundle(state: Bundle) {
        val uriText = state.getString(KEY_URI)
        if (uriText.isNullOrBlank()) {
            openAudioPicker()
            return
        }
        val uri = Uri.parse(uriText)
        statusText.text = displayNameForUri(uri)
        loadAudio(
            uri = uri,
            restorePositionMs = state.getLong(KEY_POSITION, 0),
            restoreStartMs = state.getLong(KEY_LOOP_START, 0),
            restoreEndMs = state.getLong(KEY_LOOP_END, 0),
            shouldPlay = state.getBoolean(KEY_SHOULD_PLAY, false),
            restoredWaveform = state.getFloatArray(KEY_WAVEFORM),
        )
    }

    private fun persistReadPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
    }

    private fun saveLastOpenedUri(uri: Uri) {
        preferences().edit().putString(PREF_LAST_URI, uri.toString()).apply()
    }

    private fun clearLastOpenedUri() {
        preferences().edit().remove(PREF_LAST_URI).apply()
    }

    private fun preferences() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun togglePlayback() {
        val current = player
        if (current == null || !prepared) {
            openAudioPicker()
            return
        }
        if (current.isPlaying) {
            current.pause()
            releasePlaybackWakeLock()
            playButton.text = "Play"
        } else {
            val position = current.currentPosition.toLong()
            if (position < loopStartMs || position > loopEndMs) {
                current.seekTo(loopStartMs.toInt())
            }
            current.start()
            acquirePlaybackWakeLock()
            playButton.text = "Pause"
        }
        updateMediaNotification()
    }

    private fun updatePosition() {
        val current = player ?: return
        if (!prepared) return
        var position = current.currentPosition.toLong()
        if (loopEndMs > loopStartMs && position >= loopEndMs) {
            current.seekTo(loopStartMs.toInt())
            position = loopStartMs
        }
        loopRangeView.setPosition(position)
        positionText.text = "${TimeUtil.formatMs(position)} / ${TimeUtil.formatMs(current.duration.toLong())}"
        updatePlaybackState()
    }

    private fun seekToLoopStart() {
        player?.takeIf { prepared }?.seekTo(loopStartMs.toInt())
        updatePosition()
        updateMediaNotification()
    }

    private fun seekToLoopEnd() {
        player?.takeIf { prepared }?.seekTo(loopEndMs.toInt())
        updatePosition()
        updateMediaNotification()
    }

    private fun startRangeEdit() {
        rangeInput.setText("${TimeUtil.formatMs(loopStartMs)}-${TimeUtil.formatMs(loopEndMs)}")
        rangeLabel.visibility = View.GONE
        rangeInput.visibility = View.VISIBLE
        rangeInput.requestFocus()
        rangeInput.selectAll()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(rangeInput, 0)
    }

    private fun commitRangeEdit() {
        if (rangeInput.visibility != View.VISIBLE) return
        try {
            val range = TimeUtil.parseRangeMs(rangeInput.text.toString())
            val duration = player?.duration?.toLong() ?: 0
            require(duration <= 0 || range[1] <= duration) { "Loop end is past audio duration" }
            loopRangeView.setRange(range[0], range[1])
            player?.takeIf { prepared }?.let {
                it.seekTo(max(range[0], min(it.currentPosition.toLong(), range[1])).toInt())
            }
        } catch (error: IllegalArgumentException) {
            Toast.makeText(this, error.message, Toast.LENGTH_LONG).show()
        }
        cancelRangeEdit()
    }

    private fun cancelRangeEdit() {
        rangeInput.visibility = View.GONE
        rangeLabel.visibility = View.VISIBLE
    }

    private fun updateRangeLabel() {
        rangeLabel.text = "Loop: ${TimeUtil.formatMs(loopStartMs)} - ${TimeUtil.formatMs(loopEndMs)}"
    }

    private fun makeRaisedBackground(): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.rgb(243, 245, 248))
            setStroke(dp(1), Color.rgb(111, 119, 130))
            cornerRadius = dp(3).toFloat()
        }
    }

    private fun applySystemBarPadding(root: View) {
        val base = dp(12)
        root.setOnApplyWindowInsetsListener { view, insets ->
            val systemBars = insets.getInsets(WindowInsets.Type.systemBars())
            view.setPadding(
                base + systemBars.left,
                base + systemBars.top,
                base + systemBars.right,
                base + systemBars.bottom,
            )
            insets
        }
    }

    private fun displayNameForUri(uri: Uri): String {
        queryDisplayName(uri)?.let { return it }
        val raw = uri.lastPathSegment ?: uri.toString()
        val decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8.name())
        return decoded.substringAfterLast('/').substringAfterLast(':').ifBlank { decoded }
    }

    private fun queryDisplayName(uri: Uri): String? {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            cursor?.close()
        }
    }

    private fun acquirePlaybackWakeLock() {
        if (playbackWakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            playbackWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:playback")
        }
        playbackWakeLock?.takeIf { !it.isHeld }?.acquire()
    }

    private fun releasePlaybackWakeLock() {
        playbackWakeLock?.takeIf { it.isHeld }?.release()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun updateMediaMetadata() {
        val duration = player?.takeIf { prepared }?.duration?.toLong() ?: 0
        mediaSession.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, currentTitle)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Loop ${TimeUtil.formatMs(loopStartMs)} - ${TimeUtil.formatMs(loopEndMs)}")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
                .build()
        )
    }

    private fun updatePlaybackState() {
        val current = player
        val isPlaying = current?.isPlaying == true
        val state = if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        val position = current?.takeIf { prepared }?.currentPosition?.toLong() ?: 0
        mediaSession.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SEEK_TO or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_SKIP_TO_NEXT
                )
                .setState(state, position, if (isPlaying) 1f else 0f)
                .build()
        )
    }

    private fun updateMediaNotification() {
        if (!prepared || !canPostNotifications()) return
        updateMediaMetadata()
        updatePlaybackState()
        val current = player
        val isPlaying = current?.isPlaying == true
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        val notification = builder
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(currentTitle)
            .setContentText("Loop ${TimeUtil.formatMs(loopStartMs)} - ${TimeUtil.formatMs(loopEndMs)}")
            .setContentIntent(activityPendingIntent())
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setShowWhen(false)
            .setOngoing(isPlaying)
            .addAction(R.drawable.ic_loop_start, "Loop start", actionPendingIntent(ACTION_TO_START, 1))
            .addAction(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play, "Play/Pause", actionPendingIntent(ACTION_TOGGLE_PLAY, 2))
            .addAction(R.drawable.ic_loop_end, "Loop end", actionPendingIntent(ACTION_TO_END, 3))
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !canPostNotifications()) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_POST_NOTIFICATIONS)
        }
    }

    private fun canPostNotifications(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun actionPendingIntent(action: String, requestCode: Int): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(this, requestCode, Intent(action).setPackage(packageName), flags)
    }

    private fun activityPendingIntent(): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(this, 10, Intent(this, MainActivity::class.java), flags)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val PICK_AUDIO = 1001
        private const val NOTIFICATION_ID = 7
        private const val REQUEST_POST_NOTIFICATIONS = 2001
        private const val NOTIFICATION_CHANNEL_ID = "playback"
        private const val ACTION_TO_START = "org.lighilit.control_loop_music.TO_START"
        private const val ACTION_TOGGLE_PLAY = "org.lighilit.control_loop_music.TOGGLE_PLAY"
        private const val ACTION_TO_END = "org.lighilit.control_loop_music.TO_END"
        private const val KEY_URI = "uri"
        private const val KEY_LOOP_START = "loop_start"
        private const val KEY_LOOP_END = "loop_end"
        private const val KEY_POSITION = "position"
        private const val KEY_SHOULD_PLAY = "should_play"
        private const val KEY_WAVEFORM = "waveform"
        private const val PREFS_NAME = "playback"
        private const val PREF_LAST_URI = "last_uri"
    }
}
