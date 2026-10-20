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

    public void scroll(double amount, double x, double y, double min, double max) {
        if (!Double.isFinite(amount) || !Double.isFinite(x) || !Double.isFinite(y)) return;
        target = Math.max(min, Math.min(max, target * Math.pow(1.25, Math.max(-64, Math.min(64, amount)))));
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
