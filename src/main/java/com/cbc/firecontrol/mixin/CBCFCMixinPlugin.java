package com.cbc.firecontrol.mixin;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import rbasamoyai.createbigcannons.CBCModsNeoForge;

/**
 * Gate for our mixin config, following CBC's own {@code CBCMixinPlugin} exactly: compatibility mixins for an
 * optional mod are skipped when that mod is absent, and the same installed-mod check (CBC's enum, which reads
 * the loader's mod list) is what decides it.
 *
 * <p>Nothing else is needed here. Our compat mixins target <em>CBC</em> classes rather than Sable's, so they
 * compile and load without Sable being present at all; the gate only keeps them from being applied when there
 * is no sub-level world to make consistent in the first place.
 */
public class CBCFCMixinPlugin implements IMixinConfigPlugin {

	@Override
	public void onLoad(String mixinPackage) {
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		if (mixinClassName.startsWith("com.cbc.firecontrol.mixin.compat.sable") && !CBCModsNeoForge.SABLE.isLoaded())
			return false;
		return true;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

}
