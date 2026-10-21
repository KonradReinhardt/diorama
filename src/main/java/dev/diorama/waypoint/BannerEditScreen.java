package dev.diorama.waypoint;

import com.mojang.blaze3d.platform.InputConstants;
import dev.diorama.Settings;
import dev.diorama.render.BannerAtlas;
import dev.diorama.render.BannerCompositor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
//? if >=1.21.9 {
import net.minecraft.client.input.KeyEvent;
//?}
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import org.jspecify.annotations.Nullable;

/**
 * Designs a waypoint's banner the way the loom does: a base dye, then patterns stacked in layers,
 * each in its own dye. The big preview waves on its pole; the grid shows every registered
 * pattern, datapacks' and mods' included, already in the chosen dye on the current base. Layers
 * can be reordered, recoloured and removed, with undo and redo. Every control is a button, so
 * Tab, the arrow keys and a controller move a visible focus through them.
 */
public final class BannerEditScreen extends Screen {
	private static final int SWATCH = 10;
	private static final int CELL_W = 16, CELL_H = 32;
	private static final int GRID_COLUMNS = 9;
	private static final int ROW = 12;
	private static final int GOLD = 0xFFE8C060;

	private final @Nullable Screen parent;
	private final Consumer<BannerDesign> done;
	/** The preview and the grid's thumbnails; its texture lives as long as the screen. */
	private final BannerAtlas atlas = new BannerAtlas("diorama loom");
	private final List<BannerDesign> history = new ArrayList<>();
	private int now;
	/** The row being edited: -1 for the base, otherwise a layer. */
	private int selected = -1;
	/** The dye new layers take; also the selected layer's. */
	private BannerDesign.Dye pen;
	private int gridScroll;
	private List<BannerPatternCatalog.Pattern> patterns = List.of();
	/** Widgets by what they do, so focus survives the rebuild after each change. */
	private final Map<AbstractWidget, String> ids = new HashMap<>();
	private final List<Button> gridButtons = new ArrayList<>();
	private final List<BannerPatternCatalog.Pattern> gridPatterns = new ArrayList<>();
	private final List<Button> layerButtons = new ArrayList<>();
	private int left;
	private int top;
	private int previewScale;
	private int gridRows;
	private int listRows;
	private int listScroll;

	/** Focus starts on the pattern grid, for adding a layer. Only for the first layout. */
	private boolean focusPatterns;

	public BannerEditScreen(@Nullable Screen parent, BannerDesign design, Consumer<BannerDesign> done) {
		this(parent, design, done, false);
	}

	/** @param addLayer start with the pattern grid focused, a click away from another layer */
	public BannerEditScreen(@Nullable Screen parent, BannerDesign design, Consumer<BannerDesign> done, boolean addLayer) {
		super(Component.translatable("diorama.banner.title"));
		this.focusPatterns = addLayer;
		this.parent = parent;
		this.done = done;
		this.history.add(design);
		this.pen = design.base() == BannerDesign.Dye.WHITE ? BannerDesign.Dye.BLACK : BannerDesign.Dye.WHITE;
	}

	/** The pattern grid's buttons as shown now, in grid order. For tests. */
	public List<Button> patternButtons() {
		return List.copyOf(this.gridButtons);
	}

	/** How many patterns the grid offers. For tests. */
	public int patternCount() {
		return this.patterns.size();
	}

	/** The design as it stands now. For tests. */
	public BannerDesign design() {
		return this.history.get(this.now);
	}

	private int limit() {
		return Settings.get().extraBannerLayers ? BannerDesign.MAX_LAYERS : BannerDesign.VANILLA_LAYERS;
	}

