package com.example.lastmeeting.util

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Locale
import java.util.UUID

class AudioSegmentationSession(
    val segments: List<File>,
    private val cleanupAction: () -> Unit = {}
) {
    fun cleanup() = cleanupAction()
}

fun interface AudioSegmenter {
    suspend fun segmentIfNeeded(audioFile: File, maxSegmentBytes: Long): Result<AudioSegmentationSession>
}

class AndroidAudioSegmenter(
    private val tempRootDirProvider: () -> File
) : AudioSegmenter {

    override suspend fun segmentIfNeeded(
        audioFile: File,
        maxSegmentBytes: Long
    ): Result<AudioSegmentationSession> = withContext(Dispatchers.IO) {
        if (!audioFile.exists() || audioFile.length() <= 0L) {
            return@withContext Result.failure(
                IllegalArgumentException("Audio file does not exist or is empty.")
            )
        }
        if (audioFile.length() <= maxSegmentBytes) {
            return@withContext Result.success(AudioSegmentationSession(listOf(audioFile)))
        }

        val rootDir = tempRootDirProvider()
        if (!rootDir.isDirectory && !rootDir.mkdirs() && !rootDir.isDirectory) {
            return@withContext Result.failure(IOException("Unable to create temporary segmentation directory."))
        }

        val sessionDir = File(rootDir, "seg_${UUID.randomUUID()}")
        if (!sessionDir.mkdirs()) {
            return@withContext Result.failure(IOException("Unable to create segment session directory."))
        }

        val payloadBudgetBytes = (maxSegmentBytes * 9L / 10L).coerceAtLeast(64L * 1024L).coerceAtMost(maxSegmentBytes)
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        val segments = mutableListOf<File>()

        try {
            extractor.setDataSource(audioFile.absolutePath)
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") == true) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }
            if (audioTrackIndex < 0 || audioFormat == null) {
                throw IOException("No audio track found in ${audioFile.name}.")
            }
            extractor.selectTrack(audioTrackIndex)

            val maxInputSize = if (audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(64 * 1024)
            } else {
                256 * 1024
            }
            val buffer = ByteBuffer.allocateDirect(maxInputSize)
            val bufferInfo = MediaCodec.BufferInfo()
            var segmentIndex = 0
            var currentSegmentBytes = 0L
            var segmentStartUs = -1L
            var muxerTrackIndex = -1

            while (true) {
                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                if (muxer == null || (currentSegmentBytes > 0L && currentSegmentBytes + sampleSize > payloadBudgetBytes)) {
                    muxer?.let { active ->
                        active.stop()
                        active.release()
                    }
                    muxer = null

                    segmentIndex++
                    val outFile = File(sessionDir, String.format(Locale.US, "segment_%04d.m4a", segmentIndex))
                    segments.add(outFile)
                    val created = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    muxerTrackIndex = created.addTrack(audioFormat)
                    created.start()
                    muxer = created
                    currentSegmentBytes = 0L
                    segmentStartUs = -1L
                }

                val rawPtsUs = extractor.sampleTime
                if (segmentStartUs < 0L) {
                    segmentStartUs = rawPtsUs
                }
                val codecFlags = if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else {
                    0
                }
                buffer.position(0)
                buffer.limit(sampleSize)
                bufferInfo.set(
                    0,
                    sampleSize,
                    (rawPtsUs - segmentStartUs).coerceAtLeast(0L),
                    codecFlags
                )
                muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                currentSegmentBytes += sampleSize
                extractor.advance()
            }

            muxer?.let { active ->
                active.stop()
                active.release()
                muxer = null
            }

            check(segments.isNotEmpty()) { "No audio samples found in ${audioFile.name}." }
            for (segment in segments) {
                val len = segment.length()
                check(len in 1..maxSegmentBytes) {
                    "Segment ${segment.name} has invalid size $len (max $maxSegmentBytes)."
                }
            }

            Result.success(
                AudioSegmentationSession(segments = segments) {
                    sessionDir.deleteRecursively()
                }
            )
        } catch (e: Exception) {
            runCatching { muxer?.release() }
            muxer = null
            sessionDir.deleteRecursively()
            Result.failure(e)
        } finally {
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }
}
