# Mini helicopter: render model contract

This file is for whoever writes the mini helicopter's entity, physics and registration. It covers the render
model only, which exists and compiles. The mini helicopter is meant to be the smallest and simplest aircraft
in the mod: a one-seat bubble helicopter about half the size of the existing helicopter.

| Layer | Class | Texture | Render type | Cubes |
|---|---|---|---|---|
| material, standard version | `client/render/models/MiniHeliModel` | `state.materialTexture` (the block texture, tiled 16x16, same as `PlaneModel`) | `entityCutout` (default) | 8 |
| material, medical version | `client/render/models/MiniHeliMedicalModel` | `simpleplanes:textures/plane_upgrades/mini_heli_medical.png` (128x64, `MiniHeliMedicalModel.TEXTURE`) | `entityCutout` (default) | 8 |
| metal | `client/render/models/MiniHeliMetalModel` | `simpleplanes:textures/plane_upgrades/mini_heli_metal.png` (128x64) | **`entityCutoutCull`** | 14 |
| rotors (propeller slot) | `client/render/models/MiniHeliRotorModel` | same `mini_heli_metal.png` | `entityCutout` (default) | 5 |

A material layer (either one), the metal layer and the rotors make **27 cubes**. The existing helicopter has
55 (`HelicopterModel` 19, `HelicopterMetalModel` 26, `HelicopterPropellerModel` 10). For comparison: drone
43, fighter 56, starter plane 60.

The two material layers are built by one shared class, `MiniHeliAirframe` (package-private); see *Standard
or medical*.

Nothing is registered: there is no layer location, renderer, entity type or item. `PlaneRenderState`,
`PlaneRenderer`, `PlanesModelLayers` and `SimplePlanesEntities` are untouched.

## Coordinates

### Model space

Model space is in pixels, where 1 px = 1/16 block. It uses the usual `ModelPart` convention: +Y points down
and the nose points to -Z. +X is the aircraft's left side.

All layers hang everything off one top-level part at `PartPose.offset(0, 24, 0)`: `MiniHeli` (both material
layers), `Metal` and `Rotors`. Their local frames match, and in that local frame:

- the ground contact (the bottom of both skids) is at **y = 0**;
- the cabin tub runs from y = -12 to -5 (belly 5 px above the ground), z = -16 to +2, x = ±8;
- the bubble: main glass box y -27 to -12, cap up to **y = -31**, front bulge down to the nose at z = -19;
- the nose tip is at **z = -19** and the fin's trailing edge at **z = +37**;
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
  be on the tail boom (local z = 9), which would make the cabin swing round a point behind the engine.
- The renderer rotates the model about entity point **(0, 0.375, 0)**, because the quaternion comes before the
  per-type translate. That is local (0, -6, 4): the bottom of the engine pod, straight under the mast, about
  where the centre of mass of a pilot plus engine would be.
- The damage wobble (`timeSinceHit`) rolls about the same point.

Do not leave the mini helicopter on the helicopter branch of `submit` (the `else` default `(0, 0, 0.9)`): that
sinks the skids 0.025 into the ground and shifts the whole model 1.15 blocks back, off the rotor axis.

## Dimensions (entity space, suggested translate)

| | mini helicopter | existing helicopter | ratio |
|---|---|---|---|
| length, nose to tail (fin / boom end) | **3.5** (z +1.44 to -2.06) | 7.0 (z +1.68 to -5.34) | 0.50 |
| length including the tail rotor disc | 3.69 | 7.70 | 0.48 |
| main rotor diameter | **3.375** (2 blades, 54 px) | 6.875 (4 blades, 110 px) | 0.49 |
| overall height (rotor hub top / fin top) | 2.28 | 3.07 | 0.74 |
| main rotor blade plane | 2.13 to 2.19 | 2.54 to 2.60 | |
| top of the bubble | 1.94 | | |
| cabin width (tub and bubble) | 1.0 | 1.125 | 0.89 |
| skid track (outer faces) | 1.19 (x ±0.59) | 2.0 | 0.6 |
| skid length (toe included) | about 1.7 | 3.3 | 0.5 |
| belly above the ground | 0.31 | 0.48 | |
| tail rotor diameter | 0.75 | 1.75 | 0.43 |

The existing helicopter was measured from a dump of `HelicopterModel`, `HelicopterMetalModel` and
`HelicopterPropellerModel` with its real translate `(0, 0, 0.9)`.

Length and rotor diameter are half the helicopter's, as asked. Height and width cannot be halved: a seated
player is 1.3 blocks tall and 0.94 wide at the arms and has to fit, so the bubble is almost as wide as the
helicopter's cabin and the rotor is 0.74 of its height. Review renders showing the scale against the helicopter, the starter plane and a
0.6 x 1.8 player box on a 1-block grid were delivered with this work (not committed).

