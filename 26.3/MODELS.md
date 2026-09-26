# Making render models for Simple Planes

This guide covers how to author a new aircraft render model for the 26.2 port and check it without
launching the game. It is based on the fighter jet (`FighterModel`, `FighterMetalModel`,
`FighterExhaustModel`). That model is authored and compiles but is not yet in the game, so use it as a
worked example of the method. [`FIGHTER-MODEL.md`](FIGHTER-MODEL.md) is its hand-off contract.

---

## 1. Conventions

### Units and axes

- **1 unit = 1 pixel = 1/16 block.** Every number in `addBox`, `PartPose` and `texOffs` is in pixels.
- **Model space is Y-down** (the usual `ModelPart` convention), and **the nose points to −Z**. +X is the
  aircraft's left side.
- **The root part sits at `PartPose.offset(0, 24, 0)`.** Every existing body and metal model does this
  (`Plane`, `Parts`, `bb_main`). Under that root, put the **ground contact (wheels or skis) at local
  y = 0**. The airframe then goes up from there: y is negative.
- Children use `PartPose.offset` or `PartPose.offsetAndRotation(x, y, z, xRot, yRot, zRot)`. Rotations are
  in radians and applied Z, then Y, then X, about the child's offset (its pivot). Cubes inside a part are
  positioned relative to that pivot. `ModelPart.xScale`/`yScale`/`zScale` also scale about the pivot, so
  put the pivot where you want something to grow from; the fighter's flame grows from the nozzle exit.

### The three layers

`PlaneRenderer` draws three models for every aircraft, all with the same pose:

| Layer | Texture | `LayerDefinition.create(mesh, W, H)` |
|---|---|---|
| material (the wooden body) | `state.materialTexture`, the **block texture** of the plane's material, e.g. `minecraft:textures/block/oak_planks.png` | **16, 16** |
| metal | the aircraft's own metal PNG | the PNG's real size (128 for plane, large plane, fighter; 256 helicopter; 512 cargo) |
| propeller / animated part | the propeller PNG, passed separately to the renderer | its real size |

- **The material layer tiles.** With a declared size of 16x16, `texOffs` values above 16 wrap around, so
  every face shows planks at 1 texel per pixel. Which offset you pick only changes which part of the
  plank pattern shows. Use the same offset for a left/right pair so they match.
- The third layer is whatever moves: the propeller (`IronPropeller.zRot = state.propellerRotation`) or the
  fighter's nozzle and flame. It is a model of its own with a texture of its own, which may be the same
  file as the metal layer's.
- All three layers use the default `EntityModel(ModelPart)` render type. **In 26.2 that is
  `RenderTypes::entityCutout`**, which is alpha-cutout and **culls back faces**. A model can choose
  another type with the `EntityModel(ModelPart, Function<Identifier, RenderType>)` constructor; none of
  the existing ones does.

### Box UVs (`texOffs`)

`texOffs(u, v).addBox(x, y, z, w, h, d, deformation)` lays the box's six faces out as a net that
starts at (u, v) and covers `(2d + 2w) × (d + h)` texels. The table gives each face's rectangle, with face
names in entity/world terms. This was checked against the baked `ModelPart` polygons:

| Face | Rectangle (x, y, width, height) |
|---|---|
| top (up in world) | `(u+d, v, w, d)`: u runs with +x, v = 0 at the **rear** edge |
| bottom | `(u+d+w, v, w, d)` |
| −X side | `(u, v+d, d, h)` |
| front (faces the nose) | `(u+d, v+d, w, h)` |
| +X side | `(u+d+w, v+d, d, h)` |
| rear (faces the tail) | `(u+2d+w, v+d, w, h)` |

- `.mirror()…mirror(false)` flips the UVs horizontally, so the ±X face images swap. Blockbench exports
  use it for the left copy of a symmetric part. Either use a symmetric texture there or allow for the swap.
