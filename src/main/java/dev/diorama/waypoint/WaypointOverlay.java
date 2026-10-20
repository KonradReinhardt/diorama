package dev.diorama.waypoint;

import dev.diorama.Projection;
import dev.diorama.Settings;
import dev.diorama.render.BannerAtlas;
import dev.diorama.render.BannerCompositor;
import dev.diorama.render.MapIcons;
import dev.diorama.render.MapViewport;
import dev.diorama.render.Spring;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

/**
 * Waypoints on the minimap and fullscreen map, each as its banner standing on its block, in pixel
 * art on whole GUI pixels like the player marker. Banners further south stand in front; banners
 * closer than half a sprite gather into one with a count. Names show on hover on the fullscreen
 * map, and on the minimap for waypoints the player is near. Waypoints outside the view stand on its
 * edge, smaller, with an arrow pointing toward them, so the minimap always shows which way home is.
 */
public final class WaypointOverlay {
	/** Map zoom (GUI pixels per block) from which fullscreen banners draw at twice the size. */
	private static final double DOUBLE_ZOOM = 4;
	/** Within this many blocks of a waypoint, the minimap shows its name. */
	private static final double NEAR_NAME = 10;
	/** How much bigger a banner grows under the mouse on the fullscreen map. */
	private static final float GROW = 0.25F;
	/** From this many blocks away a waypoint fades on the minimap, and at this many it's gone. */
	private static final double FAR_START = 192, FAR_GONE = 384;
	/** Animations by waypoint: the hover grow and name on the fullscreen map, the minimap's names. */
	private static final Map<UUID, Spring> HOVER_GROW = new HashMap<>();
	private static final Map<UUID, Spring> HOVER_NAME = new HashMap<>();
	private static final Map<UUID, Spring> NEAR_NAMES = new HashMap<>();

	/**
	 * A banner placed on screen. (x, y) is the middle of the sprite, where the mouse finds it;
	 * (footX, footY) the waypoint's block, where the pole stands; {@code width} x {@code height} the
	 * sprite's size. {@code clamped} when it was pulled in to the view's edge; {@code count} how
	 * many waypoints stand together here, this one in front.
	 */
	public record Pin(Waypoint waypoint, float x, float y, float size, boolean clamped,
		BannerCompositor.Size sprite, int scale, float footX, float footY, int count) {
		float width() {
			return this.sprite.width() * this.scale;
		}

		float height() {
			return this.sprite.height() * this.scale;
		}
	}

	private WaypointOverlay() {
	}

	public static List<Pin> layout(MapViewport.View view, List<Waypoint> waypoints, boolean fullscreen) {
		BannerCompositor.Size near = fullscreen ? BannerCompositor.Size.MAP : BannerCompositor.Size.SMALL;
		int scale = fullscreen && view.zoom() >= DOUBLE_ZOOM ? 2 : 1;
		float cx = view.x() + view.width() / 2.0F;
		float cy = view.y() + view.height() / 2.0F;
		List<Pin> placed = new ArrayList<>();
		for (Waypoint waypoint : waypoints) {
			if (!waypoint.visible || !fullscreen && farFade(waypoint) <= 0) {
				continue;
			}
			float x = view.screenX(Projection.u(waypoint.x + 0.5, waypoint.z + 0.5));
			float y = view.screenY(Projection.worldV(waypoint.x + 0.5, waypoint.y, waypoint.z + 0.5));
			// The banner stands above its foot: keep the whole sprite inside the view, with room
			// for the arrow beside it.
			BannerCompositor.Size small = BannerCompositor.Size.SMALL;
			float halfW = view.width() / 2.0F - small.width() / 2.0F - 5;
			float top = cy - view.y() - small.footY() - 3;
			float bottom = view.y() + view.height() - cy - 4;
			float dx = x - cx;
			float dy = y - cy;
			float reach = Math.max(Math.abs(dx) / halfW, dy < 0 ? -dy / top : dy / bottom);
			if (reach > 1) {
				placed.add(pin(waypoint, cx + dx / reach, cy + dy / reach, small, 1, true, 1));
			} else {
				placed.add(pin(waypoint, x, y, near, scale, false, 1));
			}
		}
		// Further south in front: drawn later. Then banners standing on each other gather.
		placed.sort(Comparator.comparingDouble(Pin::footY));
		List<Pin> pins = new ArrayList<>();
		for (int i = placed.size() - 1; i >= 0; i--) {
			Pin pin = placed.get(i);
			boolean merged = false;
			if (!pin.clamped()) {
				for (int j = 0; j < pins.size(); j++) {
					Pin front = pins.get(j);
					if (!front.clamped() && Math.abs(front.footX() - pin.footX()) < front.width() / 2
						&& Math.abs(front.footY() - pin.footY()) < front.height() / 2) {
						pins.set(j, pin(front.waypoint(), front.footX(), front.footY(), front.sprite(), front.scale(), false, front.count() + 1));
						merged = true;
						break;
					}
				}
			}
			if (!merged) {
				pins.add(pin);
			}
		}
		return pins.reversed();
	}

