package com.cbc.firecontrol.client.calculator;

import java.util.ArrayList;
import java.util.List;

import com.cbc.firecontrol.ballistics.AimSolution;
import com.cbc.firecontrol.ballistics.LinkStatus;
import com.cbc.firecontrol.ballistics.TrajectoryPrediction;
import com.cbc.firecontrol.block.entity.FireMission;
import com.cbc.firecontrol.block.entity.TrajectoryCalculatorBlockEntity;
import com.cbc.firecontrol.block.entity.TrajectoryCalculatorBlockEntity.MissionPhase;
import com.cbc.firecontrol.client.TrajectoryCalculatorScreen;
import com.cbc.firecontrol.client.gui.ConsoleTheme;
import com.cbc.firecontrol.menu.TrajectoryCalculatorMenu;

import net.minecraft.client.gui.Font;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * The tasks page: a six-column board, one queue per column - the console's own queue and the queue of each of its
 * five mount rows.
 *
 * <p>The board is the shape the coming fire-mission system will fill. Each column is a queue in its own right, so a
 * row is an entry position within that column and not a shared line: the console holds one order at a time today
 * (the target it stores and the solution derived from it), while a mount's first entry is the aim the console has
 * already put on it. Entries below the first are the queued ones, and nothing queues yet, so they are drawn as
 * dashes rather than invented.
 *
 * <p>Every number shown is state the console already mirrors to clients: the stored target, each row's solved aim
 * and its verdict, and whether that row's mount is being driven onto the aim. Hovering a column gives the long form
 * of whatever the column had to abbreviate.
 */
public class CalculatorTaskTab extends CalculatorTab {

	private static final String DASH = "-";

	public CalculatorTaskTab() {
		super(TrajectoryCalculatorMenu.TAB_TASKS, "cbc_firecontrol.calculator.tab.tasks");
	}

	/** Left edge of a column, panel-local. */
	private static int columnLeft(int column) {
		return TrajectoryCalculatorMenu.TASK_BOARD_X + column * TrajectoryCalculatorMenu.TASK_COLUMN_W;
	}

	/** Right edge of a column, panel-local. The last column takes the remainder of the board's width. */
	private static int columnRight(int column) {
		int last = TrajectoryCalculatorMenu.TASK_COLUMNS - 1;
		return column == last ? TrajectoryCalculatorMenu.TASK_BOARD_X + TrajectoryCalculatorMenu.TASK_BOARD_W
				: columnLeft(column) + TrajectoryCalculatorMenu.TASK_COLUMN_W;
	}

	/** Usable text width inside a column. */
	private static int cellWidth(int column) {
		return columnRight(column) - columnLeft(column) - 4;
	}

	@Override
	protected void background(TrajectoryCalculatorScreen screen, GuiGraphics graphics) {
		int boardLeft = TrajectoryCalculatorMenu.TASK_BOARD_X;
		int boardRight = TrajectoryCalculatorMenu.TASK_BOARD_X + TrajectoryCalculatorMenu.TASK_BOARD_W;
		int headerTop = TrajectoryCalculatorMenu.TASK_HEADER_Y;
		int boardBottom = TrajectoryCalculatorMenu.TASK_BOARD_BOTTOM;

		graphics.fill(boardLeft, headerTop, boardRight, headerTop + TrajectoryCalculatorMenu.TASK_HEADER_H,
				ConsoleTheme.ROW_BG);
		for (int row = 0; row < TrajectoryCalculatorMenu.SLOTS; row++) {
			int top = TrajectoryCalculatorMenu.TASK_ROW_Y + row * TrajectoryCalculatorMenu.TASK_ROW_H;
			graphics.fill(boardLeft, top, boardRight, top + TrajectoryCalculatorMenu.TASK_ROW_H,
					row % 2 == 0 ? ConsoleTheme.CHANNEL_BG_IDLE : ConsoleTheme.ROW_BG);
			graphics.fill(boardLeft, top, boardRight, top + 1, ConsoleTheme.PANEL_EDGE);
		}
		graphics.fill(boardLeft, boardBottom, boardRight, boardBottom + 1, ConsoleTheme.PANEL_EDGE);
		graphics.fill(boardLeft, headerTop + TrajectoryCalculatorMenu.TASK_HEADER_H, boardRight,
				headerTop + TrajectoryCalculatorMenu.TASK_HEADER_H + 1, ConsoleTheme.PANEL_EDGE);

		/*
		 * The column rules run the full height of the board, so the six queues read as six lanes rather than as
		 * unlabelled text: with a dash in most cells, a grid of bare words would look like a broken table.
		 */
		for (int column = 1; column < TrajectoryCalculatorMenu.TASK_COLUMNS; column++)
			graphics.fill(columnLeft(column), headerTop, columnLeft(column) + 1, boardBottom + 1,
					ConsoleTheme.PANEL_EDGE);
	}

