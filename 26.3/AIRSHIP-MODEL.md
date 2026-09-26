# Airship: render model contract

This file is for whoever writes the airship's entity, physics (buoyancy and ballast) and registration. It
covers the render model only, which exists and compiles. The model was drawn for its own sake and does not
assume anything about the flight model.

| Layer | Class | Texture | Cubes |
|---|---|---|---|
| material (wood) | `client/render/models/AirshipModel` | `state.materialTexture` (the block texture, tiled 16x16, same as `PlaneModel`) | 19 |
| metal | `client/render/models/AirshipMetalModel` | `simpleplanes:textures/plane_upgrades/airship_metal.png` (256x256 RGBA, new) | 39 |
| propellers (propeller slot) | `client/render/models/AirshipPropellerModel` | same `airship_metal.png` | 4 |
| envelope (**fourth layer**) | `client/render/models/AirshipEnvelopeModel` | `simpleplanes:textures/plane_upgrades/airship_envelope.png` (32x32 RGBA, tiling, new) | 61 |

That is 123 cubes in all. For comparison, the starter plane has 60, the fighter 56 and the cargo plane about
290. The envelope accounts for half of the count: 45 boxes for the gasbag and 16 for the fins.

Nothing is registered: there is no layer location, renderer, entity type or item. `PlaneRenderState`,
`PlaneRenderer`, `PlanesModelLayers` and `SimplePlanesEntities` are untouched.

## Coordinates

### Model space

Model space is in pixels, where 1 px = 1/16 block. It uses the usual `ModelPart` convention: +Y points
down and the nose points to -Z. +X is the aircraft's left side.

- `AirshipModel` hangs everything off `Airship`, `AirshipMetalModel` off `Metal` and
  `AirshipEnvelopeModel` off `Envelope`. All three are at `PartPose.offset(0, 24, 0)` and share one local
  frame. In that frame:
  - the ground contact, the bottom of the landing wheel under the gondola, is at **y = 0**;
  - the **gondola is centred on z = 0**: its hull runs from the bow at z = -57 to the stern at z = +56;
  - the gondola floor is at y = -9, the bulwark top at y = -20, the cabin roof underside at y = -35 and the
    roof top at y = -40;
  - the envelope axis is at **y = -88**; the gasbag runs from z = -142 (nose) to z = +127 (tail) and is at
    most 78 px across;
  - the fins span ±48 px from the axis, and the rudder and elevator trailing edges are at z = +116;
  - the mooring spike tip is at z = -151 and the tail cone ends at z = +129.
- `AirshipPropellerModel` has two top-level parts, `PropLeft` and `PropRight`. They sit at
  `PartPose.offset(±42, 2, 10)` in absolute pixels, which is the hub, local (±42, -22, 10). Each is a brass
  spinner and one two-bladed wooden propeller, 28 px across.

### Entity space

`PlaneRenderer.submit` applies `translate(0, .375, 0)`, then `scale(-1, -1, 1)`, then `rotY(180)`, then
the plane's quaternion, then the per-type translate `(tx, ty, tz)`, then `translate(0, -1.1, 0)`. With an
identity rotation, an absolute model pixel `p` ends up at:

```
entity = ( p.x/16 ,  1.475 - ty - p.y/16 ,  -tz - p.z/16 )
```

## What to put in `PlaneRenderer.submit`

Suggested per-type translate:

```java
} else if (entityType == SimplePlanesEntities.AIRSHIP.get()) {   // AIRSHIP does not exist yet
    poseStack.translate(0.0F, -0.025F, 0.0F);
}
```

This value was used for every reference render. With it, for a local point `q` under the root:

```
entity = ( q.x/16 ,  -q.y/16 ,  -q.z/16 )
```

- The landing wheel touches **y = 0** exactly.
- The entity origin is the **middle of the gondola**. That is where the hitbox and the riders are. The
  whole airship runs from entity z = -8.06 (tail cone) to +9.44 (mooring spike), so it is 0.7 blocks
  nose-heavy about the origin.
- **Rotation pivot.** The quaternion is applied before the per-type translate, so the airship rotates about
  entity **(0, 0.375, 0)**, which is inside the bottom of the gondola hull. That is fine for yaw. For pitch
  and roll it swings the 5-block-tall envelope around the gondola's keel. A buoyant craft normally
  rotates about its centre of mass or buoyancy, somewhere between the gondola and the envelope axis. The
  gasbag's volume centroid is at entity **(0, 5.5, 1.23)**. If the physics wants that, `submit()` needs a
  per-type pivot for this type: translate up to the pivot, rotate, translate back. A per-type translate
  alone cannot do it without also lifting the model off the ground.

