package com.cbc.firecontrol.client;

import com.cbc.firecontrol.CBCFireControl;
import com.cbc.firecontrol.ballistics.HeadAimConvergence;
import com.cbc.firecontrol.compat.SableShipCoordinates;
import com.cbc.firecontrol.item.TargetDesignatorItem;
import com.cbc.firecontrol.item.TargetDesignatorLinkData;
import com.cbc.firecontrol.network.HeadAimRequestPayload;
import com.cbc.firecontrol.network.HeadAimSightPayload;
import com.cbc.firecontrol.network.HeadAimSyncPayload;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Objects;

/**
 * Client side of head aiming: the off-hand designator asks the console it is bound to to follow the player's
 * sight line, reports where that sight line actually is, and draws the console's predicted impacts on the terrain
 * the player is looking at.
 *
 * <p>Three halves, all driven by what is in the off hand:
 * <ol>
 * <li><b>The ask.</b> Putting a bound designator in the off hand sends one request; taking it out sends a release.
 * A request that loses the claim (another spotter holds the console) is asked again every {@link #RETRY_INTERVAL}
 * ticks, so a console frees itself for whoever is waiting without them having to re-hand the item - the actionbar
 * line naming the current holder repeats on the same cadence, which reads as a status rather than as chat spam.
 * The console, not this client, decides who holds it, and it re-checks the off hand every tick, so nothing here
 * has to be trusted to say when the mode ends.</li>
 * <li><b>The sight line.</b> While the console is ours, every frame that shows the world also reports where this
 * client's camera is pointed and how far along that ray its laser lands. The camera is what the crosshair belongs
 * to, and it is not the eye: a third-person view sits behind the player, a vehicle turns them independently of
 * their body, and the head the server knows is only as current as the last movement packet. Reporting the ray as a
 * <i>point</i> is what makes the gun line converge rather than go parallel: every bore is driven at that one point,
 * so bores a few blocks apart bear in on it and their shots meet where the spotter is looking.</li>
 * <li><b>The markers.</b> The console sends the predicted impact of each row's next shot, drawn as a filled cross
 * with a ground frame around it, standing on a pole, coloured by the row that fired it, and it sends the
 * convergence point every bore is being driven at, drawn as a small pale-gold cross and cube. Nothing is
 * integrated client-side: the flight the marker comes from is the console's own forward prediction, so the marker
 * and the console's readouts can never disagree. No line is drawn from a muzzle to a marker - a straight wire
 * between the two would look like a trajectory and is not one.</li>
 * </ol>
 */
@EventBusSubscriber(modid = CBCFireControl.MODID, value = Dist.CLIENT)
public final class HeadAimClientEvents {

	/** How often a spotter waiting for a busy console asks again, in ticks (2 s). */
	private static final int RETRY_INTERVAL = 40;
	/**
	 * A coordinate farther than this from the camera is not in the space being rendered, so it is brought into it
	 * before it is drawn (see {@link #renderPoint}). Generously above any distance a marker is legitimately drawn
	 * at, and far below the offset Sable puts its ships at.
	 */
	private static final double RENDER_SPACE_PROBE_SQR = 100_000.0 * 100_000.0;

	/** The console's last answer addressed to this player, or null when there is nothing to draw. */
	@Nullable
	private static HeadAimSyncPayload answer;
	/** The console this client has asked about, or null when the off hand holds no bound designator. */
	@Nullable
	private static BlockPos asked;
	private static int retryCooldown;

	/**
	 * The payload's coordinates converted into the space this client renders, kept until a new payload replaces
	 * them. NaN in a slot means that point cannot be placed in the rendered space, so it is not drawn.
	 */
	@Nullable
	private static double[] drawImpact;
	/** The console's convergence point, likewise: where the camera's laser lands and every bore is being driven at. */
	private static final double[] drawSight = new double[3];
	@Nullable
	private static HeadAimSyncPayload drawnFor;

	/** The sight line last reported, so a view that has not moved reports nothing. */
	private static double lastOriginX, lastOriginY, lastOriginZ;
	private static double lastDirX, lastDirY, lastDirZ;
	private static double lastRange;
	private static boolean sightSent;

