package dev.diorama.render.gpu;

import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Draws only inside a shape made of horizontal bands, by cutting each axis-aligned rectangle into
 * the pieces that fall inside them, texture coordinates following, before it reaches the GPU.
 * There is no stencil in the GUI, and the minimap is drawn straight over the world, so this is
 * how its edge can take any pixel shape: what lies outside is simply never drawn. Quads that
 * aren't axis-aligned rectangles (turned markers) pass through uncut. Render thread only.
 */
public final class ClippedCanvas implements MapCanvas {
	/** One band of the shape: GUI rows {@code y0} to {@code y1}, columns {@code x0} to {@code x1}; fractions land on screen pixels. */
	public record Band(float y0, float y1, float x0, float x1) {
	}

	private static @Nullable List<Band> active;

	private final MapCanvas canvas;
	private final List<Band> bands;

	private ClippedCanvas(MapCanvas canvas, List<Band> bands) {
		this.canvas = canvas;
		this.bands = bands;
	}

	static MapCanvas wrap(MapCanvas canvas) {
		List<Band> bands = active;
		return bands == null ? canvas : new ClippedCanvas(canvas, bands);
	}

	/** Runs {@code draw} with every {@link MapCanvas} drawing cut to the bands. */
	public static void within(List<Band> bands, Runnable draw) {
		List<Band> before = active;
		active = bands;
		try {
			draw.run();
		} finally {
			active = before;
		}
	}

	/** Calls {@code piece} for each part of the rectangle inside a band, with its share of the UVs. */
	private void cut(float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1, Piece piece) {
		for (Band band : this.bands) {
			float px0 = Math.max(x0, band.x0()), px1 = Math.min(x1, band.x1());
			float py0 = Math.max(y0, band.y0()), py1 = Math.min(y1, band.y1());
			if (px0 >= px1 || py0 >= py1) {
				continue;
			}
			float su0 = lerp(u0, u1, (px0 - x0) / (x1 - x0)), su1 = lerp(u0, u1, (px1 - x0) / (x1 - x0));
			float sv0 = lerp(v0, v1, (py0 - y0) / (y1 - y0)), sv1 = lerp(v0, v1, (py1 - y0) / (y1 - y0));
			piece.draw(px0, py0, px1, py1, su0, sv0, su1, sv1);
		}
	}

	private interface Piece {
		void draw(float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1);
	}

	private static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}

	/** The rectangle's corners, if the quad is one: x0 y0 x1 y1. Corners run x0y0, x0y1, x1y1, x1y0. */
	private static float @Nullable [] rectangle(float[] c) {
		boolean rect = c[0] == c[2] && c[4] == c[6] && c[1] == c[7] && c[3] == c[5] && c[0] < c[4] && c[1] < c[3];
		return rect ? new float[] {c[0], c[1], c[4], c[3]} : null;
	}

	@Override
	public void quad(MapTexture texture, Sampling sampling, float[] corners, float[] uvs, int argb) {
		float[] r = rectangle(corners);
		if (r == null || uvs[0] != uvs[2] || uvs[1] != uvs[7]) {
			this.canvas.quad(texture, sampling, corners, uvs, argb);
			return;
		}
		this.cut(r[0], r[1], r[2], r[3], uvs[0], uvs[1], uvs[4], uvs[3],
			(x0, y0, x1, y1, u0, v0, u1, v1) -> this.canvas.rect(texture, sampling, x0, y0, x1, y1, u0, v0, u1, v1, argb));
	}

	@Override
	public void lit(MapTexture day, @Nullable MapTexture night, Sampling sampling, float x0, float y0, float x1, float y1,
		float u0, float v0, float u1, float v1, dev.diorama.EnvironmentLighting lighting) {
		this.cut(x0, y0, x1, y1, u0, v0, u1, v1,
			(px0, py0, px1, py1, su0, sv0, su1, sv1) -> this.canvas.lit(day, night, sampling, px0, py0, px1, py1, su0, sv0, su1, sv1, lighting));
	}

	@Override
	public void solid(float[] corners, int argb) {
		float[] r = rectangle(corners);
		if (r == null) {
			this.canvas.solid(corners, argb);
			return;
		}
		this.cut(r[0], r[1], r[2], r[3], 0, 0, 1, 1,
			(x0, y0, x1, y1, u0, v0, u1, v1) -> this.canvas.solid(new float[] {x0, y0, x0, y1, x1, y1, x1, y0}, argb));
	}

	@Override
	public void blit(Identifier texture, int x, int y, int u, int v, int w, int h, int texW, int texH, int argb) {
		this.canvas.blit(texture, x, y, u, v, w, h, texW, texH, argb);
	}

	@Override
	public void playerFace(AbstractClientPlayer player, int x, int y, int size, int argb) {
		this.canvas.playerFace(player, x, y, size, argb);
	}

	@Override
	public void fog(MapTexture mask, MapTexture noise, int x, int y, int width, int height, float u0, float v0, float u1, float v1, int params) {
		// The fog's pixel art is laid out on the screen, so pieces at whole pixels draw the same.
		this.cut(x, y, x + width, y + height, u0, v0, u1, v1, (px0, py0, px1, py1, su0, sv0, su1, sv1) -> {
			int ix = Math.round(px0), iy = Math.round(py0);
			this.canvas.fog(mask, noise, ix, iy, Math.round(px1) - ix, Math.round(py1) - iy, su0, sv0, su1, sv1, params);
		});
	}

	@Override
	public void antiqueTile(MapTexture terrain, MapTexture paper, Sampling sampling, float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1) {
		this.cut(x0, y0, x1, y1, u0, v0, u1, v1,
			(px0, py0, px1, py1, su0, sv0, su1, sv1) -> this.canvas.antiqueTile(terrain, paper, sampling, px0, py0, px1, py1, su0, sv0, su1, sv1));
	}
}
