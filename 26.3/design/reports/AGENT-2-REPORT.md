# Agent 2 report: the fighter

Branch `claude/aircraft-fighter-26.3`, base `origin/claude/aircraft-foundation-26.3` (`f8960f0`).
Worktree `/home/user/sp-agent-2`, test server `/home/user/sp-test-2`, port 25602, run at `-Xmx1536M`.

## Commits

| commit | content |
|---|---|
| `d245d6b` | `FighterEntity` flight model; `commands/FighterCommand` (`/aircraft fighter hold`, `/aircraft fighter level`); one registration line in `AircraftCommand.register` |
| (this commit) | this report |

## What was done

- **`FighterEntity`** carries the spec's parameter table unchanged. Each value is a public constant, so
  retuning means changing one line.
  - `setMaxSpeed(2.5f)` in the constructor.
  - `getMotionVars()` sets `maxSpeed` 3.0, `takeOffSpeed` 0.45, `stallSpeedFactor` 0.6,
    `liftSaturationFactor` 1.3, `maxLift` 2.0, drag 0.00145 / 0.0005 / 0.001, `pitchToMotion` 0.3,
    `yawToMotion` 0.2 and `motionToRotation` 0.05.
  - The hooks return `pushPerNotch` 0.012, `maxRollRate` 8, `groundPitchLimit` 10,
    `groundRollingResistance` 0.030 and `groundLinearDragFactor` 24.
  - Unchanged from the foundation: rotation multiplier 1.4, ground pitch 0, seat, rider count, item,
    upgrade filter and camera multiplier.
- **Two additions to `FighterEntity` that the spec did not list.** Both are local to the fighter. The
  deviations section explains each.
  1. `tickOnGround` also applies the ground pitch clamp.
  2. The throttle is saved with the world, but not with the item form.
- **`FighterCommand`** adds two test aids. The harness's `hold` cannot fly the fighter level above
  1.5 b/t, and full rudder with no roll input does not keep the wings level (measured below).
- No access-widener entries. No mixins.

### Final parameter table

The table is as in the spec. Nothing was retuned: every measured number is within ±0.01 b/t or ±0.05 deg/t
of the design's simulation.

## Test method

- Every run is frozen: `tick freeze`, the commands, the sprint, then read `status` and `trace` from
  `console.log`.
- **Stepped sprints.** Long runs sprint in steps of 50 ticks with a 0.3 s pause between steps. Each step
  adds one extra tick, so a "1500" run is 1530 ticks.
  - The reason: in a single long sprint the fighter can outrun chunk loading. At 2.3 b/t it flew into a
    chunk that was not loaded yet, was saved to disk with that chunk, and the harness lost it (z 11247).
    This happened even though that part of the corridor had been generated beforehand.
- **Corridor.** The corridor x -80..79, z -192..16000 was pre-generated with `forceload add`/`remove`
  batches of 250 chunks.
- **Altitude 60 instead of -30.** F4, F5, F8 and F9 fly at y 60, which is 120 b above the ground; see
  deviation 1.

## Acceptance tests (final jar `d245d6b`)

