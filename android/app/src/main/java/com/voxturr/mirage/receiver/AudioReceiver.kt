package com.voxturr.mirage.receiver

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import com.voxturr.mirage.core.AudioConfig
import com.voxturr.mirage.core.SecureChannel
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Plays the host's audio in sync with the host's own speakers (spec §7).
 *
 * The UDP socket is bound for the whole receiver lifetime (its port goes into ClientHello);
 * playback starts on audioConfig and stops on audioStop / disconnect.
 *  - "mirage-audio-rx" (URGENT_AUDIO): receive → (decrypt) → [JitterBuffer] keyed by seq.
 *  - "mirage-audio-out" (URGENT_AUDIO): for each packet, computes when the next written frame
 *    will reach the speaker (AudioTrack.getTimestamp) vs. when the packet should play
 *    (hostTime + playoutDelay mapped through ClockSync), lets [PlayoutController] choose
 *    silence / trim / drop / resample, and writes to a LOW_LATENCY AudioTrack (blocking writes pace the loop).
 */
class AudioReceiver {

    /** Where buffer depth / sync error are reported (the current session's stats). */
    @Volatile var stats: ReceiverStats = ReceiverStats()

    /** Host clock → this device; supplied per session (PeerLink.clock in production). */
    interface HostClock {
        val ready: Boolean
        /** Host monotonic µs → local System.nanoTime() ns. */
        fun hostMicrosToLocalNanos(hostMicros: Long): Long
    }

    private val socket = DatagramSocket(null as java.net.SocketAddress?).apply {
        reuseAddress = true
        try { trafficClass = 0xB8 } catch (_: Exception) {}
        receiveBufferSize = 256 * 1024
        bind(InetSocketAddress(0))
    }
    val port: Int = socket.localPort

    private val jitter = JitterBuffer()

    @Volatile private var config: AudioConfig? = null
    @Volatile private var clock: HostClock? = null
    @Volatile private var channel: (() -> SecureChannel?)? = null
    @Volatile private var source: InetAddress? = null
    @Volatile private var generation = 0
    @Volatile private var closed = false
    private var writer: Thread? = null
    private val writerLock = Object()

    private val rxThread = Thread({ receiveLoop() }, "mirage-audio-rx").apply { isDaemon = true; start() }

    /** Starts (or restarts) playback. [source] = the host's address; packets from elsewhere are ignored. */
    fun start(cfg: AudioConfig, hostClock: HostClock, source: InetAddress?, channel: () -> SecureChannel?) = synchronized(writerLock) {
        stopPlaybackLocked()
        this.clock = hostClock
        this.channel = channel
        this.source = source
        jitter.clear()
        this.config = cfg
        val gen = ++generation
        writer = Thread({ writeLoop(cfg, hostClock, gen) }, "mirage-audio-out").apply { start() }
        Log.i(TAG, "audio start: ${cfg.sampleRate} Hz ×${cfg.channels}, ${cfg.packetFrames} frames/packet, delay ${cfg.playoutDelayMs} ms, enc=${cfg.encrypted}")
    }

    fun stopPlayback() = synchronized(writerLock) { stopPlaybackLocked() }

    private fun stopPlaybackLocked() {
        config = null
        generation++
        writer?.let { it.interrupt(); it.join(500) }
        writer = null
        jitter.clear()
        stats.audioBufferedMs = 0.0
    }

    fun close() {
        stopPlayback()
        closed = true
        socket.close()
    }

    // ---------------------------------------------------------------------------------------
    // Receive
    // ---------------------------------------------------------------------------------------

