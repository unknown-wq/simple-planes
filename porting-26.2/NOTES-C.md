# NOTES-C — NeoForge 1.21.1 → Fabric 26.2, **client area** (renderers, models, screens, HUD, sounds, mixins)

Every entry below was checked against the decompiled 26.2 tree at `/opt/mc-src/` or against a
working ported mod on disk. Paths are given so you can re-read the source instead of trusting me.

Reference mods used: `/home/user/Fabric-LuckyTNTMod/` (26.2 renderers + HUD),
`/home/user/desolation/src/main/java/raltsmc/desolation/init/client/DesolationClient.java`
(26.2 `ClientModInitializer`).

---

## 0. The rename that touches every client file

| 1.21.1 | 26.2 | source |
|---|---|---|
| `net.minecraft.resources.ResourceLocation` | **`net.minecraft.resources.Identifier`** | `/opt/mc-src/net/minecraft/resources/Identifier.java` (there is **no** `ResourceLocation.java`) |

The factory methods survived: `Identifier.fromNamespaceAndPath(ns, path)`, `Identifier.parse`,
`Identifier.withDefaultNamespace`, `withPath(String|UnaryOperator)`, `withPrefix`, `withSuffix`,
`getNamespace()`, `getPath()`. So it is a pure type rename. `Identifier.STREAM_CODEC` exists.

This is **not** a yarn name — 26.x Mojang official mappings really call it `Identifier`.

---

## 1. Removed client classes and their replacements (the dead ends)

Everything in this table was searched for with `find /opt/mc-src -name 'X.java'` and does not exist.

| Gone in 26.2 | Use instead | Notes |
|---|---|---|
| `net.minecraft.client.renderer.MultiBufferSource` | `SubmitNodeCollector` (`/opt/mc-src/net/minecraft/client/renderer/SubmitNodeCollector.java`) | world rendering is submit-to-queue |
| `net.minecraft.client.gui.GuiGraphics` | `net.minecraft.client.gui.GuiGraphicsExtractor` | GUI is extract-then-render |
| `net.minecraft.client.renderer.RenderType` | `net.minecraft.client.renderer.rendertype.RenderType` + `…rendertype.RenderTypes` | package move + factory split |
| `RenderType.armorCutoutNoCull(rl)` | `RenderTypes.armorCutoutNoCull(Identifier)` | `RenderTypes.java:402` |
| `RenderType.itemEntityTranslucentCull(rl)` | `RenderTypes.entityTranslucentCullItemTarget(Identifier)` | `RenderTypes.java:454` |
| `net.minecraft.client.renderer.entity.ItemRenderer` (whole class) | — | `ItemRenderer.getArmorFoilBuffer(...)` has **no** replacement; enchant-glint on entity models is gone. Foil is now `ItemStackRenderState.FoilType`, item-only. |
| `net.minecraft.client.resources.model.ModelResourceLocation` | — | baked-model lookup by `blockid#inventory` is gone |
| `Tesselator`, `BufferUploader` | — | no immediate mode; `BufferBuilder`/`MeshData` still exist in `com.mojang.blaze3d.vertex` but there is no uploader |
| `RenderSystem.setShaderTexture/setShaderColor/runAsFancy` | — | `RenderSystem` still exists (`/opt/mc-src/com/mojang/blaze3d/systems/RenderSystem.java`) but not those |
| `Minecraft.getOverlay()` | — | not present in `Minecraft.java` |
| `Minecraft.screen` | `Minecraft.getInstance().gui.screen()` | `Gui.java:218` |
| `Minecraft.setScreen(...)` | `Minecraft.getInstance().gui.setScreen(...)` | `Gui.java:222` |
| `Gui.setNowPlaying(...)` | `Minecraft.getInstance().gui.hud.setNowPlaying(...)` | `Gui.java:72 (public final Hud hud)`, `Hud.java:1201` |
| `Gui.rightHeight` / `leftHeight` | — | the HUD stacking cursor is gone; place your own rows at fixed offsets |
| `EntityRenderDispatcher.setRenderShadow / overrideCameraOrientation` | `GuiGraphicsExtractor#entity(...)` | see §6 |
| `net.minecraft.client.model.ShulkerModel` | `net.minecraft.client.model.monster.shulker.ShulkerModel` | and it is now `EntityModel<ShulkerRenderState>` |
| `JukeboxSong.fromStack(RegistryAccess, ItemStack)` | `JukeboxSong.fromStack(ItemStack)` | `/opt/mc-src/net/minecraft/world/item/JukeboxSong.java:52` |
| `Registry.get(Identifier)` returning `T` | `getValue(Identifier)` → `@Nullable T`; `get(Identifier)` → `Optional<Holder.Reference<T>>` | `/opt/mc-src/net/minecraft/core/Registry.java:65,67,131,133` |

