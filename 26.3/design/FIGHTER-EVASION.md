# Fighter missile evasion

Branch `claude/fighter-evasion-26.3`, based on `26.3-beta` `5a3d7b7` (5.4.0-beta.5).

Owner's request: an autopilot fighter should do something about an incoming air-defence missile, so that some
missiles miss, but not so many that air defence stops mattering. Pilots flying a fighter themselves get a warning
only; nothing steers a player's aircraft.

Missile guidance, the silo and the missile entity were not touched (another branch owns them). Everything here
reads the missile through public accessors.

## 1. What the fighter is up against

| | T1 | T2 | T3 | T4 | fighter |
|---|---|---|---|---|---|
| top speed, b/t | 2.0 | 2.5 | 3.0 | 4.0 | 2.6 route cruise, 3.16 at throttle 10 with booster |
| acceleration, b/t² | 0.10 | 0.10 | 0.10 | 0.12 | |
| motor path (range), blocks | 400 | 600 | 900 | 1400 | |
| detection radius, blocks | 100 | 150 | 225 | 350 | |
| fuse radius, blocks | 3 | 4 | 5 | 6 | |
| turn, deg/t (far) | 12 | 10 | 9 | 8 | yaw 3.5, pitch 7, roll 8 (body frame) |

Close in the missile's turn limit rises to `2.2·speed/d`, capped at 40 deg/t, so nothing done in the last
second out-turns it. Guidance is predicted-intercept lead pursuit on the aircraft's true position every tick
(velocity estimate smoothed 50/50). There is no seeker to decoy and no Doppler gate, so a beam or notch has
nothing to break. The only way to defeat it is kinematic: make the missile fly out its motor path before it
reaches the fuse radius.

Consequences, before writing any code:

- **T1, T2 and T3 are slower than a fighter at full power.** In a tail chase they cannot close, and every
  block the fighter runs costs them a block of motor path.
- **T4 is faster** (4.0 against 3.16) and has 1400 blocks of motor. Once launched, it arrives.

### Offline simulation

An exact port of `Pursuit` against a kinematic fighter (3.1 b/t, 3.5 deg/t), same geometry as the in-game
campaign below, 10 runs per tier and manoeuvre:

- **Drag** (turn the missile to six o'clock, full power) defeats T1–T3 in all 10 runs with a reaction of
  10 or 20 ticks. At 30 or 40 ticks T3 still kills the 4 head-on passes.
- **Beam** (fly perpendicular to the line of sight) was never better than drag, and worse against T2 and T3.
- **Last-ditch break** (turn across the line of sight with a climb, inside the last second) never changed an
  outcome.
- **T4** hits 10/10 whatever the fighter does.

So the manoeuvre that matters is the drag. How soon it starts is the balance knob.

## 2. Design

Three classes in `autopilot/`, one hook in `PlaneAutopilot`, one init line in `SimplePlanesMod`.

### `MissileThreat`

What a missile approach warner sees for one aircraft:

- every `MissileTracker.active()` missile out of its tube;
- whose `Engagements` claim is on this aircraft (`Engagements.targetOf(MissileEngager(id))`);
- within 400 blocks (above T4's detection radius).

For each it records:

- range, nose to `AircraftRoster.aimPoint`;
- closing speed, the relative velocity along the line of sight;
- time to impact (range / closing, infinite when not closing);
- motor path left (`spec.range − pathLength`);
- clock position.

`canReach()` is false once the motor path left plus the fuse radius is shorter than the distance the missile
would fly to meet the aircraft head on (`range · v_m / (v_m + v_a)`). That is the test for "this one can no
longer get me, whatever I do". It is read-only and costs nothing while no missile is in flight.

### `MissileEvasion`

Called from `PlaneAutopilot.tick()` after the mode has set its command and before terrain following and the
control laws. It returns null when there is nothing to do. When there is a threat, it returns a heading, a
climb and a bank limit, which override the mode's. The hook then also sets full power (`cmdSpeed` =
strike speed, throttle pinned at the airframe's maximum) and keeps terrain following on.

It acts only when all of these hold:

- the aircraft is `AircraftType.FIGHTER`. The large, cargo and starter planes and helicopters never
  manoeuvre;
- the mode is `CLIMB`, `CRUISE`, `STRIKE`, `DESCENT`, `HOLD` or `GO_AROUND`. It does not act on the
  ground, on approach, final, flare, or while taxiing;
- the strike is not in its committed dive (`cmdPitchOverride` set). A strike that has pushed over is
  flown to the target;
- the missile can still reach it (`canReach()`);
- the missile has been seen for `REACTION_TICKS`. This is 30 by default;
  `-Dsimpleplanes.evasion.reaction=N` changes it.

The threat is the reachable one with the shortest time to impact. Manoeuvres:

| Manoeuvre | When | Command |
|---|---|---|
| `drag` | default | heading directly away from the missile, bank limit 60°, full power |
| `break` | time to impact under 25 ticks **and** the missile has the motor to fly that far | the beam heading nearer the current one, climb 30 blocks above the mode's altitude, bank limit 60° |
| `beam` | only with `-Dsimpleplanes.evasion=beam` (measurement) | the perpendicular nearer the current heading |

The 60° bank is what makes the drag turn quick enough. Yaw and pitch act in the body frame, so bank raises the
world turn rate. At the autopilot's usual 25° the fighter turns about 2.9 deg/t. At 60° it turns about 3.5 deg/t
on average, and 5.5 deg/t at the peak.

When `canReach()` goes false or the missile is gone, the method returns null again. Whatever the mode was flying
then carries on: a route turns back onto its leg, a strike resumes its run-in, a hold resumes its orbit. Nothing
in the flight plan is changed, so there is nothing to restore.

The log and the status line:

```
[evasion] #98 drag missile #103 T2 range=80 closing=0.46 tti=8.7s left=543 clock=6
[evasion] #98 resume (stood down) after missile #103
[evasion] #1 resume (out of reach) after missile #2
  #1 fighter cruise … want[hdg=085 alt=41 spd=MAX] … plan[direct] evading(drag, missile #2, tti=2.2s, 9 o'clock) hostile
```

The `resume` reasons are:

- `clear`: no missile left;
- `out of reach`: a missile left that can no longer reach the aircraft;
- `stood down`: the aircraft entered a mode or phase that does not evade, such as the strike dive or the
  approach.

An aircraft with an owner also gets an overlay line when evasion starts and ends.

Test hook: `-Dsimpleplanes.evasion=off|drag|beam|break` disables evasion or forces one manoeuvre. Anything else
means the normal choice. `-Dsimpleplanes.evasion.bank=N` changes the bank limit.

### `MissileWarning` (player-flown aircraft)

Every 5 server ticks it looks at each aircraft an AD missile is claimed on, of any type. Since silos engage
only hostile aircraft, this means a player flying an aircraft marked hostile, from a hostile item
(`/airdefence item hostile`) or with `/airdefence allegiance <targets> hostile`. Each `ServerPlayer` aboard gets
two things:

- **An action-bar line**, in bold red: `MISSILE T3, 7 o'clock, 142 blocks, impact in 2.4s`. The line reads
  `falling behind` instead when the range is opening, and adds `(+1)` for each further missile. The keys are
  `simpleplanes.missile_warning` and `.opening`, with English fallbacks, as the existing AD strings use.
- **A beep** (`NOTE_BLOCK_BIT`, sent only to that player). It repeats every 20, 10 or 5 ticks as the time to
  impact passes 5 s and 2 s, and its pitch rises inside 2 s.

It is a warning only: a player-flown aircraft is never steered.

### Not done: flares

A flare would have to make the missile lose its target (`Verdict.LOST`). The verdict is decided in
`Interceptor`/`MissileEntity`, which belong to the missile branch, and the only public way to stop a missile is
`MissileEntity.abort()`. That reports `ABORTED`, which is the silo operator's abort, not a decoyed seeker.
Doing it properly needs a small hook in the missile code, for example `Interceptor#decoy(Vec3 at, int ticks)`.
The hook would let the guidance chase the decoy, and give a tier-dependent chance of it being taken. That is a
change for that branch.

## 3. Measurements

The world was superflat (surface y=−19). Each run used one fresh silo in `air_defence` mode. Four tier lanes
ran at once, each 12 000 blocks from the next, with one hostile autopilot fighter per run. The geometry:

- 10 bearings, 36° apart;
- a lateral offset of 0, 0.3 or 0.6 of the detection radius, by run index: idx 0, 3, 6 and 9 are head-on
  passes over the silo;
- **route**: from detection + 250 blocks out, across the silo, to 700 beyond;
- **strike**: an 800-block strike (`autopilot strike … 800 <brg> 1 false false type fighter hostile`), aimed
  at the silo plus the same lateral offset.

"Survived" means the missile did not kill the aircraft. The survival figures leave out a run whose missile never
launched, or whose report was lost while the container was thrashing (load above 100); that is why some tiers
count 8 or 9 runs.

### Route: fighter crossing coverage

| Tier | before (no evasion) | reaction 10 | **reaction 30 (default)** |
|---|---|---|---|
| T1 | 10/10 | 9/9 | not rerun (T1 already missed every time) |
| T2 | 3/10 (only the three widest passes) | 9/9 | **7/9** |
| T3 | 0/10 | 9/9 | **2/9** (only the widest passes) |
| T4 | 0/10 | 0/9 | **0/8** |

Manoeuvres flown are counted per run, by the first manoeuvre, with a count of runs that flew a break at any point:

| Tier | reaction 10 | reaction 30 |
|---|---|---|
| T2 | first drag in 9; break in 4 | first drag in 4, first break in 5; break in 7 |
| T3 | first drag in 9; break in 4 | first drag in 2, first break in 7; break in 7 |
| T4 | first drag in 9; break in 9 | first drag in 3, first break in 5; break in 8 |

At reaction 30 a head-on missile is often already inside 25 ticks when the fighter reacts, so it starts with
the break. Every break against a T4 ended in an intercept, as the simulation said.

With reaction 10 the fighter outruns every T2 and T3. That would leave only T4 as air defence against fighters.
At 30 (1.5 s), T2 kills the head-on passes about half the time, T3 kills everything but the widest passes, and
T4 kills everything. That is the default.

### Strike on a point inside coverage (reaction 10 build)

| Tier | before: survived / target hit | after: survived / target hit | evasion flown |
|---|---|---|---|
| T1 | 10/10 / 10 | 8/9 / 9 (1 no launch) | none |
| T2 | 5/10 / 9 | 6/10 / 10 | none |
| T3 | 5/10 / 5 | 5/10 / 5 | none |
| T4 | 2/9 / 3 (1 no launch) | 2/10 / 3 | drag in 6 runs, then stood down at the dive |

Strike is unchanged, and by design. With the target at the silo, the fighter is already in its committed dive
when a T1–T3 launch is seen, and the dive is never interrupted. The one T1 loss hit its target in the same tick
the missile reached it.

The T4 is seen during the run-in. The fighter drags, turns back at the dive and dies. A later reaction only
shortens the drag, so strike was not rerun at 30.

### Controls and completions

- **Large and cargo planes**, route against T3 at reaction 30, 5 runs each: 4 of 4 intercepted for each type
  (one report lost and one no-launch). No `[evasion]` line was logged, and they flew straight on as before.
- **Route resumes and completes** (T2, offset 0.6, reaction 30). The sequence was:
  1. drag at 9 o'clock;
  2. `resume (out of reach)` once the missile was out of motor path (`OUT_OF_RANGE`, closest approach 57
     blocks);
  3. the route turned back onto its leg;
  4. it flew the return leg over the silo;
  5. it landed on the improvised field: `landed at field-1/18, 18 blocks down the 80-block runway`.

  Height held 59–60 blocks above the ground throughout.
- **Strike resumes and hits** (T2 silo 450 blocks out on the approach path). The missile was launched behind
  the fighter. The fighter dragged, which here means straight on at full power, then stood down at the dive
  and `hit the target … (2 blocks off)`. The missile was `LOST` 211 blocks away.
- **Terrain**: no aircraft struck the ground while evading, in any run. Terrain following stays on during the
  manoeuvre, and the break only ever climbs.

  One strike run (T3, head-on, not evading) went down 27 blocks short at y=2. The missile had hit terrain at
  6.7 blocks of flight, right under the diving fighter. The baseline shows the same kind of event (T4 idx 0).

## 4. Not tested

- Evasion over real terrain: mountains, valleys, or a drag heading into rising ground. The test world is flat.
  The drag keeps terrain following on, but the 60° bank has not been flown near terrain.
- Several missiles at one fighter from several silos.
- `HOLD` and `GO_AROUND` under attack. They are wired the same way as `CRUISE` but no run covered them.
- The player warning. The test server has no player, so the overlay and the beep were never sent or seen. Only
  the lookup it shares with the autopilot (`MissileThreat.inbound`) ran in the campaigns.
- The friendly case. Every test fighter was `hostile`. A friendly autopilot fighter is not engaged by AD, so it
  never sees a claimed missile.

## 5. Open questions

- **Hostile or friendly?** Evasion runs for any autopilot fighter a missile is claimed on. Today only hostile
  aircraft are engaged, so in practice it is hostile fighters that evade. If a player-owned autopilot fighter
  ever becomes a target (another player's silo), it will evade too. Is that wanted?
- **Flares?** Only with a decoy hook in the missile code (section 2). Should the missile branch add one?
- **Balance.** The reaction delay is the one number that sets how often T2 and T3 miss. 30 ticks is the
  measured choice. 20 would sit between the two columns above.
