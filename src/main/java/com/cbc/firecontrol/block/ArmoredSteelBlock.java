package com.cbc.firecontrol.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition.Builder;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Armored Steel - a plain, block-entity-free metal block whose texture connects between neighbours the way
 * Create's casings do, but without any casing behaviour.
 *
 * <p>The connection itself is Create's connected-texture pipeline (see {@code CBCFCSpriteShifts} and
 * {@code ArmoredSteelCTBehaviour}): Create's {@code CTModel} computes, per rendered face, which neighbours the
 * face touches and UV-shifts that face's quad into the matching cell of {@code armored_steel_block_connected.png}.
 * Only {@code CreateRegistrate.connectedTextures(...)} is used. Deliberately NOT used: {@code CasingBlock},
 * {@code BuilderTransformers.casing(...)}, {@code CasingConnectivity#makeCasing} and {@code EncasedCTBehaviour} -
 * the casing connectivity registry groups blocks by their shared casing sprite, so routing through it would make
 * armored steel connect with {@code create:andesite_casing} and would also drag in the casing transformer's wood
 * sound, loot table and casing tags. The connection rule here is Create's base one - same block, face not
 * occluded - narrowed further to same block AND same dye, in the client behaviour.
 *
 * <p>Dyeing: right-click with any dye to recolour the block; the dye is never consumed (nothing ever calls
 * {@link ItemStack#shrink(int)}). The colour lives in the block state rather than a block entity, so it survives
 * contraption assembly (StructureTransform copies properties) and a state change rebuilds the chunk section,
 * which is what re-runs the connected-texture gather for this block and its neighbours. Rendering applies it as a
 * tint (see {@code CBCFCBlockColors}) - and the tint only reaches the screen because the block's model declares
 * {@code "tintindex": 0} on all six faces: {@code ModelBlockRenderer} asks {@code BlockColors} for a colour only
 * when {@code BakedQuad#isTinted()}, so vanilla's {@code block/cube_all} (no tint index on its faces) would have
 * silently ignored the dye. The authored texture is therefore bright (body {@code #D6D8DB}) and {@link DyeColor#WHITE}
 * is the un-dyed state, which multiplies it back down to German grey; a dye multiplies it at full strength, so
 * orange dye reads as orange rather than as a washed-out stain.
 *
 * <p>Breaking one drops the plain block item: {@code data/cbc_firecontrol/loot_table/blocks/armored_steel_block.json}
 * has no {@code minecraft:copy_state} function, so the dye is not written into {@code DataComponents.BLOCK_STATE}
 * and a broken block re-placeable as un-dyed. Colour is a placement-time cosmetic only.
 *
 * <p>It is also armour in CBC's own terms: {@code data/cbc_firecontrol/block_armor/armored_steel_block.json}
 * gives it {@code hardness 2.5 / toughness 90}, which {@code BlockArmorPropertiesHandler} reads from the
 * {@code block_armor} reload directory - the file's resource location doubles as the block id, so no Java
 * registration is needed - and which {@code AbstractBigCannonProjectile#impactBlock} then weighs against the
 * projectile. Penetration needs {@code momentum = mass x (velMag x incidence) x (1 + max(0, velMag - 1) x 0.1)}
 * to reach {@code toughness} (config defaults {@code minimumVelocityForPenetrationBonus = 1},
 * {@code penetrationBonusScale = 0.1}), and muzzle velocity equals the number of powder charges, so a steel cannon
 * tops out at {@code maxSafePropellantStress = 8}: the AP shot (mass 8) reaches 108.8 at 8 charges and drills
 * through, needs ~7 charges to reach 90 at all, and the AP shell (mass 6) only reaches 81.6 - which stops. HE,
 * shrapnel, fluid and smoke shells (mass 2) and the solid shot (mass 3) never get close. Netherite blocks have no
 * {@code block_armor} entry, so they fall back to {@code hardness 1 / toughness = getExplosionResistance() = 1200};
 * armored steel is thus a tier below in toughness while its higher {@code hardness 2.5} (vs {@code 1}) makes clean
 * stops and shattering more likely - {@code hardnessPenalty = hardness - projectile.penetration} decides both the
 * bounce bonus and {@code shatter}. The block's own explosion resistance stays at iron block's 6, which is what
 * explosions (HE {@code block_damaging_explosive_power = 8}) test against - a separate mechanism from armour.
 *
 * <p>It is reversible metal: {@code data/cbc_firecontrol/recipe/steel_ingot_from_armored_steel_block.json}
 * unpacks one block into nine {@code createbigcannons:steel_ingot}, the same shapeless shape CBC uses to unpack its
 * own steel block (see {@code createbigcannons:recipe/steel_ingot_from_block.json}). Since the block is smelted from
 * one steel block, which is itself nine ingots, the loop costs furnace fuel rather than duplicating steel.
 *
 * <p>Known limitation, inherited from the renderer rather than this block: a block belonging to a Sable sub-level
 * (the ship's own hull) renders through Sable's mesh builders, which fetch quads with
 * {@code BakedModel#getQuads(state, face, rand)} - no {@code ModelData}, no {@code BlockAndTintGetter} - and the
 * single-block path tessellates through a {@code BlockAndTintGetter} wrapper holding one block state. Either way
 * the connected-texture gather never runs there, so hull blocks show the isolated cell. Create's own casings
 * behave identically on hulls. Blocks inside an assembled contraption DO connect: Create's
 * {@code ContraptionVisual} wraps the contraption structure in a {@code WrappedBlockAndTintGetter} and Flywheel's
 * {@code BakedModelBufferer} calls {@code model.getModelData(level, pos, state, ...)} before tesselating.
 */
public class ArmoredSteelBlock extends Block {

	/**
	 * The dye currently applied. Vanilla's {@code BlockStateProperties} has no DyeColor property (1.21 keeps wool
	 * and carpets as sixteen separate blocks), so this property is authored here. Its serialized names are
	 * {@code DyeColor#getSerializedName()} - white, orange, magenta, light_blue, ... - and every one of the
	 * sixteen appears in the blockstate JSON pointing at the same model, because the colour reaches the screen
	 * through the block tint, not through a per-colour model.
	 */
	public static final EnumProperty<DyeColor> COLOR = EnumProperty.create("color", DyeColor.class);

	public ArmoredSteelBlock(Properties properties) {
		super(properties);
		// WHITE is "un-dyed": CBCFCBlockColors multiplies the bright authored plate by UNDYED_TINT, so a freshly
		// placed or freshly smelted block shows German grey, the same grey CBC's steel block reads as.
		this.registerDefaultState(this.stateDefinition.any().setValue(COLOR, DyeColor.WHITE));
	}

	@Override
	protected void createBlockStateDefinition(Builder<Block, BlockState> builder) {
		builder.add(COLOR);
	}

	/**
	 * Right-click with a dye recolours this block, and the dye stack is left untouched.
	 *
	 * <p>Applying the colour server-side with {@code Block#UPDATE_NEIGHBORS | UPDATE_CLIENTS} is what makes the
	 * neighbours re-render too: a neighbour's connected-texture index depends on this block's state, so both the
	 * face on this block and the faces on its neighbours must be rebuilt. Re-dyeing to the colour already applied
	 * falls through to the default interaction so an already-coloured block keeps whatever the held item would
	 * normally do (placing another block, using the item, etc.).
	 */
	@Override
	public ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
			InteractionHand hand, BlockHitResult hitResult) {
		if (stack.getItem() instanceof DyeItem dye && player.mayBuild()) {
			DyeColor color = dye.getDyeColor();
			if (state.getValue(COLOR) == color)
				return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
			if (level.isClientSide)
				return ItemInteractionResult.SUCCESS;
			level.setBlock(pos, state.setValue(COLOR, color), Block.UPDATE_NEIGHBORS | Block.UPDATE_CLIENTS);
			return ItemInteractionResult.SUCCESS;
		}
		return super.useItemOn(stack, state, level, pos, player, hand, hitResult);
	}
}
