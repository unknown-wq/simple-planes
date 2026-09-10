# Simple Planes Commands (26.2)

Autopilot cheat sheet: everything you can type in chat or on the server console.

Every command requires operator level 2 (`/op <name>` or the server console).
**No command requires a player** — they all take explicit coordinates, so they all work
from the console, from a command block, or from a datapack function. A player is only
needed where that is called out separately.

---

## Quick start

```mcfunction
# 1. Give yourself the tools
/give @s simpleplanes:runway_tool
/give @s simpleplanes:helipad_tool
/give @s simpleplanes:route_wand
/give @s simpleplanes:plane_strike_tool

# 2. Survey a runway: right-click one threshold, then the other
#    (or by command, if you already have the coordinates)
/autopilot survey 654 68 -9 654 68 -75

# 3. See what you got
/autopilot airfields

# 4. Send a plane from one airfield to another
/autopilot flight "airfield-1" "airfield-2"

# 5. Watch it fly
/autopilot tower
/autopilot status
```

---

## Strike

```
/autopilot strike <x y z> [distance] [bearing] [blast] [blocks] [fire]
```

Spawns an aircraft off to one side of the target and flies it straight in at full throttle.

| Argument | Range | Default | What it does |
|---|---|---|---|
| `x y z` | any | — | the point the aircraft flies into. `~ ~ ~` and `^ ^ ^5` both work |
| `distance` | 20…4000 | 400 | how many blocks out from the target it spawns |
| `bearing` | 0…359 | from the caller's side | **which side the run-in comes from** — see below |
| `blast` | 0…16 | 4 | 4 is ordinary TNT, 6 a charged creeper, 16 the ceiling |
| `blocks` | true/false | true | `false` — damages entities but leaves the build alone |
| `fire` | true/false | false | `true` — leaves fires burning |

```mcfunction
# 400 blocks of run-in, approaching from the northeast,
# blast 15, breaks blocks and starts fires
/autopilot strike ~ ~ ~ 400 50 15 true true

# a "clean" strike: the bang and the damage, but not one block broken
/autopilot strike 100 70 200 400 0 8 false false

# incendiary, no demolition
/autopilot strike 100 70 200 400 0 8 false true
```

### How bearing works

`bearing` is **the side the aircraft comes in from**, not the direction it flies.
Ordinary compass bearing, in degrees: `0` is north, `90` east, `180` south, `270` west.

* `0` — the aircraft spawns **north** of the target and flies south;
* `90` — spawns **east** and flies west;
* `50` — spawns to the northeast and flies southwest.

This way round is the useful one: you decide which side of the target the run-in
passes over, instead of working out the reverse heading in your head.

If `bearing` is left off, it is taken from whoever gave the order: the aircraft comes
in so that it passes **over you** on the way to the target. From the console it is
measured from the world spawn point instead.

---

## Gunship

```
/gunship launch <x y z> [arrows] [rate] [ammunition] [altitude]
/gunship status
/gunship stop
```

Spawns an armed helicopter at the given point. It climbs, holds a hover over that spot,
shoots hostile mobs with arrows, and once its magazine is empty it lands and despawns.
If it gets shot down it despawns immediately, and the report says so: aircraft lost.

| Argument | Range | Default | What it does |
|---|---|---|---|
| `x y z` | any | — | where it holds. `~ ~ ~` works |
| `arrows` | 1…1024 | 128 | magazine size — two full stacks |
| `rate` | 0.5…20.0 | 10.0 | **rounds per second**, not a tick interval |
| `ammunition` | any projectile item | `minecraft:arrow` | resolved from the item registry |
| `altitude` | 2…120 | 18 | how many blocks above the ground it holds |

```mcfunction
# all defaults: 128 ordinary arrows, 10 rounds/second, holding at 18 blocks
/gunship launch ~ ~ ~

# 64 arrows of strong poison, 4 rounds/second, higher up
/gunship launch 100 -60 200 64 4.0 minecraft:tipped_arrow[minecraft:potion_contents={potion:"minecraft:strong_poison"}] 30

# spectral arrows — light up anything they hit
/gunship launch ~ ~ ~ 96 10.0 minecraft:spectral_arrow
```

