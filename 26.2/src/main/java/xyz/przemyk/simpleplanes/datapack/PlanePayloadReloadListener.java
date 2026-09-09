package xyz.przemyk.simpleplanes.datapack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.StrictJsonParser;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.util.HashMap;
import java.util.Map;

/**
 * See {@link PlaneLiquidFuelReloadListener} — same 26.2 reload-listener rewrite, same on-disk
 * JSON shape ({@code data/<ns>/plane_payload/*.json}).
 */
public class PlanePayloadReloadListener extends SimplePreparableReloadListener<Map<Identifier, JsonElement>> {

    private static final Logger LOGGER = LoggerFactory.getLogger("simpleplanes");
    private static final FileToIdConverter LISTER = FileToIdConverter.json("plane_payload");

    public static final Map<Item, PayloadEntry> payloadEntries = new HashMap<>();

    @Override
    protected Map<Identifier, JsonElement> prepare(ResourceManager manager, ProfilerFiller profiler) {
        Map<Identifier, JsonElement> result = new HashMap<>();
        for (Map.Entry<Identifier, Resource> entry : LISTER.listMatchingResources(manager).entrySet()) {
            Identifier id = LISTER.fileToId(entry.getKey());
            try (Reader reader = entry.getValue().openAsReader()) {
                result.put(id, StrictJsonParser.parse(reader));
            } catch (Exception e) {
                LOGGER.error("Couldn't read plane payload {}", id, e);
            }
        }
        return result;
    }

    @Override
    protected void apply(Map<Identifier, JsonElement> map, ResourceManager resourceManager, ProfilerFiller profilerFiller) {
        payloadEntries.clear();
        for (Map.Entry<Identifier, JsonElement> entry : map.entrySet()) {
            try {
                JsonObject jsonObject = GsonHelper.convertToJsonObject(entry.getValue(), "top element");
                Item item = require(BuiltInRegistries.ITEM, jsonObject, "item");
                Block renderBlock = require(BuiltInRegistries.BLOCK, jsonObject, "block");
                EntityType<?> dropSpawnEntity = require(BuiltInRegistries.ENTITY_TYPE, jsonObject, "entity");
                CompoundTag compoundTag;
                if (jsonObject.has("entity_nbt")) {
                    String tag = GsonHelper.convertToString(jsonObject.get("entity_nbt"), "entity_nbt");
                    compoundTag = TagParser.parseCompoundFully(tag);
                } else {
                    compoundTag = new CompoundTag();
                }

                payloadEntries.put(item, new PayloadEntry(item, renderBlock, dropSpawnEntity, compoundTag));
            } catch (Exception e) {
                LOGGER.error("Parsing error loading plane payload {}", entry.getKey(), e);
            }
        }
    }

    /**
     * Reads one registry id out of the entry, refusing an id the registry does not know.
     *
     * <p>The item, block and entity registries are defaulted: {@code getValue} answers with air or a
     * pig for an id that is not there rather than with null, so a mistyped payload used to load as a
     * working entry keyed on air — which then matched an empty hand. {@code getOptional} is the
     * lookup that actually reports the miss.
     */
    private static <T> T require(Registry<T> registry, JsonObject jsonObject, String field) {
        Identifier id = Identifier.parse(GsonHelper.getAsString(jsonObject, field));
        return registry.getOptional(id).orElseThrow(
            () -> new IllegalArgumentException("unknown " + field + " " + id));
    }
}
