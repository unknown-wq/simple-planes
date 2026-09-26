# Airfield taxi: route planning, traffic and the departure end

Branch `claude/airfield-taxi-26.3`, based on `26.3-beta` `5a3d7b7` (5.4.0-beta.5).

The owner's request covers five things:

- A taxiing aircraft never drives into a pit or off a drop.
- It takes a smooth, short ground route.
- It keeps both wingspans clear of other aircraft, parked or moving.
- It never rams anything. If no route exists it holds, plans again, and says why.
- It departs from the runway end that is nearest by ground, not from the far end.

## Root causes

Line numbers refer to `26.3-beta` `5a3d7b7`, under `26.3/src/main/java/xyz/przemyk/simpleplanes/autopilot/`.

| # | Symptom | Cause |
|---|---|---|
| 1 | Aircraft fall into pits beside the route | `Airfield.groundedIfLevelWith` (Airfield.java:836-854) accepts any column within ±2 blocks (`PARKING_MAX_ELEVATION_DIFFERENCE`) of the runway. A 2-block pit counts as "level". |
| 2 | Pits between the stand and the runway are not seen | `Airfield.taxiPathIsRollable` (Airfield.java:868-879) samples the centreline only, every 2 blocks. A 1–2 block hole between samples, or one under a wing or wheel off the centreline, is missed. |
| 3 | Taxi-out drives straight through whatever is in the way | `PlaneAutopilot.tickTaxi` (PlaneAutopilot.java:869-873) steers `headingTo(position, threshold)`. There is no route, and neither terrain nor aircraft are checked on the way. |
| 4 | Taxi-in weaves, overshoots corners and hits parked aircraft | `Airfield.taxiInRoute` (Airfield.java:551-584) builds three fixed legs. `PlaneAutopilot.tickTaxiIn` (PlaneAutopilot.java:2039-2049) steers bang-bang at the next point, with no speed control before a corner. The turn radius of an airliner at 0.2 b/t (about 11 blocks) is wider than the legs allow. |
| 5 | Parked or taxiing aircraft are rammed | Nothing on the ground looks at other entities. The only safeguard was the stall timeout, which ends the taxi after the aircraft is already pressed against the other one. |
| 6 | The aircraft taxis about 100 blocks to the far end when the near end is 15–20 blocks away | `DeparturePlan.decide` / `cost` (DeparturePlan.java:75-83, 96-101) score an end by track from the far threshold to the destination, plus the turn onto course, plus climb-out obstacles. Taxi distance does not enter the score at all. `tickTaxi` (PlaneAutopilot.java:869-873) then always drives to that end's threshold and never enters part-way down the runway. On airfield-3 the destination was north, so 36 won, and 36 starts at the far threshold. |
| 7 | A restart mid-taxi takes off from the apron | `PlaneAutopilot.load` (PlaneAutopilot.java:2835-2842) promotes a saved `TAXI` or `PARKED` to `TAKEOFF`, wherever the aircraft stands. In the baseline run the aircraft took off across the grass and through a pit. A saved `TAXI_IN` was dropped (PlaneAutopilot.java:2809-2819). |

## Design

### `TaxiPlanner`: grid A* over the real surface

**Grid.** The grid covers the runway rectangle, the stands and the query points, plus `TAXI_GRID_MARGIN` (24). No side may exceed `TAXI_GRID_MAX_SIDE` (640). There is one cell per block.

Columns are read lazily and cached per airfield and level for `TAXI_GRID_TTL` (200 ticks). A column records:

- Ground height: the top of the first collision shape, from the surface down to 8 below the runway. Slabs therefore count as half steps.
- Fluid: no ground.
- Headroom: 3 blocks.
- Paved: not dirt, sand or gravel.
- On the strip, or not.

**Walkability.** A cell is walkable when every column within the aircraft's radius is within `TAXI_MAX_STEP` (0.55) of the centre column. The radius is the bbox half-width plus `TAXI_TERRAIN_MARGIN` (0.75). This is the vehicle's own step rule, since `maxUpStep` is 0.6 below 0.5 b/t. Pits, walls and water edges become hard obstacles around the whole airframe footprint. The same step limit applies between neighbouring cells.

**Aircraft as inflated obstacles.** Every grounded `PlaneEntity` in the grid is an obstacle. So is a placeholder at each stand booked in `StandOccupancy` whose aircraft is not loaded. Each obstacle is two rectangles, the hull and the wing, taken from the airframe table below. Each cell gets:

- Hard (contact): within the planned aircraft's bbox half + 0.3.
- Hard (wing): within its `sweep` (max of half-span and half-length) + `TAXI_WING_MARGIN` (1.0) of a parked aircraft. For a moving aircraft this band is a soft cost instead.
- Soft: `TAXI_TRAFFIC_SOFT_COST` (3) across a `TAXI_TRAFFIC_SOFT_BAND` (4) beyond that, and along the next 24 blocks of a moving aircraft's own route.

