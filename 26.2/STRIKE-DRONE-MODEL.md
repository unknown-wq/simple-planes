# Single-use strike drone: render model contract

This file is for whoever writes the strike drone's entity, physics and registration. It covers the render model
only, which exists and compiles:

| Layer | Class | Texture | Cubes |
|---|---|---|---|
| material (wing) | `client/render/models/StrikeDroneModel` | `state.materialTexture` (the block texture, tiled 16x16, same as `PlaneModel`) | 18 |
| metal | `client/render/models/StrikeDroneMetalModel` | `simpleplanes:textures/plane_upgrades/strike_drone_metal.png` (64x64, new) | 11 |
| propeller | `client/render/models/StrikeDronePropellerModel` | same `strike_drone_metal.png` | 6 |

That is 35 cubes in all. The starter plane has 60 and the fighter 56.

Nothing is registered: there is no layer location, renderer, entity type or item. `PlaneRenderState`,
`PlaneRenderer`, `PlanesModelLayers` and `SimplePlanesEntities` are untouched.

The design is an original, blocky loitering munition:

- a slim fuselage;
- a cropped-delta wing;
- up-and-down tip winglets;
- a pointed nose holding the warhead and a seeker window;
- a rear engine driving a two-blade pusher propeller.

It has **no landing gear**, because it is launched and not recovered. It is unmanned, so there is no seat. In
place of the seat this contract gives a camera point for the remote pilot's view and the payload (warhead)
point.

## How the layers are split

The material layer carries the large flat lifting surfaces: the wing and the winglets. Those are what a Simple
Planes builder makes from the block, as on every other aircraft in the mod, and they keep the material variants
visible. The fuselage, the warhead section, the engine and the avionics are on the metal layer, because a
warhead or an engine made of planks would not read as one.

## Coordinates

### Model space

Model space is in pixels, where 1 px = 1/16 block. It uses the usual `ModelPart` convention: +Y points
down and the nose points to -Z. +X is the aircraft's left side.

`StrikeDroneModel` hangs everything off the part `Airframe`, and `StrikeDroneMetalModel` hangs everything off
`Metal`. Both parts are at `PartPose.offset(0, 24, 0)`. Their local frames match, and in that local frame:

- The lowest point is the bottom tips of the lower winglets, at **y = 0**. They are at x = ±23 to ±24 and
  z = 9 to 12.
- The fuselage's centre line is at **y = -7.5**. The fuselage is 6 px wide and runs from y -10 to -5. The spine
  on top reaches y = -11 and the keel below reaches y = -4.
- The wing is 1 px thick, at y = -8 to -7. It has four 5 px steps on each side, with a 45° leading edge.
  - The root chord runs from z = -10 to +12.
  - The tip chord runs from z = 5 to 12.
- The winglets run from y = -14 (top) to y = 0 (bottom), at x = ±23 to ±24.
- The seeker tip is at z = -22 and the warhead section starts at z = -8. The fuselage runs to z = 12, the
  engine to z = 16, and the spinner to z = 19.
- The middle of the length, from the seeker tip at -22 to the spinner at +19, is at **z = -1.5**.

`StrikeDronePropellerModel` has one top-level part, `Propeller`, at `PartPose.offset(0, 16.5, 16)` in absolute
pixels. That is local (0, -7.5, 16), on the fuselage axis at the rear face of the engine.

### Entity space

Entity space is in blocks, with +Y up and the nose at +Z.

`PlaneRenderer.submit` applies `translate(0, .375, 0)`, then `scale(-1, -1, 1)`, then `rotY(180)`, then the
plane's quaternion, then the per-type translate `(tx, ty, tz)`, then `translate(0, -1.1, 0)`. With an identity
rotation, an absolute model pixel `p` ends up at:

```
entity = ( p.x/16 ,  1.475 - ty - p.y/16 ,  -tz - p.z/16 )
```

## What to put in `PlaneRenderer.submit`

Suggested per-type translate:

```java
} else if (entityType == SimplePlanesEntities.STRIKE_DRONE.get()) {
    poseStack.translate(0.0F, -0.025F, 0.09375F);
}
```

This value was used for every reference render. With it the mapping simplifies to the following, where
`(x, y, z)` is the local position under the `offset(0, 24, 0)` root:

```
entity = ( x/16 ,  -y/16 ,  -(1.5 + z)/16 )
```

- The lower winglet tips touch **y = 0** exactly.
- The airframe is centred on the entity origin along its length. It runs from z = -1.281 at the spinner to
  z = +1.281 at the seeker tip.
- The renderer rotates the model about entity point **(0, 0.375, 0)**, because the quaternion comes before the
  per-type translate. That point is local (0, -6, -1.5): mid-length and 1.5 px below the fuselage axis, just
  under the wing.

Do not leave the drone on the helicopter branch of `submit`, which is the `else` default `(0, 0, 0.9)`.

## Dimensions (entity space, suggested translate)

| | blocks |
|---|---|
| wingspan, over the winglets | 3.0 |
| length, spinner to seeker tip | 2.5625 |
| fuselage width | 0.375 |
| wing plane (bottom to top) | 0.4375 to 0.5 |
| fuselage axis and propeller shaft height | 0.469 |
| keel above the ground | 0.25 |
| top of the fuselage (GPS puck, scoop) | 0.75 |
| winglet tops | 0.875 |
| antenna tip | 0.95 |
| propeller diameter | 0.875 (lowest blade tip 0.03 above the ground when vertical) |

