package com.cbc.firecontrol.client;

import com.cbc.firecontrol.CBCFireControl;
import com.cbc.firecontrol.item.TargetDesignatorItem;
import com.cbc.firecontrol.item.TargetDesignatorLinkData;
import com.cbc.firecontrol.network.HeadAimRequestPayload;
import com.cbc.firecontrol.network.HeadAimSyncPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.jetbrains.annotations.Nullable;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Objects;

/**
 * Client side of head aiming: the off-hand designator asks the console it is bound to to follow the player's
 * sight line, and the console's predicted impacts are drawn on the terrain the player is looking at.
 *
 * <p>Two halves, both driven by what is in the off hand:
 * <ol>
 * <li><b>The ask.</b> Putting a bound designator in the off hand sends one request; taking it out sends a release.
 * A request that loses the claim (another spotter holds the console) is asked again every {@link #RETRY_INTERVAL}
 * ticks, so a console frees itself for whoever is waiting without them having to re-hand the item - the actionbar
 * line naming the current holder repeats on the same cadence, which reads as a status rather than as chat spam.
 * The console, not this client, decides who holds it, and it re-checks the off hand every tick, so nothing here
 * has to be trusted to say when the mode ends.</li>
 * <li><b>The markers.</b> The console sends absolute world positions for each row's predicted shot, and they are
 * drawn as nested wireframe boxes on the terrain. Nothing is integrated client-side: the flight the marker comes
 * from is the console's own forward prediction, so the marker and the console's readouts can never disagree.</li>
 * </ol>
 */
@EventBusSubscriber(modid = CBCFireControl.MODID, value = Dist.CLIENT)
public final class HeadAimClientEvents {

	/** How often a spotter waiting for a busy console asks again, in ticks (2 s). */
	private static final int RETRY_INTERVAL = 40;

	/** The console's last answer addressed to this player, or null when there is nothing to draw. */
	@Nullable
	private static HeadAimSyncPayload answer;
	/** The console this client has asked about, or null when the off hand holds no bound designator. */
	@Nullable
	private static BlockPos asked;
	private static int retryCooldown;

	private HeadAimClientEvents() {
	}

