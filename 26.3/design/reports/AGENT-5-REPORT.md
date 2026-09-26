# Agent 5 report: the quadcopter crane

Branch `claude/aircraft-crane-26.3`, based on `claude/aircraft-foundation-26.3` (`f8960f0`).
Worktree `/home/user/sp-agent-5`, module `26.3/`. Test server `/home/user/sp-test-5`, port 25605, `-Xmx1536M`.

## Commits

| commit | content |
|---|---|
| `fe430a6` | `entities/crane/MultirotorPhysics`, `CraneController`, `SlungLoad`, `Sim` (plain Java, no Minecraft imports) |
| `d0bf197` | `QuadcopterEntity` (state machine, load as passenger, collisions, damage, persistence), `crane/CraneCommand`, `CraneFeedback`, `CraneRegistry`, `items/CraneRemoteItem`, `QuadcopterItem`, `QuadcopterRenderer` culling box |
| (this commit) | this report |

## What was done

- **Physics** (`entities/crane`), with every constant from the spec as a named constant:
  - `MultirotorPhysics`: the thrust vector, the attitude lag (τ 2 t, 4 °/t, 25° limit), yaw slew (6 °/t, 1 °/t²), drag and integration.
  - `CraneController`: the cascaded position, velocity and vertical loops, the swing term, the tilt and thrust commands, and yaw toward the direction of travel.
  - `SlungLoad`: the winch (1 to 12 b at 0.15 b/t), one pendulum per horizontal axis, the rope reaction, the hook position and mass `w²h`.
- **Attitude convention.** `up(ψ,θ,φ)` is `HelicopterEntity.rotorAxis` with `xRot = -θ` and `roll = -φ`. The synched quaternion is `MathUtil.toQuaternionf(yaw, -pitch, -roll)`, as the helicopter uses it. Pushed through the renderer's pose (`scale(-1,-1,1)`, `rotY(180)`, `rotate(q)`), the model's rotor axis equals the physics' `up` to 1.6e-7 over 1000 random attitudes; that check was run in a scratchpad, with JOML.
- **Entity.**
  - `QuadcopterEntity extends Entity` has no controlling passenger. It moves with `move(MoverType.SELF)`, zeroes each blocked axis, and takes impact damage.
  - The load is the only passenger, admitted through `pendingLoad`. `positionRider` puts the top of the load's box at the rope end, on both sides. The dismount point is the load position.
  - Synched data is `ROPE_LENGTH`, `ROPE_THETA_X`, `ROPE_THETA_Z` and `THRUST`; `HOOK_OFFSET` is removed. The client computes the same hook and load position from these.
  - The rotors spin at `0.6 + 4 T/T_MAX` per tick.
  - Save and load cover everything the spec lists (see "Persistence" below).
  - Position and quaternion interpolation now use 3 steps instead of 10, because the server sends state every tick.
- **State machine.** `IDLE TO_PICKUP LOWER ATTACH WINCH_IN CARRY LOWER_LOAD RELEASE STOW RETURN LAND OVERLOAD`, as in the spec. Two additions:
  - Carrying states follow the terrain: cruise at the highest `MOTION_BLOCKING` surface under the drone and up to 24 b ahead, in loaded chunks only, plus `10 + L`.
  - The drone climbs first, at 0.15 b/t horizontally, while it is more than 3 b below cruise height.
- **Chunk loading.** `CraneRegistry` renews, every 5 ticks from `END_LEVEL_TICK`, `ENDER_PEARL` radius-2 tickets on the crane's chunk and on the chunk 20 t ahead, for every crane except one parked on the ground.
- **Remote.** `use` links (and sets the owner); sneak-`use` recalls; `interactLivingEntity` picks up; `useOn` delivers (when carrying) or flies there and hovers at agl 6; sneak-`useOn` lands. No packets and no events.
- **Feedback.** `CraneFeedback.report` sends to the owner's chat when they are online, and always logs `Crane #id: …` at INFO (logger `simpleplanes-crane`). The log carries every terminal event: picking up, picked up, refused, set down, overloaded, released overweight, load lost, landed, destroyed, crashed/lost.
- **Registration without touching foundation files.** `CraneCommand.register()`, which `SimplePlanesMod` already calls, also calls `CraneRegistry.init()`.

