# One missile per aircraft, and missile fuel

Branch `claude/missile-fuel-26.3`, based on `26.3-beta` `5a3d7b7` (5.4.0-beta.5).

Owner's request: "One missile per object. A second one is fired at the same object only if the first missile was
destroyed and the object was not. And let's add fuel to missiles: a missile can fly a distance equal to the
maximum distance it is able to fly, then it simply falls down."

The user-facing description is in `MISSILES.md` (§1c "One missile per aircraft", §2 "Fuel and the fall", §4).
This note records why the old code behaved as it did, what changed, and the measurements.

## 1. The old behaviour and its causes

Line numbers are those of `5a3d7b7`, under `26.3/src/main/java/xyz/przemyk/simpleplanes/`.

| # | What happened | Cause |
|---|---|---|
| 1 | Two silos launched at one aircraft at the same time | `airdefence/InterceptorSpec.java:20` `MAX_PER_TARGET = 2` |
| 2 | A third missile was fired at the aircraft after the first hit had already brought it to 0 health, and chased the falling wreck | `airdefence/AircraftRoster.java:53` `isEngageable` checks `isAlive()`, which stays true for an aircraft at 0 health until it crashes. When the killing missile ended, its claim was released (`airdefence/Interceptor.java:134`) and the wreck was a free target again |
| 3 | A missile could lose its claim while still flying | `airdefence/Engagements.java:23` claims lapse 40 ticks after the last renewal, and the only renewal was `Interceptor.track` (`Interceptor.java:88`), called from the missile's own tick. A missile that stopped ticking (a stall) lost its claim after 2 s, although it was still there |
| 4 | A `/kill`ed interceptor kept its claim for 40 more ticks | `missile/MissileTracker.java:174` reports `REMOVED` but nothing released the claim; only `MissileEntity.finish` did (`missile/MissileEntity.java:380`) |
| 5 | A silo whose claim lapsed while its chunk slept could wake up and keep launching at an aircraft another silo had taken meanwhile | `airdefence/AirDefenceSilo.java:54-65` renewed its claim without checking for another holder |
| 6 | Nothing said why a second missile was fired | no record of how the previous engagement ended |
| 7 | An interceptor that reached its range vanished in mid-air | `Interceptor.java:118` returned `OUT_OF_RANGE`; `MissileEntity.java:243-244` ended the flight on the spot |
| 8 | A strike missile past its motor path vanished in mid-air | `MissileEntity.java:255-256`: `pathLength > 1.3 × maxRange + 200` ended the flight as `FUEL` |

## 2. What changed

### One missile per aircraft

- `InterceptorSpec.MAX_PER_TARGET` is **1**. The claim check was already shared by every silo and every missile
  (`Engagements` is one table per server), so this makes the limit global.
- `AircraftRoster.isEngageable` also requires `getHealth() > 0`. A shot-down aircraft is not a target, and a
  missile whose target drops to 0 health re-targets or ends `LOST`.
- **Launch sequences.** A silo claims its aircraft when the hatch starts to open (unchanged), and at ignition the
  missile's own claim is taken before the silo's is dropped (`Engagements.handOver`), in the same tick.
- **No lapse while alive.** `MissileTracker` renews the claim of every tracked interceptor from the level tick
  (`Interceptor.renew`), so it holds even in ticks the missile does not run. The 40-tick expiry is kept for silos
  that go to sleep.
- **Every ending releases.** `MissileEntity.onRemoval` releases the claim of a missile removed without `finish`
  (`/kill`, shutdown). Burnout releases it too (§2 below).
- **Woken silos.** `AirDefenceSilo.keepTarget` checks whether someone else holds its aircraft; if so it
  re-targets or aborts, keeping the missile.
- **Why a second shot.** When a missile gives up its claim while its aircraft is still alive (above 0 health),
  `Engagements` remembers the reason for 2400 ticks: the outcome (`TERRAIN`, `LOST`, `STALLED`, `ABORTED`,
  `REMOVED:killed`, `OUT_OF_FUEL`, `INTERCEPTED` with the aircraft surviving) and the aircraft's health. The next
  silo to claim that aircraft turns it into its claim's note, logs it on its engage line, and passes it to its
  missile. `/airdefence engagements` shows every claim with its note and the last 16 follow-ups.

