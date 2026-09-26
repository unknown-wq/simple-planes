# Mini airliner: render model contract

This file is for whoever writes the airliner's entity, physics and registration. It covers the render model
only, which exists and compiles:

| Layer | Class | Texture | Cubes |
|---|---|---|---|
| material (wood) | `client/render/models/AirlinerModel` | `state.materialTexture` (the block texture, tiled 16x16, same as `PlaneModel`) | 40 |
| metal | `client/render/models/AirlinerMetalModel` | `simpleplanes:textures/plane_upgrades/airliner_metal.png` (256x256, new) | 41 |
| fans (propeller slot) | `client/render/models/AirlinerFanModel` | same `airliner_metal.png` | 10 |

That is 91 cubes in all. For comparison: starter plane 60, fighter 56, large plane 157, cargo plane 297.

Nothing is registered: there is no layer location, renderer, entity type or item. `PlaneRenderState`,
`PlaneRenderer`, `PlanesModelLayers` and `SimplePlanesEntities` are untouched.

## Coordinates

### Model space

Model space is in pixels, where 1 px = 1/16 block. It uses the usual `ModelPart` convention: +Y points
down and the nose points to -Z. +X is the aircraft's left side.

- `AirlinerModel` hangs everything off the part `Airliner`, `AirlinerMetalModel` off `Metal` and
  `AirlinerFanModel` off `Fans`. All three are at `PartPose.offset(0, 24, 0)`, so their local frames match.
  In that local frame:
  - the ground contact (the bottom of every tyre) is at **y = 0**, which is absolute y = 24;
  - the belly (keel) is at y = -13, the belly fairing under the wing at y = -11, the cabin floor at y = -16,
    the fuselage top (crown) at y = -41 and the fin tip at y = -76;
  - the nose tip is at z = -94 and the end of the APU cone at z = +82;
  - the middle of the length is at **z = -6**;
  - the wingtips are at x = ±83.7 (the wings have 5° dihedral), the winglet tips at y ≈ -32.
- The wings, stabilisers and winglets are children with a dihedral roll: `wing_left`/`winglet_left` pivot at
  (12, -14, 0) with `zRot = -AirlinerModel.WING_DIHEDRAL` (0.0873), `wing_right`/`winglet_right` at
  (-12, -14, 0) with `+WING_DIHEDRAL`; `stab_left`/`stab_right` pivot at (±6, -25, 0) with `∓STAB_DIHEDRAL`
  (0.1222).
- The fans pivot at local (±32, -10, -24), the nacelle axis, 2 px behind the front of the intake ring.

### Entity space

Entity space is in blocks, with +Y up and the nose at +Z. `PlaneRenderer.submit` maps a model point to the
entity as follows: `translate(0, .375, 0)`, then `scale(-1, -1, 1)`, then `rotY(180)`, then the plane's
quaternion, then the per-type translate `(tx, ty, tz)`, then `translate(0, -1.1, 0)`. With an identity
rotation, an absolute model pixel `p` ends up at:

```
entity = ( p.x/16 ,  1.475 - ty - p.y/16 ,  -tz - p.z/16 )
```

## What to put in `PlaneRenderer.submit`

Suggested per-type translate:

```java
} else if (entityType == SimplePlanesEntities.AIRLINER.get()) {
    poseStack.translate(0.0F, -0.025F, 0.375F);
}
```

This value was used for every reference render. With it the mapping simplifies to (local model coordinates,
i.e. relative to the y = 24 root):

```
entity = ( x/16 ,  -y/16 ,  -(6 + z)/16 )
```

- The tyres touch **y = 0** exactly.
- The airframe is centred on the entity origin along its length: it runs from z = -5.5 at the APU cone to
  z = +5.5 at the nose tip.
- The renderer rotates the plane about entity point **(0, 0.375, 0)**, because the quaternion comes before
  the per-type translate. That is model local (0, -6, -6): 7 px under the belly, just behind the middle of
  the wheelbase (nose gear at entity z = +4.25, main gear at z = -1.375).

## Dimensions (entity space, suggested translate)

| | blocks |
|---|---|
| length, APU cone to nose tip | **11.0** |
| wingspan, winglets included | 10.47 |
| height to the fin tip | 4.75 |
| belly above the ground | 0.81 (0.69 under the wing fairing) |
| top of the fuselage (crown) | 2.56 |
| fuselage width x height | 1.625 x 1.75 |
| engine fan centre | (±2.0, 0.625, 1.125); nacelles span x ±1.56 to ±2.44, 0.19 above the ground |
| wheelbase / main-gear track | 5.625 / 2.0 (twin tyres at x ±0.75 to ±1.25) |

For comparison (same dump, propeller included): starter plane 6.2 long, 6.75 span, 2.4 tall; fighter 7.4
long, 5.6 span, 2.45 tall; large plane 8.4 long, 7.6 span, 2.8 tall. The cargo plane is bigger than all of
these, **17.8 long**, 19.0 span, 5.1 tall; the airliner is not longer than it (see Limitations).