- `new CubeDeformation(f)` grows (f > 0) or shrinks (f < 0) the box by f on every side **without** changing
  its UVs. The existing models use values like 0.001 to 0.02 to keep overlapping faces apart
  (see gotchas, section 5).

### From model space to entity space

`PlaneRenderer.submit()` applies these transforms in this order:

```
translate(0, 0.375, 0) · scale(-1, -1, 1) · rotY(180°) · plane quaternion · per-type translate (tx, ty, tz) · translate(0, -1.1, 0)
```

With the plane level, an absolute model pixel `p` (after the parent offsets) ends up at:

```
entity = ( p.x/16 ,  1.475 − ty − p.y/16 ,  −tz − p.z/16 )      (blocks, Y up, +Z = nose)
```

- Two negative scales make a positive determinant, so faces keep their winding and back-face culling still
  works.
- The plane's quaternion rotates about **entity (0, 0.375, 0)**, whatever the model or the translate is.
- The per-type translates in use today are:
  - plane: `(0, −0.5, −0.5)`;
  - large plane: `(0, −0.3, −1)`;
  - cargo plane: `(0, −0.8, −1)`;
  - anything else: `(0, 0, 0.9)`, the helicopter branch.
- Choose the new type's translate so the ground contact lands on entity y = 0 and the airframe is
  centred on the origin along its length. For a model whose root is at y = 24 with the ground at local
  y = 0, that means `ty = −0.025`. `tz` is `(local z of the centre of the length) / 16`. For the fighter
  that gives `(0, −0.025, 0.25)`, which simplifies the mapping to `entity = (x/16, (24 − y)/16, −(4 + z)/16)`.

---

## 2. Authoring

- **Existing models to read first**, all in `client/render/models/`:
  - `PlaneModel`, `PlaneMetalModel`, `PropellerModel`: the smallest complete set. 41 + 15 + 4 cubes, with
    a hollow cockpit, rotated wings and an animated propeller.
  - `HelicopterPropellerModel`: a rotor on a pivot.
  - `LargePlaneModel` and `CargoPlaneModel`: bigger airframes. Their metal layers are 128 and 512
    textures.
  - The fighter models: hand-written, hollow cockpit, stepped canopy, and an animation hook
    (`applyThrottle`) that needs no render-state fields.
- **Blockbench.** The existing models are Blockbench "Modded Entity" exports, recognisable by the
  `cube_rN` children, `bb_main` and the float noise in positions. Such an export pastes straight in:
  1. keep the `createBodyLayer()` body;
  2. change the class to `extends EntityModel<PlaneRenderState>`;
  3. call `super(root)` and fetch the top-level children in the constructor;
  4. keep `setupAnim(PlaneRenderState)`.

  Blockbench is the practical choice for organic shapes and when you want to paint UVs by hand.
- **By hand.** The fighter was typed in directly, which suits blocky, symmetric shapes:
  - stepped staircases for sweep (wings, fins);
  - a few rotated children only where a cant is needed (`zRot ±0.2618` on the fins);
  - integer sizes, so the texture generator can paint the nets texel-exact.

  Keep the names of `PartDefinition`s and `addOrReplaceChild` in Blockbench style. They are what
  `getChild` and the animation code look up.
- **Cube count.** Aim for about what the starter plane has: roughly 60 cubes over all three layers
  (the fighter has 56). Staircases cost cubes, so use 3 to 5 steps. Hollow cockpits cost 4 to 5 cubes but
  are needed for a rider to sit inside.
- **Symmetry.** Build the +X side first, then copy it with negated x and `.mirror()`.
- **Riders.** A seated player is drawn at 15/16 scale:
  - hips about 11.25 px above the feet point;
  - head top about 30 px above the feet point;
  - eye 1.62 blocks (≈ 25.9 px) above the feet point;
  - about ±7.5 px wide at the shoulders.

  Size the cockpit and canopy against those numbers, then record the seat position in the contract
  (section 6).

---

## 3. Textures

- **Material layer:** nothing to make. It is the block texture, and the renderer picks it from the
  material.
