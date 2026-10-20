package dev.diorama.bake;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

class PaintedFinishTest {
    private static final int B = PaintedFinish.BORDER;

    /** A traced area {@code size} pixels across plus the border, with some water, cliffs and unknown pixels. */
    private record Area(int size, int[] day, int[] night, float[] depth, float[] wet, float[] water, float[] sun) {
        static Area random(int size, long seed) {
            int w = size + 2 * B, n = w * w;
            Random random = new Random(seed);
            int[] day = new int[n], night = new int[n];
            float[] depth = new float[n], wet = new float[n], water = new float[n], sun = new float[n];
            for (int i = 0; i < n; i++) {
                int x = i % w, y = i / w;
                boolean unknown = x + y < 6 || random.nextInt(40) == 0;
                int alpha = unknown ? 0 : random.nextInt(8) == 0 ? 191 : 255;
                // Blocky colours, the way four pixels share a block.
                Random block = new Random(seed * 31 + (x / 4) * 7919L + (y / 4));
                day[i] = alpha << 24 | block.nextInt(0x1000000);
                night[i] = alpha << 24 | (block.nextInt(0x1000000) & 0x3F3F3F);
                depth[i] = unknown ? Float.NaN : (x / 4 + y / 4) % 5 == 0 ? 2 : 9;
                wet[i] = x > w / 2 ? 1 : 0;
                water[i] = (x - w / 2) * 0.4F;
                sun[i] = block.nextBoolean() ? 1 : 0;
            }
            return new Area(size, day, night, depth, wet, water, sun);
        }

        /** The {@code size}-pixel square at (x0, y0) of this area's interior, with its own border. */
        Area crop(int x0, int y0, int size) {
            int from = this.size + 2 * B, to = size + 2 * B;
            Area out = new Area(size, new int[to * to], new int[to * to], new float[to * to], new float[to * to], new float[to * to], new float[to * to]);
            for (int y = 0; y < to; y++) {
                for (int x = 0; x < to; x++) {
                    int i = (y0 + y) * from + x0 + x, j = y * to + x;
                    out.day[j] = this.day[i];
                    out.night[j] = this.night[i];
                    out.depth[j] = this.depth[i];
                    out.wet[j] = this.wet[i];
                    out.water[j] = this.water[i];
                    out.sun[j] = this.sun[i];
                }
            }
            return out;
        }

        int[][] finish(int gx0, int gy0) {
            int[] day = new int[this.size * this.size], night = new int[day.length];
            PaintedFinish.apply(this.size, this.size, gx0, gy0, this.day, this.night, this.depth, this.wet, this.water, this.sun, day, night);
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
                int traced = area.day()[(y + B) * w + x + B];
                assertEquals(traced >>> 24, out[0][y * 48 + x] >>> 24);
                if (traced >>> 24 == 0) assertEquals(traced, out[0][y * 48 + x]);
            }
        }
    }

    @Test void waterWashesDeepenOutward() {
        assertEquals(0, PaintedFinish.washBand(0.5F));
        assertEquals(1, PaintedFinish.washBand(2F));
        assertEquals(PaintedFinish.WATER_STEPS.length, PaintedFinish.washBand(40F));
    }
}