### Fuel

- Every missile has `fuel` and `fuelBudget` in blocks of powered flight past the tube:
  - strike and remote launch: `MissileTier#fuel` = `1.3 × maxRange + 200` (1760 / 3450 / 6700 / 13200), the same
    figure as the old motor path, so the range is exactly as before; the silo range check still limits a launch;
  - air defence: `InterceptorSpec#range` (400 / 600 / 900 / 1400), the same as the old range budget.
- Each powered tick outside the tube burns the distance flown that tick.
- At 0 fuel, or at the old powered time limit, the motor stops (`burnout`): phase `UNPOWERED`, synced thrust 0 (no
  flame and no exhaust particles), no guidance and no fuse; an interceptor releases its claim with the reason
  `OUT_OF_FUEL`. A log line gives the position.
- **The fall.** Velocity × 0.99 per tick, minus 0.08 b/t² vertically; the nose follows the velocity. The swept nose
  segment is tested against blocks and against pickable entities other than missiles. The first hit ends the
  flight as `FELL`.
- **Blast on impact.** A strike missile detonates with its tier's normal warhead, as for a terrain impact. An
  interceptor uses `InterceptorSpec.SPENT_WARHEAD`, which is `null` (no blast), see §4.
- **Backstop.** A hard lifetime cap of the powered time limit plus 600 ticks ends anything still airborne as
  `TIMEOUT`. Missiles stay `noSave`, so nothing survives a restart.
- The outcomes `FUEL` and `OUT_OF_RANGE` and the verdict `Interceptor.Verdict.OUT_OF_RANGE` are gone.

### Visibility

- Telemetry lines (`/missile list`, `/missile telemetry`) end in `fuel=<left>/<budget> motor=boost|cruise|unpowered`.
- Report lines carry the same, plus `burnout=<why>@t<tick>,x,y,z fell=<ticks> on_<block or entity #id>` after a
  burnout.
- `/airdefence engagements` (new), `/airdefence scan` (names the holder, `shot down, ignored`,
  `candidate (follow-up: …)`), `/missile silo status` (`, holding #N`), and the engage log line
  (`(first shot)` or `(second shot: …)`).
- `/missile fuel <id> <blocks>` (test) sets the fuel left of a missile in flight.

## 3. Measurements

Dedicated test server of our own (`/home/user/sp-missile-server`, port 25760, `-Xmx1536M`), superflat with
40 stone under 3 dirt and grass (surface y = −20), warheads on, 20 TPS. Silo areas force-loaded as a stand-in for a
player. "Before" is the jar built from `5a3d7b7`, "after" is this branch. The hostile aircraft flew
`/autopilot route … 0.8 hostile` 30 blocks abeam of the silos at cruise height; T1 silos 20 blocks apart.

| Scenario | Before | After |
|---|---|---|
| **A.** Three T1 silos, one hostile | **3 missiles** at #1: two launched together, both hit (0/10); the third launched at the falling wreck and ended `LOST` when it crashed. 1 kill, 2 missiles wasted | **1 missile**, `INTERCEPTED`, destroyed. The other two silos kept their missiles. 0 second shots |
| **B.** Three T1 silos, two hostiles | **all 3 missiles at #11** (two together, one at the wreck); #18 was **never engaged** | #9: 1 missile, destroyed. #10: first missile left it at **2/10**, then **exactly one follow-up** 0.3 s later ("missile #12 … ended INTERCEPTED (target hp 2/10)"), destroyed. 3 missiles, 2 kills |
| **C.** T1 against a plane at 2.8 flying away | `OUT_OF_RANGE` at 403.2 blocks, **vanished in mid-air** at y 21.6 | fuel out at t 216 after 403.2 blocks, at (66393.6, 21.7); **fell 34 ticks**, landed at (66451.1, −19.0), 57 blocks further on; `blast=none` (spent interceptor) |
| **D.** Three T1 silos, first missile `/kill`ed 15 ticks after launch | 3 missiles: #130 killed; #131 killed the aircraft; **#132 also fired and burst on the wreck** | #34 killed at t 18; **exactly one follow-up**, 0.2–0.3 s later ("ended REMOVED:killed (target hp 10/10)"), destroyed; the third silo kept its missile (two runs) |
| **E.** Two T1 silos, a stone roof 8 above the first | not run | #43 hit the roof (`TERRAIN`, harmless) at t 17; **one follow-up** ("ended TERRAIN (target hp 10/10)"), destroyed |
| **H.** Two T2 silos, missile tickets off, the missile stalls in unloaded ground | not run (the old 40-tick lapse would have freed the aircraft after 2 s) | the stalled missile **kept its claim for 201 ticks** until the watchdog ended it (`STALLED`); only then did the second silo fire, with that reason |
| **I.** Save and restart with an interceptor in flight | not run | `save-all`, stop: "#15 discarded at shutdown". After the restart: no missile entity, 0 claims, the aircraft still there; the second silo engaged it (logged as first shot, the miss memory is not saved) and killed it |

