package dev.diorama.ui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.bake.BlockLooks;
import dev.diorama.bake.MapPick;
import dev.diorama.bake.TileKey;
import dev.diorama.region.MapRegion;
import dev.diorama.region.RegionGeometry;
import dev.diorama.region.RegionStore;
import dev.diorama.region.RegionTool;
import dev.diorama.region.Ring;
import dev.diorama.render.MapLevel;
import dev.diorama.render.MapTile;
import dev.diorama.render.MapViewport;
import dev.diorama.waypoint.BannerDesign;
import dev.diorama.waypoint.Waypoints;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import org.jspecify.annotations.Nullable;

/**
 * Drawing regions on the world map (docs/design/map-regions.md): while it is on, the left button
 * belongs to the tool picked on a strip of parchment at the bottom of the map (lasso, polygon,
 * eraser, the sixteen dyes, the fill, undo and redo), and the map pans with the right or middle
 * button. Regions are drawn on the surface only. A closed stroke becomes a region lying on the
 * ground the player traced it over; ground the map doesn't know, or that is under the fog, lends
 * no height (see {@link #height}).
 */
public final class RegionMode {
	/** What the mode needs from the map screen. */
	public interface Host extends MapBook.Host {
		/** The view drawn last frame. */
		MapViewport.@Nullable View view();

		/** A short note at the top of the map. */
		void say(Component text);
	}

	private static final int PAD = 4, SLOT = 20, SWATCH = 9, GAP = 6, ARROW = 14;

	// The tool, dye and fill stay picked while the game runs.
	private static RegionTool.Tool tool = RegionTool.Tool.LASSO;
	private static BannerDesign.Dye dye = BannerDesign.Dye.RED;
	private static MapRegion.Fill fill = MapRegion.Fill.NORMAL;

	private final Host host;
	private final RegionTool stroke = new RegionTool();
	private boolean active;
	/** The region the eraser points at. */
	private @Nullable MapRegion pointed;
	private int panelX, panelY, panelW, panelH;
	private @Nullable BookWidget undo, redo;

	public RegionMode(Host host) {
		this.host = host;
	}

	public boolean active() {
		return this.active;
	}

	/** Whether a stroke is under way. */
	public boolean drawing() {
		return this.stroke.drawing();
	}

	public static RegionTool.Tool tool() {
		return tool;
	}

	/** Turns the mode on, if the map shows the surface. */
	public void enter() {
		if (this.host.caves()) {
			this.host.say(Component.translatable("diorama.regions.surface_only"));
			return;
		}
		this.active = true;
		this.stroke.cancel();
		BookWidgets.card(Minecraft.getInstance().getSoundManager());
		this.host.rebuild();
	}

	public void leave() {
		this.active = false;
		this.stroke.cancel();
		this.pointed = null;
		this.host.rebuild();
	}

	public void pick(RegionTool.Tool next) {
		tool = next;
		this.stroke.cancel();
	}

	/** Lets the stroke under way go (a right click, Escape). */
	public void cancelStroke() {
		this.stroke.cancel();
	}

	// ------------------------------------------------------------------------------------------
	// Input, in GUI coordinates

	/** The left button goes down on the map. */
	public void press(double x, double y) {
		MapViewport.View view = this.host.view();
		if (view != null && tool == RegionTool.Tool.LASSO) {
			this.stroke.begin(u(view, x), v(view, y));
		}
	}

	/** The left button drags over the map. */
	public void drag(double x, double y) {
		MapViewport.View view = this.host.view();
		if (view != null && tool == RegionTool.Tool.LASSO && this.stroke.drawing()) {
			this.stroke.extend(u(view, x), v(view, y), view.zoom());
		}
	}

