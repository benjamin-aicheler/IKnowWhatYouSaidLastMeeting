package io.github.benjamin_aicheler.lastmeeting.util

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

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

    companion object {
        internal const val SEGMENT_CACHE_DIR_NAME = "audio_segments"
        internal const val MIN_TAIL_SEGMENT_DURATION_US = 1_000_000L
        private const val SESSION_DIR_PREFIX = "seg_"
        private const val DEFAULT_FALLBACK_FRAME_DURATION_US = 64_000L

        private val sweepLock = Any()
        private val sweptRootPaths = mutableSetOf<String>()
        private val activeSessionPaths = mutableSetOf<String>()

        fun pruneAbandonedSessionsOnStartup(cacheDir: File): Int {
            return ensureInitialSweepCompleted(File(cacheDir, SEGMENT_CACHE_DIR_NAME))
        }

        internal fun ensureInitialSweepCompleted(rootDir: File): Int {
            val canonicalRoot = rootDir.canonicalOrAbsolutePath()
            val abandonedDirs = synchronized(sweepLock) {
                if (!sweptRootPaths.add(canonicalRoot)) {
                    return 0
                }
                if (!rootDir.isDirectory) {
                    emptyList()
                } else {
                    rootDir.listFiles().orEmpty().filter { entry ->
                        entry.isDirectory &&
                            entry.name.startsWith(SESSION_DIR_PREFIX) &&
                            entry.canonicalOrAbsolutePath() !in activeSessionPaths
                    }
                }
            }
            return deleteDirectories(abandonedDirs)
        }

        internal fun pruneAbandonedSegmentDirs(
            rootDir: File,
            activeSessionNames: Set<String> = emptySet()
        ): Int {
            if (!rootDir.isDirectory) return 0
            val dirs = rootDir.listFiles().orEmpty().filter { entry ->
                entry.isDirectory &&
                    entry.name.startsWith(SESSION_DIR_PREFIX) &&
                    entry.name !in activeSessionNames
            }
            return deleteDirectories(dirs)
        }

        internal fun registerActiveSessionDir(sessionDir: File) {
            val path = sessionDir.canonicalOrAbsolutePath()
            synchronized(sweepLock) {
                activeSessionPaths.add(path)
            }
        }

        internal fun releaseSessionDir(sessionDir: File) {
            try {
                sessionDir.deleteRecursively()
            } finally {
                val path = sessionDir.canonicalOrAbsolutePath()
                synchronized(sweepLock) {
                    activeSessionPaths.remove(path)
                }
            }
        }

        private fun deleteDirectories(dirs: List<File>): Int {
            var deleted = 0
            dirs.forEach { dir ->
                if (dir.deleteRecursively()) {
                    deleted++
                }
            }
            return deleted
        }

        private fun File.canonicalOrAbsolutePath(): String =
            runCatching { canonicalPath }.getOrElse { absolutePath }
    }

    override suspend fun segmentIfNeeded(
        audioFile: File,
        maxSegmentBytes: Long
    ): Result<AudioSegmentationSession> {
        val sessionDirRef = AtomicReference<File?>(null)
        return try {
            withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                if (!audioFile.exists() || audioFile.length() <= 0L) {
                    return@withContext Result.failure(
                        IllegalArgumentException("Audio file does not exist or is empty.")
                    )
                }
                if (audioFile.length() <= maxSegmentBytes) {
                    return@withContext Result.success(AudioSegmentationSession(listOf(audioFile)))
                }

                val rootDir = tempRootDirProvider()
                ensureInitialSweepCompleted(rootDir)
                if (!rootDir.isDirectory && !rootDir.mkdirs() && !rootDir.isDirectory) {
                    return@withContext Result.failure(IOException("Unable to create temporary segmentation directory."))
                }

                val sessionDir = File(rootDir, "$SESSION_DIR_PREFIX${UUID.randomUUID()}")
                registerActiveSessionDir(sessionDir)
                sessionDirRef.set(sessionDir)
                if (!sessionDir.mkdirs()) {
                    sessionDirRef.getAndSet(null)?.let(::releaseSessionDir)
                    return@withContext Result.failure(IOException("Unable to create segment session directory."))
                }

                val payloadBudgetBytes = (maxSegmentBytes * 9L / 10L).coerceAtLeast(64L * 1024L).coerceAtMost(maxSegmentBytes)
                val segments = mutableListOf<File>()

                try {
                    val scan = scanAudioSamples(audioFile)
                    val plan = planAudioSegmentRanges(
                        sampleSizes = scan.sampleSizes,
                        samplePtsUs = scan.samplePtsUs,
                        payloadBudgetBytes = payloadBudgetBytes,
                        maxSegmentBytes = maxSegmentBytes,
                        fallbackFrameDurationUs = scan.fallbackFrameDurationUs,
                        totalDurationUs = scan.totalDurationUs
                    )

                    val directRanges = if (plan.ranges.size >= 2 && plan.tryCoalesceFinal) {
                        plan.ranges.dropLast(2) + listOf(plan.ranges[plan.ranges.size - 2].first..plan.ranges.last().last)
                    } else {
                        plan.ranges
                    }

                    writePlannedSegments(
                        audioFile = audioFile,
                        audioTrackIndex = scan.audioTrackIndex,
                        audioFormat = scan.audioFormat,
                        bufferSize = scan.maxInputSize,
                        sessionDir = sessionDir,
                        ranges = directRanges,
                        startSegmentNumber = 1,
                        outSegments = segments
                    )

                    if (plan.ranges.size >= 2 && plan.tryCoalesceFinal) {
                        val coalesced = segments.last()
                        if (coalesced.length() > maxSegmentBytes) {
                            coalesced.delete()
                            segments.removeAt(segments.lastIndex)
                            writePlannedSegments(
                                audioFile = audioFile,
                                audioTrackIndex = scan.audioTrackIndex,
                                audioFormat = scan.audioFormat,
                                bufferSize = scan.maxInputSize,
                                sessionDir = sessionDir,
                                ranges = plan.ranges.takeLast(2),
                                startSegmentNumber = directRanges.size,
                                outSegments = segments
                            )
                        }
                    }

                    currentCoroutineContext().ensureActive()
                    check(segments.isNotEmpty()) { "No audio samples found in ${audioFile.name}." }
                    for (segment in segments) {
                        val len = segment.length()
                        check(len in 1..maxSegmentBytes) {
                            "Segment ${segment.name} has invalid size $len (max $maxSegmentBytes)."
                        }
                    }

                    val ownedDir = sessionDir
                    Result.success(
                        AudioSegmentationSession(segments = segments) {
                            if (sessionDirRef.compareAndSet(ownedDir, null)) {
                                releaseSessionDir(ownedDir)
                            }
                        }
                    )
                } catch (e: CancellationException) {
                    sessionDirRef.getAndSet(null)?.let(::releaseSessionDir)
                    throw e
                } catch (e: Exception) {
                    sessionDirRef.getAndSet(null)?.let(::releaseSessionDir)
                    Result.failure(e)
                }
            }
        } catch (e: CancellationException) {
            sessionDirRef.getAndSet(null)?.let(::releaseSessionDir)
            throw e
        }
    }

    private class AudioSampleScan(
        val audioTrackIndex: Int,
        val audioFormat: MediaFormat,
        val maxInputSize: Int,
        val sampleSizes: IntArray,
        val samplePtsUs: LongArray,
        val fallbackFrameDurationUs: Long,
        val totalDurationUs: Long
    )

    private suspend fun scanAudioSamples(audioFile: File): AudioSampleScan {
        val extractor = MediaExtractor()
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
            val sampleRate = if (audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                0
            }
            val fallbackFrameDurationUs = if (sampleRate > 0) {
                1024L * 1_000_000L / sampleRate.toLong()
            } else {
                DEFAULT_FALLBACK_FRAME_DURATION_US
            }
            val totalDurationUs = if (audioFormat.containsKey(MediaFormat.KEY_DURATION)) {
                audioFormat.getLong(MediaFormat.KEY_DURATION)
            } else {
                -1L
            }

            val buffer = ByteBuffer.allocateDirect(maxInputSize)
            var sizes = IntArray(256)
            var ptsUs = LongArray(256)
            var count = 0

            while (true) {
                currentCoroutineContext().ensureActive()
                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                if (count == sizes.size) {
                    sizes = sizes.copyOf(sizes.size * 2)
                    ptsUs = ptsUs.copyOf(ptsUs.size * 2)
                }
                sizes[count] = sampleSize
                ptsUs[count] = extractor.sampleTime
                count++
                extractor.advance()
            }

            check(count > 0) { "No audio samples found in ${audioFile.name}." }
            return AudioSampleScan(
                audioTrackIndex = audioTrackIndex,
                audioFormat = audioFormat,
                maxInputSize = maxInputSize,
                sampleSizes = sizes.copyOf(count),
                samplePtsUs = ptsUs.copyOf(count),
                fallbackFrameDurationUs = fallbackFrameDurationUs,
                totalDurationUs = totalDurationUs
            )
        } finally {
            runCatching { extractor.release() }
        }
    }

    private suspend fun writePlannedSegments(
        audioFile: File,
        audioTrackIndex: Int,
        audioFormat: MediaFormat,
        bufferSize: Int,
        sessionDir: File,
        ranges: List<IntRange>,
        startSegmentNumber: Int,
        outSegments: MutableList<File>
    ) {
        if (ranges.isEmpty()) return
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(audioFile.absolutePath)
            extractor.selectTrack(audioTrackIndex)
            val buffer = ByteBuffer.allocateDirect(bufferSize)
            val bufferInfo = MediaCodec.BufferInfo()

            val firstNeededSample = ranges.first().first
            val lastNeededSample = ranges.last().last
            var currentSampleIndex = 0
            while (currentSampleIndex < firstNeededSample) {
                currentCoroutineContext().ensureActive()
                check(extractor.advance()) { "Unexpected end of audio stream before sample $firstNeededSample." }
                currentSampleIndex++
            }

            var rangePosition = 0
            var activeRange = ranges[rangePosition]
            var segmentStartUs = -1L
            var muxerTrackIndex = -1

            while (currentSampleIndex <= lastNeededSample) {
                currentCoroutineContext().ensureActive()
                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                check(sampleSize >= 0) { "Unexpected EOF while muxing sample $currentSampleIndex." }

                if (muxer == null) {
                    val segNum = startSegmentNumber + rangePosition
                    val outFile = File(sessionDir, String.format(Locale.US, "segment_%04d.m4a", segNum))
                    outSegments.add(outFile)
                    val created = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    muxerTrackIndex = created.addTrack(audioFormat)
                    created.start()
                    muxer = created
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

                if (currentSampleIndex == activeRange.last) {
                    muxer.stop()
                    muxer.release()
                    muxer = null
                    rangePosition++
                    if (rangePosition < ranges.size) {
                        activeRange = ranges[rangePosition]
                    }
                }

                extractor.advance()
                currentSampleIndex++
            }

            muxer?.let { active ->
                active.stop()
                active.release()
                muxer = null
            }
        } finally {
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }
}

