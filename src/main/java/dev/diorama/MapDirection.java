package dev.diorama;

/** Direction at the top of the map. Rotations preserve height and the oblique camera. */
public enum MapDirection {
	NORTH(1, 0, 0, 1), EAST(0, 1, -1, 0), SOUTH(-1, 0, 0, -1), WEST(0, -1, 1, 0);
	private final int a, b, c, d;
	MapDirection(int a, int b, int c, int d) { this.a = a; this.b = b; this.c = c; this.d = d; }
	public double u(double x, double z) { return this.a * x + this.b * z; }
	public double depth(double x, double z) { return this.c * x + this.d * z; }
	public double worldX(double u, double depth) { return this.a * u + this.c * depth; }
	public double worldZ(double u, double depth) { return this.b * u + this.d * depth; }
	public int worldBlockX(int u, int depth) { return this.a * u + this.c * depth + (this.a < 0 || this.c < 0 ? -1 : 0); }
	public int worldBlockZ(int u, int depth) { return this.b * u + this.d * depth + (this.b < 0 || this.d < 0 ? -1 : 0); }
	public int blockU(int x, int z) { return this.a * x + this.b * z + (this.a < 0 || this.b < 0 ? -1 : 0); }
	public int blockDepth(int x, int z) { return this.c * x + this.d * z + (this.c < 0 || this.d < 0 ? -1 : 0); }
	public String topLetter() { return name().substring(0, 1); }
	public String rightLetter() { return values()[(ordinal() + 1) % 4].topLetter(); }
	public String bottomLetter() { return values()[(ordinal() + 2) % 4].topLetter(); }
	public String leftLetter() { return values()[(ordinal() + 3) % 4].topLetter(); }
}
