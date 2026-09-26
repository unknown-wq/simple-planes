# Silo stuck in "busy (opening)" after a remote launch

Base: `26.3-beta` `a7bf658` (5.4.0-beta.2, the build the player runs). Branch `claude/silo-stuck-busy-26.3`.
Headless dedicated server, superflat, **no player online at all**, no forceload. Commands from the console.

## What happened in the screenshot

The chat shows `The game is frozen`, which is the output of `/tick freeze` (`commands.tick.status.frozen`; `/tick
query` prints a second line). Everything after it happened in a frozen game:

- `Level#tickBlockEntities` skips every ticker while `!tickRateManager().runsNormally()`, so the hatch does not move.
- Game time (`ServerLevel#tickTime`) does not advance and chunk tickets do not age (`purgeStaleTickets` is skipped).
- `TickRateManager#isEntityFrozen` exempts players and anything carrying one, so the player's aircraft still flew.

Reproduced on 5.4.0-beta.2, exactly the screenshot's sequence:

```
/tick freeze                         -> The game is frozen
/missile launch 5000 -61 5000 ...    -> Silo at 5000, -61, 5000: hatch opening, tier 1 missile to ... (100.0 blocks).
  (5 s later) status                 -> loaded, opening, hatch 0.00
/missile launch (again)              -> cannot launch: busy (opening).
/tick unfreeze                       -> missile #3 launched 1 s later, ARRIVED, silo idle 12 s later
```

So the T1 at 2925, 65, -1311 was not broken: it was waiting for `/tick unfreeze`.

## Remote launch without a freeze: works

T1 silo at 5000, -61, 5000 in an unloaded chunk (`/airdefence tickets`: `loaded false, 0 ticket(s)`), launch from the
console. `getBlockState`/`getBlockEntity` in the command loads the chunk synchronously (`unknown` ticket, level 33),
`holdSilo` adds `ender_pearl` level 30 (radius 3). `DistanceManager#inBlockTickingRange` reads the simulation
tracker, which takes simulating tickets, and 30 <= 32, so the block entity ticks. Result: `loaded true, block-ticking
true`, missile launched 2 s after the command, ARRIVED, silo idle after 11 s.

## The real bug: a sequence that stops ticking is stuck until someone walks up to it

Reproduced on 5.4.0-beta.2: `missile launch` and `stop` in the same tick (as a save-and-quit or a crash right after a
launch would), then start the server again:

```
status (twice, 10 s apart) -> loaded, opening, hatch 0.00
tickets                    -> chunk 312,312: loaded false, block-ticking false, 0 ticket(s)
launch                     -> cannot launch: busy (opening).
```

`SILO_HOLDS` is in memory only and the `ender_pearl` ticket does not persist, so after a restart nothing loads the
silo's chunk. Commands load it at level 33 (not block-ticking) for one tick, so every remote `launch`/`status` sees
`busy (opening)` forever. It only resumes when a player comes within simulation distance, and then it fires at the old
target. Nothing inside the tick can hold a phase forever (`ignite` with no aim or a lost AD target still goes to
LAUNCHING; LAUNCHING/CLOSING/COOLDOWN are counted), and a tick exception would crash the server, not hang the silo.

## Changes

`LaunchSiloBlockEntity`, `MissileTracker`, `MissileCommand`, `MISSILES.md`. No mixin, no access widener.

1. **Persistent hold.** The silo's ticket is `TicketType.PORTAL` (ENDER_PEARL's flags plus persist, 300 t timeout),
   so vanilla saves it in `chunk_tickets` and loads the chunk at startup. The block entity takes the in-memory hold
   back on its first tick whenever it is mid-sequence and not held (strike only; AD holds none by design).
2. **Stuck-sequence net.** `STALE_TICKS = 600` game ticks after the launch command (the longest sequence ends by about
   230). A busy silo past that was not ticking. Its own tick, `launch`, `load`, `unload`, `status` and mode changes
   reset it to idle, hatch shut, logged as `[silo] <pos> reset: ...`: before ignition the launch is aborted and the
   missile kept, after ignition the sequence is finished. It never fires at an old target. Game time stops while
   frozen, so a freeze never trips it.
