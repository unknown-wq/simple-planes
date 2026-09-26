# Missiles and launch tubes: render model contract

This file is for whoever writes the missiles' entity, the launch tube block, their renderers and the gameplay.
It covers the **render models only**. It was written for the 26.2 line and ported to 26.3 with the models; the
gameplay that now uses them (entity, silo block, block entity, renderers, commands) is described in
[`MISSILES.md`](MISSILES.md).

## Changes in the 26.3 port

Checked against the decompiled 26.3 sources (world version 5023):

- The three model classes compile unchanged: `Model`, `EntityModel`, `ModelPart`, the mesh builders and
  `RenderTypes.entityCutoutCull` / `RenderTypes.eyes` are the same.
- `PoseStack.mulPose(Quaternionfc)` is `PoseStack.rotate(Quaternionfc)` in 26.3. The renderer snippets below
  still say `mulPose`; read it as `rotate`.
- `SubmitNodeCollector.submitModel` lost its trailing crumbling-overlay argument. The 7-argument overload
  `(model, state, poseStack, renderType, light, overlay, outlineColor)` is the one to use, and block breaking
  progress is drawn separately with `collector.order(1).submitCrumblingOverlay(model, state, poseStack,
  renderType, light, overlay, -1, state.breakProgress)`, as `ChestRenderer` does.
- Added for staging: `MissileRenderState.boosterAttached` and `MissileModel.setStaged(boolean)`, called from
  `setupAnim`. Staged, the tier-4 `Booster` is hidden and the `Flame` part moves to the sustainer nozzle at
  y = −25 px (`SUSTAINER_NOZZLE_Y`), narrowed to 0.6 of its girth. This answers "a sustainer flame would be a
  new part" below without a new part.
- Added for the glow pass: `MissileModel.flameOnly()`, which does the "hide every child but `Flame`" step
  described under "Glowing flame" once, and keeps `setStaged` from showing the booster again.

| Kind | Class | Extends | Texture | Cubes |
|---|---|---|---|---|
| missile, tiers 1 to 4 | `client/render/models/MissileModel` | `EntityModel<MissileRenderState>` | `simpleplanes:textures/entity/missile.png` (128x128) | 16 / 21 / 26 / 33 |
| launch tube, tiers 1 to 4 | `client/render/models/LaunchTubeModel` | `Model<Float>` (the hatch opening) | `simpleplanes:textures/entity/launch_tube.png` (256x512) | 16 / 16 / 17 / 17 |
| render state | `client/render/MissileRenderState` | `EntityRenderState` | | |

The missile cube counts include the 3 flame cubes, which are hidden unless the thrust is above 5%.

## Class structure: one class per kind, `createBodyLayer(int tier)`

Both models use one class per kind with a tier factory, `MissileModel.createBodyLayer(tier)` and
`LaunchTubeModel.createBodyLayer(tier)`, rather than eight classes. The reasons:

- **The part names and hooks are the same for every tier.** Every missile has `Missile` > `Body`, `Flame` and
  fin groups whose leaves are all called `hinge`, so `setFinsDeployed` and `setThrust` are written once. Every
  tube has `Tube` > `Shaft` plus either `hatch` or `hatch_left`/`hatch_right`, so `setHatchOpen` is written once.
- **One renderer drives all tiers.** It bakes four layers of the same class and picks one by
  `state.tier`; the code that poses and submits the model does not branch.
- **The tiers differ only in data**, such as cube sizes and which fin groups exist. That data lives in four
  small `tierN(...)` methods, which read like the per-tier classes would, without four copies of the hooks.
- The tier constants a gameplay author needs are public on the classes: `LENGTH_PX`, `BODY_WIDTH_PX`,
  `FOLDED_WIDTH_PX` and `DEPLOYED_SPAN_PX` on `MissileModel`, and `FOOTPRINT_BLOCKS`, `BORE_PX`, `DEPTH_PX`
  and `SEAT_PX` on `LaunchTubeModel`.

The tube extends `Model<Float>` directly, **as vanilla block-entity models do in 26.2**. `ChestModel` is
`Model<Float>`, with the lid opening as its state, and `BellModel` is `Model<BellModel.State>`. `EntityModel`
requires an `EntityRenderState`, which a block entity does not have.

## Axes and origin

