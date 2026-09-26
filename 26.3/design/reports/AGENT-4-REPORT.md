# Agent 4 report: the airship and the mini helicopter

Branch `claude/aircraft-airship-26.3`, base `claude/aircraft-foundation-26.3` (`f8960f0`). Worktree
`/home/user/sp-agent-4`, module `26.3/`. Test server `/home/user/sp-test-4`, port 25604, run with
`-Xmx1536M` (edited in its `start.sh`, as the coordinator asked).

## Commits

| commit | content |
|---|---|
| `953b792` | `AirshipEntity`: the airship's flight model |
| `b9aad00` | `MiniHelicopterEntity`: getter overrides, ceiling, forced riders put off |
| `991b5f2` | `AirshipCommand`: `/airship` and `/airship miniheli` test aids |
| (this file) | report |

Files touched: only the three above and this report. `AirshipRenderer` and `MiniHeliRenderer` are unchanged.

## Part A: the airship

`AirshipEntity` overrides all six hooks, as the helicopter does.

- **Forces.** Every term is the spec's:
  - drag: `0.025 vh^2 + 0.004 vh + 0.0002` horizontally, `(0.05|vy| + 0.02) vy` vertically;
  - engine: `0.004 * throttle` along the hull (yaw, pitch), zero when `!isPowered()`;
  - static: `-0.006 * trim - 0.0006 * riders`, scaled by `-gravity/0.03`, so `isNoGravity()` removes
    both weight and buoyancy;
  - hull lift: `0.06 vh^2 * pitch_rad`;
  - backstop: `|v| <= 1.5`.
- **Heading alignment.** In `tickRotateMotion`, at 0.03 per tick, using `lerpAngle180`.
- **Fly-by-wire loop.** In `tickPitch`. It is the spec's PI loop (KP 8, KI 0.10, slew 0.02/t) with the
  outer gain 0.01, apart from two things:
  - the capture on release (deviation 1);
  - the terrain floors (deviations 2 and 3).
- **Yaw and roll.**
  - Yaw ramps 0.04 deg/t² to 0.6 deg/t.
  - Roll leans `-3 * yawSpeed` (-1.8 deg in a full turn), slewed 0.2 deg/t and clamped to 8 deg.
- **Ground.**
  - The castor sheds 25 % of the horizontal speed per tick.
  - On contact, pitch and roll are forced to 0.
  - With throttle 0 and the elevator centred, the ship is moored: the trim goes to +1 at 0.02/t, and
    `holdY` and `trimInt` follow.
- **Other overrides.**
  - `pushPerNotch()` returns 0.004. `getRotationSpeedMultiplier()` returns 0.24.
  - Save/load: `trim`, `trim_int`, `hold_y` and `throttle`, clamped on read. `getItemStack()` strips
    them.
  - `getVerticalSpeed()` and `getHorizontalSpeed()` are added.
- **Client authority.** Two synched floats, `TRIM_SYNC` and `HOLD_Y_SYNC`, are written by the server. They
  are sent only when the value changes by 0.005 or more (trim) or 0.05 b or more (hold). A client that
  takes authority, because a player boarded, seeds its loop from them once. This is not verified; see the
  last section.

## Part B: the mini helicopter

`MiniHelicopterEntity` overrides every getter in the spec's table with the spec's value:

- `ceilingThrustFactor(y) = clamp(1 - (y-100)/100, 0.4, 1)`;
- `getRotationSpeedMultiplier() = 1.8`.

The foundation's `canAddPassenger`, `tryToAddUpgrade` and `acceptsUpgrade` (payload and large upgrades
refused) are kept unchanged. New: `tick()` puts off any non-player passenger (see M7).

The Q10 defaults are used: the medical livery comes from the block tag, and the ceiling fades from y 100
to y 200. The constants are public, named and in one place.

## Final constants that differ from the spec

