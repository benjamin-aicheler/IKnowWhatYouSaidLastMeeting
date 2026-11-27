package io.github.benjamin_aicheler.lastmeeting.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSegmenterTest {

    @Test
    fun keepsGreedyRangesWhenTailMeetsMinimumDuration() {
        // 100 samples of 64 ms each (6.4 s total): 50 in seg1 + 50 in seg2 (3.2 s tail >= 1.0 s)
        val sizes = IntArray(100) { 1_000 }
        val ptsUs = LongArray(100) { it * 64_000L }

        val plan = planAudioSegmentRanges(
            sampleSizes = sizes,
            samplePtsUs = ptsUs,
            payloadBudgetBytes = 50_000L,
            maxSegmentBytes = 60_000L
        )

        assertFalse(plan.tryCoalesceFinal)
        assertEquals(listOf(0..49, 50..99), plan.ranges)
    }

    @Test
    fun offersCoalescingAndRebalancedFallbackWhenShortTailFitsInsideMaxSegmentBytes() {
        // 40 samples total @ 64 ms each: 38 samples (38 KB) + 2 trailing samples (2 KB)
        // payloadBudget = 38 KB, maxSegmentBytes = 44 KB -> combined 40 KB <= 44 KB
        val sizes = IntArray(40) { 1_000 }
        val ptsUs = LongArray(40) { it * 64_000L }

        val plan = planAudioSegmentRanges(
            sampleSizes = sizes,
            samplePtsUs = ptsUs,
            payloadBudgetBytes = 38_000L,
            maxSegmentBytes = 44_000L
        )

        assertTrue(plan.tryCoalesceFinal)
        assertEquals(2, plan.ranges.size)
        // Rebalanced fallback shifts samples so the tail reaches >= 1.0 s (16 frames * 64 ms = 1.024 s)
        val tail = plan.ranges[1]
        val tailDurationUs = (tail.count()) * 64_000L
        assertTrue("Expected tail >= 1s, was $tailDurationUs us", tailDurationUs >= AndroidAudioSegmenter.MIN_TAIL_SEGMENT_DURATION_US)
        assertEquals((0 until 40).toList(), plan.ranges.flatten())
    }

    @Test
    fun rebalancesShortTailWithoutCoalescingWhenCombinedExceedsMaxSegmentBytes() {
        // 40 samples total @ 64 ms each: 38 samples (38 KB) + 2 trailing samples (2 KB)
        // payloadBudget = 38 KB, maxSegmentBytes = 39 KB -> combined 40 KB > 39 KB
        val sizes = IntArray(40) { 1_000 }
        val ptsUs = LongArray(40) { it * 64_000L }

        val plan = planAudioSegmentRanges(
            sampleSizes = sizes,
            samplePtsUs = ptsUs,
            payloadBudgetBytes = 38_000L,
            maxSegmentBytes = 39_000L
        )

        assertFalse(plan.tryCoalesceFinal)
        assertEquals(2, plan.ranges.size)
        val first = plan.ranges[0]
        val second = plan.ranges[1]
        assertTrue(first.count() >= 1)
        assertTrue(second.count() * 64_000L >= AndroidAudioSegmenter.MIN_TAIL_SEGMENT_DURATION_US)
        assertTrue(second.sumOf { sizes[it] } <= 38_000)
        assertEquals((0 until 40).toList(), plan.ranges.flatten())
    }
}