	/** The left button comes up on the map; {@code moved} if it was dragged rather than clicked. */
	public void release(double x, double y, boolean moved) {
		MapViewport.View view = this.host.view();
		if (view == null) {
			return;
		}
		switch (tool) {
			case LASSO -> {
				if (this.stroke.drawing()) {
					this.stroke.extend(u(view, x), v(view, y), view.zoom());
					double[][] outline = this.stroke.finish();
					if (outline != null) this.create(outline);
				}
			}
			case POLYGON -> {
				if (!moved) {
					double[][] outline = this.stroke.click(u(view, x), v(view, y), view.zoom(), System.nanoTime());
					if (outline != null) this.create(outline);
				}
			}
			case ERASER -> {
				if (!moved && this.pointed != null) this.erase(this.pointed);
			}
		}
	}

	/** A key while the mode is on; false leaves it to the map. GLFW's modifier bits. */
	public boolean key(int key, int modifiers) {
		boolean ctrl = (modifiers & (2 | 8)) != 0, shift = (modifiers & 1) != 0;
		if (ctrl && key == InputConstants.KEY_Z) {
			this.history(!shift);
			return true;
		}
		if (ctrl && key == InputConstants.KEY_Y) {
			this.history(false);
			return true;
		}
		switch (key) {
			case InputConstants.KEY_1 -> this.pick(RegionTool.Tool.LASSO);
			case InputConstants.KEY_2 -> this.pick(RegionTool.Tool.POLYGON);
			case InputConstants.KEY_3 -> this.pick(RegionTool.Tool.ERASER);
			case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> {
				if (tool != RegionTool.Tool.POLYGON || !this.stroke.drawing()) return false;
				double[][] outline = this.stroke.close();
				if (outline != null) this.create(outline);
				else this.host.say(Component.translatable("diorama.regions.too_few"));
			}
			case InputConstants.KEY_BACKSPACE -> {
				if (!this.stroke.drawing()) return false;
				this.stroke.removeLast();
			}
			default -> {
				return false;
			}
		}
		return true;
	}

	// ------------------------------------------------------------------------------------------
	// Changes

	private void create(double[][] outline) {
		MapSession session = MapSession.get();
		BlockLooks looks = BlockLooks.get();
		RegionStore store = RegionStore.get();
		String dimension = Waypoints.dimension();
		LocalPlayer player = Minecraft.getInstance().player;
		if (session == null || looks == null || store == null || dimension == null || player == null) {
			return;
		}
		if (store.readOnly()) {
			this.host.say(Component.translatable("diorama.regions.read_only"));
			return;
		}
		MapLevel level = session.map();
		Ring ring = RegionGeometry.fromMap(outline[0], outline[1], (u, v) -> height(level, looks, u, v), player.getBlockY(), Projection.direction());
		if (ring == null) {
			this.host.say(Component.translatable("diorama.regions.too_small"));
			return;
		}
		MapRegion region = new MapRegion();
		region.name = Component.translatable("diorama.regions.default_name", store.in(dimension).size() + 1).getString();
		region.dimension = dimension;
		region.dye = dye;
		region.fill = fill;
		region.rings.add(ring);
		if (store.commit(null, region)) {
			BookWidgets.play(SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, 1.0F);
			this.host.say(Component.translatable("diorama.regions.created", region.name));
		}
	}

	private void erase(MapRegion region) {
		RegionStore store = RegionStore.get();
		if (store == null) {
			return;
		}
		if (store.commit(region.copy(), null)) {
			BookWidgets.play(SoundEvents.BOOK_PAGE_TURN, 0.7F);
			this.host.say(Component.translatable("diorama.regions.erased", region.name));
		} else if (store.readOnly()) {
			this.host.say(Component.translatable("diorama.regions.read_only"));
		}
		this.pointed = null;
	}

	/** Undoes ({@code back}) or redoes the last change in this dimension. */
	private void history(boolean back) {
		RegionStore store = RegionStore.get();
		String dimension = Waypoints.dimension();
		this.stroke.cancel();
		if (store != null && dimension != null && (back ? store.undo(dimension) : store.redo(dimension))) {
			BookWidgets.pageTurn(Minecraft.getInstance().getSoundManager());
			this.host.say(Component.translatable(back ? "diorama.regions.undone" : "diorama.regions.redone"));
		}
	}

