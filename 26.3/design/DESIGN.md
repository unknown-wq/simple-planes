# Five new aircraft for Simple Planes 26.3: physics and architecture

This is the design the implementation specs in `specs/` are written from. It is written against the
`26.3/` tree on branch `claude/aircraft-physics-26.3` (base `origin/26.3`, commit `3518e05`) and the
render models committed on `claude/fighter-render-model` (commit `c7061b4` and earlier). Every number
below either comes with the calculation that produced it or is marked as a tuning start value with a
range. The calculations were run as small tick-for-tick simulations (Python) of the mod's own flight
model; their results are in §11.

Units throughout: **blocks (b), ticks (t), degrees**. 20 ticks = 1 s. Speeds are b/t; multiply by 20
for b/s. Accelerations are b/t².

---

## 0. Summary

| aircraft | class | family | headline numbers |
|---|---|---|---|
| Fighter | `FighterEntity extends PlaneEntity` | fixed-wing | cruise 2.30 b/t (46 b/s) at throttle 5, 3.1x the starter plane; take-off 0.45 b/t after a 12-block run; realised turn 58 deg/s, pitch 7 deg/tick, roll 8 deg/tick |
| Mini airliner | `AirlinerEntity extends PlaneEntity` | fixed-wing | 22 seats (2 crew, 20 passengers, boarded by where you click; AIRLINER-MODEL.md); cruise 1.25 b/t (25 b/s); take-off 0.60 b/t, rotation at 34 blocks, airborne at 51 blocks; realised turn 10 deg/s; ground pitch clamped at 12 deg (tail strike at 14.5 deg); metal skin by material tag, 6 logos rolled on placement |
| Airship | `AirshipEntity extends PlaneEntity` (all six flight hooks overridden, like the helicopter) | plane family, own physics | 7 seats; buoyancy + ballast trim, fly-by-wire altitude hold (captures with 0.08 b overshoot); cruise 0.81 b/t (16 b/s); 12 deg/s turn, radius 77 b; static climb/sink limit 0.2 b/t |
| Quadcopter crane | `QuadcopterEntity extends Entity` (new family) | multirotor, server-flown | thrust-to-weight 8.0; position controller settles a 20-block move in 4.5 s empty, 6.3 s with a cow; rope 1..12 b, winch 0.15 b/t; load mass estimated from the mob's box and knockback resistance, lift performance falls with it (chicken barely felt, horse at half capacity, iron golem hovers 0.5 b off the ground, ravager refused; CRANE-MASS.md); carried mob is a passenger placed at the rope end, rope drawn with vanilla's leash renderer |
| Mini helicopter | `MiniHelicopterEntity extends HelicopterEntity` | rotorcraft (existing model, smaller numbers) | one seat; hover at notch 2 (helicopter: 3), climb to +0.40 b/t at notch 5 (helicopter +0.24), level top speed 0.75 b/t (helicopter 1.11), pedal 90 deg/s, full cyclic in 8.6 ticks; thrust fades above y 100, absolute ceiling y 160; standard or medical livery by material tag |

Five implementation agents: one foundation agent that lands every shared touch point first, then four
aircraft agents in parallel on disjoint files; the mini helicopter goes with the airship agent. See
`PLAN.md`.

---

## 1. Ground truth, verified on 26.3

These were checked in the 26.3 jar (`~/.gradle/caches/fabric-loom/26.3/minecraft-merged.jar`, with
`javap`) and in the `26.3/` sources, not taken from the 26.2 documents.

### 1.1 Culling render types

`RenderPipelines.ENTITY_CUTOUT` (`pipeline/entity_cutout`) is built with `withCull(false)`
(bytecode: `iconst_0; invokevirtual withCull`). `ENTITY_CUTOUT_CULL` has no `withCull` call, so it
keeps the snippet default, which culls. `RenderTypes.entityCutout(Identifier)` and
`RenderTypes.entityCutoutCull(Identifier)` both exist. The `EntityModel(ModelPart, Function<Identifier,
RenderType>)` constructor exists. Consequences, unchanged from 26.2:

- layers that contain a rider's eye (fighter metal/canopy; airliner body, skin and metal) must be
  constructed with `super(root, RenderTypes::entityCutoutCull)`. The committed models on
  `claude/fighter-render-model` already do this (`baa1df5`, `c7061b4`);
