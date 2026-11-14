package com.cbc.firecontrol.client.calculator;

import com.cbc.firecontrol.ballistics.AimSolution;
import com.cbc.firecontrol.ballistics.LinkStatus;
import com.cbc.firecontrol.ballistics.TrajectoryPrediction;
import com.cbc.firecontrol.block.entity.TrajectoryCalculatorBlockEntity;
import com.cbc.firecontrol.client.TrajectoryCalculatorScreen;
import com.cbc.firecontrol.client.gui.ConsoleTheme;
import com.cbc.firecontrol.menu.TrajectoryCalculatorMenu;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * The links page: five card slots, each with the readout of the mount its card points at.
 *
 * <p>Two modes of the same row, exactly as before the pages existed. With no target entered a row reports the shot
 * the mount would fire right now; once a target is entered it reports the aim that would hit it instead, because
 * that is the question the gunner is asking at that moment. The forward prediction is never lost either way - it
 * stays in the row tooltip.
 */
public class CalculatorLinkTab extends CalculatorTab {

	public CalculatorLinkTab() {
		super(TrajectoryCalculatorMenu.TAB_LINKS, "cbc_firecontrol.calculator.tab.links");
	}

	@Override
	protected void background(TrajectoryCalculatorScreen screen, GuiGraphics graphics) {
		int x = screen.panelLeft();
		int w = TrajectoryCalculatorMenu.PANEL_WIDTH;
		for (int i = 0; i < TrajectoryCalculatorMenu.SLOTS; i++) {
			int rowY = rowTop(screen, i) - TrajectoryCalculatorMenu.ROW_BAND_INSET;
			graphics.fill(x + 8, rowY, x + w - 8, rowY + TrajectoryCalculatorMenu.ROW_BAND_H, ConsoleTheme.ROW_BG);
		}
	}

	@Override
	protected void labels(TrajectoryCalculatorScreen screen, GuiGraphics graphics, int mouseX, int mouseY) {
		/* Panel-local coordinates here: renderLabels runs inside the pose translated by the panel position. */
		int headerY = TrajectoryCalculatorMenu.CONTENT_TOP + 2;
		int summaryX = screen.consoleFont().width(screen.getTitle()) + 20;
		graphics.drawString(screen.consoleFont(), screen.getTitle(), 8, headerY, ConsoleTheme.HEADER_TEXT, false);
		graphics.drawString(screen.consoleFont(),
				fit(screen.consoleFont(), Component.translatable("container.cbc_firecontrol.trajectory_calculator.slots",
						TrajectoryCalculatorMenu.SLOTS, TrajectoryCalculatorBlockEntity.LINK_RANGE),
						TrajectoryCalculatorMenu.PANEL_WIDTH - 8 - summaryX),
				summaryX, headerY, ConsoleTheme.TEXT_SECONDARY, false);

		TrajectoryCalculatorBlockEntity be = calculator(screen);
		boolean aiming = be != null && be.hasTarget();
		for (int i = 0; i < TrajectoryCalculatorMenu.SLOTS; i++) {
			TrajectoryPrediction prediction = be != null ? be.getPrediction(i) : TrajectoryPrediction.EMPTY;
			LinkStatus status = prediction.status();
			AimSolution aim = be != null ? be.getAim(i) : AimSolution.EMPTY;
			int lineTop = rowTop(screen, i) - screen.panelTop() + 1;
			int lineBottom = lineTop + 9;
			int textX = textLeft();
			graphics.drawString(screen.consoleFont(), fit(screen.consoleFont(),
					CalculatorTooltips.firstLine(i, status), TrajectoryCalculatorMenu.ROW_TEXT_W), textX, lineTop,
					CalculatorTooltips.colorFor(status), false);
			if (aiming)
				graphics.drawString(screen.consoleFont(), fit(screen.consoleFont(),
						CalculatorTooltips.aimLine(aim), TrajectoryCalculatorMenu.ROW_TEXT_W), textX, lineBottom,
						CalculatorTooltips.colorForOutcome(aim.outcome()), false);
			else if (status.hasSolution())
				graphics.drawString(screen.consoleFont(), fit(screen.consoleFont(),
						CalculatorTooltips.solutionLine(prediction), TrajectoryCalculatorMenu.ROW_TEXT_W), textX,
						lineBottom, ConsoleTheme.TEXT_SECONDARY, false);
			else
				graphics.drawString(screen.consoleFont(), fit(screen.consoleFont(),
						Component.translatable("cbc_firecontrol.calculator.readout.none"),
						TrajectoryCalculatorMenu.ROW_TEXT_W), textX, lineBottom, ConsoleTheme.TEXT_SECONDARY, false);
		}
	}

	@Override
	protected void foreground(TrajectoryCalculatorScreen screen, GuiGraphics graphics, int mouseX, int mouseY,
			float partialTicks) {
		TrajectoryCalculatorBlockEntity be = calculator(screen);
		boolean aiming = be != null && be.hasTarget();
		for (int i = 0; i < TrajectoryCalculatorMenu.SLOTS; i++) {
			// The row tooltip covers the card slot and the readout column only; the card item's own tooltip is
			// drawn by vanilla over the slot, and two tooltips in one frame would overlap.
			if (!isInside(mouseX, mouseY, screen.panelLeft() + 8,
					rowTop(screen, i) - TrajectoryCalculatorMenu.ROW_BAND_INSET,
					TrajectoryCalculatorMenu.CHANNEL_SLOT_X - 16, TrajectoryCalculatorMenu.ROW_BAND_H))
				continue;
			TrajectoryPrediction prediction = be != null ? be.getPrediction(i) : TrajectoryPrediction.EMPTY;
			AimSolution aim = be != null ? be.getAim(i) : AimSolution.EMPTY;
			graphics.renderComponentTooltip(screen.consoleFont(),
					aiming ? CalculatorTooltips.aimDetailLines(screen, i, aim, prediction)
							: CalculatorTooltips.detailLines(screen, i, prediction),
					mouseX, mouseY);
		}
	}
}
