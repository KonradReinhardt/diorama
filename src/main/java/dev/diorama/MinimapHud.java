package dev.diorama;

import dev.diorama.bake.TileKey;
import dev.diorama.region.MapRegion;
import dev.diorama.region.RegionStore;
import dev.diorama.render.BannerAtlas;
import dev.diorama.render.BannerCompositor;
import dev.diorama.render.MapLevel;
import dev.diorama.render.MapViewport;
import dev.diorama.render.Markers;
import dev.diorama.render.PaperFrame;
import dev.diorama.render.gpu.ClippedCanvas;
import dev.diorama.render.Radar;
import dev.diorama.waypoint.WaypointOverlay;
import dev.diorama.waypoint.WaypointStore;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.phys.Vec3;

/**
 * The minimap, centred on the player, in the corner the settings pick. Compass letters sit on its
 * edges, and coordinates and the biome sit on the side away from the screen edge.
 */
public final class MinimapHud implements HudElement {
	private static final int MARGIN = 6;
	/** Half the hotbar's width; hearts and hunger sit in the same columns just above it. */
	private static final int HOTBAR_HALF_WIDTH = 91;
	private static final int SMALLEST = 64;
	/**
	 * Longest the minimap keeps showing the old slice while the new one bakes. A tile takes about
	 * half a second to bake and a new slice can need four at once, so this leaves room for that.
	 */
	private static final long LAYER_HOLD_MS = 3000;

	private final MapViewport viewport = new MapViewport(true);
	/** The slice on screen. Moving into another slice keeps this one until the next has baked. */
	private int shownLayer;
	private boolean showing;
	/** When the minimap started holding the old slice, or -1 when it isn't. */
	private long holdStart = -1;

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		MapSession session = MapSession.get();
		LocalPlayer player = mc.player;
		Settings settings = Settings.get();
		//? if >=26.2 {
		if (session == null || player == null || mc.level == null || !settings.minimap || mc.gui.hud.isHidden()
		//?} else {
		/*if (session == null || player == null || mc.level == null || !settings.minimap || mc.options.hideGui
		*///?}
			|| mc.gui.screen() instanceof MapScreen || mc.gui.screen() instanceof AbstractContainerScreen<?>
			|| !ServerRules.minimapAllowed()) {
			// Not drawn. Inventories and chests count, because the item lists other mods (JEI, REI,
			// EMI) put beside them run into the minimap's corner.
			// A hold picks up afresh when it's drawn again, rather than having run out meanwhile.
			this.holdStart = -1;
			return;
		}
		float partialTick = deltaTracker.getGameTimeDeltaPartialTick(true);
		Vec3 pos = player.getPosition(partialTick);
		int size = settings.minimapSize.pixels;
		Font font = mc.font;
		List<String> info = this.infoLines(settings, player, mc);
		// The region the player stands in, its banner and name beside the lines.
		RegionStore regions = settings.minimapRegions ? RegionStore.get() : null;
		MapRegion here = regions == null ? null : regions.containing(mc.level.dimension().identifier().toString(), pos.x, pos.z, true);
		// Beside the lines where they leave room for the name, else on a row of its own under them.
		boolean stacked = here != null && this.regionRoom(font, info, settings.minimapSize.pixels) < Math.min(font.width(here.name), REGION_MIN_ROOM);
		int infoHeight = here == null ? info.size() * 10 : stacked ? info.size() * 10 + REGION_HEIGHT : Math.max(info.size() * 10, REGION_HEIGHT);
		boolean right = settings.minimapCorner == Settings.Corner.TOP_RIGHT || settings.minimapCorner == Settings.Corner.BOTTOM_RIGHT;
		boolean top = settings.minimapCorner == Settings.Corner.TOP_RIGHT || settings.minimapCorner == Settings.Corner.TOP_LEFT;
		int effects = top ? this.effectRows(player, right) : 0;
		size = this.fit(size, graphics.guiWidth(), graphics.guiHeight(), top, effects, infoHeight);
		boolean paper = settings.minimapFrame == Settings.MinimapFrame.MAP;
		int margin = this.margin(settings);
		int x = right ? graphics.guiWidth() - size - margin : margin;
		int y = top ? margin + effects : graphics.guiHeight() - size - margin - infoHeight;
		int wantedLayer = settings.autoCaves ? session.autoLayer(player) : TileKey.SURFACE;
		int layer = this.layerToShow(session.map(), new MapViewport.View(x, y, size, size, Projection.u(pos.x, pos.z), Projection.worldV(pos.x, pos.y, pos.z), settings.zoom(), wantedLayer));

