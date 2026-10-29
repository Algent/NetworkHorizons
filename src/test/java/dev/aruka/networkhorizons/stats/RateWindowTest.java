package dev.aruka.networkhorizons.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RateWindowTest {

    @Test
    void sumsSlotsInsideTheWindow() {
        RateWindow w = new RateWindow(60, 1);
        w.add(100, 1, 10);
        w.add(100, 1, 20);
        w.add(130, 1, 5);
        assertEquals(3, w.count(130));
        assertEquals(35, w.bytes(130));
    }

    @Test
    void dropsSlotsOlderThanTheWindow() {
        RateWindow w = new RateWindow(60, 1);
        w.add(100, 1, 10);
        w.add(159, 1, 5);
        assertEquals(15, w.bytes(159));
        assertEquals(5, w.bytes(160));
        assertEquals(0, w.bytes(220));
    }

    @Test
    void reusedSlotStartsFromZero() {
        RateWindow w = new RateWindow(60, 10);
        w.add(5, 1, 100);
        w.add(605, 1, 7); // same slot index, 600 s later
        assertEquals(1, w.count(605));
        assertEquals(7, w.bytes(605));
    }

    @Test
    void mergeAddsMatchingSlotsAndKeepsNewerOnes() {
        RateWindow a = new RateWindow(60, 1);
        RateWindow b = new RateWindow(60, 1);
        a.add(100, 1, 10);
        b.add(100, 2, 30);
        b.add(101, 1, 1);
        a.merge(b);
        assertEquals(4, a.count(101));
        assertEquals(41, a.bytes(101));
    }
}