**`rate` is rounds per second.** That is how people actually talk about it: "ten rounds
a second". A tick-interval number would run backwards — smaller means faster — and
could not express a fractional rate; 7.5 rounds/second is a legal value and is honoured
exactly, not rounded to the nearest tick. The ceiling of 20 is the tick rate itself:
one arrow entity per tick is the hard limit.

**Ammunition is resolved from the item registry**, not from a hard-coded list: anything
the registry considers a projectile (`ProjectileItem`) qualifies. That covers ordinary
arrows, spectral arrows, arrows carrying any potion (the component travels with the
item), and arrows from other mods — nothing here needs to know about them in advance.
An item that is not a projectile is rejected with an explanation.

### What counts as an enemy

`net.minecraft.world.entity.monster.Enemy` — the same interface an iron golem, a snow
golem and a conduit target in vanilla. Plus one extra rule for a **provoked neutral**: a
mob currently attacking a player counts as an enemy too, even if it is not `Enemy` (an
enraged wolf pack, angered bees).

`MobCategory.MONSTER` did not work as the filter: in 26.2 it has forty-five entries, and
one of them is the **zombie horse**, a perfectly harmless mount. "The mob has a target"
did not work either, and for a decisive reason: `Mob#getTarget()` returns
`LivingEntity`, and a gunship is not a `LivingEntity`, so no mob could ever put it on
target in the first place. Without a player on the ground the gunship would never fire
a shot.

**Radius is 40 blocks, a sphere centred on the gunship.** At the 18-block default
altitude that is about a 35-block circle on the ground. It does not fly further or
pursue beyond that — it is a sentry post, not a hunter.

### Won't hit friendlies

Four separate rules, because "won't target a player" and "won't hit a player" are
different things:

* a player is never a target;
* a shot **is not fired at all** if its trajectory passes within 2.5 blocks of a
  player's hitbox — the actual ballistic arc, not a straight line to the target, because
  at this range the arc rises several blocks above the chord;
* **and the same goes for anyone else it refuses to shoot**: villagers, iron golems,
  pets, livestock — they get a half-block margin around their hitbox. Not targeting
  something and not shooting through it are different guarantees. Measured on the rig: a
  golem standing two blocks short of the target caught **15 shots out of 20** while the
  margin was measured from the entity's centre point rather than its hitbox (the golem
  is 2.7 blocks tall, and its head sits 1.35 blocks above that point). With the margin
  measured correctly the gunship does not fire at all in that geometry: **0 out of 20**;
* its own arrows cannot hit the gunship itself (it owns the projectile).

What this does not cover, to be honest about it: a missed arrow lands somewhere, and a
player who walks under it can still be hit. Arrows do not home.

### The rate is a combat rate, not a per-target rate

`LivingEntity#hurtServer` sets 20 ticks of invulnerability after a hit and, for more
than 10 of those ticks, ignores the next hit of the same strength. Arrows are all the
same, so shooting one target faster than about two rounds per second is wasted. The
gunship accounts for this: a shot is only taken if the arrow will land after that
window closes — both its own window and whatever window the arrows already in flight
will open. While the current target is invulnerable it switches fire to the next enemy,
and if there is nobody else to switch to, **it does not fire and keeps the round**.

Measured on the rig: before this rule, one skeleton (20 HP) cost **14 rounds**; after,
**3**. Six skeletons around it died to **19 rounds**.

### How much it hits

Rig: a superflat world, midnight, `spawn_mobs false`, targets with 4096 HP so the whole
magazine goes into a live target.

| Target | Hits |
|---|---|
| stationary target, 4…32 blocks out on the ground | **100%** (60 of 60) |
| zombies at their own speed (0.23), 8 of them, walking past | **87…92%** |
| zombies at double speed (0.5) | **40…53%** |

Past 35 blocks along the ground (at 18 blocks of altitude) there are no targets at
all — the radius is a sphere. Misses against a moving target are lead error, not range:
the same shot against the same target standing still always connects.

### Landing is landing

`landed` is only printed once the helicopter is **actually resting on the ground**: the
column under it is checked against two height maps (`MOTION_BLOCKING` and
`OCEAN_FLOOR`), and a mismatch between them means it is sitting on a fluid. Ditching in
water is reported as ditching:

```
Gunship #425 did not land: ditched in water at 66401, -61, 66401 - floating, not landed.
```

---

## Strike Tool

`/give @s simpleplanes:plane_strike_tool`

