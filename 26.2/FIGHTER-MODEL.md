# Fighter jet: render model contract

This file is for whoever writes the fighter's entity, physics and registration. It covers the render model
only, which exists and compiles:

| Layer | Class | Texture | Cubes |
|---|---|---|---|
| material (wood) | `client/render/models/FighterModel` | `state.materialTexture` (the block texture, tiled 16x16, same as `PlaneModel`) | 31 |
| metal | `client/render/models/FighterMetalModel` | `simpleplanes:textures/plane_upgrades/fighter_metal.png` (128x128, new) | 18 |
| exhaust (propeller slot) | `client/render/models/FighterExhaustModel` | same `fighter_metal.png` | 7 (5 at idle; the 2 flame cubes are hidden) |

That is 56 cubes in all. The starter plane has 60 (41 + 15 + 4).

Nothing is registered: there is no layer location, renderer, entity type or item. `PlaneRenderState`,
`PlaneRenderer`, `PlanesModelLayers` and `SimplePlanesEntities` are untouched.

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

The canopy is opaque tinted glass, so the pilot cannot be seen from outside. The pilot can still see out:
all three layers use the default `EntityModel` render type, which in 26.2 is `RenderTypes::entityCutout`
and culls back faces, so from an eye inside `canopy_main` every face of the canopy is a back face.

If the seat ends up a little higher and the eye moves up into `canopy_mid`, the only canopy face pointing
at it is the top of `canopy_main`. The part of that face lying under `canopy_mid` is cut out
(alpha 0) in the texture; the same is done for the top of `canopy_mid` under `canopy_top`. What is left is
a 1 to 2 px ledge outline. Do not change these models to a no-cull render type, or the canopy will cover
the pilot's view.

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

`setupAnim` currently calls `applyThrottle(IDLE_THROTTLE)`, with `IDLE_THROTTLE = 0`: nozzle closed, no
flame. To wire the animation:

1. add a `float throttle` to `PlaneRenderState`;
2. fill it in `PlaneRenderer.extractRenderState` from the fighter's throttle or engine power, normalised to
   0 to 1;
3. in `FighterExhaustModel.setupAnim`, replace `IDLE_THROTTLE` with `state.throttle`.

`applyThrottle` has to run after `super.setupAnim(state)`, which resets the pose. The reference render
`fighter-afterburner.png` shows `applyThrottle(1)`.

The flame is lit like everything else, so it is not emissive and will look dark at night. A glowing flame
needs its own submit with an emissive render type, for example `RenderTypes.eyes`.

## Textures

- The material layer tiles the plane's material block texture at 1 texel per pixel, with
  `LayerDefinition.create(mesh, 16, 16)`, exactly as `PlaneModel` does.
- `fighter_metal.png` is new. It is 128x128 RGBA, a procedurally generated original texture, and it holds
  the UV net of every metal and exhaust cube: dark panelled metal (nose and intakes), tinted canopy glass
  with a dark frame at the windscreen bows, black intake mouths, rubber tyres with hubs, light-grey struts
  and pitot probe, white missiles with a red band and dark seeker, burnt-metal nozzle petals, the glowing
  turbine face, and the flame.
- The only transparent texels are canopy faces that cannot be seen from outside: the bottom faces, the
  rear face of the windscreen foot, and the covered parts of the tier tops described under Seat.
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
- `Canopy`: four tiers, `canopy_front`, `canopy_main`, `canopy_mid` and `canopy_top`.
- `Cockpit`: the instrument panel, visible in first person.
- `Intakes`: the side intakes.
- `Gear`: nose strut and wheel, and two main struts and wheels.
- `Missiles`: the wingtip missiles.

## Things the entity work will run into

- **Upgrade models.** `UpgradesModels.modelFor`/`textureFor`/`submitSeats` send any entity type that is not
  the plane, the large plane or the cargo plane to the **helicopter** variants. A fighter with upgrades
  would therefore draw the helicopter's engine, floats, armour and seats in helicopter positions. Before
  upgrades are allowed on it, the fighter needs its own branch there, either with no upgrade visuals or
  with new ones.
- **Arm clipping.** A seated player's arms reach x = ±7.5 px, and the canopy is ±7 px wide, so about 0.5 px
  of the upper arm pokes through the sides of the canopy just above the sill.
- **Not checked in game.** The model has only been rendered outside the game, by baking the real
  `LayerDefinition`s and walking the `ModelPart`s under the same `PoseStack` transforms that `submit()`
  uses. The lighting in those renders is an approximation.
