package com.cbc.firecontrol.client.calculator;

import java.util.ArrayList;
import java.util.List;

import com.cbc.firecontrol.ballistics.AimOutcome;
import com.cbc.firecontrol.ballistics.AimSolution;
import com.cbc.firecontrol.ballistics.LinkStatus;
import com.cbc.firecontrol.ballistics.MaxReach;
import com.cbc.firecontrol.ballistics.TrajectoryPrediction;
import com.cbc.firecontrol.block.entity.TrajectoryCalculatorBlockEntity;
import com.cbc.firecontrol.client.TrajectoryCalculatorScreen;
import com.cbc.firecontrol.client.gui.ConsoleTheme;
import com.cbc.firecontrol.init.CBCItems;
import com.cbc.firecontrol.item.CalculatorLinkData;
import com.cbc.firecontrol.menu.TrajectoryCalculatorMenu;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;

/**
 * The text of the calculator console, in the order a gunner reads it.
 *
 * <p>Shared by the pages because a row's tooltip and the status page answer the same question with the same
 * numbers, and the wording of a verdict must not drift between the two places it is shown.
 */
public final class CalculatorTooltips {

	private CalculatorTooltips() {
	}

	/** Row header: the slot number and the one-word state of its link. */
	public static MutableComponent firstLine(int slot, LinkStatus status) {
		return Component.translatable("cbc_firecontrol.calculator.readout.slot", slot + 1,
				Component.translatable(status.shortLangKey()));
	}

	/** Which mount this row's card points at, for the line a header has no room for. */
	public static Component mountLine(TrajectoryCalculatorScreen screen, int slot) {
		ItemStack stack = screen.consoleMenu().getHandler().getStackInSlot(slot);
		CalculatorLinkData link = stack.get(CBCItems.CALCULATOR_LINK_DATA.get());
		if (link == null)
			return Component.translatable("cbc_firecontrol.calculator.readout.no_mount");
		return Component.translatable("cbc_firecontrol.calculator.detail.mount", link.mount().getX(),
				link.mount().getY(), link.mount().getZ());
	}

	/**
	 * The row's mount reach as the short line a header can carry: the farthest landing point its next shot
	 * makes, measured from that mount. Null when the row has nothing shootable, which its status already says -
	 * printing "0" would read as a gun that cannot fire rather than as no gun at all.
	 */
	public static Component maxRangeLine(TrajectoryCalculatorBlockEntity be, int slot) {
		if (be == null)
			return null;
		MaxReach reach = be.getMaxReach(slot);
		if (!reach.reachable())
			return null;
		return Component.translatable("cbc_firecontrol.calculator.readout.max_range",
				String.format("%.0f", reach.straightRange()));
	}

	/**
	 * Row body: the three numbers a gunner acts on - how far the shot throws, how fast it leaves the muzzle and
	 * how long it is in the air. The impact coordinates live in the tooltip instead: with the mount's own
	 * coordinate already in the header line, six numbers cannot fit between the card slot and the channel grid
	 * (148 pixels) without covering the filter slots.
	 */
	public static Component solutionLine(TrajectoryPrediction p) {
		return Component.translatable("cbc_firecontrol.calculator.readout.solution",
				Component.literal(String.format("%.0f", p.horizontalRange())),
				Component.literal(String.format("%.1f", p.muzzleSpeed())),
				Component.literal(Integer.toString(p.flightTicks())));
	}