## Hitbox

`sized(1.5F, 1.95F)`. Width 1.5 covers the cabin (1.0), the skids (x ±0.59) and the engine pod along the
middle 1.5 blocks of the length; the nose (+1.44) sticks out 0.69 in front and the tail boom 1.3 behind, as the
helicopter's tail sticks out of its 2.5 x 2.2 box. Height 1.95 reaches the top of the bubble (1.94); the mast and
the rotor are left out, as the helicopter leaves out its rotor.

Suggested `shadowRadius` for the `PlaneRenderer` constructor: `0.5F` (helicopter 0.6).

## Seat (`positionRider`)

**One seat, for the pilot.** The feet point, i.e. what `transformPos(...)` should return before it goes into
`moveFunction.accept`, is:

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

The numbers come from the seated player model at 15/16 scale (hips 11.25 px above the feet point, head top
30 px, eye 25.92 px, shoulders and arms ±7.5 px, legs 11.25 px forward at hip height):

- The hips are at local y -11.25, just above the cabin floor (the tub top, y -12). The legs point forward
  along the floor into the nose, under the instrument panel, and end at about z -17.
- The torso and head are inside the bubble: the head spans local y -22.5 to -30 and z -9.75 to -2.25, inside the
  cap (y -31 to -27, z -14 to 0), with 1 px to spare above. The arms (±7.5 px) are inside the ±8 px bubble.
- The eye is at local y -25.92, **inside the main glass box** (y -27 to -12), 1.1 px under its top.
- The mast is 3.25 px behind the back of the head; the rotor blades are 4 px above the head top.
- The seat can move up by about 1 px (0.06) before the head top meets the cap top, and down by several
  pixels; the eye stays inside a glass box for any seat between y -0.3 and +0.06.