* **right-click a block** — launch a strike into that block;
* **right-click the air** — show the current settings;
* **sneak + right-click the air** — cycle the distance (100 → 200 → 400 → 800), and the
  blast strength on every wraparound.

The gesture only cycles those two numbers. The full set of settings, including "don't
break blocks", "start fires" and a pinned bearing, is written onto the tool **in hand**
with:

```
/autopilot tool <distance> [bearing] [blast] [blocks] [fire]
```

The same arguments in the same order as `strike`, minus the target — the target is
whatever block you right-click.

```mcfunction
# put exactly the setup from the strike example above onto the tool
/autopilot tool 400 50 15 true true

# clear the pinned bearing: -1 means "approach from wherever the player stands", as usual
/autopilot tool 400 -1
```

Arguments left off keep their current value — a second call can change one setting
without restating the rest. Settings live on the item itself, so they survive logging
out, a chest, and death.

---

## Flights

```
/autopilot route   <from x y z> <to x y z>       [speed] [type <aircraft>]
/autopilot flight  <"airfield"> <"airfield">      [speed] [delay <seconds>] [type <aircraft>]
/autopilot inbound <from x y z> <"airfield">      [speed] [type <aircraft>]
```

| Command | What it does |
|---|---|
| `route` | spawns an aircraft **in the air** above the first point, flies out and back, then lands at the nearest airfield if one exists |
| `flight` | a full sortie: spawn on the stand, taxi out, take off, fly the route, fly the approach, land |
| `inbound` | spawns at the given point and flies the approach — to test the landing on its own, without a departure |

`speed` is in blocks per tick, range **0.40…2.80**, default **2.60**. A value outside
that range is not an error: it is clamped to the nearest bound, and the launch report
says the speed the aircraft was actually sent at.

`type <plane|large|cargo|random>` picks the airframe. Left off, `flight`, `route` and
`inbound` all fly the starter plane. `random` draws from the three fixed-wing airframes
only — a helicopter is never picked, and `type helicopter` is refused outright with a
pointer to `heliflight`/`heliinbound` instead, since none of `route`, `flight` or
`inbound` means anything for a rotorcraft: no take-off roll, no glide slope.

`flight` also takes `delay <seconds>` (0…3600), how long the sortie waits parked before
it asks for the runway. It comes after `speed` when both are given.

```mcfunction
# a sortie between two airfields — take-off and landing
/autopilot flight "airfield-1" "airfield-2"

# same, but slower
/autopilot flight "airfield-1" "airfield-2" 1.20

# a cargo plane, departing in 30 seconds
/autopilot flight "airfield-1" "airfield-2" delay 30 type cargo

# an out-and-back leg with no airfields involved
/autopilot route 654 68 -9 654 68 -600

# test the approach: the aircraft appears 1500 blocks out and lands
/autopilot inbound 654 120 1500 "airfield-1"
```

Airfield names go in quotes. Tab completion works.

---

## Shuttles

A shuttle is one aircraft flying between two airfields **for ever**: depart A, land B,
taxi to a stand, wait, depart B, land A, taxi in, wait, and round again. It survives a
restart. It is the only thing in the mod that launches a flight nobody typed a command
for.

```
/autopilot shuttle add  <"airfield"> <"airfield"> <seconds> [type <aircraft>]
/autopilot shuttle list
/autopilot shuttle stop [id]
```

```mcfunction
# a plane between two fields, one minute standing at each end
/autopilot shuttle add "airfield-1" "airfield-2" 60

# a cargo plane, five minutes on the ground each turnaround
/autopilot shuttle add "airfield-1" "airfield-2" 300 type cargo

/autopilot shuttle list
/autopilot shuttle stop 1
```

`<seconds>` is the **turnaround**: how long the aircraft stands on its stand before it
leaves again, not how long a leg takes. Range **10…3600**; the bounds are on the
argument itself, so a mistyped `0` or `604800` is refused as you type it rather than
three hours into an unattended run. It is counted in ticks of a running world, so a
world that is shut down or lagging does not advance it.

The first departure is immediate.

**One airframe, reused.** The whole point is that the same plane goes back and forth.
The first departure builds an aircraft; every departure after that flies *that* aircraft,
picked up off the stand it parked on. If the aircraft cannot be found or used, the
shuttle says so and retries — it never builds a replacement.

