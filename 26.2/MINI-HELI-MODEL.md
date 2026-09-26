# Mini helicopter: render model contract

This file is for whoever writes the mini helicopter's entity, physics and registration. It covers the render
model only, which exists and compiles. The mini helicopter is meant to be the smallest and simplest aircraft
in the mod: a one-seat cabin helicopter about half the size of the existing helicopter.

| Layer | Class | Texture | Render type | Cubes |
|---|---|---|---|---|
| material, standard version | `client/render/models/MiniHeliModel` | `state.materialTexture` (the block texture, tiled 16x16, same as `PlaneModel`) | `entityCutout` (default) | 14 |
| material, medical version | `client/render/models/MiniHeliMedicalModel` | `simpleplanes:textures/plane_upgrades/mini_heli_medical.png` (128x64, `MiniHeliMedicalModel.TEXTURE`) | `entityCutout` (default) | 14 |
| metal | `client/render/models/MiniHeliMetalModel` | `simpleplanes:textures/plane_upgrades/mini_heli_metal.png` (128x64) | `entityCutout` (default) | 12 |
| rotors (propeller slot) | `client/render/models/MiniHeliRotorModel` | same `mini_heli_metal.png` | `entityCutout` (default) | 5 |
| **glass (a fourth layer)** | `client/render/models/MiniHeliGlassModel` | `simpleplanes:textures/plane_upgrades/mini_heli_glass.png` (128x32, `MiniHeliGlassModel.TEXTURE`) | **`entityTranslucentCullItemTarget`** | 2 |

A material layer (either one), the metal layer, the rotors and the glass make **33 cubes**. The existing
helicopter has 55 (`HelicopterModel` 19, `HelicopterMetalModel` 26, `HelicopterPropellerModel` 10). For
comparison: drone 43, fighter 56, starter plane 60.

The two material layers are built by one shared class, `MiniHeliAirframe` (package-private); see *Standard
or medical*. The glass layer is new to `PlaneRenderer` and needs a few lines of renderer code; see *The glass
layer*.

Nothing is registered: there is no layer location, renderer, entity type or item. `PlaneRenderState`,
`PlaneRenderer`, `PlanesModelLayers` and `SimplePlanesEntities` are untouched.

## Coordinates

### Model space

Model space is in pixels, where 1 px = 1/16 block. It uses the usual `ModelPart` convention: +Y points down
and the nose points to -Z. +X is the aircraft's left side.

All layers hang everything off one top-level part at `PartPose.offset(0, 24, 0)`: `MiniHeli` (both material
layers), `Metal`, `Rotors` and `Glass`. Their local frames match, and in that local frame:

- the ground contact (the bottom of both skids) is at **y = 0**;
- the cabin tub (body colour) runs from y = -17 (the door sill) to -5 (belly 5 px above the ground), z = -16 to +2,
  x = ±8; in front of it are the nose (y -15 to -6, z -19 to -16) and the chin (y -13 to -7, **nose tip z = -22**);
- above the sill is the glasshouse: the cabin glass (y -28 to -17, z -16 to +2, x = ±8) and the windscreen
  (x = ±6.5, y -28 to -15, z -18 to -16), which stands on the nose;
- four door pillars (body colour) sit at the corners of the cabin glass, x ±7 to ±8, z -16 to -15 and +1 to +2,
  y -28 to -17, grown by 0.05 px so no face lies in the plane of the glass;
- the roof (body colour), y -31 to -28, z -10 to +2, covers the pilot's head; the front of the glasshouse (z -16 to
  -10) is left open to the sky as eyebrow windows;
- the fin's trailing edge is at **z = +37**;
- the main rotor axis (the mast) is at **x = 0, z = 4**, the blades at y -35 to -34 and the hub top at y = -36.5;
- the tail rotor sits on the left (+X) side of the fin, centred on (1.5 .. 2.5, -18, 34).

### Entity space

Entity space is in blocks, with +Y up and the nose at +Z. `PlaneRenderer.submit` maps a model point to the
entity as follows: `translate(0, .375, 0)`, then `scale(-1, -1, 1)`, then `rotY(180)`, then the plane's
quaternion, then the per-type translate `(tx, ty, tz)`, then `translate(0, -1.1, 0)`. With an identity
rotation, an absolute model pixel `p` ends up at:

```
entity = ( p.x/16 ,  1.475 - ty - p.y/16 ,  -tz - p.z/16 )
```

so `tz = -(local z that should land on entity z = 0) / 16`.

## What to put in `PlaneRenderer.submit`

Suggested per-type translate:

```java
} else if (entityType == SimplePlanesEntities.MINI_HELI.get()) {
    poseStack.translate(0.0F, -0.025F, -0.25F);
}
```

This value was used for every reference render. With it the mapping simplifies to (local model coordinates,
i.e. relative to the y = 24 root):

```
entity = ( x/16 ,  -y/16 ,  -(z - 4)/16 )
```

- The skids touch **y = 0** exactly.
- **The main rotor axis is the entity's vertical axis** (entity x = z = 0). This is deliberate and differs from
  the "centre of the length" rule used for the planes: a helicopter hangs under its rotor, the body (cabin plus
  engine) is centred near the mast, and a yaw about the mast is what a pilot expects. The length centre would
  be on the tail boom (local z = 7.5), which would make the cabin swing round a point behind the engine.
- The renderer rotates the model about entity point **(0, 0.375, 0)**, because the quaternion comes before the
  per-type translate. That is local (0, -6, 4): the bottom of the engine pod, straight under the mast, about
  where the centre of mass of a pilot plus engine would be.
- The damage wobble (`timeSinceHit`) rolls about the same point.

Do not leave the mini helicopter on the helicopter branch of `submit` (the `else` default `(0, 0, 0.9)`): that
sinks the skids 0.025 into the ground and shifts the whole model 1.15 blocks back, off the rotor axis.

## Dimensions (entity space, suggested translate)

| | mini helicopter | existing helicopter | ratio |
|---|---|---|---|
| length, nose tip to tail (fin / boom end) | **3.69** (z +1.625 to -2.06) | 7.0 (z +1.68 to -5.34) | 0.53 |
| length including the tail rotor disc | 3.88 | 7.70 | 0.50 |
| main rotor diameter | **3.375** (2 blades, 54 px) | 6.875 (4 blades, 110 px) | 0.49 |
| overall height (rotor hub top / fin top) | 2.28 | 3.07 | 0.74 |
| main rotor blade plane | 2.13 to 2.19 | 2.54 to 2.60 | |
| top of the roof | 1.94 | | |
| door sill / cabin glass | 1.06 / 1.06 to 1.75 | | |
| cabin width (tub, glass, roof) | 1.0 | 1.125 | 0.89 |
| skid track (outer faces) | 1.19 (x ±0.59) | 2.0 | 0.6 |
| skid length (toe included) | about 1.7 | 3.3 | 0.5 |
| belly above the ground | 0.31 | 0.48 | |
| tail rotor diameter | 0.75 | 1.75 | 0.43 |

The existing helicopter was measured from a dump of `HelicopterModel`, `HelicopterMetalModel` and
`HelicopterPropellerModel` with its real translate `(0, 0, 0.9)`.

Length and rotor diameter are about half the helicopter's, as asked; the stepped nose added 0.19 to the length.
Height and width cannot be halved: a seated player is 1.3 blocks tall and 0.94 wide at the arms and has to fit.
So the cabin is almost as wide as the helicopter's, and the rotor sits at 0.74 of its height.
`docs/miniheli/miniheli-scale.png` shows the scale against the helicopter, the starter plane and a 0.6 x 1.8
player box on a 1-block grid.

## Hitbox

`sized(1.5F, 1.95F)`. Width 1.5 covers:

- the cabin (1.0 wide);
- the skids (x ±0.59);
- the engine pod, along the middle 1.5 blocks of the length.

The nose (+1.625) sticks out 0.875 in front and the tail boom 1.3 behind, just as the helicopter's tail sticks
out of its 2.5 x 2.2 box. Height 1.95 reaches the top of the roof (1.94). The mast and the rotor are left out,
as the helicopter leaves out its rotor.

