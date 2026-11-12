package com.cbc.firecontrol.client;

import com.cbc.firecontrol.menu.MountFrequencyMenu;
import com.cbc.firecontrol.network.MountAction;
import com.cbc.firecontrol.network.MountControlPayload;

import com.simibubi.create.foundation.gui.AllGuiTextures;
import com.simibubi.create.foundation.gui.AllIcons;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;
import com.simibubi.create.foundation.gui.widget.IconButton;

import net.createmod.catnip.gui.element.ScreenElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Create-style radio-link console for the Advanced Cannon Mount. Both yaw and pitch aim are radio-controlled
 * (Create redstone-link channels); the mount draws its kinetic stress requirement from its single bottom
 * face and will not turn/pitch without that stress input. This menu binds the 5 Create-link frequencies
 * (Yaw+/Yaw-/Pitch+/Pitch-/Fire) and has an Assemble/Disassemble toggle + a trash to clear frequencies.
 * Aim speed is NOT edited here: it is set on the mount's own Create-native value box (click-and-hold the
 * face to edit; the number shows on the face while you look at it), exactly like a Create controller.
 */
public class MountFrequencyScreen extends AbstractSimiContainerScreen<MountFrequencyMenu> {

	private static final String[] ACTION_NAMES = {"Yaw +", "Yaw -", "Pitch +", "Pitch -", "Fire"};

	// Custom mod-owned panel (advanced_cannon_mount_panel.png, 171x109) replaces Create's LINKED_CONTROLLER
	// background. It bakes the gold title bar, the 5 slot-column backgrounds, an EMPTY header band (we overlay
	// the directional arrow icons there) and a lower footer band with a small check glyph near its bottom-right.
	// Slot columns sit at x=12,36,60,84,108 (spacing 24) / top y=34, matching MountFrequencyMenu.addSlots exactly.
	private static final ResourceLocation MOUNT_PANEL =
			ResourceLocation.fromNamespaceAndPath("cbc_firecontrol", "textures/gui/advanced_cannon_mount_panel.png");
	private static final int PANEL_W = 171;
	private static final int PANEL_H = 109;
	private static final int SLOT_X0 = 12;
	private static final int SLOT_SPACING = 24;
	// Header arrows come straight from the copied Create gui icon atlas (icons.png). Per channel
	// [Yaw+, Yaw-, Pitch+, Pitch-, Fire]: Yaw+ = RIGHT (icons.png row11 col10), Yaw- = LEFT (row11 col6),
	// Pitch+ = UP (row1 col8), Pitch- = DOWN (row1 col5); -1 in ARROW_U = no arrow glyph (draw the Fire text instead).
	private static final int[] ARROW_U = {144, 80, 112, 64, -1};
	private static final int[] ARROW_V = {160, 160, 0, 0, -1};
	// The footer of the 171x109 skin bakes THREE 18x18 button boxes (measured, left->right): load/unload x=97..114,
	// clear-settings/trash x=119..136, exit-GUI x=146..163 (all y=85..102). Each gets a Create IconButton (identical
	// chrome) laid exactly on its baked box so all three read as native buttons.
	private static final int BTN_Y = 85;
	private static final int BTN_LOAD_X = 96;
	private static final int BTN_TRASH_X = 118;
	private static final int BTN_EXIT_X = 146;
	// The copied Create gui icon atlas (textures/gui/icons.png, 256x256 = 16x16 grid of 16px cells) is the source of
	// the footer load/unload (row6 col4) and exit (row2 col1) glyphs AND the four header arrows (see ARROW_U/ARROW_V).
	private static final ResourceLocation ICON_ATLAS =
			ResourceLocation.fromNamespaceAndPath("cbc_firecontrol", "textures/gui/icons.png");
	private static final int ICON_SHEET = 256;
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
		setWindowSize(PANEL_W, PANEL_H + 4 + AllGuiTextures.PLAYER_INVENTORY.getHeight());
		setWindowOffset(0, 0);
		super.init();