**While a shuttle is waiting it keeps its aircraft's chunk loaded.** That is what makes
it work with nobody nearby: a parked aircraft in an unloaded chunk cannot be re-tasked.
Nine chunks per waiting shuttle, dropped the moment it departs, pauses or is stopped.
At most 4 shuttles per dimension, for that reason.

`/autopilot shuttle list` shows, per shuttle: both fields, the turnaround, how many legs
have been flown, which aircraft, where it is in the cycle, when the next departure is
due, and the last thing that went wrong.

```
1/4 shuttles running in this dimension, and 1 paused.
  shuttle 1: airfield-1 <-> airfield-2, 60s turnaround, 7 legs flown, plane #214
    waiting at airfield-1, next departure to airfield-2 in 0:41
  shuttle 2: airfield-3 <-> airfield-4, 120s turnaround, 2 legs flown, plane #88
    PAUSED - /autopilot shuttle stop 2 to remove it
    last problem: airfield "airfield-4" no longer exists (removed, or renamed under it)
```

A departure being retried shows as `(retry N)` beside the countdown, which is the only
thing that tells a stuck shuttle from one that is simply between legs.

**When something goes wrong** the shuttle either *defers* the departure or *pauses* for
good. A deferral is retried for as long as it takes: 30 seconds the first time, growing
with the failure count to one attempt every 5 minutes, and reported for the first three
failures before it goes quiet — the reason and the retry count stay in the listing. It
never gives up on a condition that can clear, because there is no verb to resume a shuttle
that has. A pause is for something you have to deal with; a paused shuttle stays in the
list with its reason until you stop it, and does not use one of the 4 running slots.

| What happened | What the shuttle does |
|---|---|
| the destination had no free stand, so the aircraft stopped on the runway | counts as arrived; the next departure lifts it off the runway; the reason is on the listing |
| an airfield is renamed or removed under it | pauses within a second of the aircraft being on the ground; a leg already in the air is left to finish |
| the aircraft is destroyed in flight | pauses, "its aircraft was lost in flight" |
| the aircraft is flown away, or ends a leg at neither field | pauses, giving the coordinates it is at |
| somebody is sitting in it, or it is busy on another flight | defers. `/autopilot flight` will not take a shuttle's aircraft |
| all 24 autopilot slots are in use | defers |
| the aircraft cannot be found at all (a cold field just after a restart) | waits 5 seconds for the chunk to load, then defers; pauses if it is still missing after 3 more tries |

`stop` removes the schedule and drops the chunk hold. **The aircraft is left standing
where it is** — it is not deleted, because it may be carrying cargo.

Both airfields must be in the dimension the command is run in, and they must be two
different fields. Both are checked for a usable runway and at least one marked stand
before the shuttle is created, because a shuttle that cannot park is an aircraft left on
a runway every turnaround rather than only once.

---

## Airfields

```
/autopilot survey <threshold1 x y z> <threshold2 x y z>
/autopilot airfields
/autopilot airfields info   <"airfield">
/autopilot airfields show   <"airfield">
/autopilot airfields rename <"airfield"> <"new name">
/autopilot airfields remove <"airfield">
/autopilot airfields park   <"airfield"> <x y z>
/autopilot airfields unpark <"airfield"> <x y z>
```

* `survey` — measures a runway from its two thresholds and registers it. The name is
  assigned automatically: `airfield-1`, `airfield-2`, …
* `airfields` — the list, nearest first, with distance and bearing. Names are clickable.
* `info` — full characteristics: length, width, slope, roughness, both thresholds'
  headings, approach obstacles, stands.
* `show` — highlights the centreline, thresholds and stands with particles in the world.
* `park` — marks a stand, where an aircraft taxis out from before departure.

**A runway shorter than 18 blocks is not accepted**: a sortie into it is refused with
the numbers, rather than ending in a wrecked aircraft. It is not the take-off that sets
this floor (the ground roll is only about 4 blocks) but the landing.

The runway tool does the same job:

`/give @s simpleplanes:runway_tool`

* **right-click both thresholds** — survey the runway;
* **sneak + right-click a block** — cancel a half-marked runway;
* **right-click the air** — list airfields;
* **sneak + right-click the air** — switch to stand-marking mode;
* in stand mode, **right-click** marks a stand, **sneak + right-click** removes the
  nearest one.