	@Override
	protected void init() {
		this.ids.clear();
		this.gridButtons.clear();
		this.gridPatterns.clear();
		this.layerButtons.clear();
		this.patterns = BannerPatternCatalog.all();
		BannerDesign design = this.design();
		int width = 70 + 8 + 164 + 8 + GRID_COLUMNS * CELL_W;
		this.left = (this.width - width) / 2;
		this.top = Math.max(20, this.height / 2 - 110);
		int bottom = Math.min(this.height - 28, this.top + 214);
		this.previewScale = Math.max(1, Math.min(4, (bottom - this.top - 8) / BannerCompositor.Size.MAP.height()));

		int bx = this.left + 78;
		for (BannerDesign.Dye dye : BannerDesign.Dye.values()) {
			this.add("base:" + dye.id(), Button.builder(Component.empty(), b -> this.change(this.design().withBase(dye)))
				.bounds(bx + dye.ordinal() * SWATCH, this.top + 10, SWATCH, SWATCH)
				.tooltip(Tooltip.create(Component.translatable("color.minecraft." + dye.id()))).build());
			this.add("pen:" + dye.id(), Button.builder(Component.empty(), b -> this.pick(dye))
				.bounds(bx + dye.ordinal() * SWATCH, this.top + 34, SWATCH, SWATCH)
				.tooltip(Tooltip.create(Component.translatable("color.minecraft." + dye.id()))).build());
		}

		// The layers, the base first, then each layer in the order it is applied.
		int listTop = this.top + 58;
		int controls = bottom - 20;
		this.listRows = Math.max(2, (controls - 2 - listTop) / ROW);
		int rows = design.layers().size() + 1;
		this.listScroll = Math.max(0, Math.min(this.listScroll, rows - this.listRows));
		if (this.selected >= 0) {
			this.listScroll = Math.max(Math.min(this.listScroll, this.selected + 1), this.selected + 2 - this.listRows);
		}
		for (int i = 0; i < this.listRows && this.listScroll + i < rows; i++) {
			int row = this.listScroll + i - 1;
			Component label = row < 0
				? Component.translatable("diorama.banner.base").append(": ").append(Component.translatable("color.minecraft." + design.base().id()))
				: this.layerName(design.layers().get(row));
			Button button = this.add("row:" + row, Button.builder(label, b -> this.select(row))
				.bounds(bx, listTop + i * ROW, 164, ROW).build());
			this.layerButtons.add(button);
		}
		boolean layer = this.selected >= 0;
		this.add("up", Button.builder(Component.literal("▲"), b -> this.move(-1))
			.bounds(bx, controls, 40, 20).tooltip(Tooltip.create(Component.translatable("diorama.banner.up"))).build()).active = layer && this.selected > 0;
		this.add("down", Button.builder(Component.literal("▼"), b -> this.move(1))
			.bounds(bx + 42, controls, 40, 20).tooltip(Tooltip.create(Component.translatable("diorama.banner.down"))).build()).active = layer && this.selected < design.layers().size() - 1;
		this.add("remove", Button.builder(Component.translatable("diorama.banner.remove"), b -> this.remove())
			.bounds(bx + 84, controls, 80, 20).build()).active = layer;

		// The pattern grid.
		int gx = this.left + 250;
		this.gridRows = Math.max(1, (bottom - (this.top + 10)) / CELL_H);
		int total = (this.patterns.size() + GRID_COLUMNS - 1) / GRID_COLUMNS;
		this.gridScroll = Math.max(0, Math.min(this.gridScroll, total - this.gridRows));
		boolean room = design.layers().size() < this.limit();
		for (int i = 0; i < this.gridRows * GRID_COLUMNS; i++) {
			int index = this.gridScroll * GRID_COLUMNS + i;
			if (index >= this.patterns.size()) {
				break;
			}
			BannerPatternCatalog.Pattern pattern = this.patterns.get(index);
			Button button = this.add("pattern:" + pattern.id(), Button.builder(Component.empty(), b -> this.addLayer(pattern))
				.bounds(gx + i % GRID_COLUMNS * CELL_W, this.top + 10 + i / GRID_COLUMNS * CELL_H, CELL_W, CELL_H)
				.tooltip(Tooltip.create(pattern.name(this.pen))).build());
			button.active = room;
			this.gridButtons.add(button);
			this.gridPatterns.add(pattern);
		}

		// Along the bottom.
		int y = bottom + 4;
		int x = this.left;
		this.add("undo", Button.builder(Component.translatable("diorama.banner.undo"), b -> this.undo()).bounds(x, y, 50, 20).build()).active = this.now > 0;
		this.add("redo", Button.builder(Component.translatable("diorama.banner.redo"), b -> this.redo()).bounds(x + 52, y, 50, 20).build()).active = this.now < this.history.size() - 1;
		this.add("random", Button.builder(Component.translatable("diorama.banner.random"), b -> this.randomise()).bounds(x + 104, y, 60, 20).build());
		this.add("held", Button.builder(Component.translatable("diorama.banner.from_held"), b -> this.fromHeld()).bounds(x + 166, y, 102, 20)
			.tooltip(Tooltip.create(Component.translatable("diorama.banner.from_held.tooltip"))).build());
		int end = this.left + width;
		this.add("done", Button.builder(Component.translatable("gui.done"), b -> this.finish()).bounds(end - 124, y, 60, 20).build());
		this.add("cancel", Button.builder(Component.translatable("gui.cancel"), b -> this.onClose()).bounds(end - 62, y, 62, 20).build());

		// Out of room at the loom's six: one click lifts the limit, the same setting as in Settings.
		if (!room && !Settings.get().extraBannerLayers) {
			this.add("more", Button.builder(Component.translatable("diorama.banner.more_layers"), b -> {
				Settings.get().extraBannerLayers = true;
				Settings.get().save();
				this.refresh();
			}).bounds(bx + 100, this.top + 45, 64, 12)
				.tooltip(Tooltip.create(Component.translatable("diorama.settings.extra_banner_layers.tooltip"))).build());
		}
		if (this.focusPatterns && !this.gridButtons.isEmpty()) {
			this.focusPatterns = false;
			this.setInitialFocus(this.gridButtons.getFirst());
		}
	}