| constant | spec | used | why |
|---|---|---|---|
| capture on release | `holdY = y + 30 vy` fixed at release | brake to `vsCmd = 0` until `\|vy\| < 0.004`, then `holdY = y` (`CAPTURE_STOP_VS`); `y + 30 vy` is only shown while capturing | deviation 1 |
| terrain look-ahead | none | `6 + 60 vh` blocks along the hull (cap 64), every 1 b, 3 lines (centre, ±1.5); the floor rises to `max ahead + 9`; engines idle if that cannot be climbed before arrival | deviations 2 and 3 |
| pitch level-off band | within 1 b of the ground | within `1 + 20\|vy\|` b (3.4 b at 0.12 b/t) | the hull is level when the wheel touches, instead of snapping from -10 deg on contact |

## Tests

All tests were run on the final code (`991b5f2`) or on code whose flight model is identical to it (see
the S11 note). `aircraft kill` was run before each run, and the clock was frozen around each measurement.
Numbers come from per-tick traces.

### Airship

| # | target (tolerance) | measured | result |
|---|---|---|---|
| S1 | `Done (`, no `simpleplanes` exception | `Done (` in 0.3 to 1.4 s on every start; 0 `ERROR`/`Exception` lines | pass |
| S2 | y within 1.0 of -20, vs within 0.01 | y = -20.000 for all 600 t; vs 0.000 | pass |
| S3 | elevator: vs 0.09..0.14 after 100 t; static terminal 0.16..0.24 | 0.115 after 100 t, peak 0.118 (never above 0.12); `trim -1` + `hold off`: **0.200** | pass (see deviation 4 for the command) |
| S4 | overshoot <= 0.6; final within 0.5 | released at vs 0.119, captured 103 t later; **overshoot 0.14 b**, no undershoot, final error 0.000; within 0.5 b of the hold 52 t after release | pass |
| S5 | sag <= 4.5; final within 0.5; trim -0.85..-0.55 | sag **2.99 b**; final +0.01 b; trim **-0.700** | pass |
| S6 | vh ±0.10 of 0.32/0.48/0.61/0.72/0.81; y within 2 b | **0.317 / 0.484 / 0.611 / 0.719 / 0.813**; y error 0.000 throughout (7505 ticks, all ran) | pass |
| S7 | 0.5..0.7 deg/t after t 200; radius 60..95; pitch ±1; roll ±3 | track rate mean **0.600** (every 20-tick mean 0.597..0.604); radius **76.6 b** (from v/ω, and from the 153.3 b circle width); pitch -0.02..0; roll -1.80; sideslip about 11 deg | pass |
| S8 | touchdown 20..35 s; sink <= 0.15; health unchanged | touchdown at **27.15 s** (543 t), sink 0.120, health 10/10, pitch 0 at contact | pass |
| S9 | on the ground; trim +1 within 100 t | on the ground all 1200 t; trim from 0.02 to +1 in **49 t** | pass |
| S10 | airborne within 150 t | `og=false` after **67 t**, 1 b up after 94 t (on the final jar: 77 t) | pass |
| S11 | the hold lifts ahead of the wall, or the ship stops softly; health >= 8/10 | see below; health **10/10** | pass |
| S12 | < 0.05 b/t within 600 t; altitude within 2 b | from 0.813: below 0.05 after **252 t**; altitude error 0.000 | pass |
| S13 | entity present; trim, holdY, throttle unchanged; within 1 b after 200 t | before: trim -0.2808, trim_int -0.2694, hold_y 40.0, throttle 3, riders 3. After the restart: -0.2810 / -0.2697 / 40.0 / 3 / 3. Two ticks ran between save and freeze. After 200 t, y 39.74..39.90 (hold 40) | pass |
| S14 | `item-has-trim=false` | false on every status line | pass |
| S15 | riders = 7, the eighth not mounted | `board 45 1` with an eighth villager in range: "boarded 0 of 1"; riders 7 after 200 t | pass |
| S16 | health reduced 1..4, not destroyed | as specified (`tick step 4`), the gondola is still 1.0 b short of the wall and health is 10. Continued: contact on tick 6 at 0.695 b/t, **destroyed** (the explosion holed the wall). With `Motion 0.3`: contact at 0.254 b/t, **-1 health** (10 to 9) | **fail as specified**; see open issue 1 |

**S11 in detail.**

- Setup:
  - wall 41 × 20 blocks (top surface y -40) at z -170;
  - ship spawned at z -200, y -55, `airship hold -55`;
  - throttle 3.
