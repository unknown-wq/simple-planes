package xyz.przemyk.simpleplanes.setup;

import net.fabricmc.fabric.api.recipe.v1.sync.RecipeSynchronization;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.recipes.PlaneWorkbenchRecipe;
import xyz.przemyk.simpleplanes.recipes.PlaneWorkbenchRecipeSerializer;

import java.util.function.Supplier;

public class SimplePlanesRecipes {

    public static final Supplier<RecipeSerializer<PlaneWorkbenchRecipe>> PLANE_WORKBENCH_RECIPE_SERIALIZER = registerSerializer();
    public static final Supplier<RecipeType<PlaneWorkbenchRecipe>> PLANE_WORKBENCH_RECIPE_TYPE = registerType();

    /**
     * Class-load hook. Also opts the plane-workbench recipes into Fabric's recipe synchronisation so
     * the container can list them client-side (26.2 no longer ships all recipes to the client).
     */
    public static void init() {
        RecipeSynchronization.synchronizeRecipeSerializer(PLANE_WORKBENCH_RECIPE_SERIALIZER.get());
    }

    private static Supplier<RecipeSerializer<PlaneWorkbenchRecipe>> registerSerializer() {
        RecipeSerializer<PlaneWorkbenchRecipe> value = Registry.register(BuiltInRegistries.RECIPE_SERIALIZER,
            Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "plane_workbench"),
            PlaneWorkbenchRecipeSerializer.create());
        return () -> value;
    }

    private static Supplier<RecipeType<PlaneWorkbenchRecipe>> registerType() {
        RecipeType<PlaneWorkbenchRecipe> value = Registry.register(BuiltInRegistries.RECIPE_TYPE,
            Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "plane_workbench"),
            new RecipeType<PlaneWorkbenchRecipe>() {
                @Override
                public String toString() {
                    return SimplePlanesMod.MODID + ":plane_workbench";
                }
            });
        return () -> value;
    }
}
