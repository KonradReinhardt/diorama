package dev.diorama;

import dev.diorama.bake.BlockLooks;
import dev.diorama.bake.MapPick;
import dev.diorama.bake.Overlays;
import dev.diorama.bake.TileKey;
import dev.diorama.render.MapViewport;
import dev.diorama.render.Markers;
import dev.diorama.render.Radar;
import dev.diorama.ui.BookSprites;
import dev.diorama.ui.BookWidgets;
import dev.diorama.ui.MapBook;
import dev.diorama.ui.MapMenu;
import dev.diorama.ui.OpeningZoom;
import dev.diorama.waypoint.Waypoint;
import dev.diorama.waypoint.WaypointOverlay;
import dev.diorama.waypoint.WaypointStore;
import dev.diorama.waypoint.Waypoints;
import net.minecraft.core.BlockPos;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
//? if >=1.21.9 {
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
//?}
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import com.mojang.blaze3d.platform.InputConstants;

/**
 * The fullscreen map: drag to pan, scroll to zoom, C for caves, PgUp/PgDn to move the slice,
 * right-click to plant a waypoint, click a waypoint to pick it. Its menus are a {@link MapBook}
 * over the map, opened by the ribbons at its right edge. It opens with a short zoom in.
 */
public final class MapScreen extends Screen implements MapBook.Host {
	/** How far out the saved surface map zooms; overview tiles make it cheap. */
	private static final double MIN_ZOOM = 0.04;

	private static final double MAX_ZOOM = 16;

	private final MapViewport viewport = new MapViewport();
	private double centerU;
	private double centerV;
	private double zoom = 2.0;
    private final SmoothZoom smoothZoom = new SmoothZoom(2);
    private long lastZoomFrame;
	private int layer;
	private boolean following = true;
    private MapDirection direction = Projection.direction();
	private List<Radar.Blip> blips = List.of();
	private List<WaypointOverlay.Pin> pins = List.of();
	private MapViewport.@Nullable View lastView;
	/** Where the current mouse press started, to tell a click from a drag. */
	private double pressX;
	private double pressY;
	private boolean dragged;
	/** The press started on the book, so it neither pans nor clicks the map. */
	private boolean pressOnBook;
	private final MapBook book = new MapBook(this);
	private final OpeningZoom opening = new OpeningZoom();
	/** init() has run once: later runs (a resize, coming back from a screen) don't zoom in again. */
	private boolean opened;
	/** Where "Center" on a waypoint is gliding the view to, or null. */
	private double @Nullable [] panTarget;
	private BookWidgets.@Nullable Mark help;
	/** What a right-click offers for the spot under it, or null. */
	private @Nullable MapMenu menu;
	/** A short note in parchment at the top of the map ("Copied ..."), and until when it shows. */
	private @Nullable Component note;
	private long noteUntil;
	/** The widgets are to be built again before the next frame; see {@link #rebuild}. */
	private boolean rebuildSoon;
	/** The press closed the right-click menu, so its release does nothing more. */
	private boolean pressClosedMenu;
	/** The waypoint pointed at last frame, so pointing at another one rustles once. */
	private java.util.@Nullable UUID pointedPin;
	/** Until when the controls show by themselves: the first seconds the map is open in a session. */
	private static long helpUntil;

	public MapScreen() {
		super(Component.translatable("diorama.screen.title"));
		MapSession session = MapSession.get();
		LocalPlayer player = net.minecraft.client.Minecraft.getInstance().player;
		this.layer = session != null && player != null ? session.autoLayer(player) : TileKey.SURFACE;
	}