Both spans count, so a wide airliner passing a parked airliner keeps 6.6 + 6.6 + 1 blocks between centrelines.

**Cost.** Each cell costs 1, plus:

- `TAXI_UNPAVED_COST` (0.15) off pavement;
- `TAXI_EDGE_COST` (0.4) within `TAXI_EDGE_BAND` (1.5) of an obstacle;
- the soft traffic cost;
- for arrivals only, `TAXI_ARRIVAL_RUNWAY_COST` (1.0) on the strip, so they leave the runway early.

The search is 8-connected with no corner cutting. The heuristic is the distance to the goal segment. The search stops at `TAXI_MAX_EXPANSIONS` (150 000). Smoothing replaces a stretch of cells with a straight line only when the line costs no more than the A* path it replaces.

**Relaxed zones.** Within `TAXI_RELAX_RADIUS` (3) of the start and of a stand goal, only the contact clearance applies, and wing overlap costs +8. Stands can be marked closer together than two wingspans, and an aircraft must still be able to leave or reach its own stand. The start cell is always allowed.

**Goals are segments.**

- `entryGoal(end, dims)` runs along the centreline from the threshold to `length − requiredRun − TAXI_LINEUP_ALLOWANCE`. It costs `TAXI_ENTRY_COST` (0.1) per block along, so the threshold wins a tie. This is an intersection departure that leaves enough run for this airframe.
- `standGoal` is a stand square.
- `centrelineGoal` is the whole centreline. It is used for stand validation and for the "nearest entry" note.

**Corner speeds.** At each vertex the route tries the speeds {0.2, 0.15, 0.10, 0.06, 0.03} and keeps the fastest one whose turn circle is clear ground away from traffic. The turn circle radius is 19.1 × v_to above 0.2·v_to, and v/0.0105 below it.

**Failure reasons.** When planning with traffic fails, a terrain-only search runs next:

- If the terrain-only search also fails: `no level ground route`, or `standing where it cannot move without dropping` when the start itself is boxed in.
- If it succeeds: the reason is the first obstacle on the terrain-only route, reported as `blocked by #N` or `blocked by an aircraft on a booked stand`.

### `TaxiDriver`: pure pursuit, speed profile, guards

**Steering.** Pure pursuit with a lookahead of clamp(1.5 + R(v)/2, 2, 6).

**Speed.** The speed is the minimum of:

- `TAXI_SPEED` (0.20);
- `cornerSpeed + TAXI_BRAKE_RAMP (0.08) × (distance − R/2)` for each corner within 30 blocks;
- the stop-at-stand ramp;
- a heading-error limit: above 75° → 0, above 45° → `TAXI_CREEP_SPEED` (0.03), above 20° → 0.08;
- 0.08 when more than 1.5 blocks off track.

**Throttle.** A taxi throttle law replaces the flight speed controller on the ground:

- brakes (throttle 0) above +0.04 over the target;
- 1 when just over target;
- up to 5 to break away below 0.1 b/t, where `tickOnGround` divides thrust by 5.

**Ground guard, every tick.**

- Each point on the velocity vector within braking distance (v/0.12 + 0.5) is read live. Uneven ground there → hold `uneven ground ahead`, then plan again after `TAXI_REPLAN_HOLD_TICKS` (20).
- The route ahead is checked out to the stopping distance. Changed ground there → drop the grid cache and plan again (`route no longer level`).

**Traffic guard, every tick.** A corridor along the route ahead is tested against every aircraft within reach:

- Contact distance (bbox half + 0.3) always stops.
- The wing distance (sweep + 0.5) stops when the other aircraft is closing, except in a relaxed zone.
- A stopped blocker gives `blocked by #N` and a new plan after 20 ticks.
- For moving traffic, positions are predicted 10–40 ticks ahead, and the aircraft without right of way gives way (`giving way to #N`). Right of way goes first to the runway holder, then to any aircraft not on autopilot, then to the lower entity id.

**Stuck guard.** If commanded ≥ 0.06 but moving < 0.015 for 60 ticks → plan again (`not moving`).

### Departure end: `DeparturePlan.decideForTaxi`

For each end, the planner is run to its `entryGoal` from where the aircraft stands. An end is closed:

- when the airfield is one-way the other way (`/autopilot airfields oneway <field> <designator|off>`, saved as `one_way` on the airfield);
- when an arrival is on approach to the opposite end, which would be head-on.

Among the usable ends the choice is made in this order:

1. fewer climb-out obstacles (unchanged: one blocked column outweighs any taxi);
2. shorter planned taxi, with a tie band of `TAXI_TIE_TOLERANCE` (8 blocks);
3. the old destination score.

The status line says why the shorter-taxi end was not taken:

- `not 18: one-way 36`
- `not 18: arrivals landing 36`
- `not 18: N in its climb-out`
- `not 18: <no-route reason>`

When the nearest runway point leaves too little run it adds `nearest entry leaves N blocks of run, <type> needs M`. It always says `taxi N blocks, entering N blocks in, M to run`.

The choice is made again when a plan is older than 100 ticks, and once the runway is freed after a wait. An arrival that closed the near end therefore stops forcing the far end once it has landed.

Occupied or reserved runways are still handled by `RunwayOccupancy`. A departure does not taxi without holding the reservation, and the status says `runway occupied by #N`.

### Taxi-in

`Airfield.arrivalStand` plans with multiple goals, one per free stand, and picks the nearest by planned route (`TaxiIn(stand, route, problem, traffic)`). The runway costs extra, so the arrival leaves the strip at the nearest point that lets it off and does not roll on. The driver stops on the stand within `TAXI_STAND_RADIUS` (1.5).

If traffic blocks every stand, the aircraft enters `TAXI_IN` without a stand, holds, plans again every `TAXI_REPLAN_INTERVAL` (40 ticks), and stops after `TAXI_IN_TIMEOUT` with the reason. The earlier stops on the runway ("no free stand", "no marked parking") are unchanged.

### Restart

**`TAXI` or `PARKED`.** A saved `TAXI` or `PARKED` loads as `PARKED` with the remaining departure delay (`departure_hold`). The departure end and route are decided again from where the aircraft stands. A restart can no longer turn a half-finished taxi into a take-off from the grass.

**`TAXI_IN`.** A saved `TAXI_IN` is resumed:

- the airfield comes from the plan;
- a stand is chosen again;
- the taxi continues to the stand.

### Unchanged

- `RunwayOccupancy` and `StandOccupancy`, parking and unparking, the tower board.
- Shuttles, which are still announced by `AutopilotDispatcher.arrived` on every taxi-in exit.
- Arrivals, departures, the landing roll-out and braking.
- Models.
- The single `CameraMixin`.
- The spawner's `decide`, which still picks the stand's side of the field (and respects one-way).
- Client-side stand validation, which keeps the straight-line check because the planner is server-only.

## Airframe table (`TaxiPlanner.dims`)

The `requiredRun` column is the measured distance from brake release to 5 blocks above the runway at full boost, plus a quarter. The table uses these shorthands:

- **bbox½**: half-width of the colliding bounding box.
- **len½**: half-length.
- **wid½**: half-width of the fuselage.
- **span½**: half-span.

| Type | bbox½ | len½ | wid½ | span½ | v_to | measured to 5 AGL | requiredRun |
|---|---|---|---|---|---|---|---|
| plane | 1.25 | 2.9 | 0.8 | 3.4 | 0.30 | 33.6 | 42 |
| large | 1.5 | 4.0 | 1.0 | 3.8 | 0.30 | 33.1 | 42 |
| cargo | 1.5 | 6.5 | 1.3 | 8.5 | 0.30 | 35.9 | 45 |
| fighter | 1.5 | 3.7 | 0.8 | 2.8 | 0.45 | 29.6 | 38 |
| airliner (wide) | 1.5 | 5.85 | 1.8 | 6.6 | 0.60 | 42.0 | 53 |
| regional airliner | 1.1 | 6.0 | 1.25 | 5.0 | 0.54 | 39.3 | 50 |
| unknown | 1.5 | 4.0 | 1.5 | 5.0 | 0.30 | — | 50 |

An entry point must leave `requiredRun + TAXI_LINEUP_ALLOWANCE` (8) of runway ahead.

## Results

### Test setup

- Dedicated server on port 25730, flat world, `-Dsimpleplanes.autopilot.trace=true`, positions logged every tick.
- Metrics were computed from the trace against the known poses of the parked aircraft:
  - **pit ticks**: ticks below runway level;
  - **bbox touch**: bounding boxes within 0.1;
  - **hull overlap**: fuselage rectangles intersect;
  - **wing overlap**: rectangles of span × 60 % length intersect.
- Airfield-1 is a 180-block runway with stands at x=40.
- Airfield-3 is a 100-block runway with a stand at (230,−94), 18 blocks from the 18 threshold.
- "Lost in climb" in some departures is the aircraft leaving the force-loaded test area under `tick sprint`. It happens in the baseline too and does not come from this change.

### Before and after