	/**
	 * The height of the ground the map shows at a map point, for a region's outline: only where
	 * the map knows the ground and it is explored, so terrain under the fog never shapes a region.
	 */
	static int height(MapLevel level, BlockLooks looks, double u, double v) {
		int tu = (int) Math.floor(u / Projection.TILE_UNITS), tv = (int) Math.floor(v / Projection.TILE_UNITS);
		MapTile tile = level.peek(new TileKey(TileKey.SURFACE, tu, tv));
		if (tile == null || !tile.hasCoverage()) {
			return RegionGeometry.UNKNOWN;
		}
		double cell = (double) Projection.TILE_UNITS / Projection.COVERAGE_CELLS;
		int cx = Math.min(Projection.COVERAGE_CELLS - 1, (int) ((u - tu * (double) Projection.TILE_UNITS) / cell));
		int cy = Math.min(Projection.COVERAGE_CELLS - 1, (int) ((v - tv * (double) Projection.TILE_UNITS) / cell));
		if (tile.coverage(cx, cy) <= 0) {
			return RegionGeometry.UNKNOWN;
		}
		BlockPos spot = MapPick.standingSpot(level, looks, u, v, TileKey.SURFACE, level.fogOfWar());
		return spot == null ? RegionGeometry.UNKNOWN : spot.getY();
	}

	// ------------------------------------------------------------------------------------------
	// Drawing

	/** Each frame before the map is drawn: what the eraser points at, drawn brighter. */
	public void frame(MapViewport viewport, int mouseX, int mouseY, boolean onMap) {
		MapViewport.View view = this.host.view();
		this.pointed = this.active && tool == RegionTool.Tool.ERASER && onMap && view != null && !this.covers(mouseX, mouseY)
			? viewport.regionAt(u(view, mouseX), v(view, mouseY)) : null;
		viewport.highlight(this.pointed == null ? null : this.pointed.id);
		RegionStore store = RegionStore.get();
		String dimension = Waypoints.dimension();
		if (this.undo != null) this.undo.active = store != null && dimension != null && store.canUndo(dimension);
		if (this.redo != null) this.redo.active = store != null && dimension != null && store.canRedo(dimension);
	}

	/** The stroke under way, over the map: a line in the dye, its corners, and the polygon's next side to the pointer. */
	public void draw(GuiGraphicsExtractor graphics, MapViewport.View view, int mouseX, int mouseY) {
		int n = this.stroke.count();
		if (!this.active || n == 0) {
			return;
		}
		int color = dye.argb();
		int[] xs = new int[n], ys = new int[n];
		for (int i = 0; i < n; i++) {
			xs[i] = (int) Math.floor(view.screenX(this.stroke.u(i)));
			ys[i] = (int) Math.floor(view.screenY(this.stroke.v(i)));
		}
		for (int i = 1; i < n; i++) {
			line(graphics, xs[i - 1], ys[i - 1], xs[i], ys[i], color);
		}
		if (tool == RegionTool.Tool.POLYGON) {
			line(graphics, xs[n - 1], ys[n - 1], mouseX, mouseY, 0x90000000 | color & 0xFFFFFF);
			boolean closing = n >= 3 && Math.hypot(mouseX - xs[0], mouseY - ys[0]) <= RegionTool.CLOSE_REACH;
			for (int i = 0; i < n; i++) {
				int r = i == 0 && closing ? 3 : 2;
				graphics.fill(xs[i] - r, ys[i] - r, xs[i] + r + 1, ys[i] + r + 1, BookSprites.INK);
				graphics.fill(xs[i] - r + 1, ys[i] - r + 1, xs[i] + r, ys[i] + r, i == 0 && closing ? 0xFFFCE3C0 : color);
			}
		} else {
			// The lasso closes back to its start as it is let go: shown faintly.
			line(graphics, xs[n - 1], ys[n - 1], xs[0], ys[0], 0x60000000 | color & 0xFFFFFF);
		}
	}