## Hitbox

`sized(3.0F, 2.6F)`. Width 3.0 is the mod's convention for its big airframes (large plane and cargo plane
both use 3.0 x 2.3). It covers the fuselage (1.625 wide) and both main gear legs over the middle 3 blocks of the
length; the wings, engines, nose and tail stick out, as they do on the other planes. Height 2.6 reaches the
crown (2.56); the fin, up to 4.75, is left out, as the starter plane's tail is.

Suggested `shadowRadius` for the `PlaneRenderer` constructor: `1.0F` (same as large and cargo).

## Seats (`positionRider`)

Six seats in one column on the centre line, 18 px (1.125 blocks) apart, the same layout idea as
`LargePlaneEntity` (one column, about a block apart). The feet point of each, i.e. what `transformPos(...)`
should return before it goes into `moveFunction.accept`, is:

| passenger index | role | feet point `(x, y, z)` | model local z | eye (entity y) |
|---|---|---|---|---|
| 0 | pilot | `(0, 0.375, 4.25)` | -74 | 1.995 |
| 1 | passenger | `(0, 0.375, 2.34375)` | -43.5 | 1.995 |
| 2 | passenger | `(0, 0.375, 1.21875)` | -25.5 | 1.995 |
| 3 | passenger | `(0, 0.375, 0.09375)` | -7.5 | 1.995 |
| 4 | passenger | `(0, 0.375, -1.03125)` | 10.5 | 1.995 |
| 5 | passenger | `(0, 0.375, -2.15625)` | 28.5 | 1.995 |

```java
Vector3f pos = transformPos(new Vector3f(0.0f, 0.375f, SEAT_Z[index]));
```

The y value is the sum `getPassengersRidingOffset() + getEntityYOffset(passenger)`. If the entity extends
`LargeAirframeEntity`, whose `getEntityYOffset` returns -0.4 for a player, return **0.775f** from
`getPassengersRidingOffset()`; with a plain `PlaneEntity` (no extra offset) return **0.375f**.

The numbers come from the seated player model at 15/16 scale (hips 11.25 px above the feet point, head top
30 px, eye 25.92 px, shoulders ±7.5 px):

- Hips at local y -17.25; the legs lie flat on the cabin floor (local y -16) and reach 11 px forward. The
  pilot's legs end inside the nose, under the instrument panel.
- The head spans local y -28.5 to -36, under the fuselage top (-38 for the body, -41 at the crown), so no rider
  is visible from outside. Arms (±7.5 px) are well inside the ±13 px walls.
- Every passenger's head is centred on a cabin window: the windows are 3 px wide, every 6 px along the belt,
  at local z in [-57 + 6k, -54 + 6k), and seats 1 to 5 sit on k = 2, 5, 8, 11, 14. The eye (local y -31.92)
  is in the window row (local y -34 to -31) on both sides. The pilot's head is behind the cockpit side
  windows.
- Pilot's eye: inside the lower windscreen box `glass_lo` (entity y 1.875 to 2.066, z 4.0 to 4.69).

### Seeing out of a closed cabin

