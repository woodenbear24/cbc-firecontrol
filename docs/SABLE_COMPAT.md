# Sable compatibility

Sable (ships / sub-levels) is an **optional** mod. We deliberately do not link against it: CBC is the mod that
knows Sable exists, and CBC already exposes the hooks an addon needs. Everything below is about inheriting CBC's
handling instead of re-implementing a second copy of it.

## What CBC provides

| CBC API | What it does |
| --- | --- |
| `CBCModsNeoForge.SABLE` | Installed-mod check (`LoadingModList`), the same enum CBC's own compat classes use. |
| `CBCCompatTransformers` | Registries CBC fills from `SableCompat.onCommonSetup`: `transformBlockPos` / `transformVec3` (sub-level local -> world, via `SableCompat::transformFromShip`), `transformHitNormal`, projectile fall/grounding handlers. With no transformer registered these are identity. |
| `CBCMixinPlugin` + `createbigcannons.mixins.json` | CBC's mixins, gated per optional mod in `shouldApplyMixin`. |
| `CBCSableConnectivityUtils` | The rule CBC's `SubLevelHeatMapManagerMixin` / `SableCommonEventsMixin` use to treat the air gap between a cannon mount's two halves as solid structure rather than a hole in the ship. |

A cannon on a ship is fired through CBC's contraption path (`AbstractMountedCannonContraption`), so recoil,
impact forces, sub-level grounding and projectile handling all run inside CBC's own Sable compat. Our
`AdvancedCannonMountBlockEntity` extends CBC's `CannonMountBlockEntity`, so it rides that same path and inherits
that behaviour with no work on our side.

## What we inherit

1. **Installed-mod gating, CBC's way.** `com.cbc.firecontrol.mixin.CBCFCMixinPlugin` mirrors `CBCMixinPlugin`: a
   `mixin.compat.sable` class is skipped unless `CBCModsNeoForge.SABLE.isLoaded()`. Our compat mixin targets a
   *CBC* class, so it compiles without Sable on the classpath; the gate only keeps it from applying when there is
   no sub-level world to make consistent.
2. **The mount air gap.** `CBCSableConnectivityUtils` checks `CBCBlocks.CANNON_MOUNT` by block identity, so our
   mount (a subclass of the same block, with the same `VERTICAL_DIRECTION` / `HORIZONTAL_FACING` semantics) still
   read as a hole in the ship's heat map. `CBCSableConnectivityMixin` injects at `RETURN` of
   `isCannonMountAirGap` and `getCannonMountGaps` and adds our block to the verdict, which puts our mount inside
   CBC's existing Sable handling rather than duplicating that handling.
3. **Mod metadata.** `neoforge.mods.toml` declares `[[mixins]]` (NeoForge reads mixin configs from there, so no
   Gradle mixin plugin is needed on 1.21) and `sable` as an `optional`, `ordering=NONE`, `side=BOTH` dependency,
   in the same shape CBC declares its own soft deps. `gradle.properties` carries only a `sable_version_range` for
   that metadata - no Sable artifact is resolved.

## What we deliberately do not do

- **Do not re-transform ballistics coordinates.** `CBCCompatTransformers.transformVec3(level, pos, root)` maps a
  coordinate that is *inside* the sub-level at `root` from sub-level space to world space. Our muzzle comes from
  `Contraption.toGlobalVector(...)`, which is already world space; feeding it through the transformer would apply
  the ship pose a second time whenever the gun happens to sit inside a ship's bounds.
- **Do not reach into sub-level block entities.** A mount placed inside a sub-level lives in Sable's sub-level
  `Level`, not the world level, so `world.getBlockEntity(pos)` cannot see it. Resolving that would need the Sable
  API (`Sable.HELPER.getContaining`, `SubLevelContainer`), i.e. a real dependency we are avoiding. A calculator
  reads mounts that exist in the level it is in; whether guns work at all on a moving ship is CBC's decision, and
  CBC is where the Sable knowledge lives.

`injectors.defaultRequire` is `0` in our mixin config: a missed injection logs a warning instead of crashing a
player's game over one compat detail.