| Scenario | Baseline (5a3d7b7) | After |
|---|---|---|
| **S0** Stand 18 blocks from one end of a 100-block runway, plane, everything free | End 36 (far). Taxi **544 ticks, 97.9 blocks** to threshold (211,−3). Lift-off (5 AGL) at **615 ticks**. | End 18 (near): `taxi 18 blocks, entering 6 blocks in, 94 to run`. Taxi **130 ticks, 19.1 blocks**. Lift-off at **212 ticks**. |
| **S0-oneway** Same, `oneway airfield-3 36` | — | `depart 36 … taxi 48 blocks, entering 50 blocks in, 50 to run, not 18: one-way 36`. Taxi 288 ticks, 48.4 blocks, lift-off 353 ticks. Intersection entry, not the far threshold. |
| **S0-arrival** Same, plane landing 36 while the departure waits | — | While the arrival was inbound: `not 18: arrivals landing 36`. After it landed, decided again → 18. Taxi 128 ticks, 19.2 blocks. |
| **S0-airliner** Stand at mid-runway (232,−50), wide airliner | — | `depart 36, taxi 23 blocks, entering 39 blocks in, 61 to run`, which is 53 + 8. Taxi 208 ticks, 25.4 blocks. Lift-off 295 ticks. No contacts. |
| **S1** Departure, 2-deep pit across the straight line and a 4-deep pit beside the lane | Fell into the pit (119 ticks below ground, y −62). Gave up, no take-off. | Went around both pits. Taxi 194 ticks, 30.0 blocks. Lift-off 257 ticks. **0 pit ticks.** |
| **S1** Wide airliner arrival, same pits | Fell into the 4-deep pit (304 ticks below ground). 544 ticks, then stopped short of the stand. | 214 ticks, 29.2 blocks, parked on stand (40,−40). **0 pit ticks.** |
| **S2** Apron with 4 parked (wide airliner, cargo, regional, large) + departure + wide airliner arrival | Departure rammed the large plane (137 bbox-touch ticks, 145 hull-overlap ticks) and gave up. Arrival 2400 ticks, 392.7 blocks, 108 hull-overlap and 1257 wing-overlap ticks, stopped short. | Departure 287 ticks, 44.6 blocks around the parked large plane, **0 contacts**, lift-off at 359 ticks. Arrival 763 ticks, 113.7 blocks around the parked aircraft, **0 bbox or hull contacts**, parked. Wing overlap only in the last 1.5 blocks onto a stand 10 blocks from a parked large plane (relaxed zone). |
| **S23** Stands 1–4 occupied; departure from stand 5 and wide airliner arrival to stand 6 | Departure taxied **136 blocks / 723 ticks** to the far threshold. Arrival 2400 ticks, 406.6 blocks, 160 wing-overlap ticks, stopped 6 blocks short. | Departure `taxi 28 blocks, entering 130 blocks in, 50 to run`: 212 ticks, 37.8 blocks. Arrival 594 ticks, 110.7 blocks (went straight to the far stand), parked. **0 contacts.** |
| **S3** Crossing traffic: departure leaving stand (40,−24) while a plane arrival taxis back to (40,−8) across its path | — | Arrival gave way to the runway holder (`giving way to #64`). The departure held (`blocked by #65`, then `giving way to #65`) while the arrival passed. Min centre distance 7.0 blocks. 0 contacts. Both completed: taxi 359 ticks / 33.9 blocks, and taxi-in 304 ticks / 42.2 blocks. |
| **S4** Stand walled on three sides, opening closed by three parked aircraft | Drove into them: 138 bbox-touch ticks, 308 hull-overlap ticks. Gave up. | Held on the stand, `no taxi route: blocked by #61`, 640 ticks. **0 contacts.** After two blockers were removed it planned again and left: 243 ticks, 31.9 blocks, 0 contacts. |
| **S5** Restart mid-taxi (departure) | Loaded as TAKEOFF at (34.9,−5.9) on the apron. Took off across grass and through the pit (agl 3 over a y −63 floor). | Loaded as PARKED at (24,−10), planned again (11.5 blocks), lifted off from the runway. 0 pit ticks. |
| **S5** Restart mid taxi-in | Taxi-in dropped; aircraft left where it stood. | Resumed: `taxi to stand 40,−61,−40, 5 of 5 blocks left`, parked on the stand. |

### Not measured

- A one-way airfield's effect on arrivals.
  - `bestEnd` returns the one-way end, and the go-around does not switch ends.
  - An arrival inbound from the wrong side was not flown.
- Two taxiing aircraft meeting head-on in a lane only one aircraft wide. Neither can reverse. Both hold (no contact), and the departure gives the runway back after `TAXI_OUT_HOLD_RELEASE`. Nothing resolves it by itself.
- The fighter, cargo and regional airliner as departing aircraft on the new route. They appear here as parked obstacles only.
