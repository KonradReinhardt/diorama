package dev.diorama.render;

import dev.diorama.Diorama;
import dev.diorama.render.gpu.MapCanvas;
import dev.diorama.render.gpu.MapTexture;
import dev.diorama.render.gpu.Sampling;
import dev.diorama.waypoint.BannerDesign;
import dev.diorama.waypoint.BannerPatternCatalog;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.jspecify.annotations.Nullable;

/**
 * Waypoint banners as sprites in one texture. Each design takes a slot holding every frame in
 * both sizes, composed by {@link BannerCompositor} from the banner textures of the current
 * resource packs; slots go to the designs drawn most recently. A design is composed only when it
 * first shows or the resource packs reload, a few per frame at most, so the render thread never
 * stalls on a screen full of new banners. Render thread only.
 */
public final class BannerAtlas {
	/** How long each wave frame shows. */
	private static final long FRAME_MILLIS = 250;
	private static final int SIZE = 512;
	/** A slot: a row of five 16x32 cells for each size, the four wave frames and the still one. */
	private static final int CELL_W = 16, CELL_H = 32;
	private static final int SLOT_W = CELL_W * (BannerCompositor.WAVE_FRAMES + 1), SLOT_H = CELL_H * 2;
	private static final int COLUMNS = SIZE / SLOT_W, ROWS = SIZE / SLOT_H;
	/** New designs composed per frame; the rest wait a frame and draw nothing meanwhile. */
	private static final int COMPOSE_BUDGET = 6;

	/** The map, minimap and HUD share this one; the editor opens its own for its pattern grid. */
	private static @Nullable BannerAtlas shared;

	/** The cloth fronts read from the resource packs, by pattern id; null where there is none. */
	private static final Map<String, BannerCompositor.@Nullable Mask> MASKS = new HashMap<>();

	private record Key(BannerDesign design, boolean sepia) {
	}

	private final String label;
	private final LinkedHashMap<Key, Integer> slots = new LinkedHashMap<>(16, 0.75F, true);
	private final List<Integer> free = new ArrayList<>();
	private @Nullable MapTexture texture;
	private long budgetFrame = Long.MIN_VALUE / 2;
	private int budget;

	public BannerAtlas(String label) {
		this.label = label;
		for (int i = 0; i < COLUMNS * ROWS; i++) {
			this.free.add(i);
		}
	}

	public static BannerAtlas shared() {
		if (shared == null) {
			shared = new BannerAtlas("diorama banners");
		}
		return shared;
	}

	/** Which wave frame shows now, or the still one when banners don't wave. */
	public static int frameNow(boolean animate) {
		return animate ? (int) (System.currentTimeMillis() / FRAME_MILLIS % BannerCompositor.WAVE_FRAMES) : BannerCompositor.STILL;
	}

	/**
	 * Draws a banner standing with its foot at (x, y), {@code scale} GUI pixels per sprite pixel,
	 * multiplied by {@code argb}. False if it isn't ready yet. Whole scales keep it pixel-perfect;
	 * others are for the moment of an animation.
	 */
	public boolean draw(GuiGraphicsExtractor graphics, BannerDesign design, BannerCompositor.Size size, float x, float y, float scale, int frame, boolean sepia, int argb) {
		Integer slot = this.slot(new Key(design, sepia));
		if (slot == null || this.texture == null) {
			return false;
		}
		int u = slot % COLUMNS * SLOT_W + frame * CELL_W;
		int v = slot / COLUMNS * SLOT_H + (size == BannerCompositor.Size.MAP ? 0 : CELL_H);
		// The foot sits on whole GUI pixels, between the pole's two wood columns.
		float x0 = Math.round(x) - size.width() / 2 * scale;
		float y0 = Math.round(y) - size.footY() * scale;
		float x1 = x0 + size.width() * scale, y1 = y0 + size.height() * scale;
		MapCanvas.of(graphics).rect(this.texture, Sampling.TILE_CRISP, x0, y0, x1, y1,
			(float) u / SIZE, (float) v / SIZE, (float) (u + size.width()) / SIZE, (float) (v + size.height()) / SIZE, argb);
		return true;
	}

	private @Nullable Integer slot(Key key) {
		Integer slot = this.slots.get(key);
		if (slot != null) {
			return slot;
		}
		// A new budget once a frame's worth of time has passed since the last one began.
		long now = System.nanoTime();
		if (now - this.budgetFrame > 4_000_000L) {
			this.budgetFrame = now;
			this.budget = COMPOSE_BUDGET;
		}
		if (this.budget <= 0) {
			return null;
		}
		this.budget--;
		if (this.free.isEmpty()) {
			Iterator<Map.Entry<Key, Integer>> oldest = this.slots.entrySet().iterator();
			this.free.add(oldest.next().getValue());
			oldest.remove();
		}
		slot = this.free.removeLast();
		this.upload(slot, key);
		this.slots.put(key, slot);
		return slot;
	}