## Final constants

Units are blocks, ticks, degrees and drone masses.

- **Physics:** G 0.04; T_MAX 0.12; tilt 25° max, 4 °/t, τ 2 t; yaw 6 °/t max, 1 °/t²; drag `-(0.02 + 0.01|vh|) vh` horizontally and `-0.05 vy` vertically.
- **Controller:** K_POS 0.05, V_MAX 0.8, K_VEL 0.15, A_MAX 0.018 empty and `0.010·min(1, L/4)` loaded, K_ALT 0.05, VZ_MAX 0.30, K_VZ 0.2.
- **Swing term:** `k_s = -0.4·min(1, m/0.65)`.
- **Rope:** L 1 to 12, winch 0.15 b/t, winch point 0.30 above the origin, load damping 0.002, empty-hook damping 0.05 (new), rope angle limit 80° (new).
- **Loads:** MAX_LOAD 1.55; L_PICK 4, L_CARRY 3.
- **State machine:** arrival within 0.6 b horizontally and 0.8 b vertically for 10 t; LOWER timeout 160 t, follow range 8 b; release gap 0.2 b and band 0.3 b for 5 t.
- **Overload:** saturated for 20 t with vy < -0.02; release when the load's bottom is below 1.5 b.
- **Landing:** 0.15 b/t, centred to within 0.3 b first. Recall range 64 b.
- **Impacts:** above 0.3 b/t, `4·(|v| - 0.3)` HP, 8 t cooldown.
- **Death blast:** power 1.5, no fire, no block damage.

**Swing-damping sign: negative (k = -0.4) is kept.** Measured on the rig (C13) and reproduced exactly by `Sim`:

| k | amplitude at 5 s | at 10 s | design |
|---|---|---|---|
| -0.4 | **1.78°** | 0.12° | 1.2° |
| 0 | 6.17° | 2.76° | 5.6° |
| +0.4 | 11.61° | 8.53° | 25°, growing |

## Acceptance tests

All results are from the final jar (sha1 `35f6bdbe`), in one scripted run with `tick freeze` and `tick sprint`, on `/home/user/sp-test-5`.

**Test area.** The area is mirrored to negative x, to keep clear of the village at x 55 to 75: the cow is at (-10, -60, 10) and the delivery point at (-50, -60, 10), the same distances as the spec. A force-load box covers x -64 to 16, z -16 to 32.

**Measuring.**
- "Settle" is the first tick after which the drone stays within the tolerance.
- The swing amplitude "at 5 s" is the peak |θ| over one pendulum period (77 t) starting 100 t after the event.