	@Override
	protected void init() {
        this.lastZoomFrame = System.nanoTime();
		if (!this.opened) {
			this.opened = true;
			// Start on a whole pixel step, where the view rests, so the zoom in ends without a snap.
			this.zoom = this.wholeStep(this.zoom);
			this.smoothZoom.reset(this.zoom);
			this.opening.start(this.lastZoomFrame);
			if (helpUntil == 0) {
				helpUntil = System.currentTimeMillis() + 6000;
			}
		}
		this.book.build(this.width, this.height);
		this.help = this.addRenderableWidget(new BookWidgets.Mark(6, this.height - 18, BookSprites.HELP, BookSprites.HELP_HOVER,
			Component.translatable("diorama.book.help_name"), null));
		this.help.setTooltip(Tooltip.create(Component.translatable("diorama.book.help")));
		if (this.menu != null) {
			this.menu.build(this, this.width, this.height);
		}
	}

	/** The zoom drawn pixel perfect nearest {@code zoom}, as {@link MapViewport.View#pixelPerfect} snaps it. */
	private double wholeStep(double zoom) {
		double gui = this.minecraft.getWindow().getGuiScale();
		double perMapPixel = zoom * gui / Projection.PIXELS_PER_UNIT;
		return perMapPixel < 1 ? zoom : Math.round(perMapPixel) * Projection.PIXELS_PER_UNIT / gui;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		if (!ServerRules.minimapAllowed()) {
			// The server can switch maps off while this screen is open.
			this.onClose();
			return;
		}
		if (this.rebuildSoon) {
			this.rebuildSoon = false;
			this.rebuildWidgets();
		}
		MapSession session = MapSession.get();
		LocalPlayer player = this.minecraft.player;
		if (session == null || player == null) {
			return;
		}
		if (this.direction != Projection.direction()) {
            this.direction = Projection.direction();
            this.smoothZoom.reset(this.zoom);
            this.following = true;
        }
		long zoomNow = System.nanoTime();
        var zoomStep = this.smoothZoom.advance((zoomNow - this.lastZoomFrame) / 1e9, this.centerU, this.centerV, this.minZoom());
        double seconds = (zoomNow - this.lastZoomFrame) / 1e9;
        this.lastZoomFrame = zoomNow;
        this.zoom = zoomStep.zoom();
        this.centerU = zoomStep.centerU();
        this.centerV = zoomStep.centerV();
		if (this.panTarget != null) {
			double t = 1 - Math.exp(-Math.max(0, Math.min(0.1, seconds)) / 0.08);
			this.centerU += (this.panTarget[0] - this.centerU) * t;
			this.centerV += (this.panTarget[1] - this.centerV) * t;
			if (Math.abs(this.panTarget[0] - this.centerU) * this.zoom < 0.25 && Math.abs(this.panTarget[1] - this.centerV) * this.zoom < 0.25) {
				this.centerU = this.panTarget[0];
				this.centerV = this.panTarget[1];
				this.panTarget = null;
			}
		}
		Vec3 pos = player.getPosition(a);
		double playerU = Projection.u(pos.x, pos.z);
		double playerV = Projection.worldV(pos.x, pos.y, pos.z);
		if (this.following) {
			this.centerU = playerU;
			this.centerV = playerV;
		}
		// Switching to a cave slice while zoomed far out comes back to the closest zoom it can show.
		this.zoom = Math.max(this.minZoom(), this.zoom);
		// Drawn pixel perfect once the zoom comes to rest; while it eases (the wheel, or the zoom in as
		// the map opens) it is drawn as it is, so it glides instead of stepping. The smooth zoom and pan
		// keep their own exact values, and the opening only scales the zoom drawn.
		double drawnZoom = Math.max(this.minZoom(), this.zoom * this.opening.factor(zoomNow));
		MapViewport.View view = new MapViewport.View(0, 0, this.width, this.height, this.centerU, this.centerV, drawnZoom, this.layer);
		if (!this.smoothZoom.moving() && !this.opening.running(zoomNow) && this.panTarget == null) view = view.pixelPerfect();
		this.viewport.draw(graphics, session.map(), view, overlayBits(session));
		Radar.Blip hovered = null;
		this.blips = List.of();
		// Nothing on the map under the book or a ribbon is pointed at.
		boolean onMap = !this.book.covers(mouseX, mouseY) && !this.book.onRibbon(mouseX, mouseY);
		if (ServerRules.radarAllowed()) {
			this.blips = Radar.collect(view, a, true);
			Radar.draw(graphics, view, this.blips, true, false);
			hovered = onMap ? Radar.under(this.blips, mouseX, mouseY) : null;
		}
		WaypointStore store = WaypointStore.get();
		this.pins = store == null || !Settings.get().waypointsOnMap ? List.of() : WaypointOverlay.layout(view, store.in(this.minecraft.level.dimension().identifier().toString()), true);
		WaypointOverlay.Pin hoveredPin = onMap ? WaypointOverlay.under(this.pins, mouseX, mouseY) : null;
		java.util.UUID pointed = hoveredPin != null ? hoveredPin.waypoint().id : null;
		if (pointed != null && !pointed.equals(this.pointedPin)) {
			BookWidgets.rustle();
		}
		this.pointedPin = pointed;
		WaypointOverlay.draw(graphics, view, this.pins, true, hoveredPin);
		Radar.draw(graphics, view, this.blips, true, true);
		Markers.player(graphics, view.screenX(playerU), view.screenY(playerV), player.getViewYRot(a), 2);
		this.lastView = view;
		// The tooltip is for whatever is drawn on top: a player, then a waypoint, then a mob.
		if (hovered != null && hovered.kind() == Radar.Kind.PLAYER) {
			graphics.setTooltipForNextFrame(this.font, Radar.describe(hovered, player), mouseX, mouseY);
		} else if (hoveredPin != null) {
			graphics.setTooltipForNextFrame(this.font, WaypointOverlay.describe(hoveredPin.waypoint(), player), mouseX, mouseY);
		} else if (hovered != null) {
			graphics.setTooltipForNextFrame(this.font, Radar.describe(hovered, player), mouseX, mouseY);
		}

		if (!this.book.isOpen()) {
			this.status(graphics, session, player);
			if (System.currentTimeMillis() < helpUntil) {
				this.helpCard(graphics);
			}
		}
		if (this.help != null) {
			this.help.visible = !this.book.isOpen();
		}
		if (this.note != null && System.currentTimeMillis() < this.noteUntil) {
			int w = this.font.width(this.note) + 12;
			BookSprites.nine(graphics, BookSprites.LABEL, (this.width - w) / 2, 4, w, 16, -1, true);
			graphics.text(this.font, this.note, (this.width - w) / 2 + 6, 8, BookSprites.INK, false);
		}
		this.book.frame(zoomNow);
		// The book and its widgets draw over the map and its labels.
		BookSprites.above(graphics);
		this.book.drawBehind(graphics);
		super.extractRenderState(graphics, mouseX, mouseY, a);
	}

