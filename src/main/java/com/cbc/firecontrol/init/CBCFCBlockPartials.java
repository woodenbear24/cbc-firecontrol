package com.cbc.firecontrol.init;

import com.cbc.firecontrol.CBCFireControl;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.minecraft.resources.ResourceLocation;

/**
 * Flywheel partial models supplied by cbc_firecontrol itself (as opposed to the ones we
 * import from CBC via {@link rbasamoyai.createbigcannons.index.CBCBlockPartials}).
 *
 * Two partials:
 * <ul>
 *   <li>{@link #FIRE_CONTROL_ROTATING}: user's own trunnion-cradle turntable geometry authored
 *       in Blockbench at {@code .cbc_src/fire_control_rotating.bbmodel}, exported as
 *       {@code assets/cbc_firecontrol/models/block/fire_control_mount/fire_control_rotating.json}.
 *       The FBModel JSON mirrors the bbmodel export exactly (same 9 cubes, same Y 0..20 model
 *       coords, same autouv-absolute UV rects, same rotation origins) except for one texture
 *       alias addition: Blockbench exported every face as {@code "texture": "#missing"} because
 *       no texture is yet bound in the project, so we add {@code "parent": "minecraft:block/block"}
 *       and a {@code textures} map aliasing {@code #0} to Create's {@code create:block/brass_block}
 *       and rewrite each face's {@code #missing} to {@code #0}. The geometry itself is not
 *       modified in any way; the user's Blockbench project remains the source of truth.</li>
 *   <li>{@link #CARRIAGE_AXLE}: inline re-host of CBC's
 *       {@code cannon_carriage/carriage_axle.json} with parent-alias texture references
 *       resolved directly to Create's own shaft axis textures so it matches CBC's original
 *       'mechanical shaft' visual style and sidesteps the Flywheel PartialModel bake-time
 *       limitation where a model's parent chain alias may not resolve.</li>
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
	 * bbmodel export exactly). Placed by {@link com.cbc.firecontrol.client.FireControlMountVisual}
	 * at the assembly cell corner (world Y = 1 for upright mount, Y = -1 for upside-down mount).
	 * Under this positioning the turntable plate bottom sits at world Y = 1, i.e. exactly on the
	 * mount cell top boundary, leaving a 0.5-block visible gap between the plinth top (world 0.5,
	 * SHAPE_LOWER_HALF collision) and the rotating structure - a design the user accepted from
	 * v0.0.2 onwards, matching how CBC's own cannon mount reads when its full-block top surface is
	 * flush against the mount block but our half-slab variant naturally shows a small pedestal gap.
	 * If the user later asks for plate-on-plinth visual, the fix is to change
	 * {@link com.cbc.firecontrol.client.FireControlMountBERenderer#renderSafe} translate Y from 1.0
	 * to 0.5 and add a matching fractional Y offset in the visualizer's instance.position(...) call
	 * (do NOT edit the model file Y coordinates, they must stay as exported from bbmodel).
	 */
	public static final PartialModel FIRE_CONTROL_ROTATING =
			PartialModel.of(ResourceLocation.fromNamespaceAndPath(
					CBCFireControl.MODID, "block/fire_control_mount/fire_control_rotating"));

	/**
	 * Our own carriage_axle partial. Referenced by {@link com.cbc.firecontrol.client.FireControlMountVisual}
	 * and {@link com.cbc.firecontrol.client.FireControlMountBERenderer} instead of CBC's
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
					CBCFireControl.MODID, "block/fire_control_mount/carriage_axle"));

	/** Empty no-op referenced from CBCFireControl's clientSetupDiag to force class-init before
	 * Flywheel's ModelEvent.RegisterAdditional runs, so our partials are added to
	 * PartialModel.ALL in time to be baked into Minecraft's model atlas. */
	public static void init() { }
}
