package dev.diorama.waypoint;

import com.mojang.blaze3d.platform.InputConstants;
//? if >=1.21.9 {
import com.mojang.blaze3d.platform.cursor.CursorTypes;
//?}
import dev.diorama.render.BannerAtlas;
import dev.diorama.render.BannerCompositor;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.gui.screens.Screen;
//? if >=1.21.9 {
import net.minecraft.client.input.MouseButtonEvent;
//?}
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import org.jspecify.annotations.Nullable;

/**
 * Creates or edits one waypoint: name, position, its banner and whether it shows. The
 * swatches set the banner's base; the loom-style {@link BannerEditScreen} does the rest.
 */
public final class WaypointEditScreen extends Screen {
	private static final int SWATCH = 16;
	private static final int SWATCH_GAP = 4;
	/** Vanilla's colour for a number field it can't read, from the game rules screen. */
	private static final int INVALID_TEXT = 0xFFFF0000;

	private final @Nullable Screen parent;
	private final Waypoint waypoint;
	private final boolean isNew;
	private EditBox name;
	private EditBox x;
	private EditBox y;
	private EditBox z;
	private @Nullable Button save;
	private @Nullable Button share;
	private @Nullable Button teleport;
	private BannerDesign banner;
	private boolean visible;
	private int swatchX;
	private int swatchY;

	public WaypointEditScreen(@Nullable Screen parent, Waypoint waypoint, boolean isNew) {
		super(Component.translatable(isNew ? "diorama.waypoint.new" : "diorama.waypoint.edit"));
		this.parent = parent;
		this.waypoint = waypoint;
		this.isNew = isNew;
		this.banner = waypoint.design();
		this.visible = waypoint.visible;
	}