internal data class SegmentPlan(
    val ranges: List<IntRange>,
    val tryCoalesceFinal: Boolean
)

internal fun planAudioSegmentRanges(
    sampleSizes: IntArray,
    samplePtsUs: LongArray,
    payloadBudgetBytes: Long,
    maxSegmentBytes: Long,
    minTailDurationUs: Long = AndroidAudioSegmenter.MIN_TAIL_SEGMENT_DURATION_US,
    fallbackFrameDurationUs: Long = 64_000L,
    totalDurationUs: Long = -1L
): SegmentPlan {
    require(sampleSizes.isNotEmpty() && sampleSizes.size == samplePtsUs.size) {
        "Sample sizes and timestamps must be non-empty and match in length."
    }

    val ranges = mutableListOf<IntRange>()
    var start = 0
    var currentBytes = 0L
    for (i in sampleSizes.indices) {
        val size = sampleSizes[i].toLong()
        if (i > start && currentBytes + size > payloadBudgetBytes) {
            ranges.add(start until i)
            start = i
            currentBytes = 0L
        }
        currentBytes += size
    }
    ranges.add(start until sampleSizes.size)

    if (ranges.size < 2) {
        return SegmentPlan(ranges = ranges, tryCoalesceFinal = false)
    }

    fun rangeDurationUs(range: IntRange): Long {
        if (range.isEmpty()) return 0L
        val firstPts = samplePtsUs[range.first]
        val lastIdx = range.last
        val endPts = when {
            lastIdx + 1 < samplePtsUs.size && samplePtsUs[lastIdx + 1] > samplePtsUs[lastIdx] ->
                samplePtsUs[lastIdx + 1]
            totalDurationUs > samplePtsUs[lastIdx] ->
                totalDurationUs
            else -> {
                val frameDelta = if (lastIdx > 0 && samplePtsUs[lastIdx] > samplePtsUs[lastIdx - 1]) {
                    samplePtsUs[lastIdx] - samplePtsUs[lastIdx - 1]
                } else {
                    fallbackFrameDurationUs
                }
                samplePtsUs[lastIdx] + frameDelta
            }
        }
        return (endPts - firstPts).coerceAtLeast(0L)
    }

    fun rangePayloadBytes(range: IntRange): Long {
        var total = 0L
        for (i in range) {
            total += sampleSizes[i].toLong()
        }
        return total
    }

    val prev = ranges[ranges.size - 2]
    val last = ranges.last()
    if (rangeDurationUs(last) >= minTailDurationUs) {
        return SegmentPlan(ranges = ranges, tryCoalesceFinal = false)
    }

    val prevBytes = rangePayloadBytes(prev)
    val lastBytes = rangePayloadBytes(last)
    val tryCoalesce = prevBytes + lastBytes <= maxSegmentBytes

    var split = last.first
    var newLastBytes = lastBytes
    while (split > prev.first + 1 && rangeDurationUs(split..last.last) < minTailDurationUs) {
        val candidateStart = split - 1
        val candidateBytes = newLastBytes + sampleSizes[candidateStart].toLong()
        if (candidateBytes > payloadBudgetBytes) {
            break
        }
        split = candidateStart
        newLastBytes = candidateBytes
    }

    val rebalanced = ranges.dropLast(2) + listOf(prev.first until split, split..last.last)
    return SegmentPlan(ranges = rebalanced, tryCoalesceFinal = tryCoalesce)
}