	/** Row body in target mode: the aim this mount would need, or the reason it cannot have one. */
	public static Component aimLine(AimSolution aim) {
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

	/** Colour for a link verdict. */
	public static int colorFor(LinkStatus status) {
		return switch (status) {
		case OK -> ConsoleTheme.COLOR_OK;
		case BLOCK_HIT -> ConsoleTheme.COLOR_HIT;
		case LIFETIME_END, OUT_OF_WORLD -> ConsoleTheme.COLOR_STALLED;
		case EMPTY_SLOT, NOT_A_LINK, UNBOUND -> ConsoleTheme.TEXT_SECONDARY;
		// Terrain is on its way for this row: not an error, and not ready either.
		case MOUNT_LOADING -> ConsoleTheme.COLOR_STALLED;
		default -> ConsoleTheme.COLOR_ERROR;
		};
	}

	/**
	 * Colour for a solution verdict. Blue is deliberate on the fixable failures: ELEVATION_LIMITED and
	 * CHARGE_INSUFFICIENT both come with a number the gunner can act on, so they must not read like a dead end.
	 */
	public static int colorForOutcome(AimOutcome outcome) {
		return switch (outcome) {
		case HIT -> ConsoleTheme.COLOR_OK;
		case RESIDUAL -> ConsoleTheme.COLOR_HIT;
		case BLOCKED, ELEVATION_LIMITED, CHARGE_INSUFFICIENT -> ConsoleTheme.COLOR_STALLED;
		default -> ConsoleTheme.COLOR_ERROR;
		};
	}

	/** Full detail of one forward prediction, under the row's own line. */
	public static List<Component> detailLines(TrajectoryCalculatorScreen screen, int slot, TrajectoryPrediction p) {
		List<Component> lines = new ArrayList<>();
		LinkStatus status = p.status();
		lines.add(Component.translatable("cbc_firecontrol.calculator.detail.title", slot + 1));
		lines.add(mountLine(screen, slot));
		lines.add(Component.translatable("cbc_firecontrol.calculator.detail.status",
				Component.translatable(status.langKey())));
		TrajectoryCalculatorBlockEntity be = screen.clientCalculator();
		if (status == LinkStatus.NO_ROUND && be != null)
			lines.add(Component.translatable(be.roundState(slot).langKey()));
		if (be != null && be.linkDriving(slot))
			lines.add(Component.translatable(
					be.linkOnAim(slot) ? "cbc_firecontrol.calculator.channel.on_aim"
							: "cbc_firecontrol.calculator.channel.driving"));
		lines.add(Component.translatable("cbc_firecontrol.calculator.detail.aim", String.format("%.1f", p.yawDeg()),
				String.format("%.1f", p.pitchDeg())));
		lines.add(Component.translatable("cbc_firecontrol.calculator.detail.speed",
				Component.literal(String.format("%.2f", p.muzzleSpeed())),
				Component.literal(Integer.toString(p.flightTicks()))));
		lines.add(Component.translatable("cbc_firecontrol.calculator.detail.range",
				Component.literal(String.format("%.1f", p.horizontalRange())),
				Component.literal(String.format("%.1f", p.straightRange()))));
		lines.add(Component.translatable("cbc_firecontrol.calculator.detail.impact",
				Integer.toString((int) Math.round(p.impactX())), Integer.toString((int) Math.round(p.impactY())),
				Integer.toString((int) Math.round(p.impactZ()))));
		/*
		 * The mount's own reach with the load it holds, which is what tells a gunner whether a target they are
		 * considering is inside this gun at all. It is a property of the mount rather than of the target, so it
		 * is shown whether or not a target has been entered, and it only moves when the gun or its powder does.
		 */
		if (be != null) {
			MaxReach reach = be.getMaxReach(slot);
			if (reach.reachable())
				lines.add(Component.translatable("cbc_firecontrol.calculator.detail.max_range",
						Component.literal(String.format("%.1f", reach.planRange())),
						Component.literal(String.format("%.1f", reach.straightRange())),
						Component.literal(String.format("%.2f", reach.pitchDeg())),
						Component.literal(Integer.toString(reach.flightTicks()))));
		}
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

	/** Full detail of one solution, with the forward prediction kept underneath it. */
	public static List<Component> aimDetailLines(TrajectoryCalculatorScreen screen, int slot, AimSolution aim,
			TrajectoryPrediction prediction) {
		List<Component> lines = new ArrayList<>();
		lines.add(Component.translatable("cbc_firecontrol.calculator.aimdetail.title", slot + 1));
		lines.add(mountLine(screen, slot));
		if (aim == AimSolution.EMPTY) {
			lines.add(Component.translatable("cbc_firecontrol.calculator.aimdetail.pending"));
			lines.addAll(detailLines(screen, slot, prediction));
			return lines;
		}
		lines.add(Component.translatable("cbc_firecontrol.calculator.aimdetail.outcome",
				Component.translatable(aim.outcome().langKey())));
		lines.add(Component.translatable("cbc_firecontrol.calculator.aimdetail.aim",
				String.format("%.2f", aim.yawDeg()), String.format("%.2f", aim.pitchDeg()),
				Component.translatable(aim.arcLangKey())));
		if (aim.outcome() == AimOutcome.BLOCKED)
			lines.add(Component.translatable("cbc_firecontrol.calculator.aimdetail.blocked"));
		if (aim.hasSuggestedPitch())
			lines.add(Component.translatable("cbc_firecontrol.calculator.aimdetail.suggest_elev",
					String.format("%.2f", aim.suggestedPitchDeg())));
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
				String.format("%.2f", prediction.yawDeg()), String.format("%.2f", prediction.pitchDeg())));
		lines.add(Component.translatable("cbc_firecontrol.calculator.aimdetail.convention"));
		lines.addAll(detailLines(screen, slot, prediction));
		return lines;
	}

