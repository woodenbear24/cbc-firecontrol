package com.cbc.firecontrol.client;

import com.cbc.firecontrol.CBCFireControl;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import net.neoforged.neoforge.client.ClientHooks;

/**
 * Item renderer for the Target Designator, mirroring vanilla 1.21.1's own split between the two spyglass
 * models instead of forcing one model into every context.
 *
 * <p>Vanilla 1.21.1 selects the tube by hardcoded item: {@code ItemRenderer#getModel} hands
 * {@code ItemRenderer.SPYGLASS_IN_HAND_MODEL} to the paths that draw the stack as an entity or in a hand,
 * while {@code ItemRenderer#renderModelLists} swaps it back for the flat sprite {@code SPYGLASS_MODEL}
 * whenever the context is GUI, GROUND or FIXED (its {@code flag} covers exactly those three). So vanilla
 * shows the tube in hand and the flat sprite in the inventory, on the ground and in item frames. Copying that
 * split is what keeps the tube out of the hotbar, whose slot never had room for the tube at display scale.
 *
 * <p>A modded copy gets the vignette, the zoom and the raised-to-eye arm pose for free (all driven by the
 * {@code SPYGLASS_SCOPE} item ability) but would otherwise draw a flat sprite everywhere, because both vanilla
 * model selections check {@code Items.SPYGLASS}. There is no supported way to switch models per state on this
 * version - no item model definition file, and no way to register a model predicate until 1.21.2 - so the
 * item model is {@code minecraft:builtin/entity} (which reports itself as custom-rendered, sending every
 * context here) and this class picks between two side-loaded models by context.
 *
 * <p>The model is a copy of vanilla's tube rather than a redraw: {@code models/item/target_designator_model.json}
 * duplicates the two elements of {@code minecraft:item/spyglass_in_hand}, narrows their cross-section from
 * 2 and 2.2 units to 1.75 and 1.95 so the pair fits, and joins them with a two-unit bridge between them. UV, faces
 * and the texture ({@code minecraft:item/spyglass_model}, declared as {@code texture_size: [16, 16]}) come
 * straight from the vanilla file; the tubes' inward faces land on the bridge's end planes, which the bridge
 * covers, so nothing has to be trimmed. The pair is then shifted so it is symmetric about {@code x=0} while the
 * original y and z offsets are kept. That split matters: {@code PlayerItemInHandLayer#renderArmWithSpyglass}
 * renders the stack through {@code CustomHeadLayer.translateToHead} (translate {@code (0,-0.25,0)},
 * {@code rotateY(180)}, scale {@code (0.625,-0.625,-0.625)}) and {@code ItemTransform#apply} rotates about the
 * model's ORIGIN, so an off-centre geometry contributes a real displacement - at head scale 1.5 the x=8 the
 * vanilla tube sits at is {@code 8 * 0.625 * 1.5} head-model units, half a head width, which is where a monocular
 * spyglass belongs but not where a pair has to be to straddle both eyes. {@code ItemTransform.Deserializer}
 * clamps translation to +-5 item units (json +-80), so that offset cannot be cancelled from the display table and
 * has to be removed from the geometry instead. The y/z off-centre is left alone deliberately: rotated 90 degrees
 * about the origin it is what carries the objective out in front of the face and parks the eyepiece at eye level.
 * Only {@code head}'s scale differs from vanilla, 1.5 rather than 1.6, which puts the 1.75-unit barrels at 1.64
 * head-model units against the skin's 2-unit eyes and the 3.75-unit barrel distance at 3.5 units against a 4-unit
 * eye spacing. {@code ground}, {@code gui} and {@code fixed} carry
 * vanilla's values; {@code thirdperson_righthand} keeps vanilla's transform but moves two units left (
 * {@code [-2, -2, 0]} rather than vanilla's {@code [0, -2, 0]}) because the pair is wider than a single tube, and
 * no left-hand entry is needed since {@code ItemTransform#apply} mirrors it. It declares NO {@code firstperson_*}
 * and no
 * {@code *_lefthand} entry, exactly as the vanilla tube does: first-person placement comes from
 * {@code ItemInHandRenderer#applyItemArmTransform}, which already turns a held item by -90 degrees about X, and
 * {@code ItemTransform#apply} mirrors whatever entry a left-hand context resolves to. Hand-written left values
 * would therefore be mirrored a second time, and hand-written first-person values would rotate the model on top
 * of the arm transform instead of letting that transform place it. The flat sprite is
 * {@code models/item/target_designator_flat.json} (parent {@code minecraft:item/generated}, {@code layer0:
 * cbc_firecontrol:item/target_designator_flat}), vanilla's own icon stamped twice plus the bridge.
 *
 * <p>Scoping needs no special case here: {@code ItemInHandRenderer#renderArmWithItem} bails out on
 * {@code player.isScoping()} for every item, so in first person nothing is drawn while the scope is up - the
 * vignette is the whole picture, exactly as vanilla. In third person the spyglass arm path draws it in the
 * HEAD context, where the head transform places it; the {@code thirdperson_righthand} shift therefore only
 * affects the ordinary raised-arm hold, not the scoping pose.
 */