### NeoForge-only client APIs with **no** Fabric equivalent (cut them)

| NeoForge | Fabric 26.2 |
|---|---|
| `RenderLivingEvent.Pre/Post` | none — needs a bespoke `LivingEntityRenderer` mixin |
| `ViewportEvent.ComputeCameraAngles` | none — needs a `GameRenderer`/`Camera` mixin |
| `CalculateDetachedCameraDistanceEvent` | none |
| `RegisterColorHandlersEvent.Item` | **removed from vanilla too** (1.21.4). Item tints are item-model-JSON driven |
| `TextureAtlasStitchedEvent` | none needed if you dropped the colour cache |
| `IClientFluidTypeExtensions`, `FluidStack` | none in this port (contract C4 deleted the fluid capability) |
| `ModelData` / `BlockRenderDispatcher#renderSingleBlock(..., ModelData, ...)` | block models go through `BlockModelResolver` / `submitBlockModel(...)`, which needs level context an entity render state does not carry — practical answer: cut |
| `RegisterMenuScreensEvent` | `MenuScreens.register` (§5) |
| `RegisterKeyMappingsEvent` | `KeyMappingHelper.registerKeyMapping` (§4) |
| `RegisterGuiLayersEvent` | `HudElementRegistry` (§4) |
| `EntityRenderersEvent.RegisterRenderers / RegisterLayerDefinitions / AddLayers` | `EntityRendererRegistry` / `ModelLayerRegistry` (§4) |
| `PacketDistributor.sendToServer(payload)` | `ClientPlayNetworking.send(payload)` |

---

## 2. `EntityRenderer` — the exact 26.2 contract

`/opt/mc-src/net/minecraft/client/renderer/entity/EntityRenderer.java`

```java
public abstract class EntityRenderer<T extends Entity, S extends EntityRenderState> {
    protected EntityRenderer(EntityRendererProvider.Context context);

    public abstract S createRenderState();

    public void extractRenderState(T entity, S state, float partialTicks);   // copy entity -> state

    public void submit(S state, PoseStack poseStack,
                       SubmitNodeCollector submitNodeCollector,
                       CameraRenderState camera);                            // NO entity access

    protected float getShadowRadius(S state);
    public Vec3 getRenderOffset(S state);
}
```

* `getTextureLocation(T)` / `getTexture(...)` — **gone**. Textures are chosen at submit time.
* `render(entity, yaw, partialTick, PoseStack, MultiBufferSource, int)` — **gone**, replaced by `submit`.
* Always call `super.extractRenderState(...)` (fills `x/y/z`, `ageInTicks`, `lightCoords`, nametag, leash)
  and `super.submit(...)` at the end (leash + nametag).
* `CameraRenderState` lives in `net.minecraft.client.renderer.state.level.CameraRenderState`.

### `EntityRenderState` fields worth knowing
`/opt/mc-src/net/minecraft/client/renderer/entity/state/EntityRenderState.java`

```
EntityType<?> entityType;  double x,y,z;  float ageInTicks;          // = tickCount + partialTick
float boundingBoxWidth, boundingBoxHeight, eyeHeight;
boolean isInvisible, isDiscrete, displayFireAnimation;
int lightCoords = 15728880;   int outlineColor = 0;
List<ShadowPiece> shadowPieces;  @Nullable Component nameTag;
```
Subclass it and add whatever the models need (quaternion, animation angles, texture `Identifier`,
list of installed upgrades…). Everything a model's `setupAnim` reads must live on the state.