	@Override
	protected void init() {
		int cx = this.width / 2;
		int top = this.top();
		// Resizing the window or changing GUI scale runs init() again: keep what's typed, not the saved waypoint.
		this.name = this.field(cx - 100, top + 16, 200, draft(this.name, this.waypoint.name), "diorama.waypoint.name", 32);
		this.x = this.field(cx - 100, top + 56, 64, draft(this.x, Integer.toString(this.waypoint.x)), "diorama.waypoint.x", 9);
		this.y = this.field(cx - 32, top + 56, 64, draft(this.y, Integer.toString(this.waypoint.y)), "diorama.waypoint.y", 9);
		this.z = this.field(cx + 36, top + 56, 64, draft(this.z, Integer.toString(this.waypoint.z)), "diorama.waypoint.z", 9);
		this.x.setResponder(text -> this.validate());
		this.y.setResponder(text -> this.validate());
		this.z.setResponder(text -> this.validate());
		this.swatchX = cx - 100 + 24;
		this.swatchY = top + 92;
		this.addRenderableWidget(Button.builder(Component.translatable("diorama.banner.edit"), b ->
			this.minecraft.gui.setScreen(new BannerEditScreen(this, this.banner, design -> this.banner = design)))
			.bounds(cx - 100, top + 140, 98, 20).build());
		this.addRenderableWidget(Button.builder(this.visibilityLabel(), b -> {
			this.visible = !this.visible;
			b.setMessage(this.visibilityLabel());
		}).bounds(cx + 2, top + 140, 98, 20).build());
		int buttons = top + 168;
		this.save = this.addRenderableWidget(Button.builder(Component.translatable("diorama.waypoint.save"), b -> this.save()).bounds(cx - 100, buttons, 64, 20).build());
		// A new waypoint isn't saved yet: deleting it just throws it away.
		this.addRenderableWidget(Button.builder(Component.translatable("diorama.waypoint.delete"), b -> this.delete()).bounds(cx - 32, buttons, 64, 20).build());
		this.addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> this.onClose()).bounds(cx + 36, buttons, 64, 20).build());
		if (!this.isNew) {
			this.share = this.addRenderableWidget(Button.builder(Component.translatable("diorama.waypoint.share"), b -> this.share()).bounds(cx - 100, buttons + 24, 98, 20).build());
			this.teleport = this.addRenderableWidget(Button.builder(Component.translatable("diorama.waypoint.teleport"), b -> this.teleport()).bounds(cx + 2, buttons + 24, 98, 20).build());
		}
		this.validate();
		this.setInitialFocus(this.name);
	}

	private static String draft(@Nullable EditBox box, String saved) {
		return box != null ? box.getValue() : saved;
	}

	/**
	 * Colours a coordinate that isn't a whole number red, the way vanilla's game rules screen does,
	 * and keeps Save, Share and Teleport off until every coordinate reads.
	 */
	private void validate() {
		boolean valid = true;
		for (EditBox box : new EditBox[] {this.x, this.y, this.z}) {
			boolean reads = parse(box.getValue()) != null;
			box.setTextColor(reads ? EditBox.DEFAULT_TEXT_COLOR : INVALID_TEXT);
			valid &= reads;
		}
		if (this.save != null) {
			this.save.active = valid;
		}
		if (this.share != null) {
			this.share.active = valid;
		}
		if (this.teleport != null) {
			this.teleport.active = valid && canTeleport();
		}
	}

	private EditBox field(int x, int y, int width, String value, String hint, int maxLength) {
		EditBox box = new EditBox(this.font, x, y, width, 20, Component.translatable(hint));
		box.setMaxLength(maxLength);
		box.setValue(value);
		box.setHint(Component.translatable(hint));
		return this.addRenderableWidget(box);
	}

	private Component visibilityLabel() {
		return Component.translatable(this.visible ? "diorama.waypoint.shown" : "diorama.waypoint.hidden");
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		int cx = this.width / 2;
		int top = this.top();
		graphics.centeredText(this.font, this.title, cx, top - 12, 0xFFFFFFFF);
		graphics.text(this.font, Component.translatable("diorama.waypoint.name"), cx - 100, top + 5, 0xFFA0A0A0, true);
		graphics.text(this.font, Component.translatable("diorama.waypoint.position"), cx - 100, top + 45, 0xFFA0A0A0, true);
		graphics.text(this.font, Component.translatable("diorama.waypoint.banner"), cx - 100, top + 81, 0xFFA0A0A0, true);
		// The banner as it will stand on the map, then its base dyes.
		BannerAtlas.shared().draw(graphics, this.banner, BannerCompositor.Size.MAP, cx - 90, this.swatchY + BannerCompositor.Size.MAP.footY() + 2, 1,
			BannerAtlas.frameNow(dev.diorama.Settings.get().animateBanners), false, -1);
		BannerDesign.Dye[] dyes = BannerDesign.Dye.values();
		for (int i = 0; i < dyes.length; i++) {
			int sx = this.swatchX + (i % 8) * (SWATCH + SWATCH_GAP);
			int sy = this.swatchY + (i / 8) * (SWATCH + SWATCH_GAP);
			if (dyes[i] == this.banner.base()) {
				graphics.fill(sx - 2, sy - 2, sx + SWATCH + 2, sy + SWATCH + 2, 0xFFFFFFFF);
			}
			graphics.fill(sx - 1, sy - 1, sx + SWATCH + 1, sy + SWATCH + 1, 0xFF1E140C);
			graphics.fill(sx, sy, sx + SWATCH, sy + SWATCH, dyes[i].argb());
		}
		//? if >=1.21.9 {
		if (this.swatchAt(mouseX, mouseY) >= 0) {
			graphics.requestCursor(CursorTypes.POINTING_HAND);
		}
		//?}
	}

	// Mouse events came as separate numbers before 1.21.9.
	//? if >=1.21.9 {
	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		return this.clicked(event.x(), event.y(), event.button()) || super.mouseClicked(event, doubleClick);
	}
	//?} else {
	/*@Override
	public boolean mouseClicked(double x, double y, int button) {
		return this.clicked(x, y, button) || super.mouseClicked(x, y, button);
	}
	*///?}

	/** Picks the colour swatch under a left click, with a button's click; false if the click wasn't on one. */
	private boolean clicked(double x, double y, int button) {
		int swatch = button == InputConstants.MOUSE_BUTTON_LEFT ? this.swatchAt(x, y) : -1;
		if (swatch < 0) {
			return false;
		}
		this.banner = this.banner.withBase(BannerDesign.Dye.values()[swatch]);
		this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
		return true;
	}

	/** The dye of the swatch under a point, as an index into {@link BannerDesign.Dye#values()}, or -1. */
	private int swatchAt(double x, double y) {
		int count = BannerDesign.Dye.values().length;
		for (int i = 0; i < count; i++) {
			int sx = this.swatchX + (i % 8) * (SWATCH + SWATCH_GAP);
			int sy = this.swatchY + (i / 8) * (SWATCH + SWATCH_GAP);
			if (x >= sx && x < sx + SWATCH && y >= sy && y < sy + SWATCH) {
				return i;
			}
		}
		return -1;
	}

	/** The colour kept beside the banner, for older versions: the old colour while the base dye is still its nearest. */
	private int colour() {
		return BannerDesign.Dye.nearest(this.waypoint.color) == this.banner.base() ? this.waypoint.color : this.banner.colour();
	}

	/** The banner as edited so far. For tests. */
	public BannerDesign banner() {
		return this.banner;
	}

	/** The waypoint being edited. For tests. */
	public Waypoint waypoint() {
		return this.waypoint;
	}

	/** The waypoint as the fields show it now, edits included, without saving; null while a coordinate isn't a number. */
	private @Nullable Waypoint shown() {
		Integer x = parse(this.x.getValue());
		Integer y = parse(this.y.getValue());
		Integer z = parse(this.z.getValue());
		if (x == null || y == null || z == null) {
			return null;
		}
		String name = this.name.getValue().trim();
		Waypoint shown = new Waypoint(name.isEmpty() ? this.waypoint.name : name, x, y, z, this.waypoint.dimension, this.colour());
		shown.banner = this.banner;
		shown.kind = this.waypoint.kind;
		return shown;
	}

	/** Sends the waypoint to chat for others to add. */
	private void share() {
		Waypoint shown = this.shown();
		if (shown != null && shareInChat(shown)) {
			this.onClose();
		}
	}

	/** Sends a waypoint to chat for others to add; false, with a note in chat, if it can't be. */
	public static boolean shareInChat(Waypoint waypoint) {
		net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
		java.util.Optional<String> text = WaypointShare.format(waypoint);
		if (text.isEmpty()) {
			//? if >=26.1 {
			mc.gui.hud.getChat().addClientSystemMessage(Component.translatable("diorama.share.too_long"));
			//?} else {
			/*mc.gui.getChat().addMessage(Component.translatable("diorama.share.too_long"));
			*///?}
			return false;
		}
		if (mc.player != null) {
			mc.player.connection.sendChat(text.get());
		}
		return true;
	}

	private void teleport() {
		Waypoint shown = this.shown();
		if (shown == null) {
			return;
		}
		if (this.minecraft.player != null) {
			this.minecraft.player.connection.sendCommand("tp @s " + shown.x + " " + shown.y + " " + shown.z);
		}
		this.onClose();
	}

	/** The server only sends the commands a player may use, so {@code tp} being there means it's allowed. */
	public static boolean canTeleport() {
		return commandAllowed("tp");
	}

	/** Whether the server lets this player use a command: it only sends the ones they may. */
	public static boolean commandAllowed(String name) {
		var connection = net.minecraft.client.Minecraft.getInstance().getConnection();
		return connection != null && connection.getCommands().getRoot().getChild(name) != null;
	}

	private void save() {
		Waypoint shown = this.shown();
		if (shown == null) {
			return;
		}
		WaypointStore store = WaypointStore.get();
		if (store == null) {
			this.onClose();
			return;
		}
		this.waypoint.name = shown.name;
		// Banners replaced initials: the ones kept for sharing follow the name from now on.
		this.waypoint.initials = "";
		this.waypoint.x = shown.x;
		this.waypoint.y = shown.y;
		this.waypoint.z = shown.z;
		// A banner left as it was isn't written, so older waypoints stay as they were saved.
		if (!this.banner.equals(this.waypoint.design())) {
			this.waypoint.banner = this.banner;
			this.waypoint.color = this.colour();
		}
		this.waypoint.visible = this.visible;
		if (this.isNew) {
			store.add(this.waypoint);
		} else {
			store.changed();
		}
		this.onClose();
	}

	private void delete() {
		WaypointStore store = WaypointStore.get();
		if (store != null && !this.isNew) {
			store.remove(this.waypoint);
		}
		this.onClose();
	}

	/** The form's top, kept on screen with its last row at small window sizes and large GUI scales. */
	private int top() {
		return Math.max(18, Math.min(this.height / 2 - 96, this.height - 216));
	}

	private static @Nullable Integer parse(String text) {
		try {
			return Integer.parseInt(text.trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
