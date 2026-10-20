package dev.diorama.test;

import com.mojang.blaze3d.platform.InputConstants;
import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Settings;
import dev.diorama.bake.TileKey;
import dev.diorama.render.MapViewport;
import dev.diorama.waypoint.BannerDesign;
import dev.diorama.waypoint.BannerEditScreen;
import dev.diorama.waypoint.Waypoint;
import dev.diorama.waypoint.WaypointOverlay;
import dev.diorama.waypoint.WaypointShare;
import dev.diorama.waypoint.WaypointStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.components.Button;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Waypoint banners: on the fullscreen map at both sizes, gathered when they stand together, in
 * sepia on the antique map, on the minimap with one on its edge, and the loom-style editor adding
 * layers by mouse and undoing by keyboard. Opt-in with {@code DIORAMA_TESTS=banner-waypoints}.
 */
final class BannerWaypointsScenario {
	private static final Set<String> NAMES = Set.of("Home", "Creeper Camp", "Death 12:00", "Mine A", "Mine B", "Far Tower");

	private BannerWaypointsScenario() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runCommand("tick freeze");
		var style = context.computeOnClient(mc -> Settings.get().fogStyle);
		BannerDesign creeper = new BannerDesign(BannerDesign.Dye.LIME, List.of(
			new BannerDesign.Layer("minecraft:creeper", BannerDesign.Dye.BLACK),
			new BannerDesign.Layer("minecraft:border", BannerDesign.Dye.GREEN)));
		context.runOnClient(mc -> {
			String dim = mc.level.dimension().identifier().toString();
			int px = mc.player.getBlockX(), pz = mc.player.getBlockZ();
			List<Waypoint> added = new ArrayList<>();
			// A waypoint saved before banners: shown as the plain banner nearest its colour.
			added.add(at(mc, "Home", px + 5, pz + 7, dim, Waypoint.PALETTE[0]));
			Waypoint camp = at(mc, "Creeper Camp", px - 16, pz + 8, dim, Waypoint.PALETTE[3]);
			camp.banner = creeper;
			added.add(camp);
			Waypoint death = at(mc, "Death 12:00", px + 12, pz - 16, dim, 0xFF60646C);
			death.kind = Waypoint.Kind.DEATH;
			added.add(death);
			added.add(at(mc, "Mine A", px - 12, pz - 18, dim, Waypoint.PALETTE[5]));
			Waypoint mineB = at(mc, "Mine B", px - 12, pz - 17, dim, Waypoint.PALETTE[5]);
			mineB.banner = new BannerDesign(BannerDesign.Dye.BLUE, List.of(new BannerDesign.Layer("minecraft:stripe_center", BannerDesign.Dye.WHITE)));
			added.add(mineB);
			added.add(at(mc, "Far Tower", px + 300, pz, dim, Waypoint.PALETTE[7]));
			WaypointStore.get().addAll(added);

			// The banner rides along in chat and comes back the same.
			String line = WaypointShare.format(camp).orElseThrow();
			if (line.length() > 256 || !line.contains(" diorama-banner:")) throw new AssertionError("Banner not shared: " + line);
			var back = WaypointShare.parse("<Alex> " + line).orElseThrow();
			if (!creeper.equals(back.banner())) throw new AssertionError("Shared banner came back as " + back.banner());
			if (WaypointShare.parse("<Alex> " + WaypointShare.format(added.get(0)).orElseThrow()).orElseThrow().banner() != null) {
				throw new AssertionError("A plain banner shouldn't need the extra field");
			}
		});
		try {
			context.setScreen(MapScreen::new);
			context.waitFor(mc -> ((MapScreen) mc.gui.screen()).view() != null
				&& MapViewport.ready(MapSession.get().map(), ((MapScreen) mc.gui.screen()).view()), 2400);
			look(context, 2);
			context.waitTicks(40);
			context.runOnClient(mc -> {
				List<WaypointOverlay.Pin> pins = ours(((MapScreen) mc.gui.screen()).pins());
				if (pins.size() != 5) throw new AssertionError("Expected five banners with the mines gathered, got " + pins.size());
				if (pins.stream().noneMatch(p -> p.count() == 2)) throw new AssertionError("Mine A and Mine B didn't gather");
				if (pins.stream().noneMatch(WaypointOverlay.Pin::clamped)) throw new AssertionError("Far Tower isn't on the edge");
				// South in front: the list is drawn back to front.
				for (int i = 1; i < pins.size(); i++) {
					if (pins.get(i).footY() < pins.get(i - 1).footY()) throw new AssertionError("Banners not ordered south in front");
				}
			});
			context.takeScreenshot("banner-map");
			look(context, 4);
			context.waitTicks(20);
			context.runOnClient(mc -> {
				for (WaypointOverlay.Pin pin : ours(((MapScreen) mc.gui.screen()).pins())) {
					if (!pin.clamped() && pin.scale() != 2) throw new AssertionError("Close zoom should draw banners at 2x");
				}
			});
			// Names show only for the banner under the mouse.
			float[] camp = context.computeOnClient(mc -> {
				float scale = (float) mc.getWindow().getGuiScale();
				for (WaypointOverlay.Pin p : ours(((MapScreen) mc.gui.screen()).pins())) {
					if (p.waypoint().name.equals("Creeper Camp")) return new float[] {p.x() * scale, p.y() * scale};
				}
				throw new AssertionError("Creeper Camp is not on the map");
			});
			context.getInput().setCursorPos(camp[0], camp[1]);
			context.waitTicks(5);
			context.takeScreenshot("banner-map-close");
			context.getInput().setCursorPos(0, 0);
			context.runOnClient(mc -> {
				Settings.get().fogStyle = Settings.FogStyle.ANCIENT_MAP;
				Settings.get().sepiaBanners = true;
			});
			look(context, 2);
			context.waitTicks(20);
			context.takeScreenshot("banner-map-sepia");
			context.runOnClient(mc -> {
				Settings.get().fogStyle = style;
				Settings.get().sepiaBanners = false;
			});

			// Beside Home the minimap names it; the others, further off, stay unnamed.
			context.setScreen(() -> null);
			String home = context.computeOnClient(mc -> {
				for (Waypoint w : WaypointStore.get().all()) {
					if (w.name.equals("Home")) return (w.x + 2) + " " + (w.y + 1) + " " + (w.z + 2);
				}
				throw new AssertionError("Home is missing");
			});
			world.getServer().runCommand("tp @a " + home);
			context.waitTicks(40);
			context.takeScreenshot("banner-minimap");
			// The minimap framed as a vanilla map in hand, the compass inked on its paper.
			context.runOnClient(mc -> Settings.get().minimapFrame = Settings.MinimapFrame.MAP);
			context.waitTicks(10);
			context.takeScreenshot("minimap-map-frame");
			// The map drawn only where the sheet's texture is opaque, its torn pixels as the edge.
			context.runOnClient(mc -> Settings.get().minimapFrame = Settings.MinimapFrame.MAP_CUTOUT);
			context.waitTicks(10);
			context.takeScreenshot("minimap-map-sheet");
			context.runOnClient(mc -> Settings.get().minimapFrame = Settings.MinimapFrame.PLAIN);

			// The editor: two patterns clicked onto the creeper banner, then Ctrl+Z takes one back.
			BannerDesign[] result = new BannerDesign[1];
			context.setScreen(() -> new BannerEditScreen(null, creeper, d -> result[0] = d));
			context.waitTicks(20);
			for (int i = 0; i < 2; i++) {
				int index = i * 3;
				double[] at = context.computeOnClient(mc -> {
					BannerEditScreen screen = (BannerEditScreen) mc.gui.screen();
					if (screen.patternCount() < 40) throw new AssertionError("Only " + screen.patternCount() + " patterns in the grid");
					Button button = screen.patternButtons().get(index);
					double scale = mc.getWindow().getGuiScale();
					return new double[] {(button.getX() + button.getWidth() / 2.0) * scale, (button.getY() + button.getHeight() / 2.0) * scale};
				});
				context.getInput().setCursorPos(at[0], at[1]);
				context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
				context.waitTicks(2);
			}
			context.getInput().holdKey(InputConstants.KEY_LCONTROL);
			context.getInput().pressKey(InputConstants.KEY_Z);
			context.getInput().releaseKey(InputConstants.KEY_LCONTROL);
			context.waitTicks(20);
			context.runOnClient(mc -> {
				BannerDesign design = ((BannerEditScreen) mc.gui.screen()).design();
				if (design.layers().size() != 3) throw new AssertionError("Expected three layers after an undo, got " + design.layers());
			});
			context.takeScreenshot("banner-editor");

			// The waypoint's own editor: banner buttons, and Delete even for a new waypoint.
			context.setScreen(() -> new dev.diorama.waypoint.WaypointEditScreen(null,
				new Waypoint("Lookout", 0, 70, 0, "minecraft:overworld", Waypoint.PALETTE[4]), true));
			context.waitTicks(10);
			context.takeScreenshot("waypoint-editor");
			context.setScreen(() -> null);
		} finally {
			context.runOnClient(mc -> {
				Settings.get().fogStyle = style;
				Settings.get().sepiaBanners = false;
				Settings.get().minimapFrame = Settings.MinimapFrame.PLAIN;
				WaypointStore store = WaypointStore.get();
				for (Waypoint w : new ArrayList<>(store.all())) {
					if (NAMES.contains(w.name)) store.remove(w);
				}
			});
			world.getServer().runCommand("tick unfreeze");
		}
	}

	private static Waypoint at(net.minecraft.client.Minecraft mc, String name, int x, int z, String dim, int colour) {
		int y = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
		return new Waypoint(name, x, y, z, dim, colour);
	}

	private static List<WaypointOverlay.Pin> ours(List<WaypointOverlay.Pin> pins) {
		return pins.stream().filter(p -> NAMES.contains(p.waypoint().name)).toList();
	}

	private static void look(ClientGameTestContext context, double zoom) {
		context.runOnClient(mc -> {
			if (mc.gui.screen() instanceof MapScreen screen) {
				screen.lookAt(mc.player.getX(), dev.diorama.Projection.v(mc.player.getY(), mc.player.getZ()), zoom, TileKey.SURFACE);
			}
		});
	}
}