### Submitting a model
`/opt/mc-src/net/minecraft/client/renderer/OrderedSubmitNodeCollector.java`

```java
<S> void submitModel(Model<? super S> model, S state, PoseStack poseStack,
                     RenderType renderType, int lightCoords, int overlayCoords,
                     int tintedColor, @Nullable TextureAtlasSprite sprite,
                     int outlineColor, @Nullable ModelFeatureRenderer.CrumblingOverlay crumbling);

// convenience overloads (the two you actually use):
<S> void submitModel(Model<? super S>, S, PoseStack, RenderType,  int light, int overlay, int outlineColor, @Nullable CrumblingOverlay);
<S> void submitModel(Model<? super S>, S, PoseStack, Identifier,  int light, int overlay, int outlineColor, @Nullable CrumblingOverlay);
```

Typical call: `collector.submitModel(model, state, poseStack, model.renderType(texture),
state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor, null);`

Also available: `submitModelPart`, `submitBlockModel`, `submitItem`, `submitCustomGeometry`,
`submitFlame`, `submitLeash`, `submitNameTag`, `submitShadow`.

Vanilla template to copy: `/opt/mc-src/net/minecraft/client/renderer/entity/AbstractBoatRenderer.java`
(state extraction + `submitModel` + `submitTypeAdditions`). Mod template:
`Fabric-LuckyTNTMod/tntmod/src/main/java/luckytnt/client/renderer/BombRenderer.java`
(custom nested render-state class, exactly the shape you want).

---

## 3. `EntityModel` / `Model` — what changed

`/opt/mc-src/net/minecraft/client/model/Model.java`, `.../EntityModel.java`

```java
public abstract class Model<S> implements FabricModel<S> {
    public Model(ModelPart root, Function<Identifier, RenderType> renderType);
    public final RenderType renderType(Identifier texture);
    public final void renderToBuffer(PoseStack, VertexConsumer, int light, int overlay, int color); // FINAL
    public final ModelPart root();
    public void setupAnim(S state) { this.resetPose(); }
}
public abstract class EntityModel<T extends EntityRenderState> extends Model<T> {
    protected EntityModel(ModelPart root);                                       // RenderTypes::entityCutout
    protected EntityModel(ModelPart root, Function<Identifier, RenderType> rt);
}
```

Mechanical conversion of a Blockbench-exported 1.21.1 model:

| before | after |
|---|---|
| `extends EntityModel<MyEntity>` | `extends EntityModel<MyRenderState>` |
| ctor body starts with `this.part = root.getChild("x")` | prepend **`super(root);`** |
| `@Override public void renderToBuffer(PoseStack, VertexConsumer, int, int, int)` calling `part.render(...)` | **delete it** — it is `final` now; `root()` renders all children |
| `setupAnim(E entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch)` | `setupAnim(S state)` (call `super.setupAnim(state)` first if you set rotations — the base does `resetPose()`) |

**Check before deleting `renderToBuffer`:** the old override may have rendered only *some* of the
root's children. Compare the `partdefinition.addOrReplaceChild("…")` names against the parts the
override rendered; if they are the same set, rendering the root is equivalent.

Unchanged: `MeshDefinition`, `PartDefinition`, `CubeListBuilder`, `CubeDeformation`, `PartPose`,
`LayerDefinition.create(mesh, xTex, yTex)` — all still in `net.minecraft.client.model.geom.builders`.
`ModelPart.render(PoseStack, VertexConsumer, int light, int overlay[, int color])` still exists
(`ModelPart.java:103,107`).

`ModelLayerLocation` is now `record ModelLayerLocation(Identifier model, String layer)`
(`/opt/mc-src/net/minecraft/client/model/geom/ModelLayerLocation.java`).

`EntityModelSet.bakeLayer(ModelLayerLocation) -> ModelPart` unchanged; reach it from
`EntityRendererProvider.Context#getModelSet()` or `#bakeLayer(...)`, or
`Minecraft.getInstance().getEntityModels()` (`Minecraft.java:2821`) — but **not** during client init,
the set is only populated after the first resource load. Bake shared models inside a renderer
constructor: renderers are rebuilt on every resource reload, which is exactly when you need to re-bake.

---

