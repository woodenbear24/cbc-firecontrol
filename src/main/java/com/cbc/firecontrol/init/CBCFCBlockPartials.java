package com.cbc.firecontrol.init;

import com.cbc.firecontrol.CBCFireControl;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.minecraft.resources.ResourceLocation;

/**
 * Flywheel partial models supplied by cbc_firecontrol itself (as opposed to the ones we
 * import from CBC via {@link rbasamoyai.createbigcannons.index.CBCBlockPartials}).
 *
 * Three partials:
 * <ul>
 *   <li>{@link #FIRE_CONTROL_ROTATING}: user's own trunnion-cradle turntable geometry authored
 *       in Blockbench at {@code .cbc_src/fire_control_rotating.bbmodel}, exported as
 *       {@code assets/cbc_firecontrol/models/block/advanced_cannon_mount/fire_control_rotating.json}.
 *       The FBModel JSON mirrors the bbmodel export exactly (same 9 cubes, same Y 0..20 model
 *       coords, same absolute UV rects, same rotation origins). As of the brass-texture repaint
 *       both the UV rects and the texture alias come from our own atlas: every face references
 *       {@code #0} = {@code cbc_firecontrol:block/fire_control_rotating} (64x64, painted flat
 *       Create-brass style with iron bearing/axle sockets on the bottom face), and the JSON is
 *       regenerated straight from the live Blockbench project so UVs can never drift out of sync
 *       with the exported PNG. The base plinth half-slabs live in the same folder
 *       ({@code models/block/advanced_cannon_mount/advanced_cannon_mount[_flipped].json}) and are
 *       painted on their own 16x16 texture {@code cbc_firecontrol:block/advanced_cannon_mount}
 *       (Blockbench project {@code .cbc_src/advanced_cannon_mount.bbmodel}), so no bearing/axle art
 *       can leak onto the plinth; {@code blockstates/advanced_cannon_mount.json} references those
 *       two subfolder paths. The geometry itself is not modified; the user's Blockbench project remains the
 *       source of truth.</li>
 *   <li>{@link #CARRIAGE_AXLE}: inline re-host of CBC's
 *       {@code cannon_carriage/carriage_axle.json} with parent-alias texture references
 *       resolved directly to Create's own shaft axis textures so it matches CBC's original
 *       'mechanical shaft' visual style and sidesteps the Flywheel PartialModel bake-time
 *       limitation where a model's parent chain alias may not resolve.</li>
 *   <li>{@link #CALCULATOR_LINK_CARD}: the link card that fills the trajectory calculator's card
 *       rack. One instance is drawn per slot that holds a calculator_link item, by
 *       {@link com.cbc.firecontrol.client.TrajectoryCalculatorBERenderer}, lifted by whole multiples
 *       of two model units so it hangs from the rack bar of its own slot. Geometry and UVs come from
 *       {@code models/block/trajectory_calculator/trajectory_calculator_link.json}, the card group the
 *       user authored in Blockbench; its plate's east face samples the copper column of
 *       {@code cbc_firecontrol:block/trajectory_calculator_link}, which is the side facing the
 *       console's monitor panel cube at model x=8..16.</li>
 * </ul>
 *
 * Class-init triggers lazily when the visual/BERs read the static fields. To ensure Flywheel
 * sees both partials before ModelEvent.RegisterAdditional fires (which is when Flywheel decides
 * what to bake into Minecraft's model atlas), CBCFireControl.clientSetupDiag explicitly calls
 * {@link #init()} here. Without that call, class-init would only happen at first render, way
 * after bake, and both partials would show up as purple/black missing-texture in-game.
 */
public class CBCFCBlockPartials {

	/**
	 * User's own custom turntable partial authored in Blockbench at
	 * {@code .cbc_src/fire_control_rotating.bbmodel}. Model coord space Y = 0..20 (matches the
	 * bbmodel export exactly). Placed by {@link com.cbc.firecontrol.client.AdvancedCannonMountVisual}
	 * at the assembly cell corner (world Y = 1 for upright mount, Y = -1 for upside-down mount).
	 * Under this positioning the turntable plate bottom sits at world Y = 1, i.e. exactly on the
	 * mount cell top boundary, leaving a 0.5-block visible gap between the plinth top (world 0.5,
	 * SHAPE_LOWER_HALF collision) and the rotating structure - a design the user accepted from
	 * v0.0.2 onwards, matching how CBC's own cannon mount reads when its full-block top surface is
	 * flush against the mount block but our half-slab variant naturally shows a small pedestal gap.
	 * If the user later asks for plate-on-plinth visual, the fix is to change
	 * {@link com.cbc.firecontrol.client.AdvancedCannonMountBERenderer#renderSafe} translate Y from 1.0
	 * to 0.5 and add a matching fractional Y offset in the visualizer's instance.position(...) call
	 * (do NOT edit the model file Y coordinates, they must stay as exported from bbmodel).
	 */
	public static final PartialModel FIRE_CONTROL_ROTATING =
			PartialModel.of(ResourceLocation.fromNamespaceAndPath(
					CBCFireControl.MODID, "block/advanced_cannon_mount/fire_control_rotating"));

	/**
	 * Our own carriage_axle partial. Referenced by {@link com.cbc.firecontrol.client.AdvancedCannonMountVisual}
	 * and {@link com.cbc.firecontrol.client.AdvancedCannonMountBERenderer} instead of CBC's
	 * {@code CBCBlockPartials.CANNON_CARRIAGE_AXLE}. Reason: CBC's carriage_axle.json uses
	 * "parent": "createbigcannons:block/cannon_carriage/textures" to bring in texture aliases
	 * (#axis, #axis_top -> create:block/axis etc.). When Flywheel's PartialModel pipeline bakes
	 * the model as a standalone instance (rather than through Minecraft's normal BlockState
	 * baking path), that parent-alias resolution can leave the axis faces with unresolved
	 * #alias references, which Minecraft renders as the purple/black missing-texture sprite.
	 * Our inline variant hardcodes Create's own shaft textures (create:block/axis + axis_top),
	 * sidesteps the parent dependency entirely, and preserves CBC's original 'mechanical shaft'
	 * look per user's request 'ear axle should revert to Create's mechanical shaft style'.
	 */
	public static final PartialModel CARRIAGE_AXLE =
			PartialModel.of(ResourceLocation.fromNamespaceAndPath(
					CBCFireControl.MODID, "block/advanced_cannon_mount/carriage_axle"));

	/**
	 * One calculator_link card for the trajectory calculator's rack. Loaded from
	 * {@code models/block/trajectory_calculator/trajectory_calculator_link.json}, whose texture alias
	 * points at our own {@code cbc_firecontrol:block/trajectory_calculator_link} (16x16, fully opaque
	 * since .temp/make_link_tex.js filled the empty area with the plate's navy, because
	 * RenderType.solid() does not blend alpha and would draw transparent texels as black).
	 *
	 * Declared here rather than in a client-only class so {@link #init()} still pulls it into
	 * Flywheel's PartialModel.ALL before ModelEvent.RegisterAdditional bakes the atlas.
	 */
	public static final PartialModel CALCULATOR_LINK_CARD =
			PartialModel.of(ResourceLocation.fromNamespaceAndPath(
					CBCFireControl.MODID, "block/trajectory_calculator/trajectory_calculator_link"));

	/** Empty no-op referenced from CBCFireControl's clientSetupDiag to force class-init before
	 * Flywheel's ModelEvent.RegisterAdditional runs, so our partials are added to
	 * PartialModel.ALL in time to be baked into Minecraft's model atlas. */
	public static void init() { }
}
