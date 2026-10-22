package dev.diorama.ui;

import dev.diorama.Settings;
import dev.diorama.region.MapRegion;
import dev.diorama.region.RegionStore;
import dev.diorama.waypoint.Waypoints;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * "Entering <region>" over the hotbar as the player walks into a region, in its dye, with the
 * {@code regionEnterNotice} setting on. Checked every half second; leaving a region, or walking
 * from one into a region inside it, counts by the smallest region the player stands in.
 */
public final class RegionNotice {
	private static final int EVERY = 10;
	private static @Nullable UUID inside;
	private static int ticks;

	private RegionNotice() {
	}

	/** Each client tick. */
	public static void tick(Minecraft mc) {
		if (++ticks < EVERY) {
			return;
		}
		ticks = 0;
		LocalPlayer player = mc.player;
		RegionStore store = RegionStore.get();
		String dimension = Waypoints.dimension();
		MapRegion here = player == null || store == null || dimension == null ? null : store.containing(dimension, player.getX(), player.getZ(), false);
		UUID id = here == null ? null : here.id;
		if (id != null && !id.equals(inside) && Settings.get().regionEnterNotice) {
			Component text = Component.translatable("diorama.regions.entering", here.name).withColor(dev.diorama.render.RegionLabels.readable(here.dye.argb()) & 0xFFFFFF);
			//? if >=26.1 {
			player.sendOverlayMessage(text);
			//?} else {
			/*player.displayClientMessage(text, true);
			*///?}
		}
		inside = id;
	}
}
