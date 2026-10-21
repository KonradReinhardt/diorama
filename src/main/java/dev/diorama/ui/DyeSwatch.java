package dev.diorama.ui;

import dev.diorama.waypoint.BannerDesign;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/**
 * One of the sixteen dyes as a small square of colour in an ink frame, framed in gold when it is
 * the one picked: the region tools' dyes and a region's on its page.
 */
final class DyeSwatch extends BookWidget {
	/** Pixels across. */
	static final int SIZE = 9;

	private final BannerDesign.Dye dye;
	private final Supplier<BannerDesign.Dye> picked;
	private final Consumer<BannerDesign.Dye> pick;

	DyeSwatch(int x, int y, BannerDesign.Dye dye, Supplier<BannerDesign.Dye> picked, Consumer<BannerDesign.Dye> pick) {
		super(x, y, SIZE, SIZE, Component.translatable("color.minecraft." + dye.id()));
		this.dye = dye;
		this.picked = picked;
		this.pick = pick;
		this.setTooltip(Tooltip.create(this.getMessage()));
	}

	@Override
	protected void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean lit) {
		int x = this.getX(), y = this.getY();
		boolean on = this.picked.get() == this.dye;
		int colour = BookWidgets.fade(this.dye.argb(), this.alpha);
		graphics.fill(x, y, x + SIZE, y + SIZE, BookWidgets.fade(on ? 0xFFE8B83A : lit ? BookSprites.INK_SOFT : BookSprites.INK, this.alpha));
		graphics.fill(x + 1, y + 1, x + SIZE - 1, y + SIZE - 1, on ? BookWidgets.fade(BookSprites.INK, this.alpha) : colour);
		if (on) {
			graphics.fill(x + 2, y + 2, x + SIZE - 2, y + SIZE - 2, colour);
		}
	}

	@Override
	protected void press(double x, double y) {
		this.pick.accept(this.dye);
	}

	/** The sixteen dyes in two rows of eight from (x, y), added through {@code add}. */
	static void palette(int x, int y, Supplier<BannerDesign.Dye> picked, Consumer<BannerDesign.Dye> pick, Consumer<DyeSwatch> add) {
		BannerDesign.Dye[] dyes = BannerDesign.Dye.values();
		for (int i = 0; i < dyes.length; i++) {
			add.accept(new DyeSwatch(x + (i % 8) * SIZE, y + (i / 8) * SIZE, dyes[i], picked, pick));
		}
	}
}
