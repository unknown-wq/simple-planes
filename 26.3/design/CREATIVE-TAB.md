# Creative tab cleanup and material textures

Branch `claude/creative-tab-cleanup-26.3`, from `26.3-beta` at `a8d4872`.

## 1. Creative tab

Before this change, `displayItems` went through every block in `simpleplanes:plane_materials`, which has
70 members in 26.3: 13 planks, 45 logs/woods/stems/hyphae and 12 listed blocks. For each block it added all
nine aircraft. The count was 19 parts/tools + 70 x 9 aircraft + 5 silo/missile entries = **654 entries**.

After this change the count is **309 entries**. The last row holds 3 stacks, which matches 309 = 34 x 9 + 3.

| Group | Entries | Notes |
|---|---|---|
| workbench, parts, engines, wrench | 13 | same items as before, with the workbench moved first |
| port aircraft, one each | 5 | `SINGLE_ENTRY_AIRCRAFT` |
| tools | 6 | parachute, strike tool, route wand, runway tool, helipad tool, crane remote |
| launch silo, missiles T1-T4 | 5 | `Missiles` now uses `insertAfter(CRANE_REMOTE)` instead of appending at the end |
| original aircraft x material | 4 x 70 = 280 | `PER_MATERIAL_AIRCRAFT`, grouped per material as before |

The tools come before the 280 original aircraft, so the port's aircraft and the tools all fit on the first
page. Otherwise they would have been at the bottom of a 35-row list.

Default materials for the single entries, written into the stack's `entity_tag`. The tooltip shows them.

| Item | Material | Why |
|---|---|---|
| fighter | `iron_block` | grey jet; the icon tint is 0x6E7B8B, and FIGHTER-MODEL.md shows an iron sheet |
| airliner | `iron_block` | the metal skin with airline logos (`airliner_metal_skin`) |
| airship | `oak_planks` | wooden gondola; also the entity default |
| mini_helicopter | `white_concrete` | the medical livery (`mini_heli_medical`); the icon tint is pale 0xD9E4EC |
| quadcopter | `oak_planks` | the entity default; the icon tint was set to the oak colour on purpose |

To add a type such as `regional_airliner`, add one line to `SINGLE_ENTRY_AIRCRAFT` in
`setup/SimplePlanesItems.java`:
`new CreativeEntry(REGIONAL_AIRLINER_ITEM, Blocks.IRON_BLOCK),`.

## 2. Purple and black material textures

`PlaneRenderer.getMaterialTexture` built the path `<ns>:textures/block/<block path>.png`. I checked every
member of the tag against the 26.3 client jar (`minecraft-client.jar`, `assets/minecraft/textures/block`).
**27 of the 70 members have no texture file under that name**, so they rendered with the missing
texture:

- `*_wood` and `stripped_*_wood` for oak, spruce, birch, jungle, acacia, dark oak, pale oak, mangrove, cherry
  and poplar (20 blocks). The texture is `*_log` or `stripped_*_log`.
- `crimson_hyphae`, `warped_hyphae`, `stripped_crimson_hyphae`, `stripped_warped_hyphae`. The texture is
  `*_stem`.
- `waxed_copper_block`. The texture is `copper_block`.
- `quartz_block`. The texture is `quartz_block_side`, via `cube_column`.
- `smooth_quartz`. The texture is `quartz_block_bottom`.

A second defect had not been reported. `crimson_stem.png` and `warped_stem.png` are animated 16x80 strips,
so `crimson_stem`, `warped_stem` and both hyphae (4 members) drew all five frames squashed into one tile.

### How textures are resolved now (`client/render/MaterialTextures`)

The material models use `LayerDefinition(..., 16, 16)` and their UVs run far past 16, so the texture is
tiled by REPEAT addressing. An atlas sprite with `UvMapping` would bleed into neighbouring sprites. The
layer therefore still needs a standalone PNG, which is resolved like this:

1. Take the default state's baked model from `ModelManager.getBlockStateModelSet()`. Use the sprite of the
   first quad on the south face; this is what 1.21.1 did through the inventory model. If there is no such
   quad, use the model's particle sprite. The missing sprite and the missing model are ignored.
2. Map the sprite name `ns:block/x` to `ns:textures/block/x.png` and keep it if the client
   `ResourceManager` has that resource.