	private void upload(int slot, Key key) {
		if (this.texture == null) {
			this.texture = MapTexture.create(this.label, SIZE, 1);
		}
		BannerCompositor.Tinted base = new BannerCompositor.Tinted(mask(BannerPatternCatalog.BASE), key.design().base().rgb);
		List<BannerCompositor.Tinted> layers = new ArrayList<>();
		for (BannerDesign.Layer layer : key.design().layers()) {
			BannerCompositor.Mask mask = BannerPatternCatalog.known(layer.pattern()) ? mask(layer.pattern()) : null;
			if (mask != null) {
				layers.add(new BannerCompositor.Tinted(mask, layer.color().rgb));
			}
		}
		int[] full = BannerCompositor.compose(base, layers);
		int[] palette = BannerCompositor.palette(base, layers);
		int sx = slot % COLUMNS * SLOT_W, sy = slot / COLUMNS * SLOT_H;
		BannerCompositor.Size[] sizes = BannerCompositor.Size.values();
		for (int row = 0; row < sizes.length; row++) {
			BannerCompositor.Size size = sizes[row];
			int[] cloth = BannerCompositor.reduce(full, base.mask().width(), base.mask().height(), size.clothW, size.clothH, palette);
			for (int f = 0; f <= BannerCompositor.WAVE_FRAMES; f++) {
				int[] sprite = BannerCompositor.frame(cloth, size, f);
				// Uploads are square: a cell is two 16x16 squares, transparent past the sprite.
				for (int half = 0; half < 2; half++) {
					int[] square = new int[CELL_W * CELL_W];
					for (int y = 0; y < CELL_W; y++) {
						int sy2 = half * CELL_W + y;
						if (sy2 >= size.height()) {
							break;
						}
						for (int x = 0; x < size.width(); x++) {
							int argb = sprite[sy2 * size.width() + x];
							if (key.sepia()) {
								argb = BannerCompositor.sepia(argb);
							}
							square[y * CELL_W + x] = abgr(argb);
						}
					}
					this.texture.write(0, sx + f * CELL_W, sy + row * CELL_H + half * CELL_W, CELL_W, square);
				}
			}
		}
	}

	/** The cloth front of a pattern's texture, read once; a plain one if the texture is missing. */
	private static BannerCompositor.Mask mask(String pattern) {
		if (!MASKS.containsKey(pattern)) {
			MASKS.put(pattern, read(pattern));
		}
		BannerCompositor.Mask mask = MASKS.get(pattern);
		if (mask != null) {
			return mask;
		}
		int[] white = new int[BannerCompositor.FRONT_W * BannerCompositor.FRONT_H];
		java.util.Arrays.fill(white, pattern.equals(BannerPatternCatalog.BASE) ? 0xFFFFFFFF : 0);
		return new BannerCompositor.Mask(BannerCompositor.FRONT_W, BannerCompositor.FRONT_H, white);
	}

	private static BannerCompositor.@Nullable Mask read(String pattern) {
		var id = BannerPatternCatalog.texture(pattern);
		try (var input = Minecraft.getInstance().getResourceManager().open(id);
			var image = com.mojang.blaze3d.platform.NativeImage.read(input)) {
			int size = Math.min(image.getWidth(), image.getHeight());
			int[] argb = new int[size * size];
			for (int y = 0; y < size; y++) {
				for (int x = 0; x < size; x++) {
					//? if >=1.21.2 {
					argb[y * size + x] = image.getPixel(x, y);
					//?} else {
					/*int abgr = image.getPixelRGBA(x, y);
					argb[y * size + x] = (abgr & 0xff00ff00) | (abgr & 255) << 16 | (abgr >> 16 & 255);
					*///?}
				}
			}
			return BannerCompositor.Mask.front(argb, size);
		} catch (Exception error) {
			Diorama.LOGGER.warn("Couldn't load the banner pattern texture {}", id, error);
			return null;
		}
	}

	private static int abgr(int argb) {
		return (argb & 0xff00ff00) | (argb & 255) << 16 | (argb >> 16 & 255);
	}

	/** Frees the texture; the next draw starts again. */
	public void close() {
		if (this.texture != null) {
			this.texture.close();
			this.texture = null;
		}
		this.slots.clear();
		this.free.clear();
		for (int i = 0; i < COLUMNS * ROWS; i++) {
			this.free.add(i);
		}
	}

	/** Drops every sprite and mask so the next draws read the current resource packs again. */
	public static void reload() {
		MASKS.clear();
		if (shared != null) {
			shared.close();
		}
	}
}
