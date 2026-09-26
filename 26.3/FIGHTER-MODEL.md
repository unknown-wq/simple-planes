# Fighter jet: render model contract

This file is the fighter's render model contract. It was written against 26.2 before the entity existed; on 26.3
the fighter is registered and drawn by `client/render/FighterRenderer`:

| Layer | Class | Texture | Render type | Cubes |
|---|---|---|---|---|
| material (wood) | `client/render/models/FighterModel` | `state.materialTexture` (the block texture, tiled 16x16, same as `PlaneModel`) | `entityCutout` (default) | 31 |
| metal | `client/render/models/FighterMetalModel` | `simpleplanes:textures/plane_upgrades/fighter_metal.png` (128x128) | **`entityCutoutCull`** | 25 |
| exhaust (propeller slot) | `client/render/models/FighterExhaustModel` | same `fighter_metal.png` | `entityCutout` (default) | 7 (5 at idle; the 2 flame cubes are hidden) |
| **glass (a fourth layer)** | `client/render/models/FighterGlassModel` | `simpleplanes:textures/plane_upgrades/fighter_glass.png` (128x64, `FighterGlassModel.TEXTURE`) | **`entityTranslucentCull`** | 4 |

That is 67 cubes in all. The starter plane has 60 (41 + 15 + 4).

Layer locations in `PlanesModelLayers`: `FIGHTER_LAYER`, `FIGHTER_METAL_LAYER`, `FIGHTER_EXHAUST_LAYER` and
`FIGHTER_GLASS_LAYER` (`simpleplanes:fighter#glass`). The glass is the same for every material and for the metal
variant. It follows the mini helicopter's glass (MINI-HELI-MODEL.md, *The glass layer*) exactly, with one
addition, the sort bias; see *The glass layer* below.

## Coordinates

### Model space

Model space is in pixels, where 1 px = 1/16 block. It uses the usual `ModelPart` convention: +Y points
down and the nose points to -Z.

- `FighterModel` hangs everything off the part `Fighter`, and `FighterMetalModel` hangs everything off
  `Metal`. Both parts are at `PartPose.offset(0, 24, 0)`. Their local frames match, and in that local frame:
  - the ground contact (the bottom of every wheel) is at **y = 0**, which is absolute y = 24;
  - the belly is at y = -10, the top of the fuselage (the canopy sill) at y = -22, the top of the canopy at
    y = -33 and the fin tips at about y = -39;
  - the nose tip is at z = -60, the tip of the pitot probe at z = -66 and the nozzle exit at z = +52;
  - the middle of the length is at **z = -4**;
  - the wingtips are at x = ±43, and the missiles on them reach x = ±45.
- `FighterExhaustModel` has one top-level part, `Nozzle`, at `PartPose.offset(0, 8, 44)` in absolute
  pixels. That is local (0, -16, 44), the centre of the rear end of the fuselage.

### Entity space

Entity space is in blocks, with +Y up and the nose at +Z. +X is the aircraft's left side.

`PlaneRenderer.submit` maps a model point to the entity as follows:
`translate(0, .375, 0)`, then `scale(-1, -1, 1)`, then `rotY(180)`, then the plane's quaternion, then the
per-type translate `(tx, ty, tz)`, then `translate(0, -1.1, 0)`. With an identity rotation, an absolute
model pixel `p` ends up at:

```
entity = ( p.x/16 ,  1.475 - ty - p.y/16 ,  -tz - p.z/16 )
```

## What to put in `PlaneRenderer.submit`

Suggested per-type translate:

```java
} else if (entityType == SimplePlanesEntities.FIGHTER.get()) {
    poseStack.translate(0.0F, -0.025F, 0.25F);
}
```

This value was used for every reference render. With it the mapping above simplifies to:

```
entity = ( p.x/16 ,  (24 - p.y)/16 ,  -(4 + p.z)/16 )
```

