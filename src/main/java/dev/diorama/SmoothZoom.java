package dev.diorama;

/** Frame-rate independent wheel easing in logarithmic zoom space. No tick-sized steps. */
public final class SmoothZoom {
    private double value;
    private double target;
    private double anchorX, anchorY;

    public SmoothZoom(double value) { reset(value); }
    public double value() { return value; }
    public double target() { return target; }
    public void reset(double zoom) { value = target = zoom; }
    /** Whether the zoom is still easing toward its target. */
    public boolean moving() { return value != target; }

    public void scroll(double amount, double x, double y, double min, double max) {
        this.scroll(amount, x, y, min, max, 0);
    }

    /**
     * Same, where a zoom of {@code pixelZoom} draws one screen pixel per map pixel (0 for none).
     * At or above it, the map is drawn pixel perfect, at whole screen pixels per map pixel, so each
     * wheel step lands on the next whole number instead of 1.25 times further: every step does
     * something, and the zoom comes to rest exactly where it will be drawn. Below it, steps stay
     * 1.25 times apart.
     */
    public void scroll(double amount, double x, double y, double min, double max, double pixelZoom) {
        if (!Double.isFinite(amount) || !Double.isFinite(x) || !Double.isFinite(y)) return;
        amount = Math.max(-64, Math.min(64, amount));
        double next = Math.max(min, Math.min(max, target * Math.pow(1.25, amount)));
        if (pixelZoom > 0 && next >= pixelZoom * 0.999) {
            double from = target / pixelZoom;
            double whole = Math.rint(next / pixelZoom);
            // A whole wheel notch always moves at least one step, even where 1.25 times rounds back.
            if (amount >= 0.5) whole = Math.max(whole, Math.floor(from + 1e-6) + 1);
            if (amount <= -0.5) whole = Math.min(whole, Math.ceil(from - 1e-6) - 1);
            whole = Math.min(whole, Math.floor(max / pixelZoom + 1e-9));
            // Leaving the whole steps zooms out 1.25 times from one screen pixel per map pixel.
            next = whole >= 1 ? whole * pixelZoom : Math.max(min, pixelZoom / 1.25);
        }
        target = next;
        anchorX = x;
        anchorY = y;
    }

    public record Step(double zoom, double centerU, double centerV) {}

    public Step advance(double seconds, double centerU, double centerV, double min) {
        value = Math.max(min, value);
        target = Math.max(min, target);
        double before = value;
        double distance = Math.log(target / value);
        double t = 1 - Math.exp(-Math.max(0, Math.min(0.1, seconds)) / 0.075);
        value = Math.abs(distance) < 0.0005 ? target : value * Math.exp(distance * t);
        double shift = 1 / before - 1 / value;
        return new Step(value, centerU + anchorX * shift, centerV + anchorY * shift);
    }
}
