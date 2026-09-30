package com.tailconnect.app

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object AudioIntercomManager {

    private const val TAG = "AudioIntercomManager"
    const val SAMPLE_RATE = 16000
    private const val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO
    private const val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    private var audioTrack: AudioTrack? = null
    private var audioRecord: AudioRecord? = null
    private var recordJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    var isMicStreaming = false
        private set

    // -------------------------------------------------------------
    // PC -> Phone Speaker (Walkie-Talkie Output / Wiretap Playback)
    // -------------------------------------------------------------
    @Synchronized
    fun initSpeakerTrack(context: Context? = null) {
        if (audioTrack != null && audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
            return
        }

        try {
            // Ensure speaker routing is set to media playback at high volume
            if (context != null) {
                try {
                    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    audioManager.mode = AudioManager.MODE_NORMAL
                    val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    if (currentVol < (maxVol * 0.7f).toInt()) {
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (maxVol * 0.9f).toInt(), 0)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to configure speaker routing: ${e.message}")
                }
            }

            val minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_OUT, AUDIO_FORMAT)
            val bufferSize = (minBufferSize * 4).coerceAtLeast(8192)

            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            val format = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_CONFIG_OUT)
                .setEncoding(AUDIO_FORMAT)
                .build()

            val track = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (track.state == AudioTrack.STATE_INITIALIZED) {
                track.play()
                audioTrack = track
                Log.i(TAG, "AudioTrack initialized successfully for speakerphone playback at ${SAMPLE_RATE}Hz")
            } else {
                track.release()
                Log.e(TAG, "AudioTrack failed to initialize (state != STATE_INITIALIZED)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioTrack: ${e.message}")
        }
    }

    @Synchronized
    fun playAudioChunk(context: Context? = null, pcmBytes: ByteArray) {
        if (pcmBytes.isEmpty()) return
        initSpeakerTrack(context)
        val track = audioTrack ?: return

        try {
            // Apply 2.5x digital gain with soft clipping to make PC microphone wiretap crystal clear
            val boosted = ByteArray(pcmBytes.size)
            val numSamples = pcmBytes.size / 2
            val gain = 2.5f

            for (i in 0 until numSamples) {
                val bLo = pcmBytes[i * 2].toInt() and 0xFF
                val bHi = pcmBytes[i * 2 + 1].toInt()
                val sample = ((bHi shl 8) or bLo).toShort()

                var amplified = (sample * gain).toInt()
                if (amplified > 32767) amplified = 32767
                else if (amplified < -32768) amplified = -32768

                boosted[i * 2] = (amplified and 0xFF).toByte()
                boosted[i * 2 + 1] = ((amplified shr 8) and 0xFF).toByte()
            }

            track.write(boosted, 0, boosted.size)
        } catch (e: Exception) {
            Log.w(TAG, "Error writing PCM to AudioTrack: ${e.message}")
        }
    }

    fun setSpeakerVolume(context: Context, volumePercent: Int) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val targetVolume = ((volumePercent.coerceIn(0, 100) / 100f) * maxVolume).toInt()
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
            Log.i(TAG, "Phone speaker volume set to $volumePercent% ($targetVolume/$maxVolume)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set speaker volume: ${e.message}")
        }
    }

    fun playAttentionChime() {
        scope.launch {
            try {
                val toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100)
                toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
                kotlinx.coroutines.delay(250)
                toneGen.startTone(ToneGenerator.TONE_PROP_BEEP2, 250)
                kotlinx.coroutines.delay(300)
                toneGen.release()
            } catch (e: Exception) {
                Log.w(TAG, "Could not play chime: ${e.message}")
            }
        }
    }

    // -------------------------------------------------------------
    // Phone Mic -> PC (Push-to-Talk Recording)
    // -------------------------------------------------------------
    @Synchronized
    fun startMicStream(context: Context, onAudioChunk: (ByteArray) -> Unit): Boolean {
        if (isMicStreaming) return true

        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Cannot start mic stream: RECORD_AUDIO permission not granted")
            return false
        }

        try {
            val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT)
            val bufferSize = (minBufferSize * 2).coerceAtLeast(3200)

            // Try candidate sources: VOICE_COMMUNICATION has built-in AEC, followed by MIC and DEFAULT
            val sources = intArrayOf(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.DEFAULT,
                MediaRecorder.AudioSource.VOICE_RECOGNITION
            )

            var selectedRecord: AudioRecord? = null
            for (source in sources) {
                try {
                    val record = AudioRecord(
                        source,
                        SAMPLE_RATE,
                        CHANNEL_CONFIG_IN,
                        AUDIO_FORMAT,
                        bufferSize
                    )
                    if (record.state == AudioRecord.STATE_INITIALIZED) {
                        selectedRecord = record
                        Log.i(TAG, "AudioRecord initialized with audio source: $source")
                        break
                    } else {
                        record.release()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "AudioSource $source failed: ${e.message}")
                }
            }

            if (selectedRecord == null) {
                Log.e(TAG, "AudioRecord failed to initialize with all candidate sources")
                return false
            }

            audioRecord = selectedRecord
            audioRecord?.startRecording()
            isMicStreaming = true
            Log.i(TAG, "AudioRecord started at ${SAMPLE_RATE}Hz")

            recordJob = scope.launch {
                val chunk = ByteArray(1600) // ~50ms of 16kHz 16-bit mono audio
                while (isActive && isMicStreaming) {
                    val readBytes = audioRecord?.read(chunk, 0, chunk.size) ?: -1
                    if (readBytes > 0) {
                        val payload = chunk.copyOf(readBytes)
                        onAudioChunk(payload)
                    }
                }
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioRecord: ${e.message}")
            stopMicStream()
            return false
        }
    }

    @Synchronized
    fun stopMicStream() {
        isMicStreaming = false
        recordJob?.cancel()
        recordJob = null
        try {
            if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord?.stop()
            }
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord: ${e.message}")
        }
        audioRecord = null
        Log.i(TAG, "AudioRecord stopped")
    }

    @Synchronized
    fun releaseAll(context: Context? = null) {
        stopMicStream()
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null

        if (context != null) {
            try {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.mode = AudioManager.MODE_NORMAL
            } catch (_: Exception) {}
        }
    }
}
