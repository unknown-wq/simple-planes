# TODO

Work that has been investigated, costed and deliberately **not** scheduled yet. Everything here is
anchored to real code or a measured number — nothing on this list is a wish.

Scheduled work does not live here; it lives in a branch. This file is for the backlog and, just as
importantly, for the things that were looked at and rejected, so nobody pays to rediscover them.

---

## Aircraft backlog

Ordered by what should be done first. Efforts are honest estimates including verification.

### 1. The crash explosion is a fixed charge and eats the world

`PlaneEntity#crash(float damage)` **ignores its argument** and always fires `Blast.DEFAULT` — 4.0,
vanilla TNT, block-breaking. Measured on the headless rig: **92 blocks of terrain removed per
crash**, and a fatal impact is one health point away from a survivable one.

This is a live regression rather than a missing feature: the collision rewrite made impacts
detectable for the first time, and nobody revisited what a detected impact then *does*. With
autopilot traffic flying continuously, it means aircraft slowly crater the airfields.

The `Blast` record already carries strength, block-breaking and incendiary independently, and the
crash path already receives a damage figure — the work is to make one feed the other and to pick
defaults that do not rearrange terrain by accident.

*Effort: 0.5–1 day. Verified headless — crater counting with the `fill … replace` recipe in
`TESTING.md`.*

### 2. Get a client to boot once, and write down how

Nothing under `client/**` in this port has ever been executed. `TESTING.md` §4 records that no
client has reached a title screen in this environment. Every visual item below is unverifiable
until that changes, which makes this a gate rather than a feature.

*Effort: 0.5–1 day, mostly waiting. It is its own verification.*

### 3. Restore the fuel / energy / tank gauge

`renderPowerHUD` and `Upgrade#renderScreen(Bg)` were dropped wholesale in the port, so there is no
fuel indication anywhere in the mod. A cargo plane burns one coal every **8 seconds** (fuel cost 10,
coal = 1600 ticks); a small plane every 26.7 s. Running dry is therefore normal, and invisible.

No new packets are needed — the engines already synchronise burn time, energy and tank contents
through `UpdateUpgradePacket`.

*Effort: 1.5–2 days. Client only — a real cost, since it cannot be checked automatically.*
*Blocked on 2.*

### 4. A mob can take the controls

`LargePlaneEntity` and `CargoPlaneEntity` actively mount nearby livestock. Reproduced headlessly: a
cow boards a large plane within 3 seconds and becomes passenger 0, after which every control
packet's `getControllingPassenger() == player` guard rejects the actual pilot.

Related to, but distinct from, the already-fixed thrust bug where a mob passenger froze `Q_Client`.

*Effort: 0.5 day. The mounting half is headless; the control half needs a client.*

### 5. `onGroundTicks` oscillates instead of latching

Costs a random 0–4 tick ground/air hysteresis on every lift-off. Previously deferred because it sat
in a code block another workstream owned; that block no longer exists.

*Effort: 2–4 hours. Headless.*

### 6. Stall and low-fuel warnings

The flight model has a hard stall at 0.316 b/t and damps the elevator below take-off speed. The
pilot is shown neither, and cannot see the fuel either (see 3).

*Effort: 0.5 day. Client only. Blocked on 2 and 3.*

---

## Infrastructure backlog

The traffic-separation items are scheduled and are not listed here. What remains deliberately
unbuilt:

* **`bestEnd` does not prefer the drier runway end.** It ranks approach funnels on the obstacle
  counts persisted at survey time, and a sea at or below the runway elevation is not an obstacle to
  *clearance*, which is all that count measures. Folding landability into it would silently
  reinterpret every airfield already on disk. A coastal field may therefore take its three
  go-arounds before trying the other end — safe, just untidy.
* **A restart during a departure delay departs the aircraft immediately.** `load()` maps a saved
  `PARKED` to `TAKEOFF`, exactly as it already does for `TAXI`, because it does not re-resolve the
  departure runway. The ordered delay persists on the flight plan; the remaining delay does not.
* **A full dispatcher** — an `AirfieldTower` class, per-end reservations, parallel runways,
  taxiways, hangars, navaids, approach lighting, fuelling stations, persisted queues, in-trail time
  spacing, wind. Judged out of scale for a Minecraft mod: the useful version is the smallest one
  that stops aircraft colliding and stops them queueing for a runway that is not the only runway.

---

## Looked at and declined

Recorded so the same ground is not covered twice.

**Retuning the flight model** — not the drag polynomial, not `maxLift`, not `pitchToMotion`, not
`maxSpeed`. `PHYSICS-AUDIT.md` establishes that every constant in `TempMotionVars.reset()` is
bit-identical to the 1.21.1 upstream, and records two earlier "fixes" that were arithmetic errors
and were reverted. `AutopilotConfig`'s tuning is derived *from* these exact numbers — the
throttle-notch equilibrium table, the deceleration margin, the waypoint arrival radius, the minimum
usable runway length. Changing a coefficient invalidates a file of measured tuning for a feel
improvement nobody asked for.

**A real lift/drag polar, mass and wing area.** The model has no mass by design; `PlaneCollisions`
invents its own `massOf()` purely to scale impact damage. This is a rewrite, not an improvement.

**Scaling the roll rate by `getRotationSpeedMultiplier()`.** A genuine inconsistency — a cargo plane
pitches at 1°/tick and yaws at 0.5°/tick but rolls at the same 5°/tick as the starter plane — and
present upstream too. It is a balance decision that changes the handling of all four aircraft.

**The "316× stricter" move gate.** The port changed `dist > 1e-5` to `dist² > 1e-5`. Checked against
every reachable throttle setting: the first tick of a ground roll at throttle 5 already produces
~0.005 b/t, whose squared horizontal distance is 2.8e-5, above the gate; throttle 1 and 2 settle at
0.010 and 0.061 b/t. There is no regime where a powered plane stutters. **Closed, not deferred.**

**`markHurt()` every tick.** Real — it makes `ServerEntity` send a motion packet every tick per
plane, to a client that is authoritative and will overwrite it. Bandwidth only, unmeasurable in this
environment, and it touches the damage path. Worth doing opportunistically next time someone is in
that method; not worth scheduling.

**Autopilot support for helicopters.** `HelicopterEntity` overrides `tickPitch`, `tickRoll`,
`tickRotateMotion` and `getTickPush`, so none of the control laws describe it — this is a second
flight director, not an extension of the first. Two dead constants found on the way, both dead
upstream as well: `HelicopterEntity#getMotionVars` sets `push = 0.05f`, which `PlaneEntity#tick`
overwrites two statements later, and `passiveEnginePush` is never read.

**Re-wiring a config library.** `SimplePlanesConfig` is frozen into static suppliers, so every fuel
cost, the turn threshold and the camera distances are uneditable. Genuinely lost function and cheap
to restore, but it changes nothing by itself — it should ride along with a release rather than lead
one.

**The liquid engine's modded-fluid support.** Every fluid in `data/simpleplanes/plane_liquid_fuels/`
except `lava.json` belongs to Immersive Engineering, Immersive Petroleum, PneumaticCraft, Thermal or
Ultimate Car. None of them exist for 26.2, so there is nothing to be compatible with yet.
