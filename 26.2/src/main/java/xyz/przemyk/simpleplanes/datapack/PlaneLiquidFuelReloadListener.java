package xyz.przemyk.simpleplanes.datapack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.StrictJsonParser;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.material.Fluid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.util.HashMap;
import java.util.Map;

/**
 * 26.2's {@code SimpleJsonResourceReloadListener} became codec-driven and generic, so this now
 * extends {@link SimplePreparableReloadListener} and does its own directory scan — the on-disk
 * JSON shape ({@code data/<ns>/plane_liquid_fuels/*.json}) is unchanged.
 */
public class PlaneLiquidFuelReloadListener extends SimplePreparableReloadListener<Map<Identifier, JsonElement>> {

    private static final Logger LOGGER = LoggerFactory.getLogger("simpleplanes");
    private static final FileToIdConverter LISTER = FileToIdConverter.json("plane_liquid_fuels");

    public static final Map<Fluid, Integer> fuelMap = new HashMap<>();

    @Override
    protected Map<Identifier, JsonElement> prepare(ResourceManager manager, ProfilerFiller profiler) {
        Map<Identifier, JsonElement> result = new HashMap<>();
        for (Map.Entry<Identifier, Resource> entry : LISTER.listMatchingResources(manager).entrySet()) {
            Identifier id = LISTER.fileToId(entry.getKey());
            try (Reader reader = entry.getValue().openAsReader()) {
                result.put(id, StrictJsonParser.parse(reader));
            } catch (Exception e) {
                LOGGER.error("Couldn't read plane liquid fuel {}", id, e);
            }
        }
        return result;
    }

    @Override
    protected void apply(Map<Identifier, JsonElement> map, ResourceManager resourceManager, ProfilerFiller profilerFiller) {
        fuelMap.clear();
        for (Map.Entry<Identifier, JsonElement> entry : map.entrySet()) {
            try {
                JsonObject jsonObject = GsonHelper.convertToJsonObject(entry.getValue(), "top element");
                Fluid fluidType = BuiltInRegistries.FLUID.getValue(Identifier.parse(jsonObject.get("fluid").getAsString()));
                if (fluidType == null) {
                    continue;
                }
                int fuelPerMb = jsonObject.get("burn_time_per_mb").getAsInt();

                fuelMap.put(fluidType, fuelPerMb);
            } catch (Exception e) {
                LOGGER.error("Parsing error loading plane liquid fuel {}", entry.getKey(), e);
            }
        }
    }
}