## Dimensions (entity space, suggested translate)

| | blocks |
|---|---|
| overall length, tail cone to mooring spike | 17.5 |
| gasbag length | 16.8 |
| gasbag diameter | 4.9 (entity y 3.06 to 7.94, axis at 5.5) |
| span across the fins / across the propeller discs | 6.0 / 5.95 |
| overall height, to the top fin tip | 8.5 |
| gondola: hull length, width at the roof, height to the roof top | 7.1, 2.5, 2.5 |
| clearance between the cabin roof and the gasbag underside | 0.56 |

The starter plane is 5.85 long, 6.75 across and 2.4 tall; the fighter is 7.0 long and 2.45 tall.

## Hitbox

A vanilla `EntityType` hitbox is one axis-aligned box with a square footprint (`sized(w, h)`), centred on
the entity position. It does not turn with the yaw. At 17.5 x 6 x 8.5 there is no single box that fits:

1. **Gondola only (suggested): `sized(3.0F, 2.5F)`.** It covers the middle 3 blocks of the gondola, from
   the ground to the cabin roof, and is the same width as the large and cargo planes. Players click, board
   and collide with the gondola, which is where the seats are. The envelope is not solid: arrows and
   players pass through it. The bow and stern ends of the gondola (±3.5 blocks) stick out, as the starter
   plane's wings do.
2. **One box around everything.** The square footprint would have to be about 17.5 wide to hold the length
   at any yaw, and 8.5 tall. That blocks a huge area, makes the airship impossible to walk around when it
   is moored, and makes interaction hit the air around it. Not recommended.
3. **Several boxes.** Vanilla only supports multipart entities for the ender dragon
   (`EnderDragon`/`EnderDragonPart`, special-cased in the level's entity lookups). Other options are a
   second, invisible "envelope" entity that rides along, or a custom collision shape queried by the
   physics code. Either can come later, if the envelope has to be solid for landing on roofs or for
   combat.

Suggested `shadowRadius` for the `PlaneRenderer` constructor: `2.5F`. The vanilla shadow is a disc under
the entity position, under the gondola; it cannot show the envelope's shadow.

## People aboard (`positionRider`)

There are seven seats: the pilot at the front of the enclosed cabin, one row of two passengers behind him
in the cabin, and two rows of two on the open aft deck behind the cabin. All are seated on wooden benches
and face forward. The table gives each passenger's feet point in entity space (blocks, +X = the aircraft's
left, +Z = the nose). That is what `transformPos(...)` should return before it goes into
`moveFunction.accept`.

| index | seat | feet point (x, y, z) | eye (feet + 1.62) |
|---|---|---|---|
| 0 | pilot, cabin, centre | (0.0, 0.1875, 1.75) | y 1.8075 |
| 1 | cabin row, left | (0.5625, 0.1875, 0.375) | y 1.8075 |
| 2 | cabin row, right | (-0.5625, 0.1875, 0.375) | y 1.8075 |
| 3 | deck row 1, left | (0.5625, 0.1875, -1.25) | y 1.8075 |
| 4 | deck row 1, right | (-0.5625, 0.1875, -1.25) | y 1.8075 |
| 5 | deck row 2, left | (0.5625, 0.1875, -2.75) | y 1.8075 |
| 6 | deck row 2, right | (-0.5625, 0.1875, -2.75) | y 1.8075 |

```java
Vector3f pos = transformPos(new Vector3f(SEAT_X[i], 0.1875f, SEAT_Z[i]));
moveFunction.accept(passenger, getX() + pos.x(), getY() + pos.y(), getZ() + pos.z());
```

- Use **y = 0.1875 as is**. `LargeAirframeEntity.getEntityYOffset` returns -0.4 for players, and adding
  it would sink them into the hull. If `getPassengersRidingOffset()` is used, make it return 0.1875 and add
  nothing else for players. Villagers and other smaller mobs will need their own offsets.
- The numbers come from the player model as `PlayerRenderer` draws it (15/16 scale, riding pose). The hips
  are 11.25 px above the feet point, so the thighs rest on the bench tops at local y = -12. The head top is
  30 px up, at entity y 2.06, which leaves 2 px under the cabin roof (2.1875). The arms reach ±7.5 px, and
  the pairs sit at x = ±9 px, so they clear each other and the inner face of the walls (±17 px).
- The legs reach about 13 px forward of the feet point. Each seat has at least 3 px clearance to the
  backrest or console in front of it.