---

## Helipads

A helipad is **not a short runway** — it has no heading, no centreline, and can be
approached from any side. That is why it has its own tool, its own commands and its own
list.

```
/autopilot helipad survey <corner1 x y z> <corner2 x y z>
/autopilot helipads
/autopilot helipads info     <"helipad">
/autopilot helipads show     <"helipad">
/autopilot helipads resurvey <"helipad">
/autopilot helipads rename   <"helipad"> <"new name">
/autopilot helipads remove   <"helipad">
```

* `helipad survey` — mark **two opposite corners** of the pad. Its centre and size are
  worked out automatically; the name is assigned automatically too: `helipad-1`,
  `helipad-2`, …
* Size ranges from 3x3 to 15x15. Anything smaller or larger is refused.
* `show` — highlights the pad's outline, its centre, and every clear approach bearing.

### What the survey checks, and what it refuses

A helipad is registered only if it passes **every** check. Every single block is
checked — there is no sampling step anywhere.

| Check | Refused if |
|---|---|
| surface | there is water/lava, an unloaded chunk, or a height difference of more than 1 block |
| the column above the pad | anything stands above the pad or the 2-block ring around it (up to 24 blocks high) |
| approach bearings | 8 bearings 45° apart, each checked up to 64 blocks along a 25° glide; **at least one** must be clear |

A refusal always names both the coordinate and what needs fixing:

```
REFUSED: the pad is not all solid ground - 1197, -60, -3 is water or lava
REFUSED: the pad surface varies by 6 blocks (highest at 1300, -54, 0); flatten it to within 1
REFUSED: no clear approach: every one of the 8 bearings has terrain across it inside 64 blocks.
```

The survey **moves the centre to the middle of the pad**, rather than taking the
midpoint of the two clicks: if the pad has an edge (a kerb, a step), the centre is found
from that edge instead. Both coordinates are printed side by side, and the helicopter
lands on the computed one:

```
marked centre 999, -58, 999 -> pad centre 1000, -58, 1000 (moved 1.4 blocks); touchdown at 1000.5, -57.0, 1000.5
```

On flat, edgeless ground (a superflat world, a pad flush with the field) there is
nothing to move it by — the clicks are taken as-is.

### Helipad tool

`/give @s simpleplanes:helipad_tool`

* **right-click a block** — mark one corner of the pad, then the opposite one (this
  runs the survey);
* **sneak + right-click a block** — cancel a half-marked pad;
* **right-click the air** — list helipads;
* **sneak + right-click the air** — survey a 7x7 pad centred on where you are standing.

This is a **separate item**, not a third mode of the runway tool: two clicks there are
the two ends of a **line**, and two clicks here are the two corners of an **area** — mixing
them up silently is not an option.

---

## Helicopter flights

```
/autopilot heliflight  <"helipad"> <"helipad"> [speed] [delay <seconds>]
/autopilot heliinbound <from x y z> <"helipad"> [speed]
```

| Command | What it does |
|---|---|
| `heliflight` | a full sortie: spawn on the pad, vertical take-off, route, hover over the second pad, vertical landing |
| `heliinbound` | spawns in the air and flies only the approach — to test the landing without a take-off |

`speed` is in blocks per tick, range **0.20…2.00**, default **1.20**. This is **not**
the same range as a fixed-wing aircraft's: a helicopter's useful speed starts below a
plane's stall speed and ends well below its cruise.

```mcfunction
/autopilot heliflight "helipad-1" "helipad-2"
/autopilot heliflight "helipad-1" "helipad-2" 0.40
/autopilot heliflight "helipad-1" "helipad-2" delay 30
/autopilot heliinbound 300 -30 0 "helipad-2"
```

A landing report always names **how far off the pad centre it landed**, not just "landed":

```
Helicopter #64 landed at helipad-2, 600.6, -60.0, 0.5 (0.13 blocks from the pad centre
600.5, -60.0, 0.5, tolerance 3.0; 1087 ticks from lift-off, 436 ticks from the run-in).
```

The word `landed` is only printed if **three** measurements agree: on the ground, not
in water, and standing on the pad itself — both horizontally and in elevation.
Otherwise the line says something else, and names exactly what did not line up:

```
Helicopter #100 did not land on helipad-6: came to rest 16 blocks above the pad surface -
on something the survey did not measure, at 2800.5, -44.0, 0.5 (pad centre 2800.5, -60.0, 0.5,
tolerance 3.0). 3007 ticks from lift-off, 390 ticks from the run-in.
```

That is a real case from the field: a helipad was surveyed, someone later built a stone
roof over it, and the aircraft neatly landed **on the roof**, 0.03 blocks off the pad
centre horizontally. Without the elevation check that would have printed as `landed`.

If the flight could not complete, the log carries a line with the reason and a
coordinate: could not take off, gave up en route, could not reach a hover, could not
land, the pad never cleared, or the aircraft was lost.

**A requested speed above 1.10 blocks/tick cannot be made good in level flight**, and
that is said out loud — once, from the air, not after the fact from the flight time:

```
Helicopter #253 cannot make good 1.75 blocks/tick in level flight - full forward cyclic is
holding 1.11. The leg will take that much longer.
```

**A helicopter cannot be sent the fixed-wing way.** `/autopilot flight`, `route` and
`inbound` with `type helicopter` are refused: a helicopter has no ground roll and
nothing about a glide-slope approach applies to it. If a pad already has an aircraft on
it, the second one holds overhead in a circle and lands once it clears.

---

## Route Wand

`/give @s simpleplanes:route_wand`

* **right-click a block** — add a waypoint;
* **sneak + right-click a block** — finish the route and launch the aircraft;
* **right-click the air** — preview the route with particles;
* **sneak + right-click the air** — clear the route.

---

## Blast protection

Another mod may ask for an aircraft's explosion to be weaker, or not to happen at
all — a claims mod, say, that does not want craters inside a claim. Such requests are
called *blast guards*; Simple Planes itself never creates one, it only lets other mods
register them. The switch exists for when such a mod is installed and you no longer
want that behaviour.

```
/blastguard            # same as status
/blastguard status     # on or off, and how many guards are registered
/blastguard on         # ask guards before every explosion
/blastguard off        # ask nobody
```

`off` really means "off", not a third mode: the explosion happens exactly as the
aircraft ordered it (strength, block breaking, fire) — as it did before guards existed.
Registrations are not lost while it is off — `on` restores the previous behaviour
without a restart.

**Enabled by default.** If no guard mod has registered — true of any build running only
Simple Planes — the switch changes nothing: explosions are identical in both positions,
and `status` says so plainly.

The setting is server-wide (not per dimension) and stored in the world, in
`<world>/data/simpleplanes/blast_guard.dat`, so it survives a restart.

---

## Claimed airspace

The same idea as the blast guards, but for the route. Another mod — a claims mod, say —
can say "this pilot had better not fly here", and the autopilot factors that into its
heading choice. Such answers are called *airspace guards*; Simple Planes itself never
creates one, it only lets other mods register them.

```
/airspaceguard            # same as status
/airspaceguard status     # on or off, and how many guards are registered
/airspaceguard on         # ask guards when choosing a heading
/airspaceguard off        # ask nobody
```

**This is not a no-fly zone.** Guards are only asked while the autopilot is flying the
aircraft. A player holding the stick is not affected at all: nothing turns them away and
nothing stops them anywhere.

**And it is not a ban, only advice.** A "not wanted here" answer feeds into the same
cost calculation that already chooses between climbing over a ridge and going around
it. If going around is cheaper, it goes around. If the claimed airspace is wider than
the autopilot can steer clear of (maximum deviation: 60°), it flies straight through and
says so honestly on the board. An aircraft never stops at the boundary, never circles,
and never abandons a leg.

There is a separate case for "already inside". If the autopilot is engaged while the
aircraft is already inside claimed airspace, it leaves by the nearest heading it can
see, rather than trying to "not enter" ground it is already standing on. That gets its
own line on the board, so it is not confused with routing around.

Which rules to actually apply is entirely up to the guard: along with the point, it is
handed the whole flight — what aircraft it is, who it is flying for, **whether that
player is aboard**, where it took off from and where it is headed now. So a claims mod
is free to, say, turn away only piloted aircraft and let unmanned ones through directly,
and to leave alone the territory that contains the departure or destination point
entirely — otherwise nothing could take off from or land at that airfield at all. None
of these rules is set by Simple Planes, or known to it: it reports the facts and prices
whatever answer it gets back.