The bubble glass is opaque tinted glass (the style of the fighter's canopy), so from outside the pilot is not
visible; the renders with a stand-in rider show that the rider fits (x-ray cuts through the bubble).

**Pivot mismatch (all planes share it).** `transformPos` rotates about the entity origin, while the renderer
rotates the model about entity (0, 0.375, 0). When the helicopter pitches or rolls, the seat therefore drifts
by up to `0.375 * sin(angle)` against the model. For the mini helicopter, where the pilot sits in a tight
bubble, rotating about the render pivot keeps the rider exactly in place:

```java
Vector3f pos = transformPos(new Vector3f(0.0f, seatY - 0.375f, 0.625f)).add(0.0f, 0.375f, 0.0f);
```

### Seeing out of the bubble: the render types

In 26.2 the default render type **does not cull**: `EntityModel(ModelPart)` uses `RenderTypes::entityCutout`,
whose pipeline is built with `withCull(false)`. The culling variant is `RenderTypes.entityCutoutCull`.

- `MiniHeliMetalModel` holds the bubble, and the pilot's eye is inside `glass_main`. It is constructed with
  `super(root, RenderTypes::entityCutoutCull)`, so every face of the box around the eye is a back face and is
  not drawn: the pilot sees the world through the glass.
- The glass faces that still point at the eye from inside are **transparent** in `mini_heli_metal.png`: the rear
  face and bottom of the front bulge (`glass_front`), the bottom of the cap (`glass_cap`), the bottom of
  `glass_main` and the part of `glass_main`'s top that lies under the cap. The last one keeps the view clear
  even if the eye ends up a little higher, inside the cap (checked with the eye 1.3 px higher).
- The only glass face the pilot sees is the top of the front bulge, 0.9 px under the eye: a sliver about one
  degree tall just below the horizon, like a canopy bow. Below it the pilot sees the instrument panel (four
  coloured lights on the face towards the pilot) and the tub edges.
- `MiniHeliModel`, `MiniHeliMedicalModel` and `MiniHeliRotorModel` keep the default: no eye is inside any of
  their cubes, and the blades are seen from both sides. Keep the metal layer on `entityCutoutCull`; on
  `entityCutout` the pilot would see only the inside of the tinted bubble.

## Animated parts: the rotors

`MiniHeliRotorModel` goes into the renderer's **propeller** slot; pass `mini_heli_metal.png` as
`propellerTexture` too.

| part | pivot, model local | pivot, entity | axis | contents |
|---|---|---|---|---|
| `Rotors/main_rotor` | (0, -34, 4) | (0, 2.125, 0) | model Y (vertical, the mast) | mast 2x14x2 down to the engine top, hub 4x2x4, one 54x1x3 bar = two blades with yellow tips |
| `Rotors/tail_rotor` | (1, -18, 34) | (0.0625, 1.125, -1.875) | model X (lateral) | hub 3x2x2, one 1x2x12 bar = two blades with yellow tips, on the left (+X) side of the fin |

`setupAnim` calls `super.setupAnim(state)` and then sets

```java
main_rotor.yRot = state.propellerRotation;
tail_rotor.xRot = state.propellerRotation;
```

which are exactly the axes and signs of `HelicopterPropellerModel` (`bone_propeller.yRot`,
`bone_propeller2.xRot`). Nothing else is needed: `PlaneRenderer.extractRenderState` already fills
`propellerRotation`. At rotation 0 the main blades lie across the aircraft (along x) and the tail blades along
the boom. The main rotor axis passes through the entity origin, which is the axis
`HelicopterEntity.rotorAxis()` describes for the helicopter's thrust.

The blades are 1 px thick, so they also show as a line in a pure side view; they need no culling setting.

## Standard or medical: the two material layers

The mini helicopter comes in two finishes with the same geometry, seat, rotors, dimensions and contract:

- **standard**: `MiniHeliModel`, textured with the aircraft's material block texture, tiled like every other
  plane (`LayerDefinition.create(mesh, 16, 16)`);
- **medical (air ambulance)**: `MiniHeliMedicalModel`, textured with its own `mini_heli_medical.png`: white,
  with a red cheat line and a yellow-green band round the tub and the rear pod, a red lower half on the boom
  with yellow-green bands, a red fin with a white tip, a yellow-green ventral fin and stabiliser tips, and an
  **invented medical mark, a white cross on a green square**, on both sides of the rear pod and on the belly.
  The mark follows the generic first-aid sign; it is **not** the Red Cross or Red Crescent emblem (no red cross
  on white anywhere), and there is no service name or logo.

**Structure.** A painted livery cannot reuse the tiled layout, because every face needs its own place in the
texture. So the geometry lives once, in `MiniHeliAirframe.create(UvLayout)`, and each material layer only
supplies a table of `texOffs` per named cube: `WOOD_UV` (small offsets into the tiled 16x16 block texture) in
`MiniHeliModel`, `SKIN_UV` (packed nets in the 128x64 livery) in `MiniHeliMedicalModel`. The two layers cannot
drift apart: a box moved in `MiniHeliAirframe` moves in both, and a cube missing from either table fails at
bake time with "no texOffs for mini helicopter cube ...". This is the pattern of `AirlinerAirframe`,
`AirlinerModel` and `AirlinerSkinModel`.

The metal layer (bubble, engine, skids) and the rotors are the same for both finishes, so only the material
layer is swapped. Colouring the metal parts differently would need a second metal texture as well; it was not
needed for a readable air-ambulance look.

**Choosing in the renderer (not wired).** `PlaneRenderer` draws one body model with `state.materialTexture`.
The entity work should:

1. bake both layers, e.g. `MINI_HELI_LAYER` (`MiniHeliModel::createBodyLayer`) and `MINI_HELI_MEDICAL_LAYER`
   (`MiniHeliMedicalModel::createBodyLayer`), plus the shared `metal` and `propeller` layers;
2. give the aircraft a **variant id**. Recommended: one entity type and a per-entity `byte variant`
   (0 = standard, 1 = medical), set when the helicopter is placed (for example from a second item, or an item
   component), saved in NBT and synced with a `SynchedEntityData` accessor. The physics are identical, so a
   second entity type would only duplicate registration;
3. copy it in `PlaneRenderer.extractRenderState` into a new render-state field, e.g.
   `boolean medicalLivery` in `PlaneRenderState`;
4. draw `medicalLivery ? medicalModel : standardModel` with
   `medicalLivery ? MiniHeliMedicalModel.TEXTURE : state.materialTexture`. The least intrusive way is two
   protected hooks in `PlaneRenderer`, `bodyModel(state)` and `bodyTexture(state)`, which default to today's
   `planeEntityModel` and `state.materialTexture`; a `MiniHeliRenderer` subclass overrides them.

Registering the medical helicopter as a second entity type also works, but it still needs step 4, because
`PlaneRenderer` always passes `state.materialTexture` to the body model. Whatever decides the variant, it must
come from the render state: `submitModel` only queues the model and `setupAnim`/drawing happen later, with one
model instance shared by every mini helicopter.

## Textures

- `MiniHeliModel` tiles the aircraft's material block texture at 1 texel per pixel. The tub, nose, rear pod,
  tail boom, fins and stabiliser are material.
- `mini_heli_metal.png` is new: 128x64 RGBA, procedurally generated original work. It holds the net of every
  metal and rotor cube: pale tinted glass with a lighter top, a darker sill line and one reflection streak per
  pane (transparent where described above); a black instrument panel with four lights; a grey engine with dark
  cooling slots and a rear grille; a dark exhaust; dark grey skids, legs and toes; a light mast, dark hubs and
  dark blades with yellow tips.
- `mini_heli_medical.png` is new: 128x64 RGBA, procedurally generated original work, the livery described
  above, painted by model position so the stripes line up across the tub, nose and pod.
- Unused atlas space is transparent. The `texOffs` in `MiniHeliMetalModel` and `MiniHeliRotorModel` and the
  `SKIN_UV` table in `MiniHeliMedicalModel` were written by the generator that painted the textures (it lives
  outside the repository). Hand edits are fine, but a moved `texOffs` needs the texture repainted to match.
- `.mirror()` is used for the right-hand skid, legs and toe; their paint is symmetric.
- No existing texture was reused: the `plane_metal.png` family has no alpha channel and no glass.

## Parts

`MiniHeliModel` and `MiniHeliMedicalModel` > `MiniHeli` (built by `MiniHeliAirframe`):

- `Cabin`: `hull` (the tub under the bubble, 16 x 7 x 18) and `nose` (under the front bulge).
- `RearPod`: `pod`, the engine bay behind the cabin (12 x 9 x 8).
- `Tail`: `boom` (2 x 2 x 26), `fin_lo` and `fin_hi` (a two-step swept fin), `ventral` (guards the tail rotor),
  `stab` (passes through the boom, offset half a pixel so no face is coplanar with the boom's).

`MiniHeliMetalModel` > `Metal`:

- `Canopy`: `glass_main`, `glass_cap`, `glass_front`, and the instrument panel `dash`.
- `Engine`: the engine block on the pod and its exhaust.
- `skid_left`, `skid_right`: the skid tubes.
- `legs_left`, `legs_right`: two legs each, pivoted at the belly edge (±7, -5, 0) and splayed outwards by
  `LEG_SPLAY` (0.45 rad).
- `toe_left`, `toe_right`: the upturned skid toes, bent up by `TOE_BEND` (0.6 rad).

`MiniHeliRotorModel` > `Rotors`: `main_rotor` and `tail_rotor` (see above).

## Notes for whoever writes the physics

- **Base class.** The mini helicopter flies like `HelicopterEntity`: one rotor disc rigidly attached to the
  fuselage, collective plus cyclic, a tail rotor for yaw. The simplest route is a subclass of
  `HelicopterEntity` with smaller numbers; then keep the rider offsets in *Seat* (`LargeAirframeEntity` variant).
- **Rotor axis.** The mast is at the entity origin, so thrust applied along `rotorAxis()` at the origin
  produces no pitching moment from the geometry; the helicopter's own model has its mast 1.09 blocks behind its
  origin.
- **Size.** Half the helicopter's rotor diameter means a quarter of its disc area. If the handling should feel
  like a lighter, twitchier machine, lower the inertia rather than the thrust; the damage wobble and render
  pivot are shared with the big planes, so check in game that the wobble is not exaggerated at this size.
- **Rotor clearance.** The main rotor tips sweep a circle of radius 1.69 at height 2.13 to 2.19; the tail rotor
  sweeps down to entity y 0.75 at z -1.5 to -2.25 and is the lowest point behind the skids. A tail strike can
  only happen with more than about 20° nose-up on the ground.
- **One rider.** `LargeAirframeEntity.canAddPassenger` allows up to four with the seats upgrade; override it to
  allow exactly one. `positionRider` needs only index 0.
- **Upgrade models.** `UpgradesModels.modelFor`/`textureFor`/`submitSeats` send any entity type that is not the
  plane, the large plane or the cargo plane to the **helicopter** variants, which are twice this size and would
  float around the mini helicopter. Give it its own branch there, most likely with no upgrade visuals and no
  extra seats.
- **Dismounting.** The skids are only 0.59 either side of the centre line; the default dismount location will
  put the player beside the bubble, which is fine.
- **Not checked in game.** The model has only been rendered outside the game, by baking the real
  `LayerDefinition`s and walking the `ModelPart`s under the same `PoseStack` transforms that `submit()` uses,
  with `setupAnim` run on a real `PlaneRenderState` (rotors at `propellerRotation` 0 and 0.7). The preview asks
  each model for its real `RenderType` and culls only the layers whose type is `entity_cutout_cull`. The
  lighting in those renders is an approximation; there is no emissive or translucent glass.

## Limitations

- The glass is opaque tinted glass, not translucent: the pilot is not visible from outside. A see-through
  bubble would need a translucent render type or cut-out panes, which read as a cage at this size.
- The bubble is blocky: a main box, a cap and a front bulge. Rounder shapes would cost more cubes than the
  "simplest aircraft" brief allows.
- Height and width are not halved (see *Dimensions*); only length and rotor diameter are.
