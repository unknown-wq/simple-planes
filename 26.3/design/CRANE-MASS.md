# Crane: load mass and gradual lift performance

The owner asked for an indirect weight estimate instead of the hard 1.55 cutoff: "so that it can lift a golem
only half a block, and haul a chicken easily any way it likes". This file records the formula, the
constants, the decisions and the in-game measurements. The physics is in DESIGN.md 6.6 and 6.7.

## Mass estimate

Only what every entity exposes, so modded mobs are covered without a table:

```
m = max(0.05, w^2 * h * (1 + clamp(KBR, 0, 1)) * mult)      drone masses (1 = the empty crane)
```

- `w`, `h`: the entity's current bounding box (`getBbWidth`, `getBbHeight`). Babies, slime size and the
  `SCALE` attribute follow on their own.
- `KBR`: the `KNOCKBACK_RESISTANCE` attribute, used as a density stand-in. Vanilla gives it to the things
  that are heavy for their size: iron golem and warden 1.0, ravager 0.75, hoglin and zoglin 0.6, and armour
  pieces that carry it on a player.
- `mult`: the product of the datapack tags the type is in:
  - `simpleplanes:crane_mass_x0_5`: x0.5, empty by default;
  - `simpleplanes:crane_mass_x2`: x2, empty by default;
  - `simpleplanes:crane_mass_x4`: x4, ships `minecraft:hoglin` and `minecraft:zoglin` (see open questions).
- The mass is taken at pickup, and again when a load converts on the hook.

## Lift model

```
T_MAX        = 8 G = 0.32                    rated thrust (was 3 G; the mass scale is unchanged)
assist(h)    = 1 + 0.8 * clamp(1 - h / 2, 0, 1)      h = load bottom above the ground under it
tMax         = T_MAX * assist(h)             per tick while a load hangs (ground effect off the load)
capacity     = 0.9 T_MAX / G - 1   = 6.20    hovers at any height with 10 % thrust in hand
lift limit   = 0.9 T_MAX 1.8 / G - 1 = 11.96 leaves the ground at all
ceiling(m)   = 2 (1 - (need - 1) / 0.8),  need = (1 + m) G / (0.9 T_MAX)   (m > capacity; none otherwise)
handling     = clamp((1 - (1 + m) G / tMax) / (1 - G / T_MAX), 0.15, 1)
```

- The controller's climb limit, horizontal acceleration and horizontal speed (`0.5 + 0.5 handling` of
  `V_MAX`) scale with `handling`. Descent is not slowed.
- Above capacity, the height target is capped at the ceiling, so the load hangs there.
- The mass scale was kept and `T_MAX` raised instead of scaling masses down. With masses scaled so that
  the golem fit under 3 G, light loads lost their damping on a 3 b rope in Sim (zombie 80 deg, cow 30 deg
  residual swing). With the original scale and 8 G, Sim passes C1.

## Decisions

- **Over the lift limit, refuse at pickup**, before the crane moves:
  `refused: too heavy: Ravager ≈ 14.64, max 11.96 (236% of capacity)`.
- **In flight, the limit is enforced three ways:**
  - A ceiling that turns negative (a converted load, a `debug tmax` cut): `overloaded: <name> is over the
    lift limit, lowering it`, and the load is set down.
  - The saturated-and-sinking release still applies to unlimited loads. A lift-limited load that sinks while
    saturated is settling onto its ceiling, so it is exempt. Without that exemption, the golem was dropped
    one second after pickup, while it sank from the winch-in height to 0.48 b.
  - A carry that makes no horizontal progress (0.5 b) for 200 ticks: `stuck: no progress with <name> for
    200 ticks (max lift h b), setting it down here`.
- **Messages:**
  - `picking up X (mass m, p% of capacity[, max lift h b])`
  - `picked up X (...)`
  - `load changed: A is now B (...)`
  - `/crane status` adds `use=p% ceil=h|none hand=f`
- **`crane_liftable` and `crane_never`:**
  - `crane_never` still refuses outright.
  - `crane_liftable` is the hostile allow-list when `ALLOW_HOSTILES` is false, and it also lets a type out
    of `c:bosses`. Mass still applies to it, and the ender dragon, wither, warden and elder guardian always
    stay refused.
- **Remote/autopilot:** a delivery with a heavy load uses the same carry path. Measured below, it either
  arrives (slower), stops with `stuck`, or is refused at pickup. Nothing hangs.

## Measured in game

