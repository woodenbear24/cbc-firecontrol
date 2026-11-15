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

- **A ship's TARGET is converted into the hull's coordinates; its muzzle is not.** `Contraption.toGlobalVector(...)`
  answers in the scale of the level the contraption entity lives in, and on a Sable ship that is the ship's
  sub-level: the muzzle comes back hull-relative (`122, 64, 75`-ish), and so are the mount's own yaw/pitch, because
  a gun bolted to a deck can only turn against the deck. A target read off the world is absolute (`2048xxxx, …`);
  subtracting the two gives tens of millions of blocks, which is why every ship solve answered `TARGET_OUT_OF_REACH`
  for a gun that could plainly reach it. So the TARGET travels: `com.cbc.firecontrol.compat.SableShipCoordinates`
  reflects into Sable's own runtime API (`Sable.HELPER.getContaining(Level, Vec3i)` to find the ship under a world
  position, then `SubLevel#logicalPose()` and `Pose3dc.transformPositionInverse(Vec3)`) and re-expresses the target
  in hull space. The transform is rigid, so the muzzle-to-target distance becomes the real distance while the solved
  angles stay hull-relative, and the terrain check keeps reading the level the gun actually lives in. Pushing the
  muzzle the other way through `CBCCompatTransformers.transformVec3` would make every solved angle an absolute
  bearing the mount cannot follow and would drop the muzzle into a dimension the terrain check does not read.
  A target locked with the designator from inside the ship is already hull-relative, so the bridge only acts when
  the raw muzzle-to-target distance passes `FOREIGN_SCALE_DISTANCE` (100k blocks; no cannon range comes near it) and
  only accepts the conversion when it moves the target NEARER the muzzle. Each decision is logged once per interval,
  including the case where no ship was found under the target position.
- **Do not compute distances between a ship's coordinates and a player's raw coordinates.** A Sable ship's blocks and
  block entities really do exist in the world level, at the ship's footprint, so `world.getBlockEntity(pos)` *does*
  see a mount placed in a ship — a verified example: a mount reported at `20481032, 128, 20483080` in
  `minecraft:overworld`. What is *not* comparable is the coordinate scale: a player standing in the ship reports a
  sub-level LOCAL position (measured: `122, -58, 75`) while the raycast that opened the mount's panel reports the
  mount at the ship's footprint, so a plain `blockPosition()`-vs-`worldPosition` reach check computes a nonsense
  distance of tens of millions of cells and silently drops the action.
  `CBCCompatTransformers.transformBlockPos(level, pos, root)` was tried first and does **not** bridge this pair:
  feeding it the player's local position with the mount's footprint position as `root` still produced a nonsense
  distance (`~20916232 cells`). It maps coordinates used by CBC's own ballistics/particle paths, not arbitrary
  player positions.
  The fix is to stop doing geometry: `CBCFCNetwork.handleControl` validates the *session* instead — the payload is
  accepted only while the player has that mount's `MountFrequencyMenu` open with a matching `contentHolder` — and
  `MountFrequencyMenu.stillValid` / `pushToBlockEntity` resolve the mount through the player's level and then the
  ship's world dimension, with no distance test. A panel is a fixed radio terminal like Create's controller, so it
  stays open until closed or the mount disappears.
  Reaching into a sub-level's own `Level` instance is still avoided (`Sable.HELPER.getContaining`,
  `SubLevelContainer` would be a real dependency we do not declare; Sable is metadata-only in `gradle.properties`).
  Whether guns work at all on a moving ship is CBC's decision, and CBC is where the Sable knowledge lives.

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

## Pitfall: assembling inside a ship is a *positioning* problem, not a mixin problem

CBC's Sable integration contains no assembly/structure logic at all — it only makes the cannon-mount air gap read
as solid in the heat map (`SubLevelHeatMapManagerMixin` + `SableCommonEventsMixin` wrapping
`CBCSableConnectivityUtils`), applies recoil/impact forces (`SableCompat`), and transforms trajectories
(`CBCCompatTransformers`). So "the cannon will not assemble" is never Sable refusing the build; it is one of
three things, each of which now says so in the log:

1. **Ship coordinates and player coordinates are different scales.** The panel's position comes from the client
   raycast and is the mount's world-footprint coordinate; a player inside the ship reports a sub-level local position.
   Comparing them raw produced `mount <pos> action ASSEMBLE ignored: player <name> is too far (distance to mount
   ~28965861 cells in minecraft:overworld; player at 122, -58, 75)` — a real ship mount, rejected by arithmetic rather
   than by any assembly rule. Because the same check sat in front of every action, Yaw/Pitch/Fire were dropped
   silently as well. `handleControl` no longer measures distance: it requires the player to have that mount's
   frequency panel open, and resolves the mount in the player's level, falling back to the ship's world dimension.
   If you see the new `no frequency panel open for that position` warning, the panel was closed before the click
   landed (the old `stillValid` distance test did exactly that on ships); if you see `no advanced cannon mount at
   that position`, the position really has no mount in either level, and the line names both levels searched.
2. **Where the button is.** Empty-hand right-click opens the radio-frequency menu; the footer's **left-most** icon
   (the Create "load/unload" glyph) is the Assemble/Disassemble toggle — hovering it now prints
   `Assemble cannon` / `Disassemble cannon`. Redstone does *not* assemble this mount: `onRedstoneUpdate()` is an
   intentional no-op, and the radio channels are Yaw+/Yaw-/Pitch+/Pitch-/Fire only (no assemble channel).
3. **The cannon body has to sit where the scan looks.** `assemble()` accepts the breech/first body block either
   flush against the mount (`relative(vertical, -1)`, the half-slab look) or one air gap out
   (`relative(vertical, -2)`, stock CBC layout), and records that depth for `resetContraptionToOffset()`. A big
   cannon additionally needs exactly one OPEN end, or CBC rejects the body itself. Every refusal logs
   `[cbc_firecontrol] assemble refused at <pos>: <reason>`, and the reason is also handed to goggles through the
   overridden `getLastAssemblyException()`.

To check the wiring without a ship: place the mount on solid ground, put a complete cannon in one of the two cells
above, right-click the mount, press the toggle, and read `run/logs/latest.log` for
`assemble requested at ...` followed by either `assembled mount ...` or `assemble refused at ...`.


