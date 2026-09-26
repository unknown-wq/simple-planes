# Agent 3 report: the mini airliner

Branch `claude/aircraft-airliner-26.3`, base `origin/claude/aircraft-foundation-26.3` (`f8960f0`).
Worktree `/home/user/sp-agent-3`, module `26.3/`. Test server `/home/user/sp-test-3`, port 25603, run with
`-Xmx1536M` (edited in the server's own `start.sh`, not in the repository).

## What was done

- `entities/AirlinerEntity.java`: the flight numbers of `DESIGN.md` §4, unchanged from the spec; the seat
  mapping, the passenger limit and the server-side seat transform (see "Deviations"); the throttle kept in
  the world save but not in the item; the logo rolled when the entity is constructed on the server.
- `commands/AirlinerCommand.java`: `/airliner status`, `board`, `logo`.
- `client/render/AirlinerRenderer.java`: unchanged. The foundation's skin switch and logo wiring already
  do what the spec asks.

No other file is touched. No access-widener entry, no mixin.

## Final parameters

As in the spec's table; nothing was retuned.

| hook / field | value |
|---|---|
| `pushPerNotch()` | 0.004 |
| `MAX_SPEED` data (constructor) | 2.0 |
| `maxSpeed` / `takeOffSpeed` / `stallSpeedFactor` / `liftSaturationFactor` / `maxLift` | 2.0 / 0.60 / 0.6 / 1.25 / 2.0 |
| `dragQuad`, `dragMul`, `drag` | 0.0006, 0.0003, 0.0005 |
| `pitchToMotion` / `yawToMotion` / `motionToRotation` | 0.16 / 0.06 / 0.05 |
| `getRotationSpeedMultiplier()` | 0.35 |
| `maxRollRate()` | 2.5 |
| `getGroundPitch()` / `groundPitchLimit()` | 0 / 12.0 |
| `groundRollingResistance()` / `groundLinearDragFactor(f)` | 0.007 / 5.0 |
| `getLandingAngle()` | 20 |

## Tests

All numbers come from the final jar (`simpleplanes-26.3-5.3.15.jar` built from the branch head), on
`/home/user/sp-test-3`. Every measurement was taken with the clock frozen and sprinted. A test corridor
x -16..15, z -32..2400 was force-loaded, and so was the autopilot corridor x -32..895, z -48..47. The
reason: on a fresh world a sprint at 3000+ ticks/s outruns chunk generation. An airliner flying +z was
saved into an unloaded chunk at z ≈ 350 and dropped out of `status`. It came back when the chunk was
loaded again. Trace and status speeds are measured displacement per tick (the foundation's convention).

| # | test | measured | target | result |
|---|---|---|---|---|
| A1 | boot | `Done (` in 3 to 7 s; 0 `ERROR`/`Exception` lines over every session | `Done (`, no exception | pass |
| A2 | take-off run | rotation **33.8 b** (90 t); airborne (y0 + 1) **51.4 b** (117 t) at **0.71 b/t**; max pitch while `og=true` **10.0** | rotation 25..45 b; airborne 40..65 b at 0.62..0.80; pitch ≤ 12.0 | pass |
| A3 | the run is long | plane airborne at 11.2 b (46 t); ratio 51.4 / 11.2 = **4.6** | ≥ 3.0 (design 4.4) | pass |
| A4 | throttle 3 cannot take off | after 800 t: `og=true`, `spd=0.399` | `og=true`, 0.30..0.50 (design 0.40) | pass |
| A5 | taxi at throttle 2 | after 800 t: `spd=0.107` | 0.05..0.20 (design 0.11) | pass |
| A6 | level speeds, `hold -30` | T2 **0.904**, T3 **1.042**, T4 **1.153**, T5 **1.251** (mean of ticks 1200..1500); y -30.2..-29.8; nose -0.6 / -2.1 / -3.1 / -3.8 | ±0.15 of 0.91 / 1.05 / 1.16 / 1.25; altitude within 4 b | pass |
| A7 | turn at T5, `set yaw 1` | airliner heading rate **0.515** deg/t (ticks 100..140), 0.523 (140..180); course rate 0.50..0.53; speed 1.27; radius **138..144 b**. Same method: plane 1.76 deg/t, radius 25 b; cargo 0.35 deg/t, radius 124 b | 0.35..0.65 deg/t; radius 100..190 b; between plane and cargo | pass |
| A8 | climb at T5 | trim 10: `vs` **0.163** at 0.90 b/t; trim 20: `vs` **0.195** at 0.71 b/t (ticks 200..500) | 0.10..0.24 (design 0.166 / 0.19) | pass |
| A9 | glide, throttle 0, trim -5, from y 40 | `vs` **-0.129**, `spd` **0.691**, glide ratio 5.3 | `vs` -0.10..-0.20, `spd` 0.6..0.9 (design -0.145 / 0.74) | pass |
| A10 | six riders | `board`: 5 villagers in seats 1, 2, 3, 4, 5 at +2.34, +1.22, +0.09, -1.03, -2.16 b along the nose axis; the sixth `refused, no free passenger seat`; `riders=5`; `Passengers` holds 5 villagers | `riders=5`, sixth refused | pass |
| A11 | riders and take-off | with 5 villagers aboard: rotation 33.8 b, airborne **52.1 b** at 0.71 b/t; still `riders=5` with the same seats in flight | airborne within 65 b | pass |
| A12 | logo persistence | five summons rolled 5, 0, 2, 3, 5 (an earlier jar: 2, 1, 1, 4, 0). After `save-all flush` and a restart, each airliner has the same logo at the same position. The logo is set before the first tick | same value; ≥ 2 distinct values | pass |
| A13 | logo through the item | `airliner logo <id> 4` → status `item-logo=4`. A dropped item's `entity_tag` holds `Logo: 4` and `max_speed: 2.0f`, and no `throttle` | `item-logo=4` | pass |
| A14 | metal skin | `iron_block`, `copper_block`, `waxed_copper_block`, `gold_block`, `netherite_block` → `skin=metal`; `oak_planks`, `stone` → `skin=wood` | iron metal, oak wood | pass |
| A15 | clamp releases in flight | `og=false` from tick 104 at pitch 10.8; pitch 13.2 at tick 106 (2 ticks later); 25.6 at y0 + 1 | > 12 within 40 t of `og=false` | pass |
| A16 | autopilot sortie | `Plane #33 flying 0, -20, 0 -> 800, -20, 0 -> 0, -20, 0 at altitude 40 at 1.20 blocks/tick, improvised landing, airliner`, then `landed at field-33/36, 27 blocks down the 80-block runway (34% used)`. It held **1.202 b/t** out (1.166..1.236) and **1.207** back, at y 40.2..40.4. No runway refusal, no exception | outcome line, no exception, speed reported | pass |
| A17 | save round trip in flight | before: thr 4, health 7, logo 4, at -8.00, -25.92, 267.79. After `save-all flush`, a stop and a start: the same position, thr 4, health 7, logo 4. Unfrozen, it flies on at 0.87 b/t | present; throttle, health and `Logo` unchanged | pass (needed a change; see "Deviations") |

Every result is deterministic: A2, A3 and A6 gave identical numbers on repeat runs with the same entity id
phase.

## Commands added

All subcommands need permission level 2 and work from the console. Results go to the source and to the log
at INFO, through logger `simpleplanes-airliner`. `<id>` is the entity id, as printed by `aircraft spawn` or
`airliner status`.

| syntax | effect | example |
|---|---|---|
| `airliner status` | Prints one line for every airliner in loaded chunks, test-tagged or not: `#id logo=<n> item-logo=<n> skin=<metal\|wood> riders=<n> seats=[<seat>@<b>,...] pos=x,y,z spd= pitch= og= thr= health=`. `item-logo` builds `getItemStack()` and reads `Logo` from its `entity_tag`. `seats` gives, for each passenger in list order, its seat index and its distance along the nose axis from the airliner's origin | `airliner status` |
| `airliner board <id>` | Mounts every villager within 8 b of the airliner that is not already riding, nearest first, through the normal `startRiding` (so `canAddPassenger` decides). Prints `villager #n boarded, seat s` or `villager #n refused, no free passenger seat` for each, then `k boarded, riders=n` | `airliner board 48` |
| `airliner logo <id> <0..5>` | Sets the synched logo, which is saved as `Logo` | `airliner logo 48 4` |

## Access-widener entries added

None.

## Deviations from the spec, and why

1. **Seats with no player aboard.**
   - The foundation seated passenger *i* in seat *i*. Villagers boarded with nobody aboard therefore took
     seats 0 to 4, and the first one sat in the pilot's seat.
   - `seatOf(passenger)` now returns the list index when the first passenger is a player, and index + 1
     otherwise. With no player, the villagers fill seats 1 to 5.
   - Vanilla inserts a boarding player at the head of the passenger list (`Entity.addPassenger`, checked in
     the 26.3 bytecode). A pilot who boards later therefore takes seat 0, and everybody else keeps their
     seat.
   - The six seat positions themselves are unchanged.
2. **Passenger limit.** `canAddPassenger` still allows six riders, but at most five non-players, so seat 0
   is always free for a player. That is how "5 villagers plus room for a pilot" and "a sixth villager is
   refused" both hold.
3. **Rider transform on the server.** `positionRider` uses `transformPos` (which reads `Q_Client`) on the
   client, as before. On the server it uses `transformPosPhysics`. The reason: `Q_Client` is never updated
   on the server when no player is aboard (see the `transformPosPhysics` javadoc), so a turned airliner
   would have placed its villagers along its spawn heading. The fix is verified: after a 90-degree turn,
   the `seats=` offsets are the same as on the ground.
4. **Throttle in the world save.** `PlaneEntity` does not save the throttle; an airliner came back from a
   restart with throttle 0, which fails A17. `AirlinerEntity` now writes a `throttle` key to the world
   save and reads it back. `getItemStack()` strips the key again, so a folded or dropped airliner is placed
   with its throttle at 0. This follows the helicopter, which does the same with its controls
   (`HelicopterEntity.CONTROL_KEYS`). The spec says the save methods stay "as the foundation left them";
   the foundation's `Logo` handling is kept, and only this key is added.
5. **The logo is rolled in the constructor (server only).**
   - The foundation rolled it on the first `tick()`. The spawn packet can leave before that tick, and the
     model draws logo `floorMod(-1, 6) = 5` for an unrolled airliner, so clients could briefly see the
     wrong logo.
   - A saved `Logo`, or one carried by an item, still overrides the rolled value in
     `readAdditionalSaveData`, which now defaults to the current value instead of -1.
   - The `tick()` roll stays as a fallback for a save that holds `Logo: -1`.
   - The effect is the same: one roll per airliner that has no logo.

## Observations and open issues

- **Keel-end clearance just after lift-off.**
  - The ground clamp works as designed: the maximum pitch while `og=true` is 10.0 deg, and lift-off comes
    at 10.8 deg, earlier than the design's 12.
  - After lift-off, the `aircraft takeoff` harness keeps full back-stick until y0 + 1. The nose then climbs
    about 1.1 deg/t while the aircraft is only 0.1 to 0.2 b up.
  - At ticks 109 and 110 (pitch 16.6 and 17.7, height 0.13 and 0.18) the keel end is **0.4 and 1.6 cm**
    below the ground plane. That comes from the model geometry in the spec: keel end `(0.8125, -3.25)`,
    pivot 0.375.
  - It is visual only; nothing collides.
  - If it matters, the smallest change is to hold `groundPitchLimit` until about 0.5 b of height, or to cap
    the nose at the geometric strike angle for the current height. Either keeps A15.
- **Design risk 3 did not occur.** The rig shows no nose held down: lift-off at 0.64 b/t, airborne at
  0.71 b/t. So `pitchToMotion` stays 0.16.
- **Logo tooltip.** The coordinator's note says a tooltip exists. On the base branch only the lang keys
  exist (`simpleplanes.airliner_logo`, `simpleplanes.airline.0..5`); no Java code reads them. A tooltip
  belongs in `items/PlaneItem` (or an airliner item class), which is a foundation file.
  **Needs a foundation change** if wanted.
- **Old saves.** An airliner saved by the foundation's stub keeps its saved `max_speed` (1.0), because
  `PlaneEntity.readAdditionalSaveData` restores it. Only new airliners get 2.0. The same applies to
  autopilot-spawned airframes, whose `max_speed` the autopilot sets itself. Left alone, since the stub
  never shipped.
- **A villager in the first slot is the "controlling passenger".** This is how `PlaneEntity` works:
  `getControllingPassenger()` returns any `LivingEntity`. It is harmless here. The physics reads player
  input only from a `Player`, `transformPosPhysics` tests `getPlayer()`, and a villager is not client
  authoritative. A11 flew normally with five villagers aboard.
- **Test tooling.** Force-loading the corridors was needed on a fresh world (see "Tests"). A 260-chunk
  `forceload` is refused (the limit is 256), so wide areas need several commands.

## Not verified (needs a client)

- The metal skin and the six logos as drawn, including the fin logo on a freshly placed airliner.
- The cabin view through the culled walls from each seat.
- Where players sit versus villagers:
  - on the server, the villagers' seat positions are verified by `seats=` on the ground and after a turn;
  - a player's seat and eye position on the client, and a real pilot in seat 0, are not.
- The fans, the gear, and the camera distance (`getCameraDistanceMultiplayer` 1.6).
- Rider-side flight, the client-authoritative path, including the ground pitch clamp as the pilot's
  client applies it.
