package com.cbc.firecontrol.init;

import com.cbc.firecontrol.CBCFireControl;
import com.cbc.firecontrol.block.entity.FireControlMountBlockEntity;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Registration center for the mod's BlockEntityTypes.
 */
public class CBCFCBlockEntities {

	public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
			DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, CBCFireControl.MODID);

	/**
	 * Cached handle to the BlockEntityType so the BE constructor supplier (which is
	 * called at BE-instantiation time, not registration time) can pass the type
	 * to CBC's 3-arg KineticBlockEntity constructor.
	 *
	 * CBC's CannonMountBlockEntity constructor signature is
	 *   (BlockEntityType<?> typeIn, BlockPos pos, BlockState state)
	 * but NeoForge's BlockEntitySupplier interface only provides (BlockPos, BlockState).
	 * The static holder bridges this gap.
	 */
	private static BlockEntityType<FireControlMountBlockEntity> BE_TYPE;

	/**
	 * Fire Control Mount BlockEntityType. Valid blocks: only the mod's own
	 * FireControlMountBlock. CBC's own blocks still use CBC's CBCBlockEntities.CANNON_MOUNT type,
	 * so the two coexist without conflict.
	 */
	public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FireControlMountBlockEntity>> FIRE_CONTROL_MOUNT_BE =
			BLOCK_ENTITIES.register("fire_control_mount", () -> {
				BE_TYPE = BlockEntityType.Builder.<FireControlMountBlockEntity>of(
						(pos, state) -> new FireControlMountBlockEntity(BE_TYPE, pos, state),
						CBCFCBlocks.FIRE_CONTROL_MOUNT.get()
				).build(null);
				return BE_TYPE;
			});

	public static void register(IEventBus modEventBus) {
		BLOCK_ENTITIES.register(modEventBus);
	}
}

