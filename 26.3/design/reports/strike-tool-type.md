# Strike tool: choosing the aircraft

Branch `claude/strike-tool-type-26.3`, from `origin/26.3-beta` (`a7bf658`). Module `26.3/`.
Test server `/home/user/sp-strike-type-server`, port 25711, superflat.

## What changed

- `AircraftType`: the strike set (`plane`, `large`, `cargo`, `fighter`, `airliner`, `random`),
  `canStrike()`, `strikeTypes()`, `byNameOrNull()`, and `of(EntityType)` for plane items.
- `AutopilotComponents.STRIKE_TYPE` (`simpleplanes:autopilot_strike_type`), a new component beside the
  existing four. Absent means the starter plane, so old tools behave exactly as before.
- `AutopilotSpawner.launchStrike(..., AircraftType)` builds the chosen airframe. The old overload
  sends `plane`. The launch message names the airframe that was built.
- `PlaneAutopilot.strikePushOverLead`: a slow-pitching airframe starts its dive earlier, by the
  extra push-over arc compared with the starter plane. `AutopilotSpawner.minimumStrikeDistance`
  raises a spawn distance that is too short for that arc: large 200, airliner 234, cargo 318. Both
  are derived from `getRotationSpeedMultiplier`, the existing per-airframe number. The lead is zero
  for the plane and the fighter, so their runs are unchanged.
- `PlaneStrikeToolItem`: the aircraft comes from a plane item in the other hand, or else from the
  stored type. Refusals come with a reason. The aircraft is shown in the tooltip, the status line
  and the feedback messages.
- `AutopilotCommand`:
  - `strike … [type <aircraft>]`.
  - `tool … [type <aircraft>]` and `tool type <aircraft>`.
  - Strict parsing: a refused or mistyped name launches nothing and writes nothing.
- `StrikeToolTest`: `/autopilot tooltest`, the tool in a Fabric `FakePlayer`'s hands (test tooling).
- Lang keys in `en_us.json` and `ru_ru.json`. `zh_cn.json` is untouched.
- Docs:
  - `AUTOPILOT.md`: the tool section, "The attack run", "Which airframe flies", and the new
    "Choosing the aircraft".
  - `COMMANDS.md`: Strike and Strike Tool.
  - `TESTING.md`: the command table and a tooltest recipe.

## UX

The plane in the other hand flies the strike, the way a bow takes the arrow in the other hand. The
item is used up (not in creative), and nothing is written back to the tool.

Why this gesture:

- The one spare gesture is already taken by the distance and blast cycle.
- A GUI would be a first among the mod's tools.
- A plane item already says which airframe it is and what wood it is made of.

Using the item up also closes a dupe. A crashed strike plane drops its item, so an aircraft that was
only copied would print a free plane on every click.

For repeatable strikes, the tool also stores an aircraft, set with `/autopilot tool type <aircraft>`.

## Commands

```
/autopilot strike <x y z> [distance] [bearing] [blast] [blocks] [fire] [type <aircraft>]
/autopilot tool <distance> [bearing] [blast] [blocks] [fire] [type <aircraft>]
/autopilot tool type <aircraft>
/autopilot tooltest hold <tool> [<other>] | use <pos> | air [sneak] | run <args>
```

`type` may follow any argument. Arguments left off keep their value.

## Results

Real time, bearing 0, stored type. Each cell gives ticks to impact and the miss in blocks. Every
run is a hit (8 blocks or less).

| aircraft | 100 | 200 | 400 | 800 |
|---|---|---|---|---|
| plane | 74 t, 5 | 96 t, 6 | 164 t, 6 | 305 t, 6 |
| large | 95 t, 5 (spawned at 200) | 96 t, 5 | 170 t, 5 | 319 t, 5 |
| cargo | 151 t, 7 (spawned at 318) | 139 t, 7 (spawned at 318) | 166 t, 7 | 299 t, 7 |
| fighter | 50 t, 3 | 82 t, 3 | 149 t, 4 | 268 t, 3 |
| airliner | 138 t, 5 (spawned at 234) | 137 t, 5 (spawned at 234) | 210 t, 5 | 417 t, 5 |

