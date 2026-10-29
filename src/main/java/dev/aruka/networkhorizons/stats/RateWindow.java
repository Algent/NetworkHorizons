package dev.aruka.networkhorizons.stats;

import java.util.Arrays;

/**
 * Ring of time slots holding packet count and bytes, for rates over a recent window.
 * Single writer; readers tolerate slightly stale values.
 */
final class RateWindow {

    private final int slotSeconds;
    private final int[] slotTime;
    private final int[] counts;
    private final long[] bytes;

    RateWindow(int slots, int slotSeconds) {
        this.slotSeconds = slotSeconds;
        this.slotTime = new int[slots];
        this.counts = new int[slots];
        this.bytes = new long[slots];
        Arrays.fill(slotTime, Integer.MIN_VALUE);
    }

    int seconds() {
        return slotTime.length * slotSeconds;
    }

    void add(int nowSeconds, int count, long size) {
        int t = nowSeconds / slotSeconds;
        int i = t % slotTime.length;
        if (slotTime[i] != t) {
            slotTime[i] = t;
            counts[i] = 0;
            bytes[i] = 0;
        }
        counts[i] += count;
        bytes[i] += size;
    }

    long count(int nowSeconds) {
        int oldest = nowSeconds / slotSeconds - slotTime.length;
        long sum = 0;
        for (int i = 0; i < slotTime.length; i++) {
            if (slotTime[i] > oldest) sum += counts[i];
        }
        return sum;
    }

    long bytes(int nowSeconds) {
        int oldest = nowSeconds / slotSeconds - slotTime.length;
        long sum = 0;
        for (int i = 0; i < slotTime.length; i++) {
            if (slotTime[i] > oldest) sum += bytes[i];
        }
        return sum;
    }

    /** Adds the other window's slots that are still current in this one. */
    void merge(RateWindow other) {
        for (int i = 0; i < slotTime.length; i++) {
            int t = other.slotTime[i];
            if (t == Integer.MIN_VALUE) continue;
            if (slotTime[i] < t) {
                slotTime[i] = t;
                counts[i] = other.counts[i];
                bytes[i] = other.bytes[i];
            } else if (slotTime[i] == t) {
                counts[i] += other.counts[i];
                bytes[i] += other.bytes[i];
            }
        }
    }
}