- **Eyes.** Every eye is at entity y 1.8075. In the cabin (indices 0 to 2) that is inside the window band
  (y 1.25 to 2.19): the panes are cut out (alpha 0), so they look out through open window frames. The
  pilot looks over a low console and helm (tops at y 1.31 and 1.56). On the aft deck (indices 3 to 6) the
  rail top is at y 1.6875, 2 px below the eye, so the deck passengers look out over the rail with their
  heads and shoulders above it. The renders `airship-pov-pilot.png`, `airship-pov-passenger.png` and
  `airship-pov-deck-side.png` show these views.
- Riders are seated because the players are drawn in vanilla's riding pose. Standing passengers would need
  the render of a riding player changed. That is outside this model.

## Animated parts and hooks

**Propellers.** `AirshipPropellerModel.setupAnim` calls `applyPropellerRotation(state.propellerRotation)`,
exactly as `PropellerModel` spins its propeller. The left one turns with the angle and the right one
against it, a counter-rotating pair. Nothing else is needed; the existing `propellerRotation` render state
drives it.

**Rudders and elevators.** They are in `AirshipMetalModel`, as two parts hinged at the fins' trailing edge,
local (0, -88, 104):

- `rudders` holds the top and bottom rudder and turns with `yRot`;
- `elevators` holds the left and right elevator and turns with `xRot`.

The hook is `public void applyControls(float rudder, float elevator)`. Both inputs are clamped to [-1, 1]
and scaled by `MAX_DEFLECTION` (0.4363 rad, 25°):