For comparison, the starter plane has a 6.75 span and is 5.85 long.

## Hitbox

`sized(1.5F, 0.75F)`. The width covers the fuselage and the inner half of the wing. The outer wing and the
winglets stick out, as the starter plane's wingtips do. The height reaches the top of the fuselage; the
winglet tops and the antenna are left out.

Suggested `shadowRadius`: `0.75F`.

## Resting and launching

With no gear, the only ground contact in the model's own pose is the pair of lower winglet tips, and they are
near the tail. In the level render pose the drone hovers with its keel 0.25 above the ground. The render model
does not try to show it resting on the ground; a launch rail or a catapult entity is the natural way to spawn
it. If it must sit on the ground, pitch it nose-down about the pivot until the seeker tip or the keel touches.

## Camera point (remote pilot's view)

The view comes from the seeker window in the nose tip, the dark glass on the front face of the `seeker` cube.

- **Lens centre**: local (0, -7.5, -22), which is **entity (0, 0.469, 1.281)**.
- **View direction**: straight ahead along the fuselage axis, entity (0, 0, 1), rotated with the drone's
  quaternion about (0, 0.375, 0).

The seeker tip is the most forward point of the model, so nothing of the drone is in view. A camera placed a
hair in front of the lens sees nothing of the model.

## Payload (warhead) point

The warhead is part of the airframe; nothing is dropped. The payload section is the olive nose: the warhead block
with its yellow band, the nose cone and the seeker. It spans local z = -22 to -8, which is entity z = 0.406 to
1.281.

- **Warhead centre**, where an explosion should be spawned when it detonates in the air: the warhead block's
  centre, local (0, -7.5, -11), which is **entity (0, 0.469, 0.594)**.
- **Impact point**, for a contact fuze: the seeker tip, entity (0, 0.469, 1.281).

There is no payload-release hook. A single-use drone is destroyed together with its warhead.

## Animated parts

### Propeller (`StrikeDronePropellerModel`, propeller slot)

Pass `strike_drone_metal.png` as `propellerTexture` too.

- `Propeller` is the hub (2 x 2 x 2) and the spinner (1 x 1 x 1). Its children `blade_a` and `blade_b` each have
  a 2 px-wide root section and a 1 px tip with a yellow warning band. The blades reach 7 px from the shaft.
- Both blades are pitched `BLADE_PITCH` (0.3491 rad, 20°) about their long axis. `blade_b` is `blade_a` turned
  half a turn about the shaft (`zRot` π), so both blades have the same pitch sense, as on a real propeller.
- `setupAnim` sets `Propeller.zRot = state.propellerRotation`, exactly as `PropellerModel` does.

Nothing else moves. No render-state fields are needed.

## Textures

- The material layer tiles the plane's material block texture at 1 texel per pixel, with
  `LayerDefinition.create(mesh, 16, 16)`, exactly as `PlaneModel` does.
- `strike_drone_metal.png` is new. It is 64x64 RGBA, an original texture generated procedurally, and it holds
  the UV net of every metal and propeller cube:
  - a field-grey fuselage with panel lines, a side stripe and an avionics hatch on the spine;
  - an olive warhead with a centred yellow band, and an olive nose cone;
  - a dark seeker window with a glint;
  - a dark engine with cooling fins, and a burnt exhaust stub with a black outlet;
  - a cooling scoop with a black mouth;
  - a white GPS puck and a black antenna;
  - a silver hub and spinner;
  - dark blades with yellow tips.
- No texel on a used face is transparent, and the model needs no cut-out.
- The `texOffs` were packed by the generator and are baked into the Java. The Java is the source of truth and
  can be edited by hand.

## Parts

`StrikeDroneModel` > `Airframe`:

- `Wings`: four steps on each side (a left/right `.mirror()` pair per step).
- `Winglets`: three swept steps above the wing and two below it, at each tip.

`StrikeDroneMetalModel` > `Metal`:

- `Fuselage`: the main fuselage, the spine, the keel, the cooling scoop and the GPS puck.
- `Warhead`: the warhead block, the nose cone and the seeker tip.
- `Engine`: the engine block, and the exhaust stub on the left (+X) side.
- `antenna`: a 0.5 px whip on the spine, leaning 25° back.

`StrikeDronePropellerModel` > `Propeller`: the hub and spinner, plus `blade_a` and `blade_b`.

## Things the entity work will run into

- **Upgrade models.** `UpgradesModels.modelFor`/`textureFor`/`submitSeats` send any entity type that is not
  the plane, the large plane or the cargo plane to the **helicopter** variants. Give the strike drone its own
  branch there, most likely one with no upgrade visuals and no seats.
- **Propeller near the ground.** The propeller disc comes within 0.03 of the ground in the level pose, so it can
  flicker against a grass or snow layer when the drone is on the ground. It does not intersect the ground.
- **Not checked in game.** The model has only been rendered outside the game, by baking the real
  `LayerDefinition`s and walking the `ModelPart`s under the same `PoseStack` transforms that `submit()` uses. The
  lighting in those renders is an approximation.