3. **`/missile silo reset <pos>`**: the same on demand (op level 2, like every `/missile` command).
4. **Freeze messages.** `busy (...)` gets `; the game is frozen (/tick freeze), so the hatch won't move until /tick
   unfreeze`; an accepted launch in a frozen game adds a red line saying so; `status` appends `GAME FROZEN`.
5. **Range refusal.** `target too close: 2.1 blocks from the silo; a tier 4 missile needs at least 64 and at most
   10000 blocks horizontally`, plus a Tab hint when the target is within 8 blocks. `too far` has the same form.
   Ranges unchanged. `MISSILES.md` §4 has a "How to launch" note.
6. **"No silo there."** Every part of a 2x2 silo already resolved (all four top blocks and the casings were checked).
   The likely miss is a position one block above the top (`~ ~ ~` while standing on it). `launch`, `load`, `unload`,
   `status` and `reset` now take that block too, and the refusal names the position and the block found there.

## After

| Case | 5.4.0-beta.2 | this branch |
|---|---|---|
| launch while frozen | `hatch opening`, then `busy (opening)` | the same plus `The game is frozen (/tick freeze): the hatch won't move ... until /tick unfreeze`; `busy (opening); the game is frozen ...`; status `GAME FROZEN` |
| restart mid-OPENING, nobody near | stuck `opening`, hatch 0.00, chunk unloaded, `launch` refused | ticket restored with the chunk tickets: missile launched 1 s after `Done`, ARRIVED, silo idle, nobody touched it |
| silo saved stuck by beta.2, loaded by this build | stuck | first `status`: `was stuck: launch aborted, missile kept (stale after 4403 game ticks)`, then idle and launchable |
| target on the silo | `target is 2.6 blocks away, inside the tier 4 minimum range of 64` | `target too close: ... (the target is the silo itself or right next to it; Tab fills in ...)` |
| `status` one block above the top | `No silo there.` | resolves the silo |
| `status` two blocks above | `No silo there.` | `No silo at 5000, -59, 5000 (that block is minecraft:air). Give any block of the silo: ...` |

Also checked on this branch: frozen for 37 s of real time (over 600 ticks) with the silo in OPENING, no reset
(game time stood still); `/tick unfreeze` and the missile left 1 s later, ARRIVED. `/missile silo reset` during
OPENING (hatch 0.16): `launch aborted, missile kept`, no missile created.

## Far-away flights: missiles keep their own chunks loaded

No player online at all, no forceload (`forceload query`: none). Silos placed, then left until `/airdefence tickets`
showed their chunks and the target chunks `loaded false, 0 ticket(s)`. The target regions (`r.41.39`, `r.77.58`)
did not exist, so the ground there had never been generated.

| | silo | target | outcome | miss | stalls | flight | silo afterwards |
|---|---|---|---|---|---|---|---|
| T1 #7 | 20000, -61, 20000 | 21150.5 -60 20000.5 (1150 blocks) | ARRIVED | 0.00 | 0 | 601 t, flown 1173.2 | idle, hatch 0.00 |
| T4 #8 | 30000, -56, 30000 | 39500.5 -60 30000.5 (9499.5 blocks) | ARRIVED | 0.00 | 0 | 2417 t (123.0 s), flown 9566.3 | idle, hatch 0.00 |

During the T4 sequence the silo chunk held `portal 30` (295 ticks left) and the missile's own `ender_pearl 30`.
Telemetry every 200 ticks showed `stalls=0` throughout both flights.


## Open questions

- Which silo the player's first remote launch in the report used, and whether it was frozen then, is not visible in
  the screenshot. Both explanations (freeze; a save/quit or restart mid-sequence) are covered now.
- `PORTAL` keeps the silo's 7x7 chunks resident up to 300 ticks after the sequence ends (ENDER_PEARL: 40).
- Not checked on a real client; the messages are English only, like the rest of `/missile`.
