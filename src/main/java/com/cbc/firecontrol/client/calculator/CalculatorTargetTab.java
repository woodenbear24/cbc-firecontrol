package com.cbc.firecontrol.client.calculator;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.cbc.firecontrol.block.entity.TrajectoryCalculatorBlockEntity;
import com.cbc.firecontrol.client.TrajectoryCalculatorScreen;
import com.cbc.firecontrol.client.gui.ConsoleTheme;
import com.cbc.firecontrol.menu.TrajectoryCalculatorMenu;
import com.cbc.firecontrol.network.CalculatorAimPayload;
import com.cbc.firecontrol.network.CalculatorSettingsPayload;
import com.cbc.firecontrol.network.CalculatorTargetPayload;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.Component;

import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The target page: the absolute world coordinate the console solves for, entered as three numbers and committed as
 * one order.
 *
 * <p>The entered target goes to the server once per commit rather than per keystroke: a {@link
 * CalculatorTargetPayload} is sent on Enter in a field or on SET, and the server writes it into the block entity,
 * which recomputes and syncs every solution back. Fields are vanilla {@link EditBox}es - Create has no text widget
 * of its own, but {@code AbstractSimiContainerScreen#keyPressed} routes every key (digits included) to a focused
 * EditBox instead of letting vanilla's hotbar-swap handling eat it, which is what makes a numeric field usable
 * inside a container menu screen.
 *
 * <p>The widgets are built when the page is entered, not once at construction: their bounds are window-absolute,
 * so a resized window would leave them at the old panel position otherwise. Re-seeding from the block entity on
 * entry is also what makes reopening the console never suggest a new shot.
 *
 * <p>The page also carries the fire-mission wait window - the one setting the console exposes, how long a mount
 * may hold an order it cannot take yet - in a second strip under the target entry. It belongs here rather than on
 * a page of its own because it answers the same question the rows below the target do: what becomes of an order
 * once this console has solved it. One number alone did not justify a sixth page, and a page that held only it
 * left that number's effect a click away from the readouts that show it happening.
 */
public class CalculatorTargetTab extends CalculatorTab {

	/** Optional sign, up to 12 integer digits, at most three decimals: a block coordinate, not a formula. */
	private static final Pattern COORDINATE = Pattern.compile("-?\\d{0,12}(\\.\\d{0,3})?");
	/** Whole seconds, at most two digits: the server clamps the value, so the field only needs to be numeric. */
	private static final Pattern SECONDS = Pattern.compile("\\d{0,2}");

	private EditBox targetX;
	private EditBox targetY;
	private EditBox targetZ;
	private Button setButton;
	private Button aimButton;
	/** Parse state of the three fields, recomputed on every keystroke; drives text colour and SET state. */
	private boolean fieldsValid;
	/** The wait window of the block below the strip, and its own parse state. */
	private EditBox waitField;
	private Button waitSetButton;
	private boolean waitValid;

	public CalculatorTargetTab() {
		super(TrajectoryCalculatorMenu.TAB_TARGET, "cbc_firecontrol.calculator.tab.target");
	}

	@Override
	protected List<? extends GuiEventListener> controls(TrajectoryCalculatorScreen screen) {
		int stripY = screen.panelTop() + TrajectoryCalculatorMenu.TARGET_STRIP_Y;
		targetX = numberField(screen, TrajectoryCalculatorMenu.TARGET_X_FIELD_X, stripY,
				"cbc_firecontrol.calculator.target.x");
		targetY = numberField(screen, TrajectoryCalculatorMenu.TARGET_Y_FIELD_X, stripY,
				"cbc_firecontrol.calculator.target.y");
		targetZ = numberField(screen, TrajectoryCalculatorMenu.TARGET_Z_FIELD_X, stripY,
				"cbc_firecontrol.calculator.target.z");
		seedFieldsFromCalculator(screen);

		int setWidth = TrajectoryCalculatorMenu.TARGET_CLEAR_X - TrajectoryCalculatorMenu.TARGET_SET_X - 4;
		int clearWidth = TrajectoryCalculatorMenu.TARGET_AIM_X - TrajectoryCalculatorMenu.TARGET_CLEAR_X - 4;
		int aimWidth = TrajectoryCalculatorMenu.TARGET_ECHO_X - TrajectoryCalculatorMenu.TARGET_AIM_X - 4;
		setButton = Button.builder(Component.translatable("cbc_firecontrol.calculator.target.set"),
				button -> commitTarget(screen))
				.bounds(screen.panelLeft() + TrajectoryCalculatorMenu.TARGET_SET_X, stripY, setWidth,
						TrajectoryCalculatorMenu.TARGET_FIELD_H)
				.tooltip(Tooltip.create(Component.translatable("cbc_firecontrol.calculator.target.set_hint")))
				.build();
		Button clearButton = Button.builder(Component.translatable("cbc_firecontrol.calculator.target.clear"),
				button -> sendClear(screen))
				.bounds(screen.panelLeft() + TrajectoryCalculatorMenu.TARGET_CLEAR_X, stripY, clearWidth,
						TrajectoryCalculatorMenu.TARGET_FIELD_H)
				.tooltip(Tooltip.create(Component.translatable("cbc_firecontrol.calculator.target.clear_hint")))
				.build();
		aimButton = Button.builder(Component.translatable("cbc_firecontrol.calculator.target.aim"),
				button -> sendAim(screen))
				.bounds(screen.panelLeft() + TrajectoryCalculatorMenu.TARGET_AIM_X, stripY, aimWidth,
						TrajectoryCalculatorMenu.TARGET_FIELD_H)
				.tooltip(Tooltip.create(Component.translatable("cbc_firecontrol.calculator.target.aim_hint")))
				.build();

		/*
		 * The second block, built exactly like the first: one field, one commit button, its own parse state.
		 * Its tooltip carries the server's bounds because the server clamps what this field can ask for.
		 */
		int waitY = screen.panelTop() + TrajectoryCalculatorMenu.TARGET_WAIT_STRIP_Y;
		waitField = new EditBox(screen.consoleFont(),
				screen.panelLeft() + TrajectoryCalculatorMenu.TARGET_WAIT_FIELD_X, waitY,
				TrajectoryCalculatorMenu.TARGET_WAIT_FIELD_W, TrajectoryCalculatorMenu.TARGET_FIELD_H,
				Component.translatable("cbc_firecontrol.calculator.settings.wait"));
		waitField.setFilter(text -> text.isEmpty() || SECONDS.matcher(text).matches());
		waitField.setMaxLength(2);
		waitField.setTextColor(ConsoleTheme.FIELD_TEXT);
		waitField.setResponder(ignored -> refreshWaitValidity());
		waitField.setTooltip(Tooltip.create(Component.translatable(
				"cbc_firecontrol.calculator.settings.wait_hint",
				TrajectoryCalculatorBlockEntity.MIN_MISSION_WAIT_SECONDS,
				TrajectoryCalculatorBlockEntity.MAX_MISSION_WAIT_SECONDS)));
		seedWaitFromCalculator(screen);
		waitSetButton = Button.builder(Component.translatable("cbc_firecontrol.calculator.settings.set"),
				button -> commitWait(screen))
				.bounds(screen.panelLeft() + TrajectoryCalculatorMenu.TARGET_WAIT_SET_X, waitY,
						TrajectoryCalculatorMenu.TARGET_WAIT_SET_W, TrajectoryCalculatorMenu.TARGET_FIELD_H)
				.tooltip(Tooltip.create(Component.translatable("cbc_firecontrol.calculator.settings.set_hint")))
				.build();
		refreshWaitValidity();

		List<GuiEventListener> widgets = new ArrayList<>();
		widgets.add(targetX);
		widgets.add(targetY);
		widgets.add(targetZ);
		widgets.add(setButton);
		widgets.add(clearButton);
		widgets.add(aimButton);
		widgets.add(waitField);
		widgets.add(waitSetButton);
		// SET is enabled only when all three fields parse, so a half-entered coordinate cannot be committed.
		refreshFieldValidity();
		return widgets;
	}

	/** One coordinate field: numeric-only input, live re-validation, Enter commits (see {@link #keyTyped}). */
	private EditBox numberField(TrajectoryCalculatorScreen screen, int x, int y, String messageKey) {
		EditBox field = new EditBox(screen.consoleFont(), screen.panelLeft() + x, y,
				TrajectoryCalculatorMenu.TARGET_FIELD_W, TrajectoryCalculatorMenu.TARGET_FIELD_H,
				Component.translatable(messageKey));
		field.setFilter(text -> text.isEmpty() || COORDINATE.matcher(text).matches());
		field.setMaxLength(16);
		field.setTextColor(ConsoleTheme.FIELD_TEXT);
		field.setResponder(ignored -> refreshFieldValidity());
		return field;
	}

	private void seedFieldsFromCalculator(TrajectoryCalculatorScreen screen) {
		TrajectoryCalculatorBlockEntity calculator = calculator(screen);
		if (calculator == null)
			return;
		if (!calculator.hasTarget()) {
			targetX.setValue("");
			targetY.setValue("");
			targetZ.setValue("");
			return;
		}
		targetX.setValue(CalculatorTooltips.numberText(calculator.targetX()));
		targetY.setValue(CalculatorTooltips.numberText(calculator.targetY()));
		targetZ.setValue(CalculatorTooltips.numberText(calculator.targetZ()));
	}

	/**
	 * Follow the stored target and window while their fields are not being edited, so another player (or a
	 * redstone-driven change) that moves either is reflected here instead of the fields quietly disagreeing with
	 * the readouts. Each block guards only its own fields: typing a coordinate must not freeze the window beside
	 * it, or the page would show a value the mounts have already moved past.
	 */
	@Override
	protected void ticked(TrajectoryCalculatorScreen screen) {
		TrajectoryCalculatorBlockEntity calculator = calculator(screen);
		if (calculator == null)
			return;
		if (!targetX.isFocused() && !targetY.isFocused() && !targetZ.isFocused()) {
			String x = calculator.hasTarget() ? CalculatorTooltips.numberText(calculator.targetX()) : "";
			String y = calculator.hasTarget() ? CalculatorTooltips.numberText(calculator.targetY()) : "";
			String z = calculator.hasTarget() ? CalculatorTooltips.numberText(calculator.targetZ()) : "";
			if (!targetX.getValue().equals(x))
				targetX.setValue(x);
			if (!targetY.getValue().equals(y))
				targetY.setValue(y);
			if (!targetZ.getValue().equals(z))
				targetZ.setValue(z);
			refreshFieldValidity();
		}
		if (!waitField.isFocused()) {
			String stored = String.valueOf(calculator.missionWaitSeconds());
			if (!waitField.getValue().equals(stored))
				waitField.setValue(stored);
			refreshWaitValidity();
		}
	}

	@Override
	protected boolean keyTyped(TrajectoryCalculatorScreen screen, int keyCode, int scanCode, int modifiers) {
		if (keyCode != GLFW.GLFW_KEY_ENTER && keyCode != GLFW.GLFW_KEY_KP_ENTER)
			return false;
		if (targetX.isFocused() || targetY.isFocused() || targetZ.isFocused()) {
			commitTarget(screen);
			return true;
		}
		if (waitField.isFocused()) {
			commitWait(screen);
			return true;
		}
		return false;
	}

	@Override
	protected void background(TrajectoryCalculatorScreen screen, GuiGraphics graphics) {
		/*
		 * One recessed band wide enough for the three coordinate fields, the buttons and the echo of what the
		 * calculator currently holds. A divider under it keeps the strip from reading as just another row, since
		 * the readout rows below use the same dark fill.
		 */
		int x = screen.panelLeft();
		int w = TrajectoryCalculatorMenu.PANEL_WIDTH;
		int stripTop = screen.panelTop() + TrajectoryCalculatorMenu.TARGET_STRIP_Y
				- TrajectoryCalculatorMenu.TARGET_STRIP_PAD_TOP;
		int stripBottom = screen.panelTop() + TrajectoryCalculatorMenu.TARGET_STRIP_BOTTOM;
		graphics.fill(x + 8, stripTop, x + w - 8, stripBottom, ConsoleTheme.TARGET_BG);
		graphics.fill(x + 8, stripBottom - 1, x + w - 8, stripBottom, ConsoleTheme.PANEL_EDGE);

		/* The window block's band, drawn the same way, so a setting reads as an input rather than as text. */
		int waitTop = screen.panelTop() + TrajectoryCalculatorMenu.TARGET_WAIT_STRIP_Y
				- TrajectoryCalculatorMenu.TARGET_WAIT_STRIP_PAD_TOP;
		int waitBottom = screen.panelTop() + TrajectoryCalculatorMenu.TARGET_WAIT_STRIP_BOTTOM;
		graphics.fill(x + 8, waitTop, x + w - 8, waitBottom, ConsoleTheme.TARGET_BG);
		graphics.fill(x + 8, waitBottom - 1, x + w - 8, waitBottom, ConsoleTheme.PANEL_EDGE);
	}

	@Override
	protected void labels(TrajectoryCalculatorScreen screen, GuiGraphics graphics, int mouseX, int mouseY) {
		int headerY = TrajectoryCalculatorMenu.CONTENT_TOP + 1;
		graphics.drawString(screen.consoleFont(), Component.translatable("cbc_firecontrol.calculator.target.label"),
				8, headerY, ConsoleTheme.HEADER_TEXT, false);

		/*
		 * The axis letters sit just left of each field so the three boxes read as one coordinate triple rather
		 * than three unrelated numbers, and the echo on the right says what the calculator actually holds right
		 * now - which is not necessarily what is typed, until it is committed.
		 */
		int labelY = TrajectoryCalculatorMenu.TARGET_STRIP_Y + 6;
		graphics.drawString(screen.consoleFont(), Component.translatable("cbc_firecontrol.calculator.target.x"),
				TrajectoryCalculatorMenu.TARGET_X_FIELD_X - 10, labelY, ConsoleTheme.CHANNEL_LABEL, false);
		graphics.drawString(screen.consoleFont(), Component.translatable("cbc_firecontrol.calculator.target.y"),
				TrajectoryCalculatorMenu.TARGET_Y_FIELD_X - 10, labelY, ConsoleTheme.CHANNEL_LABEL, false);
		graphics.drawString(screen.consoleFont(), Component.translatable("cbc_firecontrol.calculator.target.z"),
				TrajectoryCalculatorMenu.TARGET_Z_FIELD_X - 10, labelY, ConsoleTheme.CHANNEL_LABEL, false);

		TrajectoryCalculatorBlockEntity calculator = calculator(screen);
		if (calculator != null && calculator.hasTarget()) {
			graphics.drawString(screen.consoleFont(), fit(screen.consoleFont(),
					Component.translatable("cbc_firecontrol.calculator.target.current",
							CalculatorTooltips.numberText(calculator.targetX()),
							CalculatorTooltips.numberText(calculator.targetY()),
							CalculatorTooltips.numberText(calculator.targetZ())),
					TrajectoryCalculatorMenu.TARGET_ECHO_W), TrajectoryCalculatorMenu.TARGET_ECHO_X, labelY,
					ConsoleTheme.COLOR_STALLED, false);
		} else {
			graphics.drawString(screen.consoleFont(), Component.translatable("cbc_firecontrol.calculator.target.none"),
					TrajectoryCalculatorMenu.TARGET_ECHO_X, labelY, ConsoleTheme.TEXT_SECONDARY, false);
		}

		/*
		 * The window block under the strip: its own heading, the field's label, the echo of what the console
		 * holds (which is what the mounts are working to, not whatever is typed) and the three lines that say
		 * what the window does and does not cover.
		 */
		var font = screen.consoleFont();
		graphics.drawString(font, Component.translatable("cbc_firecontrol.calculator.settings.label"),
				8, TrajectoryCalculatorMenu.TARGET_WAIT_HEADER_Y, ConsoleTheme.HEADER_TEXT, false);
		int waitLabelY = TrajectoryCalculatorMenu.TARGET_WAIT_STRIP_Y + 6;
		graphics.drawString(font, Component.translatable("cbc_firecontrol.calculator.settings.wait"),
				TrajectoryCalculatorMenu.TARGET_WAIT_LABEL_X, waitLabelY, ConsoleTheme.CHANNEL_LABEL, false);
		int stored = calculator == null
				? TrajectoryCalculatorBlockEntity.DEFAULT_MISSION_WAIT_SECONDS
				: calculator.missionWaitSeconds();
		graphics.drawString(font, fit(font,
				Component.translatable("cbc_firecontrol.calculator.settings.current", String.valueOf(stored)),
				TrajectoryCalculatorMenu.TARGET_WAIT_ECHO_W),
				TrajectoryCalculatorMenu.TARGET_WAIT_ECHO_X, waitLabelY, ConsoleTheme.COLOR_STALLED, false);
		int noteY = TrajectoryCalculatorMenu.TARGET_WAIT_NOTE_Y;
		for (int line = 1; line <= 3; line++)
			graphics.drawString(font, Component.translatable("cbc_firecontrol.calculator.settings.note" + line),
					TrajectoryCalculatorMenu.TARGET_WAIT_LABEL_X,
					noteY + (line - 1) * TrajectoryCalculatorMenu.TARGET_WAIT_NOTE_LINE_H,
					ConsoleTheme.TEXT_SECONDARY, false);
	}

	private void refreshFieldValidity() {
		fieldsValid = parseCoordinate(targetX.getValue()) != null && parseCoordinate(targetY.getValue()) != null
				&& parseCoordinate(targetZ.getValue()) != null;
		int colour = fieldsValid ? ConsoleTheme.FIELD_TEXT : ConsoleTheme.FIELD_TEXT_INVALID;
		targetX.setTextColor(colour);
		targetY.setTextColor(colour);
		targetZ.setTextColor(colour);
		if (setButton != null)
			setButton.active = fieldsValid;
	}

	private static Double parseCoordinate(String text) {
		if (text == null || text.isEmpty() || !COORDINATE.matcher(text).matches())
			return null;
		try {
			return Double.parseDouble(text);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/**
	 * The console's stored window, not the typed text: reopening the page must show what the mounts are actually
	 * working to, and a half-entered number must never look like a setting that is in force.
	 */
	private void seedWaitFromCalculator(TrajectoryCalculatorScreen screen) {
		TrajectoryCalculatorBlockEntity calculator = calculator(screen);
		waitField.setValue(calculator == null
				? String.valueOf(TrajectoryCalculatorBlockEntity.DEFAULT_MISSION_WAIT_SECONDS)
				: String.valueOf(calculator.missionWaitSeconds()));
	}

	private void refreshWaitValidity() {
		waitValid = parseSeconds(waitField.getValue()) != null;
		waitField.setTextColor(waitValid ? ConsoleTheme.FIELD_TEXT : ConsoleTheme.FIELD_TEXT_INVALID);
		if (waitSetButton != null)
			waitSetButton.active = waitValid;
	}

	private static Integer parseSeconds(String text) {
		if (text == null || text.isEmpty() || !SECONDS.matcher(text).matches())
			return null;
		try {
			return Integer.parseInt(text);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/**
	 * Commit the typed window, or nothing at all - an empty field is never sent as a zero window. The server
	 * clamps what arrives to its own bounds, and the window is read live by the mount check loop, so lengthening
	 * it also rescues the orders already waiting and shortening it lets the oldest of them go on the next check.
	 */
	private void commitWait(TrajectoryCalculatorScreen screen) {
		Integer seconds = parseSeconds(waitField.getValue());
		if (seconds == null) {
			refreshWaitValidity();
			return;
		}
		PacketDistributor.sendToServer(new CalculatorSettingsPayload(menu(screen).getCalculatorPos(), seconds));
	}

	/** Commit what is typed, in full, or not at all - a half-entered target is never sent. */
	private void commitTarget(TrajectoryCalculatorScreen screen) {
		Double x = parseCoordinate(targetX.getValue());
		Double y = parseCoordinate(targetY.getValue());
		Double z = parseCoordinate(targetZ.getValue());
		if (x == null || y == null || z == null) {
			refreshFieldValidity();
			return;
		}
		PacketDistributor.sendToServer(
				new CalculatorTargetPayload(menu(screen).getCalculatorPos(), x, y, z, false));
	}

	/**
	 * Commit the aim every row solved for the stored target onto its mount. Nothing about the aim travels with
	 * this packet: only the server holds the solutions, and only the server may move a cannon - and the server
	 * re-checks terrain clearance there before it will turn a gun.
	 */
	private void sendAim(TrajectoryCalculatorScreen screen) {
		PacketDistributor.sendToServer(new CalculatorAimPayload(menu(screen).getCalculatorPos()));
	}

	private void sendClear(TrajectoryCalculatorScreen screen) {
		targetX.setValue("");
		targetY.setValue("");
		targetZ.setValue("");
		refreshFieldValidity();
		PacketDistributor.sendToServer(
				new CalculatorTargetPayload(menu(screen).getCalculatorPos(), 0, 0, 0, true));
	}
}
