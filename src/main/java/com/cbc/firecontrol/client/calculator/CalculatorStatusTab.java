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
 * The status page: the same rows as the links page, with the numbers that do not fit next to a card slot.
 *
 * <p>On the links page a row has 148 pixels before the channel grid, so impact coordinates and the charge/barrel
 * detail are pushed into the tooltip. Here the row spans the whole panel, and the values a gunner reads before
 * firing - target aim, current aim, impact point, muzzle speed and flight time - are all on the row itself.
 */
public class CalculatorStatusTab extends CalculatorTab {

	public CalculatorStatusTab() {
		super(TrajectoryCalculatorMenu.TAB_STATUS, "cbc_firecontrol.calculator.tab.status");
	}

	/** Full width a status line may use, from the left margin to the right one. */
	private static int lineWidth(TrajectoryCalculatorScreen screen) {
		return TrajectoryCalculatorMenu.PANEL_WIDTH - 16;
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
		int headerY = TrajectoryCalculatorMenu.CONTENT_TOP + 2;
		graphics.drawString(screen.consoleFont(), Component.translatable("cbc_firecontrol.calculator.status.title"),
				8, headerY, ConsoleTheme.HEADER_TEXT, false);

		TrajectoryCalculatorBlockEntity be = calculator(screen);
		boolean aiming = be != null && be.hasTarget();
		for (int i = 0; i < TrajectoryCalculatorMenu.SLOTS; i++) {
			TrajectoryPrediction prediction = be != null ? be.getPrediction(i) : TrajectoryPrediction.EMPTY;
			AimSolution aim = be != null ? be.getAim(i) : AimSolution.EMPTY;
			LinkStatus status = prediction.status();
			int rowTop = rowTop(screen, i) - screen.panelTop();
			int width = lineWidth(screen) - 16;

			graphics.drawString(screen.consoleFont(),
					fit(screen.consoleFont(), CalculatorTooltips.firstLine(i, status).append("  ")
							.append(CalculatorTooltips.mountLine(screen, i)), width),
					16, rowTop + 1, CalculatorTooltips.colorFor(status), false);

			Component second = aiming && aim.outcome().hasSolution()
					? aimReadout(aim)
					: status.hasSolution() ? shotReadout(prediction) : Component.translatable(status.langKey());
			graphics.drawString(screen.consoleFont(), fit(screen.consoleFont(), second, width), 16, rowTop + 10,
					aiming ? CalculatorTooltips.colorForOutcome(aim.outcome()) : ConsoleTheme.TEXT_SECONDARY, false);
		}
	}

	/** The forward shot: where the round lands, how fast it leaves the muzzle and how long it flies. */
	private static Component shotReadout(TrajectoryPrediction p) {
		return Component.translatable("cbc_firecontrol.calculator.detail.aim", String.format("%.1f", p.yawDeg()),
				String.format("%.1f", p.pitchDeg()))
				.append("  ").append(Component.translatable("cbc_firecontrol.calculator.detail.speed",
						Component.literal(String.format("%.1f", p.muzzleSpeed())),
						Component.literal(Integer.toString(p.flightTicks()))))
				.append("  ").append(Component.translatable("cbc_firecontrol.calculator.detail.impact",
						CalculatorTooltips.numberText(p.impactX()), CalculatorTooltips.numberText(p.impactY()),
						CalculatorTooltips.numberText(p.impactZ())));
	}

	/** The aim the entered target needs, with the miss it would leave. */
	private static Component aimReadout(AimSolution aim) {
		return Component.translatable("cbc_firecontrol.calculator.aimdetail.aim",
				String.format("%.2f", aim.yawDeg()), String.format("%.2f", aim.pitchDeg()),
				Component.translatable(aim.arcLangKey()))
				.append("  ").append(Component.translatable("cbc_firecontrol.calculator.aimdetail.impact",
						CalculatorTooltips.numberText(aim.impactX()), CalculatorTooltips.numberText(aim.impactY()),
						CalculatorTooltips.numberText(aim.impactZ())))
				.append("  ").append(Component.translatable("cbc_firecontrol.calculator.aimdetail.miss",
						String.format("%.1f", aim.missDistance()), String.format("%.1f", aim.impactPlanRange()),
						String.format("%.1f", aim.targetPlanRange()), String.format("%.2f", aim.altitudeError())));
	}

	@Override
	protected void foreground(TrajectoryCalculatorScreen screen, GuiGraphics graphics, int mouseX, int mouseY,
			float partialTicks) {
		TrajectoryCalculatorBlockEntity be = calculator(screen);
		boolean aiming = be != null && be.hasTarget();
		for (int i = 0; i < TrajectoryCalculatorMenu.SLOTS; i++) {
			if (!isInside(mouseX, mouseY, screen.panelLeft() + 8,
					rowTop(screen, i) - TrajectoryCalculatorMenu.ROW_BAND_INSET,
					TrajectoryCalculatorMenu.PANEL_WIDTH - 16, TrajectoryCalculatorMenu.ROW_BAND_H))
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