		boolean cutout = settings.minimapFrame == Settings.MinimapFrame.MAP_CUTOUT;
		if (paper) {
			PaperFrame.draw(graphics, x, y, size);
		} else if (!cutout) {
			graphics.fill(x - 2, y - 2, x + size + 2, y + size + 2, 0xC0101418);
			graphics.fill(x - 1, y - 1, x + size + 1, y + size + 1, 0x60FFFFFF);
		}
		MapViewport.View view = new MapViewport.View(x, y, size, size, Projection.u(pos.x, pos.z), Projection.worldV(pos.x, pos.y, pos.z), settings.zoom(), layer).pixelPerfect();
		if (cutout) {
			// The map itself takes the vanilla sheet's shape: drawn only where its texture is opaque, the sheet over it.
			ClippedCanvas.within(PaperFrame.silhouette(x, y, size), () -> this.viewport.draw(graphics, session.map(), view));
			PaperFrame.cutout(graphics, x, y, size);
		} else {
			this.viewport.draw(graphics, session.map(), view);
		}
		if (paper) {
			PaperFrame.edge(graphics, x, y, size);
		}
		List<Radar.Blip> blips = ServerRules.radarAllowed() ? Radar.collect(view, partialTick, false) : List.of();
		Radar.draw(graphics, view, blips, false, false);
		WaypointStore store = WaypointStore.get();
		if (store != null && settings.waypointsOnMap) {
			WaypointOverlay.draw(graphics, view, WaypointOverlay.layout(view, store.in(mc.level.dimension().identifier().toString()), false), false, null);
		}
		Radar.draw(graphics, view, blips, false, true);
		Markers.player(graphics, x + size / 2.0F, y + size / 2.0F, player.getViewYRot(partialTick), 1.5F);
		if (settings.compass) {
			if (paper) {
				this.inkedCompass(graphics, font, x, y, size);
			} else if (cutout) {
				this.taggedCompass(graphics, font, x, y, size);
			} else {
				this.compass(graphics, font, x, y, size);
			}
		}
		int textY = y + size + (paper ? PaperFrame.BORDER + 3 : 4);
		if (here != null) {
			this.region(graphics, font, here, stacked ? List.of() : info, x, stacked ? textY + info.size() * 10 + 1 : textY, size, right);
		}
		for (String line : info) {
			int w = font.width(line);
			int tx = right ? x + size - w : x;
			graphics.text(font, line, tx, textY, 0xFFFFFFFF, true);
			textY += 10;
		}
	}

	/** Rows the region under the minimap takes: its small banner's height. */
	private static final int REGION_HEIGHT = 22;
	/** Room for a region's name beside the lines below which it goes on a row of its own. */
	private static final int REGION_MIN_ROOM = 40;

	/** Room the lines leave beside them for a region's name, under a minimap {@code size} across. */
	private int regionRoom(Font font, List<String> info, int size) {
		int taken = 0;
		for (String line : info) {
			taken = Math.max(taken, font.width(line));
		}
		return size - taken - BannerCompositor.Size.SMALL.width() - 3 - (info.isEmpty() ? 0 : 6);
	}

	/**
	 * The region the player is in, under the minimap on the side away from the coordinates and
	 * biome: its banner (its own, or a plain one in its dye), its name beside it, cut to the room
	 * the lines leave; given no lines, on a row of its own under them.
	 */
	private void region(GuiGraphicsExtractor graphics, Font font, MapRegion region, List<String> info, int x, int textY, int size, boolean infoRight) {
		BannerCompositor.Size banner = BannerCompositor.Size.SMALL;
		int room = this.regionRoom(font, info, size);
		String name = font.plainSubstrByWidth(region.name, Math.max(0, room));
		if (name.length() < region.name.length()) {
			name = font.plainSubstrByWidth(region.name, Math.max(0, room - font.width("…"))) + "…";
		}
		int bannerX = infoRight ? x + banner.width() / 2 : x + size - banner.width() / 2 - 1;
		BannerAtlas.shared().draw(graphics, region.design(), banner, bannerX, textY + banner.footY(), 1,
			BannerAtlas.frameNow(Settings.get().animateBanners), false, -1);
		int nameX = infoRight ? x + banner.width() + 3 : x + size - banner.width() - 3 - font.width(name);
		graphics.text(font, name, nameX, textY + 6, 0xFFFFFFFF, true);
	}

	/**
	 * The slice to draw for a view of the slice the player is in: that one once its tiles have
	 * baked, the one already on screen until then, so climbing past a slice edge doesn't flash
	 * the minimap blank.
	 */
	private int layerToShow(MapLevel level, MapViewport.View wanted) {
		long now = System.currentTimeMillis();
		if (!this.showing || wanted.layer() == this.shownLayer) {
			this.showing = true;
			this.shownLayer = wanted.layer();
			this.holdStart = -1;
			return this.shownLayer;
		}
		if (this.holdStart < 0) {
			this.holdStart = now;
		}
		MapViewport.View shown = new MapViewport.View(wanted.x(), wanted.y(), wanted.width(), wanted.height(),
			wanted.centerU(), wanted.centerV(), wanted.zoom(), this.shownLayer);
		// The old slice only helps if it has something to show: after a teleport or a new dimension it doesn't.
		if (MapViewport.ready(level, wanted) || now - this.holdStart > LAYER_HOLD_MS || !MapViewport.showsAnything(level, shown)) {
			this.shownLayer = wanted.layer();
			this.holdStart = -1;
		}
		return this.shownLayer;
	}

	/**
	 * Shrinks the minimap so it and its info lines stay on screen and, in a bottom corner, beside
	 * the hotbar instead of over the hearts or hunger bar.
	 */
	private int fit(int size, int width, int height, boolean top, int effects, int infoHeight) {
		int fitted = size;
		int margin = this.margin(Settings.get());
		if (top) {
			fitted = Math.min(fitted, height - margin - effects - infoHeight - margin - 4);
		} else {
			fitted = Math.min(fitted, width / 2 - HOTBAR_HALF_WIDTH - 2 * margin - 4);
			fitted = Math.min(fitted, height - infoHeight - 2 * margin);
		}
		return Math.max(SMALLEST, Math.min(size, fitted));
	}

	/** Room from the screen's edge to the map: more for the paper, which reaches out past it. */
	private int margin(Settings settings) {
		return settings.minimapFrame == Settings.MinimapFrame.MAP ? PaperFrame.BORDER + 3 : MARGIN;
	}

	/** Cardinal letters written in ink on the paper's margin, north in red ink. */
	private void inkedCompass(GuiGraphicsExtractor graphics, Font font, int x, int y, int size) {
		// Centred on the clean paper between the map and the torn outline.
		int clean = PaperFrame.BORDER - PaperFrame.OUTLINE;
		int top = y - clean / 2 - 4, bottom = y + size + clean / 2 - 3;
		int left = x - clean / 2, right = x + size + clean / 2;
		this.ink(graphics, font, Projection.direction().topLetter(), x + size / 2, top, 0xFF8E2A1C);
		this.ink(graphics, font, Projection.direction().bottomLetter(), x + size / 2, bottom, PaperFrame.INK);
		this.ink(graphics, font, Projection.direction().leftLetter(), left, y + size / 2 - 4, PaperFrame.INK);
		this.ink(graphics, font, Projection.direction().rightLetter(), right, y + size / 2 - 4, PaperFrame.INK);
	}

	/** Cardinal letters in ink on little paper tags just inside the sheet's edge. */
	private void taggedCompass(GuiGraphicsExtractor graphics, Font font, int x, int y, int size) {
		int in = PaperFrame.BORDER - 2;
		this.tag(graphics, font, Projection.direction().topLetter(), x + size / 2, y + in, 0xFF8E2A1C);
		this.tag(graphics, font, Projection.direction().bottomLetter(), x + size / 2, y + size - in - 8, PaperFrame.INK);
		this.tag(graphics, font, Projection.direction().leftLetter(), x + in + 3, y + size / 2 - 4, PaperFrame.INK);
		this.tag(graphics, font, Projection.direction().rightLetter(), x + size - in - 3, y + size / 2 - 4, PaperFrame.INK);
	}

	private void tag(GuiGraphicsExtractor graphics, Font font, String letter, int cx, int y, int color) {
		int w = font.width(letter);
		int x = cx - w / 2;
		graphics.fill(x - 2, y - 2, x + w + 1, y + 9, PaperFrame.INK);
		graphics.fill(x - 1, y - 1, x + w, y + 8, 0xFFE8D7A8);
		graphics.text(font, letter, x, y, color, false);
	}

	private void ink(GuiGraphicsExtractor graphics, Font font, String letter, int cx, int y, int color) {
		graphics.text(font, letter, cx - font.width(letter) / 2, y, color, false);
	}

	/** Cardinal letters on the frame, following the selected map direction. */
	private void compass(GuiGraphicsExtractor graphics, Font font, int x, int y, int size) {
		this.letter(graphics, font, Projection.direction().topLetter(), x + size / 2, y + 1, 0xFFFF6B6B);
		this.letter(graphics, font, Projection.direction().bottomLetter(), x + size / 2, y + size - 9, 0xFFFFFFFF);
		this.letter(graphics, font, Projection.direction().leftLetter(), x + 5, y + size / 2 - 4, 0xFFFFFFFF);
		this.letter(graphics, font, Projection.direction().rightLetter(), x + size - 5, y + size / 2 - 4, 0xFFFFFFFF);
	}

	private void letter(GuiGraphicsExtractor graphics, Font font, String letter, int cx, int y, int color) {
		int w = font.width(letter);
		graphics.fill(cx - w / 2 - 2, y - 1, cx - w / 2 + w + 1, y + 8, 0xA0101418);
		graphics.text(font, letter, cx - w / 2, y, color, false);
	}

	private List<String> infoLines(Settings settings, LocalPlayer player, Minecraft mc) {
		List<String> lines = new ArrayList<>();
		BlockPos pos = player.blockPosition();
		if (settings.coordinates) {
			lines.add(pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
		}
		if (settings.biome) {
			mc.level.getBiome(pos).unwrapKey().ifPresent(key ->
				lines.add(Component.translatable(key.identifier().toLanguageKey("biome")).getString()));
		}
		return lines;
	}

	/** Vanilla draws status effect icons in the top-right corner; move the minimap below them. */
	private int effectRows(LocalPlayer player, boolean right) {
		if (!right) {
			return 0;
		}
		boolean beneficial = false;
		boolean harmful = false;
		for (MobEffectInstance effect : player.getActiveEffects()) {
			if (!effect.showIcon()) {
				continue;
			}
			if (effect.getEffect().value().isBeneficial()) {
				beneficial = true;
			} else {
				harmful = true;
			}
		}
		return harmful ? 52 : beneficial ? 26 : 0;
	}
}
