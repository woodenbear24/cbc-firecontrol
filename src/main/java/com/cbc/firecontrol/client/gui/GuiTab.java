package com.cbc.firecontrol.client.gui;

import java.util.List;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.Component;

/**
 * One page of a tabbed Create-style console.
 *
 * <p>A page owns four separate things, and the base screen calls each of them at the point in the render pass
 * where that layer exists:
 * <ol>
 * <li>{@link #widgets} - interactive controls belonging to this page alone. They are added when the page becomes
 *     active and removed when it leaves, which is what stops a hidden text field from taking keyboard focus or a
 *     hidden button from being clickable.</li>
 * <li>{@link #renderBackground} - flat fills, drawn before the slots and clipped to the content area, so a page
 *     cannot bleed into the strip above it.</li>
 * <li>{@link #renderLabels} - panel-local text, drawn after the slots inside the translated pose.</li>
 * <li>{@link #renderForeground} - screen-space text and tooltips, drawn last and NOT clipped, because a tooltip is
 *     expected to leave the panel.</li>
 * </ol>
 *
 * <p>Every coordinate handed to a page is absolute window space except {@link #renderLabels}, which matches
 * {@code AbstractContainerScreen#renderLabels} and is therefore relative to the panel's top-left corner. The
 * distinction is vanilla's own contract; getting it wrong is the classic cause of text drifting by exactly the
 * panel offset.
 */
public abstract class GuiTab {

	/** Tail marker {@link #fit} appends when a line has to be shortened to its column. */
	protected static final String TRUNCATION_MARK = "...";

	private final int id;
	private final String titleKey;

	protected GuiTab(int id, String titleKey) {
		this.id = id;
		this.titleKey = titleKey;
	}

	public final int id() {
		return id;
	}

	/** Label of this page in the strip. */
	public final Component title() {
		return Component.translatable(titleKey);
	}

	/**
	 * Controls owned by this page. The base screen adds and removes them as the page is entered and left, which is
	 * what stops a hidden text field from taking keyboard focus or a hidden button from being clickable at
	 * coordinates the next page uses for something else. A page's controls are widgets - vanilla's widget base
	 * already carries the listener, renderable and narratable roles the screen needs.
	 */
	public List<? extends GuiEventListener> widgets(TabbedSimiContainerScreen<?> screen) {
		return List.of();
	}

	/** Fills inside the content area, in absolute window coordinates. */
	public void renderBackground(TabbedSimiContainerScreen<?> screen, GuiGraphics graphics) {
	}

	/** Text inside the panel, in panel-local coordinates (the pose is already translated by the panel position). */
	public void renderLabels(TabbedSimiContainerScreen<?> screen, GuiGraphics graphics, int mouseX, int mouseY) {
	}

	/** Screen-space overlays and tooltips, drawn after everything else and outside the clip. */
	public void renderForeground(TabbedSimiContainerScreen<?> screen, GuiGraphics graphics, int mouseX, int mouseY,
			float partialTicks) {
	}

	/**
	 * Called from the screen's {@code containerTick} while this page is the visible one. Live values that follow
	 * the server belong here rather than in the render pass: a tick is where state is polled, a frame is where it
	 * is drawn.
	 */
	public void tick(TabbedSimiContainerScreen<?> screen) {
	}

	/**
	 * Give the page first refusal on a key press while it is visible. Enter in one of the page's own fields is the
	 * case this exists for: a container screen sees every key first, and vanilla's hotbar-swap handling would
	 * otherwise eat it.
	 */
	public boolean keyPressed(TabbedSimiContainerScreen<?> screen, int keyCode, int scanCode, int modifiers) {
		return false;
	}

	/**
	 * Shorten a line to the width its column actually has rather than let it print over the next one. A long
	 * translation must not spill across a slot grid or past the panel edge; what gets dropped is safe because the
	 * tooltip under the same row carries the unwrapped text.
	 */
	protected static Component fit(Font font, Component text, int width) {
		if (font.width(text) <= width)
			return text;
		String full = text.getString();
		int budget = width - font.width(TRUNCATION_MARK);
		int keep = 0;
		while (keep < full.length() && font.width(full.substring(0, keep + 1)) <= budget)
			keep++;
		return Component.literal(full.substring(0, Math.max(1, keep)) + TRUNCATION_MARK);
	}

	protected static boolean isInside(int x, int y, int left, int top, int width, int height) {
		return x >= left && x < left + width && y >= top && y < top + height;
	}
}
