package dev.diorama;

import dev.diorama.ui.SettingsCatalog;
import dev.diorama.ui.SettingsCatalog.Section;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Every setting as a button that cycles its value, built from {@link SettingsCatalog}: in three
 * columns, Minimap, Radar with Overlays, Banners and Regions, and Waypoints with Look (four, with Regions beside
 * Waypoints, on screens too short for three); or, given sections,
 * only those, in one column (the world map's book sends the minimap's settings here).
 */
public final class SettingsScreen extends Screen {
	private static final int MAX_BUTTON_WIDTH = 158;
	/** The full screen's columns, left to right. */
	private static final Section[][] COLUMNS = {
		{Section.MINIMAP},
		{Section.RADAR, Section.OVERLAYS, Section.BANNERS, Section.REGIONS},
		{Section.WAYPOINTS, Section.LOOK},
	};
	/** The same in four columns, for screens too short for three. */
	private static final Section[][] FOUR_COLUMNS = {
		{Section.MINIMAP},
		{Section.RADAR, Section.OVERLAYS, Section.BANNERS},
		{Section.WAYPOINTS, Section.REGIONS},
		{Section.LOOK},
	};
	/** Rows no shorter than this, or the buttons' text won't fit. */
	private static final int SMALLEST_ROW = 10;

	/** Sized in init() to fit the screen: at 427x240 the full-size layout would run off it. */
	private int buttonWidth = MAX_BUTTON_WIDTH;
	private int row = 22;
	private int buttonHeight = 20;

	private final @Nullable Screen parent;
	private final Section[][] columns;
	/** The columns laid out: {@link #columns}, or four of them where three don't fit. */
	private Section[][] laid;
	private final List<Header> headers = new ArrayList<>();

	private record Header(Component text, int x, int y) {
	}

	public SettingsScreen(@Nullable Screen parent) {
		this(parent, COLUMNS, Component.translatable("diorama.settings.title"));
	}

	/** Only the settings of {@code sections}, in one column. */
	public SettingsScreen(@Nullable Screen parent, Section... sections) {
		this(parent, new Section[][] {sections}, Component.translatable("diorama.settings.title_" + sections[0].name().toLowerCase(java.util.Locale.ROOT)));
	}

	private SettingsScreen(@Nullable Screen parent, Section[][] columns, Component title) {
		super(title);
		this.parent = parent;
		this.columns = columns;
		this.laid = columns;
	}

	@Override
	protected void init() {
		this.headers.clear();
		Settings s = Settings.get();
		int gap = 6;
		this.laid = this.columns;
		int fullRow = this.rowFor(s, this.laid);
		if (fullRow < SMALLEST_ROW && this.columns == COLUMNS) {
			this.laid = FOUR_COLUMNS;
			fullRow = this.rowFor(s, this.laid);
		}
		int count = this.laid.length;
		this.buttonWidth = Math.min(MAX_BUTTON_WIDTH, (this.width - (count - 1) * gap - 16) / count);
		boolean compact = fullRow < 22;
		this.row = compact ? Math.min(19, Math.max(SMALLEST_ROW, fullRow)) : 22;
		this.buttonHeight = compact ? this.row - 1 : 20;
		int left = this.width / 2 - (this.buttonWidth * count + gap * (count - 1)) / 2;
		int top = compact ? 22 : 30;
		for (int c = 0; c < count; c++) {
			int x = left + c * (this.buttonWidth + gap);
			int y = top;
			for (Section section : this.laid[c]) {
				y = this.header(section.key, x, y == top ? y : y + 4);
				for (SettingsCatalog.Entry entry : shown(s, section)) {
					y = this.cycle(x, y, entry);
				}
			}
		}

		this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> this.onClose())
			.bounds(this.width / 2 - 100, this.height - 26, 200, 20).build());
	}

	/**
	 * The row height every column of a layout fits with above Done: 22, a full button row, where
	 * there is room; else what the tallest column (its rows, headers and the gaps between them)
	 * leaves, which may be under {@link #SMALLEST_ROW}.
	 */
	private int rowFor(Settings s, Section[][] columns) {
		int row = 22;
		for (Section[] column : columns) {
			int rows = 0;
			for (Section section : column) {
				rows += shown(s, section).size();
			}
			if (rows == 0) {
				continue;
			}
			int heading = column.length * 16 + (column.length - 1) * 4;
			if (30 + heading + rows * 22 > this.height - 30) {
				row = Math.min(row, (this.height - 22 - heading - 30) / rows);
			}
		}
		return row;
	}

	private static List<SettingsCatalog.Entry> shown(Settings s, Section section) {
		List<SettingsCatalog.Entry> out = new ArrayList<>();
		for (SettingsCatalog.Entry entry : SettingsCatalog.in(s, section)) {
			if (entry.shown().getAsBoolean()) {
				out.add(entry);
			}
		}
		return out;
	}

	private int header(String key, int x, int y) {
		this.headers.add(new Header(Component.translatable(key), x, y + 5));
		return y + 16;
	}

	private int cycle(int x, int y, SettingsCatalog.Entry entry) {
		Component label = Component.translatable(entry.key());
		Button button = Button.builder(label(label, valueName(entry, entry.value())), b -> {
			entry.choose(entry.value() + 1);
			Settings.get().save();
			b.setMessage(label(label, valueName(entry, entry.value())));
		}).bounds(x, y, this.buttonWidth, this.buttonHeight).build();
		if (entry.tooltip()) {
			button.setTooltip(Tooltip.create(Component.translatable(entry.tooltipKey())));
		}
		this.addRenderableWidget(button);
		return y + this.row;
	}

	/** The name of value {@code i} of a setting. */
	public static Component valueName(SettingsCatalog.Entry entry, int i) {
		String value = entry.values()[i];
		return entry.literal() ? Component.literal(value) : Component.translatable(value);
	}

	private static Component label(Component name, Component value) {
		return Component.empty().append(name).append(": ").append(value);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.centeredText(this.font, this.title, this.width / 2, 8, 0xFFFFFFFF);
		for (Header header : this.headers) {
			graphics.text(this.font, header.text(), header.x(), header.y(), 0xFFFFD870, true);
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