3. If the sprite is animated, cut the top frame (`SpriteContents.width/height`) out of the strip and
   register it as a `DynamicTexture` under `simpleplanes:material/<ns>/<path>`.
4. Otherwise try `textures/block/<block id>.png`, and fall back to `oak_planks`.

This works for any block, modded ones included, as long as the block has a baked model. Only public vanilla
and Fabric API are used, with no mixin and no access widener. The cache is keyed by block. A client reload
listener registered with Fabric `ResourceLoader`, ordered after `ResourceReloaderKeys.Client.MODELS`, clears
it and releases the frame textures. Before this change the cache was never cleared.
`PlaneRenderer.getMaterialTexture`, `clearTextureCache` and `FALLBACK_TEXTURE` still exist and delegate to
the new class.

### Items, tooltip, workbench

- Item icons do not depend on material. `items/*.json` use constant tints, so nothing is broken there.
  Every material variant of an item looks the same in the tab; only the tooltip tells them apart.
- `PlaneItem` tooltip: "Material - <block name>", taken from the block registry. It is correct for every
  block. `QuadcopterItem` shows no material.
- `PlaneWorkbenchContainer` writes only `material` into `entity_tag` and renders nothing that depends on
  material. Nothing needed fixing.

## 3. Verification

Commands:

```sh
/home/user/minecolonies-fabric/tools/mc-build.sh /home/user/sp-creative-tab/26.3 build --offline   # baseline, then after
26.3/tools/testserver/make-server.sh <scratch>/creative-tab/server 25717
MC_JVM_OPTS=-Xmx1536M ./start.sh
# dev client from build/classes (runClient.args as in sp-fighter-glass-client), Xvfb :74, llvmpipe
./cmd.sh 'summon simpleplanes:plane X -60 0 {material:"minecraft:<m>",Rotation:[180f,0f]}'    # and fighter at z=14
```

Materials placed in the world: `oak_wood`, `crimson_hyphae`, `quartz_block`, `smooth_quartz`,
`waxed_copper_block`, `iron_block`, `crimson_stem` and `oak_planks`. There is one plane and one fighter of
each. After the change, all of them render with the right block texture. `crimson_stem` and
`crimson_hyphae` show a single frame. A resource reload (F3+T) keeps them correct, and the client log shows
no errors from the mod.

Screenshots, in the scratchpad `creative-tab/` folder:

- `01-tab-before-top.png`, `02-tab-before-tooltip.png` (fighter in spruce planks), `03-tab-before-scrolled.png`
- `10-world-before-overview.png`, `11-world-before-oakwood-hyphae-quartz-smoothquartz.png`,
  `12-world-before-waxedcopper-iron-crimsonstem-oakplanks.png`
- `20-tab-after-top.png`, `21-tab-after-tooltip-fighter.png` (Block of Iron),
  `22-tab-after-tooltip-miniheli.png` (White Concrete), `23-tab-after-end.png` (last row of 3)
- `30-world-after-overview.png`, `31-world-after-oakwood-hyphae-quartz-smoothquartz.png`,
  `32-world-after-waxedcopper-iron-crimsonstem-oakplanks.png`, `33-world-after-reload-...png` (after F3+T)
- `34-world-after-single-entry-defaults.png`, `35-world-after-miniheli-quadcopter-defaults.png`,
  `36-world-after-quadcopter-default.png`

## 4. Open questions

- The tab order is parts, the port's aircraft, tools and missiles, then the original aircraft per
  material. The brief suggested "built-ins, then ours, then tools". I put the 280 per-material entries last
  so that the rest stays on page one. Changing this means reordering `displayItems`.
- Default material for the mini helicopter: medical white (`white_concrete`) or the standard oak livery.
  The icon is pale, so the medical livery was chosen.
- The original four aircraft still get 70 variants each, and their icons look identical. The tab could be
  cut further by limiting the variants to planks plus the listed blocks and leaving out the 45 logs. I left
  that alone because the owner asked for the per-material variants to stay as before.
- Seen while testing, not investigated: a quadcopter summoned at y=-60 on superflat once showed only a few
  dark bits above the grass. It then moved to (0, -60, 0) instead of staying where it was summoned. After a
  teleport it rendered normally at y=-59. The material path is not involved (oak_planks resolved before and
  after). The quadcopter's lift and crane rules belong to another branch, so this is left to that work.
