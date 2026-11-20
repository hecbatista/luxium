package io.github.hecbatista.metalshaders.uniform;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class CameraTrackerTest {
    @Test
    void nearbyPositionsAreReportedAsTheyAre() {
        GameUniforms.CameraTracker t = new GameUniforms.CameraTracker();
        t.update(100.5, 64, -200.25);
        t.update(101.0, 64, -200.0);
        assertArrayEquals(new double[]{101.0, 64, -200.0}, t.current);
        assertArrayEquals(new double[]{100.5, 64, -200.25}, t.previous);
    }

    @Test
    void farAwayPositionsAreShiftedByMultiplesOf30000() {
        GameUniforms.CameraTracker t = new GameUniforms.CameraTracker();
        t.update(0, 64, 0);
        t.update(65000.5, 64, 10);   // a teleport: more than 1000 blocks in one frame
        // x is shifted by -60000 (the whole multiples of 30000), so the shader sees 5000.5;
        // the real position stays available unshifted.
        assertArrayEquals(new double[]{5000.5, 64, 10}, t.current);
        assertArrayEquals(new double[]{65000.5, 64, 10}, t.currentUnshifted);
        t.update(65001.5, 64, 10);
        assertArrayEquals(new double[]{5001.5, 64, 10}, t.current);
    }
}