| # | pass criterion | measured | result |
|---|---|---|---|
| F1 | `Done (`, no `simpleplanes` exception | `build --offline` BUILD SUCCESSFUL (15 to 21 s). `Done (0.284s)`. `simpleplanes 5.3.15` loaded. 0 `Exception` lines over every session | pass |
| F2 | rotation 4..8 b; airborne 9..18 b at 0.65..0.90 b/t; pitch at lift-off ≤ 10.0 | rotation **5.6 b (21 t)**, airborne **12.6 b (33 t) at 0.77 b/t** (design 5.6 b / 20 t, 12.5 b / 32 t, 0.76; the harness counts the command tick). Pitch on the last ground tick (t 26, `og=true`) was **9.2**, and that was the highest pitch on any ground tick. See the F2 note below | pass |
| F3 | T3 `spd` 0.14..0.26; T5 0.70..0.95 on the ground | T3: **0.186 / 0.191** (100-tick mean 0.192). T5: **0.840 / 0.836** (100-tick mean 0.838). It stays on the ground (`og=true`, pitch 0.0), as the design predicts. T2: 0.024 (design 0.02) | pass |
| F4 | `spd` ±0.20 of 1.08 / 1.46 / 1.77 / 2.04 / 2.30; altitude within 4 b | see the F4 table | pass |
| F5 | ratio ≥ 2.6 | starter plane, same procedure at T5: **0.749** (design 0.748). Ratio **2.300 / 0.749 = 3.07** | pass |
| F6 | trim 10: `vs` 0.35..0.60; trim 20: 0.55..0.90 | trim 10: **0.484 / 0.485** (100-tick mean 0.500) at 2.04 b/t, pitch 9.2..10.9. Trim 20: **0.765 / 0.763** (100-tick mean 0.766) at 1.87 b/t. Design 0.485 / 0.75 | pass |
| F7 | `vs` -0.08..-0.16, `spd` 0.35..0.55 | **spd 0.427 / 0.428, vs -0.121 / -0.118** (100-tick mean -0.121). Design 0.43 / -0.12 | pass |
| F8 | mean hdg rate in 40-tick windows after t 60: 2.4..3.5 deg/t; radius 30..48 b; loses ≤ 15 b | **2.93 deg/t in every window** from t 20 to t 300, for both the nose and the track. **Radius 40.3 b at 2.06 b/t**. Altitude 59.85..60.02, so at most 0.15 b lost. Design: 2.92 deg/t, 39 b at 2.0. Wings held level with `fighter level` (roll 0.7..1.2) | pass |
| F9 | `roll` changes 7..8 deg/tick once ramped | ramps +0.5 deg/t² to **8.0 deg/tick at t 16**, then 8.0 every tick through t 61 | pass |
| F10 | speed decays below 0.27; descends; no exception | Speed 0.60 → **0.269 at t 38**, settling at **0.254**. `vs` is negative from t 25 and steady at **-0.066** to t 300. Nose 14..15 deg, no exception. It first zooms up 0.58 b in 25 ticks while still above stall speed | pass |
| F11 | outcome line, no exception; report held speed and oscillation | See the F11 note below. Health 10 afterwards, no exception | pass |
| F12 | lands or reports why; alive afterwards | `survey -12 -60 0 12 -60 -160` registered `airfield-1` (36/18, 160x25). `inbound 0 -20 700 "airfield-1" 1.2 type fighter` is accepted. Outcome line: "landed at airfield-1/36, **35 blocks down** the 160-block runway (22% used), parked at airfield-1, 19, -60, 13". Final approach 0.37..0.47 b/t, sink 0.04..0.09. Touchdown at 0.31 b/t, sink 0.084. **Health 10** | pass |
| F13 | same throttle and health after a restart | Before `save-all flush`, health **7** (after `damage ... 3`), throttle **4**, `max_speed` 2.5. After a restart: health **7**, throttle **4**, `max_speed` 2.5, tag `aircraft-test` kept | pass (needs the throttle save; see deviations) |
| F14 | health reduced or entity gone; nothing hangs | Summoned at 2.0 b/t, 6 b from a stone wall. Health 10 after ticks 1 and 2 (z 392.46, 394.38). On **tick 3** it hits the wall at 1.92 b/t and **the entity is gone**: it drops one `simpleplanes:fighter` item, and the wall breaks into cobblestone items. Nothing hangs | pass |

F2 note:

- The harness prints `pitch 45.8`. That is the pitch at `y0 + 1`, eight ticks after the wheels left the
  ground at t 27, while the harness is still holding full up elevator. The clamp only acts on the ground,
  so it does not apply there.
- The clamp never engages in a full-throttle take-off, because lift raises the aircraft before the nose
  passes 10 degrees.
- Where the clamp does act is a nose-high touchdown; see "Clamp check".

F4 at y 60, launched at 1.0 b/t, using `fighter hold 60`. Each reading is taken after 1530 ticks at the new
throttle:

| throttle | `spd` at reading | 102-tick mean | design | altitude, last 500 t | trim (pitch) mean, range | design trim |
|---|---|---|---|---|---|---|
| 1 | 1.082 | 1.082 | 1.08 | 59.94..60.03 | -1.33, -1.8..-1.0 | -1.4 |
| 2 | 1.461 | 1.459 | 1.46 | 59.94..60.03 | -2.69, -3.2..-2.3 | |
| 3 | 1.764 | 1.761 | 1.77 | 59.91..60.06 | -3.35, -3.9..-3.0 | |
| 4 | 2.041 | 2.038 | 2.04 | 59.93..60.05 | -3.78, -4.3..-3.3 | |
| 5 | 2.300 | 2.304 | 2.30 | 59.95..60.12 | -4.10, -4.5..-3.6 | -4.8 |

F11 note: `autopilot route 0 -20 0 800 -20 0 2.0 type fighter`, 4000 ticks.