	@Override
	protected void labels(TrajectoryCalculatorScreen screen, GuiGraphics graphics, int mouseX, int mouseY) {
		Font font = screen.consoleFont();
		int headerY = TrajectoryCalculatorMenu.TASK_HEADER_Y + 2;
		graphics.drawString(font, fit(font, Component.translatable("cbc_firecontrol.calculator.tasks.column.console"),
				cellWidth(0)), columnLeft(0) + 2, headerY, ConsoleTheme.HEADER_TEXT, false);
		for (int slot = 0; slot < TrajectoryCalculatorMenu.SLOTS; slot++)
			graphics.drawString(font, fit(font,
					Component.translatable("cbc_firecontrol.calculator.tasks.column.mount", slot + 1),
					cellWidth(slot + 1)), columnLeft(slot + 1) + 2, headerY, ConsoleTheme.CHANNEL_LABEL, false);

		TrajectoryCalculatorBlockEntity be = calculator(screen);
		for (int row = 0; row < TrajectoryCalculatorMenu.SLOTS; row++) {
			int lineY = TrajectoryCalculatorMenu.TASK_ROW_Y + 6 + row * TrajectoryCalculatorMenu.TASK_ROW_H;
			Cell console = consoleEntry(be, row);
			graphics.drawString(font, fit(font, console.text(), cellWidth(0)), columnLeft(0) + 2, lineY,
					console.colour(), false);
			for (int slot = 0; slot < TrajectoryCalculatorMenu.SLOTS; slot++) {
				Cell entry = mountEntry(screen, be, slot, row);
				graphics.drawString(font, fit(font, entry.text(), cellWidth(slot + 1)), columnLeft(slot + 1) + 2,
						lineY, entry.colour(), false);
			}
		}

		graphics.drawString(font, fit(font, Component.translatable("cbc_firecontrol.calculator.tasks.hint"),
				TrajectoryCalculatorMenu.TASK_BOARD_W), TrajectoryCalculatorMenu.TASK_BOARD_X,
				TrajectoryCalculatorMenu.TASK_BOARD_BOTTOM + 5, ConsoleTheme.TEXT_SECONDARY, false);
	}

	/** A cell's text and the colour that says what kind of entry it is. */
	private record Cell(Component text, int colour) {
	}

	/**
	 * The console's queue: orders it received but could not hand to any mount yet, because no card is in or
	 * nothing is in range. Once mounts take an order its own queue lives in the mount columns; with nothing
	 * pending, the console's first entry is the target it holds itself - what its mounts aim at when ordered.
	 */
	private static Cell consoleEntry(TrajectoryCalculatorBlockEntity be, int row) {
		if (be == null)
			return new Cell(Component.literal(DASH), ConsoleTheme.TEXT_SECONDARY);
		List<FireMission> pending = be.pendingMissionView();
		if (row < pending.size()) {
			FireMission mission = pending.get(row);
			return new Cell(coords(mission.x(), mission.y(), mission.z()), ConsoleTheme.COLOR_HIT);
		}
		if (row == 0 && be.hasTarget())
			return new Cell(coords(be.targetX(), be.targetY(), be.targetZ()), ConsoleTheme.COLOR_HIT);
		return new Cell(Component.literal(DASH), ConsoleTheme.TEXT_SECONDARY);
	}