`off` really means "off": the autopilot plans a route over terrain alone, as it did
before this feature existed. Registrations are not lost — `on` restores the previous
behaviour without a restart and does not disturb aircraft already in flight: the route
is recalculated on the next planning cycle.

**Enabled by default.** If no guard mod has registered — true of any build running only
Simple Planes — the switch changes nothing: aircraft fly identically in both positions,
and `status` says so plainly.

The setting is server-wide (not per dimension) and stored in the world, in
`<world>/data/simpleplanes/airspace_guard.dat`, so it survives a restart.

---

## Monitoring

```
/autopilot tower              # a board of every runway: free / occupied / who is holding
/autopilot tower <"airfield"> # the same for one runway
/autopilot status             # every aircraft under autopilot: mode, altitude, speed, heading
/autopilot status <id>        # just the one aircraft, by the number the reports print
/autopilot stop               # take everything currently flying off autopilot
/autopilot stop <id>          # take just the one aircraft off autopilot
```

`<id>` is the plain number every message prints after the `#` — `Plane #4231 going around`,
the `#4231` at the head of a status line, `reserved by #7`. Not an entity selector: type the
number you can see. It is only unique inside one dimension, which is the one you are in.

The board shows the rule that actually governs right now: there is no queue — a runway
goes to whoever asks for it first. Departure ordering is not built yet.

To watch it with your own eyes:

```mcfunction
/gamemode spectator
/autopilot status              # find the aircraft's number and coordinates
/tp @s <coordinates from status>
```

In spectator mode chunks load around you, so you can follow an aircraft by eye for the
whole sortie. The aircraft itself does not need you for that — the autopilot holds its
own loading tickets.

---

## Useful vanilla commands

```mcfunction
/kill @e[type=simpleplanes:plane]        # clean up anything left over from testing
/kill @e[type=simpleplanes:helicopter]   # same, for helicopters
/gamerule minecraft:spawn_mobs false     # this is the 26.2 name, not doMobSpawning
/gamerule minecraft:advance_time false   # the former doDaylightCycle
/time set midnight                       # otherwise skeletons burn in the sun and "kill themselves"
/summon simpleplanes:plane ~ ~ ~         # a plain aircraft, no autopilot
/time set day
/weather clear
/gamerule doDaylightCycle false
```

On a dedicated server flying with no players present, set
`pause-when-empty-seconds=0` in `server.properties` — otherwise the server goes to
sleep and the aircraft freeze in mid-air.

---

## Limits

| What | Value |
|---|---|
| Aircraft under autopilot at once | 24 |
| Cruise speed | 0.40…2.80 blocks/tick, default 2.60 |
| Blast strength | 0…16, default 4 |
| Strike spawn distance | 20…4000 blocks, default 400 |
| Minimum usable runway | 18 blocks |
| Glide slope angle | 8° |
| Departure delay | 0…3600 seconds |
| Helipad size | 3x3 to 15x15 |
| Helicopter cruise speed | 0.20…2.00 blocks/tick, default 1.20 (see note) |
| Helipad approach bearings | 8, checked up to 64 blocks along a 25° glide |
| Gunships at once | 16 |
| Gunship magazine | 1…1024, default 128 |
| Rate of fire | 0.5…20.0 rounds/second, default 10.0 |
| Hover altitude | 2…120 blocks, default 18 |
| Engagement radius | 40-block sphere (about 35 blocks on the ground from 18 blocks up) |
| Arrow muzzle velocity | 3.0 blocks/tick — a fully drawn bow |
| Maximum airspace deviation | 60° |
| Shuttles per dimension | 4 running, plus up to 4 paused records |
| Shuttle turnaround | 10…3600 seconds |

**Note on helicopter speed.** The argument accepts up to 2.00, but the aircraft runs
into its own thrust ceiling around **1.10 blocks/tick**: 1.20 and 1.75 both fly the
same, with the cyclic pinned to its stop the whole leg. Below 1.10 the requested value
is made good exactly (0.50 → 0.524). So the upper part of the range currently changes
nothing.

Details are in [`26.2/AUTOPILOT.md`](26.2/AUTOPILOT.md); how all of this is tested on a
headless server is in [`26.2/TESTING.md`](26.2/TESTING.md).
