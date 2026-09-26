# Mini recon quadcopter: render model contract

This file is for whoever writes the quadcopter's entity, physics and registration. It covers the render model
only, which exists and compiles:

| Layer | Class | Texture | Cubes |
|---|---|---|---|
| material (frame) | `client/render/models/DroneModel` | `state.materialTexture` (the block texture, tiled 16x16, same as `PlaneModel`) | 3 |
| metal | `client/render/models/DroneMetalModel` | `simpleplanes:textures/plane_upgrades/drone_metal.png` (64x32, new) | 32 (28 with the payload released) |
| rotors (propeller slot) | `client/render/models/DroneRotorModel` | same `drone_metal.png` | 8 |

That is 43 cubes in all. The starter plane has 60 and the fighter 56.

Nothing is registered: there is no layer location, renderer, entity type or item. `PlaneRenderState`,
`PlaneRenderer`, `PlanesModelLayers` and `SimplePlanesEntities` are untouched.

The drone is unmanned, so there is no seat. In place of the seat this contract gives a camera point for the
remote pilot's view and the payload attachment point.

## Why the material layer is so small

A small quadcopter is almost all electronics and plastic. The shell, battery, motors, camera, legs, clamp and
rotors would look wrong in planks, so they are on the metal layer. Only the structural frame is on the material
layer, meaning the bottom plate and the two crossed arm beams of the X frame. That is still the part a Simple
Planes builder would make from the block. Seen from above, the material shows as the X under the rotors, so the
material variants stay recognisable at this size.

## Coordinates

### Model space

Model space is in pixels, where 1 px = 1/16 block. It uses the usual `ModelPart` convention: +Y points
down and the nose points to -Z. +X is the aircraft's left side.

All three layers hang everything off one top-level part at `PartPose.offset(0, 24, 0)`: `Frame` in
`DroneModel`, `Metal` in `DroneMetalModel` and `Rotors` in `DroneRotorModel`. Their local frames match, and in
that local frame:

- The ground contact, meaning the bottoms of the four rubber feet, is at **y = 0**. The feet are centred at
  x = ±6 and z = ±6.
- The frame plate is at y = -8 to -7, the arm beams at y -9 to -8, the electronics shell at y -12 to -9, and the
  battery top at y = -14.
- The motors are centred on **(±6.5, ±6.5)** and run from y = -12 to -9. The rotor blades lie in the plane
  y = -12.5, and the hubs reach y = -13.
- The antenna tips are at about y = -17.2.
- The body runs from z = -5 to +5 (the plate). The camera lens reaches z ≈ -6.9, and the payload's tail fins
  reach z = +5.5.