	/** "x,y,z" - the compact form of an impact point, which the column tooltip spells out. */
	private static Component coords(double x, double y, double z) {
		return Component.literal(CalculatorTooltips.numberText(x) + "," + CalculatorTooltips.numberText(y)
				+ "," + CalculatorTooltips.numberText(z));
	}

	/**
	 * One mount's queue. Its first entry is the order in front of that mount and the phase it is in, with the
	 * refusal chances left while the mount still cannot take it; behind the front order the queue shows its
	 * depth as "+n" rather than repeating each waiting order, whose coordinates the console column already
	 * carries. A row holding no orders still shows the aim the console put on it by hand.
	 */
	private static Cell mountEntry(TrajectoryCalculatorScreen screen, TrajectoryCalculatorBlockEntity be, int slot,
			int row) {
		if (be == null)
			return new Cell(Component.literal(DASH), ConsoleTheme.TEXT_SECONDARY);
		if (!be.isSlotActive(slot))
			return new Cell(Component.translatable("cbc_firecontrol.calculator.tasks.cell.unlinked"),
					ConsoleTheme.TEXT_SECONDARY);
		int size = be.missionQueueSize(slot);
		if (row > 0)
			return size > row
					? new Cell(Component.translatable("cbc_firecontrol.calculator.tasks.cell.more", size - row),
							ConsoleTheme.TEXT_SECONDARY)
					: new Cell(Component.literal(DASH), ConsoleTheme.TEXT_SECONDARY);
		if (size > 0)
			return missionCell(be, slot);
		if (!be.hasTarget()) {
			LinkStatus status = be.getPrediction(slot).status();
			return new Cell(Component.translatable(status.shortLangKey()), CalculatorTooltips.colorFor(status));
		}
		AimSolution aim = be.getAim(slot);
		if (!aim.outcome().hasSolution())
			return new Cell(Component.translatable(aim.outcome().shortLangKey()),
					CalculatorTooltips.colorForOutcome(aim.outcome()));
		/* Whole degrees: a column is 66 pixels wide, and the column tooltip carries the precise pair. */
		int colour = be.linkOnAim(slot) ? ConsoleTheme.COLOR_OK
				: be.linkDriving(slot) ? ConsoleTheme.COLOR_STALLED
						: CalculatorTooltips.colorForOutcome(aim.outcome());
		return new Cell(Component.translatable("cbc_firecontrol.calculator.tasks.cell.aim",
				String.format("%.0f", aim.yawDeg()), String.format("%.0f", aim.pitchDeg())), colour);
	}

	/** The front order's phase in short form: what the mount is doing with the order in front of it. */
	private static Cell missionCell(TrajectoryCalculatorBlockEntity be, int slot) {
		MissionPhase phase = be.missionPhase(slot);
		return switch (phase) {
			case NONE -> new Cell(Component.literal(DASH), ConsoleTheme.TEXT_SECONDARY);
			case QUEUED -> new Cell(Component.translatable("cbc_firecontrol.calculator.tasks.cell.refusing",
					be.missionRejectionsLeft(slot)), ConsoleTheme.COLOR_ERROR);
			case AIMING -> new Cell(Component.translatable("cbc_firecontrol.calculator.tasks.cell.aiming"),
					ConsoleTheme.COLOR_STALLED);
			case FIRING -> new Cell(Component.translatable("cbc_firecontrol.calculator.tasks.cell.firing"),
					ConsoleTheme.COLOR_OK);
		};
	}

	@Override
	protected void foreground(TrajectoryCalculatorScreen screen, GuiGraphics graphics, int mouseX, int mouseY,
			float partialTicks) {
		int column = hoveredColumn(screen, mouseX, mouseY);
		if (column < 0)
			return;
		graphics.renderComponentTooltip(screen.consoleFont(), columnLines(screen, column), mouseX, mouseY);
	}

