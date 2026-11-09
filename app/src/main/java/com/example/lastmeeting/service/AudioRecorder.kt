package com.example.lastmeeting.service

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/** Owns one recorder, including instances whose configuration or startup fails. */
internal interface AudioRecorder {
    fun configure(outputFile: File)
    fun prepare()
    fun start()
    fun stop()
    fun release()
}

internal class AndroidAudioRecorder(context: Context) : AudioRecorder {
    private val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        MediaRecorder(context)
    } else {
        @Suppress("DEPRECATION")
        MediaRecorder()
    }

    override fun configure(outputFile: File) {
        recorder.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioChannels(1)
            setAudioEncodingBitRate(AUDIO_BIT_RATE)
            setAudioSamplingRate(AUDIO_SAMPLE_RATE)
            setOutputFile(outputFile.absolutePath)
        }
    }

    override fun prepare() = recorder.prepare()
    override fun start() = recorder.start()
    override fun stop() = recorder.stop()
    override fun release() = recorder.release()

    companion object {
        /** 32 kbps / 16 kHz mono AAC keeps a 1-hour meeting at ~14.4 MB, well under the 25 MB upload limit. */
        internal const val AUDIO_BIT_RATE = 32_000
        internal const val AUDIO_SAMPLE_RATE = 16_000
    }
}