	/** Server-side handler entry point: remember the answer when it is addressed to this client's own player. */
	public static void applySync(HeadAimSyncPayload payload) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || !payload.owner().equals(mc.player.getUUID()))
			return;
		answer = payload;
	}

	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Post event) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) {
			answer = null;
			asked = null;
			return;
		}
		ItemStack off = mc.player.getOffhandItem();
		TargetDesignatorLinkData data = off.getItem() instanceof TargetDesignatorItem
				? TargetDesignatorItem.bindingOf(off)
				: null;
		BlockPos wanted = data == null ? null : data.calculator();
		if (!Objects.equals(wanted, asked)) {
			/*
			 * The off hand changed. Whatever console it used to point at is told to stop - the console would notice
			 * on its own, but saying so ends the claim on the same tick instead of on the console's next - and the
			 * console it now points at is asked once, immediately.
			 */
			if (asked != null)
				PacketDistributor.sendToServer(new HeadAimRequestPayload(asked, false));
			asked = wanted;
			answer = null;
			retryCooldown = 0;
		}
		if (wanted == null)
			return;
		if (answer != null)
			return; // The claim is already this player's: nothing to ask for.
		if (retryCooldown-- > 0)
			return;
		retryCooldown = RETRY_INTERVAL;
		PacketDistributor.sendToServer(new HeadAimRequestPayload(wanted, true));
	}

	/** Colours the five rows are drawn in, in link-slot order, so a marker reads as the row that fired it. */
	private static final float[][] ROW_COLOURS = {
			{ 0.85F, 0.65F, 0.25F },
			{ 0.35F, 0.75F, 0.85F },
			{ 0.80F, 0.45F, 0.70F },
			{ 0.55F, 0.80F, 0.45F },
			{ 0.90F, 0.40F, 0.35F }
	};

	/**
	 * Draw the console's predicted impacts. After translucent blocks so the markers sit on top of the terrain they
	 * were predicted against rather than being buried in it, with the depth test still on so a marker behind a hill
	 * is hidden by that hill like anything else in the world.
	 */
	@SubscribeEvent
	public static void onRenderLevelStage(RenderLevelStageEvent event) {
		if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS)
			return;
		HeadAimSyncPayload payload = answer;
		Minecraft mc = Minecraft.getInstance();
		Level level = mc.level;
		Player player = mc.player;
		if (payload == null || level == null || player == null || !payload.owner().equals(player.getUUID()))
			return;
		Vec3 camera = event.getCamera().getPosition();
		PoseStack poseStack = event.getPoseStack();
		MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
		VertexConsumer buffer = buffers.getBuffer(RenderType.lines());
		RenderSystem.setShader(GameRenderer::getPositionColorShader);
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		poseStack.pushPose();
		poseStack.translate(-camera.x, -camera.y, -camera.z);
		for (int row = 0; row < payload.count(); row++)
			drawImpact(poseStack, buffer, level, player, payload, row);
		poseStack.popPose();
		buffers.endBatch(RenderType.lines());
		RenderSystem.disableBlend();
	}

	/**
	 * One row's marker: a nested pair of wireframe boxes at the predicted impact, and a small box at the muzzle the
	 * shot leaves from, so a spotter watching several guns can tell which marker belongs to which of them. The boxes
	 * widen with distance - at the ranges these guns shoot over, a two-block marker at three hundred blocks away is
	 * a few pixels, which is not a highlight of anything.
	 */
	private static void drawImpact(PoseStack poseStack, VertexConsumer buffer, Level level, Player player,
			HeadAimSyncPayload payload, int row) {
		byte status = payload.status()[row];
		if (status == HeadAimSyncPayload.STATUS_NONE)
			return;
		double ix = payload.impact()[row * 3];
		double iy = payload.impact()[row * 3 + 1];
		double iz = payload.impact()[row * 3 + 2];
		Vec3 impact = new Vec3(ix, iy, iz);
		/*
		 * An impact in a chunk this client has not loaded is not drawn: the console flew that shot through terrain
		 * the spotter cannot see, and promising a hit on nothing they can verify is worse than showing nothing.
		 */
		if (!level.hasChunkAt(BlockPos.containing(impact)))
			return;
		float[] colour = ROW_COLOURS[row % ROW_COLOURS.length];
		// A flight that ended in the air has no hit to point at, so it reads faint rather than insistent.
		float alpha = status == HeadAimSyncPayload.STATUS_TERRAIN ? 0.95F : 0.30F;
		double distance = player.position().distanceTo(impact);
		float half = (float) Math.min(12.0, Math.max(1.5, distance * 0.035));
		LevelRenderer.renderLineBox(poseStack, buffer, ix - half, iy - half, iz - half, ix + half, iy + half,
				iz + half, colour[0], colour[1], colour[2], alpha);
		float inner = half * 0.35F;
		LevelRenderer.renderLineBox(poseStack, buffer, ix - inner, iy - inner, iz - inner, ix + inner, iy + inner,
				iz + inner, colour[0], colour[1], colour[2], alpha);
		double mx = payload.muzzle()[row * 3];
		double my = payload.muzzle()[row * 3 + 1];
		double mz = payload.muzzle()[row * 3 + 2];
		Vec3 muzzle = new Vec3(mx, my, mz);
		if (!level.hasChunkAt(BlockPos.containing(muzzle)))
			return;
		float muzzleHalf = 0.18F;
		LevelRenderer.renderLineBox(poseStack, buffer, mx - muzzleHalf, my - muzzleHalf, mz - muzzleHalf,
				mx + muzzleHalf, my + muzzleHalf, mz + muzzleHalf, colour[0], colour[1], colour[2], 0.5F);
	}
}
