package com.bookmari.walkie

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/**
 * Monologue (10/9): one long recording that ignores pauses. We own the mic and feed the recognizer
 * through a pipe in one segmented session (API 33 EXTRA_AUDIO_SOURCE), which only ends when we close the pipe,
 * so there's no session-per-pause restarting. The audio is also kept in [file]: if recognition fails it is
 * fed again from there. test phone (10/9): fed in full, no restarts across 5 s pauses. Callbacks on the main thread.
 */
@RequiresApi(33)
class Monologue(
    private val ctx: Context,
    private val onPartial: (String) -> Unit,
    private val onLevel: (Float) -> Unit = {},
    private val onRouted: (AudioDeviceInfo?) -> Unit = {},
    private val onDone: (text: String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val file = File(ctx.cacheDir, "monologue.pcm") // ponytail: only the latest one is kept
    @Volatile private var recording = true
    private val chunks = LinkedBlockingQueue<ByteArray>()
    private var recognizer: SpeechRecognizer? = null
    private var heard = ""
    private var retried = false
    private var restarts = 0
    private var restarting = false
    private val live = { chunks.take().takeIf { it !== END } }
    private var done = false

    @SuppressLint("MissingPermission") // the walkie only runs with the mic granted
    fun start() {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE)) // ≥ 0.5 s of buffer
        Mic.device(ctx, Mic.pref(ctx))?.let { rec.preferredDevice = it }
        rec.startRecording()
        thread(name = "monologue-rec") {
            FileOutputStream(file).use { out ->
                val buf = ByteArray(3_200) // 0.1 s
                var routedId = -2
                while (recording) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    out.write(buf, 0, n)
                    chunks.put(buf.copyOf(n))
                    val level = MicLevel.level(buf, n)
                    val device = rec.routedDevice // known once recording runs; may change when a headset connects
                    val id = device?.id ?: -1
                    main.post {
                        if (id != routedId) { routedId = id; onRouted(device) }
                        onLevel(level)
                    }
                }
            }
            rec.stop()
            rec.release()
            chunks.put(END)
        }
        listen(live)
    }

    /** Ends the recording; the text arrives in onDone once the recognizer has read the rest. */
    fun stop() {
        recording = false
        // No session left to read the rest (it gave up mid-recording): settle once the file is complete.
        main.postDelayed({ if (recognizer == null && !restarting) settle() }, 300)
    }

    /** One segmented session fed by [next] (null = end of audio). */
    private fun listen(next: () -> ByteArray?) {
        val (read, write) = ParcelFileDescriptor.createPipe()
        thread(name = "monologue-feed") {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(write).use { out ->
                    while (true) {
                        val c = next() ?: break
                        out.write(c)
                    }
                }
            } catch (_: Exception) { // the recognizer went away; the recording carries on (file + queue for the next session)
            }
        }
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onSegmentResults(segmentResults: Bundle) = add(segmentResults)
            override fun onEndOfSegmentedSession() = finish(r, read)
            override fun onResults(results: Bundle) { add(results); finish(r, read) } // a recognizer without segments
            override fun onPartialResults(partialResults: Bundle?) {
                val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                onPartial("$heard $t".trim())
            }
            override fun onError(error: Int) = finish(r, read)
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, read)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, RATE)
            .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE))
    }

    private fun add(b: Bundle) {
        val t = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        if (t.isNullOrBlank()) return
        heard = "$heard $t".replace(Regex("\\s+"), " ").trim() // segments may carry their own spaces
        onPartial(heard)
    }

    private fun finish(r: SpeechRecognizer, read: ParcelFileDescriptor) {
        if (recognizer !== r) return
        r.destroy()
        recognizer = null
        runCatching { read.close() }
        if (recording) { // the recognizer quit mid-recording: a new session carries on from the queue
            if (restarts++ < 5) {
                restarting = true
                main.postDelayed({ restarting = false; if (recognizer == null && !done) listen(live) }, 300L * restarts)
            }
            return
        }
        settle()
    }

    private fun settle() = if (heard.isEmpty() && !retried && file.length() > 0) retry() else end()

    /** Once, when nothing was heard: the whole file through a fresh session. */
    private fun retry() {
        retried = true
        val input = file.inputStream()
        val buf = ByteArray(3_200)
        listen { input.read(buf).takeIf { it > 0 }?.let { buf.copyOf(it) } ?: run { input.close(); null } }
    }

    private fun end() {
        if (done) return
        done = true
        onDone(heard)
    }

    companion object {
        private const val RATE = 16_000
        private val END = ByteArray(0)
    }
}