Units are pixels (1 px = 1/16 block). Model space is **Y down**, the usual `ModelPart` convention. MODELS.md
defines its axes for aircraft (nose to −Z); these models stand vertically, so they use their own axes, described
below.

### Missile

- The root part `Missile` is at `PartPose.offset(0, 24, 0)`, as in every entity model in the mod.
- In the root's frame:
  - the **missile axis is x = z = 0**;
  - the **base** (the nozzle exit plane) is at **y = 0**;
  - the **nose tip** is at **y = −16 · tier**;
  - the **nose points to model −Y**, which is world up for an upright missile.
- The four fins of a set sit on the four body faces: +X, −Z, −X and +Z, in that order (`fin_0` to `fin_3`).
  The model is symmetric about the axis apart from the fin fold direction.
- **At rest in the tube** the missile is upright (identity orientation) and its base sits on the launch seat.
- **In flight** the renderer rotates the model so that its nose axis (world +Y at identity) points along the
  velocity; see "Missile renderer" below. The roll about the axis is free, because the model has four-fold
  symmetry.

With the upright renderer chain `scale(-1,-1,1) · translate(0,-1.5,0)` and no rotation, an absolute model pixel `p`
maps to entity space as follows:

```
entity = ( -p.x/16 ,  (24 - p.y)/16 ,  p.z/16 )        (blocks, Y up; base at y = 0, nose at y = tier)
```

### Tube

- The root part `Tube` is at `PartPose.ZERO`. **The origin is the centre of the footprint, at ground level.**
- The ground surface is **y = 0**, and the shaft runs down to **y = +DEPTH_PX**. Nothing on the tube is ever
  above y = 0 while the hatch is closed.
- The hatch hinges run along Z, at the ±X rims of the bore.

With the BER chain `translate(ox, 1, oz) · scale(-1,-1,1)`, a model pixel `p` maps to world space relative to
the block entity's `BlockPos` as follows:

```
world = ( ox - p.x/16 ,  1 - p.y/16 ,  oz + p.z/16 )
```

## Missile dimensions

"Folded" is the width across the fins as stowed in the tube. "Span" is the width from fin tip to fin tip with
the fins deployed.

| Tier | Length (= height) | Body width | Folded | Span | Fin sets (y ranges, px) | Tier bands | Cubes |
|---|---|---|---|---|---|---|---|
| 1 | 16 px = **1 block** | 4 px | 6 px | 8 px | `tail_fins` −6..−1 | 1 | 13 + 3 flame |
| 2 | 32 px = **2 blocks** | 6 px | 8 px | 12 px | `canards` −24..−21, `tail_fins` −10..−2 | 2 | 18 + 3 |
| 3 | 48 px = **3 blocks** | 8 px | 10 px | 16 px | `strakes` −27..−16, `tail_fins` −14..−2 | 3 | 23 + 3 |
| 4 | 64 px = **4 blocks** | booster 14 px, sustainer 10 px | 16 px | 26 px | `sustainer_fins` −33..−25, `booster_fins` −16..−2 | 4 | 30 + 3 |

- **Tier 4 stages.** The booster runs from y = 0 to −22, the interstage from −22 to −25, and the sustainer
  and nose from −25 to −64. The booster, with its nozzle, interstage and `booster_fins`, is a separate child
  part, `Booster`, which is returned by `MissileModel.booster()` and is null for tiers 1 to 3. A staging effect
  can set `booster().visible = false`. After staging there is no second flame at y = −25: that would be a new
  part.
- **Markings.** They are consistent across tiers so the tier can be read at a glance:
  - a yellow warhead ring at the top of the body;
  - **N red bands = tier N** below it;
  - a dark seeker tip.

  The tube hatch repeats the same N red bars on its top face, so the tier can also be read from above.
- **Folded fins.** Each fin is hinged at its root and folds flat onto its body face, pinwheel fashion. That is
  why the folded width is only body + 2 px.

Measured from the baked models:

- upright, the bbox y runs from 0.000 to exactly 1.000, 2.000, 3.000 and 4.000;
- the folded half-widths are 0.1875, 0.25, 0.3125 and 0.5 blocks;
- the deployed half-spans are 0.25, 0.375, 0.5 and 0.8125 blocks.

## Launch tubes

