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
7. **Translations.** Keys are needed for `zh_cn` and other languages.