	/** Where the player is and what the map shows, on a slip of parchment in the top left corner. */
	private void status(GuiGraphicsExtractor graphics, MapSession session, LocalPlayer player) {
		Component mode = this.layer != TileKey.SURFACE
			? Component.translatable("diorama.book.status.cave", this.layer)
			: Component.translatable(session.map().roofless() ? "diorama.book.status.roofless" : "diorama.book.status.surface");
		net.minecraft.network.chat.MutableComponent status = Component.empty().append(mode)
			.append("  ·  " + player.getBlockX() + ", " + player.getBlockY() + ", " + player.getBlockZ() + "  ·  ")
			.append(Component.translatable("diorama.book.status.chunks", session.map().chunkCount()));
		if (session.map().bakingCount() > 0 || session.map().refreshingAppearance()) {
			status.append("  ·  ").append(Component.translatable("diorama.book.status.baking"));
		}
		Component shown = BookWidgets.fit(status, this.width - 60);
		BookSprites.nine(graphics, BookSprites.LABEL, 4, 4, this.font.width(shown) + 12, 16, -1, true);
		graphics.text(this.font, shown, 10, 8, BookSprites.INK, false);
	}

	/** The controls, beside the help seal, for the first seconds the map is open. */
	private void helpCard(GuiGraphicsExtractor graphics) {
		List<net.minecraft.util.FormattedCharSequence> lines = this.font.split(Component.translatable("diorama.book.help"), 220);
		int w = 0;
		for (var line : lines) {
			w = Math.max(w, this.font.width(line));
		}
		int h = lines.size() * 10 + 8;
		int x = 22, y = this.height - 6 - h;
		BookSprites.nine(graphics, BookSprites.LABEL, x, y, w + 12, h, -1, true);
		for (int i = 0; i < lines.size(); i++) {
			graphics.text(this.font, lines.get(i), x + 6, y + 5 + i * 10, BookSprites.INK, false);
		}
	}