	private <T extends AbstractWidget> T add(String id, T widget) {
		this.ids.put(widget, id);
		return this.addRenderableWidget(widget);
	}

	private Component layerName(BannerDesign.Layer layer) {
		for (BannerPatternCatalog.Pattern pattern : this.patterns) {
			if (pattern.id().equals(layer.pattern())) {
				return pattern.name(layer.color());
			}
		}
		// A pattern this world doesn't have: kept, but not drawn.
		return Component.literal(layer.pattern() + " (?)");
	}

	/** Rebuilds the widgets after a change, keeping focus on the same control. */
	private void refresh() {
		GuiEventListener focused = this.getFocused();
		String id = focused instanceof AbstractWidget widget ? this.ids.get(widget) : null;
		this.rebuildWidgets();
		if (id != null) {
			for (Map.Entry<AbstractWidget, String> entry : this.ids.entrySet()) {
				if (entry.getValue().equals(id)) {
					this.setFocused(entry.getKey());
				}
			}
		}
	}

	private void change(BannerDesign next) {
		if (next.equals(this.design())) {
			return;
		}
		while (this.history.size() > this.now + 1) {
			this.history.removeLast();
		}
		this.history.add(next);
		this.now++;
		this.selected = Math.min(this.selected, next.layers().size() - 1);
		this.refresh();
	}

	private void pick(BannerDesign.Dye dye) {
		this.pen = dye;
		if (this.selected >= 0) {
			List<BannerDesign.Layer> layers = new ArrayList<>(this.design().layers());
			layers.set(this.selected, new BannerDesign.Layer(layers.get(this.selected).pattern(), dye));
			this.change(this.design().withLayers(layers));
		} else {
			this.refresh();
		}
	}

	private void select(int row) {
		this.selected = row;
		if (row >= 0) {
			this.pen = this.design().layers().get(row).color();
		}
		this.refresh();
	}

	private void addLayer(BannerPatternCatalog.Pattern pattern) {
		List<BannerDesign.Layer> layers = new ArrayList<>(this.design().layers());
		if (layers.size() >= this.limit()) {
			return;
		}
		layers.add(new BannerDesign.Layer(pattern.id(), this.pen));
		this.selected = layers.size() - 1;
		this.change(this.design().withLayers(layers));
		// The loom's own sounds: picking a pattern, and taking the banner off it.
		dev.diorama.ui.BookWidgets.play(SoundEvents.UI_LOOM_SELECT_PATTERN, 1.0F);
	}

	private void move(int by) {
		List<BannerDesign.Layer> layers = new ArrayList<>(this.design().layers());
		int to = this.selected + by;
		if (this.selected < 0 || to < 0 || to >= layers.size()) {
			return;
		}
		layers.add(to, layers.remove(this.selected));
		this.selected = to;
		this.change(this.design().withLayers(layers));
	}