	private HeadAimClientEvents() {
	}

	/** Server-side handler entry point: remember the answer when it is addressed to this client's own player. */
	public static void applySync(HeadAimSyncPayload payload) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || !payload.owner().equals(mc.player.getUUID()))
			return;
		answer = payload;
		drawnFor = null;
	}

	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Post event) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) {
			answer = null;
			asked = null;
			drawnFor = null;
			sightSent = false;
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
			drawnFor = null;
			sightSent = false;
			retryCooldown = 0;
		}
		if (wanted == null)
			return;
		if (answer != null)
			return; // The claim is already this player's: nothing to ask for, and the sight line goes with the frame.
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
	 * Report this frame's camera as a sight line the console converges its bores onto.
	 *
	 * <p>The line is reported as a point rather than a direction, which is the difference between a gun line that
	 * converges and one that goes parallel: the console drives every bore at whatever point this returns, so the
	 * point has to be somewhere the spotter actually means to shoot at. That is found the way aircraft gunnery
	 * finds it - drop the camera's ray onto the terrain this client can see, and aim at where it lands. Empty sky
	 * lands nowhere, and there the point is put at {@code HeadAimConvergence.MAX_RANGE} out, which is far enough
	 * that the rows read as near-parallel (the right answer for a target beyond the guns) and finite enough that
	 * the drive loop still has a bearing to hold.
	 *
	 * <p>Sent per frame but only when the view moved: the console quantises its cue to a quarter of a degree
	 * anyway, so the thresholds below are far finer than anything it will act on, and a spotter holding steady on a
	 * converged gun sends nothing at all even on a client rendering a hundred and twenty frames a second.
	 */
	private static void sendSight(Camera camera, HeadAimSyncPayload payload) {
		if (!camera.isInitialized())
			return;
		Minecraft mc = Minecraft.getInstance();
		Level level = mc.level;
		if (level == null || mc.player == null)
			return;
		Vec3 origin = camera.getPosition();
		Vector3f look = camera.getLookVector();
		Vec3 dir = new Vec3(look.x, look.y, look.z);
		if (dir.lengthSqr() < 1.0e-6)
			return;
		dir = dir.normalize();
		double range = convergenceRange(mc, level, origin, dir);
		if (sightSent
				&& Math.abs(origin.x - lastOriginX) < 0.05
				&& Math.abs(origin.y - lastOriginY) < 0.05
				&& Math.abs(origin.z - lastOriginZ) < 0.05
				&& Math.abs(dir.x - lastDirX) < 0.002
				&& Math.abs(dir.y - lastDirY) < 0.002
				&& Math.abs(dir.z - lastDirZ) < 0.002
				&& Math.abs(range - lastRange) < Math.max(1.0, lastRange * 0.005))
			return;
		lastOriginX = origin.x;
		lastOriginY = origin.y;
		lastOriginZ = origin.z;
		lastDirX = dir.x;
		lastDirY = dir.y;
		lastDirZ = dir.z;
		lastRange = range;
		sightSent = true;
		PacketDistributor.sendToServer(new HeadAimSightPayload(payload.calculator(), origin.x, origin.y, origin.z,
				(float) dir.x, (float) dir.y, (float) dir.z, (float) range));
	}

	/**
	 * How far along the camera's ray the sight point goes: the terrain the ray lands on, pushed out past anything
	 * the spotter is standing on, and bounded by the convergence limits.
	 *
	 * <p>The push-out is the part that keeps the mechanic usable. A laser that lands on the deck under a gunner's
	 * feet, on the hull plating beside a turret, or on the vehicle they are riding would ask every bore in the line
	 * to slew inward at a point a few blocks away - which is the gun-snapping-up failure a camera-ray sight is
	 * famous for. A spotter riding a Create contraption is therefore asked to aim at least past the far side of it:
	 * the contraption's own bounding box gives a sphere twice its longest side wide, and the ray's exit from that
	 * sphere is the floor. Riding anything else, including a Sable hull (which is not a Create contraption), falls
	 * back to the plain minimum.
	 */
	private static double convergenceRange(Minecraft mc, Level level, Vec3 origin, Vec3 dir) {
		BlockHitResult hit = level.clip(new ClipContext(origin, origin.add(dir.scale(HeadAimConvergence.MAX_RANGE)),
				ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
		double range = hit.getType() == BlockHitResult.Type.BLOCK
				? origin.distanceTo(hit.getLocation())
				: HeadAimConvergence.MAX_RANGE;
		double outside = ridingContraptionExit(mc.player, origin, dir);
		if (outside > range)
			range = outside;
		return HeadAimConvergence.clampRange(range);
	}

	/**
	 * The distance at which the spotter's ray leaves the Create contraption they are riding, or NaN when they are
	 * not riding one or it is not in front of them. A seat entity is itself a vehicle whose vehicle is the
	 * contraption, so the chain is walked rather than the immediate mount read.
	 */
	private static double ridingContraptionExit(@Nullable Entity rider, Vec3 origin, Vec3 dir) {
		Entity vehicle = rider == null ? null : rider.getVehicle();
		while (vehicle != null) {
			if (vehicle instanceof AbstractContraptionEntity contraption) {
				AABB box = contraption.getBoundingBox();
				double centreX = (box.minX + box.maxX) / 2.0;
				double centreY = (box.minY + box.maxY) / 2.0;
				double centreZ = (box.minZ + box.maxZ) / 2.0;
				double radius = HeadAimConvergence.vehicleConvergenceRadius(box.getXsize(), box.getYsize(),
						box.getZsize());
				return HeadAimConvergence.sphereExitDistance(origin.x, origin.y, origin.z, dir.x, dir.y, dir.z,
						centreX, centreY, centreZ, radius);
			}
			vehicle = vehicle.getVehicle();
		}
		return Double.NaN;
	}

	/**
	 * Draw the console's predicted impacts. After translucent blocks so the markers sit on top of the terrain they
	 * were predicted against rather than being buried in it, with the depth test still on so a marker behind a hill
	 * is hidden by that hill like anything else in the world.
	 */
	@SubscribeEvent
	public static void onRenderLevelStage(RenderLevelStageEvent event) {
		if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS)
			return;
		Minecraft mc = Minecraft.getInstance();
		Level level = mc.level;
		Player player = mc.player;
		if (level == null || player == null)
			return;
		HeadAimSyncPayload payload = answer;
		if (payload == null || !payload.owner().equals(player.getUUID()))
			return;
		Camera camera = event.getCamera();
		sendSight(camera, payload);
		Vec3 cameraPos = camera.getPosition();
		memoize(payload, level, cameraPos);
		/*
		 * The marker's screen axes come from the camera it is drawn for, so a filled cross lies flat against the
		 * view from any angle, including looking straight down at the impact.
		 */
		Vec3 right = new Vec3(camera.getLeftVector().x, camera.getLeftVector().y, camera.getLeftVector().z).reverse();
		Vec3 up = new Vec3(camera.getUpVector().x, camera.getUpVector().y, camera.getUpVector().z);
		Vec3 look = new Vec3(camera.getLookVector().x, camera.getLookVector().y, camera.getLookVector().z);
		PoseStack poseStack = event.getPoseStack();
		MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
		/*
		 * debugQuads() rather than translucent(): it is position-colour and unlit, so it needs no texture or
		 * lightmap (which a cross built by hand has no way to supply), it is not face-culled, and it draws straight
		 * into the level's own framebuffer rather than into a separate translucent target - which is what makes it
		 * correct to issue from this stage. lines() is the wire format, and it additionally demands a per-vertex
		 * normal, which segment() supplies.
		 */
		/*
		 * No consumer is taken once and held across the drawing. {@code BufferSource} keeps one shared builder
		 * at a time: asking for a different {@code RenderType} ends and uploads the previous one, so a reference
		 * obtained before such a switch points at a builder that is no longer building, and the next
		 * {@code addVertex} throws {@code IllegalStateException: Not building!}. Each drawing block therefore
		 * asks for the consumer it needs at the moment it needs it.
		 */
		RenderSystem.setShader(GameRenderer::getPositionColorShader);
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		long gameTime = level.getGameTime();
		for (int row = 0; row < payload.count(); row++)
			drawRow(poseStack, buffers, cameraPos, right, up, look, level, payload, row, gameTime);
		drawSightPoint(poseStack, buffers, cameraPos, right, up, look, level);
		buffers.endBatch(RenderType.debugQuads());
		buffers.endBatch(RenderType.lines());
		RenderSystem.disableBlend();
	}

	/**
	 * Convert the payload's coordinates once per answer into the space this client renders. The console sends world
	 * coordinates because they are what its shots flew through, but a spotter standing on a ship is shown that
	 * ship's sub-level, whose coordinates are the hull's own - so a point that is a ship's width from the camera is
	 * brought back into the hull frame, and a point that no ship covers is left alone because it was never in
	 * another frame to begin with.
	 */
	private static void memoize(HeadAimSyncPayload payload, Level level, Vec3 camera) {
		if (drawnFor == payload)
			return;
		int count = payload.count();
		if (drawImpact == null || drawImpact.length < count * 3)
			drawImpact = new double[count * 3];
		for (int row = 0; row < count; row++)
			put(drawImpact, row * 3, renderPoint(level, camera, payload.impact(), row * 3));
		/*
		 * The convergence point is sent in the scale the holder's camera renders in and always sits within a few
		 * hundred blocks of it, so the probe passes it straight through. Running it through the same check anyway
		 * is what keeps a console aiming from one frame and a spotter watching from another from putting it
		 * somewhere neither of them can see.
		 */
		double[] sight = { payload.sightX(), payload.sightY(), payload.sightZ() };
		put(drawSight, 0, renderPoint(level, camera, sight, 0));
		drawnFor = payload;
	}

	private static void put(double[] target, int offset, @Nullable Vec3 point) {
		if (point == null) {
			target[offset] = Double.NaN;
			target[offset + 1] = Double.NaN;
			target[offset + 2] = Double.NaN;
			return;
		}
		target[offset] = point.x;
		target[offset + 1] = point.y;
		target[offset + 2] = point.z;
	}

	@Nullable
	private static Vec3 renderPoint(Level level, Vec3 camera, double[] source, int offset) {
		Vec3 sent = new Vec3(source[offset], source[offset + 1], source[offset + 2]);
		if (sent.distanceToSqr(camera) < RENDER_SPACE_PROBE_SQR)
			return sent; // Already in the rendered space: a land console, or a spotter watching from the world.
		Vec3 local = SableShipCoordinates.worldToShipLocal(level, sent);
		if (local != null && local.distanceToSqr(camera) < RENDER_SPACE_PROBE_SQR)
			return local; // The spotter is on the ship this point belongs to.
		return null; // Neither frame can place it; drawing nothing beats drawing it somewhere wrong.
	}

	/**
	 * One row's marker, drawn around its own impact so the coordinates that reach the vertex buffer stay small.
	 * The headline is a filled cross standing on a pole, big enough to read at the ranges these guns shoot over:
	 * at three hundred blocks a two-block wireframe is a handful of pixels, which highlights nothing. The wireframe
	 * box the shell the shot occupies is kept, but faint, so it explains the marker without competing with it. The
	 * whole thing breathes, on a per-row phase, so it reads as a prediction being live rather than as something
	 * painted on the terrain.
	 *
	 * <p>Nothing is drawn between the muzzle and this point. A straight line from a gun to a predicted landing is
	 * not a trajectory: a shell rises, falls, and drifts, and a wire drawn straight would read as the shot flying
	 * straight. The console's own flight is what produced the point, and the point is the honest half of it.
	 */
	private static void drawRow(PoseStack poseStack, MultiBufferSource.BufferSource buffers, Vec3 camera,
			Vec3 right, Vec3 up, Vec3 look, Level level, HeadAimSyncPayload payload, int row, long gameTime) {
		byte status = payload.status()[row];
		if (status == HeadAimSyncPayload.STATUS_NONE)
			return;
		double[] impact = drawImpact;
		if (impact == null || impact.length <= row * 3 + 2)
			return;
		int o = row * 3;
		if (Double.isNaN(impact[o]))
			return;
		double ix = impact[o], iy = impact[o + 1], iz = impact[o + 2];
		Vec3 point = new Vec3(ix, iy, iz);
		/*
		 * An impact in a chunk this client has not loaded is not drawn: the console flew that shot through terrain
		 * the spotter cannot see, and promising a hit on nothing they can verify is worse than showing nothing.
		 */
		if (!level.hasChunkAt(BlockPos.containing(point)))
			return;
		boolean hit = status == HeadAimSyncPayload.STATUS_TERRAIN;
		boolean pinned = status == HeadAimSyncPayload.STATUS_LIMITED;
		float[] base = ROW_COLOURS[row % ROW_COLOURS.length];
		// A shot that struck is drawn white-hot at its centre; one that ran out reads faint, in plain row colour.
		float white = hit ? 0.70F : 0.0F;
		float r = base[0] + (1.0F - base[0]) * white;
		float g = base[1] + (1.0F - base[1]) * white;
		float b = base[2] + (1.0F - base[2]) * white;
		/*
		 * A row pinned at its elevation stop is told in warning red, kept bright: its marker sits where the
		 * carriage's limit sends the shot, not where the crosshair is, and the gap between this red cross and the
		 * pale-gold convergence point is the whole message - the sight line says one place, the gun can only go to
		 * another. It stays at full strength rather than the faint reading a stalled shot gets, because this is not
		 * a shot that ran out of range; it is a shot that cannot be aimed where it was asked to be.
		 */
		if (pinned) {
			r = Mth.lerp(0.80F, r, 0.95F);
			g = Mth.lerp(0.80F, g, 0.20F);
			b = Mth.lerp(0.80F, b, 0.16F);
		}
		float dim = hit || pinned ? 1.0F : 0.35F;
		float pulse = 1.0F + 0.10F * Mth.sin((gameTime % 400L) * 0.25F + row * 1.7F);
		float half = (float) Math.min(20.0, Math.max(3.0, camera.distanceTo(point) * 0.06)) * pulse;
		poseStack.pushPose();
		poseStack.translate(ix - camera.x, iy - camera.y, iz - camera.z);
		PoseStack.Pose pose = poseStack.last();
		Vec3 centre = new Vec3(0.0, 0.0, 0.0);
		Vec3 armA = right.add(up).normalize();
		Vec3 armB = right.add(up.reverse()).normalize();
		float thickness = Math.max(0.4F, half * 0.17F);
		VertexConsumer fills = buffers.getBuffer(RenderType.debugQuads());
		bar(fills, pose, centre, armA, half * 1.5F, armA.cross(look), thickness, r, g, b, 0.85F * dim);
		bar(fills, pose, centre, armB, half * 1.5F, armB.cross(look), thickness, r, g, b, 0.85F * dim);
		bar(fills, pose, new Vec3(0.0, half * 0.9, 0.0), new Vec3(0.0, 1.0, 0.0), half * 1.8F, right,
				Math.max(0.3F, half * 0.11F), r, g, b, 0.50F * dim);
		float y0 = 0.06F;
		float y1 = y0 + half * 0.45F;
		VertexConsumer lines = buffers.getBuffer(RenderType.lines());
		ring(lines, pose, half, y0, r, g, b, 0.95F * dim);
		if (hit) {
			ring(lines, pose, half, y1, r, g, b, 0.55F);
			post(lines, pose, half, half, y0, y1, r, g, b);
			post(lines, pose, -half, half, y0, y1, r, g, b);
			post(lines, pose, half, -half, y0, y1, r, g, b);
			post(lines, pose, -half, -half, y0, y1, r, g, b);
		}
		// Four white spokes out of the centre, so the exact point being promised is the brightest thing on it.
		float spoke = half * 0.45F;
		segment(lines, pose, new Vec3(-spoke, y0, 0.0), new Vec3(spoke, y0, 0.0), 1.0F, 1.0F, 1.0F, 0.9F * dim);
		segment(lines, pose, new Vec3(0.0, y0, -spoke), new Vec3(0.0, y0, spoke), 1.0F, 1.0F, 1.0F, 0.9F * dim);
		float box = half * 0.5F;
		LevelRenderer.renderLineBox(poseStack, lines, -box, -box, -box, box, box, box, r, g, b, 0.30F * dim);
		poseStack.popPose();
	}

	/**
	 * The convergence point itself, drawn as a small camera-facing cross with a wireframe cube around it. It is the
	 * spot every bore is being driven at, so it is what tells a spotter whether the guns are aimed where their
	 * crosshair is or somewhere the sight limits moved the aim to: aiming at the sky puts the point far out in
	 * front of them, and aiming at their own vehicle puts it on the far side of it. Drawn in a colour no row uses,
	 * and much smaller than the impact markers, because it is a reference rather than a result.
	 */
	private static void drawSightPoint(PoseStack poseStack, MultiBufferSource.BufferSource buffers, Vec3 camera,
			Vec3 right, Vec3 up, Vec3 look, Level level) {
		if (Double.isNaN(drawSight[0]))
			return;
		Vec3 point = new Vec3(drawSight[0], drawSight[1], drawSight[2]);
		if (!level.hasChunkAt(BlockPos.containing(point)))
			return;
		float r = 0.95F;
		float g = 0.95F;
		float b = 0.55F;
		// A steady tick rather than a breath: this is a fixed reference, and pulsing it would read as uncertainty.
		float half = (float) Math.min(6.0, Math.max(1.0, camera.distanceTo(point) * 0.018));
		poseStack.pushPose();
		poseStack.translate(point.x - camera.x, point.y - camera.y, point.z - camera.z);
		PoseStack.Pose pose = poseStack.last();
		Vec3 centre = new Vec3(0.0, 0.0, 0.0);
		Vec3 armA = right.add(up).normalize();
		Vec3 armB = right.add(up.reverse()).normalize();
		float thickness = Math.max(0.25F, half * 0.22F);
		VertexConsumer fills = buffers.getBuffer(RenderType.debugQuads());
		bar(fills, pose, centre, armA, half * 1.4F, armA.cross(look), thickness, r, g, b, 0.55F);
		bar(fills, pose, centre, armB, half * 1.4F, armB.cross(look), thickness, r, g, b, 0.55F);
		float spoke = half * 0.5F;
		VertexConsumer lines = buffers.getBuffer(RenderType.lines());
		segment(lines, pose, new Vec3(-spoke, 0.0, 0.0), new Vec3(spoke, 0.0, 0.0), r, g, b, 0.9F);
		segment(lines, pose, new Vec3(0.0, -spoke, 0.0), new Vec3(0.0, spoke, 0.0), r, g, b, 0.9F);
		segment(lines, pose, new Vec3(0.0, 0.0, -spoke), new Vec3(0.0, 0.0, spoke), r, g, b, 0.9F);
		LevelRenderer.renderLineBox(poseStack, lines, -half, -half, -half, half, half, half, r, g, b, 0.35F);
		poseStack.popPose();
	}

	/** A square ring on the ground plane around the marker's centre, in that centre's own coordinates. */
	private static void ring(VertexConsumer buffer, PoseStack.Pose pose, float half, float y,
			float r, float g, float b, float alpha) {
		Vec3 a = new Vec3(-half, y, -half);
		Vec3 bb = new Vec3(half, y, -half);
		Vec3 c = new Vec3(half, y, half);
		Vec3 d = new Vec3(-half, y, half);
		segment(buffer, pose, a, bb, r, g, b, alpha);
		segment(buffer, pose, bb, c, r, g, b, alpha);
		segment(buffer, pose, c, d, r, g, b, alpha);
		segment(buffer, pose, d, a, r, g, b, alpha);
	}

	/** The upright between a marker's two ground rings, at one corner of them. */
	private static void post(VertexConsumer buffer, PoseStack.Pose pose, double x, double z, float y0, float y1,
			float r, float g, float b) {
		segment(buffer, pose, new Vec3(x, y0, z), new Vec3(x, y1, z), r, g, b, 0.55F);
	}

	/**
	 * One straight segment, in the marker's own coordinates so its numbers stay small enough to be exact.
	 *
	 * <p>{@code RenderType.lines()} is position-colour-<i>normal</i>, and the pose overload of {@code addVertex}
	 * fills only the position, so every vertex here must carry a normal or the buffer refuses to close the
	 * vertex. The normal is not decoration: the lines shader extrudes thick lines along it, so it has to stay
	 * roughly perpendicular to the segment for the wire to keep its width from any angle. Vanilla's own
	 * {@code LevelRenderer.renderLineBox} solves this by giving each edge group the axis-aligned normal of the
	 * face it belongs to; the same trick applies here as the axis least aligned with the segment, which works
	 * for the axis-aligned rings, posts and spokes these markers are built from.
	 */
	private static void segment(VertexConsumer buffer, PoseStack.Pose pose, Vec3 from, Vec3 to,
			float r, float g, float b, float alpha) {
		double dx = Math.abs(to.x - from.x);
		double dy = Math.abs(to.y - from.y);
		double dz = Math.abs(to.z - from.z);
		float nx, ny, nz;
		if (dx <= dy && dx <= dz) {
			nx = 1.0F;
			ny = 0.0F;
			nz = 0.0F;
		} else if (dy <= dz) {
			nx = 0.0F;
			ny = 1.0F;
			nz = 0.0F;
		} else {
			nx = 0.0F;
			ny = 0.0F;
			nz = 1.0F;
		}
		buffer.addVertex(pose, (float) from.x, (float) from.y, (float) from.z)
				.setColor(r, g, b, alpha)
				.setNormal(pose, nx, ny, nz);
		buffer.addVertex(pose, (float) to.x, (float) to.y, (float) to.z)
				.setColor(r, g, b, alpha)
				.setNormal(pose, nx, ny, nz);
	}

	/**
	 * A filled bar lying in the plane facing the camera: {@code axis} gives its length and {@code across} the
	 * perpendicular direction it is measured on, which the caller takes from the camera so the bar is never seen
	 * edge on. Each bar is emitted in both windings: {@code debugQuads()} is not culled today, but a bar drawn once
	 * only is a bar that disappears the moment anything starts culling, and the second winding costs four vertices.
	 */
	private static void bar(VertexConsumer buffer, PoseStack.Pose pose, Vec3 offset, Vec3 axis, double length,
			Vec3 across, double width, float r, float g, float b, float alpha) {
		Vec3 u = axis.normalize();
		Vec3 v = across.lengthSqr() < 1.0e-6 ? u : across.normalize();
		Vec3 along = u.scale(length / 2.0);
		Vec3 side = v.scale(width / 2.0);
		Vec3 p1 = offset.add(along).add(side);
		Vec3 p2 = offset.subtract(along).add(side);
		Vec3 p3 = offset.subtract(along).subtract(side);
		Vec3 p4 = offset.add(along).subtract(side);
		quad(buffer, pose, p1, p2, p3, p4, r, g, b, alpha);
		quad(buffer, pose, p4, p3, p2, p1, r, g, b, alpha);
	}

	private static void quad(VertexConsumer buffer, PoseStack.Pose pose, Vec3 p1, Vec3 p2, Vec3 p3, Vec3 p4,
			float r, float g, float b, float alpha) {
		buffer.addVertex(pose, (float) p1.x, (float) p1.y, (float) p1.z).setColor(r, g, b, alpha);
		buffer.addVertex(pose, (float) p2.x, (float) p2.y, (float) p2.z).setColor(r, g, b, alpha);
		buffer.addVertex(pose, (float) p3.x, (float) p3.y, (float) p3.z).setColor(r, g, b, alpha);
		buffer.addVertex(pose, (float) p4.x, (float) p4.y, (float) p4.z).setColor(r, g, b, alpha);
	}
}