| Tier | Footprint | Bore | Wall | Depth | Seat (missile base) | Nose in tube | Clearance per side (folded) | Cubes |
|---|---|---|---|---|---|---|---|---|
| 1 | **1x1** | 10 px | 3 px | 32 px = 2 blocks | 20 px = 1.25 blocks | 4 px | 2 px | 16 |
| 2 | **1x1** | 12 px | 2 px | 48 px = 3 blocks | 36 px = 2.25 blocks | 4 px | 2 px | 16 |
| 3 | **2x2** | 16 px | 8 px | 64 px = 4 blocks | 52 px = 3.25 blocks | 4 px | 3 px | 17 |
| 4 | **2x2** | 20 px | 6 px | 80 px = 5 blocks | 68 px = 4.25 blocks | 4 px | 2 px | 17 |

The layers of a tube, from the surface down (y in px), using tier 1 as the example:

| y | Layer |
|---|---|
| 0 to 2 | the hatch, 2 px thick, whose top is flush with the surface |
| 2 to 4 | 2 px of headroom |
| 4 to 20 | the missile, nose at 4 and base on the seat at 20 |
| 20 to 21 | a 1 px launch seat plate |
| 21 to 27 | the exhaust plenum |
| 27 to 30 | a flame deflector block on the floor |
| 30 to 32 | the floor, 2 px |

- **Depth.** The depth is always **tier + 1 whole blocks**. The 4 px above the nose and the 12 px below the base
  (seat, plenum, deflector, floor) add up to exactly one extra block, so the structure occupies whole blocks.
- **Guide rails.** Four 1x1 px rails stand in the bore corners, from y = 2 down to the seat. They sit outside
  the folded missile's square.
- **Walls.** The walls are four side walls plus four corner posts, rather than four overlapping walls. That way
  every edge of the collar top meets another edge exactly. A ring of four overlapping walls leaves a T-junction
  crack along the collar, which showed up in the top view.

**Footprint rationale.** A missile needs its folded width plus 2 px of clearance on each side. The tube also needs
a visible rim of at least 2 px, carrying a 1 px hazard ring and a kerb.

| Tier | Needs a bore of | Fits in 1x1? |
|---|---|---|
| 1 | 10 px | yes, with 3 px walls |
| 2 | 12 px | yes, with 2 px walls |
| 3 | 14 to 16 px | only with 1 px walls, which leaves no rim, no hinge room and a hazard ring that covers the whole collar |
| 4 | at least 20 px | no: its 16 px folded width alone fills a block |

So tiers 3 and 4 use a 2x2 footprint. That also makes the heavy tiers read as heavier installations.

**Hatch.**

- On tiers 1 and 2 the hatch is a single leaf (`hatch`) hinged at the model −X rim of the bore.
- On tiers 3 and 4 it is a clamshell (`hatch_left` and `hatch_right`), hinged at the −X and +X rims, with the
  two leaves meeting at x = 0.
- Open means swung up by `HATCH_OPEN_ANGLE` = 105°, past upright and leaning outwards.
- With the BER chain's x flip, the single leaf appears on the world +X side.
- Fully open, the tier-2 leaf leans 1.1 px past the footprint over the neighbouring block, and the others stay
  within their footprint. This is visual only.
- Fully open, a leaf's hinge corner sits 1.9 px inside the bore rim, which just clears the folded missile
  (2 px clearance). **Do not start moving the missile before the hatch is fully open.**

**Suggested block layout.** The block entity is the tube's **top** block (its top face is the surface):

- 1x1: the BE block itself, `ox = oz = 0.5`;
- 2x2: the min-X / min-Z block of the top layer, `ox = oz = 1.0`, the shared corner of the four columns.

The shaft occupies the `tier` blocks below the top layer, as solid or dummy blocks.

## Hooks

All hooks clamp their input to [0, 1] and must run **after** `super.setupAnim(...)`. In 26.2 `Model.setupAnim`
does nothing but `resetPose()`.

**Drive the hooks through the render state, not by calling them before a submit.** In 26.2,
`SubmitNodeCollector.submitModel(model, state, …)` is deferred: `ModelFeatureRenderer` calls
`model.setupAnim(state)` itself, right before it draws. That call resets the pose and replays the hooks from
the state, so anything set by hand before the submit is lost. The hooks are public so that the preview (and
tests) can pose a model directly.

