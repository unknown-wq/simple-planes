# PORT-CHEATSHEET — verified 26.2 fixes for the remaining compile errors

All mechanical yarn→Mojang renames (imports, class names, ~90 vanilla renames, method
renames) are ALREADY applied by scripts. What remains is per-file semantic work. This
sheet lists the VERIFIED fix for every recurring remaining error. Ground truth:
`/opt/mc-src/` (grep it) and `TntLib/src/main/java/luckytntlib/` (mirror it). Never invent.

## Environment
- Do NOT run gradle (the orchestrator compiles centrally). Just edit files.
- Do NOT `git commit` (orchestrator commits). Just save edits.
- Reference: `grep -rn <symbol> /opt/mc-src/` and the ported lib under `TntLib/`.

## Recurring errors → fix

1. **`Level cannot be converted to ServerLevel`** — `IExplosiveEntity#getLevel()` returns
   `Level`. Methods needing server (`sendParticles`, `EntityType.create`, structure
   `place`, `wasExploded`, `hurtServer`, worldgen) need a `ServerLevel`. Cast when you
   know it's server-side: `(ServerLevel) entity.getLevel()`; or guard:
   `if (entity.getLevel() instanceof ServerLevel level) { ... }`. TNT effects' `serverExplosion`
   runs server-side, so a cast is safe there.

2. **`no suitable method found for create(Level)`** — `EntityType#create` is now
   `create(Level, EntitySpawnReason)`. Use `type.create(level, EntitySpawnReason.MOB_SUMMONED)`.
   Add `import net.minecraft.world.entity.EntitySpawnReason;`. (Values incl. MOB_SUMMONED,
   TRIGGERED, COMMAND.)

3. **`no suitable method found for setBlock(BlockPos,BlockState)`** (2-arg) — the rename
   turned `setBlockState`→`setBlock`. The 2-arg form is now `setBlockAndUpdate(pos, state)`.
   The 3-arg `setBlock(pos, state, flags)` is correct as-is.

4. **`addParticle(..., boolean, ...)` no suitable method** — yarn's 1-boolean overload is
   gone. Mojang: `addParticle(ParticleOptions, double x,y,z, double dx,dy,dz)` (drop the
   boolean), or the two-boolean `addParticle(options, overrideLimiter, alwaysShow, x,y,z, dx,dy,dz)`.
   Simplest: delete the single boolean arg. Server-side particles → `((ServerLevel)level).sendParticles(...)`.

5. **`Optional<Integer/Double/String> cannot be converted to …`** — codec/NBT reads return
   Optional. Use the `*Or` variants on `ValueInput`: `input.getIntOr(name, def)`,
   `input.getShortOr(name, def)`, `input.getDoubleOr`, `input.getStringOr`; or `.orElse(def)`.
   Entity NBT is codec-based: `readAdditionalSaveData(ValueInput)` /
   `addAdditionalSaveData(ValueOutput)` with `output.putInt(name,v)` / `output.putShort` etc.
   Mirror `luckytntlib` entities (e.g. `PrimedLTNT`) for the exact shape.

6. **`method does not override or implement a method from a supertype`** — a vanilla/lib
   signature changed. Check the real one:
   - Items: `use()` → `InteractionResult use(Level, Player, InteractionHand)`;
     `useOnBlock`→`useOn(UseOnContext)`; tooltip →
     `appendHoverText(ItemStack, Item.TooltipContext, TooltipDisplay, Consumer<Component>, TooltipFlag)`.
   - Entity data: `initDataTracker`→`defineSynchedData(SynchedEntityData.Builder)`.
   - Blocks: `onDestroyedByExplosion`→`wasExploded(ServerLevel, BlockPos, Explosion)` (now
     needs ServerLevel). Confirm each against `/opt/mc-src`.
   - PrimedTNTEffect overrides (getBlock/getBlockState/getItem/serverExplosion/explosionTick/
     spawnParticles/…): match `TntLib/.../PrimedTNTEffect.java` exactly.

7. **`bad operand types for binary operator`** — usually a method that used to return a
   primitive now returns Optional/boxed, or a `getX()` type changed. Unbox / `.orElse()` /
   fix the type. Look at the specific line.

8. **residual `cannot find symbol`** — a rename the scripts didn't cover. `grep -rn` the
   symbol in `/opt/mc-src/` for the new name and fix the call. Common leftovers:
   `.getStepX/Y/Z` on Direction, `Blocks.` constants, `SoundEvents.` renamed constants
   (many lost the `ENTITY_`/`BLOCK_` prefix — verify), `Mth.` for math helpers.

## §9 — when a file resists (worldgen/structure especially)
Worldgen (`ConfiguredFeature`, `StructureStart`, `StructureTemplate`,
`*ConfiguredFeatures`, `ChunkGenerator`, `world.gen.*`) changed massively. If a
structure/feature spawn resists ~2 honest attempts: comment out the registration and stub
the broken body (keep original in a `/* ... */` block with
`// TODO(port-26.2): DISABLED — <reason>`), so the class compiles. Log EVERY cut in
`PORT-STATUS.md` under "Disabled content" (file, what, why). Build-green beats feature-complete.