## 4. Fabric client registration (all verified by `javap` on `fabric-api-0.154.2+26.2`)

```java
// entrypoint, declared in fabric.mod.json "entrypoints": { "client": [...] }
public class FooClient implements ClientModInitializer { public void onInitializeClient() { … } }
```

| what | call | package |
|---|---|---|
| entity renderer | `EntityRendererRegistry.register(EntityType<? extends E>, EntityRendererProvider<E>)` | `net.fabricmc.fabric.api.client.rendering.v1` |
| model layer | `ModelLayerRegistry.registerModelLayer(ModelLayerLocation, TexturedLayerDefinitionProvider)` where the provider is `LayerDefinition createLayerDefinition()` | `net.fabricmc.fabric.api.client.rendering.v1` |
| armor layers | `ModelLayerRegistry.registerArmorModelLayers(ArmorModelSet<ModelLayerLocation>, TexturedArmorModelSetProvider)` | same |
| menu screen | `MenuScreens.register(MenuType<? extends M>, MenuScreens.ScreenConstructor<M,U>)` — **vanilla**, see §5 | `net.minecraft.client.gui.screens` |
| key binding | `KeyMappingHelper.registerKeyMapping(KeyMapping)` | `net.fabricmc.fabric.api.client.keymapping.v1` |
| HUD layer | `HudElementRegistry.addLast(Identifier, HudElement)` (also `addFirst`, `attachElementBefore/After`, `removeElement`, `replaceElement`) | `net.fabricmc.fabric.api.client.rendering.v1.hud` |
| client tick | `ClientTickEvents.END_CLIENT_TICK.register(mc -> …)` (`EndTick#onEndTick(Minecraft)`) | `net.fabricmc.fabric.api.client.event.lifecycle.v1` |
| C2S packet | `ClientPlayNetworking.send(CustomPacketPayload)` | `net.fabricmc.fabric.api.client.networking.v1` |
| living-entity feature layers | `LivingEntityRenderLayerRegistrationCallback` | `net.fabricmc.fabric.api.client.rendering.v1` |

`HudElement` is a single method — note it is **extract**, not render:

```java
public interface HudElement { void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker); }
```

Vanilla element ids to anchor against: `VanillaHudElements.HOTBAR / HEALTH_BAR / MOUNT_HEALTH / CHAT / …`.

### KeyMapping
`/opt/mc-src/net/minecraft/client/KeyMapping.java:90-98,206-221`

```java
new KeyMapping(String name, int keysym, KeyMapping.Category category);
new KeyMapping(String name, InputConstants.Type type, int value, KeyMapping.Category category);
KeyMapping.Category.register(Identifier);   // string categories are gone
```
`isDown()` / `consumeClick()` unchanged.

---

## 5. Screens — `GuiGraphics` is gone, everything is *extraction*

`/opt/mc-src/net/minecraft/client/gui/screens/inventory/AbstractContainerScreen.java`

| 1.21.1 | 26.2 |
|---|---|
| `public void render(GuiGraphics, int mouseX, int mouseY, float partialTick)` | `public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a)` |
| `protected void renderBg(GuiGraphics, float partialTick, int x, int y)` | `public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a)` (declared on `Screen`, `Screen.java:377`) |
| `protected void renderLabels(GuiGraphics, int, int)` | `protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym)` |
| `renderTooltip(GuiGraphics, x, y)` | `graphics.setTooltipForNextFrame(font, List<? extends FormattedCharSequence>, x, y)` (and ~10 sibling overloads) — the base class already does slot tooltips |
| `imageWidth`/`imageHeight` assignable in ctor | **`protected final`** — pass them to `super(menu, inv, title, imageWidth, imageHeight)` |
| `getGuiLeft()` / `getGuiTop()` | **gone** — use the `protected leftPos` / `topPos`, or add your own accessors |

`GuiGraphicsExtractor` (`/opt/mc-src/net/minecraft/client/gui/GuiGraphicsExtractor.java`) essentials:

