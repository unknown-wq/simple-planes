package xyz.przemyk.simpleplanes.recipes;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;

/**
 * 26.1+ turned {@code RecipeSerializer} into a record of {@code (MapCodec, StreamCodec)} — there is
 * no serializer class to implement any more, so this is now just the codec holder plus a factory.
 */
public final class PlaneWorkbenchRecipeSerializer {

    private PlaneWorkbenchRecipeSerializer() {
    }

    public static final MapCodec<PlaneWorkbenchRecipe> CODEC = RecordCodecBuilder.mapCodec(
        kind -> kind.group(
                Ingredient.CODEC.fieldOf("ingredient").forGetter(PlaneWorkbenchRecipe::ingredient),
                Codec.INT.fieldOf("ingredient_amount").forGetter(PlaneWorkbenchRecipe::ingredientAmount),
                Codec.INT.fieldOf("material_amount").forGetter(PlaneWorkbenchRecipe::materialAmount),
                ItemStackTemplate.CODEC.fieldOf("result").forGetter(PlaneWorkbenchRecipe::result)
            ).apply(kind, PlaneWorkbenchRecipe::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, PlaneWorkbenchRecipe> STREAM_CODEC = StreamCodec.of(
        PlaneWorkbenchRecipeSerializer::toNetwork, PlaneWorkbenchRecipeSerializer::fromNetwork
    );

    public static PlaneWorkbenchRecipe fromNetwork(RegistryFriendlyByteBuf buffer) {
        Ingredient ingredient = Ingredient.CONTENTS_STREAM_CODEC.decode(buffer);
        int ingredientAmount = buffer.readVarInt();
        int materialAmount = buffer.readVarInt();
        ItemStackTemplate result = ItemStackTemplate.STREAM_CODEC.decode(buffer);
        return new PlaneWorkbenchRecipe(ingredient, ingredientAmount, materialAmount, result);
    }

    public static void toNetwork(RegistryFriendlyByteBuf buffer, PlaneWorkbenchRecipe recipe) {
        Ingredient.CONTENTS_STREAM_CODEC.encode(buffer, recipe.ingredient());
        buffer.writeVarInt(recipe.ingredientAmount());
        buffer.writeVarInt(recipe.materialAmount());
        ItemStackTemplate.STREAM_CODEC.encode(buffer, recipe.result());
    }

    public static RecipeSerializer<PlaneWorkbenchRecipe> create() {
        return new RecipeSerializer<>(CODEC, STREAM_CODEC);
    }
}