- The 5-column floor lifted the hold to -51 at once. The spec's "hold at ground+5" is refused by design.
- At t 33, z -194 (24 b before the wall) and 0.31 b/t:
  - the look-ahead lifted `holdY` to **-31 = wall top + 9**;
  - it idled the engines.
- The ship coasted, climbing, and reached the wall at about 0.1 b/t with y still -40.6:
  - it rested against the wall on the gondola's hitbox, with no damage;
  - it climbed to -31;
  - it passed over at t 382 and flew on at -31.
- Health stayed **10/10**. So both halves of the criterion hold: the hold was lifted before the wall, and
  the ship stopped softly.
- Before the look-ahead step was cut from 3 b to 1 b, the probe missed the one-block-thick wall two ticks
  in three. The ship hit it at 0.456 b/t and dropped to 3/10. That run is why the step is 1 b.

**An unplanned S11 on the aircraft seed.** In the first S6 run at y -20 there is a structure at x 0,
z ≈ 1660..1705. Its surface is about y 11, over 70 b above the flat ground.

- The look-ahead saw it 40 b ahead and idled the engines.
- The ship slowed from 0.60 to 0.20 b/t and climbed to y 20.
- It passed over with health 10/10.

S6 was rerun at y 40 for clean numbers.

### Mini helicopter

| # | target (tolerance) | measured | result |
|---|---|---|---|
| M1 | ±0.03 of -0.43/-0.25/0/+0.18/+0.30/+0.40 | **-0.432 / -0.246 / 0.000 / +0.178 / +0.304 / +0.403** | pass |
| M2 | drift < 2 b over 600 t | 1.18 b. All of it comes from two one-tick engine power gaps, 0.6 b each; see open issue 3 | pass |
| M3 | vh 0.65..0.85; at least 0.25 below the helicopter | Full cyclic, integer notches. Mini: notch 2 gives 0.691 at vs -0.075; notch 3 gives 0.833 at vs +0.115; interpolated to vs 0: **0.747**. Helicopter: notch 3 gives 1.044 at vs -0.054; notch 4 gives 1.200 at vs +0.088; at vs 0: **1.103**. Difference **0.356** | pass (see note) |
| M4 | 4.3..4.7 deg/t, on the ground and in hover | **4.500** on both, reached on the 4th tick | pass |
| M5 | pitch -30 ±1 within 8..11 t | -3.5 deg per tick; -30 on the **9th** tick | pass |
| M6 | settles at y 150..170; sinks at notch 3 | notch 5 from y -30: 66.98 at 250 t, 145.5 at 500 t, **160.0** from 1000 t on. Notch 4: settles at **149.8**. Notch 3: sinking at -0.010 b/t, 134.2 after 300 t | pass |
| M7 | never mounts by itself; `/ride` refused | 200 t beside it: riders 0. `/ride ... mount` is **accepted by vanilla** ("Villager started riding"; `/ride` forces the mount, so `canAddPassenger` is never asked). The new `tick()` puts the villager off on the next tick: riders 1, then 0, then 0 after 200 t | pass, with the change described |
| M8 | code reading | `tryToAddUpgrade` returns false for `getLargeUpgradeFromItem(item).isPresent()` and for `PlanePayloadReloadListener.payloadEntries.containsKey(item)` before calling `super`. `acceptsUpgrade` refuses SEATS, SHOOTER, FLOATY_BEDDING, PAYLOAD and every `LARGE_ITEM_UPGRADE_MAP` value, which also closes the wrench path. **Verified by code reading only** | pass |
| M9 | white wool medical; oak standard | white_wool: `livery=medical`; oak_planks: `livery=standard`; quartz_block: `livery=medical` | pass |
| M10 | helicopter ±0.03 of -0.43/-0.31/-0.17/0/+0.13/+0.24 | **-0.432 / -0.312 / -0.173 / 0.000 / +0.134 / +0.238** (median of the last 100 t) | pass |
| M11 | contact at vs -0.20..-0.30; health unchanged | -0.246 on the last full tick before contact; health 10 | pass |
| M12 | controls and livery unchanged | throttle 3, cyclic_forward 20, cyclic_right -10, pedal 1, collective_boost 1, material white_wool before and after the restart; `livery=medical` | pass |