Other runs:

- **Bearing 90, 400 blocks:**
  - plane 194 t / 6, large 175 / 6, cargo 178 / 7, fighter 159 / 2, airliner 217 / 5.
  - random: 5 draws, all hits, 6 to 8 off.
- **Other hand**, overriding a stored fighter:
  - plane 173 / 6, large 174 / 5, cargo 164 / 7 (it flew in birch, checked on the entity),
    fighter 148 / 4, airliner 215 / 5.
  - The item was used up, and the next click fell back to the stored type.
- **Old tools:**
  - A tool with no components flew the starter plane: 185 t, 6 off.
  - A tool carrying the old four components flew the same trajectory as the equivalent console
    strike.
- **Baseline:** `strike` output against the `5.4.0-beta.2` jar was identical apart from entity ids
  and the appended "Aircraft: plane.".
- **Refusals:**
  - Refused through the command, the other hand and a stored value: quadcopter, crane, helicopter,
    mini_helicopter, airship, and unknown names.
  - `tool 300 type quadcopter` changes nothing.
  - A `/give` with `autopilot_strike_type="quadcopter"` does not parse.

## Excluded, and why

Each rotorcraft and the airship was flown on a temporary build that allowed it:

- **Quadcopter (crane)**: peaceful, never offered.
- **Helicopter**: it climbed without end (+0.57 b/t, past y=1400). `tickStrike` is a fixed-wing
  control law and `HelicopterEntity` overrides the pitch, roll and push it drives.
- **Mini helicopter and medical mini helicopter** (the same entity): it climbed to 160 above ground,
  stalled and went off 397 blocks short.
- **Airship**: it cannot dive (0.12 b/t down at most). It took 1021 ticks and went down 90 blocks past
  the target.

## Known limitation

The cargo plane's final is long and shallow. At two superflat sites, a floating structure in the dive
corridor brought it down 73 and 119 blocks short, reproducibly. The steeper airframes passed over
it. The workaround is to pin a bearing over open ground.

## Open questions

1. **Using up the other-hand plane.** Consuming it is the dupe fix. The alternative is to stop strike
   planes dropping their item, which is an existing free-plane source even without this change.
2. **What comes from the other-hand plane.** Only its material comes along, not its upgrades. Should
   upgrades come too?
3. **Minimum distance.** It moves a large plane at 100 out to 200, and a cargo out to 318. Is this
   acceptable, or should those short distances be refused instead?
4. **Rotorcraft strikes.** A helicopter strike would need its own law, built on `HelicopterAutopilot`.
5. **`random`.** Keep it? It draws plane, large or cargo, as on `route`.
6. **`/autopilot tooltest`.** Keep it in the shipped jar, or strip it?

## Drones

Added on top of the above: `strike_drone` (fixed-wing, render model ported from the 26.2 line —
`STRIKE-DRONE-MODEL.md`) and `fpv_drone` (a second, armed multirotor entity, separate from the
peaceful `QuadcopterEntity`, sharing only `MultirotorPhysics`). Both are `AircraftType#isDrone()`,
`type strike_drone|fpv_drone` on `strike`/`tool`, or the item in the other hand.

**Warhead override.** `AircraftType#warhead`/`PlaneEntity#warhead` swap in `Blast#forDrone()` —
`DRONE_POWER` (1.0F), `breaksBlocks` kept, `fire` forced off — at read time
(`PlaneEntity#explode`), so whatever the tool/command asked for is replaced. Still through
`Blast#detonate`, so `BlastGuards` and the explosion game rules still apply.

**Picking `DRONE_POWER`.** New `tooltest charge <pos> <power> [<blocks>]` and
`tooltest crater snapshot/diff` (bare `Blast#detonate`, no aircraft) gave, on the superflat rig:

| Power | Blocks removed | Farthest | Fire |
|---|---|---|---|
| 0.5 | 1 | 0.71 | 0 |
| **1.0** | **8** | **1.22** | **0** |
| 1.5 | 8 | 1.22 | 0 |

1.0 is the cheapest power that reliably takes the full 2×2×2 around the point (effective radius
about a block); 1.5 removes nothing more. No fire at any of the three.

**Flown into a target**, bearing 0, superflat, tool defaults (4.0/breaks blocks/no fire, overridden
to 1.0/no fire on launch):

| Aircraft | 100 | 200 | 400 | 800 |
|---|---|---|---|---|
| `strike_drone` | 56 t, 4.7 off | 91 t, 5.8 off | 165 t, 5.6 off | 306 t, 5.1 off |
| `fpv_drone` | 67 t, 1.2 off | 137 t, 1.2 off | 236 t, 0.6 off | 437 t, 1.3 off |

Every crater diff: 4-8 blocks removed, fire 0.

**Finding: `strike_drone` shares the starter plane's dive precision (~5 blocks), which is not
precise enough for a ~1-block charge; `fpv_drone` is.** `strike_drone` is the same fixed-wing
`tickStrike` control law on a lighter airframe, and its miss (4.7-5.8) is the same few blocks
`plane`/`large`/`airliner` already show in the table above — fine for their 4.0-16.0 warheads and
multi-block craters, not fine for a warhead that only reaches about a block. All four
`strike_drone` runs crashed into the ground short of the target (the "committed" dive points the
nose straight at the aim point on a fixed dive angle, and a fast, low-drag airframe can reach the
ground before it closes the horizontal gap) rather than registering a hit
(`PlaneAutopilot.STRIKE_DRONE_HIT_RADIUS` is 2.0). `fpv_drone` does not fly that law at all —
`FpvDroneEntity#steer`/`#control` close on the aim point by velocity feedback — and landed within
0.6-1.3 blocks on all four distances, a hit every time. This is read as the intended split (a fast,
cheap, somewhat-imprecise munition versus a slow, precise one), not a bug to fix in this session;
flagged here in case the owner wants `strike_drone`'s dive re-tuned or its hit radius loosened to
match.

**Refusals reverified** on every path: `strike`/`tool type quadcopter|crane|helicopter` unchanged;
`route`/`flight`/`inbound`/`shuttle add type strike_drone|fpv_drone` refused the same way
`helicopter` already was ("a one-way munition and cannot land"); `type random` never drew a drone
(8/8 draws were plane/large/cargo).

**Regression: the five already-shipped types rerun unaffected** at 200 blocks — `plane` 96 t/6,
`large` 96 t/5, `cargo` 139 t/7 (raised to 318), `fighter` 82 t/3, `airliner` 137 t/5 (raised to
234) — every figure matches the Results table above exactly.

No exceptions or errors in the server log across the whole session (`grep -icE "exception|error"
console.log` after the run: only the four startup "offline mode" `WARN` lines and JOML's `Unsafe`
deprecation notice, both pre-existing and unrelated).

Docs updated: `AUTOPILOT.md` ("Plane Strike Tool", "Choosing the aircraft" intro, "The warhead", and
a new "The drones" subsection), `COMMANDS.md` (Strike, Strike Tool, Flights), `TESTING.md` (the new
`tooltest` subcommands).

### Open questions (drones)

1. **`strike_drone`'s accuracy vs. its own hit radius**, as above — leave as documented behaviour,
   retune the dive, or loosen `STRIKE_DRONE_HIT_RADIUS`?
2. **No recipe.** Neither drone item has a crafting recipe; they reach a player's inventory through
   the creative tab or `/give` only, same as the render/physics work this session did not add a way
   to build one in survival. Worth one, or is `/give`-only acceptable for a one-way weapon?
3. **Drone item tooltip/rendering** was reviewed in source and via the headless log only — this
   session had no real client to confirm the item icon or the in-world model on screen.
7. **Translations.** Keys are needed for `zh_cn` and other languages.