- **Outcome line.** "Plane #30 landed at field-30/36, 20 blocks down the 80-block runway (25% used) at 0,
  -60, 20". The route cruises at y 40.
- **Cruise holds well.** Outbound: speed 1.95..2.07, mean 2.00 (2.00 commanded), altitude 39.3..40.0, `vs`
  within ±0.1. Inbound: speed 1.97..2.06, altitude 39.6..39.9. There is no oscillation, and the maximum
  roll is 35.7 deg.
- **Three go-arounds**, each "terrain in the approach corridor". The improvised field at the origin
  has the village at x 55..75 in its approach. This is the autopilot's terrain check, not the fighter's
  handling.
- No `AutopilotConfig` quantity is at fault.

Clamp check. Spawned at y -56 at 0.5 b/t, throttle 0, `trim 15`. It mushes down and touches the ground with
the nose at 14.7 deg (t 90):

| build | pitch on the ticks after touchdown | result |
|---|---|---|
| before the `tickOnGround` override | 13.3, 11.9, 10.8, 9.7 | above the limit for three ticks |
| final | **10.0**, 9.0, 8.1 | clamped on the first ground tick |

Turn information (not part of the acceptance table):

- **With no roll input and no leveller**, the bank drifts.
  - Yawing the body while the nose is trimmed 4 degrees down couples into roll, and nothing damps the
    roll. The bank is 4 deg at t 20, 24 at t 100 and saturates at 57..67 from t 180.
  - The heading rate over 40-tick windows was 2.99, 3.15, 3.47, 4.31, 6.21 and 6.54 deg/t (radius 18 b
    at the end).
  - The pitch-up that the hold commands in the bank adds to the turn rate. The hold then loses
    altitude authority, and the fighter climbs to y 78.
- **Autopilot banked turn** (F11 reversal at x 800): 3.33 deg/t at 24..28 deg of bank and 2.06 b/t, which
  is a radius of 35 b.

## Commands added

All of them are under the foundation's `/aircraft` root:

- permission level 2, and they work from the console;
- results are reported like the harness's, to the command source and to logger `simpleplanes-aircraft`;
- registered by one line in `AircraftCommand.register`: `FighterCommand.register(root);`;
- they work on any `PlaneEntity`, not only the fighter.

| syntax | effect | example |
|---|---|---|
| `aircraft fighter hold <id> <y>` | An altitude hold whose gains do not depend on airspeed. Once per tick, before the entity ticks: it commands a flight-path angle `clamp(0.5 * (y - Y), ±10)` deg. It aims the nose at the low-passed nose-to-path angle (the trim estimate), plus that angle, plus 0.5 × the path error, plus a small integral (0.002 per block-tick, ±3 deg, within 10 b). It drives `pitchUp` with the harness's own stop-point bang-bang. It does nothing on the ground. Turn the harness's `hold`/`trim` off first, because both write `pitchUp` | `aircraft fighter hold 7 60` |
| `aircraft fighter hold <id> off` | Stops the hold and centres `pitchUp` | `aircraft fighter hold 7 off` |
| `aircraft fighter level <id> on\|off` | A wings leveller. It writes the test roll input (`TEST_STRAFE`) as a bang-bang aimed at where the roll will stop under `tickRoll`'s 0.5 deg/t² ramp, with a 1 deg deadband. `off` centres the input | `aircraft fighter level 7 on` |

Why `fighter hold` exists. The harness's `hold` on the fighter, 800 ticks at y 60, readings from t 300:

| throttle | speed | altitude | pitch | result |
|---|---|---|---|---|
| T1 | 1.08 b/t | 59.6..61.0 | -2.1..-0.8 | fine |
| T2 | 1.46 b/t | 59.2..61.8 | -3.5..-2.2 | fine |
| T3 | 1.77 b/t | 66.0..74.9 | -19.5..14.0 | oscillates |
| T5 | 2.30 b/t | 71.2..81.1 | -19.9..14.4 | oscillates |

- **Cause.** Its damping term `-60 * vy` is in b/t. At 2 b/t one degree of flight path is about 0.04 b/t,
  so the loop gain is three times the starter's.
- **The integral band is also too narrow.** The fighter's T5 trim of -4 deg gives an 8 b P-only offset,
  which is outside the 5 b band, so the integral never engages.

## Access-widener entries added

None.

## Deviations from the spec, and why

