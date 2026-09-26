# Model preview tooling (as used for the fighter)

These are the scripts that rendered the fighter jet outside the game (`docs/fighter/`). They are
committed **as they were used**, so the work is not lost. They have not been cleaned up into a
general tool yet.

## Before running them

- **Paths are hardcoded.** `dump.sh`, `iter.sh` and `gen/gen.py` point at the session scratchpad
  they were written in (`/tmp/claude-0/.../scratchpad/fighter`) and at
  `/home/user/simple-planes/26.2`. Edit `F=` / `REPO=` before use.
- **`dumper/cp.txt`** is the dumper's classpath from that machine. It lists
  `26.2/build/classes/java/main`, Loom's `minecraft-merged.jar` and the library jars from the Gradle
  cache. Regenerate it for your machine.
- **Textures are not included.** `viewer.html` loads `tex/<name>.png`:
  - Block textures such as `oak_planks` are Mojang's. Extract them from `minecraft-merged.jar`
    (`assets/minecraft/textures/block/`); they must not be committed.
  - Mod textures come from `src/main/resources/assets/simpleplanes/textures/`.
- **Node dependencies** come from `npm install` here (three, playwright). Use the Chromium that is
  already installed rather than running `playwright install`.
- **Build only through `tools/mc-build.sh`**, never run Gradle directly or in parallel.

## Files

| File | What it does |
|---|---|
| `dumper/ModelDump.java` | Calls `createBodyLayer()`, bakes the real `ModelPart`s, applies `PlaneRenderer.submit()`'s transforms and writes entity-space quads with UVs to JSON. |
| `dump.sh` | javac's the fighter models and runs the dumper (`THROTTLE`, `TX/TY/TZ` env vars). |
| `views.py` | Writes the standard camera set (3/4 front, 3/4 rear, side, top, front, below) as a scene list. |
| `render.mjs` + `viewer.html` | Render a scene list with three.js in headless Chromium via Playwright, and write PNGs. |
| `iter.sh <round>` | One iteration: regenerate the texture, dump, render all views and an unlabelled sheet into `out/<round>/`. |
| `gen/gen.py` | Generates `fighter_metal.png` and **rewrites the `texOffs` in `FighterMetalModel.java` / `FighterExhaustModel.java` from the `.tmpl` files**, so edit the templates, not the Java. |
| `gen/gen_cutout.py` | An abandoned variant with see-through (cut-out) canopy glass. It looked like a wire cage and was replaced by `gen.py`. |
| `scenes/*.json` | The scene lists behind the final renders, scale comparison, cockpit view and close-ups. |

The labelled contact sheet in `docs/fighter/` was put together by hand; there is no script for it.