- **Reuse an existing metal texture** (`plane_upgrades/plane_metal.png` and its siblings) when the
  metal parts are plain panels, struts and skids: point `texOffs` at its dark-metal regions. Be aware
  of two limits:
  - these PNGs are **RGB with no alpha channel**, so nothing on them can be cut out;
  - they have no glass, glow or rubber areas.
- **Make a new texture** when the model needs any of the following:
  - transparency;
  - materials the existing sheets lack (glass, exhaust glow, tyres, painted stores);
  - so many metal cubes that sharing regions would look repetitive.

  Put it in `assets/simpleplanes/textures/plane_upgrades/<name>.png`. It must be original work, and it
  must not copy other mods' textures.

### How the fighter texture was generated (`gen.py`)

`fighter_metal.png` is produced procedurally by a Python + Pillow script,
[`tools/model-preview/gen/gen.py`](tools/model-preview/gen/gen.py). It works like this:

1. A table `CUBES = {name: (w, h, d, material)}` lists every metal and exhaust cube that needs a UV net.
   Cubes that share a name share one net: both intakes use `intake`, both missiles use `missile`.
2. It packs the nets into 128x128 **first-fit, largest first, with a 1-texel gutter**. An earlier shelf
   packer overflowed the atlas, which is why the packing is first-fit.
3. It paints each net face by face (using the table in section 1), per material:
   - `metal`, `strut`, `tire`, `missile`, `intake`, `dash`, `nozzle`, `turbine`, `flame`, `core`;
   - `glass`, which gets a dark windscreen bow on the front face and cut-out bottoms;
   - `COVERED`, a list of top-face areas to cut out under the next canopy tier.

   The noise is seeded, so the output is reproducible.
4. It writes the PNG **directly into the repo resources**.
5. It fills in the Java. `gen/FighterMetalModel.java.tmpl` and `gen/FighterExhaustModel.java.tmpl` contain
   `texOffs(@name@)` placeholders. The script replaces them with the packed `u, v` and writes
   **`FighterMetalModel.java` and `FighterExhaustModel.java` into the repo**, overwriting them.

> **Gotcha: `gen.py` owns those two Java files.** Hand edits to `FighterMetalModel.java` or
> `FighterExhaustModel.java` in the repo are lost the next time the script runs (and `iter.sh` runs it on
> every iteration). Edit the `.tmpl` files instead. To add a cube, add it to the template with a new
> `@name@` **and** to `CUBES` with its size and material. `FighterModel.java`, the material layer, is not
> generated and can be edited freely.

For a new aircraft, copy `gen.py` and change these four things:
- `CUBES`;
- the material painters, if the new model needs new materials;
- `COVERED`;
- `OUT_PNG` and the template and class names at the bottom.

---

## 4. Previewing without the game

Geometry comes from the **real compiled model code**; nothing parses Java text. The pipeline bakes the
real `LayerDefinition` with the real `PoseStack` transforms from `PlaneRenderer.submit()`, dumps the
quads, and renders them with three.js in headless Chromium.

The tools are in [`tools/model-preview/`](tools/model-preview/). Its
[`README.md`](tools/model-preview/README.md) lists every file. All paths below are relative to that
directory.

> **Status: committed as used, not yet a general tool.**
> - **The paths are still hardcoded.**
>   - `dump.sh` and `iter.sh` set `F=` to the session scratchpad the fighter was built in, and every
>     step runs relative to `$F`. Point `F=` at `tools/model-preview` (or at a working copy of it)
>     before running anything.
>   - `gen/gen.py` writes into `/home/user/simple-planes/26.2/src/main` (`REPO`).
>   - `dumper/cp.txt` holds absolute jar paths from the machine the fighter was built on.
> - **The fighter's class names are baked in** to `dump.sh`, `gen/gen.py` and the templates.
> - **Some inputs are not committed** and have to be recreated:
>   - `node_modules/` (`npm install`);
>   - `tex/`: Mojang block textures, which must never be committed, plus copies of mod textures;
>   - the dump JSONs the scene lists read;
>   - `out/`.
>
>   Keep all of these out of commits.