	private void remove() {
		if (this.selected < 0) {
			return;
		}
		List<BannerDesign.Layer> layers = new ArrayList<>(this.design().layers());
		layers.remove(this.selected);
		this.selected = Math.min(this.selected, layers.size() - 1);
		this.change(this.design().withLayers(layers));
	}

	private void undo() {
		if (this.now > 0) {
			this.now--;
			this.selected = Math.min(this.selected, this.design().layers().size() - 1);
			this.refresh();
		}
	}

	private void redo() {
		if (this.now < this.history.size() - 1) {
			this.now++;
			this.refresh();
		}
	}

	private void randomise() {
		Random random = new Random();
		BannerDesign.Dye[] dyes = BannerDesign.Dye.values();
		BannerDesign.Dye base = dyes[random.nextInt(dyes.length)];
		List<BannerDesign.Layer> layers = new ArrayList<>();
		int count = this.patterns.isEmpty() ? 0 : 1 + random.nextInt(Math.min(4, this.limit()));
		BannerDesign.Dye last = base;
		for (int i = 0; i < count; i++) {
			BannerDesign.Dye dye;
			do {
				dye = dyes[random.nextInt(dyes.length)];
			} while (dye == last);
			last = dye;
			layers.add(new BannerDesign.Layer(this.patterns.get(random.nextInt(this.patterns.size())).id(), dye));
		}
		this.selected = -1;
		this.change(new BannerDesign(base, layers));
	}

	private void fromHeld() {
		BannerDesign held = BannerPatternCatalog.fromInventory();
		if (held != null) {
			this.selected = -1;
			this.change(held);
		} else {
			this.toast = Component.translatable("diorama.banner.no_banner");
			this.toastUntil = System.currentTimeMillis() + 2500;
		}
	}

	private @Nullable Component toast;
	private long toastUntil;

	private void finish() {
		dev.diorama.ui.BookWidgets.play(SoundEvents.UI_LOOM_TAKE_RESULT, 1.0F);
		this.done.accept(this.design());
		this.onClose();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		BannerDesign design = this.design();
		int bx = this.left + 78;
		graphics.centeredText(this.font, this.title, this.width / 2, this.top - 14, 0xFFFFFFFF);
		graphics.text(this.font, Component.translatable("diorama.banner.base"), bx, this.top, 0xFFA0A0A0, true);
		graphics.text(this.font, Component.translatable("diorama.banner.pen"), bx, this.top + 24, 0xFFA0A0A0, true);
		graphics.text(this.font, Component.translatable("diorama.banner.layers", design.layers().size(), this.limit()), bx, this.top + 48, 0xFFA0A0A0, true);
		graphics.text(this.font, Component.translatable("diorama.banner.patterns"), this.left + 250, this.top, 0xFFA0A0A0, true);

		// Swatches over their buttons; a gold frame marks the chosen ones.
		for (BannerDesign.Dye dye : BannerDesign.Dye.values()) {
			int x = bx + dye.ordinal() * SWATCH;
			this.swatch(graphics, x, this.top + 10, dye, dye == design.base());
			this.swatch(graphics, x, this.top + 34, dye, dye == this.pen);
		}
		for (int i = 0; i < this.layerButtons.size(); i++) {
			if (this.listScroll + i - 1 == this.selected) {
				Button row = this.layerButtons.get(i);
				frame(graphics, row.getX(), row.getY(), row.getWidth(), row.getHeight(), GOLD);
			}
		}

		// The preview, waving on its pole.
		int frame = BannerAtlas.frameNow(true);
		float footX = this.left + 35;
		float footY = this.top + 6 + BannerCompositor.Size.MAP.footY() * this.previewScale;
		this.atlas.draw(graphics, design, BannerCompositor.Size.MAP, footX, footY, this.previewScale, frame, false, -1);
		if (!design.fitsVanilla()) {
			graphics.centeredText(this.font, Component.translatable("diorama.banner.not_vanilla"), this.left + 35,
				(int) footY + 2 * this.previewScale + 4, 0xFFE0A040);
		}

		// The patterns, each in the pen's dye on this base; a base of the same dye would hide it.
		BannerDesign.Dye ground = design.base() != this.pen ? design.base() : this.pen == BannerDesign.Dye.WHITE ? BannerDesign.Dye.GRAY : BannerDesign.Dye.WHITE;
		for (int i = 0; i < this.gridButtons.size(); i++) {
			Button button = this.gridButtons.get(i);
			BannerDesign thumbnail = new BannerDesign(ground, List.of(new BannerDesign.Layer(this.gridPatterns.get(i).id(), this.pen)));
			this.atlas.draw(graphics, thumbnail, BannerCompositor.Size.MAP, button.getX() + CELL_W / 2.0F,
				button.getY() + 1 + BannerCompositor.Size.MAP.footY(), 1, BannerCompositor.STILL, false, button.active ? -1 : 0x80FFFFFF);
		}
		if (this.toast != null && System.currentTimeMillis() < this.toastUntil) {
			graphics.centeredText(this.font, this.toast, this.width / 2, this.top + 214 - 8, 0xFFFF8060);
		}
	}

