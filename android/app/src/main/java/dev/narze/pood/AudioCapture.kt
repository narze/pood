package dev.narze.pood

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import kotlin.concurrent.thread
import kotlin.math.sqrt

/** Records 16 kHz mono PCM16 in 100 ms frames on its own thread. */
class AudioCapture(
    private val onFrame: (ByteArray) -> Unit,
    private val onLevel: (Float) -> Unit,
) {
    @Volatile private var running = false
    private var worker: Thread? = null

    /** Requires RECORD_AUDIO; throws if the mic can't be opened. */
    @SuppressLint("MissingPermission")
    fun start() {
        check(!running) { "already recording" }
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, FRAME_BYTES * 4),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("Microphone unavailable")
        }
        record.startRecording()
        running = true
        worker = thread(name = "pood-audio") {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf = ByteArray(FRAME_BYTES)
            while (running) {
                var n = 0
                while (n < FRAME_BYTES && running) {
                    val r = record.read(buf, n, FRAME_BYTES - n)
                    if (r <= 0) break
                    n += r
                }
                if (n > 0) {
                    onFrame(buf.copyOf(n - n % 2))
                    onLevel(level(buf, n))
                }
            }
            record.stop()
            record.release()
            onLevel(0f)
        }
    }

    /** Stops and waits until the last frame has been delivered. */
    fun stop() {
        running = false
        worker?.join(1000)
        worker = null
    }

    private fun level(buf: ByteArray, n: Int): Float {
        var sum = 0.0
        for (i in 0 until n - 1 step 2) {
            val s = (buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8)
            sum += s.toDouble() * s
        }
        val rms = sqrt(sum / (n / 2)) / 32768.0
        return (rms * 6).coerceIn(0.0, 1.0).toFloat()
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_BYTES = 3200 // 100 ms
    }
}
