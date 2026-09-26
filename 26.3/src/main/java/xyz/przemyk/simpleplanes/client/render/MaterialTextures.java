package xyz.przemyk.simpleplanes.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.reloader.ResourceReloaderKeys;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.przemyk.simpleplanes.SimplePlanesMod;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Standalone texture for the material layer of an aircraft, per block.
 *
 * <p>The material models tile the texture with UVs far outside 0..1, so they need a standalone
 * texture with REPEAT addressing; an atlas sprite cannot be used. The texture is found through the
 * block's baked model: the sprite of the first south-face quad of the default state (as 1.21.1 did),
 * else the model's particle sprite, and its sprite name {@code ns:block/x} maps to
 * {@code ns:textures/block/x.png}. That covers blocks whose texture is not named after the block
 * ({@code oak_wood} -> {@code oak_log}, {@code quartz_block} -> {@code quartz_block_side}, ...) and
 * modded blocks alike. Animated sprites are strips; their first frame is uploaded as a
 * {@link DynamicTexture}. Results are cached per block and dropped on every resource reload.
 */
@Environment(EnvType.CLIENT)
public final class MaterialTextures {

    public static final Identifier FALLBACK = Identifier.fromNamespaceAndPath("minecraft", "textures/block/oak_planks.png");
    private static final Identifier RELOAD_ID = Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "material_textures");

    private static final Logger LOGGER = LoggerFactory.getLogger("simpleplanes-textures");
    private static final Map<Block, Identifier> CACHE = new HashMap<>();
    private static final List<Identifier> FIRST_FRAMES = new ArrayList<>();

    private MaterialTextures() {
    }

    /** Clears the cache after every client resource reload, once the new block models are baked. */
    public static void register() {
        ResourceLoader loader = ResourceLoader.get(PackType.CLIENT_RESOURCES);
        loader.registerReloadListener(RELOAD_ID, (ResourceManagerReloadListener) manager -> clear());
        loader.addListenerOrdering(ResourceReloaderKeys.Client.MODELS, RELOAD_ID);
    }

    /** Render thread only. */
    public static Identifier get(Block block) {
        Identifier texture = CACHE.get(block);
        if (texture == null) {
            texture = resolve(block);
            CACHE.put(block, texture);
        }
        return texture;
    }

    public static void clear() {
        CACHE.clear();
        Minecraft minecraft = Minecraft.getInstance();
        for (Identifier id : FIRST_FRAMES) {
            minecraft.getTextureManager().release(id);
        }
        FIRST_FRAMES.clear();
    }

    private static Identifier resolve(Block block) {
        Minecraft minecraft = Minecraft.getInstance();
        ResourceManager resources = minecraft.getResourceManager();

        TextureAtlasSprite sprite = modelSprite(minecraft, block);
        if (sprite != null) {
            SpriteContents contents = sprite.contents();
            Identifier png = contents.name().withPath(path -> "textures/" + path + ".png");
            if (resources.getResource(png).isPresent()) {
                return contents.isAnimated() ? firstFrame(minecraft, contents, png) : png;
            }
        }

        // No usable model (not loaded yet, or a sprite from a generated source): try the block id.
        Identifier key = BuiltInRegistries.BLOCK.getKey(block);
        Identifier byId = key.withPath(path -> "textures/block/" + path + ".png");
        if (resources.getResource(byId).isPresent()) {
            return byId;
        }
        LOGGER.debug("No material texture for {}, using {}", key, FALLBACK);
        return FALLBACK;
    }

    private static @Nullable TextureAtlasSprite modelSprite(Minecraft minecraft, Block block) {
        BlockStateModelSet models;
        try {
            models = minecraft.getModelManager().getBlockStateModelSet();
        } catch (NullPointerException notBakedYet) {
            return null;
        }
        BlockStateModel model = models.get(block.defaultBlockState());
        if (model == models.missingModel()) {
            return null;
        }

        List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(RandomSource.create(42L), parts);
        for (BlockStateModelPart part : parts) {
            List<BakedQuad> quads = part.getQuads(Direction.SOUTH);
            if (!quads.isEmpty()) {
                TextureAtlasSprite sprite = quads.getFirst().materialInfo().sprite();
                if (!isMissing(sprite)) {
                    return sprite;
                }
            }
        }

        TextureAtlasSprite particle = model.particleMaterial().sprite();
        return isMissing(particle) ? null : particle;
    }

    private static boolean isMissing(TextureAtlasSprite sprite) {
        return sprite.contents().name().equals(MissingTextureAtlasSprite.getLocation());
    }

    /** Uploads the top frame of an animation strip; falls back to the whole strip on failure. */
    private static Identifier firstFrame(Minecraft minecraft, SpriteContents contents, Identifier png) {
        Identifier id = Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID,
            "material/" + png.getNamespace() + "/" + png.getPath());
        try (InputStream in = minecraft.getResourceManager().open(png); NativeImage strip = NativeImage.read(in)) {
            int width = Math.min(contents.width(), strip.getWidth());
            int height = Math.min(contents.height(), strip.getHeight());
            NativeImage frame = new NativeImage(width, height, false);
            strip.copyRect(frame, 0, 0, 0, 0, width, height, false, false);
            minecraft.getTextureManager().register(id, new DynamicTexture(id::toString, frame));
            FIRST_FRAMES.add(id);
            return id;
        } catch (Exception e) {
            LOGGER.warn("Could not cut the first frame out of {}", png, e);
            return png;
        }
    }
}
