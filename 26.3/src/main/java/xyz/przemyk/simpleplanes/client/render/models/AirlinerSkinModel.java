package xyz.przemyk.simpleplanes.client.render.models;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import xyz.przemyk.simpleplanes.client.render.PlaneRenderState;

/**
 * Airliner, metal-skinned material layer: the same {@link AirlinerAirframe} geometry as {@link AirlinerModel},
 * with every cube's UV net laid out once in the size's skin texture ({@link AirlinerShape#skinTexture()})
 * instead of tiling a block texture. The skin is a white-and-aluminium airliner livery: white upper fuselage
 * and fin, a blue cheat line that continues the window belt's around the nose and tail, an aluminium belly,
 * wings and stabilisers, panel lines and a grey radome. Pair it with {@link AirlinerMetalModel} and {@link AirlinerFanModel} as usual.
 *
 * <p>Rendered with {@link RenderTypes#entityCutoutCull} for the same reason as {@link AirlinerModel}: the
 * riders' eyes are inside the fuselage. The texOffs tables ({@link AirlinerUv}) are generated with the textures.
 */
public class AirlinerSkinModel extends EntityModel<PlaneRenderState> {

    public AirlinerSkinModel(ModelPart root) {
        super(root, RenderTypes::entityCutoutCull);
    }

    public static LayerDefinition createBodyLayer(AirlinerShape shape) {
        return LayerDefinition.create(AirlinerAirframe.create(shape, AirlinerAirframe.table(shape.skinUv)),
                shape.skinSize[0], shape.skinSize[1]);
    }

    @Override
    public void setupAnim(PlaneRenderState state) {}
}
