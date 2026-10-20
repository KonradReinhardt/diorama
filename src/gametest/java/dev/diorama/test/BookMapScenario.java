package dev.diorama.test;

import com.mojang.blaze3d.platform.InputConstants;
import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.MapStyle;
import dev.diorama.Settings;
import dev.diorama.SettingsScreen;
import dev.diorama.bake.TileKey;
import dev.diorama.render.MapViewport;
import dev.diorama.ui.BookLayout;
import dev.diorama.ui.MapBook;
import dev.diorama.waypoint.BannerDesign;
import dev.diorama.waypoint.Waypoint;
import dev.diorama.waypoint.WaypointOverlay;
import dev.diorama.waypoint.WaypointStore;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The world map's book: the zoom in as the map opens, each section's pages, a look picked in the
 * book redrawing the map behind it without leaving it, the minimap ribbon going out and back, a
 * banner on the map opening its page, typing a name without the map's keys firing, and nothing
 * overlapping or off screen at 1280x720. Opt-in with {@code DIORAMA_TESTS=book-map}.
 */
final class BookMapScenario {
	private BookMapScenario() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runCommand("time set noon");
		world.getServer().runCommand("tick freeze");
		Settings.RenderStyle style = context.computeOnClient(mc -> Settings.get().renderStyle);
		context.runOnClient(mc -> {
			String dim = mc.level.dimension().identifier().toString();
			int px = mc.player.getBlockX(), pz = mc.player.getBlockZ();
			List<Waypoint> added = new ArrayList<>();
			added.add(at(mc, "Home", px + 6, pz + 5, dim, Waypoint.PALETTE[0]));
			Waypoint camp = at(mc, "Creeper Camp", px - 14, pz + 9, dim, Waypoint.PALETTE[3]);
			camp.banner = new BannerDesign(BannerDesign.Dye.LIME, List.of(new BannerDesign.Layer("minecraft:creeper", BannerDesign.Dye.BLACK)));
			added.add(camp);
			added.add(at(mc, "Mine", px - 9, pz - 15, dim, Waypoint.PALETTE[5]));
			WaypointStore.get().addAll(added);
		});
		try {
			// The map opens zoomed a little out and eases in to a whole pixel step.
			context.setScreen(MapScreen::new);
			boolean[] zooming = context.computeOnClient(mc -> {
				MapScreen screen = (MapScreen) mc.gui.screen();
				return new boolean[] {screen.opening(), screen.view() == null || screen.view().zoom() < screen.heldView().zoom()};
			});
			if (!zooming[0] || !zooming[1]) {
				throw new AssertionError("The map didn't open with its zoom in");
			}
			context.waitFor(mc -> !((MapScreen) mc.gui.screen()).opening(), 100);
			context.waitTicks(2);
			context.runOnClient(mc -> {
				MapScreen screen = (MapScreen) mc.gui.screen();
				MapViewport.View drawn = screen.view();
				MapViewport.View rest = screen.heldView().pixelPerfect();
				if (Math.abs(drawn.zoom() - rest.zoom()) > 1e-9 || Math.abs(screen.heldView().zoom() - rest.zoom()) > 1e-9) {
					throw new AssertionError("The zoom in ended at " + drawn.zoom() + ", not at rest on " + rest.zoom());
				}
			});
			settle(context);
			context.takeScreenshot("book-01-shut");

			for (MapBook.Section section : List.of(MapBook.Section.BANNERS, MapBook.Section.LOOK, MapBook.Section.LAYERS, MapBook.Section.RADAR)) {
				context.runOnClient(mc -> book(mc).show(section, 0));
				context.waitTicks(12);
				context.takeScreenshot("book-02-" + section.name().toLowerCase(java.util.Locale.ROOT));
				checkLayout(context, section.name());
			}
			context.runOnClient(mc -> book(mc).show(MapBook.Section.BANNERS, 1));
			context.waitTicks(12);
			context.takeScreenshot("book-03-banner-options");

			// A look picked in the book: the map behind redraws in it, and the book stays.
			context.runOnClient(mc -> book(mc).show(MapBook.Section.LOOK, 0));
			context.waitTicks(10);
			clickWidget(context, Component.translatable("diorama.settings.render_style.painted").getString());
			context.runOnClient(mc -> {
				if (Settings.get().renderStyle != Settings.RenderStyle.PAINTED || !MapStyle.get().painted()) {
					throw new AssertionError("Picking Painted in the book didn't change the look");
				}
				if (!(mc.gui.screen() instanceof MapScreen) || !book(mc).isOpen()) {
					throw new AssertionError("Picking a look left the book");
				}
			});
			settle(context);
			context.takeScreenshot("book-04-painted-from-book");
			// The antique tint is only composed, never redrawn.
			context.runOnClient(mc -> {
				Settings.get().fogStyle = Settings.FogStyle.ANCIENT_MAP;
				Settings.get().save();
			});
			context.waitTicks(5);
			long before = context.computeOnClient(mc -> MapSession.get().map().submittedJobs());
			clickWidget(context, Component.translatable("diorama.settings.antique_tint").getString());
			context.waitTicks(10);
			long after = context.computeOnClient(mc -> MapSession.get().map().submittedJobs());
			if (after != before) {
				throw new AssertionError("Tinting the explored map redrew " + (after - before) + " tiles");
			}
			context.takeScreenshot("book-05-antique-untinted");
			context.runOnClient(mc -> {
				Settings.get().antiqueTintsExplored = true;
				Settings.get().fogStyle = Settings.FogStyle.CLOUDS;
				Settings.get().save();
			});

			// The minimap's ribbon leaves for its settings, and Done comes back to the same open book.
			MapScreen map = context.computeOnClient(mc -> (MapScreen) mc.gui.screen());
			clickAt(context, context.computeOnClient(mc -> book(mc).ribbonOf(MapBook.Section.MINIMAP)));
			context.waitTicks(3);
			if (!context.computeOnClient(mc -> mc.gui.screen() instanceof SettingsScreen)) {
				throw new AssertionError("The minimap ribbon didn't open the minimap's settings");
			}
			context.takeScreenshot("book-06-minimap-settings");
			context.runOnClient(mc -> mc.gui.screen().onClose());
			context.waitTicks(3);
			context.runOnClient(mc -> {
				if (mc.gui.screen() != map || map.opening() || !map.book().isOpen() || map.book().section() != MapBook.Section.LOOK) {
					throw new AssertionError("Coming back from the minimap's settings didn't reopen the book where it was");
				}
			});

			// A click on a banner on the map opens its page; typing its name doesn't fire the map's keys.
			context.runOnClient(mc -> book(mc).close());
			context.waitTicks(10);
			lookAtPlayer(context);
			context.waitTicks(10);
			float[] pin = context.computeOnClient(mc -> {
				float scale = (float) mc.getWindow().getGuiScale();
				for (WaypointOverlay.Pin p : ((MapScreen) mc.gui.screen()).pins()) {
					if (p.waypoint().name.equals("Home")) {
						return new float[] {p.x() * scale, (p.y() - 4) * scale};
					}
				}
				throw new AssertionError("Home is not on the map");
			});
			context.getInput().setCursorPos(pin[0], pin[1]);
			context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
			context.waitTicks(12);
			context.runOnClient(mc -> {
				Waypoint picked = book(mc).picked();
				if (!book(mc).isOpen() || picked == null || !picked.name.equals("Home")) {
					throw new AssertionError("Clicking Home on the map didn't open its page");
				}
			});
			context.takeScreenshot("book-07-home-picked");
			int layer = context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).layer());
			EditBox name = context.computeOnClient(mc -> editBoxes(mc).get(0));
			clickAt(context, name);
			context.runOnClient(mc -> name.setValue(""));
			context.getInput().typeChars("cabana");
			context.waitTicks(2);
			context.runOnClient(mc -> {
				MapScreen screen = (MapScreen) mc.gui.screen();
				if (screen.layer() != layer) {
					throw new AssertionError("Typing c in the name switched the map to caves");
				}
				Waypoint picked = book(mc).picked();
				if (picked == null || !picked.name.equals("cabana")) {
					throw new AssertionError("The typed name didn't reach the waypoint: " + (picked == null ? null : picked.name));
				}
			});
			context.takeScreenshot("book-08-renamed");

			context.getInput().pressKey(InputConstants.KEY_ESCAPE);
			context.waitTicks(10);
			if (!context.computeOnClient(mc -> mc.gui.screen() instanceof MapScreen && !book(mc).isOpen())) {
				throw new AssertionError("Escape didn't shut the book first");
			}

			// A right-click anywhere on the map offers its spot: copying puts it on the clipboard.
			double[] spot = context.computeOnClient(mc -> {
				double scale = mc.getWindow().getGuiScale();
				MapScreen screen = (MapScreen) mc.gui.screen();
				return new double[] {(screen.width / 2.0 - 40) * scale, (screen.height / 2.0 + 30) * scale};
			});
			context.getInput().setCursorPos(spot[0], spot[1]);
			context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
			context.waitTicks(3);
			String expected = context.computeOnClient(mc -> {
				var menu = ((MapScreen) mc.gui.screen()).menu();
				if (menu == null) {
					throw new AssertionError("Right-clicking the map didn't open its menu");
				}
				return menu.target().command();
			});
			context.takeScreenshot("book-08b-map-menu");
			clickWidget(context, Component.translatable("diorama.menu.copy").getString(), false);
			context.runOnClient(mc -> {
				String copied = mc.keyboardHandler.getClipboard();
				if (!expected.equals(copied)) {
					throw new AssertionError("Copy coordinates put " + copied + " on the clipboard, not " + expected);
				}
				if (((MapScreen) mc.gui.screen()).menu() != null) {
					throw new AssertionError("The menu stayed open after copying");
				}
			});
			context.getInput().setCursorPos(spot[0], spot[1]);
			context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
			context.waitTicks(3);
			context.getInput().pressKey(InputConstants.KEY_ESCAPE);
			context.waitTicks(3);
			if (!context.computeOnClient(mc -> mc.gui.screen() instanceof MapScreen screen && screen.menu() == null)) {
				throw new AssertionError("Escape didn't shut the right-click menu before the map");
			}

			// 1280x720 at GUI scale 3: nothing on the open pages runs off the screen or overlaps.
			int guiScale = context.computeOnClient(mc -> mc.options.guiScale().get());
			context.getInput().resizeWindow(1280, 720);
			setGuiScale(context, 3);
			context.waitTicks(5);
			for (MapBook.Section section : List.of(MapBook.Section.BANNERS, MapBook.Section.LOOK, MapBook.Section.LAYERS, MapBook.Section.RADAR)) {
				context.runOnClient(mc -> book(mc).show(section, 0));
				context.waitTicks(12);
				checkLayout(context, section.name() + " at 427x240");
			}
			context.takeScreenshot("book-09-small-radar");
			setGuiScale(context, guiScale);
			context.getInput().pressKey(InputConstants.KEY_ESCAPE);
			context.waitTicks(10);
			context.getInput().pressKey(InputConstants.KEY_ESCAPE);
			context.waitTicks(5);
			if (context.computeOnClient(mc -> mc.gui.screen() != null)) {
				throw new AssertionError("A second Escape didn't close the map");
			}
		} finally {
			context.setScreen(() -> null);
			context.runOnClient(mc -> {
				WaypointStore store = WaypointStore.get();
				for (Waypoint w : new ArrayList<>(store.all())) {
					store.remove(w);
				}
				Settings.get().renderStyle = style;
				Settings.get().fogStyle = Settings.FogStyle.CLOUDS;
				Settings.get().antiqueTintsExplored = true;
				Settings.get().save();
			});
			world.getServer().runCommand("tick unfreeze");
		}
	}

	private static MapBook book(net.minecraft.client.Minecraft mc) {
		return ((MapScreen) mc.gui.screen()).book();
	}

	private static Waypoint at(net.minecraft.client.Minecraft mc, String name, int x, int z, String dim, int colour) {
		int y = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
		return new Waypoint(name, x, y, z, dim, colour);
	}

	private static List<EditBox> editBoxes(net.minecraft.client.Minecraft mc) {
		List<EditBox> boxes = new ArrayList<>();
		for (var child : mc.gui.screen().children()) {
			if (child instanceof EditBox box) {
				boxes.add(box);
			}
		}
		return boxes;
	}

	/** The open pages' widgets stay on screen, inside the book, and apart from each other and the ribbons. */
	private static void checkLayout(ClientGameTestContext context, String where) {
		context.runOnClient(mc -> {
			MapBook book = book(mc);
			BookLayout layout = book.layout();
			int w = mc.gui.screen().width, h = mc.gui.screen().height;
			List<AbstractWidget> widgets = book.pageWidgets();
			for (AbstractWidget a : widgets) {
				if (a.getX() < 0 || a.getY() < 0 || a.getRight() > w || a.getBottom() > h) {
					throw new AssertionError(where + ": " + a.getMessage().getString() + " runs off the screen");
				}
				for (AbstractWidget b : widgets) {
					if (a != b && a.getX() < b.getRight() && b.getX() < a.getRight() && a.getY() < b.getBottom() && b.getY() < a.getBottom()) {
						throw new AssertionError(where + ": " + a.getMessage().getString() + " overlaps " + b.getMessage().getString());
					}
				}
				boolean inside = a.getX() >= layout.cover().x() && a.getRight() <= layout.cover().right()
					&& a.getY() >= layout.tabY() - 2 && a.getBottom() <= layout.cover().bottom();
				if (!inside) {
					throw new AssertionError(where + ": " + a.getMessage().getString() + " sits outside the book");
				}
			}
		});
	}

	/** Clicks the page widget whose name is {@code label}. */
	private static void clickWidget(ClientGameTestContext context, String label) {
		clickWidget(context, label, true);
	}

	/** Clicks the widget named {@code label}, on the open pages or anywhere on the screen. */
	private static void clickWidget(ClientGameTestContext context, String label, boolean onPages) {
		AbstractWidget widget = context.computeOnClient(mc -> {
			List<AbstractWidget> widgets = new ArrayList<>();
			if (onPages) {
				widgets.addAll(book(mc).pageWidgets());
			} else {
				for (var child : mc.gui.screen().children()) {
					if (child instanceof AbstractWidget w) {
						widgets.add(w);
					}
				}
			}
			for (AbstractWidget w : widgets) {
				if (w.getMessage().getString().equals(label)) {
					return w;
				}
			}
			throw new AssertionError("No widget " + label);
		});
		clickAt(context, widget);
	}

	private static void clickAt(ClientGameTestContext context, AbstractWidget widget) {
		double[] at = context.computeOnClient(mc -> {
			double scale = mc.getWindow().getGuiScale();
			return new double[] {(widget.getX() + widget.getWidth() / 2.0) * scale, (widget.getY() + widget.getHeight() / 2.0) * scale};
		});
		context.getInput().setCursorPos(at[0], at[1]);
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(2);
	}

	private static void setGuiScale(ClientGameTestContext context, int scale) {
		context.runOnClient(mc -> {
			mc.options.guiScale().set(scale);
			//? if >=26.1 {
			mc.resizeGui();
			//?} else {
			/*mc.resizeDisplay();
			*///?}
		});
		context.waitTicks(5);
	}

	private static void lookAtPlayer(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			if (mc.gui.screen() instanceof MapScreen screen) {
				screen.lookAt(mc.player.getX(), dev.diorama.Projection.v(mc.player.getY(), mc.player.getZ()), 4.0, TileKey.SURFACE);
			}
		});
	}

	private static void settle(ClientGameTestContext context) {
		context.waitTicks(20);
		int quiet = 0;
		for (int i = 0; i < 1200 && quiet < 30; i++) {
			context.waitTick();
			boolean baking = context.computeOnClient(mc -> MapSession.get() != null && MapSession.get().map().bakingCount() > 0);
			quiet = baking ? 0 : quiet + 1;
		}
	}
}
