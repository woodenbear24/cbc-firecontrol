package com.cbc.firecontrol.client;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.cbc.firecontrol.ballistics.AimOutcome;
import com.cbc.firecontrol.ballistics.AimSolution;
import com.cbc.firecontrol.ballistics.LinkStatus;
import com.cbc.firecontrol.ballistics.TrajectoryPrediction;
import com.cbc.firecontrol.block.entity.TrajectoryCalculatorBlockEntity;
import com.cbc.firecontrol.init.CBCItems;
import com.cbc.firecontrol.item.CalculatorLinkData;
import com.cbc.firecontrol.menu.TrajectoryCalculatorMenu;
import com.cbc.firecontrol.network.CalculatorAimPayload;
import com.cbc.firecontrol.network.CalculatorTargetPayload;

import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Create-style console of the Trajectory Calculator: a target-entry strip (absolute world X/Y/Z of the point to
 * engage), then a column of five link-card slots, each with its own readout row - what the linked mount's NEXT
 * shot would hit while no target is entered, and the aim it would have to be given to hit the entered target.
 *
 * <p>Drawn procedurally with {@link GuiGraphics#fill} instead of a bespoke panel texture: the readout column
 * is wide (mount coordinates, status, impact point, speed, flight time) and text-driven, so a flat dark panel
 * with row strips reads better than art and adds no new asset.
 *
 * <p>Numbers are read from the CLIENT-side calculator block entity, whose solutions the server pushes through
 * the block-entity data packet ({@code TrajectoryCalculatorBlockEntity#write(..., clientPacket=true)}) - no
 * menu payload round-trip, so the readouts remain correct for any player viewing the GUI. Hovering a row
 * shows the full detail (charge, barrels, warhead, material, current aiming) as a tooltip.
 *
 * <p>The entered target goes the other way, once per commit rather than per keystroke: a
 * {@link CalculatorTargetPayload} is sent on Enter in a field or on SET, and the server writes it into the block
 * entity, which recomputes and syncs every solution back. Fields are vanilla {@link EditBox}es - Create has no
 * text widget of its own, but {@code AbstractSimiContainerScreen#keyPressed} routes every key (digits included)
 * to a focused EditBox instead of letting vanilla's hotbar-swap handling eat it, which is what makes a numeric
 * field usable inside a container menu screen.
 */
public class TrajectoryCalculatorScreen extends AbstractSimiContainerScreen<TrajectoryCalculatorMenu> {

	private static final int PANEL_BG = 0xFF26262B;
	private static final int PANEL_EDGE = 0xFF41414A;
	private static final int ROW_BG = 0xFF1C1C20;
	private static final int INVENTORY_BG = 0xFF191920;
	private static final int HEADER_TEXT = 0xFFD8C58C;
	private static final int TEXT_SECONDARY = 0xFFA8A8B2;
	private static final int COLOR_OK = 0xFF7BC96F;
	private static final int COLOR_HIT = 0xFFE7C25C;
	private static final int COLOR_STALLED = 0xFF8FB3D9;
	private static final int COLOR_ERROR = 0xFFCE6A5C;
	private static final int CHANNEL_BG_ACTIVE = 0xFF2E3340;
	private static final int CHANNEL_BG_IDLE = 0xFF202126;
	private static final int CHANNEL_LABEL = 0xFF9AA6C4;
	private static final int TARGET_BG = 0xFF1A1D24;
	private static final int FIELD_TEXT = 0xFFE4E4EA;
	private static final int FIELD_TEXT_INVALID = 0xFFE08B7C;

	/** Optional sign, up to 12 integer digits, at most three decimals: a block coordinate, not a formula. */
	private static final Pattern COORDINATE = Pattern.compile("-?\\d{0,12}(\\.\\d{0,3})?");

	/** Tail marker used by {@link #fit(Component, int)} when a line has to be shortened to its column. */
	private static final String TRUNCATION_MARK = "...";

	private EditBox targetX;
	private EditBox targetY;
	private EditBox targetZ;
	private Button setTargetButton;
	private Button clearTargetButton;
	private Button aimTargetButton;
	/** Parse state of the three fields, recomputed on every keystroke; drives text colour and SET state. */
	private boolean fieldsValid;

	public TrajectoryCalculatorScreen(TrajectoryCalculatorMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title);
	}

	@Override
	protected void init() {
		setWindowSize(TrajectoryCalculatorMenu.PANEL_WIDTH,
				TrajectoryCalculatorMenu.PLAYER_INV_Y + TrajectoryCalculatorMenu.PLAYER_INV_H);
		setWindowOffset(0, 0);
		super.init();

		int stripY = topPos + TrajectoryCalculatorMenu.TARGET_STRIP_Y;
		targetX = numberField(TrajectoryCalculatorMenu.TARGET_X_FIELD_X, stripY, "cbc_firecontrol.calculator.target.x");
		targetY = numberField(TrajectoryCalculatorMenu.TARGET_Y_FIELD_X, stripY, "cbc_firecontrol.calculator.target.y");
		targetZ = numberField(TrajectoryCalculatorMenu.TARGET_Z_FIELD_X, stripY, "cbc_firecontrol.calculator.target.z");
		addRenderableWidgets(targetX, targetY, targetZ);

		int setWidth = TrajectoryCalculatorMenu.TARGET_CLEAR_X - TrajectoryCalculatorMenu.TARGET_SET_X - 4;
		int clearWidth = TrajectoryCalculatorMenu.TARGET_AIM_X - TrajectoryCalculatorMenu.TARGET_CLEAR_X - 4;
		int aimWidth = TrajectoryCalculatorMenu.TARGET_ECHO_X - TrajectoryCalculatorMenu.TARGET_AIM_X - 4;
		setTargetButton = Button
				.builder(Component.translatable("cbc_firecontrol.calculator.target.set"), button -> commitTarget())
				.bounds(leftPos + TrajectoryCalculatorMenu.TARGET_SET_X, stripY, setWidth,
						TrajectoryCalculatorMenu.TARGET_FIELD_H)
				.tooltip(Tooltip.create(Component.translatable("cbc_firecontrol.calculator.target.set_hint")))
				.build();
		clearTargetButton = Button
				.builder(Component.translatable("cbc_firecontrol.calculator.target.clear"), button -> sendClear())
				.bounds(leftPos + TrajectoryCalculatorMenu.TARGET_CLEAR_X, stripY, clearWidth,
						TrajectoryCalculatorMenu.TARGET_FIELD_H)
				.tooltip(Tooltip.create(Component.translatable("cbc_firecontrol.calculator.target.clear_hint")))
				.build();
		aimTargetButton = Button
				.builder(Component.translatable("cbc_firecontrol.calculator.target.aim"), button -> sendAim())
				.bounds(leftPos + TrajectoryCalculatorMenu.TARGET_AIM_X, stripY, aimWidth,
						TrajectoryCalculatorMenu.TARGET_FIELD_H)
				.tooltip(Tooltip.create(Component.translatable("cbc_firecontrol.calculator.target.aim_hint")))
				.build();
		addRenderableWidgets(setTargetButton, clearTargetButton, aimTargetButton);

		// Fields start from what the calculator already stores, so reopening the GUI never suggests a new shot.
		seedFieldsFromCalculator();
		refreshFieldValidity();
	}

	/** One coordinate field: numeric-only input, live re-validation, Enter commits (see {@link #keyPressed}). */
	private EditBox numberField(int x, int y, String messageKey) {
		EditBox field = new EditBox(this.font, leftPos + x, y, TrajectoryCalculatorMenu.TARGET_FIELD_W,
				TrajectoryCalculatorMenu.TARGET_FIELD_H, Component.translatable(messageKey));
		field.setFilter(text -> text.isEmpty() || COORDINATE.matcher(text).matches());
		field.setMaxLength(16);
		field.setTextColor(FIELD_TEXT);
		field.setResponder(ignored -> refreshFieldValidity());
		return field;
	}

	private void seedFieldsFromCalculator() {
		TrajectoryCalculatorBlockEntity calculator = clientCalculator();
		if (calculator == null)
			return;
		if (!calculator.hasTarget()) {
			targetX.setValue("");
			targetY.setValue("");
			targetZ.setValue("");
			return;
		}
		targetX.setValue(numberText(calculator.targetX()));
		targetY.setValue(numberText(calculator.targetY()));
		targetZ.setValue(numberText(calculator.targetZ()));
	}

	/**
	 * Follow the stored target while no field is being edited, so another player (or a redstone-driven change)
	 * that moves the target is reflected here instead of the fields quietly disagreeing with the readouts.
	 */
	@Override
	protected void containerTick() {
		super.containerTick();
		TrajectoryCalculatorBlockEntity calculator = clientCalculator();
		if (calculator == null || targetX.isFocused() || targetY.isFocused() || targetZ.isFocused())
			return;
		String x = calculator.hasTarget() ? numberText(calculator.targetX()) : "";
		String y = calculator.hasTarget() ? numberText(calculator.targetY()) : "";
		String z = calculator.hasTarget() ? numberText(calculator.targetZ()) : "";
		if (!targetX.getValue().equals(x))
			targetX.setValue(x);
		if (!targetY.getValue().equals(y))
			targetY.setValue(y);
		if (!targetZ.getValue().equals(z))
			targetZ.setValue(z);
		refreshFieldValidity();
	}

	private void refreshFieldValidity() {
		fieldsValid = parseCoordinate(targetX.getValue()) != null
				&& parseCoordinate(targetY.getValue()) != null
				&& parseCoordinate(targetZ.getValue()) != null;
		int colour = fieldsValid ? FIELD_TEXT : FIELD_TEXT_INVALID;
		targetX.setTextColor(colour);
		targetY.setTextColor(colour);
		targetZ.setTextColor(colour);
		setTargetButton.active = fieldsValid;
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

	/** Commit what is typed, in full, or not at all - a half-entered target is never sent. */
	private void commitTarget() {
		Double x = parseCoordinate(targetX.getValue());
		Double y = parseCoordinate(targetY.getValue());
		Double z = parseCoordinate(targetZ.getValue());
		if (x == null || y == null || z == null) {
			refreshFieldValidity();
			return;
		}
		PacketDistributor.sendToServer(new CalculatorTargetPayload(menu.getCalculatorPos(), x, y, z, false));
	}

	/**
	 * Commit the aim every row solved for the stored target onto its mount. Nothing about the aim travels with
	 * this packet: only the server holds the solutions, and only the server may move a cannon - and the server
	 * re-checks terrain clearance there before it will turn a gun.
	 */
	private void sendAim() {
		PacketDistributor.sendToServer(new CalculatorAimPayload(menu.getCalculatorPos()));
	}

	private void sendClear() {
		targetX.setValue("");
		targetY.setValue("");
		targetZ.setValue("");
		refreshFieldValidity();
		PacketDistributor.sendToServer(new CalculatorTargetPayload(menu.getCalculatorPos(), 0, 0, 0, true));
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)
				&& (targetX.isFocused() || targetY.isFocused() || targetZ.isFocused())) {
			commitTarget();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	private static String numberText(double value) {
		if (value == Math.rint(value))
			return Integer.toString((int) value);
		return String.format("%.3f", value);
	}

	/**
	 * Shorten a line to the width the layout has for it rather than let it print over the next column: a long
	 * translation (or a far-flung coordinate) must not spill across the channel filter slots or past the panel
	 * edge. What gets dropped is safe because the row tooltip carries the same text unwrapped, and the input
	 * fields carry the exact typed coordinate.
	 */
	private Component fit(Component text, int width) {
		if (this.font.width(text) <= width)
			return text;
		String full = text.getString();
		int budget = width - this.font.width(TRUNCATION_MARK);
		int keep = 0;
		while (keep < full.length() && this.font.width(full.substring(0, keep + 1)) <= budget)
			keep++;
		return Component.literal(full.substring(0, Math.max(1, keep)) + TRUNCATION_MARK);
	}

	@Override
	protected void renderBg(GuiGraphics graphics, float partialTicks, int mouseX, int mouseY) {
		int x = leftPos;
		int y = topPos;
		int w = TrajectoryCalculatorMenu.PANEL_WIDTH;
		int h = TrajectoryCalculatorMenu.PANEL_HEIGHT;

		graphics.fill(x, y, x + w, y + h, PANEL_BG);
		graphics.fill(x, y, x + w, y + 1, PANEL_EDGE);
		graphics.fill(x, y + h - 1, x + w, y + h, PANEL_EDGE);
		graphics.fill(x, y, x + 1, y + h, PANEL_EDGE);
		graphics.fill(x + w - 1, y, x + w, y + h, PANEL_EDGE);

		/*
		 * Target-entry strip: one recessed band wide enough for the three coordinate fields, the two buttons and
		 * the echo of what the calculator currently holds. A divider under it keeps the strip from reading as
		 * just another row, since the readout rows below use the same dark fill.
		 */
		int stripTop = y + TrajectoryCalculatorMenu.TARGET_STRIP_Y - TrajectoryCalculatorMenu.TARGET_STRIP_PAD_TOP;
		int stripBottom = y + TrajectoryCalculatorMenu.TARGET_STRIP_BOTTOM;
		graphics.fill(x + 8, stripTop, x + w - 8, stripBottom, TARGET_BG);
		graphics.fill(x + 8, stripBottom - 1, x + w - 8, stripBottom, PANEL_EDGE);

		for (int i = 0; i < TrajectoryCalculatorMenu.SLOTS; i++) {
			int rowY = y + TrajectoryCalculatorMenu.LINK_SLOT_Y - TrajectoryCalculatorMenu.ROW_BAND_INSET
					+ i * TrajectoryCalculatorMenu.LINK_SLOT_SPACING;
			graphics.fill(x + 8, rowY, x + w - 8, rowY + TrajectoryCalculatorMenu.ROW_BAND_H, ROW_BG);
		}

		/*
		 * Channel filter backdrops: 5 columns x 2 filter slots per row. An ACTIVE slot (its card records a
		 * mount) gets the lighter backdrop, so at a glance a row that can transmit is distinguishable from a
		 * row that is only configured but idle.
		 */
		TrajectoryCalculatorBlockEntity calculatorBE = clientCalculator();
		for (int slot = 0; slot < TrajectoryCalculatorMenu.SLOTS; slot++) {
			boolean active = calculatorBE != null && calculatorBE.isSlotActive(slot);
			int baseY = y + TrajectoryCalculatorMenu.LINK_SLOT_Y + TrajectoryCalculatorMenu.CHANNEL_Y_OFFSET
					+ slot * TrajectoryCalculatorMenu.LINK_SLOT_SPACING;
			for (int channel = 0; channel < TrajectoryCalculatorMenu.CHANNELS; channel++) {
				int baseX = x + TrajectoryCalculatorMenu.CHANNEL_SLOT_X
						+ channel * TrajectoryCalculatorMenu.CHANNEL_COLUMN_SPACING;
				graphics.fill(baseX - 1, baseY - 1, baseX + 2 * 18 + 1, baseY + 18 + 1,
						active ? CHANNEL_BG_ACTIVE : CHANNEL_BG_IDLE);
			}
		}

		int invY = y + TrajectoryCalculatorMenu.PLAYER_INV_Y;
		graphics.fill(x + TrajectoryCalculatorMenu.PLAYER_INV_X, invY,
				x + TrajectoryCalculatorMenu.PLAYER_INV_X + 178,
				invY + TrajectoryCalculatorMenu.PLAYER_INV_H, INVENTORY_BG);
	}

	@Override
	protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
		// renderLabels already runs translated by (leftPos, topPos), so every coordinate here is window-local.
		graphics.drawString(this.font, this.title, 8, 5, HEADER_TEXT, false);
		graphics.drawString(this.font,
				Component.translatable("container.cbc_firecontrol.trajectory_calculator.slots",
						TrajectoryCalculatorMenu.SLOTS, TrajectoryCalculatorBlockEntity.LINK_RANGE),
				120, 5, TEXT_SECONDARY, false);
		graphics.drawString(this.font,
				Component.translatable("container.cbc_firecontrol.trajectory_calculator.inventory"),
				TrajectoryCalculatorMenu.PLAYER_INV_X + 8, TrajectoryCalculatorMenu.PLAYER_INV_Y + 3,
				HEADER_TEXT, false);

		/*
		 * Target strip labels. The axis letters sit just left of each field so the three boxes read as one
		 * coordinate triple rather than three unrelated numbers, and the echo on the right says what the
		 * calculator actually holds right now - which is not necessarily what is typed, until it is committed.
		 */
		graphics.drawString(this.font, Component.translatable("cbc_firecontrol.calculator.target.label"),
				8, 16, HEADER_TEXT, false);
		int labelY = TrajectoryCalculatorMenu.TARGET_STRIP_Y + 6;
		graphics.drawString(this.font,
				Component.translatable("cbc_firecontrol.calculator.target.x"),
				TrajectoryCalculatorMenu.TARGET_X_FIELD_X - 10, labelY, CHANNEL_LABEL, false);
		graphics.drawString(this.font,
				Component.translatable("cbc_firecontrol.calculator.target.y"),
				TrajectoryCalculatorMenu.TARGET_Y_FIELD_X - 10, labelY, CHANNEL_LABEL, false);
		graphics.drawString(this.font,
				Component.translatable("cbc_firecontrol.calculator.target.z"),
				TrajectoryCalculatorMenu.TARGET_Z_FIELD_X - 10, labelY, CHANNEL_LABEL, false);

		TrajectoryCalculatorBlockEntity targetBE = clientCalculator();
		if (targetBE != null && targetBE.hasTarget()) {
			graphics.drawString(this.font,
					fit(Component.translatable("cbc_firecontrol.calculator.target.current",
							Integer.toString((int) Math.round(targetBE.targetX())),
							Integer.toString((int) Math.round(targetBE.targetY())),
							Integer.toString((int) Math.round(targetBE.targetZ()))),
							TrajectoryCalculatorMenu.TARGET_ECHO_W),
					TrajectoryCalculatorMenu.TARGET_ECHO_X, labelY, COLOR_STALLED, false);
		} else {
			graphics.drawString(this.font, Component.translatable("cbc_firecontrol.calculator.target.none"),
					TrajectoryCalculatorMenu.TARGET_ECHO_X, labelY, TEXT_SECONDARY, false);
		}

		// Channel column headers, aligned with the filter pairs below them. The meanings mirror the mount
		// radio's columns 1:1, which is what makes a calculator column drive one mount action.
		graphics.drawString(this.font,
				Component.translatable("cbc_firecontrol.calculator.channels"),
				TrajectoryCalculatorMenu.CHANNEL_SLOT_X, 5, HEADER_TEXT, false);
		for (int channel = 0; channel < TrajectoryCalculatorMenu.CHANNELS; channel++) {
			graphics.drawString(this.font,
					Component.translatable("cbc_firecontrol.calculator.channel." + channel),
					TrajectoryCalculatorMenu.CHANNEL_SLOT_X + channel * TrajectoryCalculatorMenu.CHANNEL_COLUMN_SPACING,
					16, CHANNEL_LABEL, false);
		}
	}

	@Override
	protected void renderForeground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
		TrajectoryCalculatorBlockEntity be = clientCalculator();
		/*
		 * Two modes of the same row. With no target entered a row reports the shot the mount would fire right
		 * now; once a target is entered it reports the aim that would hit it instead, because that is the
		 * question the gunner is asking at that moment. The forward prediction is never lost either way - it
		 * stays in the row tooltip.
		 */
		boolean aiming = be != null && be.hasTarget();

		for (int i = 0; i < TrajectoryCalculatorMenu.SLOTS; i++) {
			TrajectoryPrediction prediction = be != null ? be.getPrediction(i) : TrajectoryPrediction.EMPTY;
			LinkStatus status = prediction.status();
			AimSolution aim = be != null ? be.getAim(i) : AimSolution.EMPTY;
			int rowTop = topPos + TrajectoryCalculatorMenu.LINK_SLOT_Y
					+ i * TrajectoryCalculatorMenu.LINK_SLOT_SPACING;
			int textX = leftPos + TrajectoryCalculatorMenu.LINK_SLOT_X + 34;

			/* Two lines inside one 20 pixel row band: the header at the top, the readout under it. */
			int lineTop = rowTop + 1;
			int lineBottom = rowTop + 10;
			graphics.drawString(this.font, fit(firstLine(i, status), TrajectoryCalculatorMenu.ROW_TEXT_W), textX,
					lineTop, colorFor(status), false);
			if (aiming)
				graphics.drawString(this.font, fit(aimLine(aim), TrajectoryCalculatorMenu.ROW_TEXT_W), textX,
						lineBottom, colorForOutcome(aim.outcome()), false);
			else if (status.hasSolution())
				graphics.drawString(this.font, fit(solutionLine(prediction), TrajectoryCalculatorMenu.ROW_TEXT_W),
						textX, lineBottom, TEXT_SECONDARY, false);
			else
				graphics.drawString(this.font,
						fit(Component.translatable("cbc_firecontrol.calculator.readout.none"),
								TrajectoryCalculatorMenu.ROW_TEXT_W),
						textX, lineBottom, TEXT_SECONDARY, false);

			// The row tooltip covers the card slot + the readout column only; the channel grid has its own
			// tooltip, and two tooltips in one frame would overlap.
			if (isInside(mouseX, mouseY, leftPos + 8, rowTop - TrajectoryCalculatorMenu.ROW_BAND_INSET,
					TrajectoryCalculatorMenu.CHANNEL_SLOT_X - 16, TrajectoryCalculatorMenu.ROW_BAND_H))
				graphics.renderComponentTooltip(this.font,
						aiming ? aimDetailLines(i, aim, prediction) : detailLines(i, prediction), mouseX, mouseY);
		}

		int[] hovered = hoveredChannel(mouseX, mouseY);
		if (hovered != null)
			graphics.renderComponentTooltip(this.font, channelLines(hovered[0], hovered[1]), mouseX, mouseY);
	}

	/** Which (slot, channel) filter pair the mouse sits over, or null when it is over neither. */
	private int[] hoveredChannel(int mouseX, int mouseY) {
		for (int slot = 0; slot < TrajectoryCalculatorMenu.SLOTS; slot++) {
			int top = topPos + TrajectoryCalculatorMenu.LINK_SLOT_Y + TrajectoryCalculatorMenu.CHANNEL_Y_OFFSET - 1
					+ slot * TrajectoryCalculatorMenu.LINK_SLOT_SPACING;
			for (int channel = 0; channel < TrajectoryCalculatorMenu.CHANNELS; channel++) {
				int left = leftPos + TrajectoryCalculatorMenu.CHANNEL_SLOT_X
						+ channel * TrajectoryCalculatorMenu.CHANNEL_COLUMN_SPACING - 1;
				if (isInside(mouseX, mouseY, left, top, 2 * 18 + 2, 18 + 2))
					return new int[] { slot, channel };
			}
		}
		return null;
	}

	/** Channel tooltip: what this column means, its two filter items, and whether it can transmit right now. */
	private List<Component> channelLines(int slot, int channel) {
		List<Component> lines = new ArrayList<>();
		lines.add(Component.translatable("cbc_firecontrol.calculator.channel.hover",
				slot + 1, channel + 1,
				Component.translatable("cbc_firecontrol.calculator.channel." + channel)));
		for (int filter = 0; filter < TrajectoryCalculatorBlockEntity.FILTERS_PER_CHANNEL; filter++) {
			ItemStack filterStack = menu.getGhostHandler()
					.getStackInSlot(TrajectoryCalculatorBlockEntity.channelIndex(slot, channel, filter));
			lines.add(filterStack.isEmpty() ? Component.literal("-") : filterStack.getDisplayName());
		}
		lines.add(Component.translatable("cbc_firecontrol.calculator.channel.hint"));
		TrajectoryCalculatorBlockEntity be = clientCalculator();
		if (be != null && be.isSlotActive(slot))
			lines.add(Component.translatable("cbc_firecontrol.calculator.channel.power", be.getEmittedLinkPower()));
		else
			lines.add(Component.translatable("cbc_firecontrol.calculator.channel.inactive"));
		return lines;
	}

	/**
	 * Row header: the slot number and the one-word state of its link. The linked mount's coordinates are NOT in
	 * the header - a six-figure coordinate plus a status sentence cannot share 148 pixels - they are the first
	 * line of the row tooltip, and the card item's own tooltip already carries them.
	 */
	private Component firstLine(int slot, LinkStatus status) {
		return Component.translatable("cbc_firecontrol.calculator.readout.slot", slot + 1,
				Component.translatable(status.shortLangKey()));
	}

	/** Which mount this row's card points at, for the tooltip line the header has no room for. */
	private Component mountLine(int slot) {
		ItemStack stack = menu.getHandler().getStackInSlot(slot);
		CalculatorLinkData link = stack.get(CBCItems.CALCULATOR_LINK_DATA.get());
		if (link == null)
			return Component.translatable("cbc_firecontrol.calculator.readout.no_mount");
		return Component.translatable("cbc_firecontrol.calculator.detail.mount",
				link.mount().getX(), link.mount().getY(), link.mount().getZ());
	}

	/**
	 * Row body: the three numbers a gunner acts on - how far the shot throws, how fast it leaves the muzzle
	 * and how long it is in the air. The impact coordinates live in the row tooltip instead: with the mount's
	 * own coordinate already in the header line, six numbers cannot fit between the card slot and the channel
	 * grid (148 pixels) without covering the filter slots.
	 */
	private Component solutionLine(TrajectoryPrediction p) {
		return Component.translatable("cbc_firecontrol.calculator.readout.solution",
				Component.literal(String.format("%.0f", p.horizontalRange())),
				Component.literal(String.format("%.1f", p.muzzleSpeed())),
				Component.literal(Integer.toString(p.flightTicks())));
	}

	private List<Component> detailLines(int slot, TrajectoryPrediction p) {
		List<Component> lines = new ArrayList<>();
		LinkStatus status = p.status();
		lines.add(Component.translatable("cbc_firecontrol.calculator.detail.title", slot + 1));
		lines.add(mountLine(slot));
		lines.add(Component.translatable("cbc_firecontrol.calculator.detail.status",
				Component.translatable(status.langKey())));
		lines.add(Component.translatable("cbc_firecontrol.calculator.detail.aim",
				String.format("%.1f", p.yawDeg()), String.format("%.1f", p.pitchDeg())));
		lines.add(Component.translatable("cbc_firecontrol.calculator.detail.speed",
				Component.literal(String.format("%.2f", p.muzzleSpeed())),
				Component.literal(Integer.toString(p.flightTicks()))));
		lines.add(Component.translatable("cbc_firecontrol.calculator.detail.range",
				Component.literal(String.format("%.1f", p.horizontalRange())),
				Component.literal(String.format("%.1f", p.straightRange()))));
		lines.add(Component.translatable("cbc_firecontrol.calculator.detail.impact",
				Integer.toString((int) Math.round(p.impactX())), Integer.toString((int) Math.round(p.impactY())),
				Integer.toString((int) Math.round(p.impactZ()))));
		if (p.barrelCount() > 0 || p.chargePower() > 0)
			lines.add(Component.translatable("cbc_firecontrol.calculator.detail.gun",
					Component.literal(Integer.toString(p.barrelCount())),
					Component.literal(String.format("%.1f", p.chargePower()))));
		if (!p.munitionKey().isEmpty())
			lines.add(Component.translatable("cbc_firecontrol.calculator.detail.munition",
					Component.translatable(p.munitionKey())));
		if (!p.materialKey().isEmpty())
			lines.add(Component.translatable("cbc_firecontrol.calculator.detail.material", p.materialKey()));
		return lines;
	}

	/** Row body in target mode: the aim this mount would need, or the reason it cannot have one. */
	private Component aimLine(AimSolution aim) {
		if (aim == AimSolution.EMPTY)
			return Component.translatable("cbc_firecontrol.calculator.readout.none");
		if (aim.outcome().hasSolution())
			return Component.translatable("cbc_firecontrol.calculator.readout.aim",
					String.format("%.1f", aim.yawDeg()), String.format("%.1f", aim.pitchDeg()),
					Component.translatable(aim.arc().shortLangKey()));
		/*
		 * Terse wording here on purpose: the row has 148 pixels before the channel grid, and both the
		 * sentence-length outcome labels and the sentence-length suggestions the tooltip uses would print across
		 * the filter slots. The full sentences are the first lines of the row tooltip.
		 */
		MutableComponent line = Component.translatable(aim.outcome().shortLangKey());
		if (aim.hasSuggestedPitch())
			line.append("  ").append(Component.translatable("cbc_firecontrol.calculator.readout.advice.elev",
					String.format("%.1f", aim.suggestedPitchDeg())));
		else if (aim.hasSuggestedCharge())
			line.append("  ").append(Component.translatable("cbc_firecontrol.calculator.readout.advice.charge",
					String.format("%.2f", aim.suggestedCharge())));
		return line;
	}

	/**
	 * Colour for a solution verdict. Blue is deliberate on the fixable failures: ELEVATION_LIMITED and
	 * CHARGE_INSUFFICIENT both come with a number the gunner can act on, so they must not read like a dead end.
	 */
	private static int colorForOutcome(AimOutcome outcome) {
		return switch (outcome) {
			case HIT -> COLOR_OK;
			case RESIDUAL -> COLOR_HIT;
			case BLOCKED, ELEVATION_LIMITED, CHARGE_INSUFFICIENT -> COLOR_STALLED;
			default -> COLOR_ERROR;
		};
	}

	/** Full detail of one solution, with the forward prediction kept underneath it. */
	private List<Component> aimDetailLines(int slot, AimSolution aim, TrajectoryPrediction prediction) {
		List<Component> lines = new ArrayList<>();
		lines.add(Component.translatable("cbc_firecontrol.calculator.aimdetail.title", slot + 1));
		lines.add(mountLine(slot));
		if (aim == AimSolution.EMPTY) {
			lines.add(Component.translatable("cbc_firecontrol.calculator.aimdetail.pending"));
			lines.addAll(detailLines(slot, prediction));
			return lines;
		}
		lines.add(Component.translatable("cbc_firecontrol.calculator.aimdetail.outcome",
				Component.translatable(aim.outcome().langKey())));
		lines.add(Component.translatable("cbc_firecontrol.calculator.aimdetail.aim",
				String.format("%.1f", aim.yawDeg()), String.format("%.1f", aim.pitchDeg()),
				Component.translatable(aim.arcLangKey())));
		if (aim.outcome() == AimOutcome.BLOCKED)
			lines.add(Component.translatable("cbc_firecontrol.calculator.aimdetail.blocked"));
		if (aim.hasSuggestedPitch())
			lines.add(Component.translatable("cbc_firecontrol.calculator.aimdetail.suggest_elev",
					String.format("%.1f", aim.suggestedPitchDeg())));
		if (aim.hasSuggestedCharge())
			lines.add(Component.translatable("cbc_firecontrol.calculator.aimdetail.suggest_charge",
					String.format("%.2f", aim.suggestedCharge())));
		if (aim.outcome().hasSolution()) {
			lines.add(Component.translatable("cbc_firecontrol.calculator.aimdetail.impact",
					numberText(aim.impactX()), numberText(aim.impactY()), numberText(aim.impactZ())));
			lines.add(Component.translatable("cbc_firecontrol.calculator.aimdetail.miss",
					String.format("%.2f", aim.missDistance()), String.format("%.1f", aim.impactPlanRange()),
					String.format("%.1f", aim.targetPlanRange()), String.format("%.2f", aim.altitudeError())));
			lines.add(Component.translatable("cbc_firecontrol.calculator.aimdetail.flight",
					Integer.toString(aim.flightTicks()), String.format("%.2f", aim.muzzleSpeed()),
					String.format("%.2f", aim.chargePower())));
			if (aim.alternativeArc())
				lines.add(Component.translatable("cbc_firecontrol.calculator.aimdetail.alt_arc"));
		}
		lines.add(Component.translatable("cbc_firecontrol.calculator.aimdetail.mount_aim",
				String.format("%.1f", prediction.yawDeg()), String.format("%.1f", prediction.pitchDeg())));
		lines.add(Component.translatable("cbc_firecontrol.calculator.aimdetail.convention"));
		lines.addAll(detailLines(slot, prediction));
		return lines;
	}

	private static int colorFor(LinkStatus status) {
		return switch (status) {
			case OK -> COLOR_OK;
			case BLOCK_HIT -> COLOR_HIT;
			case LIFETIME_END, OUT_OF_WORLD, TOO_FAR -> COLOR_STALLED;
			case EMPTY_SLOT, NOT_A_LINK, UNBOUND -> TEXT_SECONDARY;
			default -> COLOR_ERROR;
		};
	}

	private static boolean isInside(int x, int y, int left, int top, int width, int height) {
		return x >= left && x < left + width && y >= top && y < top + height;
	}

	private TrajectoryCalculatorBlockEntity clientCalculator() {
		if (Minecraft.getInstance().level == null)
			return null;
		if (Minecraft.getInstance().level.getBlockEntity(menu.getCalculatorPos())
				instanceof TrajectoryCalculatorBlockEntity be)
			return be;
		return null;
	}
}
