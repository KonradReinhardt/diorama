package dev.diorama.bake;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

class PaintedFinishTest {
    private static final int B = PaintedFinish.BORDER;

    /** A traced area {@code size} pixels across plus the border: block faces with some water, steps and unknown pixels. */
    private record Area(int size, PaintedFinish.Traced traced) {
        static Area random(int size, long seed) {
            int w = size + 2 * B, n = w * w;
            Random random = new Random(seed);
            int[] day = new int[n], night = new int[n];
            boolean[] wet = new boolean[n], sun = new boolean[n];
            float[] water = new float[n], albedo = new float[n * 3], behind = new float[n], front = new float[n];
            for (int i = 0; i < n; i++) {
                int x = i % w, y = i / w;
                boolean unknown = x + y < 6 || random.nextInt(40) == 0;
                // One colour per 4 by 4 face, the way the map's grid draws blocks.
                Random face = new Random(seed * 31 + (x / 4) * 7919L + (y / 4));
                day[i] = unknown ? 0 : 0xFF000000 | face.nextInt(0x1000000);
                night[i] = unknown ? 0 : 0xFF000000 | (face.nextInt(0x1000000) & 0x3F3F3F);
                for (int k = 0; k < 3; k++) albedo[i * 3 + k] = 0.05F + face.nextFloat() * 0.6F;
                wet[i] = x > w / 2;
                water[i] = (x - w / 2) * 0.4F;
                sun[i] = face.nextBoolean();
                behind[i] = y % 4 == 0 && (x / 4) % 3 == 0 ? 2.8F : 0;
                front[i] = y % 4 == 3 && (x / 4) % 3 == 0 ? 2.8F : 0;
            }
            return new Area(size, new PaintedFinish.Traced(day, night, wet, water, sun, albedo, behind, front));
        }

        /** The {@code size}-pixel square at (x0, y0) of this area's interior, with its own border. */
        Area crop(int x0, int y0, int size) {
            int from = this.size + 2 * B, to = size + 2 * B;
            PaintedFinish.Traced t = this.traced;
            PaintedFinish.Traced out = new PaintedFinish.Traced(new int[to * to], new int[to * to], new boolean[to * to], new float[to * to],
                new boolean[to * to], new float[to * to * 3], new float[to * to], new float[to * to]);
            for (int y = 0; y < to; y++) {
                for (int x = 0; x < to; x++) {
                    int i = (y0 + y) * from + x0 + x, j = y * to + x;
                    out.day()[j] = t.day()[i];
                    out.night()[j] = t.night()[i];
                    out.wet()[j] = t.wet()[i];
                    out.waterDepth()[j] = t.waterDepth()[i];
                    out.sun()[j] = t.sun()[i];
                    out.behind()[j] = t.behind()[i];
                    out.front()[j] = t.front()[i];
                    System.arraycopy(t.albedo(), i * 3, out.albedo(), j * 3, 3);
                }
            }
            return new Area(size, out);
        }

        int[][] finish(int gx0, int gy0) {
            int[] day = new int[this.size * this.size], night = new int[day.length];
            PaintedFinish.apply(this.size, this.size, gx0, gy0, this.traced, day, night);
            return new int[][] {day, night};
        }
    }

    @Test void aPatchMatchesTheSamePixelsOfAWholeArea() {
        Area whole = Area.random(64, 7);
        int[][] all = whole.finish(1_000, -2_000);
        int x0 = 24, y0 = 8, size = 32;
        int[][] patch = whole.crop(x0, y0, size).finish(1_000 + x0, -2_000 + y0);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                assertEquals(all[0][(y0 + y) * 64 + x0 + x], patch[0][y * size + x], "day pixel " + x + "," + y);
                assertEquals(all[1][(y0 + y) * 64 + x0 + x], patch[1][y * size + x], "night pixel " + x + "," + y);
            }
        }
    }

    @Test void keepsEveryAlphaSoCoverageNeverGrows() {
        Area area = Area.random(48, 3);
        int[][] out = area.finish(0, 0);
        int w = 48 + 2 * B;
        for (int y = 0; y < 48; y++) {
            for (int x = 0; x < 48; x++) {
                int traced = area.traced().day()[(y + B) * w + x + B];
                assertEquals(traced >>> 24, out[0][y * 48 + x] >>> 24);
                if (traced >>> 24 == 0) assertEquals(traced, out[0][y * 48 + x]);
            }
        }
    }

    @Test void patchesOffTheBlockGridAreRefused() {
        Area area = Area.random(32, 1);
        assertThrows(IllegalArgumentException.class, () -> area.finish(2, 0));
    }

    @Test void waterWashesDeepenOutward() {
        assertEquals(0, PaintedFinish.washBand(0.5F));
        assertEquals(1, PaintedFinish.washBand(2F));
        assertEquals(PaintedFinish.WATER_STEPS.length, PaintedFinish.washBand(40F));
    }
}
