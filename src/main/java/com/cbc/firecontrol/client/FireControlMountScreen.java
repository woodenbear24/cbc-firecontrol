package com.cbc.firecontrol.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlock;
import com.cbc.firecontrol.network.MountAction;
import com.cbc.firecontrol.network.MountControlPayload;

/**
 * Client-side status screen opened by empty-hand right-click on a Fire Control Mount
 * (see {@link com.cbc.firecontrol.block.FireControlMountBlock#useWithoutItem}).
 *
 * Read-only display of the mount's orientation, redstone assembly/fire flags and the
 * cannon-assembly cell it drives into. Interactive controls (fire / rotate / assemble buttons)
 * need a server round-trip (custom packet payload or a container menu) and are a follow-up; this
 * Interactive console: buttons send a {@link com.cbc.firecontrol.network.MountControlPayload} to the
 * server, which performs the action on the mount block entity and replies with a
 * {@link com.cbc.firecontrol.network.MountStatePayload} carrying authoritative running/yaw/pitch.
 * Static info (facing, orientation, redstone flags, cannon cell) is read from the client BlockState.
 */
public class FireControlMountScreen extends Screen {

	private final BlockPos pos;

	// Authoritative mount state pushed by the server via MountStatePayload (see CBCFCNetwork).
	private boolean gotState;
	private boolean running;
	private float yaw;
	private float pitch;

	public FireControlMountScreen(BlockPos pos) {
		super(Component.translatable("block.cbc_firecontrol.fire_control_mount"));
		this.pos = pos.immutable();
	}

	@Override
	protected void init() {
		int bw = 56;
		int bh = 20;
		int gap = 6;

		int rowY = this.height - 78;
		int total1 = 3 * bw + 2 * gap;
		int x1 = (this.width - total1) / 2;
		addButton("Assemble", x1, rowY, bw, bh, MountAction.ASSEMBLE, 0);
		addButton("Disassemble", x1 + (bw + gap), rowY, bw, bh, MountAction.DISASSEMBLE, 0);
		addButton("Fire", x1 + 2 * (bw + gap), rowY, bw, bh, MountAction.FIRE, 0);

		int rowY2 = this.height - 52;
		int total2 = 4 * bw + 3 * gap;
		int x2 = (this.width - total2) / 2;
		addButton("Yaw -", x2, rowY2, bw, bh, MountAction.YAW, -15);
		addButton("Yaw +", x2 + (bw + gap), rowY2, bw, bh, MountAction.YAW, 15);
		addButton("Pitch -", x2 + 2 * (bw + gap), rowY2, bw, bh, MountAction.PITCH, -10);
		addButton("Pitch +", x2 + 3 * (bw + gap), rowY2, bw, bh, MountAction.PITCH, 10);

		this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> this.onClose())
				.bounds((this.width - 120) / 2, this.height - 26, 120, 20).build());

		// Ask the server for authoritative state right after opening.
		PacketDistributor.sendToServer(new MountControlPayload(this.pos, MountAction.REQUEST, 0));
	}

	private void addButton(String label, int x, int y, int w, int h, MountAction action, int amount) {
		this.addRenderableWidget(Button.builder(Component.literal(label), b -> send(action, amount))
				.bounds(x, y, w, h).build());
	}

	private void send(MountAction action, int amount) {
		PacketDistributor.sendToServer(new MountControlPayload(this.pos, action, amount));
	}

	/** Applied from the server MountStatePayload reply (see CBCFCNetwork.handleStateClient). */
	public void applyState(boolean running, float yaw, float pitch) {
		this.running = running;
		this.yaw = yaw;
		this.pitch = pitch;
		this.gotState = true;
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		// Paint our own translucent backdrop instead of Screen#renderBackground (its GuiGraphics
		// overload signature differs across 1.21.x mappings); keeps the panel readable everywhere.
		graphics.fill(0, 0, this.width, this.height, 0xC0101010);
		super.render(graphics, mouseX, mouseY, partialTick);

		int cx = this.width / 2;
		graphics.drawCenteredString(this.font, this.title, cx, 18, 0xFFFFFF);

		Level level = Minecraft.getInstance().level;
		if (level == null) {
			graphics.drawCenteredString(this.font, Component.literal("(no world)"), cx, 40, 0xB0B0B0);
			return;
		}
		BlockState state = level.getBlockState(this.pos);
		if (state.isAir()) {
			graphics.drawCenteredString(this.font, Component.literal("(block gone)"), cx, 40, 0xB0B0B0);
			return;
		}

		Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
		Direction vertical = state.getValue(BlockStateProperties.VERTICAL_DIRECTION);
		boolean upright = vertical == Direction.DOWN;
		boolean assemblyPowered = readBool(state, CannonMountBlock.ASSEMBLY_POWERED);
		boolean firePowered = readBool(state, CannonMountBlock.FIRE_POWERED);
		Direction cannonDir = vertical.getOpposite();
		BlockPos assemblyCell = this.pos.relative(cannonDir);

		int y = 40;
		int line = 12;
		draw(graphics, cx, y, "Orientation: " + (upright ? "upright (cannon above)" : "inverted (cannon below)"));
		y += line;
		draw(graphics, cx, y, "Facing: " + cap(facing.getSerializedName()));
		y += line;
		draw(graphics, cx, y, "Cannon cell: " + assemblyCell.getX() + ", " + assemblyCell.getY() + ", " + assemblyCell.getZ());
		y += line;
		if (!this.gotState) {
			draw(graphics, cx, y, "Status: querying server...", 0xB0B0B0);
		} else {
			draw(graphics, cx, y, "State: " + (this.running ? "ASSEMBLED (running)" : "idle (not assembled)"));
			y += line;
			draw(graphics, cx, y, "Yaw: " + formatDeg(this.yaw) + "    Pitch: " + formatDeg(this.pitch));
			y += line;
			draw(graphics, cx, y, "Assembly redstone: " + assemblyPowered + "    Fire redstone: " + firePowered);
		}
	}

	private void draw(GuiGraphics graphics, int cx, int y, String text) {
		draw(graphics, cx, y, text, 0xE0E0E0);
	}

	private void draw(GuiGraphics graphics, int cx, int y, String text, int color) {
		graphics.drawCenteredString(this.font, Component.literal(text), cx, y, color);
	}

	private static String cap(String s) {
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	private static String formatDeg(float deg) {
		int whole = Math.round(deg);
		return (whole >= 0 ? "+" : "") + whole + "\u00b0";
	}

	private static boolean readBool(BlockState state, BooleanProperty property) {
		return state.hasProperty(property) && state.getValue(property);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