```java
int guiWidth(); int guiHeight();
Matrix3x2fStack pose();                       // 2-D now, not PoseStack
void enableScissor(int,int,int,int); void disableScissor();
void fill(int x0,int y0,int x1,int y1,int argb);
void text(Font, Component|String|FormattedCharSequence, int x, int y, int color[, boolean shadow]);
void blit(RenderPipeline, Identifier texture, int x, int y, float u, float v,
          int width, int height, int textureWidth, int textureHeight[, int color]);
void blitSprite(RenderPipeline, Identifier sprite, int x, int y, int w, int h);
void item(ItemStack, int x, int y); void itemDecorations(Font, ItemStack, int x, int y[, String count]);
void setTooltipForNextFrame(...);
```

`RenderPipelines.GUI_TEXTURED` (`/opt/mc-src/net/minecraft/client/renderer/RenderPipelines.java:769`)
is the pipeline for plain textured blits. There is no zero-pipeline `blit(Identifier, …)` overload
except the raw-UV one.

`ImageButton` / `WidgetSprites` are unchanged in shape but take `Identifier`
(`/opt/mc-src/net/minecraft/client/gui/components/ImageButton.java`, `WidgetSprites.java`); widgets
now override `extractContents(GuiGraphicsExtractor, int, int, float)`.

### `MenuScreens.register` accessibility — gotcha
`MenuScreens.register` and `MenuScreens.ScreenConstructor` are **private** in raw vanilla. The
Javadoc in `/opt/mc-src/net/minecraft/client/gui/screens/MenuScreens.java:60,113` literally says
*"Access widened by fabric-transitive-access-wideners-v1 to accessible"* — i.e. they are public only
because Fabric API's transitive access widener is applied. If a build fails with
`register(...) has private access in MenuScreens`, the transitive AW is not being applied; add to
your own `*.accesswidener` (namespace `official`):

```
accessible	method	net/minecraft/client/gui/screens/MenuScreens	register	(Lnet/minecraft/world/inventory/MenuType;Lnet/minecraft/client/gui/screens/MenuScreens$ScreenConstructor;)V
accessible	class	net/minecraft/client/gui/screens/MenuScreens$ScreenConstructor
```

`ScreenConstructor` shape is unchanged: `U create(T menu, Inventory inventory, Component title)`.

---

## 6. Rendering an entity inside a GUI

`InventoryScreen.renderEntityInInventory(...)` is gone. 26.2 uses a picture-in-picture render state
(`/opt/mc-src/net/minecraft/client/gui/screens/inventory/InventoryScreen.java:103-148`):

```java
EntityRenderDispatcher d = Minecraft.getInstance().getEntityRenderDispatcher();
EntityRenderer<? super E, ?> r = d.getRenderer(entity);      // EntityRenderDispatcher.java:94
EntityRenderState st = r.createRenderState(entity, 1.0F);    // EntityRenderer.java, final 2-arg form
st.shadowPieces.clear();
st.outlineColor = 0;
graphics.entity(st, size, new Vector3f(0, st.boundingBoxHeight / 2f, 0),
                rotationQuaternion, /*overrideCameraAngle*/ null, x0, y0, x1, y1);
```

`GuiGraphicsExtractor#entity(EntityRenderState, float scale, Vector3fc translation,
Quaternionfc rotation, @Nullable Quaternionfc overrideCameraAngle, int x0,int y0,int x1,int y1)`
— `GuiGraphicsExtractor.java:1006`.

---

## 7. Sounds — almost unchanged

`/opt/mc-src/net/minecraft/client/resources/sounds/AbstractTickableSoundInstance.java`

```java
protected AbstractTickableSoundInstance(SoundEvent event, SoundSource source, RandomSource random);
protected final void stop();   public boolean isStopped();
```
`AbstractSoundInstance` still exposes `protected double x,y,z; protected float volume; protected boolean looping;`
and `public float getPitch()`. `SoundInstance.createUnseededRandom()` still exists
(`SoundInstance.java:49`). Only the *now playing* toast moved: `mc.gui.hud.setNowPlaying(Component)`.

---

## 8. Mixins

