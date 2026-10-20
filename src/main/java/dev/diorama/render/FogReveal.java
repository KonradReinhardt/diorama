package dev.diorama.render;

/** CPU endpoints only; the GPU eases discovery without rebuilding or uploading a mask per frame. */
public final class FogReveal {
    public static final long DURATION_NANOS = 600_000_000L;
    private final int size;
    private final byte[] from, to, scratch;
    private boolean initialized, changing;
    private double u, v, texel;
    private long started;

    public FogReveal(int size) {
        this.size = size;
        this.from = new byte[size * size];
        this.to = new byte[size * size];
        this.scratch = new byte[size * size];
    }

    public float progress(long now) {
        float t = changing ? Math.max(0, Math.min(1, (now - started) / (float) DURATION_NANOS)) : 1;
        return t * t * (3 - 2 * t);
    }

    /** A coverage decrease is immediate; only new knowledge fades in. Reprojects a moving viewport. */
    public void update(int[] pixels, double originU, double originV, double step, boolean reset, long now) {
        float t = progress(now);
        byte[] next = scratch;
        boolean animate = false;
        boolean aligned = originU == u && originV == v && step == texel;
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
            int i = y * size + x;
            int target = pixels[i] & 255;
            int old = target;
            if (initialized && !reset) {
                int ox = aligned ? x : (int) Math.floor((originU + (x + .5) * step - u) / texel);
                int oy = aligned ? y : (int) Math.floor((originV + (y + .5) * step - v) / texel);
                if (ox >= 0 && oy >= 0 && ox < size && oy < size) {
                    int j = oy * size + ox;
                    old = Math.round((from[j] & 255) * (1 - t) + (to[j] & 255) * t);
                }
            }
            old = Math.min(old, target);
            next[i] = (byte) old;
            animate |= old != target;
            pixels[i] = (old << 24) | target;
        }
        System.arraycopy(next, 0, from, 0, next.length);
        for (int i = 0; i < to.length; i++) to[i] = (byte) pixels[i];
        u = originU; v = originV; texel = step; started = now;
        initialized = true; changing = animate;
    }

    public void reset() { initialized = changing = false; }
}