| # | pass | measured | target |
|---|---|---|---|
| C1 | yes | `Sim` (run with `javac`/`java` alone), 20 b step. Empty: settle **90 t**, overshoot 0. Cow on a 6 b rope: settle **105 t**, overshoot 0.41 b, peak swing **20.7°**, residual after 300 t **0.07°**. Also: hover thrust 0.0400 / 0.0852 / 0.1020; pendulum period 76.9 t; vertical step 114 t with vy 0.240; overload 2.2 sinks at -0.050 b/t with thrust saturated. Prints `C1 PASS` | empty ≤ 110, loaded ≤ 150, swing ≤ 32°, residual ≤ 3° |
| C2 | yes | `Done (0.97s)`. 0 lines matching exception or error over the whole session, including four restarts | no exception |
| C3 | yes | after 600 t: pos 0.00, -50.00, 0.00; tilt 0.00; thrust **0.0400** | ±0.3 b, tilt < 1, T 0.038 to 0.042 |
| C4 | yes | settle **89 t**; overshoot 0.00 b; max tilt **24.22°** | ≤ 140 t, ≤ 0.5 b, ≤ 25° |
| C5 | yes | settle **113 t**; overshoot 0.00; peak vy **0.240** | ≤ 160 t, ≤ 0.5 b, 0.22 to 0.32 |
| C6 | yes | state CARRY; `load=Cow mass=1.13`; the crane's `Passengers[0].id` is `minecraft:cow`; L 3.00; cow 10 HP; cow feet at -51.100, which is exactly `y + 0.30 - 3.0 - 1.4`, with x and z equal to the drone's | as spec, ±0.1 b |
| C7 | yes | cow at (-50.002, -60.0, 10.000), 0.002 b from the point, 10 HP, no `RootVehicle`; crane IDLE, `carrying=false`, L 1.00; `Crane #10: set down Cow at -50.0 -59.7 10.0` logged | within 1.5 b, alive, IDLE |
| C8 | yes | peak θ **22.8°**, at the deceleration into the arrival. After arrival: 5.0° (0 to 2 s), 1.15° (2 to 4 s), **0.21° (4 to 5 s)**, 0.09° (5 to 6 s). The load is released 5.4 s after arrival, so there is no 10 s value | ≤ 32°; ≤ 3° within 300 t |
| C9 | yes | **Villager** (0.70): delivered alive, 20 HP; peak 22.2°; after arrival 8.2 / 4.5 / 2.0° (0 to 2 / 2 to 4 / 4 to 6 s). **Chicken** (0.11): delivered alive, 4/4 HP, 0.03 b from the point; peak 24.5°; residual swing **16 / 15 / 12°**, as the design predicts for chicken-weight loads (cosmetic) | both alive; report the chicken |
| C10 | yes | `refused: too heavy: Horse is 3.12, limit 1.55`; the crane stays IDLE; the horse is untouched at (-39.5, -60, 10.5) | as spec |
| C11 | yes | `refused: cannot lift Zombie` | as spec |
| C12 | yes | with `debug tmax 0.07`: OVERLOAD **20 t** after the cut, released **44 t** after it (`load released: overweight (Cow)`); the cow is on the ground with 10 HP | ≤ 40 t, ≤ 300 t, alive |
| C13 | yes | see the table above; negative sign kept | smallest 5 s peak |
| C14 | yes | 300 b to (-350, -60, 10) with no force-load: **0 gaps** in the trace; arrived after **984 t** (0.31 b/t loaded); cow set down alive; no "lost" line. On the first flight into never-generated chunks, `tick sprint` outran generation: the crane ran 590 of 12000 sprinted ticks, then continued in real time and delivered. The measured run was on the generated corridor | arrives, no "lost" |
| C15 | yes | carrying at x -298, then `save-all flush` and a restart: `#6 state=CARRY … load=Cow mass=1.13`, `Passengers[0].id = minecraft:cow`, velocity restored (0.31 b/t); after unfreeze it delivered the cow alive to (-200, -60, 10). The restart used `-Dsimpleplanes.crane.trace=true`, which traced both cranes from their first tick | CARRY with the cow, continues |
| C16 | yes | 10-high stone wall at x -30. **Empty**, agl 5: stops at x -28.5; `impact at 0.60 b/t, 1.2 damage`, health **8**; the hook is held at the 80° clamp. **With a cow**: stops at x -28.5, the cow still riding with 10 HP, crane health 10; peak swing 17°. No exception | stops, health ≤ 9, the cow not lost |
| C17 | yes | `land` from agl 20: on the ground after **187 t**; vy on the contact tick **-0.024**; fastest descent 0.120 | ≤ 300 t, ≤ 0.2 |
| C18 | yes | `damage @s 20` while carrying at agl 13: `destroyed … falling`, `load released: crane destroyed (Cow, feet at agl 8.9)`, `lost: crashed at -15.0 -60.0 5.0`; the crane is removed. **The cow survives with 5/10 HP** (fall damage from 8.9 b; the blast does not hurt the load it released). No exception | released, survives or report the height |
| C19 | yes (partial) | `debug remote` drives the real `use`, `interactLivingEntity` and `useOn` methods with Fabric's `FakePlayer`. Results: unlinked gives "no crane linked"; `link` gives "linked to crane #9"; pickup takes off from parked and picks up; a block while carrying delivers (cow set down at the block top); a block while empty gives a hover at agl 6 (0.5, -54, 0.5); sneak on a block lands at (5.5, -60, 5.5); sneak recall reports "owner out of range", because `FakePlayer` is not in the level's player list. `stop` while carrying at agl 13 lowered the cow and set it down alive; `stop all` also works. A real player's click routing is not verified | report |

## Commands added