* **Re-verify every `target=` descriptor against `/opt/mc-src`.** Concrete example from this port:
  the `Camera#setPosition(DDD)V` call used to sit in `Camera#setup`; in 26.2 it is inside the private
  `Camera#alignWithEntity(float partialTicks)` (`/opt/mc-src/net/minecraft/client/Camera.java:249-262`),
  and `Camera.partialTickTime` **no longer exists** (the partial tick is a method parameter now).
  `eyeHeight` / `eyeHeightOld` are still private fields (`Camera.java:62-63`) so `@Shadow` works.
* `Camera.getEntity()` → **`Camera.entity()`** (`Camera.java:407`). `isDetached()` still exists (`:419`).
* **MixinExtras availability is not guaranteed at compile time.** It ships nested inside
  `fabric-loader-0.19.3.jar` as `META-INF/jars/mixinextras-fabric-0.5.4.jar`, but no
  `org/spongepowered/asm/**` and no un-nested MixinExtras jar were present in the local Gradle cache.
  If you can express the patch without it, do — a plain
  `@Inject(method=…, at=@At(value="INVOKE", target=…, shift=At.Shift.AFTER, ordinal=0))` that
  re-applies the value is often enough to replace a `@WrapOperation`, and `@Inject` handlers already
  receive the target method's parameters (which is how you get `partialTicks`).
* Access wideners in 26.x use the `official` namespace header (`accessWidener v1 official`).
  `accessible method …` makes the method **public**, so you can call it from the mixin via
  `((Camera)(Object)this).setPosition(x, y, z)` without a `@Shadow`.

---

## 9. Client/server class-loading safety (what the dedicated-server boot actually catches)

* Everything client-only gets `@Environment(EnvType.CLIENT)` (`net.fabricmc.api.Environment` /
  `EnvType`). Vanilla does this on every client class — see the header of any file under
  `/opt/mc-src/net/minecraft/client/`.
* A *reference* to a client class from common code is only resolved when the enclosing bytecode
  actually executes, so the classic `if (level().isClientSide) { PlaneSound.tryToPlay(this); }` guard
  does keep the server from loading it. It is fragile but it works — do not "fix" it by hoisting the
  call out of the branch.
* Never put a client type in a **field type, superclass, interface, or annotation** of a
  common class — those are resolved at class-load time and will crash the server.
* Method *parameter/return* types of a common class are resolved lazily too, but only if the method
  is never called and never verified against; treat this as a smell, not a pattern. The safe fix is
  to move the method to a client-only class.
* Client-only registration all funnels through `ClientModInitializer#onInitializeClient`, which the
  dedicated server never invokes — that is the real firewall.

---

## 10. Misc verified odds and ends

| thing | 26.2 |
|---|---|
| `Registry#getKey(T)` | `@Nullable Identifier getKey(T)` (`/opt/mc-src/net/minecraft/core/Registry.java:58`) |
| `Font#split(FormattedText, int)` | unchanged, returns `List<FormattedCharSequence>` (`Font.java:148`) |
| `Lighting` | still `com.mojang.blaze3d.platform.Lighting` |
| `OverlayTexture.NO_OVERLAY` | unchanged |
| `com.mojang.math.Axis` | unchanged |
| `PoseStack` | unchanged, `com.mojang.blaze3d.vertex.PoseStack` |
| `@Nullable` | vanilla uses `org.jspecify.annotations.Nullable`; `javax.annotation.Nullable` is **not** on the 26.2 classpath |
| block texture from a block | there is no model-quad path any more; deriving `<ns>:textures/block/<path>.png` from `BuiltInRegistries.BLOCK.getKey(block)` is the cheap approximation |

---

## 11. Quick offline type-check trick (no gradle)

The orchestrator owns gradle, but you can type-check your own files without it:

```sh
CP=$(find /root/.gradle/caches/modules-2/files-2.1 -name '*.jar' ! -name '*sources*' | tr '\n' ':')\
/root/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.2/minecraft-merged-deobf-26.2.jar
javac -nowarn -proc:none -Xmaxerrs 3000 --release 25 -cp "$CP" -d /tmp/out \
      $(find src/main/java -name '*.java' ! -name '*Mixin.java')
```

Caveats: mixin annotations are not on that classpath (exclude mixin files), and the raw deobf jar has
**no access wideners applied**, so `MenuScreens.register` reports "has private access" — that one is a
false positive under Loom. Everything else is real.