public class TargetDesignatorRenderer extends BlockEntityWithoutLevelRenderer {

	/**
	 * The tube, with vanilla's placement re-declared on the child rather than inherited. Side-loaded (nothing references it - see
	 * {@code CBCFCClientEvents#onRegisterAdditionalModels}), which on NeoForge 1.21.1 means it must use the
	 * 'standalone' variant; asking for the 'inventory' variant throws during baking.
	 */
	private static final ModelResourceLocation TUBE = ModelResourceLocation.standalone(
			ResourceLocation.fromNamespaceAndPath(CBCFireControl.MODID, "item/target_designator_model"));

	/**
	 * The flat sprite vanilla reserves for GUI, GROUND and FIXED, reached through this same custom-renderer
	 * path so the item's marker model can stay {@code builtin/entity}.
	 */
	private static final ModelResourceLocation FLAT = ModelResourceLocation.standalone(
			ResourceLocation.fromNamespaceAndPath(CBCFireControl.MODID, "item/target_designator_flat"));

	/** Vanilla's own context split: these three get the flat sprite, anything held or worn gets the tube. */
	private static boolean usesFlatSprite(ItemDisplayContext context) {
		return context == ItemDisplayContext.GUI || context == ItemDisplayContext.GROUND
				|| context == ItemDisplayContext.FIXED;
	}

	public TargetDesignatorRenderer() {
		super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
	}

	@Override
	public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack poseStack,
			MultiBufferSource buffers, int packedLight, int packedOverlay) {
		draw(Minecraft.getInstance().getItemRenderer(), usesFlatSprite(context) ? FLAT : TUBE, stack, context,
				poseStack, buffers, packedLight, packedOverlay);
	}

	/**
	 * Draw a baked model with the transforms that belong to the model actually being drawn, not the
	 * ones the caller already applied for our marker model. {@code ItemRenderer#render} runs
	 * {@code handleCameraTransforms} on the model it was handed - here the {@code builtin/entity}
	 * marker, whose transforms are the identity - and then shifts the pose stack by -0.5 before
	 * delegating to a custom renderer. That shift exists so the quads a renderer emits land centred in
	 * the unit cube, which is why vanilla's own custom renderers never repeat it. So: undo the shift
	 * made by the caller, apply the real model transform for this context, then re-apply -0.5. The
	 * result is vanilla's chain, {@code transform(context)} then {@code translate(-0.5, -0.5, -0.5)},
	 * and it stops the tube from being displaced twice - once inside the arm frame and again after its
	 * own rotation.
	 */
	private static void draw(ItemRenderer renderer, ModelResourceLocation location, ItemStack stack,
			ItemDisplayContext context, PoseStack poseStack, MultiBufferSource buffers, int light, int overlay) {
		BakedModel model = renderer.getItemModelShaper().getModelManager().getModel(location);
		poseStack.pushPose();
		poseStack.translate(0.5F, 0.5F, 0.5F);
		ClientHooks.handleCameraTransforms(poseStack, model, context,
				context == ItemDisplayContext.THIRD_PERSON_LEFT_HAND || context == ItemDisplayContext.FIRST_PERSON_LEFT_HAND);
		poseStack.translate(-0.5F, -0.5F, -0.5F);
		for (BakedModel pass : model.getRenderPasses(stack, true)) {
			for (RenderType type : pass.getRenderTypes(stack, true)) {
				// renderQuadList is ItemRenderer's public quad path; it also applies item tinting, which the tube's
				// untinted quads ignore, and the GUI/front shading vanilla derives inside it.
				renderer.renderQuadList(poseStack, ItemRenderer.getFoilBufferDirect(buffers, type, true, stack.hasFoil()),
						pass.getQuads(null, null, RandomSource.create(location.hashCode())), stack, light, overlay);
			}
		}
		poseStack.popPose();
	}
}