	// Mouse and key events came as separate numbers before 1.21.9. Each override hands them to a
	// method below that both versions share.
	//? if >=1.21.9 {
	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		return this.clicked(super.mouseClicked(event, doubleClick), event.x(), event.y(), event.button());
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		return this.released(event.x(), event.y(), event.button()) || super.mouseReleased(event);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		return this.dragged(event.x(), event.y(), event.button(), dx, dy) || super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		return this.keyDown(event.key(), DioramaClient.WAYPOINT_LIST.matches(event), DioramaClient.OPEN_MAP.matches(event))
			|| super.keyPressed(event);
	}
	//?} else {
	/*@Override
	public boolean mouseClicked(double x, double y, int button) {
		return this.clicked(super.mouseClicked(x, y, button), x, y, button);
	}

	@Override
	public boolean mouseReleased(double x, double y, int button) {
		return this.released(x, y, button) || super.mouseReleased(x, y, button);
	}

	@Override
	public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
		return this.dragged(x, y, button, dx, dy) || super.mouseDragged(x, y, button, dx, dy);
	}

	@Override
	public boolean keyPressed(int key, int scancode, int modifiers) {
		return this.keyDown(key, DioramaClient.WAYPOINT_LIST.matches(key, scancode), DioramaClient.OPEN_MAP.matches(key, scancode))
			|| super.keyPressed(key, scancode, modifiers);
	}
	*///?}

	/** A press; {@code widgetTook} if a widget (the settings button) took it. */
	private boolean clicked(boolean widgetTook, double x, double y, int button) {
		if (widgetTook) {
			this.dragged = true;
			return true;
		}
		// A click away from the right-click menu shuts it; a right-click opens it again where it lands.
		this.pressClosedMenu = this.menu != null && button != InputConstants.MOUSE_BUTTON_RIGHT;
		this.closeMenu();
		this.pressOnBook = this.book.covers(x, y);
		if (!this.pressOnBook) {
			// A click on the map leaves the book's fields.
			this.setFocused(null);
		}
		this.pressX = x;
		this.pressY = y;
		this.dragged = false;
		return true;
	}

	/** A release; false leaves it to the screen's widgets. */
	private boolean released(double x, double y, int button) {
		if (this.pressOnBook) {
			this.pressOnBook = false;
			return false;
		}
		if (this.pressClosedMenu) {
			this.pressClosedMenu = false;
			return true;
		}
		if (this.dragged || Math.hypot(x - this.pressX, y - this.pressY) > 3) {
			return true;
		}
		if (button == InputConstants.MOUSE_BUTTON_LEFT) {
			WaypointOverlay.Pin pin = WaypointOverlay.under(this.pins, x, y);
			if (pin != null) {
				this.book.showWaypoint(pin.waypoint());
			}
			return true;
		}
		if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
			this.openMenu(x, y);
			return true;
		}
		return false;
	}

	/**
	 * The block under a screen point: where the map knows the ground, the one it draws there;
	 * elsewhere the column under the point at the player's height, with the height unknown.
	 */
	private MapMenu.@Nullable Target target(double screenX, double screenY) {
		MapSession session = MapSession.get();
		BlockLooks looks = BlockLooks.get();
		MapViewport.View view = this.lastView;
		LocalPlayer player = this.minecraft.player;
		if (session == null || looks == null || view == null || player == null) {
			return null;
		}
		double u = view.centerU() + (screenX - view.x() - view.width() / 2.0) / view.zoom();
		double v = view.centerV() + (screenY - view.y() - view.height() / 2.0) / view.zoom();
		BlockPos spot = MapPick.standingSpot(session.map(), looks, u, v, this.layer, session.map().fogOfWar());
		if (spot != null) {
			return new MapMenu.Target(spot.getX(), spot.getY(), spot.getZ(), true);
		}
		// Undo the projection at a guessed height: the column is right, the height may not be.
		int y = this.layer != TileKey.SURFACE ? this.layer : player.getBlockY();
		double depth = (v + y * Projection.yScale()) / Projection.zScale();
		MapDirection d = Projection.direction();
		return new MapMenu.Target((int) Math.floor(d.worldX(u, depth)), y, (int) Math.floor(d.worldZ(u, depth)), false);
	}

	/** Opens the right-click menu for the spot under a screen point. */
	public void openMenu(double screenX, double screenY) {
		MapMenu.Target target = this.target(screenX, screenY);
		if (target == null) {
			return;
		}
		List<MapMenu.Entry> entries = new java.util.ArrayList<>();
		entries.add(new MapMenu.Entry(Component.translatable("diorama.menu.plant"), MapBook.BANNER_ICON, () -> {
			this.closeMenu();
			Waypoint waypoint = Waypoints.at(new BlockPos(target.x(), target.y(), target.z()));
			if (waypoint != null) {
				this.book.newWaypoint(waypoint);
			}
		}));
		entries.add(new MapMenu.Entry(Component.translatable("diorama.menu.copy"), BookWidgets.item(BookSprites.stack("paper")), () -> {
			this.minecraft.keyboardHandler.setClipboard(target.command());
			this.say(Component.translatable("diorama.menu.copied", target.command()));
			this.closeMenu();
		}));
		MapSession session = MapSession.get();
		boolean roofed = session != null && session.map().roof() != dev.diorama.bake.ChunkSource.NO_ROOF;
		// Unknown ground is reached by spreadplayers, which lands on the surface; under a roof that
		// surface is the roof, so there it isn't offered.
		boolean canGo = target.heightKnown()
			? dev.diorama.waypoint.WaypointEditScreen.canTeleport()
			: !roofed && dev.diorama.waypoint.WaypointEditScreen.commandAllowed("spreadplayers");
		if (canGo) {
			entries.add(new MapMenu.Entry(Component.translatable("diorama.menu.teleport"), BookWidgets.item(BookSprites.stack("ender_pearl")), () -> {
				LocalPlayer player = this.minecraft.player;
				if (player != null) {
					player.connection.sendCommand(target.heightKnown()
						? "tp @s " + target.x() + " " + target.y() + " " + target.z()
						: "spreadplayers " + (target.x() + 0.5) + " " + (target.z() + 0.5) + " 0 1 false @s");
				}
				this.say(Component.translatable("diorama.menu.teleporting"));
				this.smoothZoom.reset(this.zoom);
				this.panTarget = null;
				this.following = true;
				this.closeMenu();
			}));
		}
		this.menu = new MapMenu(target, entries, (int) screenX, (int) screenY);
		this.rebuild();
	}

	private void closeMenu() {
		if (this.menu != null) {
			this.menu = null;
			this.rebuild();
		}
	}

	/** A short note at the top of the map for two seconds. */
	private void say(Component text) {
		this.note = text;
		this.noteUntil = System.currentTimeMillis() + 2000;
	}

	/** For tests: the right-click menu open now, or null. */
	public @Nullable MapMenu menu() {
		return this.menu;
	}

	/** Plants a new waypoint on the block shown under a screen point, to name in the book. */
	public void addWaypointAt(double screenX, double screenY) {
		MapSession session = MapSession.get();
		BlockLooks looks = BlockLooks.get();
		MapViewport.View view = this.lastView;
		if (session == null || looks == null || view == null) {
			return;
		}
		double u = view.centerU() + (screenX - view.x() - view.width() / 2.0) / view.zoom();
		double v = view.centerV() + (screenY - view.y() - view.height() / 2.0) / view.zoom();
		BlockPos spot = MapPick.standingSpot(session.map(), looks, u, v, this.layer, session.map().fogOfWar());
		if (spot == null) {
			return;
		}
		Waypoint waypoint = Waypoints.at(spot);
		if (waypoint != null) {
			this.book.newWaypoint(waypoint);
		}
	}

	/** A drag; false leaves it to the screen's widgets. */
	private boolean dragged(double x, double y, int button, double dx, double dy) {
		if (this.pressOnBook || this.pressClosedMenu) {
			return false;
		}
		if (Math.hypot(x - this.pressX, y - this.pressY) > 3) {
			this.dragged = true;
		}
		if (button == InputConstants.MOUSE_BUTTON_LEFT && this.dragged) {
            this.smoothZoom.reset(this.zoom);
			this.opening.cancel();
			this.panTarget = null;
			this.centerU -= dx / this.zoom;
			this.centerV -= dy / this.zoom;
			this.following = false;
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		if (this.book.covers(x, y)) {
			return true;
		}
		this.closeMenu();
		this.opening.cancel();
        // GUI pixels per map unit at which a map pixel covers one screen pixel: the pixel-perfect steps start there.
        double pixelZoom = Projection.PIXELS_PER_UNIT / this.minecraft.getWindow().getGuiScale();
        this.smoothZoom.scroll(scrollY, x - this.width / 2.0, y - this.height / 2.0, this.minZoom(), MAX_ZOOM, pixelZoom);
        if (this.smoothZoom.target() != this.zoom) this.following = false;
		return true;
	}

	/** A key press, and whether it is bound to the waypoint list or the map; false leaves it to the screen. */
	private boolean keyDown(int key, boolean waypointListKey, boolean mapKey) {
		// Escape shuts the right-click menu first, then the book, and the map only once both are shut.
		if (key == InputConstants.KEY_ESCAPE && this.menu != null) {
			this.closeMenu();
			return true;
		}
		if (key == InputConstants.KEY_ESCAPE && this.book.isOpen()) {
			this.setFocused(null);
			this.book.close();
			return true;
		}
		// While a name or a coordinate is being typed, letters are letters.
		if (this.getFocused() instanceof EditBox box && box.isFocused()) {
			return false;
		}
		// The player's own bindings come before the map's fixed keys, so Open map rebound to C still closes it.
		if (waypointListKey) {
			this.book.show(MapBook.Section.BANNERS, 0);
			return true;
		}
		if (mapKey) {
			this.onClose();
			return true;
		}
		LocalPlayer player = this.minecraft.player;
		switch (key) {
			case InputConstants.KEY_C -> {
				this.toggleCaves();
				return true;
			}
			case InputConstants.KEY_PAGEUP -> {
				this.shiftSlice(4, player);
				return true;
			}
			case InputConstants.KEY_PAGEDOWN -> {
				this.shiftSlice(-4, player);
				return true;
			}
			case InputConstants.KEY_SPACE -> {
				// Space presses a focused button of the book instead.
				if (this.getFocused() instanceof AbstractWidget widget && widget.isFocused()) {
					return false;
				}
                this.smoothZoom.reset(this.zoom);
				this.panTarget = null;
				this.following = true;
				return true;
			}
			default -> {
			}
		}
		return false;
	}

	private void shiftSlice(int delta, LocalPlayer player) {
		if (this.layer == TileKey.SURFACE) {
			if (player == null) {
				return;
			}
			this.layer = MapSession.caveLayerFor(player.getY());
		}
		this.layer = Math.max(this.minecraft.level.getMinY() + 4, Math.min(this.minecraft.level.getMaxY() + 1, this.layer + delta));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void removed() {
		this.book.removed();
		this.viewport.close();
	}

	@Override
	public <T extends GuiEventListener & Renderable & NarratableEntry> T add(T widget) {
		return this.addRenderableWidget(widget);
	}

	/**
	 * Builds the widgets again before the next frame. Clicks change what the book shows, and the
	 * screen goes on handling a click (focusing what was clicked) after its widget has acted, so the
	 * widgets aren't swapped from under it.
	 */
	@Override
	public void rebuild() {
		this.rebuildSoon = true;
	}

	@Override
	public Screen screen() {
		return this;
	}

	@Override
	public void focus(GuiEventListener widget) {
		this.setFocused(widget);
	}

	@Override
	public boolean caves() {
		return this.layer != TileKey.SURFACE;
	}

	@Override
	public int layer() {
		return this.layer;
	}

	@Override
	public void toggleCaves() {
		LocalPlayer player = this.minecraft.player;
		this.layer = this.layer == TileKey.SURFACE && player != null ? MapSession.caveLayerFor(player.getY()) : TileKey.SURFACE;
	}

	@Override
	public void shiftSlice(int delta) {
		this.shiftSlice(delta, this.minecraft.player);
	}

	/** Glides the view to a waypoint and stops following the player. */
	@Override
	public void centerOn(Waypoint waypoint) {
		this.following = false;
		this.smoothZoom.reset(this.zoom);
		this.panTarget = new double[] {
			Projection.u(waypoint.x + 0.5, waypoint.z + 0.5),
			Projection.worldV(waypoint.x + 0.5, waypoint.y, waypoint.z + 0.5)
		};
	}

	/** For tests: the book over the map. */
	public MapBook book() {
		return this.book;
	}

	/** For tests: whether the zoom in of opening is still running. */
	public boolean opening() {
		return this.opening.running(System.nanoTime());
	}

	/** For tests: the view drawn last frame. */
	public MapViewport.@Nullable View view() {
		return this.lastView;
	}

	/** For tests: the view the map holds, with its exact zoom and centre, before it is snapped for drawing. */
	public MapViewport.View heldView() {
		return new MapViewport.View(0, 0, this.width, this.height, this.centerU, this.centerV, this.zoom, this.layer);
	}

	/** For tests: the zoom the wheel is easing toward. */
	public double zoomTarget() {
		return this.smoothZoom.target();
	}

	/** For tests: the waypoint pins drawn last frame, in GUI coordinates. */
	public List<WaypointOverlay.Pin> pins() {
		return this.pins;
	}

	/** For tests: the radar markers drawn last frame, in GUI coordinates. */
	public List<Radar.Blip> blips() {
		return this.blips;
	}

	/** The {@link Overlays} bits to draw, from the settings. */
	private static int overlayBits(MapSession session) {
		Settings s = Settings.get();
		int bits = 0;
		if (s.chunkGrid) {
			bits |= Overlays.CHUNK_WEST | Overlays.CHUNK_NORTH;
		}
		if (s.slimeChunks && session.slimeSeed() != null) {
			bits |= Overlays.SLIME;
		}
		if (s.spawnSpots != Settings.SpawnSpots.OFF) {
			bits |= Overlays.SPAWN_ANY_TIME;
		}
		if (s.spawnSpots == Settings.SpawnSpots.AT_NIGHT_TOO) {
			bits |= Overlays.SPAWN_AT_NIGHT;
		}
		return bits;
	}

	/** Cave slices and unsaved maps have no overview tiles, so they stop at the detailed zoom. */
	private double minZoom() {
		MapSession session = MapSession.get();
		boolean overviews = this.layer == TileKey.SURFACE && session != null && session.map().hasOverviews();
		return overviews ? MIN_ZOOM : MapViewport.DETAILED_ZOOM;
	}

	/** For tests: put the view at a spot without following the player. */
	public void lookAt(double u, double v, double zoom, int layer) {
		this.opening.cancel();
		this.panTarget = null;
		this.centerU = u;
		this.centerV = v;
		this.zoom = zoom;
        this.smoothZoom.reset(zoom);
		this.layer = layer;
		this.following = false;
	}
}