- two-sided zero-thickness parts (drone rotors, drone payload fins, airliner fans, fighter flame, the
  airship's rails and helm with alpha gaps) stay on the default `entityCutout`;
- the airship's cabin has open (alpha 0) window panes, so its riders see out through cut-outs, not through
  culled back faces; all four airship layers stay on the default type as the contract says.

### 1.2 Renderer and render state (26.3 API)

- `EntityRenderer<T, S>`: `extractRenderState(T, S, float)`, `submit(S, PoseStack, SubmitNodeCollector,
  CameraRenderState)`, `protected AABB getBoundingBoxForCulling(T, float)`, `protected boolean
  affectedByCulling(T)`, `protected float shadowRadius`.
- `OrderedSubmitNodeCollector.submitModel(Model<? super S>, S, PoseStack, RenderType, int light, int
  overlay, int outlineColor)` (7-arg default overload; the 9-arg one adds `UvMapping` and tint) and
  `submitLeash(PoseStack, EntityRenderState.LeashState)`.
- `EntityRenderState` has public `List<LeashState> leashStates`; `EntityRenderer.submit` iterates it and
  calls `submitLeash` for each entry. `LeashState` has public fields `offset`, `start`, `end`
  (`Vec3`), `startBlockLight`, `endBlockLight`, `startSkyLight`, `endSkyLight`, `slack`. Vanilla fills
  them in `extractRenderState` only for `Leashable` entities; a renderer may fill them itself after
  `super.extractRenderState(...)`. That is the mixin-free rope renderer for the crane (§6.8).
- Hooks run from `setupAnim` at draw time because `submitModel` only queues the model and
  `ModelFeatureRenderer` calls `setupAnim(state)` before drawing; one model instance is shared by all
  entities of a type. Every animation input therefore travels through the render state.
- `PoseStack.rotate(Quaternionfc)` (26.3), not `mulPose`.

### 1.3 Entity API used by the design

- `Entity.getControllingPassenger()` decides authority: `isClientAuthoritative()` is
  `getControllingPassenger() instanceof LivingEntity l && l.isClientAuthoritative()`. An entity whose
  `getControllingPassenger()` returns null is simulated by the server, which is what an unmanned quadcopter
  and every autopilot aircraft rely on.
- `protected void positionRider(Entity, MoveFunction)`, `protected boolean canAddPassenger(Entity)`,
  `startRiding(Entity, boolean force, boolean)` (`startRiding(Entity)` calls it with `false, true`),
  `stopRiding()`, `ejectPassengers()`, `getDismountLocationForPassenger`.
- `Entity.shouldRenderAtSqrDistance(double)` is public and overridable; `getDimensions(Pose)` likewise.
- `Item.interactLivingEntity(ItemStack, Player, LivingEntity, InteractionHand)`, `Item.useOn(UseOnContext)`
  and `Item.use(Level, Player, InteractionHand)` exist: the crane remote needs no new packets and no
  Fabric event.
- `ServerChunkCache.addTicketWithRadius(TicketType.ENDER_PEARL, ChunkPos, int)` is what
  `PlaneAutopilot.keepChunksLoaded` uses (40-tick ticket, renewed every 5 ticks).
- `Leashable` exists and is richer than needed; the crane does **not** use it (its spring physics would
  fight the winch, and players are not `Leashable`). Only the render state's `LeashState` is used.

### 1.4 Facts about the existing mod that constrain the design

- `PlaneEntity.tick()` sets `push = 0.00625f * getThrottle()` and calls the hooks in the order
  `tickRotateMotion -> tickOnGround -> tickPitch -> tickYaw -> tickMotion -> tickRoll`, then `move()`,
  `PlaneCollisions.afterMove`, and folds `rotationRoll/xRot/yRot` deltas back into the quaternion as
  body-frame rotations. Thrust fades with speed: `pushVec *= clamp(1 - dot*speed/(maxPushSpeed*(push+0.05)), 0, 2)`
  with `maxPushSpeed = MAX_SPEED_data * 10`. Lift is an angular rate: the velocity vector is pitched up by
  `maxLift * liftRatio(speed) * d` degrees per tick (`d` = 1 when the nose is aligned with the velocity,
  0 at 60 deg off). Gravity is `-0.03` b/t².
- Control ramps are hard-coded: pitch `0.5*mult` per tick up to `5*mult`, yaw `0.5*mult` up to
  `2.5*mult`, roll `0.5` up to `5.0`, the velocity heading follows the nose at `0.1` per tick. The
  foundation adds hooks for the literal constants the new aircraft need (§8.3); defaults keep the existing
  aircraft bit-identical.
- `PlaneEntity.canAddPassenger` allows 1 rider, 3 with seats; `LargeAirframeEntity` allows up to 4 and
  its `getEntityYOffset` returns `-0.4` for players; both `LargeAirframeEntity` and `CargoPlaneEntity`
  mount any nearby non-player `LivingEntity` from `tick()`. None of the new aircraft wants that: the
  fighter, airliner and airship do not extend `LargeAirframeEntity`, and the mini helicopter, which
  inherits it through `HelicopterEntity`, refuses non-player passengers in `canAddPassenger`, which is
  what the magnet's `startRiding` call checks.
- `UpgradesModels.modelFor/textureFor/submitSeats` send every unknown `EntityType` to the helicopter
  variants. `PlaneCollisions.massOf` returns 1.0 for unknown types.
- `PlaneItem` requires `EntityType<? extends PlaneEntity>` and calls `loadFromItemTag`; the quadcopter,
  which is not a `PlaneEntity`, gets its own item.
- `AircraftType.of(plane)` returns null for unknown types and `tag()` then prints nothing: the autopilot
  ignores the new aircraft unless a type is added. The fighter and the airliner are added as
  non-random `AircraftType`s so `/autopilot route ... type fighter` can fly them (the flight director's
  gains are written against `getRotationSpeedMultiplier()`; the fighter's 1.4 and the airliner's 0.35 are
  inside the range it already handles, 0.2 to 1.2). This is optional test tooling, not a feature.
- The 26.3 tree has no access widener any more (`PORT-STATUS.md`, "Camera mixin re-aimed, access widener
  dropped") and one mixin, `CameraMixin`, which moves the first-person eye of a `PlaneEntity` rider.

### 1.5 The rules

**No mixins. Use the Fabric API and the vanilla 26.3 API. An access widener is allowed where needed;
add only the entries listed in the design (§9), or document any new one in your report. If something
appears impossible without a mixin, stop and report it instead.**

Nothing in this design needs a mixin. Nothing in it extends or depends on `CameraMixin`; its interactions
are listed in §10.

---

## 2. The baseline: the starter plane, as the simulation reads it

The simulation (`§11`, `fixedwing.py`) ports the longitudinal part of `PlaneEntity` tick for tick. It
reproduces the two published starter-plane numbers, which is the calibration check:

| quantity | published | simulated |
|---|---|---|
| level cruise, throttle 5 | 0.76 b/t (`PlaneCollisions.CRUISE_SPEED`) | 0.748 b/t |
| ground roll to 0.30 b/t, throttle 5 | 38 ticks (`tickOnGround` comment) | 38 ticks, 4.3 b |

Other starter numbers the new aircraft are measured against: airborne (y > 1) at 57 ticks and 11.6
blocks; climb 0.081 b/t at 10 deg nose-up, 0.143 at 20 deg; realised turn 1.72 deg/tick unbanked in the
simplified yaw model (the rig measured 2.065 with the autopilot's banked turns); radius 33 b at 1.0 b/t;
stall 0.165 b/t; glide ratio 3.6.

---

## 3. Fighter

### 3.1 Physics model

The fighter is the fixed-wing model with a jet's numbers: more thrust that fades later, more drag,
faster control ramps, a tricycle gear (level on the ground) and a tail-strike pitch clamp. State
variables are `PlaneEntity`'s own (`Q`, `xRot`, `yRot`, `rotationRoll`, `pitchSpeed`, `yawSpeed`,
`rollSpeed`, `deltaMovement`, `throttle`).

Per tick, with `v = |deltaMovement|`, `T = throttle` (0..5, 0..10 with a booster):

```
push          = PUSH_PER_NOTCH * T                       PUSH_PER_NOTCH = 0.012   (starter 0.00625)
fade          = clamp(1 - cos(angle(thrust, v)) * v / (10 * MAX_SPEED * (push + 0.05)), 0, 2)
                                                          MAX_SPEED (entity data) = 2.5  (starter 1.0)
drag          = 0.00145 v^2 + 0.0005 v + 0.001            (starter 0.001 / 0.0005 / 0.001), x5 at T = 0
lift (deg/t)  = 2.0 * liftRatio(v) * d                    liftRatio: 0 below 0.27, 1 above 0.585
velocity pitch lerps to the nose at 0.3 * d per tick      (pitchToMotion, starter 0.2)
velocity yaw   lerps to the nose at 0.2 per tick           (yawToMotion, new hook, starter 0.1)
pitch rate     ramps 0.7 deg/t^2 to 7.0 deg/t              (rotMult 1.4, times pitch authority)
yaw rate       ramps 0.7 deg/t^2 to 3.5 deg/t
roll rate      ramps 0.5 deg/t^2 to 8.0 deg/t              (maxRollRate, new hook, starter 5.0)
on the ground: xRot clamped to <= 10 deg, groundPitch 0, rolling resistance drag += 0.030,
               dragMul *= 24 (groundLinearFactor, new hook; stock 20*(3-friction) = 48 on grass)
```

Thrust fades to zero at `10 * 2.5 * (0.06 + 0.05) = 2.75 b/t` at throttle 5. Level cruise is where
`push * fade = drag`.

### 3.2 Numbers (simulated, §11.1)

| quantity | value | note |
|---|---|---|
| stall speed | 0.27 b/t (5.4 b/s) | `takeOffSpeed 0.45 * stallSpeedFactor 0.6` |
| take-off speed | 0.45 b/t (9.0 b/s) | rotation allowed above it (`tickOnGround` returns `speedingUp`) |
| ground roll | rotation at 20 t / 5.6 b, airborne at 32 t / 12.5 b at 0.76 b/t | full throttle; the starter takes 57 t / 11.6 b |
| taxi | throttle 2: 0.02 b/t, throttle 3: 0.19 b/t | throttle 2 creeps, 3 taxis at 4 b/s |
| level speed per throttle | T1 1.08, T2 1.46, T3 1.77, T4 2.04, T5 2.30 b/t | trim -1.4 to -4.8 deg nose-down |
| top speed | **2.30 b/t (46 b/s)**, 3.1x the starter | thrust/drag equilibrium, not a clamp; `maxSpeed` clamp stays at 3.0 |
| climb | 0.485 b/t (9.7 b/s) at 10 deg nose-up, 0.75 at 20 deg; best 1.09 at 40 deg | starter 0.081 / 0.143 |
| glide, throttle 0, nose 5 deg down | 0.43 b/t, sink 0.12 b/t, ratio 3.4 | |
| turn | nominal yaw 3.5 deg/t; realised 2.92 deg/t (58 deg/s), radius 20 b at 1.0 b/t, 39 b at 2.0 | starter 34 deg/s, 33 b at 1.0 |
| pitch / roll rates | 7 / 8 deg per tick (140 / 160 deg/s) | starter 5 / 5 |
| tail-strike angle | 12 deg | nozzle bottom at entity (y 0.375+0.375, z -3.5) about the pivot (0, 0.375, 0): sin θ = 0.75/3.5 |
| ground pitch clamp | 10 deg while `getOnGround()` | 2 deg margin |
| landing angle (`getLandingAngle`) | 30 (inherited) | |
| collision mass (`PlaneCollisions.massOf`) | 1.1 | heavier than the starter, lighter than the large plane |

The stall factor 0.6 (starter 0.55) and `liftSaturationFactor 1.3` are tuning start values (range 0.5 to
0.7, 1.2 to 1.5). All the drag and push numbers are start values; the equilibrium table above is what
to re-measure after any change.

### 3.3 Controls and hooks

Player input is unchanged: arrows for throttle and yaw, W/S for pitch, A/D roll. The exhaust nozzle
animates from `state.throttle = throttle / MAX_THROTTLE` (0..1; with a booster `min(1, throttle / 5)`,
so the afterburner shows from notch 5 up) via `FighterExhaustModel.applyThrottle`. `isPowered()` is
inherited: the fighter needs an engine upgrade or a creative pilot, like every plane.

Upgrades: engines, booster, armor, healing, folding, banner allowed; seats, shooter, floaty bedding,
and every large upgrade refused in `canAddUpgrade`. No upgrade model is drawn on the fighter
(`UpgradesModels` returns nothing for its type); the engine upgrade is functional but invisible. This is
an accepted limitation (open question Q4).

Seat: one, feet point `(0, 0.0625, 0.125)` (contract). The pilot's eye is inside `canopy_main`, whose
layer culls, so the pilot sees out.

---

## 4. Mini airliner

### 4.1 Physics model

Same fixed-wing model with heavy-aircraft numbers: modest thrust, low drag, a long ground roll from a
constant rolling resistance, slow control ramps, and a pitch clamp on the ground that keeps the tail off
the runway.

```
PUSH_PER_NOTCH = 0.004 (T5: 0.020);   MAX_SPEED data = 2.0  -> thrust fades out at 20*(0.02+0.05) = 1.4 b/t
drag = 0.0006 v^2 + 0.0003 v + 0.0005
takeOffSpeed 0.60; stallSpeedFactor 0.6 (stall 0.36); liftSaturationFactor 1.25 (saturates at 0.75)
maxLift 2.0; pitchToMotion 0.16; yawToMotion 0.06; motionToRotation 0.05 (inherited)
rotMult 0.35: pitch ramps 0.175 deg/t^2 to 1.75 deg/t; yaw to 0.875 deg/t; roll (maxRollRate) 2.5 deg/t
groundPitch 0; on the ground: drag += 0.007 (rolling), dragMul *= 5 (groundLinearFactor), xRot <= 12 deg
```

### 4.2 Numbers (simulated, §11.1)

| quantity | value |
|---|---|
| stall / take-off | 0.36 b/t (7.2 b/s) / 0.60 b/t (12 b/s) |
| ground roll, full throttle | rotation at 89 t / 34 b, airborne (y > 1) at 116 t / **51 b** at 0.70 b/t. Starter: 12 b, fighter: 12.5 b |
| taxi | T2 0.11 b/t (2 b/s), T3 0.40, T5 0.75 on the ground |
| level speed per throttle | T1 0.64 (nose 15 deg up, marginal), T2 0.91, T3 1.05, T4 1.16, **T5 1.25 b/t (25 b/s)** |
| climb | 0.166 b/t (3.3 b/s) at 10 deg, 0.19 at 20 deg |
| glide | 0.74 b/t, sink 0.145, ratio 5.0 |
| turn | nominal 0.875 deg/t; realised 0.49 deg/t (**10 deg/s**), radius 117 b at 1.0 b/t, 146 b at cruise |
| tail-strike angle | **14.5 deg** (keel end at entity y 0.8125, z -3.25 about the pivot; sin θ = (0.375+0.4375)/3.25); tail_1 15.4, tail_2 16.5, tail_3 18 |
| ground pitch clamp | 12 deg while `getOnGround()`; released when the coyote timer expires after lift-off |
| lift-off attitude | 12 deg at 0.70 b/t (the clamp is the rotation attitude; lift-off happens when the velocity vector's climb beats gravity's 2.45 deg/t at 0.70 b/t) |
| collision mass | 1.6 |

At throttle 3 the airliner reaches 0.40 b/t on the ground and cannot take off (it needs 4 or 5); that is
intended. Below `takeOffSpeed` the elevator is disabled by `tickOnGround` as on every plane.

### 4.3 Seats, skin and logo

- 22 seats (`entities/AirlinerSeats`): captain (seat 0, the pilot) and first officer side by side in the
  cockpit, feet at `(±0.6875, 0.6875, 3.75)`; 20 cabin seats in five rows two by two either side of the
  aisle, feet at `x = ±1.3125, ±0.5625`, `y = 0.5625`, `z = 2.5, 1.375, 0.25, -0.875, -2.0`. No seats upgrade
  is needed or accepted.
- A seat belongs to its rider: one synched int per seat holds the rider's entity id, assigned in
  `addPassenger`, cleared in `removePassenger`, saved as a `Seats` list of `{UUID, Seat}`. A player boards the
  seat nearest to where he clicked, the captain's when he clicks the cockpit or the nose; anyone else takes
  the front-most free cabin seat and never a crew seat. The controlling passenger is the player in the
  captain's seat, or nobody.
- The nose and tail are clickable through four `AirlinerPartEntity` hitboxes (`sized(3.4, 3.3)`, never saved)
  that follow the airliner; its own bounding box stays `sized(3.0, 2.6)`. Details, the options weighed and the
  test command `airliner click` are in AIRLINER-MODEL.md.
- The widened fuselage (3.625 wide, 22 seats) changes nothing in flight. Measured with one procedure on the
  5.4.0-beta.2 jar and on the cabin jar (`aircraft takeoff`; `hold`, throttle 5 and 1500 ticks; `launch 1.25`,
  `hold`, throttle 5, 300 ticks, then `set yaw 1`), both give: rotation at 33.8 b (90 t), airborne at 52.1 b
  (118 t) at 0.71 b/t; level speed 1.250 b/t at throttle 5; heading 53.0, 73.7, 94.7 deg at ticks 100, 140
  and 180 of the turn (0.52 deg/t). With 20 villagers aboard: airborne at 51.4 b (117 t) at 0.71 b/t.
- Metal skin: `metalSkin = material block is in the new block tag simpleplanes:airliner_metal_skin`
  (`iron_block`, `copper_block`, `waxed_copper_block`, `gold_block`, `netherite_block`; the same blocks
  are added to `simpleplanes:plane_materials` so the workbench builds one). The renderer draws
  `AirlinerSkinModel` with `AirlinerSkinModel.TEXTURE` instead of `AirlinerModel` with the block texture.
- Logo: `int LOGO` synched, rolled with `random.nextInt(AirlinerEntity.LOGO_COUNT)` (6) the first time the
  entity is added to a level from an item without a `Logo` tag, saved as `Logo`, copied into
  `state.airlinerLogo` and applied with `AirlinerMetalModel.setLogo` in `setupAnim`. The item keeps the
  logo through `getItemStack()` (it calls `addAdditionalSaveData`), so a folded airliner keeps its airline.

Upgrades: engines, booster, armor, healing, folding, banner; seats and shooter refused; no upgrade
model drawn.

---

## 5. Airship

### 5.1 Why it is in the plane family

The airship's controls map one-to-one onto the inputs `PlaneEntity` already synchronises and the
client already sends: throttle (arrows up/down) is engine thrust, `YAW_RIGHT` (arrows left/right) is the
rudder, `PITCH_UP` (W/S) is the elevator. Rendering (`PlaneRenderer`), interpolation, health and damage,
upgrades, the item, persistence and `PlaneCollisions` are inherited unchanged. What is different is the
force model, and the helicopter already shows how to replace exactly that: override all six flight hooks
so not one line of the fixed-wing model runs. So `AirshipEntity extends PlaneEntity` with the helicopter's
pattern, and not `LargeAirframeEntity`: the livestock magnet and the large-upgrade bay are wrong for a
passenger gondola, and its `-0.4` player offset would sink the riders into the hull.

### 5.2 Physics model (mass normalised; every force is an acceleration)

State: `deltaMovement`, `yaw`, `pitch` (hull, cosmetic and dynamic lift), `rotationRoll` (kept at 0 in
flight; a small roll `-0.3 * yawSpeed` deg is optional cosmetics), `trim` in [-1, +1] (ballast: -1 all
ballast dumped, +1 full), `trimInt` (the hold loop's integrator), `holdY` (captured altitude), `yawSpeed`.

```
G                = 0.03                                    (plane family gravity; buoyancy at neutral = +0.03)
static           = -BALLAST_RANGE * trim - RIDER_WEIGHT * riders
                   BALLAST_RANGE = 0.006 (20 % of the weight), RIDER_WEIGHT = 0.0006 per passenger (2 %)
engine           = PUSH_PER_NOTCH * throttle = 0.004 * T along the hull axis (pitch, yaw)
horizontal drag  = 0.025 vh^2 + 0.004 vh + 0.0002          -> cruise 0.81 b/t at T5
vertical drag    = (0.05 |vy| + 0.02) vy                   -> static terminal +-0.20 b/t at full trim
dynamic lift     = 0.06 * vh^2 * pitch_rad                 (hull incidence; small)
velocity heading lerps toward the hull heading at 0.03 per tick (sideslip)
yaw              ramps 0.04 deg/t^2 to 0.6 deg/t (12 deg/s); rudder is the sign of YAW_RIGHT
hull pitch       follows the vertical command: pitch_cmd = 10 deg * vs_cmd / 0.12, slewed 0.5 deg/t
```

**Vertical command and altitude hold (fly-by-wire).** The elevator commands a vertical speed, not a
pitch:

```
elevator held:   vs_cmd = +-0.12 b/t (2.4 b/s);  holdY = y + 30 * vy   (predicted stop point, so the
                 capture has no overshoot)
elevator free:   vs_cmd = clamp(0.01 * (holdY - y), +-0.12)
error e          = vs_cmd - vy
trimInt         -= 0.10 * e   (clamped to [-1, 1])
trim            -> clamp(trimInt - 8.0 * e, -1, 1), slewed at most 0.02 per tick (full range in 5 s)
```

The trim loop is a PI on vertical speed; the outer altitude loop is proportional with gain 0.01. The
combination settles in about 220 ticks (11 s) and captured a 0.12 b/t climb with **0.08 b overshoot** in
the simulation; seven riders boarding (+14 % weight) sags the ship 3 b and the hold recovers to 0.1 b.
Doubling the integral gain adds 14 % overshoot, quadrupling it 21 %: the loop has margin. Vertical drag
supplies the damping (`V_DRAG_LIN 0.02`), which is why the gains are as low as they are.

**Ground handling.** On the ground with throttle 0 the hold is off and the trim goes to +1 (ballast
taken on: the ship stays moored; nothing in the model blows it away, but neutral buoyancy would let a
nudge float it off). Elevator up lifts off. The wheel touches `y = 0`; hull pitch is forced to 0 within
1 block of the ground. `getLandingAngle` is 15 (a gondola on its wheel is not a plane on its belly).

**Space bar.** Unused by the airship (`MoveHeliUpPacket` only reaches `HelicopterEntity`). Reserved.

### 5.3 Numbers (simulated, §11.2)

| quantity | value |
|---|---|
| static climb / sink at full trim, engine off | +-0.20 b/t (4 b/s) |
| commanded climb (elevator held) | 0.118 b/t (2.4 b/s), peak; no overshoot of the 0.12 command |
| altitude capture | 0.08 b overshoot; settled within 0.5 b after ~220 t |
| cruise per throttle | T1 0.32, T2 0.48, T3 0.61, T4 0.72, **T5 0.81 b/t (16 b/s)** |
| turn | nose 0.6 deg/t, track follows at 0.6 deg/t after the sideslip settles: **12 deg/s**, radius 77 b at 0.81 b/t |
| descent from 60 b to the ground, elevator down | 27 s at 0.12 b/t; touchdown sink 0.12 b/t |
| pitch / roll limits | +-10 / +-8 deg in flight, 0 on the ground |
| collision mass | 2.0; scrape and vertical tolerances are the inherited ones (touchdown at 0.12 b/t is far below `V_TOLERANCE_MIN` 0.20) |

Rider weight makes the hold visibly work when a crowd boards, and it bounds the trim: 7 riders need 0.14
of the 0.2 range, leaving 0.06 (= 0.0036 b/t², terminal 0.12 b/t) of static climb with a full gondola.
That is intentional: a full airship climbs slowly.

### 5.4 Rendering

- Hitbox `sized(3.0F, 2.5F)`: the gondola. The envelope is not solid (contract option 1). The physics
  keeps the envelope out of terrain with a clearance probe: the hull top is 8.5 b above the entity, so
  the altitude hold refuses `holdY` below `heightmap + 9` under the envelope's footprint (five columns:
  centre and +-4 b along the hull axis, +-2.5 b across) and the ground-approach logic lands the wheel only
  where those columns are clear. This is not a collision; it is what stops the envelope visibly
  intersecting a hillside.
- Culling box: `AirshipRenderer.getBoundingBoxForCulling(entity, partial)` returns
  `entity.getBoundingBox().inflate(9.5, 0, 9.5).expandTowards(0, 9, 0)`; `AirshipEntity.shouldRenderAtSqrDistance`
  uses `getBbWidth()` of 17.5 in vanilla's formula (`d < (17.5 * 64 * viewScale)^2`).
- Fourth layer: `AirshipRenderer extends PlaneRenderer<AirshipEntity>` adds an `envelopeModel` and
  `envelopeTexture` and submits it after the three standard layers under the same pose. Per-type
  translate `(0, -0.025, 0)`. `state.rudder = yawRight` (-1/0/+1) and `state.elevator = pitchUp`
  (-1/0/+1) drive `AirshipMetalModel.applyControls`; the propellers spin from `propellerRotation`.
  Shadow radius 2.5.
- Seven seats from the contract, `y = 0.1875` as is (no player offset). Tracking range stays 10 chunks
  (the class note in `SimplePlanesEntities` explains why more buys nothing on a default server).

Upgrades: engines (the furnace engine model would draw in a helicopter position; nothing is drawn),
healing, armor, banner, folding allowed; booster, seats, shooter, floaty bedding refused.

---

## 5b. Mini helicopter

### 5b.1 What it is and where it sits

The smallest and simplest aircraft: a one-seat bubble helicopter 3.5 b long with a 3.375 b rotor (half
the existing helicopter's), 27 cubes, in a standard (block material) or a medical (white air-ambulance)
livery. Contract: `MINI-HELI-MODEL.md` on `claude/fighter-render-model` (`fb218f6`); the front is being
reworked visually, the contract numbers stay. It flies the existing helicopter's model with smaller,
sharper numbers, so it is `MiniHelicopterEntity extends HelicopterEntity`, as the contract suggests, with
exactly one rider and no cargo. `HelicopterEntity` reads its tuning from `public static final`
constants inline; the foundation turns each into a protected instance getter with the constant as its
default (behaviour-preserving, the constants stay for `CollectiveHover`, `HelicopterAutopilot` and the
client, which reference `CYCLIC_FULL`, `HOVER_THROTTLE`, `MAX_SPEED`, `TURN_COORDINATION_SPEED`,
`YAW_RAMP`), and the mini helicopter overrides the getters.

### 5b.2 Flight model: the helicopter's, with these numbers

| getter (new, in `HelicopterEntity`) | helicopter (default) | mini helicopter | effect |
|---|---|---|---|
| `collectivePerNotch()` | 0.010 | **0.015** | hover at notch 2 instead of 3: lighter, more thrust in hand |
| `rotorInflowLimit()` | 2.0 | 1.6 | climb saturates sooner (small disc) |
| `maxCyclic()` | 25 deg | **30 deg** | tips further |
| `maxCyclicRate()` | 2.0 deg/t | **3.5 deg/t** | full tilt in 8.6 t instead of 12.5: twitchy |
| `maxYawRate()` / `yawRamp()` | 3.0 / 0.5 | **4.5 / 1.0** deg/t, deg/t^2 | 90 deg/s pedal, faster ramp |
| `turnFromBank()` | 2.6 | 3.0 | |
| `hDragQuad()/hDragLin()/hDragConst()` | 0.009 / 0.0025 / 0.0002 | **0.025 / 0.004 / 0.0003** | top speed down to 0.75 b/t |
| `vDragQuad()/vDragLin()` | 0.045 / 0.050 | 0.045 / 0.050 | descent ladder unchanged |
| `maxSpeedBackstop()` | 2.0 | 1.5 | backstop only |
| `groundFriction()` | 0.25 | 0.30 | skids |
| `ceilingThrustFactor(y)` | 1.0 | `clamp(1 - (y - 100) / 100, 0.4, 1)` | thrust fades above y 100 (world y, not agl) |

`rotorThrust` multiplies by `ceilingThrustFactor(getY())`. Everything else (turn coordination,
velocity alignment, the dead-machine behaviour, `applyYaw`'s attitude correction) is inherited.

### 5b.3 Numbers (computed on `HelicopterEntity`'s own equations, §11.4)

| quantity | helicopter | mini helicopter |
|---|---|---|
| vertical equilibrium per notch 0..5 | -0.43 / -0.31 / -0.17 / 0 / +0.13 / +0.24 b/t | -0.43 / -0.25 / **0** / +0.18 / +0.30 / **+0.40 b/t (8 b/s)** |
| level top speed at full cyclic (collective trimmed) | 1.11 b/t (22 b/s) at 3.3 notches | **0.75 b/t (15 b/s)** at 2.3 notches |
| vertical margin at 5 notches and full tilt | +0.015 | +0.035 (it can climb at full tilt) |
| pedal | 60 deg/s | 90 deg/s |
| ceiling | none | hover needs notch 3 at y 130, notch 4 at y 150, notch 5 at y 160: **absolute ceiling y 160**; on the superflat rig that is 220 b above the ground |
| collision mass | 1.15 | 0.8 |
| payload | up to 3 riders with seats, one large upgrade, livestock | one rider, no large upgrade, no payload rack, no livestock |

The ceiling is the one new mechanism; it is a linear fade of thrust with world height and costs one
multiplication. It gives the mini helicopter something the big one lacks (a reason not to fly over
mountains) without touching the big one (its factor is 1.0).

### 5b.4 Seat, livery, rendering

- Seat: feet at `(0, 0.0, 0.625)` in the contract; implemented with the pivot-corrected form the contract
  gives, `transformPos(new Vector3f(0, seatY - 0.375f, 0.625f)).add(0, 0.375f, 0)` with `seatY =
  getPassengersRidingOffset() + getEntityYOffset(passenger)` and `getPassengersRidingOffset() = 0.4f`
  (the `LargeAirframeEntity` player offset of -0.4 applies).
- `canAddPassenger`: exactly one, and only a `Player` (which also disables `LargeAirframeEntity`'s
  livestock magnet, since it goes through `startRiding`); `tryToAddUpgrade` refuses large upgrades and
  payload entries before calling `super`; `acceptsUpgrade` refuses seats, shooter, floaty bedding.
- Livery: `hasMedicalLivery()` is true when the material block is in the new block tag
  `simpleplanes:mini_heli_medical` (`white_wool`, `white_concrete`, `quartz_block`, `smooth_quartz`; the same
  blocks are added to `plane_materials`). Same rule as the airliner skin (Q6 applies to both). The renderer
  (`MiniHeliRenderer extends PlaneRenderer`) uses `bodyModel/bodyTexture` with `state.medicalLivery`.
- Per-type translate `(0, -0.025, -0.25)`; hitbox `sized(1.5F, 1.95F)`; shadow 0.5; rotors from
  `propellerRotation`; damage wobble scaled 0.6 for this size (a `wobbleScale(entityType)` in the renderer:
  1.0 default, 0.6 mini helicopter).
- `UpgradesModels`: no upgrade visuals for this type (the helicopter's would float around it).
- Not an `AircraftType`; `HelicopterAutopilot` is not asked to fly it (its `RotorcraftConfig` is written
  for the big airframe). `CollectiveHover` would work on it (it searches the ladder from `HOVER_THROTTLE`),
  but the gunship spawns a `HelicopterEntity` and is untouched.

---

## 6. Quadcopter crane

### 6.1 What it is

A small, unmanned, server-flown multirotor that a player directs with a **crane remote** item: point it
at a mob to have it picked up, at a block to have the load carried there and set down, sneak-use to
recall. A console command tree (`/crane`) drives the same controller headlessly for the tests. It has no
seat, no weapons and no manual stick flying in this scope (open question Q1). It picks up a cow, a sheep,
a villager or a player on a rope and hook, winches it up, flies it, lowers it and lets go.

### 6.2 Why it is its own class of entity

Its physics is a rigid body under a thrust vector with a slung load; nothing in `PlaneEntity`'s tick
(fixed-wing hooks, client authority, controlling passenger, ground roll, wings) applies, and the plumbing
it would inherit (upgrades, seats, `PlaneCollisions`, autopilot, the item) is either wrong or unwanted.
So `QuadcopterEntity extends Entity` directly, with three plain classes that hold the physics and are
compiled and tested with `javac` and a `main` method, without Minecraft on the classpath:

- `MultirotorPhysics` (state integration, §6.3),
- `CraneController` (position and attitude loops, §6.4),
- `SlungLoad` (rope, winch and pendulum, §6.5).

The entity owns: health and damage (`hurtServer`), material (frame layer texture, synched string),
synched attitude quaternion `Q` plus `Q_Prev/Q_Client` for interpolation (same pattern as `PlaneEntity`),
`LinearInterpolationHandler.create(this, 10)` via `createInterpolationHandler()`, save/load, the crane
state machine, the passenger (the load) and its `positionRider`, and a rolling chunk ticket while it flies.
`getControllingPassenger()` returns null always, so the server is always authoritative and a carried
player cannot steer it.

### 6.3 Multirotor dynamics

Body frame: `up` is the rotor axis; attitude = yaw ψ, pitch θ (nose down positive = accelerates forward),
roll φ (right down positive = accelerates right). Mass unit: 1 = the empty drone.

```
G       = 0.04 b/t^2      (this airframe's gravity; the plane family uses 0.03, vanilla mobs 0.08.
                           0.04 gives a slung load's pendulum at 6 b a period of 77 t = 3.9 s, which reads
                           naturally; a released mob falls under vanilla gravity anyway)
T_MAX   = 8.0 * G = 0.32  (thrust-to-weight 8.0 empty; rated, `tMaxBase`. The effective ceiling `tMax`
                           adds the ground assist of 6.6 while a load hangs near the ground)
thrust vector    = T * up(ψ, θ, φ)
drag             = -(0.02 + 0.01 |vh|) vh   horizontally, -0.05 vy vertically
attitude         : commanded (θc, φc) reached through a first-order lag τ = 2 ticks, slewed <= 4 deg/t,
                   |tilt| <= 25 deg   (the inner attitude loop, assumed fast; a 20 Hz tick cannot resolve
                   the real 1 kHz rate loop, so it is modelled as this lag)
yaw              : ψ rate <= 6 deg/t (120 deg/s) toward the commanded heading, slewed 1 deg/t^2
                   (differential rotor torque; cosmetic for the crane, it faces its direction of travel)
rotor animation  : propellerRotation += 0.6 + 4.0 * T / (3 G) per tick (client; the old T_MAX, so the
                   empty-hover spin is unchanged by the 8 G rating)
```

Integration per tick: `v += thrust/m_total + drag + rope reaction - G; p += v`, then `move(MoverType.SELF)`
for terrain collision. Rope reaction is what the load pulls on the drone (§6.5). `m_total = 1 + m_load`.

### 6.4 Controller (20 Hz)

Two cascaded loops per horizontal axis plus a vertical pair, all in world axes, run on the server every
tick before the physics:

```
position loop:   v_cmd  = clamp(K_POS * (target - p), +-V_MAX)         K_POS 0.05 /t,  V_MAX 0.8 b/t
swing damping:   v_cmd += k_s * (v_load - v_drone)_horizontal          k_s = -0.4 * min(1, m_load / 0.65)
velocity loop:   a_cmd  = clamp(K_VEL * (v_cmd - v), +-A_MAX)          K_VEL 0.15 /t
                 A_MAX  = 0.018 empty (tilt atan(0.018/0.04) = 24 deg); 0.010 * min(1, L/4) loaded
vertical:        vz_cmd = clamp(K_ALT * (target_y - y), +-0.30);  az = K_VZ * (vz_cmd - vy)   K_ALT 0.05, K_VZ 0.2
attitude cmd:    tilt = atan2(a_cmd_h, G + az), clamped 25 deg; direction of a_cmd_h in yaw
thrust:          T = m_total * (G + az) / cos(tilt), clamped to [0, T_MAX]
```

Sign of the swing term: the drone accelerates **against** the load's velocity relative to itself. The
naive "move with the load" law (positive gain) makes the drone track the load and removes the relative
motion that dissipates energy; the simulation shows it destabilising at every gain tried, while the
negative law kills a 20-degree swing with a cow from 20 to 1.2 deg in 5 s (positive 0.4: 25 deg and
growing). The gain scales down for light loads because they barely couple to the drone.

Simulated performance (§11.3): a 20-block step settles (within 0.5 b) in 88 t empty with no overshoot;
116 t with a cow on a 6 b rope with 0.63 b overshoot, peak swing 25 deg and **no residual swing** after 15
s; a 20-block vertical step in 105 t with no overshoot at 0.27 b/t peak. Margins: all gains x1.5 is fine
(1.7 b overshoot), x2 is degraded (3.5 b, 2.4 deg residual), x4 is unstable; an attitude lag of 6 ticks
instead of 2 changes nothing visible. Stable at 20 Hz with a factor of about 2 in hand.

### 6.5 The crane: rope, winch, attachment, pendulum

```
rope length L    : 1.0 (stowed, hook under the clamp) .. 12.0 b, winch rate 0.15 b/t (3 b/s) both ways
winch point      : entity (0, 0.30, 0), the underside of the clamp servo
load position    : p_drone + winch + L * (sin θx, -cos θ, sin θz)   with θ the rope angle from vertical
load mass        : m = w^2 * h * (1 + KBR) * tag multiplier (drone masses), clamped >= 0.05; w, h the
                   current bounding box, KBR the knockback-resistance attribute clamped 0..1 (6.6)
                   chicken 0.11, villager/zombie 0.70, sheep 1.05, cow 1.13, llama 1.51, spider 1.76,
                   horse 3.12, iron golem 10.58, ravager 14.64 (over), hoglin 17.47 (tag x4, over)
capacity         : 6.20 = 0.9 * T_MAX / G - 1, hovers anywhere; lift limit 11.96 with full ground assist
```

**Pendulum.** In 2-D per axis, with pivot acceleration `a_p` (the drone's) and damping `c`:

```
θ'' = -(G / L) sin θ - (a_p / L) cos θ - c θ'        c = 0.002 (air drag on the load; real cows are draggy
                                                     but not that draggy: 0.16 m/s^2 at 10 m/s)
```

integrated explicitly each tick (`ω += θ''; θ += ω`), one angle per horizontal axis, small-angle
coupling ignored. The rope reaction on the drone is the tension's horizontal component
`m * (G cos θ + L ω^2) * sin θ / m_total` opposing the swing, plus the load's weight vertically; the
simulation used the exact two-body rigid-rope equations and the controller gains above were tuned on
those, so an implementer who uses the simpler split model should expect a few percent difference, not a
different behaviour. Period at L = 6: `2π sqrt(L/G)` = 77 t.

**Attaching** (server): the target becomes a passenger (`target.startRiding(drone, true, true)`) and the
drone's `positionRider` places it at the load position every tick on both sides (the client computes the
same position from synched `L`, `θx`, `θz`), so the load moves smoothly and is rendered by vanilla with
no extra sync. The drone accepts one passenger, only through the hook (`canAddPassenger` is false for
anything not pre-approved by the crane state machine). While riding, the mob's own movement is inert
(vanilla), which is what a hooked animal should do. A carried **player** may let go by sneaking
(vanilla dismount): `getDismountLocationForPassenger` returns the load position, so they drop from where
they hang. Baby animals ride at the same point (their box is smaller; the rope end is the box top).

Pickup sequence (state machine in `QuadcopterEntity`, states saved by name):
`IDLE -> TO_PICKUP (hover 1.5 + L_pick above the target, L_pick = 4) -> LOWER (winch out until the hook is
within 0.6 b of the target's box top, 8 s timeout) -> ATTACH (hook closes: setPayloadAttached true) ->
WINCH_IN (to L_CARRY = 3.0; the winch is refused above the load limit, see 6.7) -> CARRY (climb to
cruise height agl 10 + L, transit at 0.8 b/t) -> LOWER_LOAD (over the drop point: descend until the load's
box bottom is 0.2 b above the heightmap, then winch out) -> RELEASE (stopRiding; jaws open) -> STOW
(winch to 1.0) -> IDLE/RETURN`.

**Mobs allowed.** `LivingEntity` that is alive, hostile (`Enemy`) or not, not a boss (ender dragon,
wither, warden, elder guardian, or tag `c:bosses`), not another vehicle's passenger or vehicle, not a
`PlaneEntity`/`QuadcopterEntity`, not in tag `simpleplanes:crane_never`, and `m <=` the lift limit (11.96); players
allowed unless in spectator mode. `QuadcopterEntity.ALLOW_HOSTILES` (true) is the switch back to the old
rule, under which `simpleplanes:crane_liftable` is the allow-list for hostiles. Every refusal names its
reason: `cannot lift <name>: boss|aircraft|spectator|riding <vehicle>|has a rider|dead|not a mob|in tag
simpleplanes:crane_never|hostile` or `too heavy: <name> ≈ m, max 11.96 (p% of capacity)`. Under the mass rule the ravager, hoglin and zoglin
(tag `crane_mass_x4`), ghast and happy ghast stay out; the iron golem, size-4 slimes and magma cubes are lifted
but only just off the ground (6.6). `simpleplanes:crane_liftable` also exempts a type from `c:bosses`
(the four vanilla bosses always stay refused); mass still applies to it.

**A slung mob does not fight** (Q8, answered). While a `Mob` is the crane's passenger it carries a transient
`FOLLOW_RANGE` modifier `simpleplanes:crane_slung` (x0, added in `addPassenger`, removed in
`removePassenger`, never saved), so target goals neither find nor keep a target; and every crane tick,
which runs before the passenger's own tick, clears its target and `ATTACK_TARGET` memory, stops its
navigation, and runs a creeper's fuse back down. Nothing else is stored on the mob, so its AI resumes as
soon as it leaves the hook by any path (release, overload, crane destroyed or killed, teleport). A creeper
lit with flint and steel still explodes; that explosion damages the crane (the passenger exemption in
`hurtServer` does not cover explosions). Read in the 26.3 sources, not tested: an arrow cannot hit the crane
while its shooter is on it (`Projectile.canHitEntity`), and a passenger does not despawn
(`Mob.requiresCustomPersistence`). Tested: endermen do not teleport while riding (vanilla `Enderman`), undead
still burn in daylight (`load lost: Zombie died`), and a load that converts on the hook (piglin to zombified
piglin; zombie to drowned takes the same vanilla `ConversionType.SINGLE` path) is handed to its successor,
which vanilla re-mounts at once: `load changed: Piglin is now Zombified Piglin`.

### 6.6 Payload mass and lift performance

Mass is estimated from what every entity, modded ones included, exposes: its current bounding box and its
knockback resistance. The box follows babies, slime size and the `SCALE` attribute; knockback resistance
is vanilla's own "hard to push" number (iron golem and warden 1.0, ravager 0.75, hoglin 0.6, armour on a
player) and stands in for density:

```
m = max(0.05, w^2 * h * (1 + clamp(KBR, 0, 1)) * mult)          mult: product of the tags the type is in
    simpleplanes:crane_mass_x0_5 (x0.5), crane_mass_x2 (x2), crane_mass_x4 (x4; hoglin, zoglin shipped)
```

Thrust (`MultirotorPhysics`): `T_MAX = 8 G` rated. A load hanging within `ASSIST_HEIGHT = 2` b of the
ground raises the usable ceiling (ground effect on the rotor wash off the load), linearly from x1 at 2 b to
x1.8 at the ground:

```
assist(h)    = 1 + 0.8 * clamp(1 - h / 2, 0, 1)            h = load bottom above the ground
tMax         = T_MAX * assist(h)                           recomputed every tick while a load hangs
capacity     = 0.9 * T_MAX / G - 1         = 6.20          hovers anywhere with 10 % thrust in hand
lift limit   = 0.9 * T_MAX * 1.8 / G - 1   = 11.96         leaves the ground at all
ceiling(m)   = 2 * (1 - (need - 1) / 0.8), need = (1 + m) G / (0.9 T_MAX)   for m > capacity, else none
handling     = clamp((1 - m_total G / tMax) / (1 - G / T_MAX), 0.15, 1)    thrust margin vs. empty
```

The controller scales with `handling`: climb limit `VZ_MAX * handling` (descent unchanged), horizontal
acceleration `A_MAX * handling`, horizontal speed `V_MAX * (0.5 + 0.5 * handling)`. A chicken (0.11) is
barely felt (0.98); a cow (1.13) 0.84, a horse (3.12) 0.55. Above capacity the target height is capped at
the ceiling, so an iron golem (10.58, 171 %) hangs at about 0.48 b and is carried at that height. The
swing-damping gain still scales with `min(1, m / 0.65)`; loads heavier than that damp like a cow.

Numbers measured in game are in CRANE-MASS.md. Offline (Sim, L = 3, 20 b vertical step):

```
load      m      use   ceiling  handling  climb     settle
empty     0      0 %   -        1.00      0.240     90 t
chicken   0.112  2 %   -        0.98      0.236     119 t (10 deg cosmetic swing, as before)
zombie    0.702  11 %  -        0.90      0.216     122 t
cow       1.134  18 %  -        0.84      0.201     125 t
spider    1.764  28 %  -        0.75      0.180     133 t
horse     3.120  50 %  -        0.55      0.133     158 t
capacity  6.2    100 % -        0.15      0.036     410 t
golem     10.584 171 % 0.48 b   0.15      -         409 t to its ceiling, stable
```

### 6.7 Too heavy

- Over the lift limit, the pickup is refused before the crane moves: "too heavy: Ravager ≈ 14.64, max 11.96
  (236% of capacity)". The limit is checked against the rated `T_MAX`.
- A carried load whose ceiling turns negative (a debug `tmax` cut, a load that converts into a
  heavier mob) is reported "overloaded: <name> is over the lift limit, lowering it" and set down.
- If the hover thrust of a load within capacity still saturates (thrust pinned for `OVERLOAD_TICKS` 20 with
  `vy < -0.02`; a lift-limited load sinking onto its ceiling is exempt), the
  controller sinks and the state machine releases the load where it is if it is within 1.5 b of the ground,
  else winches it down first; message "load released: overweight".
- In transit, a lift-limited load flies at its ceiling. If the horizontal distance to the drop point does not
  shrink by 0.5 b in `CARRY_STALL_TICKS` 200, the crane reports "stuck: no progress with <name> for 200
  ticks (max lift h b), setting it down here" and lowers the load where it is. The ceiling follows the ground
  under the load and a passenger has no collision, so a golem crosses a 1-block step; at a 3-block wall it
  stalled on the top and was set down there (CRANE-MASS.md).

### 6.8 Rendering

- `QuadcopterRenderer extends EntityRenderer<QuadcopterEntity, QuadcopterRenderState>`;
  `QuadcopterRenderState extends PlaneRenderState` (so `DroneModel`, `DroneMetalModel`, `DroneRotorModel`,
  typed `EntityModel<PlaneRenderState>`, draw unchanged) adds `boolean carrying`, `float ropeLength`,
  `Vec3 hookWorld`. The pose is the contract's: `translate(0, 0.375, 0)`, `scale(-1,-1,1)`, `rotY(180)`,
  `rotate(q)`, `translate(0, -0.025, 0)`, `translate(0, -1.1, 0)`; a `SCALE` constant (default 1.0)
  multiplies the model before the translates for open question Q2. Shadow 0.5.
- **Rope** is drawn by vanilla: `extractRenderState` sets `state.leashStates = List.of(ls)` with
  `ls.offset = (0, 0.30, 0)` rotated by the body yaw, `ls.start = entity.getPosition(partial) + offset`,
  `ls.end = hook world position interpolated with partial ticks` (the load's box top when carrying, the
  free hook otherwise), lights from `getBlockLightLevel`/`getSkyLightLevel` at both ends, `slack = false`.
  `EntityRenderer.submit` then calls `submitLeash` for it. No leash entity, no `Leashable`.
- Hook: nothing is drawn at the free end beyond the rope; the model's clamp jaws show open (empty) or
  closed (carrying) via `setPayloadAttached(state.carrying)`, and the payload canister is made invisible
  always (edit `DroneMetalModel.setPayloadAttached` to keep `Payload.visible = false`; the canister is a
  bomb and this is a crane). The camera pod stays as decoration.
- Damage wobble is scaled by 0.4 for this 1.4-block model.

### 6.9 Remote item and commands

`crane_remote` (item, stack 1, data component `CRANE_LINK` = UUID):

| action | effect |
|---|---|
| use on a quadcopter (entity interact) | links the remote to it: "linked to crane #id" |
| `interactLivingEntity` on a mob or player | orders the linked crane to pick it up (`TO_PICKUP`) |
| `useOn` a block | if carrying: deliver the load to the top of that block; else fly there and hover at agl 6 |
| sneak + `use` in the air | recall: fly to the player, hover 4 b away at agl 3, then land |
| sneak + `useOn` a block | land on that block |

All feedback goes through a `CraneFeedback` helper modelled on `AutopilotFeedback` (owner chat when
there is one, log line otherwise), so the headless rig sees every terminal event in `console.log`.

`/crane` (permission level 2): `spawn <x y z>`, `goto <id> <x y z> [agl]`, `pickup <id> <targets>`,
`deliver <id> <x y z>`, `land <id>`, `winch <id> <length>`, `status`, `stop <id>|all`, `list`. The
telemetry line `-Dsimpleplanes.crane.trace=true` prints per tick:
`trace crane #id t= state= pos= vel= tilt= thrust= L= theta= load= sat=`.

Power: the quadcopter is electric and always powered (no fuel, no engine upgrade). Open question Q3.

---

## 7. Class mapping

| new class | extends | why |
|---|---|---|
| `entities/FighterEntity` | `PlaneEntity` | fixed-wing; one seat; only numbers and two clamps differ |
| `entities/AirlinerEntity` | `PlaneEntity` | fixed-wing, 22 assigned seats (`AirlinerSeats`, synched per seat) by its own `canAddPassenger`/`addPassenger`/`positionRider`, the captain is the controlling passenger, nose-to-tail hitboxes `AirlinerPartEntity`; not `LargeAirframeEntity` because of the livestock magnet, the large-upgrade bay and the -0.4 player offset |
| `entities/AirshipEntity` | `PlaneEntity` | overrides all six flight hooks (helicopter pattern); inherits controls, rendering, riders, persistence, collisions |
| `entities/QuadcopterEntity` | `Entity` | own physics, server-authoritative, load as passenger; no plane plumbing |
| `entities/MiniHelicopterEntity` | `HelicopterEntity` | the helicopter's model with smaller numbers through new getters; one rider; a ceiling |
| `client/render/MiniHeliRenderer` | `PlaneRenderer<MiniHelicopterEntity>` | body model/texture by livery |
| `entities/crane/{MultirotorPhysics, CraneController, SlungLoad}` | plain | unit-testable with `javac` alone |
| `client/render/AirshipRenderer` | `PlaneRenderer<AirshipEntity>` | fourth layer, culling box |
| `client/render/AirlinerRenderer` | `PlaneRenderer<AirlinerEntity>` | body model/texture by skin |
| `client/render/QuadcopterRenderer`, `QuadcopterRenderState` | `EntityRenderer`, `PlaneRenderState` | rope via leash state |
| `items/CraneRemoteItem`, `items/QuadcopterItem` | `Item` | the crane's remote; the placeable item (a `PlaneItem` cannot place a non-`PlaneEntity`) |
| `autopilot/AircraftType.FIGHTER, AIRLINER` | enum values | test tooling: `/autopilot route ... type fighter`; not in `FLYABLE`/`RANDOM` |

`PlaneEntity` gains the hooks in §8.3 and `HelicopterEntity` the getters in §5b.2, all with defaults that
keep every existing aircraft bit-identical.

---

## 8. Shared infrastructure (built by the foundation agent, Agent 1)

The principle: **every file more than one agent would otherwise touch is written once, by the foundation,
before the aircraft agents start.** The foundation registers all five aircraft with stub entities that
compile, boot and can be summoned, and it lands every registration touch point and every renderer hook.
The aircraft agents then edit only their own entity, physics, command and renderer classes.

### 8.1 Models and textures, ported from `claude/fighter-render-model` (`26.2/`) to `26.3/`

Files (committed versions only; read with `git -C /home/user/simple-planes show claude/fighter-render-model:<path>`):

- `client/render/models/`: `Fighter{Model,MetalModel,ExhaustModel}`, `AirlinerAirframe`,
  `Airliner{Model,SkinModel,MetalModel,FanModel}`, `Airship{Model,MetalModel,PropellerModel,EnvelopeModel}`,
  `Drone{Model,MetalModel,RotorModel}`, `MiniHeliAirframe`, `MiniHeli{Model,MedicalModel,MetalModel,RotorModel}`;
- `textures/plane_upgrades/`: `fighter_metal.png`, `airliner_metal.png`, `airliner_skin.png`,
  `airship_metal.png`, `airship_envelope.png`, `drone_metal.png`, `mini_heli_metal.png`, `mini_heli_medical.png`;
- the five contracts and `MODELS.md` are copied to `26.3/` as documentation.

Out of scope on that branch and not to be read: missile, launch tube and strike drone files.

The models use only `EntityModel`, `ModelPart`, `PartPose`, `CubeListBuilder`, `LayerDefinition`,
`RenderTypes::entityCutoutCull` and `PlaneRenderState`; none of the 26.2 -> 26.3 API deltas
(`mulPose`, `submitModel` arity, `InterpolationHandler`) touches them. The `tz` sign convention is
`-(local z)/16` and `ModelPart` rotation order is ZYX (the contracts' corrections to `MODELS.md`).

### 8.2 Render state and renderer

`PlaneRenderState` gains: `float throttle` (0..1), `float rudder`, `float elevator` (-1..1), `boolean
metalSkin`, `int airlinerLogo`, `boolean medicalLivery`. `PlaneRenderer.extractRenderState` fills `throttle` from
`min(1, plane.getThrottle() / 5f)`, `rudder = plane.getYawRight()`, `elevator = plane.getPitchUp()` for
every plane (cheap, and the models that ignore them do not care); `metalSkin`/`airlinerLogo` are filled
by `AirlinerRenderer`. `PlaneRenderer` gains:

- protected hooks `EntityModel<PlaneRenderState> bodyModel(PlaneRenderState)` and `Identifier
  bodyTexture(PlaneRenderState)` (defaults: `planeEntityModel`, `state.materialTexture`), used by `submit`;
- protected hook `void submitExtraLayers(PlaneRenderState, PoseStack, SubmitNodeCollector)` called after
  the third layer (default: nothing; `AirshipRenderer` submits the envelope);
- per-type translate branches for `FIGHTER (0, -0.025, 0.25)`, `AIRLINER (0, -0.025, 0.375)`,
  `AIRSHIP (0, -0.025, 0)`, `MINI_HELICOPTER (0, -0.025, -0.25)` before the helicopter `else`;
- the damage wobble scaled by a per-type `wobbleScale` (1.0; 0.6 for the mini helicopter).

`UpgradesModels.modelFor/textureFor/submitSeats` get an explicit "no upgrade visuals" branch for the new
entity types (`FIGHTER`, `AIRLINER`, `AIRSHIP`, `MINI_HELICOPTER`; the quadcopter never reaches
`UpgradesModels`).

### 8.3 `PlaneEntity` hooks (behaviour-preserving)

| hook | default | used by |
|---|---|---|
| `protected float pushPerNotch()` | `0.00625f` (replaces the literal in `tick()`) | fighter 0.012, airliner 0.004, airship 0.004 |
| `protected float maxRollRate()` | `5.0f` (literal in `tickRoll`) | fighter 8, airliner 2.5 |
| `protected float groundPitchLimit()` | `90f` (no clamp; applied at the end of `tickPitch` while `getOnGround()`) | fighter 10, airliner 12 |
| `protected double groundRollingResistance()` | `0` (added to `drag` in `tickOnGround` when `onGround()`) | fighter 0.030, airliner 0.007 |
| `protected double groundLinearDragFactor(float friction)` | `20 * (3 - friction)` (replaces the literal) | fighter 24, airliner 5 |
| `TempMotionVars.yawToMotion` | `0.1` (replaces the literal `0.1f` in `tickRotateMotion`) | fighter 0.2, airliner 0.06 |
| `protected boolean acceptsUpgrade(UpgradeType)` | `true`, consulted by `canAddUpgrade` | all three planes |

`PlaneCollisions.massOf` gets `FighterEntity 1.1`, `AirlinerEntity 1.6`, `AirshipEntity 2.0`,
`MiniHelicopterEntity 0.8` (tested before `HelicopterEntity`).

`HelicopterEntity` gets the twelve protected getters of §5b.2, each returning its constant; every
inline use of `COLLECTIVE_PER_NOTCH`, `ROTOR_INFLOW_LIMIT`, `MAX_CYCLIC`, `MAX_CYCLIC_RATE`, `MAX_YAW_RATE`,
`YAW_RAMP`, `TURN_FROM_BANK`, `H_DRAG_*`, `V_DRAG_*`, `MAX_SPEED`, `GROUND_FRICTION` inside the class goes
through the getter, and `rotorThrust` multiplies by `ceilingThrustFactor(getY())` (default 1.0). The
constants remain public for the callers outside the class.

### 8.4 Registration (all in the foundation)

- `SimplePlanesEntities`: `FIGHTER sized(3.0, 2.0)`, `AIRLINER sized(3.0, 2.6)` (plus the `AIRLINER_PART` hitboxes,
  `sized(3.4, 3.3)`, added with the cabin), `AIRSHIP sized(3.0, 2.5)`,
  `QUADCOPTER sized(1.0, 0.875)`, `MINI_HELICOPTER sized(1.5, 1.95)` with the aircraft tracking range;
- `SimplePlanesItems`: `FIGHTER_ITEM`, `AIRLINER_ITEM`, `AIRSHIP_ITEM`, `MINI_HELICOPTER_ITEM` as
  `PlaneItem`s, `QUADCOPTER_ITEM` (`QuadcopterItem`), `CRANE_REMOTE`; all added to `getPlaneItems()` where
  they are planes, and to the creative tab; item model JSONs (`items/*.json`, `models/item/*.json`)
  reusing `plane.png`/`helicopter.png` with tints until real icons exist (open question Q5);
- `PlanesModelLayers`: `FIGHTER_LAYER/METAL/PROPELLER`, `AIRLINER_LAYER/SKIN/METAL/PROPELLER`,
  `AIRSHIP_LAYER/METAL/PROPELLER/ENVELOPE`, `MINI_HELI_LAYER/MEDICAL/METAL/PROPELLER`,
  `QUADCOPTER_LAYER/METAL/PROPELLER`, and the renderer registrations (`PlaneRenderer` for the fighter;
  `AirlinerRenderer`, `AirshipRenderer`, `MiniHeliRenderer`, `QuadcopterRenderer` as thin subclasses the
  foundation creates with the hooks wired and the aircraft agents extend);
- block tag `mini_heli_medical` beside `airliner_metal_skin`;
- `lang/en_us.json`: item and entity names, the remote's tooltip, the key for the airliner's logo tooltip;
- datapack: workbench recipes (`fighter` 2 propellers + 6 material, `airliner` 4 + 12, `airship` 2 + 14,
  `quadcopter` 1 + 3), tags `airliner_metal_skin`, `plane_materials` additions, `crane_liftable`,
  `crane_never` (empty);
- `SimplePlanesMod`: `CraneCommand.register()` and `AircraftCommand.register()` (the shared test command,
  §8.7); the aircraft agents fill in their subcommands in their own classes;
- `AircraftType.FIGHTER`, `AIRLINER`;
- `simpleplanes.accesswidener` re-added with the Loom and `fabric.mod.json` wiring, initially **empty** (§9).

### 8.5 Stub entities

The foundation creates `FighterEntity`, `AirlinerEntity`, `AirshipEntity` (each `extends PlaneEntity`,
with item, seats/rider count, `getRotationSpeedMultiplier`, and no physics overrides yet, so they fly as
starter planes), `MiniHelicopterEntity extends HelicopterEntity` (one rider, livery flag, no getter
overrides yet, so it flies as the helicopter) and `QuadcopterEntity extends Entity` that hovers in place
(thrust = weight, no controller) with `getControllingPassenger() = null`, health, material, save/load and
the synched `Q`. Each boots, summons, ticks and renders (on a client) before the aircraft agents start.

### 8.6 Test harness

- `26.3/tools/testserver/make-server.sh <dir> <port>` builds a dedicated-server directory from the
  launcher already on this container (`/tmp/mc-server-26.3/fabric-server-launch.jar`, its `libraries/`,
  `versions/`, `.fabric/`, and `/home/user/minecolonies-fabric/.cache/fabric-api-0.160.5+26.3.jar`; nothing
  is downloaded), with `start.sh`, `cmd.sh`, `stop.sh` as in `TESTING.md` §3 (FIFO opened read-write),
  `server.properties` tuned for tests (superflat, creative, `online-mode=false`, `spawn-monsters=false`,
  `max-tick-time=-1`, `pause-when-empty-seconds=0`, `level-seed=aircraft`, `view-distance=6`, the given
  port, query and rcon off), `eula=true`, and `-Xmx2G`;
- `26.3/tools/quick-javac.sh`: compiles `src/main/java` with `javac` against
  `~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.3/minecraft-merged-deobf-26.3.jar`
  plus the jars under `~/.gradle/caches/modules-2/files-2.1` (fabric loader and API, mixin, joml,
  jspecify, guava, gson, slf4j, log4j, netty, brigadier, datafixerupper, fastutil, commons) into
  `/tmp/quick-javac-<branch>/`, for fast iteration without the build lock. The real build stays
  `mc-build.sh`. If the classpath cannot be made to work in an hour, the script is dropped and agents use
  `mc-build.sh ... compileJava`;
- **`/aircraft` test command** (§8.7) and the trace flags.

### 8.7 `/aircraft` test command (permission 2, console-friendly)

| subcommand | effect |
|---|---|
| `spawn <type> <x y z> [heading]` | summons `fighter`/`airliner`/`airship`/`quadcopter`/`plane`/`large`/`cargo`/`helicopter` at the position facing `heading`, with a furnace engine and 64 coal (planes) so `isPowered()` is true with nobody aboard; sets `Q`, `Q_Client`, `Q_Prev` from the heading as `GunshipCommand.spawn` does; tags it `aircraft-test`; prints `Aircraft #<id> spawned` |
| `set <id> throttle <0-10>`, `set <id> pitch <-1..1>`, `set <id> yaw <-1..1>`, `set <id> roll <-1..1>` | writes the synched controls (`roll` writes a synched `TEST_STRAFE` byte the physics reads as `moveStrafing` when no player is aboard) |
| `set <id> cyclic <fwd> <right>`, `set <id> boost on|off` | helicopters (`HelicopterEntity` and subclasses): `setCyclicForward/Right` in percent, `setCollectiveBoost` |
| `launch <id> <speed> [pitch]` | sets `deltaMovement` along the heading |
| `status [id]` | one line per test aircraft: `#id type pos= vel= spd= vs= hdg= pitch= roll= thr= og=` |
| `trace <id> on|off` | per-tick line `trace #id t= pos= spd= vs= hdg= pitch= roll= thr= og= agl=` to the log (planes) |
| `kill` | removes every `aircraft-test`-tagged entity in loaded chunks |

Planes with nobody aboard fly the server path (`transformPosPhysics`, no `RotationPacket`), which is the
same path the autopilot exercises and the one every number in this design was computed for. Rider-side
behaviour (the client-authoritative branch) cannot be tested headlessly; §12.

### 8.8 Telemetry logging

All new per-tick traces are gated on a `Boolean.getBoolean` system property read once into a static
final (`simpleplanes.aircraft.trace`, `simpleplanes.crane.trace`), print through the mod's logger at
`INFO` with the `trace ` prefix, and are formatted with `String.format(Locale.ROOT, ...)` so
`grep "trace #7" console.log` per aircraft works as it does for the autopilot.

---

## 9. Access widener entries

Everything above reaches vanilla through public or protected API. **The list is empty.** The foundation
still re-adds the file and wiring (`src/main/resources/simpleplanes.accesswidener` with the header
`accessWidener v2 named`, `"accessWidener": "simpleplanes.accesswidener"` in `fabric.mod.json`, and
`loom { accessWidenerPath = file("src/main/resources/simpleplanes.accesswidener") }` in `build.gradle`) so
that an agent who finds a genuine need adds one line instead of the wiring. Ownership: the foundation
owns the file; an aircraft agent who needs an entry appends it on its own branch **and** records the
class, member and reason in its report; the merge is a two-line union and the reports are the audit
trail. Members that were checked and found not to need widening:

| member | why it is not needed |
|---|---|
| `EntityRenderer.getBoundingBoxForCulling(T, float)` | protected, overridden in `AirshipRenderer` |
| `Entity.positionRider`, `canAddPassenger`, `getPassengerAttachmentPoint` | protected, overridden |
| `Entity.startRiding(Entity, boolean, boolean)` | public |
| `EntityRenderState.leashStates`, `LeashState` fields | public |
| `Camera` internals | not touched; see §10 |

---

## 10. Mixin-free routes, and the existing `CameraMixin`

| need | route |
|---|---|
| rider sees out of a closed cabin | culling render type on the eye layers (§1.1) |
| animated nozzle, control surfaces, logo, jaws | render-state fields + `setupAnim` hooks |
| fourth model layer, large culling box | `PlaneRenderer` subclass hooks; `getBoundingBoxForCulling` override |
| rope | `EntityRenderState.leashStates` filled by the drone renderer |
| load carried smoothly on both sides | vanilla passenger mechanism + `positionRider` |
| remote control of the crane | `Item.interactLivingEntity`/`useOn`/`use` on the server; no packets |
| unmanned flight | `getControllingPassenger() == null` keeps the server authoritative |
| chunk loading in flight | `addTicketWithRadius(TicketType.ENDER_PEARL, ...)` as the autopilot does |
| commands | Fabric `CommandRegistrationCallback` |
| test power without a pilot | a furnace engine with coal, as `GunshipCommand` does |

`CameraMixin` (client, `Camera.update` after `alignWithEntity`) moves the first-person eye of any
`PlaneEntity` rider by the rider's eye height minus 0.3, rotated by the plane's quaternion, plus 0.375. It
therefore also applies to fighter, airliner and airship riders. **The seat positions in the contracts were
derived for the vanilla eye (feet + 1.62)**, and the mixin moves that eye: for a level aircraft it puts
the camera at `feet + eyeHeight - 0.3 + 0.375 = feet + 1.695`, 0.075 above vanilla, and it rolls and pitches
the offset with the airframe. On the fighter the vanilla eye (seat 0.0625 + 1.62 = 1.6825) is inside
`canopy_main` (1.375 to 1.6875) and the mixin eye (1.7575) is inside `canopy_mid`; the contract cuts out
the covered part of `canopy_main`'s top face for exactly that case, so the view is clear either way.
**Decision: the design does not rely on the mixin; the contracts' seats stand unchanged.** The airliner's
cabin eyes are inside its open window band and its crew eyes inside the lower windscreen's panes with the
mixin and without it (AIRLINER-MODEL.md), the airship's eye is in an open window band. If the
mixin is ever removed, nothing here changes. A future spec may replace it with a Fabric camera hook if one
exists in the API version in use; none is required. The fighter agent's client check (when a client is
available) is to look out with the mixin present, since that is what ships.

The quadcopter is not a `PlaneEntity`, so the mixin ignores a carried player. Its camera pod (a
first-person "drone camera" view) is out of scope: it would need `Minecraft.setCameraEntity` and a way to
give a non-living entity a view rotation, which is client work with no headless test; open question Q1.

---

## 11. Simulation results

The three scripts live in the designer's scratchpad
(`/tmp/claude-0/-home-user-minecolonies-fabric/32776c26-3535-4748-aeb8-06e05c1c6bcf/scratchpad/design/{fixedwing,airship,quad}.py`);
they are not part of the deliverable. Everything they established is reproduced here, and the
acceptance tests in the specs are written from these numbers with tolerances.

### 11.1 Fixed wing (`fixedwing.py`: tick-for-tick port of `tickRotateMotion`, `tickOnGround`, `tickPitch`, `tickMotion`; level speeds by bisection on the held pitch until `vy = 0`)

```
=== starter (calibration)
  stall 0.165; take-off 0.30
  T3 ground 0.345 level 0.601 trim +4.5 | T4 0.415 / 0.681 | T5 0.481 / 0.748 trim +1.4
  run: rotation t=38 x=4.3; airborne t=57 x=11.6 v=0.437
  climb T5: 10 deg 0.081, 20 deg 0.143; glide ratio 3.6
  turn: nominal 2.50, realised 1.72 deg/t (34 deg/s), radius 20/33/50/66 b at 0.6/1.0/1.5/2.0
=== fighter (PUSH 0.012, MAX_SPEED 2.5, dragQuad 0.00145, takeOff 0.45, rotMult 1.4, rolling 0.030, gl 24)
  stall 0.27; take-off 0.45
  ground T2 0.02 T3 0.19 T5 0.84 | level T1 1.08 T2 1.46 T3 1.77 T4 2.04 T5 2.30, trim -1.4..-4.8
  run: rotation t=20 x=5.6; airborne t=32 x=12.5 v=0.76
  climb T5: 10 deg 0.485, 20 deg 0.750, best 1.09 at 40 deg; glide ratio 3.4
  turn: nominal 3.50, realised 2.92 deg/t (58 deg/s), radius 12/20/29/39 b
=== airliner (PUSH 0.004, MAX_SPEED 2.0, drag 0.0006/0.0003/0.0005, takeOff 0.60, maxLift 2.0, pitchToMotion 0.16,
              rotMult 0.35, rolling 0.007, gl 5, ground pitch clamp 12)
  stall 0.36; take-off 0.60
  ground T2 0.11 T3 0.40 T5 0.75 | level T1 0.64 T2 0.91 T3 1.05 T4 1.16 T5 1.25, trim +15..-2
  run: rotation t=89 x=34; airborne t=116 x=51 v=0.70
  climb T5: 10 deg 0.166, 20 deg 0.19; glide ratio 5.0
  turn: nominal 0.875, realised 0.49 deg/t (10 deg/s), radius 70/117/175/234 b
```

Sweeps that set the numbers: airliner rolling resistance 0.006..0.008 moved the run between 45 and 63
blocks; `groundLinearFactor` 4..6 was second order; with the stock factor 48 the airliner's ground speed
capped at 0.64 b/t and it never lifted off, which is why the hook exists. Fighter rolling 0.02..0.04 moved
the run between 11 and 21 blocks; 0.03 keeps throttle 3 taxiing.

### 11.2 Airship (`airship.py`)

```
static terminal, engine off: trim -1 -> +0.200 b/t, trim +1 -> -0.200 b/t
elevator up 200 t then release: peak climb 0.118; captured hold 82.58, y at t=600: 82.66 (0.08 overshoot)
7 riders board: sag 2.99 b, final error -0.10 b, trim -0.70 of 1.0
cruise: T1 0.318 T2 0.484 T3 0.612 T4 0.719 T5 0.814 b/t, altitude error 0.00 at every setting
full rudder at cruise: nose 0.6 deg/t, track 0.600 deg/t (12 deg/s), speed 0.806, radius 77 b
elevator down from 60: touchdown t=543 (27 s), sink 0.120
KI x1 0 % overshoot, x2 +14 %, x3 +22 %, x4 +21 %  (hold gains: ALT 0.01, KP 8, KI 0.10, rate 0.02, lead 30)
```

The first gain set tried (`ALT_GAIN 0.03, KI 0.21, KP 5`) hunted at +-4 b for ever; lowering the outer
gain to 0.01 and capturing the hold at the predicted stop point removed both the limit cycle and the
capture overshoot.

### 11.3 Quadcopter (`quad.py`: exact two-body rigid-rope equations, solved per tick; attitude as a rate-limited first-order lag)

```
hover thrust: empty 0.040, cow (1.13) 0.085, T_MAX 0.120 -> max load at 85 % thrust 1.55
  (superseded by 6.6: T_MAX 0.32, capacity 6.20, lift limit 11.96 with ground assist; the gains below
  were re-checked with Sim.java at the new rating, C1 PASS)
climb (target +200): empty 0.240 b/t, 0.65 load 0.248, cow 0.251, 1.50 load 0.211
20 b step, empty: settle 88 t, overshoot 0
20 b step, cow, L=6, final law: settle 116 t, overshoot 0.63 b, peak swing 25.1 deg, residual after 15 s 0.0
free swing 20 deg, cow, L=6: 1.2 deg at 5 s, 0.0 at 10 s (positive-gain law: 25 deg and growing; no law: 5.6 / 2.4 / 0.3)
rope 1.5..12 b, loads 0.31..1.5: residual < 3 deg everywhere; loads of 0.11 (chicken) keep 5-19 deg
  for 15 s on long ropes (cosmetic; the drone barely feels them)
gain margin, all loop gains x f: x0.5 settle 134 t; x1 116 t / 0.63 b; x1.5 132 t / 1.7 b; x2 178 t / 3.5 b,
  2.4 deg residual; x3 5.7 b / 8 deg; x4 unstable
attitude lag tau 1..6 t: settle 116..125 t, overshoot 0.62..0.93 b
vertical 20 b step, cow: settle 105 t, overshoot 0, max vy 0.267
overload 2.2 masses, target +30: after 300 t altitude -18.9, vy -0.069, thrust saturated 300 t
pendulum period L=6: 77 t
```

### 11.4 Mini helicopter (`miniheli.py`: `HelicopterEntity.tickMotion`/`rotorThrust` equations, vertical equilibria by iteration, level speed at a fixed tilt with the collective trimmed)

```
=== existing helicopter (per notch 0.010, inflow 2.0, cyclic 25, hDrag 0.009/0.0025/0.0002, vDrag 0.045/0.050)
  hover at 3.00 notches; ladder 0..5: -0.432 -0.312 -0.173 +0.000 +0.134 +0.238 b/t
  full cyclic 25 deg: 3.31 notches, level top speed 1.107 b/t; margin at 5 notches + full tilt +0.0153
  pedal 60 deg/s; full tilt in 12.5 t
=== mini helicopter (per notch 0.015, inflow 1.6, cyclic 30, hDrag 0.025/0.004/0.0003, ceiling fade 100..200)
  hover at 2.00 notches; ladder 0..5: -0.432 -0.246 +0.000 +0.178 +0.304 +0.403 b/t
  full cyclic 30 deg: 2.31 notches, level top speed 0.749 b/t; margin at 5 notches + full tilt +0.0350
  pedal 90 deg/s; full tilt in 8.6 t
  ceiling: factor needed at 5 notches 0.40 -> absolute ceiling y = 160 (fade starts at 100)
```

The helicopter's own published hover notch (3) and inflow-limited ladder are reproduced by the same
code, which is the calibration.

---

## 12. Risks

1. **Nothing client-side has been run on 26.3** (`PORT-STATUS.md`, "Not verified"). Rider placement,
   the canopy view, the rope, the logos and the nozzle animation can only be checked in a client. The
   specs require the headless tests and ask each agent to state exactly what was not seen.
2. **The lift model is an angular rate**, so the fighter at 2.3 b/t needs 4 to 5 deg of nose-down trim
   to fly level and pulls hard when the nose is raised; the autopilot already flies the starter at 2.6 b/t
   under the same law, but a human pilot may find the fighter twitchy. The pitch ramp (7 deg/t) is the
   first knob.
3. **Airliner rotation**: the 12-degree clamp with `pitchToMotion 0.16` lifts off at 0.70 b/t in the
   simulation. If the rig shows the nose held down longer, raise `pitchToMotion` to 0.18 before touching
   the clamp; the tail-strike angle is geometry.
4. **Ground drag on non-grass surfaces**: the rolling-resistance hook adds a constant, and the linear
   factor scales `(3 - friction)`; on ice (`friction 0.98`) the stock factor is 40, so the new aircraft
   are less sensitive to the surface than the old ones. Runways of stone (0.6) behave as grass.
5. **The passenger-as-load mechanism** relies on vanilla positioning riders on both sides every tick.
   A carried player's client will draw them in the riding pose (sitting). Acceptable.
6. **A quadcopter with a passenger is tracked to the passenger's range** (`getEffectiveRange`
   maximises), so a carried player keeps it visible farther than an empty one; cosmetic.
7. **`quick-javac.sh` classpath**: may need iteration; bounded to an hour in the foundation spec.
8. **Memory**: four test servers at 2 GB plus a 3 GB Gradle daemon-less build fit the 16 GB box with 3 GB
   to spare only if nobody runs a fifth server; `PLAN.md` sequences the foundation alone first.
9. **Swing-damping sign**: the negative-gain law was found by simulation, not derived; the spec asks
   the crane agent to measure the free-swing decay with and without it and to keep whichever is better,
   reporting the number.

---

## 13. Open questions for the user

- **Q1. Manual flying of the quadcopter.** This design directs the crane with a remote (point and
  click) and commands; there is no stick flying and no drone-camera view. Both would be client work (input
  capture while holding the remote, `Minecraft.setCameraEntity` on a non-living entity with a view
  rotation) that cannot be verified on the headless rig. Do you want a manual mode as a later step?
- **Q2. Scale.** The drone model is 1.4 b across and a cow is 0.9 x 1.4. A `SCALE` render constant is
  provided (default 1.0) and the hitbox is registered at 1.0 x 0.875. Do you want the crane drawn at 2.0x
  (2.8 b across, hitbox 2.0 x 1.75) as a "heavy-lift" variant, or left small? Physics is unaffected.
- **Q3. Power.** The quadcopter is always powered (electric, no fuel). Should it take an engine upgrade
  or a battery item instead?
- **Q4. Upgrade visuals.** No upgrade model is drawn on the three new planes (the helicopter fallback was
  wrong for all of them); engines and boosters work invisibly. Is that acceptable for now?
- **Q5. Item icons.** The four items reuse `plane.png` with a tint until icons exist. Who makes icons?
- **Q6. Airliner materials.** Metal skin is selected by building from a block in
  `simpleplanes:airliner_metal_skin` (iron, copper, gold, netherite). Alternative: a wrench toggle or an
  upgrade item. Which do you prefer?
- **Q7. Recall behaviour for the crane** when the remote's owner is far away (> 64 b): the crane hovers
  where it is and waits. Should it fly home to a "base" block instead?
- **Q8. Hostile mobs** — answered: allowed. Ordinary hostiles are lifted under the same mass limit;
  bosses are refused; a slung mob does not fight (see 6.5, "A slung mob does not fight"). Details and
  test results in `design/CRANE-HOSTILES.md`.
- **Q9. Autopilot types.** `AircraftType.FIGHTER`/`AIRLINER` are added for testing (not random). Keep
  them player-visible on `/autopilot flight ... type airliner`, or hide them?
- **Q10. Mini helicopter livery and ceiling.** The medical livery is chosen by building from a white block
  (`simpleplanes:mini_heli_medical` tag) like the airliner's skin; alternative: a second item. And the
  ceiling (thrust fades from y 100, none above y 160) is the one mechanism the big helicopter does not
  have; keep it, move the heights, or drop it?