All subcommands are under `/crane`, need permission level 2, and work from the console. Every line goes to the command source and to the log. `<id>` is the entity id that `spawn` prints. `debug` subcommands are test aids, and their output says `[test aid]`.

| syntax | effect | example |
|---|---|---|
| `crane spawn <x y z>` | Spawns a quadcopter tagged `aircraft-test`. It hovers there if the point is more than 0.5 above the surface, else it parks with the rotors off. Prints `Crane #id spawned at …` | `crane spawn 0 -50 0` |
| `crane goto <id> <x y z> [agl]` | Flies straight to the point, or to `agl` above the surface at x z, and hovers. When carrying, it flies there with the load and without terrain following | `crane goto 5 20 -50 0` |
| `crane pickup <id> <targets>` | Picks up the first match of the selector, or prints the refusal | `crane pickup 5 @e[type=cow,limit=1]` |
| `crane deliver <id> <x y z>` | Sets the delivery point. A carrying crane goes there now; otherwise the point is used after the next pickup | `crane deliver 5 -50 -60 10` |
| `crane land <id>` | Lands where it is; a load is set down first | `crane land 5` |
| `crane winch <id> <length>` | Winches to the length, 1 to 12 (test aid) | `crane winch 5 6` |
| `crane status [id]` | Prints `#id state= pos= vel= tilt= thrust= L= thetaX= thetaZ= load= mass= sat= agl= carrying= health= target=`. Without an id: every crane in the level's loaded chunks | `crane status` |
| `crane list` | Prints one short line per crane | `crane list` |
| `crane stop <id>\|all` | Goes IDLE. A load within 1.5 b of the ground is released at once; otherwise it is lowered and then released | `crane stop all` |
| `crane trace <id> on\|off` | Logs `trace crane #id t= state= pos= vel= tilt= thrust= L= theta= load= sat=` for every tick. `-Dsimpleplanes.crane.trace=true` turns it on for every crane | `crane trace 5 on` |
| `crane kill` | Removes every crane, and every non-player entity tagged `crane-load` (each picked-up mob gets that tag) | `crane kill` |
| `crane debug tmax <id> <value>` | Test aid: overrides T_MAX | `crane debug tmax 5 0.07` |
| `crane debug swinggain <id> <k>` | Test aid: sets the swing gain | `crane debug swinggain 5 0` |
| `crane debug kick <id> <deg>` | Test aid: sets the x rope angle | `crane debug kick 5 20` |
| `crane debug remote <id> link\|recall\|pickup <target>\|block <pos>\|sneakblock <pos>` | Test aid: calls the real `CraneRemoteItem` method through a `FakePlayer` holding a per-crane remote | `crane debug remote 5 block -20 -61 20` |

## Access-widener entries

None.

## Deviations from the spec, and why

1. **The rope reaction uses the exact rigid-rope two-body solution, not the spec's split formula.**
   - The spec's formula is `-(m/M)(G cos θ + Lω²) sin θ` on top of `T/M·up`. It pushes the drone *away* from the load. The physical tension pulls the drone toward the load.
   - With that formula, `Sim` diverges on the loaded 20 b step: the swing grew to 19 000° before the angle clamp was added, and now sits at the 80° clamp.
   - The exact solution is, per axis, `a_d = (T·up + M·drag + m·sinθ(G cosθ + Lω²)) / (1 + m sin²θ)`. It is applied through the same `a_extra` slot, and `SlungLoad.splitModel` switches back to the spec's formula.
   - The exact solution reproduces the design's numbers: 105 t / 0.41 b / 20.7° against the design's 116 t / 0.63 b / 25.1°, and 1.78 / 6.17° against 1.2 / 5.6°.
2. **Impact damage is `4·(|v| - 0.3)` HP, not `4·(|v| - 0.3)·10`.**
   - The literal formula gives 12 to 20 damage at cruise speed, which destroys the 10 HP drone at any wall. C16 expects it to stop at the wall with health ≤ 9.
   - The measured hit at 0.60 b/t costs 1.2 HP. It is one constant, `IMPACT_DAMAGE`.
3. **The load collides with blocks.** This is not in the spec.
   - A move that would put the load's box into a block is undone: first horizontally, then entirely.
   - A swing into a block stops on that axis.
   - The rope angle is clamped at ±80°.
   - Without this, C16's cow was swung into the wall and suffocated, and an empty hook on a 1 b rope was flung over the top.