### Step by step

1. **Compile** with the lock-holding wrapper only:

   ```sh
   /home/user/minecolonies-fabric/tools/mc-build.sh /home/user/simple-planes/26.2 compileJava
   ```

   - Never run Gradle directly or in parallel. The script takes a global flock on `/tmp/mc-build.lock`
     and picks Java 25 and Gradle 9.6.1. It lives in the minecolonies-fabric checkout, not in this repo.
     (`TESTING.md` shows a direct `gradle` call; don't use it while other builds may be running.) Output
     goes to `26.2/build/classes/java/main`.
   - For fast iteration, `dump.sh` instead runs plain `javac` (not Gradle) on just the model files into
     `$F/classes/`. Make the final check with `mc-build.sh`, and take the final renders from
     `build/classes`.

2. **Dumper classpath: `dumper/cp.txt`.** This is the only record of how it was built; the shell commands
   were not saved. Regenerate it for your machine. It contains:
   - `26.2/build/classes/java/main`;
   - `~/.gradle/caches/fabric-loom/26.2/minecraft-merged.jar`, the unobfuscated, Mojang-named game;
   - every non-sources jar under `~/.gradle/caches/modules-2/files-2.1/` for: `org.joml` (1.10.9 only),
     `it.unimi.dsi`, `com.google.guava`, `com.mojang`, `org.slf4j`, `org.jspecify`,
     `org.apache.logging.log4j`, `net.fabricmc/fabric-loader`, `com.google.code.gson`, `com.ibm.icu`,
     `org.apache.commons`, `commons-io` and `io.netty`.

   Gson and friends are needed because `CubeListBuilder`'s static initialiser touches `Direction`, which
   touches `ExtraCodecs`. No `Bootstrap` or game start-up is needed.

3. **Dump the geometry** with `dumper/ModelDump.java`, compiled with
   `/usr/lib/jvm/java-25-openjdk-amd64/bin/javac` into `dumper/`:

   ```
   java -cp "dumper:$(cat dumper/cp.txt)" ModelDump out.json tx ty tz  name=fully.qualified.Model=textureKey[=throttle] ...
   ```

   - For each layer it calls `createBodyLayer()` and `bakeRoot()`.
   - It pushes the transforms from `submit()`, with `(tx, ty, tz)` as the per-type translate and the
     plane level.
   - It walks the parts the way `ModelPart.render` does: it honours `visible` and `skipDraw`, reads the
     private `children`/`cubes` fields by reflection, and calls `translateAndRotate`.
   - It writes entity-space quads (position, normal, baked UV) and a bbox per layer, and prints cube
     counts and bboxes.
   - With the optional 4th field, it builds the real model around the baked root and calls its
     `applyThrottle(float)`. That method name is fighter-specific.
   - `textureKey` names a PNG in `tex/`. Extract block textures with `unzip` from Loom's
     `minecraft-client.jar` or `minecraft-merged.jar` (`assets/minecraft/textures/block/`), and copy mod
     textures from `src/main/resources/assets/simpleplanes/textures/`.
   - `dump.sh` wraps all of this for the fighter. It takes the environment variables `TX`, `TY`, `TZ` and
     `THROTTLE` and always writes `fighter.json`.

4. **Render.** `viewer.html` holds the three.js scene and `render.mjs` is the Playwright driver.

   - Run `npm ci` in the directory. `package-lock.json` locks the versions used for the fighter, three
     0.170.0 and playwright 1.56.1; `package.json` only asks for `^` ranges.
     Chromium comes from `/opt/pw-browsers`; do not run `playwright install`. Pillow (`pip install
     pillow`) is needed for `gen.py` and the sheets.
   - `render.mjs` serves **its own directory** over a local HTTP server. Run it as
     `node render.mjs scenes/<list>.json` from `tools/model-preview`, so that the scene's `out` paths
     land under `out/`.
   - A scene list is a list of scenes, each with:
     - `models`: a list of `{json, offset?, skip?, tex?}`. `skip` names layers to leave out; `tex` maps
       layer names to texture keys;
     - `out`, `w`, `h`: output file and size;
     - `dir`: camera direction from the target;
     - `ortho`, `fit`, `margin`, `up`, `fov`: projection and framing;
     - `ground`: whether to draw the ground plane;
     - `target`, `dist`: close-ups;
     - `eye`, `look`: a first-person camera.
   - The viewer matches the game where it matters:
     - nearest filtering;
     - `RepeatWrapping`, so 16x16 materials tile;
     - `flipY = false`, so v = 0 is the image top;
     - `alphaTest 0.1`, for cutout;
     - **`FrontSide`**, back-face culled like `entityCutout`.
   - It adds ambient light, a shadow-casting sun, a fill light and a 1-block grid at y = 0.

5. **Views and sheets.**
   - `views.py <outdir> <prefix> <model.json>` writes the standard scenes: 3/4 front, 3/4 rear, side,
     top, front (ortho) and 3/4 from below.
   - `iter.sh <round>` does one full round:
     1. runs `gen/gen.py`;
     2. runs `dump.sh`;
     3. runs `views.py`;
     4. renders;
     5. makes an unlabelled 3x2 `out/<round>/sheet.png`.
   - The hand-written scene lists in `scenes/` need these dump files in the tool root:
     - `scenes_plane.json` reads `plane.json`, the starter plane at translate `0 -0.5 -0.5`;
     - `scenes_close.json` and `scenes_fp.json` read `fighter_idle.json` and `fighter_full.json`;
     - `scenes_final.json` reads `final_t0.json` and `final_t1.json`;
     - `scenes_scale.json` reads `plane.json` and `fighter_idle.json`, offset side by side.

     All of those files were made by hand, by running `ModelDump` directly or copying `fighter.json`.
     No script produces them under those names.
   - **Also done by hand, with no saved script:**
     - the labelled contact sheet, whose output is committed as `docs/fighter/fighter-sheet.png`;
     - the starter-plane validation sheets;
     - generating `cp.txt`;
     - extracting the textures.

     The sheets were ad-hoc Pillow snippets typed into the shell.
   - `gen/gen_cutout.py` is the abandoned see-through-canopy variant (see section 5). It is kept for
     reference only and exits immediately when run, because its body would rewrite the texture and the
     two Java files.
   - `tools/model-preview/.gitignore` keeps `node_modules/`, `tex/` (Mojang textures), `out/` and the
     dump JSONs out of commits.

Look at every PNG yourself and iterate. The fighter took 8 rounds.

---

## 5. Validation and gotchas

- **Validate the pipeline on a known model first.** Dump and render `PlaneModel`, `PlaneMetalModel` and
  `PropellerModel` with translate `0 -0.5 -0.5`. You should see a coherent biplane: propeller at the nose,
  skis resting on y ≈ 0 (the metal bbox starts at −0.055), mirrored parts in place. Only trust the
  pipeline for a new model after that works. Repeat the check whenever you change the viewer.
- **Back-face culling is real.** Render with `FrontSide`: `entityCutout` culls. With the plane model,
  culling on and off gave identical images, which also confirms the winding. Culling changes design
  decisions, as the canopy points below show.
- **`ModelPart.visit` ignores `visible`.** Walk the tree the way `render` does, or hidden parts (such as
  the idle flame) will show up.
- **A see-through canopy reads as a cage.**
  - With cut-out glass, every box edge becomes a visible frame, and in side view the canopy disappears.
  - The fighter uses **opaque tinted glass**, with a dark frame only on the windscreen bows and the sill.
  - Culling keeps it from blocking the pilot, **provided the eye is inside a canopy box**: from inside,
    every face is a back face.
  - A stepped canopy (4 tiers, 1–2 px steps) reads rounder than 2–3 big boxes.
- **Seat, eye and canopy tiers.**
  - Put the feet point so that:
    - the legs rest on the cockpit floor;
    - the head top clears the canopy top;
    - the eye (feet + 1.62) lies **inside** a canopy tier.
  - If the eye sits above a tier, that tier's top face points at it and blocks the view down. The fighter
    therefore cuts out the covered part of each lower tier's top face.
  - Check this with a first-person render (`eye`/`look`), both at the planned eye height and a little
    above it.
- **Coplanar faces.**
  - Two faces in the same plane that overlap and face the same way z-fight. Either make the boxes touch
    (adjacent) instead of overlapping, or separate them with a small `CubeDeformation` as the existing
    models do.
  - Faces back to back, such as a canopy bottom resting on the fuselage top, never fight with culling on.
  - Give glass bottoms alpha 0 anyway, so nothing shows through.
- **Animation must run after `super.setupAnim(state)`,** which resets the pose. Keep animation inputs in a
  public method, like `applyThrottle`, until `PlaneRenderState` carries the data. That way it can be
  previewed (the dumper calls it) without touching the render state.
- **Shared renderer paths.** `UpgradesModels` treats any entity type it doesn't recognise as a
  **helicopter** (`modelFor`, `textureFor`, `submitSeats`), so upgrades on a new aircraft appear as
  helicopter parts in helicopter positions.
- **Limits of the preview.**
  - The lighting is not Minecraft's: there is no per-face shade table and no light levels.
  - Emissive effects are not modelled; the fighter's flame is lit, so it will be dark at night in game.
  - A preview is not a game test.

---

## 6. Integration checklist

None of this is done for the fighter; see [`FIGHTER-MODEL.md`](FIGHTER-MODEL.md) for the values.

- [ ] **Render/physics contract.** Write a `<NAME>-MODEL.md` like `FIGHTER-MODEL.md`, covering:
      - model origin and pivots;
      - per-type translate;
      - dimensions and the suggested hitbox (`sized(w, h)`);
      - seat positions for `positionRider`, with eye checks;
      - animated parts and their inputs;
      - textures and render types.
- [ ] **Model layers.** In `client/PlanesModelLayers`, add three `ModelLayerLocation`s (`main`, `metal`,
      `propeller`) and `ModelLayerRegistry.registerModelLayer(..., XxxModel::createBodyLayer)` in
      `registerLayers()`.
- [ ] **Renderer.** In `registerRenderers()`, add
      `new PlaneRenderer<>(context, body, metal, propeller, shadowRadius, metalTexture, propellerTexture)`.
- [ ] **Per-type translate.** Add a branch in `PlaneRenderer.submit()` before the helicopter `else`.
- [ ] **Render state.** Add any new `PlaneRenderState` fields (e.g. a `throttle` in 0..1), fill them in
      `PlaneRenderer.extractRenderState`, and read them in `setupAnim` in place of the placeholder
      constant.
- [ ] **Entity.** Add a subclass of `PlaneEntity`, registered in `setup/SimplePlanesEntities` with
      `register(name, factory, width, height)`. It needs:
      - `positionRider`, with the seat numbers from the contract;
      - `getPassengersRidingOffset`;
      - the physics.
- [ ] **Item.**
      - `PlaneItem` in `setup/SimplePlanesItems`, added to `planeItems`;
      - `assets/simpleplanes/items/<name>.json` and `models/item/<name>.json`;
      - `textures/item/<name>.png`;
      - a recipe in `data/simpleplanes/recipe/`;
      - lang entries.
- [ ] **Upgrades.** Give the new type its own branch in `UpgradesModels` (`modelFor`, `textureFor`,
      `submitSeats`), with new upgrade models or none.
- [ ] **Final check.** Run `mc-build.sh … build`, then test in game. Confirm what the preview cannot
      show: lighting, the first-person view with a real player, rider placement and the hit wobble.