- The wheels touch **y = 0** exactly. (With `0.0F` instead of `-0.025F` they sink 0.4 px into the ground,
  about as much as the starter plane's skis do.)
- The airframe is centred on the entity origin along its length: it runs from z = -3.5 at the nozzle exit to
  z = +3.5 at the nose tip, and to +3.875 including the pitot probe.
- The renderer rotates the plane about entity point **(0, 0.375, 0)**, because the quaternion comes before
  the per-type translate. That point is model local (0, -6, -4): 4 px below the belly, halfway along the
  length, between the nose gear and the main gear. The other planes have the same pivot.

## Dimensions (entity space, suggested translate)

| | blocks |
|---|---|
| length, nozzle to nose tip | 7.0 (7.375 with the pitot probe) |
| wingspan, including the tip missiles | 5.625 |
| height to the fin tips | 2.45 |
| belly above the ground | 0.625 |
| top of the fuselage (canopy sill) | 1.375 |
| top of the canopy | 2.06 |

For comparison, the starter plane is 5.85 long, has a 6.75 span and is 2.4 tall. The fighter is longer and
flatter: its fuselage is 16 px wide and 12 px tall, against the starter plane's 18 x 17.

## Hitbox

`sized(3.0F, 2.0F)`. Width 3.0 is the same as the large plane. It covers the middle of the length, from the
cockpit to the wing roots; the wings and tail stick out, as they do on the starter plane (whose hitbox is
2.5 wide for a 6.75 span). Height 2.0 reaches the top of the canopy. The fin tips, at 2.45, are left out,
as the starter plane's tail is.

Suggested `shadowRadius` for the `PlaneRenderer` constructor: `1.0F`.

## Seat (`positionRider`)

There is one seat. Passenger 0's feet point, which is what `transformPos(...)` should return before it goes
into `moveFunction.accept`, is:

```java
Vector3f pos = transformPos(new Vector3f(0.0f, 0.0625f, 0.125f));
```

Return `0.0625f` from `getPassengersRidingOffset()` and use `z = 0.125f`. The numbers were derived from the
player model, which `PlayerRenderer` scales by 15/16:

- The hips are 11.25 px above the feet point, at local y = -12.25. The legs lie flat and forward on the
  cockpit floor, whose top is at local y = -11, and reach local z ≈ -17. The cockpit opening runs from
  local z -24 to -2.
- The head spans local z -10 to -2 and y -23 to -31. It is under the two top canopy tiers, 2 px clear of
  the canopy top.
- The eye is at feet + 1.62 = entity y **1.6825**, which is inside the lowest canopy tier (`canopy_main`,
  entity y 1.375 to 1.6875).

**Measured in game (26.3).** With the feet at the value above (F3 confirms them), the first-person eye sits
about 0.08 block (1.3 px) higher relative to the model than the 1.6825 derived here: just above the top of
`canopy_main`, inside `canopy_mid`. The glass texture is cut so that the view is clean from either tier (see
*The glass layer*). The cause of the offset has not been tracked down.

**What the pilot sees.** The canopy is see-through glass, so the pilot and the seat are visible from outside.
From the seat the pilot looks out through clear glass: the glass layer culls back faces, and every face of the
tier that holds the eye is a back face. The faces of the other tiers that point at the eye are alpha 0 (see
*The glass layer*). What is left in view is the opaque frame (sill rails and windscreen bow), the instrument
panel and coaming ahead, and a faint tint over the windscreen foot.

The default `EntityModel(ModelPart)` render type is `RenderTypes::entityCutout`, which does **not** cull
(`pipeline/entity_cutout` is built with `withCull(false)`). `FighterMetalModel` is constructed with
**`RenderTypes::entityCutoutCull`**: its frame cubes stand right round the pilot's head, and on a no-cull type
their inner faces would be drawn. The material and exhaust layers keep the default: the eye is inside none of
their boxes, and the open cockpit's walls are meant to be seen from inside anyway. No cube in any fighter layer
has zero thickness, so nothing needs to stay two-sided.

## Exhaust and the throttle animation

`FighterExhaustModel` goes into the renderer's **propeller** slot. Pass `fighter_metal.png` as
`propellerTexture` too.

Parts, children of `Nozzle`:

- `petal_top`, `petal_bottom`, `petal_left`, `petal_right`: the four nozzle petals. Each is hinged at its
  front edge, at z = 44.
- `Flame`: the afterburner, two cubes, pivot at the nozzle exit (z = 52).
- The turbine face, with its orange glow, is a cube on `Nozzle` itself, recessed inside the petals.

The hook is `public void applyThrottle(float throttle)`, with the throttle clamped to [0, 1]:

- the petals open outwards by `throttle * PETAL_OPEN_ANGLE` (0.2618 rad, 15°);
- `Flame` is visible above 5% throttle;
- `Flame.zScale` = `0.4 + 1.2 * throttle` and its x/y scale = `0.8 + 0.3 * throttle`.

On 26.3 `setupAnim` calls `applyThrottle(state.throttle)`; `PlaneRenderer.extractRenderState` fills
`PlaneRenderState.throttle` with the plane's throttle over 5, capped at 1. `IDLE_THROTTLE = 0` (nozzle closed, no
flame) is kept for the previews. `applyThrottle` has to run after `super.setupAnim(state)`, which resets the
pose. The reference render
`fighter-afterburner.png` shows `applyThrottle(1)`.

The flame is lit like everything else, so it is not emissive and will look dark at night. A glowing flame
needs its own submit with an emissive render type, for example `RenderTypes.eyes`.

## The glass layer

The four canopy tiers are real see-through glass in `FighterGlassModel`, part `Glass`, at
`PartPose.offset(0, 24, 0)` like the other layers:

| Cube | Box (local px) | UV |
|---|---|---|
| `canopy_front` (windscreen foot) | x ±5, y -25 to -22, z -33 to -28 | (84, 0) |
| `canopy_main` | x ±7, y -27 to -22, z -28 to 0 | (0, 0) |
| `canopy_mid` | x ±6, y -30 to -27, z -26 to -1 | (0, 33) |
| `canopy_top` | x ±4, y -33 to -30, z -21 to -3 | (74, 33) |

They are the boxes of the old opaque canopy, unchanged, so the silhouette, the dimensions and the rider
position are what they were. The opaque parts stay in `FighterMetalModel`: `CanopyFrame` (sill rails and the
windscreen bow) and `Cockpit` (panel, coaming, seat, headrest). The seat and the pilot are visible through the
glass.

### Render type

`super(root, RenderTypes::entityTranslucentCull)`: pipeline `entity_translucent_cull`, translucent blend, alpha
cutout at 0.1, back faces culled, depth write on. It is the 26.2 `entityTranslucentCullItemTarget` under its 26.3
name, the same type as the mini helicopter's glass. From outside only the faces turned towards the camera are
drawn, so the pilot and the scenery behind the canopy are seen through one layer of tint.

### Culling: a clean view from the seat

The eye is inside `canopy_main` (derived) or `canopy_mid` (measured); from either, every face of that tier is a
back face and is not drawn. The faces of the other tiers that point at the eye are alpha 0 in `fighter_glass.png`,
below the 0.1 cutout, so they are discarded:

- every bottom face, and the rear face of `canopy_front`;
- the side ledges of `canopy_main`'s top (|x| > 6): from an eye in `canopy_mid` they are front faces seen edge-on,
  and at any alpha they showed as bright lines along the sill.

The faces that lie on another tier, on the fuselage or against the dorsal spine are alpha 0 as well, so no two
tinted faces are ever coplanar from outside:

- the tops of `canopy_main` under `canopy_mid` and of `canopy_mid` under `canopy_top`;
- the part of `canopy_main`'s front face behind `canopy_front`;
- the middle of `canopy_main`'s rear face (|x| < 4), against the spine.

What the pilot still sees is the top of `canopy_front` and the front and rear ledges of `canopy_main`'s top: plain
faint panes (alpha 100 and 80) without the denser edge or the highlight, which read as windscreen tint.

### Sorting against the pilot

`SubmitNodeCollection.submitModel` sends every blended submit to the translucent phase, and
`TranslucentFeatureRenderPhase` draws it back to front by the distance from the camera to each submit's pose
origin. The rider's `PlayerModel` is `entityTranslucent`, so the pilot is a translucent submit too, and the glass
writes depth: if the glass is drawn first, the pilot behind it disappears.

- The rider's submit origin is about 1.41 blocks above the feet, model local (0, -23.5, -6): inside
  `canopy_main`.
- The glass is submitted from `FighterGlassModel.sortOrigin(camera, state.glassSortOrigin)`: the camera, clamped
  into the canopy's bounds (x ±7, y -33 to -22, z -33 to 0 px), then moved **`SORT_BIAS` = 0.5 block** further
  towards the camera (never past it). From inside the box the origin is the camera itself.
- The clamped point is nearer the camera than any point inside the box, so with a level plane the glass always
  sorts after the pilot. The bias is the one addition to the mini helicopter's scheme: the rider's origin is 1.41 blocks
  above the feet in world up, not in the plane's up, so in a bank or pitch of more than about 20 degrees it
  swings out of the canopy's box, where it could sort after the glass. 0.5 block covers any attitude.
- `FighterGlassModel.applySortOrigin` moves the `Glass` part by `-origin`, so the translated pose draws the glass in
  its usual place. It runs in `setupAnim`, which on 26.3 happens at draw time; that is why the origin travels in
  `PlaneRenderState.glassSortOrigin`, shared with the mini helicopter.

`FighterRenderer.submitExtraLayers` does the wiring, the same code as `MiniHeliRenderer`: it takes the camera as
the inverse of the current pose applied to the origin, fills `state.glassSortOrigin`, pushes a pose translated by
it and submits the glass with `glassModel.renderType(FighterGlassModel.TEXTURE)`, `state.lightCoords`,
`NO_OVERLAY` and `state.outlineColor`.

With improved transparency on (the order-independent path) the glass and the pilot are composited by depth and the
order does not matter; it was checked in game as well.

### Frame against glass

`CanopyFrame` cubes are grown by `FighterMetalModel.FRAME_GROW` (0.05 px), so no frame face lies in the plane of a
glass face and nothing z-fights.

## Textures

- The material layer tiles the plane's material block texture at 1 texel per pixel, with
  `LayerDefinition.create(mesh, 16, 16)`, exactly as `PlaneModel` does.