| Hook | Class | 0 | 1 |
|---|---|---|---|
| `setFinsDeployed(float)` | `MissileModel` | fins folded flat on the body, as stowed in the tube | fins deployed |
| `setThrust(float)` | `MissileModel` | no flame (hidden at ≤ 5%) | longest flame |
| `setHatchOpen(float)` | `LaunchTubeModel` | closed, flush | open by 105° |

- `setFinsDeployed(d)` sets `yRot = (1 − d) · π/2` on every `hinge` part. The hinge is on the body surface,
  at the middle of the fin's 1 px root.
- `setThrust(t)`, in the spirit of `FighterExhaustModel.applyThrottle`:
  - `Flame.visible = t > 0.05`;
  - `Flame.yScale = 0.4 + 1.2 t` (the length);
  - `Flame.xScale = zScale = 0.8 + 0.3 t` (the girth);
  - it scales about the nozzle exit.

  The flame is three nested cubes, getting narrower and longer, with a ragged cut-out tail. Their modelled
  lengths are 11, 16, 21 and 27 px, so at full thrust it reaches **1.1, 1.6, 2.1 and 2.7 blocks** below the
  base.
- `setHatchOpen(o)` sets `hatch.zRot = −o · 105°`, or `hatch_left.zRot = −o · 105°` and
  `hatch_right.zRot = +o · 105°`.
- `MissileModel.setupAnim(state)` calls `setFinsDeployed(state.finsDeployed)` and `setThrust(state.thrust)`.
  `LaunchTubeModel.setupAnim(Float)` calls `setHatchOpen(value)`.

`MissileRenderState` has these fields:

- `tier`;
- `finsDeployed`;
- `thrust`;
- `rotation`, a `Quaternionf`; identity means upright, nose to world +Y.

The fighter hooks needed no render-state fields because nothing filled them. These have fields so a future
renderer only has to fill them.

## Key points

Each point below is given in the missile's local frame (px, root frame) and in blocks for an upright missile
whose base is at entity or world point `B`.

| Point | Local (px) | Upright | In flight |
|---|---|---|---|
| **Nose / warhead point** (where a proximity or impact check measures from) | (0, −16·tier, 0) | `B + (0, tier, 0)` | `C + q·(0, +L/2, 0)` |
| Warhead ring (the yellow ring, where the warhead section starts) | tier 1: y = −12; tier 2: −25; tier 3: −38; tier 4: −52 | `B + (0, −y/16, 0)` | |
| **Flame origin** (the nozzle exit, centre of the base) | (0, 0, 0) | `B` | `C + q·(0, −L/2, 0)`, flame pointing along `q·(0, −1, 0)` |
| Centre of the length (the rotation pivot) | (0, −8·tier, 0) | `B + (0, tier/2, 0)` | `C` |

- `L` is `tier` blocks.
- `q` is `state.rotation`.
- `C` is the hitbox centre (below).

**Launch start point.** Seated in the tube, the missile's base is at

```
surface centre of the footprint - (0, SEAT_PX[tier] / 16, 0)
```

that is, 1.25, 2.25, 3.25 or 4.25 blocks below the surface. The launch is a straight rise along +Y from there:

- the nose reaches the surface after 0.25 blocks;
- the base clears the surface after `SEAT_PX/16` blocks.

The "launch moment" renders show the missile with its base at −L/2 (half out), fins still folded and thrust 1.
Fins are meant to deploy only once the missile is clear of the tube: `finsDeployed` stays 0 until
base > surface. With the fins deployed the missile no longer fits the bore.

## Suggested hitboxes and shadows

A Minecraft AABB cannot rotate, and these missiles are long and thin. The suggestion is therefore a small cube
centred on the missile's mid-length, plus a **segment test from tail to nose** (the two key points above) for
terrain and target impact each tick. Measure proximity fuzing from the nose point.

| Tier | `sized(w, h)` | `shadowRadius` |
|---|---|---|
| 1 | `sized(0.4F, 0.4F)` | 0.2 |
| 2 | `sized(0.5F, 0.5F)` | 0.3 |
| 3 | `sized(0.625F, 0.625F)` | 0.4 |
| 4 | `sized(1.0F, 1.0F)` | 0.6 |