	/**
	 * Channel tooltip: what this column means, its two filter items, and whether it can transmit right now.
	 *
	 * <p>An unarmed row transmits nothing, so the console signal is only what a gunner would get by hand; an armed
	 * row shows the correction the link is driving, which is proportional to the angular error still left on this
	 * axis.
	 */
	public static List<Component> channelLines(TrajectoryCalculatorScreen screen, int slot, int channel) {
		List<Component> lines = new ArrayList<>();
		TrajectoryCalculatorMenu menu = screen.consoleMenu();
		lines.add(Component.translatable("cbc_firecontrol.calculator.channel.hover", slot + 1, channel + 1,
				Component.translatable("cbc_firecontrol.calculator.channel." + channel)));
		for (int filter = 0; filter < TrajectoryCalculatorBlockEntity.FILTERS_PER_CHANNEL; filter++) {
			ItemStack filterStack = menu.getGhostHandler()
					.getStackInSlot(TrajectoryCalculatorBlockEntity.channelIndex(slot, channel, filter));
			lines.add(filterStack.isEmpty() ? Component.literal("-") : filterStack.getDisplayName());
		}
		lines.add(Component.translatable("cbc_firecontrol.calculator.channel.hint"));
		TrajectoryCalculatorBlockEntity be = screen.clientCalculator();
		if (be == null || !be.isSlotActive(slot)) {
			lines.add(Component.translatable("cbc_firecontrol.calculator.channel.inactive"));
			return lines;
		}
		if (!be.linkDriving(slot)) {
			lines.add(Component.translatable("cbc_firecontrol.calculator.channel.idle_line",
					be.getEmittedLinkPower()));
			return lines;
		}
		lines.add(Component.translatable("cbc_firecontrol.calculator.channel.power",
				be.displayedLinkLevel(slot, channel)));
		lines.add(Component.translatable("cbc_firecontrol.calculator.channel.deadband",
				String.format("%.2f", be.aimDeadband(slot)), String.format("%.2f", be.aimExitBand(slot))));
		lines.add(Component.translatable(
				be.linkOnAim(slot) ? "cbc_firecontrol.calculator.channel.on_aim"
						: "cbc_firecontrol.calculator.channel.driving"));
		return lines;
	}

	/** A coordinate as text: integral values print as integers, so a block position does not read as "1.000". */
	public static String numberText(double value) {
		if (value == Math.rint(value))
			return Integer.toString((int) value);
		return String.format("%.3f", value);
	}

	/** Shorten a line to the width the layout has for it, using the console's own font metrics. */
	public static Component fit(TrajectoryCalculatorScreen screen, Component text, int width) {
		Font font = screen.consoleFont();
		if (font.width(text) <= width)
			return text;
		String full = text.getString();
		int budget = width - font.width("...");
		int keep = 0;
		while (keep < full.length() && font.width(full.substring(0, keep + 1)) <= budget)
			keep++;
		return Component.literal(full.substring(0, Math.max(1, keep)) + "...");
	}
}
