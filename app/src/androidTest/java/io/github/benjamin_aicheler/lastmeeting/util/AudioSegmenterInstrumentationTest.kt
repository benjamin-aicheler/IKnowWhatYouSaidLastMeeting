package io.github.benjamin_aicheler.lastmeeting.util

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer

@RunWith(AndroidJUnit4::class)
class AudioSegmenterInstrumentationTest {

    @Test
    fun shortTrailingSamplesAreCoalescedOrRebalancedWhilePreservingAllFrames() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tempRoot = File(context.cacheDir, "test_audio_segments_${System.nanoTime()}").apply { mkdirs() }
        val sourceFile = File(tempRoot, "source_aac.m4a")
        try {
            val encodedFrames = createSyntheticAacM4a(sourceFile, durationSeconds = 4)
            val totalPayload = encodedFrames.totalPayloadBytes
            val fileLength = sourceFile.length()

            // Force a 1-2 frame tail by setting maxSegmentBytes such that 90% leaves ~1 frame over.
            val tailFrames = 2
            val tailBytes = encodedFrames.frameSizes.takeLast(tailFrames).sum()
            val prefixBytes = totalPayload - tailBytes
            val budgetNeeded = (prefixBytes - 1).coerceAtLeast(64L * 1024L)
            // Only test coalescing/rebalancing if payload exceeds 64 KB floor; otherwise adjust via budget ceiling
            val maxSegmentBytes = maxOf((budgetNeeded * 10L) / 9L, fileLength - 1L)

            val segmenter = AndroidAudioSegmenter { File(tempRoot, "sessions") }
            val session = segmenter.segmentIfNeeded(sourceFile, maxSegmentBytes).getOrThrow()
            try {
                var totalOutSamples = 0
                for (segment in session.segments) {
                    assertTrue(segment.exists())
                    assertTrue(segment.length() in 1..maxSegmentBytes)
                    val (count, durationUs) = inspectSegment(segment)
                    assertTrue("Segment ${segment.name} has <100ms duration ($durationUs us)", durationUs >= 100_000L)
                    totalOutSamples += count
                }
                assertEquals(encodedFrames.frameSizes.size, totalOutSamples)
            } finally {
                session.cleanup()
            }
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    private data class SyntheticAudioInfo(
        val frameSizes: List<Int>,
        val totalPayloadBytes: Long
    )

    private fun createSyntheticAacM4a(outputFile: File, durationSeconds: Int): SyntheticAudioInfo {
        val sampleRate = 16_000
        val bitRate = 256_000 // high bitrate so 4s exceeds the 64 KB floor for segmentation
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val frameSizes = mutableListOf<Int>()
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            val totalSamples = sampleRate * durationSeconds
            val chunkSamples = 1024
            var samplesFed = 0
            var inputDone = false
            var outputDone = false
            var muxerTrack = -1
            var muxerStarted = false
            val bufferInfo = MediaCodec.BufferInfo()

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        inBuf.clear()
                        if (samplesFed >= totalSamples) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val samplesThisChunk = minOf(chunkSamples, totalSamples - samplesFed)
                            val byteCount = samplesThisChunk * 2
                            inBuf.put(ByteArray(byteCount))
                            val ptsUs = samplesFed * 1_000_000L / sampleRate
                            codec.queueInputBuffer(inIdx, 0, byteCount, ptsUs, 0)
                            samplesFed += samplesThisChunk
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        muxerTrack = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    outIdx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIdx)!!
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 &&
                            bufferInfo.size > 0 &&
                            muxerStarted
                        ) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(muxerTrack, outBuf, bufferInfo)
                            frameSizes.add(bufferInfo.size)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }
            if (muxerStarted) {
                muxer.stop()
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { muxer.release() }
        }
        return SyntheticAudioInfo(frameSizes, frameSizes.sumOf { it.toLong() })
    }

    private fun inspectSegment(file: File): Pair<Int, Long> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i
                    format = f
                    break
                }
            }
            assertTrue(track >= 0 && format != null)
            extractor.selectTrack(track)
            val buf = ByteBuffer.allocateDirect(64 * 1024)
            var count = 0
            var firstPts = -1L
            var lastPts = 0L
            while (true) {
                buf.clear()
                val n = extractor.readSampleData(buf, 0)
                if (n < 0) break
                val pts = extractor.sampleTime
                if (firstPts < 0L) firstPts = pts
                lastPts = pts
                count++
                extractor.advance()
            }
            val durationUs = if (format!!.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else {
                (lastPts - firstPts).coerceAtLeast(0L) + 64_000L
            }
            return count to durationUs
        } finally {
            runCatching { extractor.release() }
        }
    }
}