- `fighter_metal.png` is 128x128 RGBA, a procedurally generated original texture, and it holds the UV net of
  every metal and exhaust cube: dark panelled metal (nose and intakes), the dark canopy frame, the black
  coaming, a grey seat with an olive cushion and a yellow-and-black striped handle on the headrest, black
  intake mouths, rubber tyres with hubs, light-grey struts and pitot probe, white missiles with a red band and
  dark seeker, burnt-metal nozzle petals, the glowing turbine face, and the flame. The nets of the old opaque
  canopy tiers are cleared.
- `fighter_glass.png` is 128x64 RGBA, procedurally generated and original (no Mojang textures): light blue-grey
  panes, RGB about (128, 168, 192), alpha 112 on the sides and 100 on the tops; a denser rim (alpha 150) along
  the top row and the vertical edges of each side pane and round each top; one diagonal highlight streak
  (alpha 165) on panes 12 texels or longer; the alpha-0 and faint-pane regions listed under *The glass layer*.
- Render types: see the table at the top.
- The existing `plane_metal.png` could not be reused. It has no alpha channel, and it has no glass, flame or
  turbine regions.

## Parts

`FighterModel` > `Fighter`:

- `Fuselage`: nose section, forward fuselage, cockpit floor and side walls (the cockpit is hollow), centre
  and aft fuselage, and a two-step dorsal spine.
- `Wings`: a four-step delta planform on each side, 2 px thick, at local y -15 to -13.
- `Tail`: two-step stabilisers, plus `fin_left` and `fin_right`, which are five-step fins canted 15°
  outwards (`zRot` ±0.2618).

`FighterMetalModel` > `Metal`:

- `Nose`: radome in two steps, plus the pitot probe.
- `CanopyFrame`: the sill rails along both sides of `canopy_main` (y -23 to -22, z -28 to 0) and the
  windscreen bow at z -23: posts through `canopy_main` and `canopy_mid` and a bar across the top of
  `canopy_mid`. All grown by `FRAME_GROW`.
- `Cockpit`: the instrument panel and the coaming over it, visible in first person, and the seat: pan (on the
  cockpit floor), back (up to the sill) and headrest (behind the pilot's head).

`FighterGlassModel` > `Glass`: the four canopy tiers (see *The glass layer*).
- `Intakes`: the side intakes.
- `Gear`: nose strut and wheel, and two main struts and wheels.
- `Missiles`: the wingtip missiles.

## Things the entity work will run into

- **Upgrade models.** On 26.3 `UpgradesModels.hasNoUpgradeVisuals` includes the fighter, so upgrades draw
  nothing on it (without that branch it would get the helicopter's engine, floats, armour and seats).
- **Arm clipping.** A seated player's arms reach x = ±7.5 px, and the canopy is ±7 px wide, so about 0.5 px
  of the upper arm pokes through the sides of the canopy just above the sill.
- **Head yaw.** The headrest sits 0.25 px behind the head; turning the head far to the side can clip it into
  the headrest.

## Flight limits the autopilot relies on

Not part of the render contract, but it is what missile evasion (`design/FIGHTER-EVASION.md`) is sized
against. If the airframe's handling changes, re-measure these and the evasion results with them.

| | value |
|---|---|
| rates (`FighterEntity`) | yaw 3.5, pitch 7.0, roll 8.0 deg/t, body frame |
| route cruise | 2.6 b/t |
| full power, booster fitted (throttle 10, `setMaxSpeed(3.0)`) | 3.16–3.17 b/t level |
| world turn rate at the autopilot's 25° bank | about 2.9 deg/t |
| world turn rate at 60° bank (evasion) | about 3.5 deg/t average, 5.5 peak |

Because yaw and pitch act in the body frame, bank is what turns the aircraft faster. The flight path follows
the nose with `yawToMotion` 0.2 and `pitchToMotion` 0.3, so the track lags the heading by a few ticks.
3.16 b/t is above T1–T3 missile top speed (2.0 / 2.5 / 3.0) and below T4 (4.0). That one comparison decides
which missiles a fighter can outrun.

## Reference images

`docs/fighter/`:

- `offline-oak-sheet.png`, `offline-iron-sheet.png`: 3/4 front, 3/4 rear, side, top, front and low views of the
  wood and iron-block variants, from the real `LayerDefinition`s baked outside the game (approximate lighting,
  back-to-front sorted glass).
- `offline-oak-cockpit-sheet.png`: cockpit close-ups with and without a pilot.
- `offline-fp-sheet.png`: the pilot's view in the preview, from the derived and the measured eye.
- `before-after.png`: the opaque canopy against the glass one.
- `client-oak-sheet.png`, `client-iron-sheet.png`: the 26.3 client, from outside, with a pilot.
- `client-pilot-sheet.png`: the 26.3 client from the pilot's seat (forward, down, left, right, up, back) and in
  third person.