	private static Pin pin(Waypoint waypoint, float footX, float footY, BannerCompositor.Size sprite, int scale, boolean clamped, int count) {
		float footRow = sprite.footY() * scale;
		float middle = footY - footRow / 2;
		return new Pin(waypoint, footX, middle, Math.max(sprite.width(), sprite.height()) * scale, clamped, sprite, scale, footX, footY, count);
	}

	public static void draw(GuiGraphicsExtractor graphics, MapViewport.View view, List<Pin> pins, boolean fullscreen, @Nullable Pin hovered) {
		Minecraft mc = Minecraft.getInstance();
		Font font = mc.font;
		Settings settings = Settings.get();
		boolean sepia = settings.sepiaBanners && settings.fogStyle == Settings.FogStyle.ANCIENT_MAP;
		int frame = BannerAtlas.frameNow(settings.animateBanners);
		BannerAtlas atlas = BannerAtlas.shared();
		graphics.enableScissor(view.x(), view.y(), view.x() + view.width(), view.y() + view.height());
		float cx = view.x() + view.width() / 2.0F;
		float cy = view.y() + view.height() / 2.0F;
		long now = System.nanoTime();
		Map<UUID, Spring> grows = fullscreen ? HOVER_GROW : Map.of();
		Map<UUID, Spring> fades = fullscreen ? HOVER_NAME : NEAR_NAMES;
		// Banners growing under the mouse stand in front of the rest while they do.
		List<Pin> order = new ArrayList<>(pins.size());
		List<Pin> lifted = new ArrayList<>();
		for (Pin pin : pins) {
			double grow = 0;
			if (fullscreen) {
				Spring spring = HOVER_GROW.computeIfAbsent(pin.waypoint().id, id -> new Spring(Spring.BOUNCY));
				grow = spring.update(isHovered(pin, hovered) ? 1 : 0, now);
			}
			(Math.abs(grow) > 0.01 ? lifted : order).add(pin);
		}
		order.addAll(lifted);
		for (Pin pin : order) {
			Waypoint waypoint = pin.waypoint();
			Spring grow = grows.get(waypoint.id);
			float scale = pin.scale() * (float) (1 + GROW * (grow == null ? 0 : grow.value()));
			// On the minimap, far waypoints fade away before they go.
			float opacity = fullscreen ? 1 : farFade(waypoint);
			if (pin.clamped()) {
				double angle = Math.atan2(pin.y() - cy, pin.x() - cx);
				float reach = pin.width() * 0.5F + 3;
				float ax = (float) (pin.x() + Math.cos(angle) * reach);
				float ay = (float) (pin.y() + Math.sin(angle) * reach);
				MapIcons.arrow(graphics, ax, ay, 6, angle, alpha(0xE0101418, opacity), 1.6F);
				MapIcons.arrow(graphics, ax, ay, 6, angle, alpha(waypoint.design().colour(), opacity), 0);
			}
			if (!atlas.draw(graphics, waypoint.design(), pin.sprite(), pin.footX(), pin.footY(), scale, frame, sepia, alpha(-1, opacity))) {
				// Its sprite is still being composed: a dot of its colour this frame.
				MapIcons.dot(graphics, pin.footX(), pin.footY() - 4, 7, alpha(waypoint.design().colour(), opacity));
			}
			if (pin.count() > 1) {
				badge(graphics, font, pin, opacity);
			}
		}
		// Names only where they're wanted, fading in and out: on the fullscreen map the banner
		// under the mouse, on the minimap the banners the player stands near. Front to back, each
		// skipped where it would cover a name already placed; the hovered one always shows.
		List<int[]> placed = new ArrayList<>();
		for (int i = pins.size() - 1; i >= 0; i--) {
			Pin pin = pins.get(i);
			boolean wanted = !pin.clamped() && (fullscreen ? isHovered(pin, hovered)
				: mc.player != null && pin.waypoint().distanceTo(mc.player.getX(), mc.player.getY(), mc.player.getZ()) <= NEAR_NAME);
			Spring fade = fades.computeIfAbsent(pin.waypoint().id, id -> new Spring(Spring.SOFT));
			double shown = Math.clamp(fade.update(wanted ? 1 : 0, now), 0, 1);
			if (shown > 0.02 && !pin.clamped()) {
				plaque(graphics, font, pin, placed, fullscreen, !fullscreen, (float) shown);
			}
		}
		graphics.disableScissor();
		// Forget waypoints at rest, so the maps don't keep one spring per waypoint ever seen.
		if (fullscreen) {
			HOVER_GROW.values().removeIf(spring -> spring.settled(0));
		}
		fades.values().removeIf(spring -> spring.settled(0));
	}