	/** Which queue column the mouse is over, counting the header band as part of it, or -1 outside the board. */
	private static int hoveredColumn(TrajectoryCalculatorScreen screen, int mouseX, int mouseY) {
		int top = screen.panelTop() + TrajectoryCalculatorMenu.TASK_HEADER_Y;
		int bottom = screen.panelTop() + TrajectoryCalculatorMenu.TASK_BOARD_BOTTOM;
		if (mouseY < top || mouseY > bottom)
			return -1;
		for (int column = 0; column < TrajectoryCalculatorMenu.TASK_COLUMNS; column++)
			if (isInside(mouseX, mouseY, screen.panelLeft() + columnLeft(column), top,
					columnRight(column) - columnLeft(column), bottom - top))
				return column;
		return -1;
	}

	/** The long form of a column, since the board itself shows only one abbreviated line per entry. */
	/** The long form of a front order's phase, which the short cell spells only in three or four letters. */
	private static String phaseKey(MissionPhase phase) {
		return switch (phase) {
			case NONE -> "cbc_firecontrol.calculator.tasks.cell.none";
			case QUEUED -> "cbc_firecontrol.calculator.tasks.phase.queued";
			case AIMING -> "cbc_firecontrol.calculator.tasks.phase.aiming";
			case FIRING -> "cbc_firecontrol.calculator.tasks.phase.firing";
		};
	}

	private static List<Component> columnLines(TrajectoryCalculatorScreen screen, int column) {
		List<Component> lines = new ArrayList<>();
		TrajectoryCalculatorBlockEntity be = calculator(screen);
		if (column == 0) {
			lines.add(Component.translatable("cbc_firecontrol.calculator.tasks.tooltip.console"));
			if (be != null && be.hasTarget())
				lines.add(Component.translatable("cbc_firecontrol.calculator.target.current",
						CalculatorTooltips.numberText(be.targetX()), CalculatorTooltips.numberText(be.targetY()),
						CalculatorTooltips.numberText(be.targetZ())));
			else
				lines.add(Component.translatable("cbc_firecontrol.calculator.target.none"));
			int pending = be == null ? 0 : be.pendingMissionView().size();
			if (pending > 0) {
				lines.add(Component.translatable("cbc_firecontrol.calculator.tasks.tooltip.pending", pending));
				for (FireMission mission : be.pendingMissionView())
					lines.add(coords(mission.x(), mission.y(), mission.z()));
			}
			lines.add(Component.translatable("cbc_firecontrol.calculator.tasks.tooltip.console.note"));
			return lines;
		}
		int slot = column - 1;
		lines.add(Component.translatable("cbc_firecontrol.calculator.tasks.tooltip.mount", slot + 1));
		lines.add(CalculatorTooltips.mountLine(screen, slot));
		if (be == null || !be.isSlotActive(slot))
			return lines;
		TrajectoryPrediction prediction = be.getPrediction(slot);
		lines.add(CalculatorTooltips.firstLine(slot, prediction.status()));
		int queueSize = be.missionQueueSize(slot);
		if (queueSize > 0) {
			Vec3 front = be.missionFrontTarget(slot);
			lines.add(Component.translatable("cbc_firecontrol.calculator.tasks.tooltip.mission",
					front == null ? Component.literal(DASH)
						: coords(front.x(), front.y(), front.z()),
					Component.translatable(phaseKey(be.missionPhase(slot))),
					be.missionRejectionsLeft(slot), queueSize));
		}
		lines.add(CalculatorTooltips.firstLine(slot, prediction.status()));
		if (be.hasTarget()) {
			AimSolution aim = be.getAim(slot);
			lines.add(CalculatorTooltips.aimLine(aim));
			if (aim.outcome().hasSolution())
				lines.add(Component.translatable(be.linkOnAim(slot)
						? "cbc_firecontrol.calculator.channel.on_aim"
						: "cbc_firecontrol.calculator.channel.driving"));
		} else {
			lines.add(CalculatorTooltips.solutionLine(prediction));
		}
		lines.add(Component.translatable("cbc_firecontrol.calculator.tasks.tooltip.mount.note"));
		return lines;
	}
}