- The rotor discs reach x and z = ±11.

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
} else if (entityType == SimplePlanesEntities.DRONE.get()) {
    poseStack.translate(0.0F, -0.025F, 0.0F);
}
```

This value was used for every reference render. With it the mapping simplifies to the following, where
`(x, y, z)` is the local position under the `offset(0, 24, 0)` root:

```
entity = ( x/16 ,  -y/16 ,  -z/16 )
```

- The feet touch **y = 0** exactly.
- The airframe is centred on the entity origin in x and z.
- The renderer rotates the model about entity point **(0, 0.375, 0)**, because the quaternion comes before the
  per-type translate. That point is local (0, -6, 0): the top of the payload, directly under the clamp servo and
  1 px below the frame plate. It is roughly the drone's centre of mass, so pitch and roll look natural.
- The damage wobble (`timeSinceHit`) rolls about the same point.

Do not leave the drone on the helicopter branch of `submit`, which is the `else` default `(0, 0, 0.9)`. That
would lift it 1.1 blocks and shift it 0.9 blocks back.

## Dimensions (entity space, suggested translate)

| | blocks |
|---|---|
| rotor-tip envelope, x by z | 1.375 x 1.375 (tip to tip along a diagonal: 1.71) |
| motor to motor (outer faces) | 1.0 |
| feet footprint (outer edges) | 0.875 x 0.875 |
| frame plate, x by z | 0.375 x 0.625 |
| height of the rotor plane | 0.78 |
| height to the top of the battery | 0.875 |
| height to the antenna tips | 1.075 |
| lowest point under the body (clamp hooks; the camera is at 0.16) | 0.125 |

For comparison, a player is 0.6 x 1.8. The drone reaches about the player's knee, and the rotors cover a bit
more than a block.

## Hitbox

`sized(1.0F, 0.875F)`. The width covers the body and the motors (x = ±0.5). The rotor tips stick out 0.19 on each
side, as the starter plane's wingtips stick out of its hitbox. The height reaches the top of the battery; the
antennas are left out.

Suggested `shadowRadius` for the `PlaneRenderer` constructor: `0.5F`.

## Camera point (remote pilot's view)

The gimbal camera hangs under the nose, pitched **20° nose-down** (`DroneMetalModel.CAMERA_TILT = 0.3491` rad).

- The camera is the cube in part `Gimbal/Camera`. Its pivot, which is the cube's centre, is at local
  (0, -4.5, -5).
- **Lens centre**, on the front face: local (0, -3.99, -6.41), which is **entity (0, 0.249, 0.401)**.
- **View direction** in entity space: **(0, -0.342, 0.940)**, forward and 20° down.

A remote-view camera should sit at the lens centre, or a hair in front of it, and look along the view
direction, rotated with the drone's quaternion about (0, 0.375, 0) like everything else. If the view is put
inside the camera cube, culling hides the cube's own faces, so nothing blocks it. The plate and the feet are
above and behind the lens, so the downward view is clear.

## Payload attachment point

The payload is a finned drop canister. It is 3 x 3 x 5 px, plus a 1 px fuze nose and 2 px tail fins, and it hangs
in the drop clamp under the centre of the frame.

- **Payload centre** (the point to spawn a dropped projectile at): local (0, -4.5, 1), which is
  **entity (0, 0.281, -0.063)**.
- **Clamp face** (top of the payload, underside of the servo): local y = -6, which is entity y = 0.375.
- The payload's nose points forward (+Z in entity space).
- A projectile should leave with the drone's velocity. Nothing below the payload is in the way: the hooks
  open outwards and the legs are at ±0.28 or more.

## Animated parts and hooks

### Rotors (`DroneRotorModel`, propeller slot)

Pass `drone_metal.png` as `propellerTexture` too.

- There are four children of `Rotors`: `rotor_front_left` (+6.5, -12, -6.5), `rotor_front_right` (-6.5, -12,
  -6.5), `rotor_rear_left` (+6.5, -12, +6.5) and `rotor_rear_right` (-6.5, -12, +6.5). Each pivot is on its
  motor's axis, at the top of the motor bell.
- Each rotor is a 1 px hub and a 9 x 2 px blade plane of **zero thickness**, with a cut-out, Z-shaped two-blade
  outline and orange tips. Its top and bottom faces make it visible from both sides.
- `setupAnim` spins them from `state.propellerRotation`, exactly the input `PropellerModel` uses:
  - front-left and rear-right: `yRot = rotation - BLADE_PHASE`;
  - front-right and rear-left: `yRot = -rotation + BLADE_PHASE`.

  So diagonal pairs turn together and **adjacent rotors counter-rotate**. With `propellerRotation` increasing,
  front-left and rear-right turn clockwise seen from above, and front-right and rear-left anticlockwise (the
  common layout for real quadcopters). `BLADE_PHASE` (π/4) starts every blade across its arm, which keeps the
  picture mirror-symmetric left to right.
- The blade outlines are cut out, so these parts need a cutout render type. That is the default `EntityModel`
  type in 26.2 (`RenderTypes::entityCutout`); do not change it to a solid type.

### Payload release (`DroneMetalModel`)

The hook is `public void setPayloadAttached(boolean attached)`:

- attached: the `Payload` part is visible and the jaws are closed;
- released: `Payload.visible = false`, and the clamp jaws `Clamp/jaw_left` and `Clamp/jaw_right` swing outwards
  by `JAW_OPEN_ANGLE` (0.6109 rad, 35°) about their hinges at the sides of the servo.

The hook sets `visible` in both directions. It has to, because `super.setupAnim` resets the pose but not
`visible`.

`setupAnim` currently calls `setPayloadAttached(DEFAULT_PAYLOAD_ATTACHED)`, with the constant `true`. To wire
it:

1. add a `boolean payloadAttached` to `PlaneRenderState`;
2. fill it in `PlaneRenderer.extractRenderState` from the drone entity;
3. in `DroneMetalModel.setupAnim`, replace `DEFAULT_PAYLOAD_ATTACHED` with `state.payloadAttached`.

The reference render `drone-payload-released.png` shows `setPayloadAttached(false)`.

Nothing else moves. The gimbal tilt is fixed through `CAMERA_TILT`; if the pilot's view should be able to pitch,
the `Gimbal/Camera` part's `xRot` is the place to do it.

## Textures

- The material layer tiles the plane's material block texture at 1 texel per pixel, with
  `LayerDefinition.create(mesh, 16, 16)`, exactly as `PlaneModel` does.
- `drone_metal.png` is new. It is 64x32 RGBA, an original texture generated procedurally, and it holds the UV
  net of every metal and rotor cube:
  - an olive-grey composite shell with a cooling grille, a front sensor window, and green and red navigation
    LEDs;
  - a black battery with a yellow label and a strap;
  - a grey GPS puck;
  - black whip antennas with red tips;
  - silver-topped motor bells;
  - dark legs and rubber feet;
  - a grey gimbal yoke and a black camera with a blue lens;
  - a dark clamp servo with an amber status lamp, and steel jaws;
  - an olive payload with a yellow band, a dark fuze and olive fins;
  - black rotor blades with orange tips.
- Transparent texels are used only around the blade outlines and in unused atlas space.
- The `texOffs` were packed by the generator and are baked into the Java. The Java is the source of truth and
  can be edited by hand.
- No existing texture was reused. `plane_metal.png` has no alpha channel, so it cannot cut out the blades.

## Parts

`DroneModel` > `Frame`: the frame plate, plus `arm_a` and `arm_b`, the two 18 px beams crossing at ±45°
(`yRot` ±0.7854).

`DroneMetalModel` > `Metal`:

- `Body`: shell, battery, GPS puck and the two antenna bases.
- `antenna_left`, `antenna_right`: 0.5 px whips, leaning 20° outwards and 15° back.
- `Motors`: the four motors.
- `Legs`: the four feet, plus `leg_front_left`, `leg_front_right`, `leg_rear_left` and `leg_rear_right`. Each leg
  starts at the middle of an arm and is splayed out along the diagonal (`LEG_SPLAY` = 0.2 rad).
- `Gimbal`: the U yoke, plus `Camera`.
- `Clamp`: the servo, plus `jaw_left` and `jaw_right`, which are L-shaped jaws whose hooks sit under the
  payload.
- `Payload`: the canister, the fuze nose, and the vertical and horizontal tail fins (zero-thickness planes).

`DroneRotorModel` > `Rotors`: the four rotors described above.

## Things the entity work will run into

- **Upgrade models.** `UpgradesModels.modelFor`/`textureFor`/`submitSeats` send any entity type that is not
  the plane, the large plane or the cargo plane to the **helicopter** variants. A drone with upgrades would
  therefore draw helicopter parts, and seats, in helicopter positions, which are far larger than the drone.
  Give it its own branch there, most likely one with no upgrade visuals and no seats.
- **Small size.** The whole model fits inside 1.4 x 1.1 x 1.4 blocks. The damage wobble and the pivot are
  shared with the big planes, so check in game that the wobble angle does not look exaggerated at this size.
- **Not checked in game.** The model has only been rendered outside the game, by baking the real
  `LayerDefinition`s and walking the `ModelPart`s under the same `PoseStack` transforms that `submit()` uses. The
  lighting in those renders is an approximation.
