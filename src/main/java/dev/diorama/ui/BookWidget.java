package dev.diorama.ui;

import com.mojang.blaze3d.platform.InputConstants;
//? if >=1.21.9 {
import com.mojang.blaze3d.platform.cursor.CursorTypes;
//?}
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
//? if >=1.21.9 {
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
//?}
import net.minecraft.network.chat.Component;

/**
 * A control drawn in the book's own pixel art, kept a vanilla widget so Tab, the arrow keys and
 * controllers move focus to it, Enter and Space press it and the narrator reads it. The ways
 * widgets draw and take clicks and keys changed between versions; this is where that is handled.
 */
public abstract class BookWidget extends AbstractWidget {
	protected BookWidget(int x, int y, int width, int height, Component message) {
		super(x, y, width, height, message);
	}

	/** Draws the widget; {@code lit} when the mouse is over it or it has the keyboard's focus. */
	protected abstract void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean lit);

	/** Pressed, by a click at (x, y) or, with NaN, by the keyboard. */
	protected abstract void press(double x, double y);

	/** Whether a click on it does something, so the pointer turns to a hand over it. */
	protected boolean clickable() {
		return true;
	}

	//? if >=26.1 {
	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		this.draw(graphics, mouseX, mouseY, this.isHoveredOrFocused() && this.active);
		this.pointer(graphics);
	}
	//?} else {
	/*@Override
	protected void renderWidget(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		this.draw(graphics, mouseX, mouseY, this.isHoveredOrFocused() && this.active);
		this.pointer(graphics);
	}
	*///?}

	/** A hand over a control that can be pressed, "not allowed" over one switched off, as vanilla's buttons do. */
	private void pointer(GuiGraphicsExtractor graphics) {
		//? if >=1.21.9 {
		if (this.isHovered() && this.clickable()) {
			graphics.requestCursor(this.active ? CursorTypes.POINTING_HAND : CursorTypes.NOT_ALLOWED);
		}
		//?}
	}

	//? if >=1.21.9 {
	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		this.press(event.x(), event.y());
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		return this.keyDown(event.key());
	}
	//?} else {
	/*@Override
	public void onClick(double x, double y) {
		this.press(x, y);
	}

	@Override
	public boolean keyPressed(int key, int scancode, int modifiers) {
		return this.keyDown(key);
	}
	*///?}

	private boolean keyDown(int key) {
		if (!this.active || !this.visible) {
			return false;
		}
		if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_SPACE || key == InputConstants.KEY_NUMPADENTER) {
			this.playDownSound(Minecraft.getInstance().getSoundManager());
			this.press(Double.NaN, Double.NaN);
			return true;
		}
		return this.arrow(key);
	}

	/** Left and right arrows, for controls that step through values; false leaves them to focus moves. */
	protected boolean arrow(int key) {
		return false;
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		this.defaultButtonNarrationText(output);
	}
}
