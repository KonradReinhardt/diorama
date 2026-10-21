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
import dev.diorama.ui.RegionMode;
import dev.diorama.region.MapRegion;
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
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
//?}
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import com.mojang.blaze3d.platform.InputConstants;

/**
 * The fullscreen map: drag to pan, scroll to zoom, C for caves, PgUp/PgDn to move the slice,
 * right-click to plant a waypoint, click a waypoint to pick it. Its menus are a {@link MapBook}
 * over the map, opened by the ribbons at its right edge. It opens with a short zoom in. In
 * {@link RegionMode} the left button draws regions instead, and the map pans with the others.
 */
public final class MapScreen extends Screen implements RegionMode.Host {
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
	/** The left button is down and dragging the map. */
	private boolean panning;
	/** The press started on the book, so it neither pans nor clicks the map. */
	private boolean pressOnBook;
	private final MapBook book = new MapBook(this);
	/** Drawing regions, while it is on. */
	private final RegionMode regions = new RegionMode(this);
	/** The book opened to name a region just drawn: shutting it goes back to drawing. */
	private boolean backToRegions;
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
	/** The chunk a region to reveal starts at, while the player picks its other corner; see {@link RegionReveal}. */
	private int @Nullable [] revealCorner;

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
			BookWidgets.pageTurn(this.minecraft.getSoundManager());
			if (helpUntil == 0) {
				helpUntil = System.currentTimeMillis() + 6000;
			}
		}
		this.book.build(this.width, this.height);
		this.regions.build(this.width, this.height);
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
		// Regions are drawn on the surface, with the book shut.
		if (this.backToRegions && !this.book.isOpen()) {
			this.backToRegions = false;
			this.regions.enter();
		}
		if (this.regions.active() && (this.book.isOpen() || this.layer != TileKey.SURFACE)) {
			this.regions.leave();
		}
		// Nothing on the map under the book, a ribbon or the region tools is pointed at.
		boolean onMap = !this.book.covers(mouseX, mouseY) && !this.book.onRibbon(mouseX, mouseY) && !this.regions.covers(mouseX, mouseY);
		this.regions.frame(this.viewport, mouseX, mouseY, onMap,
			this.book.isOpen() && this.book.section() == MapBook.Section.REGIONS ? this.book.pickedRegion() : null);
		this.viewport.draw(graphics, session.map(), view, overlayBits(session));
		this.drawReveal(graphics, view, player, mouseX, mouseY);
		if (Settings.get().regionLabels) {
			dev.diorama.render.RegionLabels.draw(graphics, view, this.viewport.shownRegions(), Settings.get());
		}
		this.regions.draw(graphics, view, mouseX, mouseY);
		Radar.Blip hovered = null;
		this.blips = List.of();
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
		this.pointer(graphics, onMap, hoveredPin != null);
		// The tooltip is for whatever is drawn on top: a player, then a waypoint, then a mob.
		if (hovered != null && hovered.kind() == Radar.Kind.PLAYER) {
			graphics.setTooltipForNextFrame(this.font, Radar.describe(hovered, player), mouseX, mouseY);
		} else if (hoveredPin != null) {
			graphics.setTooltipForNextFrame(this.font, WaypointOverlay.describe(hoveredPin.waypoint(), player), mouseX, mouseY);
		} else if (hovered != null) {
			graphics.setTooltipForNextFrame(this.font, Radar.describe(hovered, player), mouseX, mouseY);
		} else if (onMap && !this.regions.active() && !this.book.isOpen()) {
			MapRegion region = this.regionUnder(mouseX, mouseY);
			if (region != null) {
				graphics.setTooltipForNextFrame(this.font, Component.literal(region.name), mouseX, mouseY);
			}
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
		RegionReveal.Outcome outcome = RegionReveal.takeOutcome();
		if (outcome != null) {
			if (outcome == RegionReveal.Outcome.DONE) {
				BookWidgets.play(SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, 1.0F);
			}
			this.say(Component.translatable("diorama.reveal." + outcome.name().toLowerCase(java.util.Locale.ROOT)));
		}
		Component shownNote = this.revealCorner != null ? this.revealHint(mouseX, mouseY)
			: this.note != null && System.currentTimeMillis() < this.noteUntil ? this.note
			: this.regions.active() ? this.regions.hint() : null;
		if (shownNote != null) {
			// Under the status slip, which can reach the middle of a narrow screen.
			int w = this.font.width(shownNote) + 12;
			BookSprites.nine(graphics, BookSprites.LABEL, (this.width - w) / 2, 22, w, 16, -1, true);
			graphics.text(this.font, shownNote, (this.width - w) / 2 + 6, 26, BookSprites.INK, false);
		}
		this.book.frame(zoomNow);
		// The book and its widgets draw over the map and its labels.
		BookSprites.above(graphics);
		this.book.drawBehind(graphics);
		this.regions.drawPanel(graphics);
		super.extractRenderState(graphics, mouseX, mouseY, a);
	}

	/**
	 * The pointer over the map, as the book's controls set their own: a cross of arrows while the map
	 * is dragged, a crosshair while a region's other corner is picked, a hand over a banner.
	 */
	private void pointer(GuiGraphicsExtractor graphics, boolean onMap, boolean overPin) {
		//? if >=1.21.9 {
		if (this.panning) {
			graphics.requestCursor(CursorTypes.RESIZE_ALL);
		} else if (onMap && (this.revealCorner != null || this.regions.active())) {
			graphics.requestCursor(CursorTypes.CROSSHAIR);
		} else if (onMap && overPin) {
			graphics.requestCursor(CursorTypes.POINTING_HAND);
		}
		//?}
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
		float revealed = RegionReveal.progress();
		if (revealed >= 0) {
			status.append("  ·  ").append(Component.translatable("diorama.book.status.revealing", Math.round(revealed * 100)));
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
		this.modifier(event.key(), true);
		return this.keyDown(event.key(), event.modifiers(), DioramaClient.WAYPOINT_LIST.matches(event), DioramaClient.OPEN_MAP.matches(event),
			DioramaClient.REGIONS.matches(event)) || super.keyPressed(event);
	}

	@Override
	public boolean keyReleased(KeyEvent event) {
		this.modifier(event.key(), false);
		return super.keyReleased(event);
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
		this.modifier(key, true);
		return this.keyDown(key, modifiers, DioramaClient.WAYPOINT_LIST.matches(key, scancode), DioramaClient.OPEN_MAP.matches(key, scancode),
			DioramaClient.REGIONS.matches(key, scancode)) || super.keyPressed(key, scancode, modifiers);
	}

	@Override
	public boolean keyReleased(int key, int scancode, int modifiers) {
		this.modifier(key, false);
		return super.keyReleased(key, scancode, modifiers);
	}
	*///?}

	/** Ctrl and Shift as this screen saw them pressed, beside the event's own modifier bits. */
	private boolean control, shift;

	private void modifier(int key, boolean down) {
		if (key == InputConstants.KEY_LCONTROL || key == InputConstants.KEY_RCONTROL) {
			this.control = down;
		} else if (key == InputConstants.KEY_LSHIFT || key == InputConstants.KEY_RSHIFT) {
			this.shift = down;
		}
	}

	/** A press; {@code widgetTook} if a widget (the settings button) took it. */
	private boolean clicked(boolean widgetTook, double x, double y, int button) {
		if (widgetTook) {
			this.dragged = true;
			return true;
		}
		// A click away from the right-click menu shuts it; a right-click opens it again where it lands.
		this.pressClosedMenu = this.menu != null && button != InputConstants.MOUSE_BUTTON_RIGHT;
		this.closeMenu();
		this.pressOnBook = this.book.covers(x, y) || this.regions.covers(x, y);
		if (!this.pressOnBook) {
			// A click on the map leaves the book's fields.
			this.setFocused(null);
		}
		this.pressX = x;
		this.pressY = y;
		this.dragged = false;
		this.panning = false;
		if (!this.pressOnBook && this.regions.active() && button == InputConstants.MOUSE_BUTTON_LEFT) {
			this.regions.press(x, y);
		}
		return true;
	}

	/** A release; false leaves it to the screen's widgets. */
	private boolean released(double x, double y, int button) {
		this.panning = false;
		if (this.pressOnBook) {
			this.pressOnBook = false;
			return false;
		}
		if (this.pressClosedMenu) {
			this.pressClosedMenu = false;
			return true;
		}
		boolean moved = this.dragged || Math.hypot(x - this.pressX, y - this.pressY) > 3;
		if (this.regions.active()) {
			// The left button draws; a right click lets the stroke under way go.
			if (button == InputConstants.MOUSE_BUTTON_LEFT) {
				this.regions.release(x, y, moved);
				return true;
			}
			if (button == InputConstants.MOUSE_BUTTON_RIGHT && !moved && this.regions.drawing()) {
				this.regions.cancelStroke();
				return true;
			}
			if (button == InputConstants.MOUSE_BUTTON_RIGHT && !moved && this.regions.rightClick(x, y)) {
				return true;
			}
		}
		if (moved) {
			return true;
		}
		if (this.revealCorner != null) {
			// A left click sets the other corner and starts; a right click lets the region go.
			RegionReveal.Region picked = button == InputConstants.MOUSE_BUTTON_LEFT ? this.pickedRegion(x, y) : null;
			this.revealCorner = null;
			if (picked != null) {
				boolean started = RegionReveal.start(picked);
				if (started) {
					BookWidgets.play(SoundEvents.SPYGLASS_USE, 1.0F);
				}
				this.say(Component.translatable(started ? "diorama.reveal.started" : "diorama.reveal.denied"));
			}
			return true;
		}
		if (button == InputConstants.MOUSE_BUTTON_LEFT) {
			WaypointOverlay.Pin pin = WaypointOverlay.under(this.pins, x, y);
			if (pin != null) {
				BookWidgets.pageTurn(this.minecraft.getSoundManager());
				this.book.showWaypoint(pin.waypoint());
				return true;
			}
			MapRegion region = this.regionUnder(x, y);
			if (region != null) {
				BookWidgets.pageTurn(this.minecraft.getSoundManager());
				this.book.showRegion(region);
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
		int[] column = this.column(screenX, screenY);
		return column == null ? null : new MapMenu.Target(column[0], this.guessedHeight(player), column[1], false);
	}

	/** The height unknown ground is taken at: the slice shown, or the player's own on the surface. */
	private int guessedHeight(LocalPlayer player) {
		return this.layer != TileKey.SURFACE ? this.layer : player.getBlockY();
	}

	/**
	 * The block column {x, z} under a screen point, undoing the projection at {@link #guessedHeight}:
	 * the column is right where the ground is that high, and off by a little where it isn't.
	 */
	private int @Nullable [] column(double screenX, double screenY) {
		MapViewport.View view = this.lastView;
		LocalPlayer player = this.minecraft.player;
		if (view == null || player == null) {
			return null;
		}
		double u = view.centerU() + (screenX - view.x() - view.width() / 2.0) / view.zoom();
		double v = view.centerV() + (screenY - view.y() - view.height() / 2.0) / view.zoom();
		double depth = (v + this.guessedHeight(player) * Projection.yScale()) / Projection.zScale();
		MapDirection d = Projection.direction();
		return new int[] {(int) Math.floor(d.worldX(u, depth)), (int) Math.floor(d.worldZ(u, depth))};
	}

	/**
	 * The region being picked, from its first corner to the chunk under the cursor, and the one
	 * being revealed: outlined in ink at the guessed height, over a light wash.
	 */
	private void drawReveal(GuiGraphicsExtractor graphics, MapViewport.View view, LocalPlayer player, int mouseX, int mouseY) {
		RegionReveal.Region running = RegionReveal.region();
		if (running != null) {
			this.outline(graphics, view, player, running, 0x30000000 | (BookSprites.INK & 0xFFFFFF));
		}
		RegionReveal.Region picked = this.pickedRegion(mouseX, mouseY);
		if (picked != null) {
			this.outline(graphics, view, player, picked, 0x60FCE3C0);
		}
	}

	/** The region from the first corner picked to the chunk under a screen point, or null when none is being picked. */
	private RegionReveal.@Nullable Region pickedRegion(double screenX, double screenY) {
		int[] corner = this.revealCorner;
		int[] cursor = corner == null ? null : this.column(screenX, screenY);
		return cursor == null ? null : RegionReveal.Region.between(corner[0], corner[1], cursor[0] >> 4, cursor[1] >> 4);
	}

	private void outline(GuiGraphicsExtractor graphics, MapViewport.View view, LocalPlayer player, RegionReveal.Region region, int wash) {
		int y = this.guessedHeight(player);
		double x0 = region.minX() << 4, z0 = region.minZ() << 4, x1 = (region.maxX() + 1) << 4, z1 = (region.maxZ() + 1) << 4;
		// Every camera faces a cardinal direction, so the region's square stays a rectangle on screen.
		float ax = view.screenX(Projection.u(x0, z0)), bx = view.screenX(Projection.u(x1, z1));
		float ay = view.screenY(Projection.worldV(x0, y, z0)), by = view.screenY(Projection.worldV(x1, y, z1));
		int l = Math.round(Math.min(ax, bx)), t = Math.round(Math.min(ay, by));
		int r = Math.max(l + 2, Math.round(Math.max(ax, bx))), b = Math.max(t + 2, Math.round(Math.max(ay, by)));
		graphics.fill(l, t, r, b, wash);
		graphics.fill(l, t, r, t + 1, BookSprites.INK);
		graphics.fill(l, b - 1, r, b, BookSprites.INK);
		graphics.fill(l, t, l + 1, b, BookSprites.INK);
		graphics.fill(r - 1, t, r, b, BookSprites.INK);
	}

	/** What the slip at the top says while the other corner is picked: the region's size, and how to let it go. */
	private Component revealHint(int mouseX, int mouseY) {
		RegionReveal.Region picked = this.pickedRegion(mouseX, mouseY);
		return Component.translatable("diorama.reveal.pick", picked == null ? 1 : picked.width(), picked == null ? 1 : picked.depth());
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
		// Unexplored land drawn from the world itself, for creative and cheats in singleplayer.
		if (RegionReveal.region() != null) {
			entries.add(new MapMenu.Entry(Component.translatable("diorama.menu.reveal_stop"), BookWidgets.item(BookSprites.stack("barrier")), () -> {
				RegionReveal.cancel();
				this.closeMenu();
			}));
		} else if (RegionReveal.allowed(session)) {
			int[] corner = this.column(screenX, screenY);
			if (corner != null) {
				entries.add(new MapMenu.Entry(Component.translatable("diorama.menu.reveal"), BookWidgets.item(BookSprites.stack("spyglass")), () -> {
					this.revealCorner = new int[] {corner[0] >> 4, corner[1] >> 4};
					this.closeMenu();
				}));
			}
		}
		if (this.layer == TileKey.SURFACE && !this.regions.active()) {
			entries.add(new MapMenu.Entry(Component.translatable("diorama.menu.draw_region"), BookWidgets.item(BookSprites.stack("lead")), () -> {
				this.closeMenu();
				this.startRegions();
			}));
		}
		this.menu = new MapMenu(target, entries, (int) screenX, (int) screenY);
		BookWidgets.card(this.minecraft.getSoundManager());
		this.rebuild();
	}

	/** For tests: starts picking a region to reveal at a screen point, as the right-click menu's entry does. */
	public void pickRevealFrom(double screenX, double screenY) {
		int[] corner = this.column(screenX, screenY);
		if (corner != null && RegionReveal.allowed(MapSession.get())) {
			this.revealCorner = new int[] {corner[0] >> 4, corner[1] >> 4};
		}
	}

	/** For tests: whether the map's fog is easing away from ground it just discovered. */
	public boolean fogEasing() {
		return this.viewport.easingFog();
	}

	/** For tests: whether the other corner of a region to reveal is being picked. */
	public boolean pickingReveal() {
		return this.revealCorner != null;
	}

	private void closeMenu() {
		if (this.menu != null) {
			this.menu = null;
			this.rebuild();
		}
	}

	/** A short note at the top of the map for two seconds. */
	@Override
	public void say(Component text) {
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
		if (this.regions.active() && button == InputConstants.MOUSE_BUTTON_LEFT) {
			this.regions.drag(x, y);
			return true;
		}
		// Drawing regions, the map pans with the right or middle button instead of the left.
		boolean panButton = this.regions.active()
			? button == InputConstants.MOUSE_BUTTON_RIGHT || button == InputConstants.MOUSE_BUTTON_MIDDLE
			: button == InputConstants.MOUSE_BUTTON_LEFT;
		if (panButton && this.dragged) {
			this.panning = true;
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

	/**
	 * A key press with GLFW's modifier bits, and whether it is bound to the waypoint list, the map
	 * or the region tools; false leaves it to the screen.
	 */
	private boolean keyDown(int key, int modifiers, boolean waypointListKey, boolean mapKey, boolean regionKey) {
		// Escape shuts the right-click menu first, then the book, and the map only once both are shut.
		if (key == InputConstants.KEY_ESCAPE && this.menu != null) {
			this.closeMenu();
			return true;
		}
		if (key == InputConstants.KEY_ESCAPE && this.revealCorner != null) {
			this.revealCorner = null;
			return true;
		}
		// Then the stroke under way, then the region tools.
		if (key == InputConstants.KEY_ESCAPE && this.regions.drawing()) {
			this.regions.cancelStroke();
			return true;
		}
		if (key == InputConstants.KEY_ESCAPE && this.regions.active()) {
			this.regions.leave();
			return true;
		}
		if (key == InputConstants.KEY_ESCAPE && this.book.isOpen()) {
			this.setFocused(null);
			BookWidgets.pageTurn(this.minecraft.getSoundManager());
			this.book.close();
			return true;
		}
		// While a name or a coordinate is being typed, letters are letters.
		if (this.getFocused() instanceof EditBox box && box.isFocused()) {
			return false;
		}
		// The player's own bindings come before the map's fixed keys, so Open map rebound to C still closes it.
		if (waypointListKey) {
			BookWidgets.pageTurn(this.minecraft.getSoundManager());
			this.book.show(MapBook.Section.BANNERS, 0);
			return true;
		}
		if (mapKey) {
			this.onClose();
			return true;
		}
		if (regionKey) {
			if (this.regions.active()) {
				this.regions.leave();
			} else {
				this.startRegions();
			}
			return true;
		}
		if (this.regions.active() && this.regions.key(key, modifiers | (this.control ? 2 : 0) | (this.shift ? 1 : 0))) {
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

	/** Shut by the player (or by the server's rules), with the book's page sound; not when a settings screen replaces it. */
	@Override
	public void onClose() {
		BookWidgets.pageTurn(this.minecraft.getSoundManager());
		super.onClose();
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

	/** Turns the region tools on, shutting the book, which they would sit under. */
	public void startRegions() {
		if (this.book.isOpen()) {
			this.book.close();
		}
		this.regions.enter();
	}

	@Override
	public void drawRegions(@Nullable MapRegion region) {
		if (this.book.isOpen()) {
			this.book.close();
		}
		if (region == null) {
			this.regions.pick(dev.diorama.region.RegionTool.Tool.LASSO);
			this.regions.enter();
		} else {
			this.regions.edit(region);
		}
	}

	@Override
	public void created(MapRegion region) {
		// Left first, so the widgets are built once, with the name field taking the keyboard.
		this.regions.leave();
		this.backToRegions = true;
		this.book.newRegion(region);
	}

	/** Glides the view to where a region's name is written, and stops following the player. */
	@Override
	public void centerOn(MapRegion region) {
		double[] at = region.anchor();
		this.following = false;
		this.smoothZoom.reset(this.zoom);
		this.panTarget = new double[] {Projection.u(at[0], at[1]), Projection.worldV(at[0], at[2], at[1])};
	}

	/** The region drawn under a screen point, or null. */
	private @Nullable MapRegion regionUnder(double screenX, double screenY) {
		MapViewport.View view = this.lastView;
		if (view == null) {
			return null;
		}
		double u = view.centerU() + (screenX - view.x() - view.width() / 2.0) / view.zoom();
		double v = view.centerV() + (screenY - view.y() - view.height() / 2.0) / view.zoom();
		return this.viewport.regionAt(u, v);
	}

	/** For tests: the region tools. */
	public RegionMode regions() {
		return this.regions;
	}

	/** For tests: the map drawn last frame. */
	public MapViewport viewport() {
		return this.viewport;
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