Suggested `shadowRadius` for the `PlaneRenderer` constructor: `0.5F` (helicopter 0.6).

## Seat (`positionRider`)

**One seat, for the pilot.** The cabin rework did not change it. The feet point, i.e. what
`transformPos(...)` should return before it goes into `moveFunction.accept`, is:

| passenger index | role | feet point `(x, y, z)` | model local | eye (entity) |
|---|---|---|---|---|
| 0 | pilot | `(0, 0.0, 0.625)` | (0, 0, -6) | (0, 1.62, 0.625) |

```java
Vector3f pos = transformPos(new Vector3f(0.0f, getPassengersRidingOffset() + getEntityYOffset(passenger), 0.625f));
```

The y value is the sum `getPassengersRidingOffset() + getEntityYOffset(passenger)`:

- if the entity extends `LargeAirframeEntity`, as `HelicopterEntity` does (its `getEntityYOffset` returns -0.4
  for a player), return **0.4f** from `getPassengersRidingOffset()`;
- with a plain `PlaneEntity` (no extra offset), return **0.0f** (the `PlaneEntity` default is 0.5).

The numbers come from the seated player model at 15/16 scale:

- hips 11.25 px above the feet point;
- head top 30 px above it, eye 25.92 px;
- shoulders and arms ±7.5 px;
- legs 11.25 px forward, at hip height.

Where each part of the pilot sits:

- **Hips and legs.** The hips are at local y -11.25, inside the tub. The legs point forward into the nose, under
  the instrument panel, and end at about z -17. The door sill (y -17) is at the pilot's waist.
- **Torso and face** are behind the cabin glass and visible from outside. The seat back (8 x 5 x 2, local z -4 to
  -2) is right behind the torso.
- **Head.** It spans local y -22.5 to -30 and z -9.75 to -2.25. Its top 2 px are inside the roof (y -31 to -28,
  z -10 to +2), and the roof covers the whole head.
- **Arms** (±7.5 px) are inside the ±8 px cabin glass.
- **Eye.** It is at local y -25.92, **inside the cabin glass box** (y -28 to -17), 2.1 px under its top.
- **Clearances.** The mast is 5 px behind the back of the head, and the rotor blades are 4 px above the head top.

The seat can move up by about 1 px (0.06) before the head top meets the roof top, and down by several pixels.
The eye stays inside the cabin glass for any seat between y -0.5 and +0.06.

**Pivot mismatch (all planes share it).** `transformPos` rotates about the entity origin, while the renderer
rotates the model about entity (0, 0.375, 0). When the helicopter pitches or rolls, the seat therefore drifts
by up to `0.375 * sin(angle)` against the model. For the mini helicopter, where the pilot sits in a tight
cabin, rotating about the render pivot keeps the rider exactly in place:

```java
Vector3f pos = transformPos(new Vector3f(0.0f, seatY - 0.375f, 0.625f)).add(0.0f, 0.375f, 0.0f);
```

### What the pilot sees

**The glass is invisible from inside.** The glass layer is back-face culled and the eye is inside
`glass_cabin`. From inside, every cabin-glass face is a back face and is not drawn, so the pilot looks out
through clear glass.

- The one glass face that points at the eye is the rear of the windscreen. It is alpha 0 in
  `mini_heli_glass.png`, below the pipeline's 0.1 cutout, so it is discarded.
- The glass faces that lie on the tub, under the roof or behind the windscreen are alpha 0 too.

**The other layers stay unculled.** No material, metal or rotor cube contains the eye, so those layers keep the
default `entityCutout`. What the pilot sees of them is wanted:

- the two front door pillars, framing the view;
- the instrument panel on the sill, with four coloured lights on the face towards the pilot;
- the nose top below it, and the tub sides;
- the roof's underside overhead.

The roof starts 4 px ahead of the eye. Straight ahead, the view is clear from the ground up to about 27° above
the horizon, and higher still through the eyebrow windows.

## The glass layer

The cabin glass and the windscreen are real see-through glass:

- tinted panes, RGB about (58, 92, 122), alpha 112 to 122;
- a thin opaque dark frame round every pane;
- a centre post that splits the windscreen in two;
- one small highlight per pane.

The frame, pillars, roof, nose and body stay opaque in the other layers. From outside, the pilot and the seat
back are visible through the glass.

### Render type

`MiniHeliGlassModel` is constructed with `super(root, RenderTypes::entityTranslucentCullItemTarget)`. In 26.2
this is the only entity render type that both blends and culls back faces:

- `RenderTypes` has `entityTranslucent`, which does not cull, and `entityTranslucentCullItemTarget`;
- there is no plain `entityTranslucentCull` in 26.2;
- vanilla uses `entityTranslucentCullItemTarget` for semi-transparent living entities (`LivingEntityRenderer`),
  so it is a normal entity path.

What the type does:

- **Pipeline** `ENTITY_TRANSLUCENT_CULL`: blend function `TRANSLUCENT`, alpha cutout at 0.1, culling on, depth test
  and depth write on (the entity default).
- **`sortOnUpload`**: the quads of one draw are sorted back to front before upload.
- **Output target** `ITEM_ENTITY_TARGET`: the separate item-entity target when Fabulous graphics is on, the main
  target otherwise.

Culling is what the pilot needs: a clear view from inside. From outside, only the near panes are drawn. The
pilot is therefore seen through one pane of tint, and so is the scenery behind the cabin. The non-culling
`entityTranslucent` would instead tint the pilot's whole view, and draw the far panes too.

### How 26.2 draws it, and the one thing the renderer must do

**How the glass is drawn:**

- `SubmitNodeCollection.submitModel` sends every submit whose render type `hasBlending()` to the
  `translucentModels` phase. The order in which `PlaneRenderer` submits does not matter.
- That phase is drawn after the solid phase (the opaque body, metal and rotor layers).
- Inside the phase, `TranslucentFeatureRenderPhase` sorts the submits back to front by the distance from the
  camera to each submit's **pose origin**.
- The glass writes depth.

**The problem is the pilot.** `PlayerModel` uses `RenderTypes::entityTranslucent`, so the rider is a translucent
submit too. If the glass is drawn first, its depth hides the pilot behind it and the cabin looks empty.

- The glass's default pose origin is the model origin, entity (0, 1.5, 0.25). The rider's is about
  (0, 1.5, 0.625).
- So when the camera is anywhere in front of the cabin, the glass would sort first.
- With Fabulous graphics, the glass goes to the item-entity target, which the transparency pass composites by
  depth, and the order does not matter. On Fast and Fancy graphics it does.

**The fix** is to submit the glass with its pose origin at **the point of the glass's box nearest the camera**.
That point is always nearer the camera than any point inside the box, the rider's origin included. So the glass
is always drawn after the pilot. `MiniHeliGlassModel` provides both halves:

- `static Vector3f sortOrigin(Vector3fc cameraInModel, Vector3f dest)` clamps the camera position (model space,
  blocks) into the glass's bounds.
- `void applySortOrigin(Vector3fc origin)` moves the `Glass` part by `-origin`, so a pose translated by
  `+origin` draws the glass in the same place. It has to run in `setupAnim`, because the pose reset happens there,
  at draw time. That is why the origin travels in the render state.

Wiring (not done):

1. Add `public final Vector3f glassSortOrigin = new Vector3f();` to `PlaneRenderState`.
2. In `MiniHeliGlassModel.setupAnim`, replace `applySortOrigin(DEFAULT_SORT_ORIGIN)` with
   `applySortOrigin(state.glassSortOrigin)`.
3. Give `PlaneRenderer` a protected hook, e.g. `submitExtraLayers(state, poseStack, collector)`. Call it inside
   `submit()` with the same pose as the three layers, after them and before `popPose`. Override it in a
   `MiniHeliRenderer`:

```java
@Override
protected void submitExtraLayers(PlaneRenderState state, PoseStack poseStack, SubmitNodeCollector collector) {
    // the pose is camera-relative: the camera is at its origin
    Vector3f camera = new Matrix4f(poseStack.last().pose()).invert().transformPosition(new Vector3f());
    MiniHeliGlassModel.sortOrigin(camera, state.glassSortOrigin);
    poseStack.pushPose();
    poseStack.translate(state.glassSortOrigin.x, state.glassSortOrigin.y, state.glassSortOrigin.z);
    collector.submitModel(glassModel, state, poseStack, glassModel.renderType(MiniHeliGlassModel.TEXTURE),
            state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor, null);
    poseStack.popPose();
}
```

Bake the layer like the others (`MINI_HELI_GLASS_LAYER`, `MiniHeliGlassModel::createBodyLayer`). The glass is
the same for both liveries.

**Panes seen through panes.** The layer is culled, so only the faces turned towards the camera are drawn. For
the two convex boxes, those faces do not overlap each other on screen. Where the windscreen stands in front of the
cabin's front face, that part of the front face is alpha 0. From outside, the camera never looks through two
tinted panes, so the per-draw quad sorting has nothing to get wrong.

**If the sort origin is not wired,** the model still works, with one exception. On Fast and Fancy graphics, the
pilot disappears behind the glass whenever the camera is in front of the cabin. A cruder fallback is to make the
pane texels alpha 0 (frame-only panes). Then the glass is invisible, the frame stays, and ordering cannot matter.

## Animated parts: the rotors

`MiniHeliRotorModel` goes into the renderer's **propeller** slot; pass `mini_heli_metal.png` as
`propellerTexture` too.

| part | pivot, model local | pivot, entity | axis | contents |
|---|---|---|---|---|
| `Rotors/main_rotor` | (0, -34, 4) | (0, 2.125, 0) | model Y (vertical, the mast) | mast 2x14x2 down to the engine top, hub 4x2x4, one 54x1x3 bar = two blades with yellow tips |
| `Rotors/tail_rotor` | (1, -18, 34) | (0.0625, 1.125, -1.875) | model X (lateral) | hub 3x2x2, one 1x2x12 bar = two blades with yellow tips, on the left (+X) side of the fin |

`setupAnim` calls `super.setupAnim(state)` and then sets:

```java
main_rotor.yRot = state.propellerRotation;
tail_rotor.xRot = state.propellerRotation;
```

These are exactly the axes and signs of `HelicopterPropellerModel` (`bone_propeller.yRot`,
`bone_propeller2.xRot`). Nothing else is needed: `PlaneRenderer.extractRenderState` already fills
`propellerRotation`.

- At rotation 0, the main blades lie across the aircraft (along x) and the tail blades along the boom.
- The main rotor axis passes through the entity origin. That is the axis `HelicopterEntity.rotorAxis()`
  describes for the helicopter's thrust.
- The blades are 1 px thick, so they also show as a line in a pure side view. They need no culling setting.

## Standard or medical: the two material layers

The mini helicopter comes in two finishes. Both have the same geometry, seat, rotors, glass, dimensions and
contract.

**Standard: `MiniHeliModel`.** It is textured with the aircraft's material block texture, tiled like every other
plane (`LayerDefinition.create(mesh, 16, 16)`). The tub, nose, chin, door pillars, roof, pod, boom, fins and
stabiliser are material, so the material carries up the cabin sides and round the windows.

**Medical (air ambulance): `MiniHeliMedicalModel`.** It is textured with its own `mini_heli_medical.png`:

- white body, pillars and roof;
- a red cheat line and a yellow-green band round the tub, nose, chin and rear pod;
- on the boom, a red lower half with yellow-green bands;
- a red fin with a white tip;
- a yellow-green ventral fin and yellow-green stabiliser tips;
- an **invented medical mark, a white cross on a green square**, on both sides of the rear pod, on the roof and on
  the belly.

The mark follows the generic first-aid sign. It is **not** the Red Cross or Red Crescent emblem: there is no red
cross on white anywhere. There is no service name or logo either.

**Structure.** A painted livery cannot reuse the tiled layout, because every face needs its own place in the
texture. So the geometry lives once, in `MiniHeliAirframe.create(UvLayout)`, and each material layer only
supplies a table of `texOffs` per named cube:

- `WOOD_UV` in `MiniHeliModel`: small offsets into the tiled 16x16 block texture;
- `SKIN_UV` in `MiniHeliMedicalModel`: packed nets in the 128x64 livery.

The two layers cannot drift apart. A box moved in `MiniHeliAirframe` moves in both, and a cube missing from either
table fails at bake time with "no texOffs for mini helicopter cube ...". This is the pattern of
`AirlinerAirframe`, `AirlinerModel` and `AirlinerSkinModel`. The left and right pillars share one name, and so
one net.

The metal layer (panel, seat, engine, skids), the rotors and the glass are the same for both finishes, so only
the material layer is swapped.

**Choosing in the renderer (not wired).** `PlaneRenderer` draws one body model with `state.materialTexture`.
The entity work should:

1. Bake both layers, e.g. `MINI_HELI_LAYER` (`MiniHeliModel::createBodyLayer`) and `MINI_HELI_MEDICAL_LAYER`
   (`MiniHeliMedicalModel::createBodyLayer`), plus the shared `metal`, `propeller` and `glass` layers.
2. Give the aircraft a **variant id**. Recommended: one entity type and a per-entity `byte variant`
   (0 = standard, 1 = medical).
   - Set it when the helicopter is placed, for example from a second item or from an item component.
   - Save it in NBT and sync it with a `SynchedEntityData` accessor.
   - The physics are identical, so a second entity type would only duplicate registration.
3. Copy it in `PlaneRenderer.extractRenderState` into a new render-state field, e.g.
   `boolean medicalLivery` in `PlaneRenderState`.
4. Draw `medicalLivery ? medicalModel : standardModel` with
   `medicalLivery ? MiniHeliMedicalModel.TEXTURE : state.materialTexture`. The least intrusive way is two
   protected hooks in `PlaneRenderer`, `bodyModel(state)` and `bodyTexture(state)`. They default to today's
   `planeEntityModel` and `state.materialTexture`, and the `MiniHeliRenderer` subclass (which also submits the
   glass) overrides them.

Registering the medical helicopter as a second entity type also works, but it still needs step 4, because
`PlaneRenderer` always passes `state.materialTexture` to the body model. Whatever decides the variant must
come from the render state. `submitModel` only queues the model; `setupAnim` and drawing happen later, and one
model instance is shared by every mini helicopter.

## Textures

- **`MiniHeliModel`** tiles the aircraft's material block texture at 1 texel per pixel.
- **`mini_heli_metal.png`** is 128x64 RGBA, procedurally generated original work. It holds:
  - a black instrument panel with four lights;
  - a dark seat back with a red cushion;
  - a grey engine with dark cooling slots and a rear grille, and a dark exhaust;
  - dark grey skids, legs and toes;
  - a light mast, dark hubs and dark blades with yellow tips.
- **`mini_heli_glass.png`** is 128x32 RGBA, procedurally generated original work: the translucent panes, frames
  and highlights described above, with alpha 0 where a face must not be seen.
- **`mini_heli_medical.png`** is 128x64 RGBA, procedurally generated original work: the livery described above,
  painted by model position so the stripes line up across the tub, nose, chin and pod.

Notes that apply to all of them:

- Unused atlas space is transparent.
- The generator that painted the textures wrote the `texOffs` in `MiniHeliMetalModel`, `MiniHeliRotorModel` and
  `MiniHeliGlassModel`, and the `SKIN_UV` table in `MiniHeliMedicalModel`. It lives outside the repository. Hand
  edits are fine, but a moved `texOffs` needs the texture repainted to match.
- `.mirror()` is used for the right-hand skid, legs and toe; their paint is symmetric.
- No existing texture was reused: the `plane_metal.png` family has no alpha channel and no glass.

## Parts

`MiniHeliModel` and `MiniHeliMedicalModel` > `MiniHeli` (built by `MiniHeliAirframe`):

- `Cabin`:
  - `hull`, the tub, 16 x 12 x 18, up to the door sill;
  - `nose` and `chin`, the stepped nose;
  - `roof`;
  - the four door pillars, `a_pillar` at the front and `b_pillar` at the rear, grown by
    `MiniHeliAirframe.PILLAR_GROW`.