	/** How many waypoints stand together, in a small ink tag at the cloth's top corner. */
	private static void badge(GuiGraphicsExtractor graphics, Font font, Pin pin, float opacity) {
		String text = Integer.toString(pin.count());
		int w = font.width(text);
		int x = Math.round(pin.footX() + pin.width() / 2) - 2;
		int y = Math.round(pin.footY() - pin.height()) + 2;
		graphics.fill(x - 1, y - 1, x + w + 2, y + 9, alpha(0xFF1E140C, opacity));
		graphics.fill(x, y, x + w + 1, y + 8, alpha(0xFFE8D7A8, opacity));
		graphics.text(font, text, x + 1, y, alpha(0xFF1E140C, opacity), false);
	}

	/**
	 * How much of a waypoint the minimap shows by its distance from the player: whole up to
	 * {@link #FAR_START} blocks, easing out to nothing at {@link #FAR_GONE}.
	 */
	private static float farFade(Waypoint waypoint) {
		Player player = Minecraft.getInstance().player;
		if (player == null) {
			return 1;
		}
		double t = (waypoint.distanceTo(player.getX(), player.getY(), player.getZ()) - FAR_START) / (FAR_GONE - FAR_START);
		t = Math.clamp(t, 0, 1);
		return (float) (1 - t * t * (3 - 2 * t));
	}

	/**
	 * The name on a little parchment plaque under the banner's foot, or above its finial where the
	 * player's marker would stand on it (the minimap, when the player is near).
	 */
	private static void plaque(GuiGraphicsExtractor graphics, Font font, Pin pin, List<int[]> placed, boolean always, boolean above, float shown) {
		String name = pin.count() > 1 ? pin.waypoint().name + " +" + (pin.count() - 1) : pin.waypoint().name;
		int w = font.width(name);
		int x = Math.round(pin.footX() - w / 2.0F);
		// It drifts the last few pixels into place as it fades in, toward the banner.
		int drift = Math.round((1 - shown) * 3);
		int y = above ? Math.round(pin.footY() - pin.height()) - 10 - drift : Math.round(pin.footY()) + 2 * pin.scale() + 2 + drift;
		int[] box = {x - 3, y - 2, x + w + 3, y + 10};
		for (int[] other : placed) {
			if (!always && box[0] < other[2] && other[0] < box[2] && box[1] < other[3] && other[1] < box[3]) {
				return;
			}
		}
		placed.add(box);
		graphics.fill(x - 3, y - 2, x + w + 3, y + 10, alpha(0xFF1E140C, shown));
		graphics.fill(x - 2, y - 1, x + w + 2, y + 9, alpha(0xFFE8D7A8, shown));
		graphics.fill(x - 2, y + 8, x + w + 2, y + 9, alpha(0xFFC9B27E, shown));
		graphics.text(font, name, x, y, alpha(0xFF2A1C10, shown), false);
	}

	private static int alpha(int argb, float shown) {
		return Math.max(8, Math.round((argb >>> 24) * shown)) << 24 | (argb & 0xFFFFFF);
	}

	private static boolean isHovered(Pin pin, @Nullable Pin hovered) {
		return hovered != null && hovered.waypoint() == pin.waypoint();
	}

	public static @Nullable Pin under(List<Pin> pins, double mouseX, double mouseY) {
		// The front banner wins: the last drawn that the mouse is over.
		for (int i = pins.size() - 1; i >= 0; i--) {
			Pin pin = pins.get(i);
			float x0 = Math.round(pin.footX()) - pin.width() / 2 - 1;
			float y0 = Math.round(pin.footY()) - pin.sprite().footY() * pin.scale() - 1;
			if (mouseX >= x0 && mouseX <= x0 + pin.width() + 2 && mouseY >= y0 && mouseY <= Math.round(pin.footY()) + pin.scale() + 1) {
				return pin;
			}
		}
		return null;
	}

	/** "Home · 120 m · 4 below" */
	public static Component describe(Waypoint waypoint, Player viewer) {
		int distance = (int) Math.round(waypoint.distanceTo(viewer.getX(), viewer.getY(), viewer.getZ()));
		int dy = (int) Math.round(waypoint.y - viewer.getY());
		String height = dy > 2 ? " · " + dy + " above" : dy < -2 ? " · " + (-dy) + " below" : "";
		return Component.literal(waypoint.name + " · " + distance + " m" + height);
	}

	/** Dark letters on light colours, white on dark ones. */
	static int textColour(int argb) {
		int r = (argb >> 16) & 0xFF;
		int g = (argb >> 8) & 0xFF;
		int b = argb & 0xFF;
		return 0.299 * r + 0.587 * g + 0.114 * b > 160 ? 0xFF101418 : 0xFFFFFFFF;
	}
}
