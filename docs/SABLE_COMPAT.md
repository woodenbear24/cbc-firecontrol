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
   CBC's existing Sable handling rather than duplicating that handling. Both targets are `public static`, so the
   `@Inject` handlers have to be `static` as well - see the pitfall note at the end.
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

## Pitfall: static targets need static handlers

`v0.0.4b` shipped with instance `@Inject` handlers against CBC's `static` methods, and any player who entered a
ship while CBC's Sable compat was active crashed the server:

```
InvalidInjectionException: non-static callback method ...cbcfc$advancedMountCountsAsAGap
  targets a static method which is not supported   [INJECT_PREPARE Applicator Phase]
MixinApplyError: cbc_firecontrol.mixins.json:compat.sable.CBCSableConnectivityMixin FAILED during APPLY
```

The check happens while mixin *prepares* the injection, so it is not a "missed injector": `injectors.defaultRequire`
does not cover it, the whole transform of the target class fails, and the error surfaces at the first call site that
loads that class (`SubLevelHeatMapManager.step` -> the world tick). Two consequences for anyone touching this mixin:

- Match the target's modifiers exactly - `javap -p -cp <cbc jar> rbasamoyai.createbigcannons.compat.sable.CBCSableConnectivityUtils`
  shows both methods as `public static`.
- This only ever runs when Sable is installed, so a dev run without Sable proves nothing. To exercise the injection
  locally, temporarily let `CBCFCMixinPlugin.shouldApplyMixin` return `true` and force-load the target class from a
  dev-only hook; the log then shows `Mixing compat.sable.CBCSableConnectivityMixin ...` plus one line per injected
  handler, and nothing else. Revert both temporary changes before releasing.

`compatibilityLevel` was measured both ways on this loader: `JAVA_21` and `JAVA_17` (CBC's own value) inject
identically. The DEBUG line `Compatibility level ... is higher than the maximum level supported by this version of
mixin` is emitted by sponge-mixin 0.16.4 for every mod here (flywheel and ponder trigger it too), so it is not our
config misbehaving; `JAVA_17` only adds a per-mixin `Class version 65 required is higher...` note, which is why the
config keeps `JAVA_21`.

player's game over one compat detail.