	/** A one pixel line in GUI pixels, Bresenham's. */
	private static void line(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int argb) {
		int dx = Math.abs(x1 - x0), dy = -Math.abs(y1 - y0);
		int sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1;
		int err = dx + dy;
		for (int guard = 0; guard < 4096; guard++) {
			graphics.fill(x0, y0, x0 + 1, y0 + 1, argb);
			if (x0 == x1 && y0 == y1) return;
			int e2 = 2 * err;
			if (e2 >= dy) {
				err += dy;
				x0 += sx;
			}
			if (e2 <= dx) {
				err += dx;
				y0 += sy;
			}
		}
	}

	/** The parchment strip under the toolbar, drawn before its widgets. */
	public void drawPanel(GuiGraphicsExtractor graphics) {
		if (this.active && this.panelW > 0) {
			BookSprites.nine(graphics, BookSprites.LABEL, this.panelX, this.panelY, this.panelW, this.panelH, -1, true);
		}
	}

	/** What the slip at the top says while the mode is on. */
	public Component hint() {
		if (tool == RegionTool.Tool.ERASER && this.pointed != null) {
			return Component.translatable("diorama.regions.hint.erase", this.pointed.name);
		}
		return Component.translatable("diorama.regions.hint." + tool.name().toLowerCase(java.util.Locale.ROOT));
	}

	/** Whether a point is on the toolbar. */
	public boolean covers(double x, double y) {
		return this.active && x >= this.panelX && x < this.panelX + this.panelW && y >= this.panelY && y < this.panelY + this.panelH;
	}

	// ------------------------------------------------------------------------------------------
	// The toolbar

	/** Adds the toolbar's widgets along the bottom of a screen {@code width} by {@code height}. */
	public void build(int width, int height) {
		this.undo = null;
		this.redo = null;
		this.panelW = 0;
		if (!this.active) {
			return;
		}
		Component done = Component.translatable("diorama.regions.done");
		int doneW = BookWidgets.font().width(done) + 12;
		int w = PAD + 3 * SLOT + 2 * 2 + GAP + 8 * SWATCH + GAP + SLOT + GAP + 2 * ARROW + 2 + GAP + doneW + PAD;
		int h = SLOT + 2 * 3;
		this.panelX = (width - w) / 2;
		this.panelY = height - h - 4;
		this.panelW = w;
		this.panelH = h;
		int x = this.panelX + PAD, y = this.panelY + 3;
		RegionTool.Tool[] tools = RegionTool.Tool.values();
		String[] icons = {"lead", "tripwire_hook", "brush"};
		for (int i = 0; i < tools.length; i++) {
			RegionTool.Tool t = tools[i];
			String name = t.name().toLowerCase(java.util.Locale.ROOT);
			BookWidgets.Slot slot = this.host.add(new BookWidgets.Slot(x, y, SLOT, SLOT, Component.translatable("diorama.regions.tool." + name),
				BookWidgets.item(BookSprites.stack(icons[i])), () -> tool == t, () -> this.pick(t), false));
			slot.setTooltip(Tooltip.create(Component.translatable("diorama.regions.tool." + name + ".tip", i + 1)));
			x += SLOT + 2;
		}
		x += GAP - 2;
		BannerDesign.Dye[] dyes = BannerDesign.Dye.values();
		for (int i = 0; i < dyes.length; i++) {
			BannerDesign.Dye d = dyes[i];
			this.host.add(new Swatch(x + (i % 8) * SWATCH, y + 1 + (i / 8) * SWATCH, d));
		}
		x += 8 * SWATCH + GAP;
		BookWidgets.Slot fillSlot = this.host.add(new BookWidgets.Slot(x, y, SLOT, SLOT, Component.translatable("diorama.regions.fill"),
			(graphics, sx, sy, sw, sh, alpha) -> {
				int a = Math.round(255 * new float[] {0, 0.25F, 0.5F, 0.8F}[fill.ordinal()]);
				graphics.fill(sx + 5, sy + 5, sx + sw - 5, sy + sh - 5, a << 24 | dye.rgb);
				outline(graphics, sx + 4, sy + 4, sw - 8, sh - 8, dye.argb());
			}, () -> false, () -> fill = MapRegion.Fill.values()[(fill.ordinal() + 1) % MapRegion.Fill.values().length], false));
		fillSlot.setTooltip(Tooltip.create(Component.translatable("diorama.regions.fill.tip")));
		x += SLOT + GAP;
		int ay = y + (SLOT - ARROW) / 2;
		this.undo = this.host.add(new BookWidgets.Slot(x, ay, ARROW, ARROW, Component.translatable("diorama.regions.undo"),
			arrow(BookSprites.ARROW_LEFT), () -> false, () -> this.history(true), false));
		this.undo.setTooltip(Tooltip.create(Component.translatable("diorama.regions.undo.tip")));
		x += ARROW + 2;
		this.redo = this.host.add(new BookWidgets.Slot(x, ay, ARROW, ARROW, Component.translatable("diorama.regions.redo"),
			arrow(BookSprites.ARROW_RIGHT), () -> false, () -> this.history(false), false));
		this.redo.setTooltip(Tooltip.create(Component.translatable("diorama.regions.redo.tip")));
		x += ARROW + GAP;
		this.host.add(new BookWidgets.Button(x, y + (SLOT - BookWidgets.BUTTON) / 2, doneW, done, this::leave));
	}