- `RearPod`: `pod`, the engine bay behind the cabin (12 x 9 x 8).
- `Tail`:
  - `boom` (2 x 2 x 26);
  - `fin_lo` and `fin_hi`, a two-step swept fin;
  - `ventral`, which guards the tail rotor;
  - `stab`, which passes through the boom, offset half a pixel so no face is coplanar with the boom's.

`MiniHeliMetalModel` > `Metal`:

- `Cockpit`: the instrument panel `dash` on the sill, and the `seat` back.
- `Engine`: the engine block on the pod, and its exhaust.
- `skid_left`, `skid_right`: the skid tubes.
- `legs_left`, `legs_right`: two legs each, pivoted at the belly edge (±7, -5, 0) and splayed outwards by
  `LEG_SPLAY` (0.45 rad).
- `toe_left`, `toe_right`: the upturned skid toes, bent up by `TOE_BEND` (0.6 rad).

`MiniHeliRotorModel` > `Rotors`: `main_rotor` and `tail_rotor` (see above).

`MiniHeliGlassModel` > `Glass`: `glass_cabin` and `windscreen`.

## Notes for whoever writes the physics

- **Base class.** The mini helicopter flies like `HelicopterEntity`: one rotor disc rigidly attached to the
  fuselage, collective plus cyclic, and a tail rotor for yaw. The simplest route is a subclass of
  `HelicopterEntity` with smaller numbers. In that case, use the `LargeAirframeEntity` rider offsets in *Seat*.
- **Rotor axis.** The mast is at the entity origin, so thrust applied along `rotorAxis()` at the origin
  produces no pitching moment from the geometry. The helicopter's own model has its mast 1.09 blocks behind its
  origin.
- **Size.** Half the helicopter's rotor diameter means a quarter of its disc area. If the handling should feel
  like a lighter, twitchier machine, lower the inertia rather than the thrust. The damage wobble and render
  pivot are shared with the big planes, so check in game that the wobble is not exaggerated at this size.
- **Rotor clearance.**
  - The main rotor tips sweep a circle of radius 1.69 at height 2.13 to 2.19.
  - The tail rotor sweeps down to entity y 0.75 at z -1.5 to -2.25. It is the lowest point behind the skids.
  - A tail strike can only happen with more than about 20° nose-up on the ground.
- **One rider.** `LargeAirframeEntity.canAddPassenger` allows up to four with the seats upgrade. Override it to
  allow exactly one. `positionRider` needs only index 0.
- **Upgrade models.** `UpgradesModels.modelFor`/`textureFor`/`submitSeats` send any entity type that is not the
  plane, the large plane or the cargo plane to the **helicopter** variants. Those are twice this size and would
  float around the mini helicopter. Give it its own branch there, most likely with no upgrade visuals and no
  extra seats.
- **Dismounting.** The skids are only 0.59 either side of the centre line. The default dismount location will
  put the player beside the cabin, which is fine.
- **Not checked in game.** The model has only been rendered outside the game:
  - the real `LayerDefinition`s were baked, and the `ModelPart`s walked under the same `PoseStack` transforms
    that `submit()` uses;
  - `setupAnim` ran on a real `PlaneRenderState`, with the rotors at `propellerRotation` 0 and 0.7;
  - the preview asks each model for its real `RenderType`, and uses the pipeline's cull flag and `hasBlending()`;
  - the glass is drawn with alpha blending after the opaque layers, which is the order the sort origin above
    guarantees on Fast and Fancy graphics.

  The lighting in those renders is an approximation.

## Limitations

- The glass needs the renderer code in *The glass layer*. Without the sort origin, the pilot is hidden behind the
  glass on Fast and Fancy graphics whenever the camera is in front of the cabin.
- From inside, the glass is culled. The pilot sees no tint and no windscreen frame, only the opaque pillars,
  sill and roof.
- The cabin is blocky: a glass box, a windscreen and a roof. Rounder shapes would cost more cubes than the
  "simplest aircraft" brief allows.
- Height and width are not halved (see *Dimensions*); only length and rotor diameter are.