- `rudder > 0` swings the trailing edges to +X (the aircraft's left), which yaws the nose left;
- `elevator > 0` raises the trailing edges, which pitches the nose up.

`setupAnim` currently calls `applyControls(0, 0)`. To wire it:

1. add `float rudder` and `float elevator` (or whatever the physics calls them) to `PlaneRenderState`;
2. fill them in `PlaneRenderer.extractRenderState`, normalised to [-1, 1];
3. pass them in `AirshipMetalModel.setupAnim`.

The hook has to run after `super.setupAnim(state)`, which resets the pose. `airship-controls-deflected.png`
shows `applyControls(1, 1)`.

## Textures and layers

- **Material layer:** `LayerDefinition.create(mesh, 16, 16)`, tiling the plane's block texture 1 texel per
  pixel, as `PlaneModel` does. It covers the gondola hull, stepped bow and stern, the cabin roof, the pilot
  seat and the benches.
- **`airship_metal.png`**, 256x256 RGBA, is new: procedurally generated original work. It holds a packed box
  UV net for every metal and propeller cube:
  - window bands and the windscreen: dark varnished frames, with alpha-0 panes;
  - deck rails: a brass top rail and balusters with alpha-0 gaps;
  - the helm wheel: its spoke gaps are alpha 0;
  - the console with gauges;
  - the rubber landing wheel;
  - the olive engine cars, with louvres and an exhaust;
  - aluminium cowls with grilles;
  - steel struts and braces, and dark rigging cables;
  - the brass nose cap, mooring cone, tail cone and rub rails, and a steel mooring spike;
  - red control surfaces with a white band;
  - wooden propeller blades with brass tips, and brass spinners.

  The only transparent texels are the panes and gaps listed. `AirshipPropellerModel` uses the same file,
  so pass `airship_metal.png` as `propellerTexture` too.
- **`airship_envelope.png`**, 32x32 RGBA, is new: a tiling light warm-grey doped fabric with faint seams
  every 16 px. `AirshipEnvelopeModel` declares 32x32, so its large boxes repeat it at 1 texel per pixel.
  This works the same way the material layer repeats a block texture. The texture is light and neutral on
  purpose, so a tint colour could dye it later.
- All four layers use the default `EntityModel` render type, `RenderTypes::entityCutout`: alpha cutout,
  with back faces culled. Do not switch any of them to a no-cull type. The window bands, rails and helm rely
  on it to show one face at a time. The first-person views also rely on it: the eye is inside the cabin,
  so the cabin's walls and roof are seen from behind there.

### The fourth layer: what `PlaneRenderer` would need

The envelope has its own tiling texture, so it cannot share the metal atlas. At 1 texel per pixel its
largest boxes need UV nets of about 540 x 280 texels each; a shared atlas would have to be 1024x1024 or
more. So it is a model of its own, and `PlaneRenderer` draws three. The smallest change:

1. Add two optional fields, `EntityModel<PlaneRenderState> envelopeModel` and `Identifier envelopeTexture`.
   Set them through a second constructor, or a small `AirshipRenderer extends PlaneRenderer<...>`. Leave
   them null for every other aircraft.
2. In `submit()`, right after the three existing `submitModel` calls and under the same pose, add:
   ```java
   if (this.envelopeModel != null) {
       collector.submitModel(this.envelopeModel, state, poseStack,
               this.envelopeModel.renderType(this.envelopeTexture),
               state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor, null);
   }
   ```
   For a dyed envelope, use the `submitModel` overload that also takes a tint colour.
3. Register a fourth `ModelLayerLocation` (`envelope`) in `PlanesModelLayers` and bake it with the other
   three.

**If a fourth layer is not wanted**, there is a zero-change fallback:

- put `AirshipEnvelopeModel` in the **metal** slot with `airship_envelope.png`;
- move the parts of `AirshipMetalModel` into `AirshipPropellerModel`, which already uses the same atlas, and
  move the `applyControls` hook with them.

The metal and propeller models share one texture, so the move needs no texture changes. The class names
would then no longer match their slots.

## What the size implies for rendering

- **Frustum culling.** `EntityRenderer.shouldRender` culls by `getBoundingBoxForCulling(entity)`, which is
  the hitbox. With the suggested 3 x 2.5 box, the airship disappears whenever the gondola leaves the
  screen. That happens, for example, when you look up at the envelope from underneath, or stand beside
  the tail. The airship's renderer should override the protected
  `getBoundingBoxForCulling(T)` and return a box that holds the whole model at any rotation:
  - `entity.getBoundingBox().inflate(9.5, 9.5, 9.5)`: the farthest point, the mooring spike, is about
    10.5 blocks from the rotation pivot;
  - or, if it only ever yaws, `AABB(x - 9.5, y, z - 9.5, x + 9.5, y + 9, z + 9.5)`.
- **Render distance.** `Entity.shouldRenderAtSqrDistance` scales the maximum distance by the hitbox size
  (`getSize() * 64 * viewScale`). With 3 x 2.5 that is about 175 blocks at view scale 1, so the hitbox, not
  the model, sets it. Override it in the entity if the airship should be visible farther away.
- **Entity tracking.** The aircraft tracking range is 10 chunks (160 blocks, see `SimplePlanesEntities`).
  Beyond that the client removes the entity whatever its size. A large, slow airship is the one aircraft
  that players will watch from far away. The class note there explains why 10 chunks is the useful maximum
  on a default server.
- **Upgrades.** `UpgradesModels.modelFor`/`textureFor`/`submitSeats` send any unknown entity type to the
  helicopter variants. Before upgrades are allowed on the airship, it needs its own branch there.
- **Cost.** 123 cubes over four submits, all opaque cutout. That is less than half the cargo plane.

## Parts

`AirshipModel` > `Airship`:

- `Hull`: the hull bottom and floor; a narrower keel; side walls; a three-step bow (`bow_1` to `bow_3`);
  the stern wall and stern step.
- `Cabin`: a two-step roof over the front of the gondola.
- `Seats`: the pilot seat and back; three benches with backrests.

`AirshipMetalModel` > `Metal`:

- `Windows`: the two side window bands and the windscreen.
- `Controls`: the console and helm.
- `Rails`: the side and stern deck rails.
- `Trim`: the brass rub rails.
- `Gear`: the landing wheel.
- `Engines`: for each side, the engine car, cowl, tail and outrigger, plus `brace_left`/`brace_right`
  from the engine car up to the envelope.
- `Rigging`: vertical struts from the roof and the stern to the envelope, plus four suspension cables
  (`cable_fore_*`, `cable_aft_*`).
- `rudders`, `elevators`: the hinged control surfaces.
- `Nose`: the cap, mooring cone and spike.
- `Tail`: the tail cone.

`AirshipEnvelopeModel` > `Envelope`:

- `Gasbag`: 45 nested boxes centred on the axis, in 11 rings. Each ring is a stepped circle of three
  boxes (small rings) or five boxes (large rings) with their outer corners on a teardrop body of
  revolution. In each ring the inner boxes are 1 to 2 px longer than the outer ones: that bevels the ring
  ends, and no two boxes share a visible face plane (checked).
- `Fins`: four fins, each in four swept steps.

## Not checked in game

The model has only been rendered outside the game. The real `LayerDefinition`s were baked and the
`ModelPart`s walked under the same `PoseStack` transforms that `submit()` uses. The reference renders
approximate Minecraft's entity face shading: ambient 0.4 plus two fixed lights. Check the lighting, the
tiling of `airship_envelope.png`, the first-person view with a real player and the rider placement in game.