	private void swatch(GuiGraphicsExtractor graphics, int x, int y, BannerDesign.Dye dye, boolean chosen) {
		graphics.fill(x + 1, y + 1, x + SWATCH - 1, y + SWATCH - 1, 0xFF1E140C);
		graphics.fill(x + 2, y + 2, x + SWATCH - 2, y + SWATCH - 2, dye.argb());
		if (chosen) {
			frame(graphics, x, y, SWATCH, SWATCH, GOLD);
		}
	}

	private static void frame(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int argb) {
		graphics.fill(x, y, x + w, y + 1, argb);
		graphics.fill(x, y + h - 1, x + w, y + h, argb);
		graphics.fill(x, y, x + 1, y + h, argb);
		graphics.fill(x + w - 1, y, x + w, y + h, argb);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		int step = (int) -Math.signum(scrollY);
		if (x >= this.left + 250) {
			this.gridScroll = Math.max(0, this.gridScroll + step);
		} else {
			this.listScroll = Math.max(0, this.listScroll + step);
		}
		this.refresh();
		return true;
	}

	//? if >=1.21.9 {
	@Override
	public boolean keyPressed(KeyEvent event) {
		this.modifier(event.key(), true);
		return this.keyDown(event.key(), event.hasControlDown() || this.control, event.hasShiftDown() || this.shift) || super.keyPressed(event);
	}

	@Override
	public boolean keyReleased(KeyEvent event) {
		this.modifier(event.key(), false);
		return super.keyReleased(event);
	}
	//?} else {
	/*@Override
	public boolean keyPressed(int key, int scancode, int modifiers) {
		this.modifier(key, true);
		return this.keyDown(key, Screen.hasControlDown() || this.control, Screen.hasShiftDown() || this.shift) || super.keyPressed(key, scancode, modifiers);
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

	/**
	 * Ctrl+Z and Ctrl+Y (or Ctrl+Shift+Z) undo and redo. Moving the focus off the grid's top or
	 * bottom row scrolls the grid instead, so the arrow keys reach every pattern.
	 */
	private boolean keyDown(int key, boolean control, boolean shift) {
		if (control && key == InputConstants.KEY_Z) {
			if (shift) {
				this.redo();
			} else {
				this.undo();
			}
			return true;
		}
		if (control && key == InputConstants.KEY_Y) {
			this.redo();
			return true;
		}
		int at = this.getFocused() instanceof Button button ? this.gridButtons.indexOf(button) : -1;
		if (at >= 0 && (key == InputConstants.KEY_DOWN || key == InputConstants.KEY_UP)) {
			boolean down = key == InputConstants.KEY_DOWN;
			int row = at / GRID_COLUMNS;
			int total = (this.patterns.size() + GRID_COLUMNS - 1) / GRID_COLUMNS;
			if (down && row == this.gridRows - 1 && this.gridScroll + this.gridRows < total
				|| !down && row == 0 && this.gridScroll > 0) {
				this.gridScroll += down ? 1 : -1;
				this.refresh();
				return true;
			}
		}
		return false;
	}

	@Override
	public void removed() {
		this.atlas.close();
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