	private static BookWidgets.Art arrow(BookSprites.Region sprite) {
		return (graphics, x, y, w, h, alpha) -> BookSprites.sprite(graphics, sprite, x + (w - sprite.w()) / 2, y + (h - sprite.h()) / 2, -1);
	}

	private static void outline(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int argb) {
		graphics.fill(x, y, x + w, y + 1, argb);
		graphics.fill(x, y + h - 1, x + w, y + h, argb);
		graphics.fill(x, y, x + 1, y + h, argb);
		graphics.fill(x + w - 1, y, x + w, y + h, argb);
	}

	/** One of the sixteen dyes, picked for the next region. */
	private static final class Swatch extends BookWidget {
		private final BannerDesign.Dye dye;

		Swatch(int x, int y, BannerDesign.Dye dye) {
			super(x, y, SWATCH, SWATCH, Component.translatable("color.minecraft." + dye.id()));
			this.dye = dye;
			this.setTooltip(Tooltip.create(this.getMessage()));
		}

		@Override
		protected void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean lit) {
			int x = this.getX(), y = this.getY();
			boolean picked = RegionMode.dye == this.dye;
			graphics.fill(x, y, x + SWATCH, y + SWATCH, picked ? 0xFFE8B83A : lit ? BookSprites.INK_SOFT : BookSprites.INK);
			graphics.fill(x + 1, y + 1, x + SWATCH - 1, y + SWATCH - 1, picked ? BookSprites.INK : this.dye.argb());
			if (picked) {
				graphics.fill(x + 2, y + 2, x + SWATCH - 2, y + SWATCH - 2, this.dye.argb());
			}
		}

		@Override
		protected void press(double x, double y) {
			RegionMode.dye = this.dye;
		}
	}

	private static double u(MapViewport.View view, double x) {
		return view.centerU() + (x - view.x() - view.width() / 2.0) / view.zoom();
	}

	private static double v(MapViewport.View view, double y) {
		return view.centerV() + (y - view.y() - view.height() / 2.0) / view.zoom();
	}

	/** For tests: the regions of the dimension the player is in. */
	public static List<MapRegion> here() {
		RegionStore store = RegionStore.get();
		String dimension = Waypoints.dimension();
		return store == null || dimension == null ? List.of() : new ArrayList<>(store.in(dimension));
	}
}