The fuselage is closed, and the view out works the way the fighter's canopy does. All three layers use the
default `EntityModel` render type, which in 26.2 is `RenderTypes::entityCutout` and culls back faces. Every
eye is inside the main fuselage box `body` (and the pilot's also inside `glass_lo`), so every face of those
boxes is a back face from the rider's point of view, and the riders see the world through the walls. The
faces that still point into the cabin are either wanted or transparent:

- wanted: the ceiling (underside of the upper shoulder), the floor, the rear bulkhead (front face of the tail
  cone) and the instrument panel plus the nose ahead of the pilot, whose tops sit 2.9 px and 1.9 px below the
  pilot's eye so the view over the nose stays clear;
- transparent (alpha 0 in `airliner_metal.png`): every face of the window belts, doors and tail emblem except
  the outer one, the rear faces of both windscreen boxes (the cockpit is open to the cabin) and the bottom of
  `glass_hi`, which is the one windscreen face that points at the pilot if the seat ends up higher.

The seat can rise to about y 0.63 (eye 2.25) before the top face of `glass_hi` blocks the pilot's view. Do not
change these models to a no-cull render type, or the walls will close the view.

## Animated parts: the fans

`AirlinerFanModel` goes into the renderer's **propeller** slot; pass `airliner_metal.png` as
`propellerTexture` too.

Parts, children of `Fans`: `fan_left` and `fan_right`. Each holds a spinner cube (dark, with a white spiral
mark so the spin is visible) and four crossed blade bars, `blades_0` to `blades_3`, at 45° steps, each
twisted 0.5 rad about its own axis: eight blades per fan.

`setupAnim` calls `super.setupAnim(state)` and then sets `fanLeft.zRot = fanRight.zRot =
state.propellerRotation`, exactly as `PropellerModel` does for `IronPropeller`. Nothing else is needed:
`PlaneRenderer.extractRenderState` already fills `propellerRotation`. The review renders
`airliner-fans-a.png` and `airliner-fans-b.png` (delivered with this work, not committed) show rotation 0
and 0.4 rad. The blades are symmetric under a 45° turn, so the spinner mark is what makes slow rotation
readable.

The fans sit 2 px inside open intake rings (four `lip` cubes); the cowl's front face behind them is painted
as the dark fan case.

## Textures

- The material layer tiles the plane's material block texture at 1 texel per pixel, with
  `LayerDefinition.create(mesh, 16, 16)`, exactly as `PlaneModel` does. The fuselage, nose, tail cone, wings,
  belly fairing, stabilisers and fin are material.
- `airliner_metal.png` is new: 256x256 RGBA, a procedurally generated original texture made for this model
  (its generator lives outside the repository; the Java files are the source of truth, so edit them
  directly). It holds the UV net of every metal and fan cube:
  - a black cockpit "mask" band with tinted windscreen and side panes;
  - the passenger window belt: white band, dark windows, blue cheat line;
  - door outlines (the inside of the outline is transparent, so the material shows through), with a window
    and the cheat line;
  - a blue-and-white tail emblem (transparent outside the disc);
  - blue nacelle cowls with a silver band, silver intake rings, dark fan case, exhaust and plug;
  - blue winglets with a white leading edge;
  - light-grey pylons, struts and APU cone, black tyres with hubs;
  - the instrument panel with coloured lights on the face towards the pilot;
  - the grey fan blades and the spinner.
- Window belts, doors and the emblem are flush plates just outside the wooden skin
  (`CubeDeformation` 0.05; 0.04 for the doors so they never overlap a belt end on the same plane).
- `.mirror()` is used for every right-hand copy. It swaps the ±X images, so the right-hand plates show the
  same outer image as the left ones, still with the nose end first; the windows line up on both sides.
- The existing `plane_metal.png` family could not be reused: no alpha channel and no glass, livery or tyre
  regions.

## Parts

`AirlinerModel` > `Airliner`:

- `Fuselage`: `body` (26 x 22 px, solid), upper and lower shoulders (24 wide), crown and keel (18 wide),
  giving a rounded cross-section; the belly fairing under the wing root.
- `Nose`: four steps down and forward from the windscreen to the nose tip.
- `TailCone`: three steps; the top stays level while the belly sweeps up to the APU.
- `wing_left`, `wing_right`: six chord steps each, 3 px thick inboard and 2 px outboard, about 27° of sweep.
- `stab_left`, `stab_right`: four swept steps each.
- `Fin`: dorsal fillet plus six swept steps.

`AirlinerMetalModel` > `Metal`:

- `Cockpit`: `glass_lo` and `glass_hi` (two-tier windscreen), the instrument panel.
- `Cabin`: both window belts, front and rear doors on both sides.
- `engine_left`, `engine_right`: intake ring (4), cowl, exhaust, plug, pylon.
- `winglet_left`, `winglet_right`: two cubes each, on the wing pivots.
- `Tail`: the emblem on both sides of the fin, the APU cone.
- `Gear`: nose strut and twin nose tyres; two main struts, each with two tyres.

## Things the entity work will run into

- **Upgrade models.** `UpgradesModels.modelFor`/`textureFor`/`submitSeats` send any entity type that is not
  the plane, the large plane or the cargo plane to the **helicopter** variants. An airliner with upgrades
  would draw helicopter parts in helicopter positions; it needs its own branch there first.
- **Passenger count.** `PlaneEntity.canAddPassenger` allows one rider, or three with the seats upgrade. Six
  seats need an override.
- **Rotation pivot and length.** The plane rotates about a point 7 px under the belly at mid-length. At 11
  blocks, a pitch of 15° lifts or drops the nose and tail by about 1.4 blocks, so the tail can dip into
  the ground on take-off rotation; the physics should either limit pitch on the ground or accept it.
- **Not checked in game.** The model has only been rendered outside the game, by baking the real
  `LayerDefinition`s and walking the `ModelPart`s under the same `PoseStack` transforms that `submit()`
  uses, with `setupAnim` run on a real `PlaneRenderState` for the fans. The lighting in those renders is an
  approximation.

## Limitations

- **Not longer than the cargo plane.** The airliner is 11.0 blocks long, the upper end of the requested
  9–11 and clearly longer than the starter plane (6.2), fighter (7.4) and large plane (8.4). The cargo
  plane is 17.8 long, so beating it would need a model about 1.7 times this size, which would no longer be
  a "mini" airliner. The review render `airliner-scale-cargo.png` (not committed) shows the two together.
- The engines are square in section (blocky style); the fans are opaque, so the intake reads as a disc of
  blades in front of a dark case.
- Riders see the outside through the fuselage walls rather than through the windows, like the fighter's
  pilot through the canopy; the window belts are drawn only from outside.
