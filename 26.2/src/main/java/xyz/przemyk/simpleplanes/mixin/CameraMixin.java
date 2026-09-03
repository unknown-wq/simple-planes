package xyz.przemyk.simpleplanes.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;

/**
 * Puts the first-person camera inside the cockpit and rolls it with the plane.
 *
 * <p>Port note: in 1.21.1 this wrapped {@code Camera#setPosition(DDD)} inside {@code Camera#setup}
 * using MixinExtras' {@code @WrapOperation}, and read {@code Camera.partialTickTime}. In 26.2
 * (/opt/mc-src/net/minecraft/client/Camera.java) that field is gone and the {@code setPosition(DDD)}
 * call moved into the private {@code alignWithEntity(float partialTicks)} (line ~262), which is now
 * the target. To avoid depending on MixinExtras this re-applies the position with a plain
 * {@code @Inject} right after vanilla set it; {@code Camera#setPosition(DDD)V} is public thanks to
 * {@code simpleplanes.accesswidener}.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {

    @Shadow private float eyeHeight;

    @Shadow private float eyeHeightOld;

    @Inject(
        method = "alignWithEntity",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Camera;setPosition(DDD)V",
            shift = At.Shift.AFTER,
            ordinal = 0
        )
    )
    private void simpleplanes$cockpitCamera(float partialTicks, CallbackInfo ci) {
        Camera camera = (Camera) (Object) this;
        Entity player = camera.entity();
        if (player == null || !(player.getVehicle() instanceof PlaneEntity planeEntity)) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.options.getCameraType().isFirstPerson()) {
            return;
        }

        float heightDiff = -0.3F;

        Quaternionf qPrev = planeEntity.getQ_Prev();
        Quaternionf qNow = planeEntity.getQ_Client();

        Vector3f eyePrev = new Vector3f(0.0F, this.eyeHeightOld + heightDiff, 0.0F);
        Vector3f eyeNow = new Vector3f(0.0F, this.eyeHeight + heightDiff, 0.0F);
        eyePrev.rotate(qPrev);
        eyeNow.rotate(qNow);

        camera.setPosition(
            Mth.lerp(partialTicks, player.xo - eyePrev.x(), player.getX() - eyeNow.x()),
            Mth.lerp(partialTicks, player.yo + eyePrev.y(), player.getY() + eyeNow.y()) + 0.375,
            Mth.lerp(partialTicks, player.zo + eyePrev.z(), player.getZ() + eyeNow.z()));
    }
}