4. **The empty hook uses damping 0.05.** Otherwise it swings 60° after every move. Loads keep the spec's 0.002.
5. **Death.**
   - The crash explosion (power 1.5, no fire, no blocks) spares the load the crane just released, through an `ExplosionDamageCalculator`. Otherwise the drone falls onto the cow and the blast kills it.
   - A crane broken by a player while it is on the ground still drops its item, as the foundation had it, so a player can pick up a parked crane. In the air it explodes and drops nothing, as the spec says.
6. **Refusals happen at order time** (as well as at ATTACH), so a horse or zombie is refused at once and the crane stays IDLE.
7. **LOWER_LOAD** descends until the load's bottom is 0.2 b above the surface and releases within 0.3 b. It does not winch out, because the drone never needs to go below agl 2.
8. **Speed limits are applied as vectors** as well as per axis, so a diagonal move keeps V_MAX and A_MAX.
9. **Loaded cruise is about 0.31 b/t**, not the design summary's "0.8 b/t". The loaded `A_MAX 0.010·min(1, 3/4) = 0.0075` balances the drag at that speed. It is the spec's numbers, not a bug.
10. **Climb at load 1.5 is 0.160 b/t** (design 0.211). That follows from the spec's vertical drag being applied to the combined mass. Empty and cow climbs are 0.240 (design 0.24 / 0.25).
11. **Extra saved fields:** `after_stow`, `delivery`, `pickup`, `rotors_off` and `load_name`.
12. **Test coordinates** are mirrored to negative x, clear of the village.

## Open questions: what changing each would take

- **Q1, manual stick or camera flying.**
  - Client input capture while the remote is held. Fabric API's `ClientTickEvents` and a `KeyMapping` can read the input, but a new C2S payload would be needed to carry it to the server.
  - A `setCameraEntity` view from the drone.
  - Server side: a `MANUAL` state whose target is set from the stick instead of an order. `CraneController.control` takes any target, so the physics is unchanged.
- **Q2, 2× scale.**
  - `QuadcopterRenderer.SCALE = 2.0F`. The rope start (`WINCH_HEIGHT`, 0.30) would scale with it.
  - The hitbox is registered in `SimplePlanesEntities` as `1.0F, 0.875F`, which needs a foundation change to `2.0F, 1.75F`.
  - Physics is unaffected. Heavier loads would additionally need `SlungLoad.MAX_LOAD` and `MultirotorPhysics.T_MAX` raised.
- **Q3, power.**
  - `QuadcopterEntity.ALWAYS_POWERED` is the switch: when false, the tick already runs rotors-off.
  - A battery or fuel model replaces that constant with a method that reads a charge or fuel field. The field would be saved and synched, and an item or upgrade would fill it.
- **Q7, recall range.**
  - `QuadcopterEntity.RECALL_RANGE` (64).
  - "Fly home instead" is a change in `orderReturn` and `tickReturn`: when the owner is out of range, target `home`, which is already saved and set at spawn or placement, instead of `holdHere()`.
- **Q8, hostiles.**
  - `QuadcopterEntity.ALLOW_HOSTILES = true`, or list individual types in the datapack tag `simpleplanes:crane_liftable`, which `refusal()` already honours.
  - All load rules are in `refusal()` and the constants just above it.

## Needs a foundation change (not done)

- **Q2 hitbox.** Only if 2× scale is wanted; see Q2 above.
- **`/aircraft status`** prints the literal `state=hover` for a quadcopter. `QuadcopterEntity.getState()` is available if the foundation wants to show the real state. `/crane status` shows it.

## Not verified (no client on the rig)

- **Rendering:** the rope line through vanilla's leash renderer, the jaw animation (`carrying`), the rotor spin speed, and the culling box following a swinging hook.
- **A carried player:** the riding pose, and sneak-dismounting at the load position.
- **The remote in a real player's hand:** click routing, and recall to a real player (FakePlayer is not in the player list, so only the out-of-range branch ran).
- **Transitions under real-time lag with a player nearby.**
- **The chicken's residual swing** (12 to 16°) is visible and cosmetic.