The M3 note: integer notches never give `|vs| < 0.02` at full tilt, for either aircraft. The design's
2.31 and 3.31 notches sit between two settings. So each aircraft was measured at the two neighbouring
notches, and `vh` was interpolated linearly to `vs = 0`. The raw pairs are in the table.

## Commands added

All are under `/airship`, permission level 2, and work from the console. Every result line is also logged
at INFO by logger `simpleplanes-airship`.

| syntax | effect | example |
|---|---|---|
| `airship status` | One line per airship in loaded chunks: `#id pos= vh= vs= hdg= trk= pitch= roll= trim= trimInt= holdY= riders= thr= elev= rud= og= agl= vsCmd= cap= blocked= fbw= health= item-has-trim=`. `vh` and `vs` are the displacement over the last tick. `trk` is the track heading. `cap` means capturing. `blocked` means the engines are idled by the look-ahead. `item-has-trim` calls `getItemStack()` | `airship status` |
| `airship trim <id> <-1..1>` | Sets `trim` and the loop's integrator (test aid; the loop keeps running) | `airship trim 45 -1` |
| `airship hold <id> <y>` | Centres the elevator, turns fly-by-wire on and holds `y`. The envelope floor still applies | `airship hold 45 -20` |
| `airship hold <id> off` | Turns fly-by-wire off: the ballast stays where it is, and the hull pitch goes to 0. Used for the static terminal | `airship hold 45 off` |
| `airship trace <id> on\|off` | One INFO line per tick the ship actually ran: `trace airship #id t=N <status fields>` | `airship trace 45 on` |
| `airship board <id> <n>` | Mounts up to `n` villagers within 8 b of the hitbox, nearest first, through `startRiding` (not forced). Reports `boarded k of m villagers in range; riders=r` | `airship board 45 7` |
| `airship miniheli status` | One line per mini helicopter: `#id pos= vh= vs= hdg= pitch= roll= thr= boost= cyc=fwd,right ped= livery= riders= og= agl= health=` | `airship miniheli status` |
| `airship miniheli trace <id> on\|off` | `trace miniheli #id t=N <status fields>` per tick | `airship miniheli trace 95 on` |

**The foundation's `/aircraft hold`, `trim` and `takeoff` drive `PITCH_UP`.** On the airship that input is
the elevator, so those commands would work against its own hold. Use `/airship hold` instead. The
foundation's own altitude-hold change does not touch the airship.

## Access-widener entries

None.

## Deviations from the spec, and why

1. **Capture at the stop point.**
   - A tick-exact port of the spec's loop reproduces every other §11.2 number. Examples: sag 2.99, trim
     -0.700, cruise 0.318..0.814, radius 76.9, touchdown at 542 t.
   - But its capture overshoots by **1.05 b**, over S4's 0.6 limit. The design's "0.08 b" is the error
     at t = 600, not the peak.
   - The limit is the trim slew: 25 ticks to swing from -0.5 to 0, during which the ship keeps climbing.
   - Leads of 40..60 still left 0.47..0.61 b.
   - With the change, a released elevator commands `vs = 0` (or a climb back to the floor), and the hold
     is captured where `|vy|` falls below 0.004. Measured overshoot: 0.14 b.
   - The loop, the gains and the outer law are otherwise unchanged.
2. **Terrain look-ahead and engine idle.**
   - The 5-column probe sees only 4 b ahead. A ship at 0.61 b/t needs about 250 ticks to climb 29 b.
   - Under `PlaneCollisions` (mass 2.0, factor 20), any wall contact above 0.53 b/t destroys it.
   - So the probe alone cannot pass S11. The look-ahead:
     - raises the floor to `max ahead + 9`;
     - idles the engines while the terrain ahead is higher than the ground under the ship and higher
       than the ship can climb before it arrives (0.8 × 0.12 b/t after a 30-tick delay).
   - Nothing changes over flat ground or with no terrain ahead.