Headless 26.3 server, superflat, 20 TPS. Crane in CARRY with a 3 b rope.

- **Climb:** from a hover at y -55 (5 b above the ground) to y -30, sampled with `/crane status` every
  0.5 s. "Max vy" is the highest vertical speed seen.
- **Max height:** the load's bottom above the ground at the end of that climb.
- **Forward:** `crane deliver` to a point 40 b east, with the time until `set down`. Wall-clock times
  include 2 s polling.

| Mob | Mass | % of capacity | Handling | Max climb b/t | 25 b climb | Max height above ground | 40 b delivery |
|---|---|---|---|---|---|---|---|
| chicken | 0.11 | 2 | 0.98 | 0.236 | 7.7 s | 26.6 (no limit) | 16 s, set down |
| baby zombie | 0.24 | 4 | - | - | - | no limit | (pickup message only) |
| zombie | 0.70 | 11 | 0.90 | 0.216 | 7.8 s | 25.4 (no limit) | 16 s |
| villager | 0.70 | 11 | 0.90 | 0.216 | 7.8 s | 25.4 (no limit) | 16 s |
| sheep | 1.05 | 17 | 0.85 | 0.204 | 7.8 s | 26.0 (no limit) | 16 s |
| cow | 1.13 | 18 | 0.84 | 0.201 | 7.8 s | 25.9 (no limit) | 16 s |
| llama | 1.51 | 24 | 0.78 | 0.191 | 8.6 s | 25.4 (no limit) | 16 s |
| spider | 1.76 | 28 | 0.75 | 0.180 | 8.7 s | 26.4 (no limit) | 18 s |
| polar bear | 2.74 | 44 | - | - | - | no limit | (pickup message only) |
| horse | 3.12 | 50 | 0.55 | 0.143 | 11.2 s | 25.7 (no limit) | 20 s |
| slime size 4 | 9.00 | 145 | - | - | - | max lift 1.0 b | (pickup message only) |
| iron golem | 10.58 | 171 | 0.15 | 0.015 (capped) | never | **0.48** | 42 s, set down |
| ravager | 14.64 | 236 | - | - | - | - | refused: max 11.96 |
| hoglin, zoglin | 17.47 | 282 | - | - | - | - | refused (tag x4) |
| ghast | 64.00 | 1032 | - | - | - | - | refused |

**Iron golem:**
- It hovered at a load-bottom height of 0.4778 b for 16 s of sampling: thrust constant at 0.4634, speed 0,
  rope angle 0. No oscillation.
- Carried over flat ground at that height, 40 b in 42 s.
- Crossed a 1-block step: the ceiling follows the ground under the load, and a passenger has no collision.
- At a 3-block wall it got onto the top and then made no progress. It reported `stuck` and was set down on
  the wall, unhurt (health 100).
- With `crane debug tmax 1 0.2` (lift limit 7.10) while carrying it, the result was `overloaded: Iron Golem
  is over the lift limit, lowering it`, then `load released: overweight`.

No `ERROR` or exception lines appeared in either server log.

Offline Sim, with the same classes and a 20 b step on a 3 b rope, is in DESIGN.md 6.6. Its climb rates
agree with the game to within 0.005 b/t.

## Commands

```
crane pickup <id> <entity>          # message carries mass, % and max lift
crane status <id>                   # ... mass=10.58 use=171% ceil=0.48 hand=0.15 ...
crane debug tmax <id> <0..2>        # test aid: rated thrust; prints capacity and lift limit
```

## Open questions

1. **Hoglin and zoglin.**
   - By box and KBR alone they come to 4.37, which is lighter than the golem, and they would be lifted
     freely.
   - The shipped `crane_mass_x4` tag puts them at 17.47, which is refused.
   - Keep the tag, or let them fly?
2. **Terrain.** A lift-limited load follows the ground at its ceiling and clips through low steps: a
   passenger has no collision, as for any load. Tall walls end in `stuck` and a set-down. Is that
   acceptable, or should the crane refuse a delivery whose track rises above what the load can clear?
3. **Mass is fixed at pickup.** A baby that grows up on the hook keeps its baby mass until it is set down.
   Conversions do refresh it.
4. **Chicken swing.** A chicken still keeps the small cosmetic swing (about 10 deg) it had before. The
   drone barely feels it.
5. **Density proxy.** Knockback resistance is the only attribute used. Max health and armour were not
   used, because they rank a zombie in armour or a boss-like mod mob as heavy for reasons unrelated to
   weight. The tags are the override.
