package dev.diorama.waypoint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** The waypoints a list shows, and the Xaero's import offered beside them, for the list screen and the map's book. */
public final class WaypointListModel {
	private WaypointListModel() {
	}

	/** The waypoints of the player's dimension, nearest first. */
	public static List<Waypoint> rows() {
		Minecraft mc = Minecraft.getInstance();
		WaypointStore store = WaypointStore.get();
		LocalPlayer player = mc.player;
		List<Waypoint> rows = new ArrayList<>();
		if (store != null && player != null && mc.level != null) {
			rows.addAll(store.in(mc.level.dimension().identifier().toString()));
			rows.sort(Comparator.comparingDouble(w -> w.distanceTo(player.getX(), player.getY(), player.getZ())));
		}
		return rows;
	}

	/**
	 * How many waypoints the world Xaero's would open has that this store doesn't, or -1 when
	 * Xaero's has several worlds for this one and the player must pick; 0 offers nothing.
	 */
	public static int importable(XaeroImport.@Nullable Found found, @Nullable WaypointStore store) {
		if (found == null || store == null) {
			return 0;
		}
		if (found.chosen() != null) {
			return XaeroImport.missing(found.chosen(), store).size();
		}
		return found.worlds().stream().allMatch(world -> XaeroImport.missing(world, store).isEmpty()) ? 0 : -1;
	}

	/** Imports one of Xaero's worlds and says what came in. */
	public static @Nullable Component importFrom(XaeroImport.World world) {
		WaypointStore store = WaypointStore.get();
		Minecraft mc = Minecraft.getInstance();
		if (store == null || mc.level == null) {
			return null;
		}
		List<Waypoint> added = XaeroImport.importInto(world, store);
		String here = mc.level.dimension().identifier().toString();
		long elsewhere = added.stream().filter(w -> !w.dimension.equals(here)).count();
		Component count = added.size() == 1
			? Component.translatable("diorama.xaero.count_one")
			: Component.translatable("diorama.xaero.count", added.size());
		return elsewhere == 0
			? Component.translatable("diorama.xaero.imported", count)
			: Component.translatable("diorama.xaero.imported_elsewhere", count, elsewhere);
	}
}