3. **Look-ahead step 1 b.** At 3 b, it missed a one-block-thick wall two ticks in three (measured).
4. **`hold <id> off` as a test aid.** The spec's S3 recipe (`hold <id> 1000`) cannot show the 0.20 b/t
   static terminal: the loop commands at most 0.12. So fly-by-wire can be switched off, which freezes the
   ballast.
5. **Landing needs `Ground.isLandable`.** Over water, the floor stays at the surface plus 9, so the
   gondola is never driven into a lake. And once landed on landable ground, the floor is the ground. A
   landed ship with the engines running, elevator released, therefore stays down instead of lifting to
   9 b.
6. **A dead airship** (health 0) takes on full ballast, sinks at 0.2 b/t and crashes on contact through
   `PlaneEntity`'s rule. It has no yaw and no hold.
7. **Mini helicopter, M7.** Vanilla `/ride` forces the mount past `canAddPassenger`. `tick()` now puts off
   any non-player passenger on the next tick. Refusing inside `addPassenger` would leave the rider's
   vehicle link half set.

## Open issues

1. **S16 cannot pass as written** (needs a design or foundation decision).
   - `massOf(airship) = 2.0` with `H_DAMAGE_FACTOR` 20 and `H_TOLERANCE_AIR` 0.15 gives
     `damage = 40 (v^2 - 0.0225)`.
   - So 1..4 health corresponds only to 0.19..0.35 b/t. Ten health is lost at 0.53 b/t.
   - `Motion 0.8` into a wall is therefore fatal, and 0.3 costs 1 health, as measured.
   - Either the expectation or the airship's mass or tolerance has to change. Both are outside my files.
2. **Elevator authority at cruise.**
   - At T5, 10 deg of hull pitch adds `0.06 vh^2 sin p + 0.004 T sin p` ≈ 0.0104 b/t² of lift. That is
     more than the ballast's whole range, 0.006.
   - Measured: elevator up gives vs peak **0.248**, steady 0.157, with the trim saturated at +1.
   - Elevator down gives peak **-0.320**, steady -0.197, with the trim saturated at -1.
   - The capture after release is still clean: 35 t, 0.01 b.
   - The spec's formula is kept. If ±0.12 must hold at speed, one fix is to scale the pitch target by
     about `1 / (1 + (vh / 0.4)^2)`.
3. **The furnace engine has a one-tick power gap per coal item** (existing code, not in my files).
   - `burnTime` reaches 0 one tick before the next coal loads.
   - This gives a one-tick thrust gap every `1600 / fuelCost` ticks: every 200 t on the airship (a 0.33
     deg/t blip in S7's track rate) and every 400 t on the mini helicopter.
   - At hover it drops the helicopters about 0.6 b each time, which is all of M2's drift.
4. **Taxiing near obstacles.** On the ground, any column ahead that is higher than the ground under the
   wheel, within the few-block reach at rest, idles the engines. A moored ship facing a wall or a house
   will not drive into it, but it will not creep across a raised step either. The elevator lifts off
   normally.
5. **Harness: the first spawn after a start can be lost.**
   - The first `/aircraft spawn` right after a fresh start or a restart twice lost its entity within
     about 100 ticks. It reappeared later with a new id (unloaded and reloaded).
   - The cause looks like the entity-section load race at spawn. Spawning again worked every time.
   - Unloaded airships also persist in the world and fly on when their chunks load again. One of them
     rammed a later test ship; that run was discarded and rerun.
6. **World:** as the foundation said, never-generated chunks stall or unload aircraft. The corridor x -48..47,
   z -320..5439 and the square ±320 were pregenerated with `forceload` batches before measuring.

## What could not be verified headless

- **Airship:**
  - the envelope and its culling box;
  - the rudder and elevator deflections (the model is fed `-state.rudder`);
  - propeller spin;
  - the 1.8 deg lean;
  - where the seven riders sit;
  - the first-person view out of the cabin;
  - the client seeding its loop from `TRIM_SYNC`/`HOLD_Y_SYNC` when a player boards, and flight with a
    client-authoritative player in general.
- **Mini helicopter:**
  - the bubble view and the glass sorting;
  - the medical livery as drawn;
  - rotor spin and the seat position;
  - its handling with a real pilot.
