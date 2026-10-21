package dev.diorama.render.gpu;

/**
 * The map's optional effects, each a shader pass of its own over a view, drawn with
 * {@link MapCanvas#effect}: a mask in sampler 0, the fog's noise in sampler 1, parameters in the
 * vertex colour. They need the render pipelines of 1.21.6 on.
 */
public enum MapEffect {
	/** Clouds and their shadows (clouds.fsh), over the fog's mask. */
	CLOUDS,
	/** Foam and ripples along the coasts (coast.fsh), over the coast mask. */
	COAST;

	/** Whether this version can draw them. */
	public static boolean supported() {
		//? if >=1.21.6 {
		return true;
		//?} else {
		/*return false;
		*///?}
	}
}
