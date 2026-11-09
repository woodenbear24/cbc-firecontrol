package com.cbc.firecontrol.client;

import com.cbc.firecontrol.menu.MountFrequencyMenu;
import com.cbc.firecontrol.network.MountAction;
import com.cbc.firecontrol.network.MountControlPayload;

import com.simibubi.create.foundation.gui.AllGuiTextures;
import com.simibubi.create.foundation.gui.AllIcons;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;
import com.simibubi.create.foundation.gui.widget.IconButton;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Create-style radio-link console for the Fire Control Mount. Both yaw and pitch aim are radio-controlled
 * (Create redstone-link channels); the mount draws its kinetic stress requirement from its single bottom
 * face and will not turn/pitch without that stress input. This menu binds the 5 Create-link frequencies
 * (Yaw+/Yaw-/Pitch+/Pitch-/Fire) and has an Assemble/Disassemble toggle + a trash to clear frequencies.
 * Aim speed is NOT edited here: it is set on the mount's own Create-native value box (click-and-hold the
 * face to edit; the number shows on the face while you look at it), exactly like a Create controller.
 */
public class MountFrequencyScreen extends AbstractSimiContainerScreen<MountFrequencyMenu> {

	private static final String[] ACTION_NAMES = {"Yaw +", "Yaw -", "Pitch +", "Pitch -", "Fire"};

	private final AllGuiTextures background = AllGuiTextures.LINKED_CONTROLLER;
	private Button toggleButton;
	private IconButton resetButton;

	private boolean gotState;
	private boolean running;
	private float yaw;
	private float pitch;

	public MountFrequencyScreen(MountFrequencyMenu menu, Inventory inv, Component title) {
		super(menu, inv, title);
	}

	@Override
	protected void init() {
		setWindowSize(background.getWidth(), background.getHeight() + 4 + AllGuiTextures.PLAYER_INVENTORY.getHeight());
		setWindowOffset(1, 0);
		super.init();

		int panelX = leftPos;
		int panelBottom = topPos + background.getHeight();

		resetButton = new IconButton(panelX + background.getWidth() - 62, panelBottom - 24, AllIcons.I_TRASH);
		resetButton.withCallback(() -> {
			menu.clearContents();
			menu.pushToBlockEntity();
		});
		addRenderableWidget(resetButton);

		int invBottom = panelBottom + 4 + AllGuiTextures.PLAYER_INVENTORY.getHeight();
		int bw = 140;
		toggleButton = Button.builder(toggleLabel(), b -> sendToggle())
				.bounds((this.width - bw) / 2, invBottom + 8, bw, 20).build();
		addRenderableWidget(toggleButton);

		PacketDistributor.sendToServer(new MountControlPayload(menu.contentHolder, MountAction.REQUEST, 0));
	}

	private Component toggleLabel() {
		return Component.literal(gotState && running ? "Disassemble" : "Assemble");
	}

	private void sendToggle() {
		MountAction action = (gotState && running) ? MountAction.DISASSEMBLE : MountAction.ASSEMBLE;
		PacketDistributor.sendToServer(new MountControlPayload(menu.contentHolder, action, 0));
	}

	/** Applied from the server MountStatePayload reply (see CBCFCNetwork.handleStateClient). */
	public void applyState(boolean running, float yaw, float pitch) {
		this.running = running;
		this.yaw = yaw;
		this.pitch = pitch;

		this.gotState = true;
		if (toggleButton != null)
			toggleButton.setMessage(toggleLabel());
	}

	@Override
	protected void renderBg(GuiGraphics graphics, float partialTicks, int mouseX, int mouseY) {
		int invX = getLeftOfCentered(AllGuiTextures.PLAYER_INVENTORY.getWidth());
		int invY = topPos + background.getHeight() + 4;
		renderPlayerInventory(graphics, invX, invY);

		int x = leftPos;
		int y = topPos;
		background.render(graphics, x, y);
		graphics.drawCenteredString(this.font, this.title, x + background.getWidth() / 2, y + 4, 0x592424);
	}

	@Override
	protected void renderForeground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
		for (int i = 0; i < MountFrequencyMenu.CHANNELS; i++) {
			int slotX = leftPos + 12 + 24 * i;
			graphics.drawCenteredString(this.font, ACTION_NAMES[i], slotX + 9, topPos + 18, 0xE0E0E0);
		}

		int statusX = leftPos + 12 + 24 * MountFrequencyMenu.CHANNELS + 6;
		int statusY = topPos + 30;
		graphics.drawString(this.font, "CH1..5", statusX, statusY, 0xB0B0B0);
		if (this.gotState) {
			graphics.drawString(this.font, running ? "ASSEMBLED" : "idle", statusX, statusY + 14, 0xC0FFC0);
			graphics.drawString(this.font, "yaw " + Math.round(yaw) + " deg", statusX, statusY + 26, 0xE0E0E0);
			graphics.drawString(this.font, "pitch " + Math.round(pitch) + " deg", statusX, statusY + 38, 0xE0E0E0);

		} else {
			graphics.drawString(this.font, "...", statusX, statusY + 14, 0xB0B0B0);
		}

		graphics.drawCenteredString(this.font, "Items in a column set the Create link frequency for that action",
				leftPos + background.getWidth() / 2, topPos + background.getHeight() - 4, 0x909090);
	}
}
