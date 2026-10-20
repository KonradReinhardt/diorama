package dev.diorama.render;

import dev.diorama.render.gpu.MapCanvas;
import dev.diorama.render.gpu.MapTexture;
import dev.diorama.render.gpu.Sampling;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.jspecify.annotations.Nullable;

/** Shapes drawn on top of the map. */
public final class Markers {
	/** The vanilla map's player marker, 8x8, pointing up. Read from the resource packs. */
	private static final String PLAYER_SPRITE = "textures/map/decorations/player.png";
	private static final int SPRITE = 8;
	private static @Nullable MapTexture player;

	private Markers() {
	}

	/**
	 * The player marker at a screen point: the vanilla map's own sprite, {@code scale} GUI pixels
	 * per sprite pixel. {@code yawDegrees} is Minecraft's yaw: 0 faces south, 90 faces west. Like
	 * the vanilla map, it turns in sixteen steps.
	 */
	public static void player(GuiGraphicsExtractor graphics, float x, float y, float yawDegrees, float scale) {
		double yaw = Math.toRadians(yawDegrees - dev.diorama.Projection.direction().ordinal() * 90);
		// Screen direction of travel: a block north moves zScale units up the map.
		double dx = -Math.sin(yaw);
		double dy = Math.cos(yaw) * dev.diorama.Projection.zScale();
		// The sprite points up; turn it toward the direction of travel, in sixteenths of a turn.
		double turn = Math.round(Math.atan2(dx, -dy) / (Math.PI / 8)) * (Math.PI / 8);
		float cos = (float) Math.cos(turn);
		float sin = (float) Math.sin(turn);
		float half = SPRITE * scale / 2;
		// Centred on whole GUI pixels, so the unturned sprite lands on the pixel grid.
		x = Math.round(x);
		y = Math.round(y);
		float[] corners = new float[8];
		float[][] local = {{-half, -half}, {-half, half}, {half, half}, {half, -half}};
		for (int i = 0; i < 4; i++) {
			corners[i * 2] = x + local[i][0] * cos - local[i][1] * sin;
			corners[i * 2 + 1] = y + local[i][0] * sin + local[i][1] * cos;
		}
		MapCanvas.of(graphics).quad(playerSprite(), Sampling.TILE_CRISP, corners, new float[] {0, 0, 0, 1, 1, 1, 1, 0}, -1);
	}

	private static MapTexture playerSprite() {
		if (player == null) {
			int[] pixels = new int[SPRITE * SPRITE];
			try (var input = net.minecraft.client.Minecraft.getInstance().getResourceManager()
				.open(net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft", PLAYER_SPRITE));
				var image = com.mojang.blaze3d.platform.NativeImage.read(input)) {
				int size = Math.min(image.getWidth(), image.getHeight());
				for (int y = 0; y < SPRITE; y++) {
					for (int x = 0; x < SPRITE; x++) {
						// A larger resource-pack sprite is read at its nearest pixels.
						int sx = x * size / SPRITE, sy = y * size / SPRITE;
						//? if >=1.21.2 {
						int argb = image.getPixel(sx, sy);
						pixels[y * SPRITE + x] = (argb & 0xff00ff00) | (argb & 255) << 16 | (argb >> 16 & 255);
						//?} else {
						/*pixels[y * SPRITE + x] = image.getPixelRGBA(sx, sy);
						*///?}
					}
				}
			} catch (Exception error) {
				// A plain white arrow with a dark outline, like the vanilla one.
				String[] rows = {"....#...", "...#+#..", "...#+#..", "..#+++#.", "..#+++#.", "..#+++#.", "..#+++#.", "...###.."};
				for (int i = 0; i < pixels.length; i++) {
					char c = rows[i / SPRITE].charAt(i % SPRITE);
					pixels[i] = c == '#' ? 0xFF000000 : c == '+' ? 0xFFFFFFFF : 0;
				}
				dev.diorama.Diorama.LOGGER.warn("Couldn't load the map's player marker", error);
			}
			player = MapTexture.create("diorama player marker", SPRITE, 1);
			player.write(0, 0, 0, SPRITE, pixels);
		}
		return player;
	}

	/** Drops the sprite so the next draw reads it again from the current resource packs. */
	public static void reload() {
		if (player != null) {
			player.close();
			player = null;
		}
	}
}
