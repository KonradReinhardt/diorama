package dev.diorama.render;

/** RGBA8 allocation including every mip; shared by eviction and measurements. */
public final class TextureMemory {
	/**
	 * Map tiles held on the GPU. Views pick a level of detail whose tiles fit in part of it
	 * ({@code MapViewport.lodFor}); the rest keeps recently seen tiles for panning back.
	 */
	public static final long BUDGET = 512L * 1024 * 1024;
	private TextureMemory() {}
	public static long bytes(int edge, int levels) {
		long bytes = 0;
		for (int i = 0; i < levels; i++) bytes += (long) (edge >> i) * (edge >> i) * 4;
		return bytes;
	}
}
