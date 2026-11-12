package com.limelight.binding.video;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * M4 exact O(1) PTS -> sequence/queue-time tracker.
 *
 * A direct hash collision can only make an observation UNMATCHED because the exact PTS and
 * sequence publication markers are re-checked. It must never create a false positive recovery.
 * No allocation occurs on queue/output hot paths.
 */
final class OutputDebtTracker {
    static final int CAPACITY = 8192;
    static final int MASK = CAPACITY - 1;
    static final long UNMATCHED = Long.MIN_VALUE;

    static final class Observation {
        long packed = UNMATCHED;
        long residenceUs = -1;
        long matchedSeq;
        long latestSeq;
        void clear() { packed = UNMATCHED; residenceUs = -1; matchedSeq = 0; latestSeq = 0; }
    }

    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong publishedSeq = new AtomicLong();

    // Exact lookup by PTS. hashSeq is the publication marker for the slot.
    private final AtomicLongArray hashPts = new AtomicLongArray(CAPACITY);
    private final AtomicLongArray hashQueueNs = new AtomicLongArray(CAPACITY);
    private final AtomicLongArray hashSeq = new AtomicLongArray(CAPACITY);

    // Exact latest PTS lookup by sequence. seqTag is the publication marker for the slot.
    private final AtomicLongArray seqPts = new AtomicLongArray(CAPACITY);
    private final AtomicLongArray seqTag = new AtomicLongArray(CAPACITY);

    private volatile long minEpochSeq = 1;

    void onQueued(long ptsUs, long queueEndNs) {
        if (ptsUs <= 0 || queueEndNs <= 0) return;
        final long seq = sequence.incrementAndGet();
        final int h = hash(ptsUs);
        hashPts.set(h, ptsUs);
        hashQueueNs.set(h, queueEndNs);
        hashSeq.set(h, seq); // publish exact PTS record

        final int s = (int)(seq & MASK);
        seqPts.set(s, ptsUs);
        seqTag.set(s, seq);  // publish sequence record
        publishedSeq.set(seq); // publish latest only after both records are complete
    }

    void newEpoch() {
        minEpochSeq = publishedSeq.get() + 1;
    }

    void observe(long ptsUs, long outputNs, Observation out) {
        out.clear();
        if (ptsUs <= 0 || outputNs <= 0) return;

        final long latest = publishedSeq.get();
        if (latest < minEpochSeq) return;

        final int ls = (int)(latest & MASK);
        final long latestTag1 = seqTag.get(ls);
        final long latestPts = seqPts.get(ls);
        final long latestTag2 = seqTag.get(ls);
        if (latestTag1 != latest || latestTag2 != latest) return;

        final int h = hash(ptsUs);
        final long matchedSeq1 = hashSeq.get(h);
        final long slotPts = hashPts.get(h);
        final long queueNs = hashQueueNs.get(h);
        final long matchedSeq2 = hashSeq.get(h);
        if (matchedSeq1 != matchedSeq2 || matchedSeq1 < minEpochSeq || matchedSeq1 > latest || slotPts != ptsUs || queueNs <= 0)
            return;

        final long lagFramesLong = latest - matchedSeq1;
        final int lagFrames = lagFramesLong > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int)lagFramesLong;
        final long lagPtsUs = Math.max(0L, latestPts - ptsUs);
        final long residenceUs = outputNs >= queueNs ? (outputNs - queueNs) / 1000L : -1L;
        if (residenceUs < 0) return;

        out.packed = (((long)lagFrames) << 32) | (lagPtsUs & 0xffffffffL);
        out.residenceUs = residenceUs;
        out.matchedSeq = matchedSeq1;
        out.latestSeq = latest;
    }

    private static int hash(long v) {
        v ^= (v >>> 33);
        v *= 0xff51afd7ed558ccdl;
        v ^= (v >>> 33);
        v *= 0xc4ceb9fe1a85ec53l;
        v ^= (v >>> 33);
        return (int)v & MASK;
    }

    static boolean matched(long packed) { return packed != UNMATCHED; }
    static int lagFrames(long packed) { return (int)(packed >>> 32); }
    static long lagPtsUs(long packed) { return packed & 0xffffffffL; }
}