		// Three footer IconButtons, each laid squarely on its baked 18x18 box (identical Create chrome), left->right:
		// load/unload (icons.png row6 col4) -> assemble/disassemble toggle; clear-settings (trash); exit (icons.png row2 col1).
		IconButton loadButton = new IconButton(leftPos + BTN_LOAD_X, topPos + BTN_Y, atlasIcon(4, 6));
		loadButton.withCallback(this::sendToggle);
		addRenderableWidget(loadButton);

		resetButton = new IconButton(leftPos + BTN_TRASH_X, topPos + BTN_Y, AllIcons.I_TRASH);
		resetButton.withCallback(() -> {
			menu.clearContents();
			menu.pushToBlockEntity();
		});
		addRenderableWidget(resetButton);

		IconButton exitButton = new IconButton(leftPos + BTN_EXIT_X, topPos + BTN_Y, atlasIcon(1, 2));
		exitButton.withCallback(() -> Minecraft.getInstance().setScreen(null));
		addRenderableWidget(exitButton);

		PacketDistributor.sendToServer(new MountControlPayload(menu.contentHolder, MountAction.REQUEST, 0));
	}

	private void sendToggle() {
		MountAction action = (gotState && running) ? MountAction.DISASSEMBLE : MountAction.ASSEMBLE;
		PacketDistributor.sendToServer(new MountControlPayload(menu.contentHolder, action, 0));
	}

	// Builds a ScreenElement that blits a 16x16 cell (1-indexed col,row) from the copied Create gui icon atlas
	// (icons.png, 256x256). IconButton centers the 16px icon inside its 18px button exactly like an AllIcons glyph.
	private static ScreenElement atlasIcon(int col, int row) {
		int u = (col - 1) * 16;
		int v = (row - 1) * 16;
		return (graphics, x, y) -> graphics.blit(ICON_ATLAS, x, y, u, v, 16, 16, ICON_SHEET, ICON_SHEET);
	}

	/** Applied from the server MountStatePayload reply (see CBCFCNetwork.handleStateClient). */
	public void applyState(boolean running, float yaw, float pitch) {
		this.running = running;
		this.yaw = yaw;
		this.pitch = pitch;

		this.gotState = true;
	}

	@Override
	protected void renderBg(GuiGraphics graphics, float partialTicks, int mouseX, int mouseY) {
		int x = leftPos;
		int y = topPos;
		// Blit the custom panel at native 171x109 (it is a full texture, not an atlas sub-region).
		graphics.blit(MOUNT_PANEL, x, y, 0, 0, PANEL_W, PANEL_H, PANEL_W, PANEL_H);
		// Title centered on the gold bar WITHOUT the text drop-shadow (textShadow=false).
		graphics.drawString(this.font, this.title, x + (PANEL_W - this.font.width(this.title)) / 2, y + 3, 0x3a2f12, false);

		// Left-align the player-inventory frame with the panel behind it (same left edge) so the grid lines up.
		int invX = leftPos;
		int invY = y + PANEL_H + 4;
		renderPlayerInventory(graphics, invX, invY);
	}

	@Override
	protected void renderForeground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
		int bandTop = topPos + 15;
		for (int i = 0; i < MountFrequencyMenu.CHANNELS; i++) {
			int slotX = leftPos + SLOT_X0 + SLOT_SPACING * i;
			int u = ARROW_U[i];
			if (u >= 0) {
				// 16x16 arrow cell taken straight from the Create icon atlas (icons.png) header band.
				graphics.blit(ICON_ATLAS, slotX + 1, bandTop, u, ARROW_V[i], 16, 16, ICON_SHEET, ICON_SHEET);
			} else {
				// Fire column stays a text label (no arrow glyph), centered over its column, no drop-shadow.
				String fire = ACTION_NAMES[i];
				graphics.drawString(this.font, fire, slotX + 9 - this.font.width(fire) / 2, bandTop + 6, 0xE0E0E0, false);
			}
		}
	}
}