Strike and remote launch (after only; the range check is unchanged code):

| Case | Result |
|---|---|
| T1 strike to 1250 blocks | refused: "target too far: 1250.0 blocks … at most 1200" |
| T1 strike to 1190 blocks | `ARRIVED`, flown 1213.2, `fuel=548.6/1760 motor=cruise` |
| T1 strike to 600, fuel set to 60 (`/missile fuel`) | fuel out at t 106 at y 5.0 (cruise), **fell 25 ticks**, landed 44 blocks further on, `FELL on_grass_block`, `blast=2.0,blocks` (normal warhead) |
| The same flight, a NoAI iron golem at that landing point | `FELL on_iron_golem_#78`, `blast=2.0,blocks`; golem 100 → 84.2 health |
| T2 remote launch (silo chunk unloaded), 2600 blocks | refused: "target too far: 2600.0 blocks … at most 2500" |
| T2 remote launch, 2400 blocks | pending, launched after 1 tick; `ARRIVED`, flown 2432.4, `fuel=1020.2/3450` |
| T2 remote launch, fuel set to 150 | fuel out at t 80 at y 12.7, fell 29 ticks, landed 63 blocks further on, `blast=4.0,blocks` |

Leaks: after every case `/missile list` said "No missiles in flight", `execute if entity
@e[type=simpleplanes:missile]` failed and `/airdefence engagements` listed 0 claims.

One run (a repeat of A) was lost to the machine, not the mod: the load average was above 100 and the server thread
fell 530 s behind while placing silos. It is not counted.

## 4. The blast of a spent missile

Options considered for a missile that falls after its fuel is gone:

1. **The normal warhead.** Right for a strike missile: it was sent to blow something up, and one that falls
   short still carries its warhead. Chosen for strike and remote launches.
2. **A reduced blast**, for example `new Blast(1.0F, false, false)`: hurts entities within 2 blocks, breaks
   nothing.
3. **None**, the puff only.

For **interceptors** the default is **none** (`InterceptorSpec.SPENT_WARHEAD = null`):

- a spent interceptor falls back over the defended area, often the owner's own base, a few hundred blocks from the
  silo;
- a powered interceptor that hits terrain was already harmless by design (§1c), and a spent one should not do
  more harm than a powered one;
- a reduced blast is one line if the owner prefers it (option 2, shown in the field's comment).

## 5. Not tested

- The hard lifetime cap (`TIMEOUT` 600 ticks after burnout): no fall lasts that long on these worlds.
- The powered time limit as a burnout cause ("motor time limit"): fuel always ran out first.
- A spent interceptor landing on an entity (the entity path was tested with a strike missile).
- A client: the flame and exhaust stopping at burnout follow from `thrust = 0`, which the renderer and
  `tickClient` already test, but were not looked at.
- A silo that wakes after its claim lapsed and finds its aircraft taken (`keepTarget`); it needs a sleeping silo
  mid-sequence.
- MineColonies: not involved; nothing here depends on it.
