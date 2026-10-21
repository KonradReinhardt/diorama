package dev.diorama.ui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.diorama.MapDirection;
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
import java.util.UUID;
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
 * edit, eraser, the sixteen dyes, the fill, undo and redo), and the map pans with the right or
 * middle button. Regions are drawn on the surface only. A closed stroke becomes a region lying on
 * the ground the player traced it over; ground the map doesn't know, or that is under the fog,
 * lends no height (see {@link #height}). The edit tool works on one region at a time: its corners
 * are dragged, a click on a side adds one, a right click takes one away, and a drag inside moves
 * the whole region; the map shows the change as it is made, and it is kept on letting go.
 */
public final class RegionMode {
	/** What the mode needs from the map screen. */
	public interface Host extends MapBook.Host {
		/** The view drawn last frame. */
		MapViewport.@Nullable View view();

		/** A short note at the top of the map. */
		void say(Component text);

		/** A region was just drawn: it is to be named. */
		void created(MapRegion region);
	}

	private static final int PAD = 4, SLOT = 20, GAP = 6, ARROW = 14;

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
	private @Nullable MapViewport viewport;

	/** The region the edit tool works on. */
	private @Nullable UUID target;
	/** The target as it was when a drag began, and as the drag has it now; null while nothing is dragged. */
	private @Nullable MapRegion before, working;
	/** What the drag holds: a corner of a ring, or the whole region from a map point. */
	private int grabRing = -1, grabVertex = -1;
	private boolean grabWhole;
	private double grabU, grabV;
	private boolean changed;
	/** Goes up with every change a drag makes, so the map draws it again. */
	private long previewVersion;

	/** A corner, or a side from corner {@code vertex} to the next, of a region's ring. */
	private record Hit(int ring, int vertex, boolean side) {
	}

	public RegionMode(Host host) {
		this.host = host;
	}

	public boolean active() {
		return this.active;
	}

	/** Whether a stroke or a drag is under way. */
	public boolean drawing() {
		return this.stroke.drawing() || this.working != null;
	}

	public static RegionTool.Tool tool() {
		return tool;
	}

	/** The region the edit tool works on, if it still exists. */
	public @Nullable MapRegion target() {
		RegionStore store = RegionStore.get();
		MapRegion region = store == null || this.target == null ? null : store.find(this.target);
		if (region == null) {
			this.target = null;
		}
		return region;
	}

	/** Turns the mode on, if the map shows the surface. */
	public void enter() {
		if (this.host.caves()) {
			this.host.say(Component.translatable("diorama.regions.surface_only"));
			return;
		}
		this.active = true;
		this.cancelStroke();
		BookWidgets.card(Minecraft.getInstance().getSoundManager());
		this.host.rebuild();
	}

	/** Turns the mode on with the edit tool on a region. */
	public void edit(MapRegion region) {
		tool = RegionTool.Tool.EDIT;
		this.target = region.id;
		this.enter();
	}

	public void leave() {
		this.active = false;
		this.cancelStroke();
		this.pointed = null;
		this.target = null;
		this.host.rebuild();
	}

	public void pick(RegionTool.Tool next) {
		tool = next;
		this.cancelStroke();
	}

	/** Lets the stroke or drag under way go (a right click, Escape): the region stays as it was. */
	public void cancelStroke() {
		this.stroke.cancel();
		this.before = null;
		this.working = null;
		this.previewVersion++;
	}

	// ------------------------------------------------------------------------------------------
	// Input, in GUI coordinates

	/** The left button goes down on the map. */
	public void press(double x, double y) {
		MapViewport.View view = this.host.view();
		if (view == null) {
			return;
		}
		if (tool == RegionTool.Tool.LASSO) {
			this.stroke.begin(u(view, x), v(view, y));
		} else if (tool == RegionTool.Tool.EDIT) {
			this.grab(view, x, y);
		}
	}

	/** The left button drags over the map. */
	public void drag(double x, double y) {
		MapViewport.View view = this.host.view();
		if (view == null) {
			return;
		}
		if (tool == RegionTool.Tool.LASSO && this.stroke.drawing()) {
			this.stroke.extend(u(view, x), v(view, y), view.zoom());
		} else if (tool == RegionTool.Tool.EDIT && this.working != null) {
			this.pull(view, x, y);
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
			case EDIT -> this.letGo();
			case ERASER -> {
				if (!moved && this.pointed != null) this.erase(this.pointed);
			}
		}
	}

	/** A right click on the map that wasn't a drag; true if the tool took it. */
	public boolean rightClick(double x, double y) {
		MapViewport.View view = this.host.view();
		MapRegion region = this.target();
		if (tool != RegionTool.Tool.EDIT || view == null || region == null || this.working != null) {
			return false;
		}
		Hit hit = hit(view, region, x, y);
		if (hit == null || hit.side()) {
			return false;
		}
		MapRegion after = region.copy();
		Ring fewer = after.rings.get(hit.ring()).without(hit.vertex());
		if (fewer != null) {
			after.rings.set(hit.ring(), fewer);
		} else if (after.rings.size() > 1) {
			after.rings.remove(hit.ring());
		} else {
			this.host.say(Component.translatable("diorama.regions.too_few"));
			return true;
		}
		after.reshaped();
		this.keep(region.copy(), after);
		return true;
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
			case InputConstants.KEY_3 -> this.pick(RegionTool.Tool.EDIT);
			case InputConstants.KEY_4 -> this.pick(RegionTool.Tool.ERASER);
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
	// The edit tool

	/**
	 * The press of the edit tool: on a corner of the target it takes the corner, on a side it adds
	 * a corner there and takes it, inside the target it takes the whole region; anywhere else it
	 * picks the region there as the target, or none.
	 */
	private void grab(MapViewport.View view, double x, double y) {
		MapRegion region = this.target();
		Hit hit = region == null ? null : hit(view, region, x, y);
		double u = u(view, x), v = v(view, y);
		this.changed = false;
		if (hit != null) {
			this.before = region.copy();
			this.working = region.copy();
			Ring ring = this.working.rings.get(hit.ring());
			int vertex = hit.vertex();
			if (hit.side()) {
				int next = (vertex + 1) % ring.size();
				int h = this.ground(u, v, (ring.y(vertex) + ring.y(next)) / 2);
				float[] at = world(u, v, h);
				this.working.rings.set(hit.ring(), ring.inserting(vertex, at[0], at[1], h));
				vertex++;
				this.changed = true;
			}
			this.grabRing = hit.ring();
			this.grabVertex = vertex;
			this.grabWhole = false;
			this.working.reshaped();
			this.previewVersion++;
			return;
		}
		MapRegion under = this.viewport == null ? null : this.viewport.regionAt(u, v);
		if (region != null && under != null && under.id.equals(region.id)) {
			this.before = region.copy();
			this.working = region.copy();
			this.grabWhole = true;
			this.grabU = u;
			this.grabV = v;
		} else {
			this.target = under == null ? null : under.id;
		}
	}

	/** The edit tool's drag: the corner held follows the ground under the pointer, or the whole region moves with it. */
	private void pull(MapViewport.View view, double x, double y) {
		MapRegion before = this.before, working = this.working;
		if (before == null || working == null) {
			return;
		}
		double u = u(view, x), v = v(view, y);
		if (this.grabWhole) {
			// Along the ground, at the height each corner already has.
			MapDirection d = Projection.direction();
			double du = u - this.grabU, depth = (v - this.grabV) / Projection.zScale();
			float dx = snap(d.worldX(du, depth)), dz = snap(d.worldZ(du, depth));
			List<Ring> moved = new ArrayList<>();
			for (Ring ring : before.rings) {
				moved.add(ring.moved(dx, dz, ring::y));
			}
			working.rings = moved;
			this.changed = dx != 0 || dz != 0;
		} else {
			Ring ring = working.rings.get(this.grabRing);
			int h = this.ground(u, v, ring.y(this.grabVertex));
			float[] at = world(u, v, h);
			working.rings.set(this.grabRing, ring.with(this.grabVertex, at[0], at[1], h));
			this.changed = true;
		}
		working.reshaped();
		this.previewVersion++;
	}

	/** The edit tool lets go: what the drag changed is kept, as one step of the history. */
	private void letGo() {
		MapRegion before = this.before, working = this.working;
		this.before = null;
		this.working = null;
		this.previewVersion++;
		if (before == null || working == null || !this.changed) {
			return;
		}
		if (this.grabWhole) {
			// The region moved: each corner settles on the ground the map shows where it was let
			// go, as a stroke drawn there would, so the outline keeps the shape it had on screen.
			List<Ring> settled = new ArrayList<>();
			for (Ring ring : working.rings) {
				int n = ring.size();
				float[] xs = new float[n], zs = new float[n];
				short[] ys = new short[n];
				for (int i = 0; i < n; i++) {
					double u = Projection.u(ring.x(i), ring.z(i)), v = Projection.worldV(ring.x(i), ring.y(i), ring.z(i));
					int h = this.ground(u, v, ring.y(i));
					float[] at = world(u, v, h);
					xs[i] = at[0];
					zs[i] = at[1];
					ys[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, h));
				}
				settled.add(new Ring(xs, zs, ys));
			}
			working.rings = settled;
			working.reshaped();
		}
		this.keep(before, working);
	}

	/** Keeps a change the edit tool made, or says why it can't. */
	private void keep(MapRegion before, MapRegion after) {
		RegionStore store = RegionStore.get();
		if (store == null) {
			return;
		}
		if (store.readOnly()) {
			this.host.say(Component.translatable("diorama.regions.read_only"));
		} else if (store.commit(before, after)) {
			BookWidgets.play(SoundEvents.BOOK_PAGE_TURN, 1.3F);
		}
	}

	/** The corner, or failing that the side, of a region nearest a screen point, within {@link RegionTool#GRAB_REACH}. */
	private static @Nullable Hit hit(MapViewport.View view, MapRegion region, double x, double y) {
		Hit best = null;
		double reach = RegionTool.GRAB_REACH;
		for (int r = 0; r < region.rings.size(); r++) {
			Ring ring = region.rings.get(r);
			for (int i = 0; i < ring.size(); i++) {
				double d = Math.hypot(sx(view, ring, i) - x, sy(view, ring, i) - y);
				if (d <= reach) {
					reach = d;
					best = new Hit(r, i, false);
				}
			}
		}
		if (best != null) {
			return best;
		}
		reach = RegionTool.GRAB_REACH;
		for (int r = 0; r < region.rings.size(); r++) {
			Ring ring = region.rings.get(r);
			for (int i = 0; i < ring.size(); i++) {
				int j = (i + 1) % ring.size();
				double d = RegionGeometry.segmentDistance(x, y, sx(view, ring, i), sy(view, ring, i), sx(view, ring, j), sy(view, ring, j));
				if (d <= reach) {
					reach = d;
					best = new Hit(r, i, true);
				}
			}
		}
		return best;
	}

	private static float sx(MapViewport.View view, Ring ring, int i) {
		return view.screenX(Projection.u(ring.x(i), ring.z(i)));
	}

	private static float sy(MapViewport.View view, Ring ring, int i) {
		return view.screenY(Projection.worldV(ring.x(i), ring.y(i), ring.z(i)));
	}

	/** The world point {x, z}, on the half-block grid, a map point shows at height {@code y}. */
	private static float[] world(double u, double v, int y) {
		MapDirection d = Projection.direction();
		double depth = Projection.zAt(v, y);
		return new float[] {snap(d.worldX(u, depth)), snap(d.worldZ(u, depth))};
	}

	private static float snap(double blocks) {
		return Math.round(blocks / RegionGeometry.GRID) * RegionGeometry.GRID;
	}

	/** The height of the ground shown at a map point, where it is known and explored, else {@code fallback}. */
	private int ground(double u, double v, int fallback) {
		MapSession session = MapSession.get();
		BlockLooks looks = BlockLooks.get();
		int h = session == null || looks == null ? RegionGeometry.UNKNOWN : height(session.map(), looks, u, v);
		return h == RegionGeometry.UNKNOWN ? fallback : h;
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
			this.host.created(region);
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
		this.cancelStroke();
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

	/**
	 * Each frame before the map is drawn: the edit tool's drag shown on the map, and one region
	 * drawn brighter: the one the eraser points at, else the edit tool's, else {@code picked} (the
	 * one the book shows).
	 */
	public void frame(MapViewport viewport, int mouseX, int mouseY, boolean onMap, @Nullable MapRegion picked) {
		this.viewport = viewport;
		MapViewport.View view = this.host.view();
		this.pointed = this.active && tool == RegionTool.Tool.ERASER && onMap && view != null && !this.covers(mouseX, mouseY)
			? viewport.regionAt(u(view, mouseX), v(view, mouseY)) : null;
		MapRegion target = this.active && tool == RegionTool.Tool.EDIT ? this.target() : null;
		MapRegion lit = this.pointed != null ? this.pointed : target != null ? target : picked;
		viewport.highlight(lit == null ? null : lit.id);
		viewport.preview(this.working, this.previewVersion);
		RegionStore store = RegionStore.get();
		String dimension = Waypoints.dimension();
		if (this.undo != null) this.undo.active = store != null && dimension != null && store.canUndo(dimension);
		if (this.redo != null) this.redo.active = store != null && dimension != null && store.canRedo(dimension);
	}

	/**
	 * What is under way, over the map: the stroke, a line in the dye with its corners and the
	 * polygon's next side to the pointer; or the edit tool's corners of its region.
	 */
	public void draw(GuiGraphicsExtractor graphics, MapViewport.View view, int mouseX, int mouseY) {
		if (!this.active) {
			return;
		}
		if (tool == RegionTool.Tool.EDIT) {
			this.handles(graphics, view, mouseX, mouseY);
			return;
		}
		int n = this.stroke.count();
		if (n == 0) {
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
				square(graphics, xs[i], ys[i], i == 0 && closing ? 3 : 2, i == 0 && closing ? 0xFFFCE3C0 : color);
			}
		} else {
			// The lasso closes back to its start as it is let go: shown faintly.
			line(graphics, xs[n - 1], ys[n - 1], xs[0], ys[0], 0x60000000 | color & 0xFFFFFF);
		}
	}

	/**
	 * The edit tool's region's corners, little squares of paper in ink; the one under the pointer,
	 * or held, bigger and in the region's dye. Where corners crowd closer than a few pixels only
	 * some are drawn, though all can be taken.
	 */
	private void handles(GuiGraphicsExtractor graphics, MapViewport.View view, int mouseX, int mouseY) {
		MapRegion region = this.working != null ? this.working : this.target();
		if (region == null) {
			return;
		}
		Hit hover = this.working != null ? new Hit(this.grabRing, this.grabVertex, this.grabWhole) : hit(view, region, mouseX, mouseY);
		for (int r = 0; r < region.rings.size(); r++) {
			Ring ring = region.rings.get(r);
			float lastX = Float.NaN, lastY = Float.NaN;
			for (int i = 0; i < ring.size(); i++) {
				float x = sx(view, ring, i), y = sy(view, ring, i);
				boolean lit = hover != null && !hover.side() && hover.ring() == r && hover.vertex() == i;
				if (!lit && Math.hypot(x - lastX, y - lastY) < 6) {
					continue;
				}
				lastX = x;
				lastY = y;
				square(graphics, (int) Math.floor(x), (int) Math.floor(y), lit ? 3 : 1, lit ? region.dye.argb() : 0xFFFCE3C0);
			}
		}
		if (hover != null && hover.side() && this.working == null) {
			// Where a click on the side adds a corner.
			square(graphics, mouseX, mouseY, 2, 0xFFFCE3C0);
		}
	}

	/** A square of {@code fill} with an ink frame, {@code r} pixels out from (x, y). */
	private static void square(GuiGraphicsExtractor graphics, int x, int y, int r, int fill) {
		graphics.fill(x - r, y - r, x + r + 1, y + r + 1, BookSprites.INK);
		graphics.fill(x - r + 1, y - r + 1, x + r, y + r, fill);
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
		if (tool == RegionTool.Tool.EDIT) {
			MapRegion region = this.target();
			return region == null ? Component.translatable("diorama.regions.hint.edit_pick")
				: Component.translatable("diorama.regions.hint.edit", region.name);
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
		RegionTool.Tool[] tools = RegionTool.Tool.values();
		Component done = Component.translatable("diorama.regions.done");
		int doneW = BookWidgets.font().width(done) + 12;
		int w = PAD + tools.length * (SLOT + 2) - 2 + GAP + 8 * DyeSwatch.SIZE + GAP + SLOT + GAP + 2 * ARROW + 2 + GAP + doneW + PAD;
		int h = SLOT + 2 * 3;
		this.panelX = (width - w) / 2;
		this.panelY = height - h - 4;
		this.panelW = w;
		this.panelH = h;
		int x = this.panelX + PAD, y = this.panelY + 3;
		String[] icons = {"lead", "tripwire_hook", "brush", "sponge"};
		for (int i = 0; i < tools.length; i++) {
			RegionTool.Tool t = tools[i];
			String name = t.name().toLowerCase(java.util.Locale.ROOT);
			BookWidgets.Slot slot = this.host.add(new BookWidgets.Slot(x, y, SLOT, SLOT, Component.translatable("diorama.regions.tool." + name),
				BookWidgets.item(BookSprites.stack(icons[i])), () -> tool == t, () -> this.pick(t), false));
			slot.setTooltip(Tooltip.create(Component.translatable("diorama.regions.tool." + name + ".tip", i + 1)));
			x += SLOT + 2;
		}
		x += GAP - 2;
		DyeSwatch.palette(x, y + 1, () -> dye, picked -> dye = picked, this.host::add);
		x += 8 * DyeSwatch.SIZE + GAP;
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