These widths are about the folded width. A cube keeps the box meaningful at any pitch.

For the tube block, the full footprint is solid except the bore. The collision of an open tube is gameplay's
choice.

## What a missile renderer needs

`EntityRenderer<MissileEntity, MissileRenderState>`:

- **Layers.** Four `ModelLayerLocation`s, e.g. `missile_t1` to `missile_t4`, each registered with
  `() -> MissileModel.createBodyLayer(n)`. Bake four `MissileModel`s and pick one by `state.tier`.
- **`extractRenderState`.** Copy:
  - `tier`;
  - `finsDeployed`, interpolated;
  - `thrust`;
  - `rotation`, the interpolated orientation, with identity meaning nose up.
- **`submit`.** With `C` at `h/2` above the entity position, `h` being the hitbox height:

  ```java
  poseStack.pushPose();
  poseStack.translate(0.0F, h / 2, 0.0F);                        // hitbox centre C
  poseStack.mulPose(state.rotation);                              // nose (+Y at identity) onto the flight direction
  poseStack.translate(0.0F, -MissileModel.lengthBlocks(state.tier) / 2, 0.0F);   // missile mid-length onto C
  poseStack.scale(-1.0F, -1.0F, 1.0F);
  poseStack.translate(0.0F, -1.5F, 0.0F);                         // model y = 24 px -> base
  MissileModel model = models[state.tier];                         // setupAnim(state) runs at draw time
  collector.submitModel(model, state, poseStack, model.renderType(TEXTURE),
          state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor, null);
  poseStack.popPose();
  ```

  The three calls from `scale(-1,-1,1)` onwards are the chain every reference render used, with the base at
  `y = 0` and the nose at `y = tier`.
- **Glowing flame.** This is optional but recommended. The flame is lit like everything else, so it goes dark
  at night. To make it glow:
  1. Bake each missile layer **a second time** and wrap it in another `MissileModel`, used only for the glow.
  2. Once, after construction, set `visible = false` on every child of its `Missile` part except `Flame`.
     `resetPose` does not touch `visible`, so they stay hidden.
  3. Submit that model in the same pose with `RenderTypes.eyes(TEXTURE)` and the same state.

  Its `setupAnim` still scales and shows or hides the flame from `state.thrust`. Do not use `submitModelPart`
  on the shared `flame()` part: it is deferred and does not call `setupAnim`, so it would draw whatever pose
  the last missile left. The preview renders show the flame unlit, which is what the glow pass would look
  like.
- **Render type.** `RenderTypes::entityCutoutCull`, passed in the constructor. See "Render types" below.
- **Texture.** `simpleplanes:textures/entity/missile.png`.

## What a block-entity renderer needs

`BlockEntityRenderer<LaunchTubeBlockEntity, LaunchTubeRenderState>`, with the render state extending
`BlockEntityRenderState` and carrying `tier`, `hatchOpen` (interpolated) and, if a missile is loaded,
`missileLoaded`:

- **Layers.** Four `ModelLayerLocation`s for the tubes (`() -> LaunchTubeModel.createBodyLayer(n)`). Bake the
  four missile layers too, so the renderer can draw the stowed missile.
- **`submit`:**

  ```java
  float o = LaunchTubeModel.footprintBlocks(tier) / 2.0F;         // 0.5 (1x1) or 1.0 (2x2, BE at the min corner)
  poseStack.pushPose();
  poseStack.translate(o, 1.0F, o);                                 // footprint centre, top face of the BE block
  poseStack.scale(-1.0F, -1.0F, 1.0F);                             // model Y-down -> world Y-up
  collector.submitModel(tube, state.hatchOpen, poseStack, tube.renderType(TUBE_TEXTURE),
          state.lightCoords, OverlayTexture.NO_OVERLAY, 0, state.breakProgress);
  if (state.missileLoaded) {                                       // the stowed missile, same frame
      poseStack.translate(0.0F, (LaunchTubeModel.SEAT_PX[tier] - 24) / 16.0F, 0.0F);
      collector.submitModel(missile, STOWED, poseStack,                // STOWED: a MissileRenderState with tier set,
                                                                     // finsDeployed = 0, thrust = 0 missile.renderType(MISSILE_TEXTURE),
              state.lightCoords, OverlayTexture.NO_OVERLAY, 0, null);
  }
  poseStack.popPose();
  ```

  Once launched, the missile should become the entity and stop being drawn by the BER.