    private fun receiveLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = ByteArray(8192)
        val packet = DatagramPacket(buf, buf.size)
        val header = ByteArray(AudioPacket.HEADER_BYTES) // reused for the encrypted path's AAD
        while (!closed) {
            try {
                packet.setData(buf, 0, buf.size)
                socket.receive(packet)
            } catch (e: Exception) {
                if (closed) return
                continue
            }
            val cfg = config ?: continue
            val len = packet.length
            if (len <= AudioPacket.HEADER_BYTES || AudioPacket.version(buf) != AudioPacket.VERSION) continue
            val src = source
            if (src != null && packet.address != src) continue
            val seq = AudioPacket.seq(buf)
            val hostTime = AudioPacket.hostTimeMicros(buf)
            if (AudioPacket.flags(buf) and AudioPacket.FLAG_ENCRYPTED != 0) {
                val ch = channel?.invoke() ?: continue
                System.arraycopy(buf, 0, header, 0, AudioPacket.HEADER_BYTES)
                val sealed = buf.copyOfRange(AudioPacket.HEADER_BYTES, len)
                val pcm = try { ch.openAudio(seq, header, sealed) } catch (e: Exception) { continue }
                jitter.put(seq, hostTime, pcm, 0, pcm.size)
            } else if (!cfg.encrypted) {
                jitter.put(seq, hostTime, buf, AudioPacket.HEADER_BYTES, len - AudioPacket.HEADER_BYTES)
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Playout
    // ---------------------------------------------------------------------------------------

    private fun writeLoop(cfg: AudioConfig, hostClock: HostClock, gen: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val sr = cfg.sampleRate
        val ch = cfg.channels.coerceIn(1, 2)
        val channelMask = if (ch == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuf = AudioTrack.getMinBufferSize(sr, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(sr)
                    .setChannelMask(channelMask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build())
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(minBuf * 2)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack creation failed", e)
            return
        }

        val packetFrames = cfg.packetFrames.coerceAtLeast(1)
        val packetUs = packetFrames * 1_000_000L / sr
        val delayUs = cfg.playoutDelayMs * 1000L
        val pcm = ShortArray(4096)
        val resampler = LinearResampler(ch)
        val out = ShortArray(resampler.maxOutFrames(4096 / ch, 1.0 - PlayoutController.MAX_DEVIATION) * ch)
        val silence = ShortArray(sr / 100 * ch) // 10 ms
        val controller = PlayoutController(sr)
        val ts = AudioTimestamp()
        var written = 0L
        var nextSeq = -1L
        var lastHostTime = 0L
        val assumedLatencyNs = 20_000_000L

        fun write(buf: ShortArray, frames: Int): Boolean {
            var off = 0
            val total = frames * ch
            while (off < total) {
                val n = track.write(buf, off, total - off, AudioTrack.WRITE_BLOCKING)
                if (n <= 0) return false
                off += n
            }
            written += frames
            return true
        }
        fun writeSilence(frames: Int): Boolean {
            var left = frames
            while (left > 0) {
                val n = minOf(left, silence.size / ch)
                if (!write(silence, n)) return false
                left -= n
            }
            return true
        }
        fun nextOutNanos(): Long =
            if (track.getTimestamp(ts) && ts.nanoTime > 0) {
                SpeakerClock.nextFrameNanos(ts.framePosition, ts.nanoTime, written, sr)
            } else {
                SpeakerClock.estimateNanos(System.nanoTime(), track.playbackHeadPosition.toLong() and 0xffffffffL,
                    written, sr, assumedLatencyNs)
            }

        try {
            track.play()
            writeSilence(packetFrames) // prime
            while (gen == generation && !Thread.currentThread().isInterrupted) {
                // Nothing to play yet (stream not started, gap, or no clock estimate): keep the
                // track running on silence so restarting costs no extra latency.
                if (nextSeq < 0) {
                    if (!hostClock.ready || jitter.size == 0) {
                        if (!hostClock.ready) jitter.discardThrough(jitter.newestSeq)
                        if (!writeSilence(packetFrames)) break
                        continue
                    }
                    nextSeq = jitter.oldestSeq()
                    if (nextSeq < 0) continue
                    lastHostTime = jitter.hostTimeOf(nextSeq, 0)
                    controller.reset(); resampler.reset()
                }
                // Stream went quiet for a while: drop back to idle and relock when it resumes.
                if (jitter.size == 0 && jitter.newestSeq < nextSeq - IDLE_PACKETS) {
                    nextSeq = -1
                    continue
                }

                val hostTime = jitter.hostTimeOf(nextSeq, lastHostTime)
                val target = hostClock.hostMicrosToLocalNanos(hostTime + delayUs)
                val error = nextOutNanos() - target
                stats.audioErrorMs = error / 1e6
                stats.audioBufferedMs = jitter.size * packetUs / 1000.0
                val d = controller.decide(error, packetFrames)
                when (d.action) {
                    PlayoutController.Action.SILENCE -> {
                        if (!writeSilence(minOf(d.frames, sr / 50))) break // ≤20 ms at a time, then re-check
                    }
                    PlayoutController.Action.DROP -> {
                        jitter.discardThrough(nextSeq)
                        nextSeq++; lastHostTime = hostTime + packetUs
                    }
                    PlayoutController.Action.TRIM, PlayoutController.Action.PLAY -> {
                        val n = jitter.take(nextSeq, pcm)
                        nextSeq++; lastHostTime = hostTime + packetUs
                        if (n < 0) {
                            // Lost or late: conceal with silence for its slot, keep the timeline.
                            if (!writeSilence(packetFrames)) break
                            continue
                        }
                        val frames = n / ch
                        if (d.action == PlayoutController.Action.TRIM) {
                            val skip = d.frames.coerceAtMost(frames)
                            if (skip < frames) {
                                System.arraycopy(pcm, skip * ch, pcm, 0, (frames - skip) * ch)
                                if (!write(pcm, frames - skip)) break
                            }
                            resampler.reset()
                        } else {
                            // Always through the resampler (exact copy at ratio 1) so its phase
                            // stays continuous when the controller moves on and off 1.0.
                            val o = resampler.process(pcm, frames, d.ratio, out)
                            if (!write(out, o)) break
                        }
                    }
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            Log.w(TAG, "audio playout stopped", e)
        } finally {
            try { track.pause(); track.flush() } catch (_: Exception) {}
            track.release()
        }
    }

    private companion object {
        const val TAG = "Mirage"
        /** ~100 ms of 5 ms packets with nothing arriving → idle. */
        const val IDLE_PACKETS = 20
    }
}
