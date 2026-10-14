package app.einknotes.notes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import java.io.File

/**
 * Audio notes: one recording at a time, played back one at a time. The files are small mono AAC.
 * Items written during a recording remember the recording and the moment ([Item.recId], [Item.recMs]),
 * so later a tap on a stroke can jump the audio to the moment it was written.
 */
object AudioNotes {
    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null
    private var startedAt = 0L

    /** Playback speed (1, 1.5 or 2). */
    var speed = 1f
        set(v) {
            field = v
            try { if (player?.isPlaying == true) player?.playbackParams = player!!.playbackParams.setSpeed(v) } catch (_: Throwable) { }
        }

    /** The recording in progress, if any. */
    var active: Recording? = null
        private set

    /** The recording being played, and whether it is playing right now. */
    var playing: Recording? = null
        private set
    val isPlaying get() = try { player?.isPlaying == true } catch (_: Throwable) { false }

    fun dir(ctx: Context): File = File(ctx.filesDir, "notes/audio").also { it.mkdirs() }
    fun fileOf(ctx: Context, r: Recording) = File(dir(ctx), r.file)

    fun elapsedMs(): Int = if (active != null) (SystemClock.elapsedRealtime() - startedAt).toInt() else 0
    /** The SystemClock.elapsedRealtime() at which the recording in progress began. */
    fun startedElapsed() = startedAt

    /** Starts recording into a new file and adds it to the notebook. Throws if the microphone cannot be opened. */
    fun start(ctx: Context, note: NoteData): Recording {
        stopRecording(ctx, note)
        stopPlayback()
        val id = (note.recordings.maxOfOrNull { it.id } ?: 0) + 1
        val rec = Recording(id, "rec-" + java.lang.Long.toString(System.currentTimeMillis(), 36) + ".m4a", System.currentTimeMillis(), 0)
        val r = MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(22050)
            r.setAudioEncodingBitRate(48000)
            r.setOutputFile(fileOf(ctx, rec).absolutePath)
            r.prepare()
            r.start()
        } catch (t: Throwable) {
            try { r.release() } catch (_: Throwable) { }
            fileOf(ctx, rec).delete()
            throw t
        }
        recorder = r; active = rec
        startedAt = SystemClock.elapsedRealtime()
        note.recordings.add(rec)
        try {
            val i = Intent(ctx, RecordingService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        } catch (_: Throwable) { }
        return rec
    }

    /** Ends the recording in progress and returns it (with its length), or null if nothing was recording. */
    fun stopRecording(ctx: Context, note: NoteData): Recording? {
        val rec = active ?: return null
        val ms = elapsedMs()
        try { recorder?.stop() } catch (_: Throwable) { }
        try { recorder?.release() } catch (_: Throwable) { }
        recorder = null; active = null
        rec.durationMs = ms
        if (!fileOf(ctx, rec).exists() || fileOf(ctx, rec).length() < 200) note.recordings.remove(rec)
        try { ctx.stopService(Intent(ctx, RecordingService::class.java)) } catch (_: Throwable) { }
        return rec
    }

    fun play(ctx: Context, rec: Recording, fromMs: Int, onDone: () -> Unit): Boolean {
        stopPlayback()
        val f = fileOf(ctx, rec)
        if (!f.exists()) return false
        val p = MediaPlayer()
        return try {
            p.setDataSource(f.absolutePath)
            p.prepare()
            if (fromMs > 0) p.seekTo(fromMs.coerceIn(0, p.duration))
            p.setOnCompletionListener { stopPlayback(); onDone() }
            p.start()
            if (speed != 1f) try { p.playbackParams = p.playbackParams.setSpeed(speed) } catch (_: Throwable) { }
            player = p; playing = rec
            true
        } catch (_: Throwable) { try { p.release() } catch (_: Throwable) { }; false }
    }

    fun pause() { try { player?.pause() } catch (_: Throwable) { } }
    fun resume() { try { player?.start() } catch (_: Throwable) { } }
    fun positionMs(): Int = try { player?.currentPosition ?: 0 } catch (_: Throwable) { 0 }
    fun durationMs(): Int = try { player?.duration ?: 0 } catch (_: Throwable) { 0 }
    fun seekTo(ms: Int) { try { player?.seekTo(ms.coerceIn(0, durationMs())) } catch (_: Throwable) { } }

    fun stopPlayback() {
        try { player?.stop() } catch (_: Throwable) { }
        try { player?.release() } catch (_: Throwable) { }
        player = null; playing = null
    }

    /** Deletes the audio file and forgets every link from the notebook's items to it. */
    fun delete(ctx: Context, note: NoteData, rec: Recording) {
        if (playing?.id == rec.id) stopPlayback()
        fileOf(ctx, rec).delete()
        note.recordings.remove(rec)
        for (p in note.pages) for (it in p.items) if (it.recId == rec.id) { it.recId = 0; it.recMs = 0 }
    }

    /** Audio files no notebook refers to any more (called after a notebook is deleted). */
    fun collectGarbage(ctx: Context, used: Set<String>) {
        dir(ctx).listFiles()?.forEach { if (it.name !in used) it.delete() }
    }

    fun clock(ms: Int): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
}

/** Does nothing but keep the app in the foreground while a recording runs, so the system does not stop the microphone. */
class RecordingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("recording", "Audio recording", NotificationManager.IMPORTANCE_LOW))
            Notification.Builder(this, "recording")
        } else @Suppress("DEPRECATION") Notification.Builder(this)
        builder.setContentTitle("Ink Notes is recording").setContentText("Tap to return to your notes")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setOngoing(true)
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            builder.setContentIntent(android.app.PendingIntent.getActivity(this, 0, it, android.app.PendingIntent.FLAG_IMMUTABLE))
        }
        val n = builder.build()
        startForeground(7, n)
        return START_NOT_STICKY
    }
}