- **Culling.** The geometry reaches `tier` blocks below the BE block and, for 2x2, into three neighbouring
  columns. Override `shouldRenderOffScreen()` to return `true`, or the tube disappears when its top block
  leaves the view.
- **Lighting.** The BE's light is that of the top block. Deep in the shaft that is brighter than it would be;
  the textures darken the shaft walls with depth to compensate.
- **Render type.** `RenderTypes::entityCutoutCull`, passed in the constructor. The texture is a plain
  `Identifier`, `simpleplanes:textures/entity/launch_tube.png`, not an atlas sprite.

## Textures

Both textures are new, original and procedurally generated (Python + Pillow). They are RGBA and contain no
upstream or third-party art.

- **`missile.png`, 128x128.** Every missile cube of every tier has its own box-UV net: 54 nets, packed first-fit
  with a 1 px gutter. It holds:
  - white body panels with the yellow ring and red tier bands;
  - grey fins with darker leading and trailing edges;
  - a dark seeker tip;
  - a burnt nozzle with a dark throat;
  - the tier-4 gunmetal booster and hazard-striped interstage;
  - the flame ramps, white-yellow to orange to red.

  The **only transparent texels** are the ragged ends of the two outer flame cubes.
- **`launch_tube.png`, 256x512.** Every tube cube has its own net: 52 nets. It holds:
  - concrete collar tops, with a 1 px yellow/black hazard ring round the bore and a darker kerb round the
    footprint;
  - steel shaft linings that darken with depth, with a seam every block;
  - an olive hatch with a dark rim, N red tier bars and a ribbed underside;
  - the seat plate with its exhaust hole;
  - the rails, the scorched deflector and the floor.

  The two clamshell leaves share one net; the right leaf is `.mirror()`ed so that both hinge edges match.
- The textures live under `textures/entity/`, not `textures/plane_upgrades/` as MODELS.md suggests for aircraft
  metal. These are an entity and a block entity, not plane upgrades.
- The `texOffs` in both Java files and the two PNGs were produced together by a generator script that is not
  committed. The Java is plain and may be edited by hand; if a cube's size changes, its net has to be
  repainted.

## Render types

Both models pass **`RenderTypes::entityCutoutCull`** explicitly. In 26.2 the default `EntityModel` render type,
`RenderTypes.entityCutout`, is built on the `pipeline/entity_cutout` pipeline **with `withCull(false)`**, so it
does **not** cull back faces. `entityCutoutCull` is the culling variant, and `ChestModel` uses the same one.
Every box in these models is closed, so the result looks the same either way; culling just skips the hidden
faces. The previews render front faces only, which matches `entityCutoutCull`.

## How the renders were made

The geometry comes from the compiled classes (the final set from `build/classes/java/main` after
`mc-build.sh … compileJava`):

1. `createBodyLayer(tier)` → `bakeRoot()`.
2. The real model is constructed around the baked root and the hooks are called on it.
3. The parts are walked the way `ModelPart.render` does it, under the transform chains above, and rendered with
   three.js.

The preview adds:

- a ground plane with holes over the tube footprints;
- clipped cutaways with flat section caps, a soil backdrop behind them;
- a vertical block grid for the side views and the lineup;
- the flame drawn unlit.

The lighting is not Minecraft's.

## Things the gameplay work will run into

- **Hatch before motion.** The open leaves clear the folded missile by only about 0.1 px. Keep the missile
  seated until `hatchOpen` reaches 1.
- **Fins before exit.** Deployed fins do not fit the bore on tiers 2 to 4 (tier 2 touches, tier 4 is 6 px too
  wide). Deploy only after the base clears the surface.
- **Flame inside the tube.** At launch the flame is inside the shaft and can hardly be seen from outside.
  Particles (smoke from the mouth) will carry the launch visually. There is no smoke in the model.
- **Staging (tier 4).** Hiding `Booster` leaves the sustainer with no flame. A sustainer flame would be a new
  part at y = −25.
- **Not checked in game:** lighting, emissive flame, BER culling, the stowed missile inside a real shaft and the
  T-junction between the collar and terrain blocks.