1. **Altitude 60 instead of -30** for F4, F5, F8 and F9, and x = 0 for the corridor.
   - Trial chambers on this seed float above the superflat, right on the +z corridor:
     `locate` puts them at (16, 1744) and (96, 4960), and one near z 1744 reaches y 11.
   - Another structure near z 6670 reaches y 27, by `agl` from the heightmap.
   - A fighter holding y -30 disappeared at z 1751. A `simpleplanes:fighter` item was found there,
     together with tuff-brick and waxed-copper block items, so it had crashed into that chamber.
   - The flight model has no altitude term, so the numbers do not depend on the height.
2. **`fighter hold` instead of `hold`** for F4, F5 and F8. The harness's hold oscillates at ≥ 1.77 b/t
   (table above). F5 flies the starter with the same `fighter hold`, as the spec's "same procedure"
   requires. On the starter it gives 0.749, which matches the foundation's 0.747 with its own hold.
3. **F8's "unbanked" uses `fighter level on`.** With `set roll 0` alone the bank does not stay at zero
   (see the turn information). F8's pass criterion also holds without the leveller for the windows
   t 60..100 (3.15) and t 100..140 (3.47), but not after that, once the bank passes 36 deg.
4. **`FighterEntity.tickOnGround` also clamps the pitch to `groundPitchLimit()`.**
   - `PlaneEntity` applies the clamp only at the end of `tickPitch`, and `tick()` skips `tickPitch` on the
     ground below take-off speed.
   - Without the override, a nose-high touchdown stayed above 10 deg for three ticks (clamp check above).
   - The override is local to the fighter; see "Needs a foundation change".
5. **`FighterEntity` saves the throttle with the world.**
   - `PlaneEntity` does not save it, so F13's "same throttle" would read 0 after a restart.
   - It is modelled on `HelicopterEntity`: saved as `throttle`, clamped to 0..10 on load, and removed from
     the item form in `getItemStack()`, so a placed fighter starts at throttle 0.
6. **F5 at T5 only.** The starter was launched at 1.0 b/t, held, and flown 1530 ticks at T5, rather than
   through T1..T4 first. Only the T5 steady state enters the ratio.

## Needs a foundation change

I did not make any of these changes; each is a recommendation. None blocks the fighter.

- **`PlaneEntity`: the ground pitch clamp.** `groundPitchLimit()` is applied only inside `tickPitch`,
  which does not run on the ground below take-off speed. Also applying it after `tickOnGround` in `tick()`
  would fix it for every plane, and the airliner (Agent 3) has the same gap. The fighter's override then
  becomes redundant but harmless.
- **`PlaneEntity`: the throttle is not persisted.** This could move into `PlaneEntity`, with the same
  item-form stripping as the helicopter, and replace the fighter's and helicopter's copies.
- **`AircraftCommand.hold`: the damping term.** It should work on the flight-path angle, not on `vy` in
  b/t, and its integral band (5 b) is too narrow for trims beyond about 2.5 deg. `fighter hold` is one way
  to do it.
- **`AircraftCommand`: aircraft lost during a sprint.**
  - A fast aircraft in a long `tick sprint` can enter an unloaded chunk. It is then saved with that
    chunk, `level.getEntity(id)` returns null, and the harness drops its control and its tickets.
  - A longer lead ticket, or a note in the testing docs to sprint in steps, would avoid it.
- **`AircraftCommand.takeoff`: the reported pitch** is the pitch at `y0 + 1`. Reporting the pitch on the
  last ground tick as well would make "pitch at lift-off" measurable directly.
- **Autopilot, for information.** `AutopilotSpawner.orient` sets `setMaxSpeed(1.0)` and `fitBooster`
  then sets `ROUTE_MAX_SPEED` 3.0. An autopilot fighter therefore flies with thrust fade at 3.0 (not 2.5)
  and a booster (throttle 10). F11 held its commanded 2.0 b/t without trouble.

## Not done

- **No booster-specific tuning.** Throttle 6..10 uses the same `pushPerNotch`.
- **No new autopilot gains.** `AutopilotConfig` is unchanged: F11 and F12 flew and landed with the stock
  gains.

## Not verified (no client on the rig)

- The nozzle animation (`FighterExhaustModel`, from `throttle / 5`) at throttle 0, 3 and 5, and the
  afterburner with a booster.
- The canopy view from the seat, with the `CameraMixin` eye and with the vanilla eye. The seat placement
  (feet `(0, 0.0625, 0.125)`) with a real player, and the arm clipping the contract notes.
- **The rider (client-authoritative) path.** Every number above is from the unmanned server path.
- **How the fighter feels to a player** (DESIGN.md §12 risk 2): the pitch ramp of 7 deg/t and the 4 deg
  nose-down trim at T5.
