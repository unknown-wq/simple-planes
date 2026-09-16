package xyz.przemyk.simpleplanes.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
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
 * Moves the first-person viewpoint into the cockpit.
 *
 * <p>This changes the camera's <em>position</em> and nothing else. It does not roll the view: the
 * camera keeps the orientation {@code Camera#alignWithEntity} gave it from
 * {@code entity.getViewYRot}/{@code getViewXRot}. Rolling the horizon with the aircraft was a
 * separate Forge-era hook ({@code ViewportEvent.ComputeCameraAngles}) with no Fabric counterpart,
 * and it was dropped during the port — see the note in {@code client/ClientEventHandler}.
 *
 * <p>What the position change is for: on an ordinary vehicle vanilla places the camera at the
 * rider's interpolated position plus a strictly <em>vertical</em> eye offset, so in a rolled or
 * inverted aircraft the pilot's eye hangs above the seat in world space instead of staying in the
 * cockpit. Here the eye offset is rotated out of the aircraft's body frame by its interpolated
 * attitude ({@code getQ_Prev()} to {@code getQ_Client()}, lerped by {@code partialTicks}) — the
 * same body-to-world transform {@code PlaneEntity#transformPos} uses to place the seat itself.
 *
 * <h2>Why the injection sits here</h2>
 *
 * <p>It runs immediately after the {@code alignWithEntity(F)V} call inside the public
 * {@code Camera#update(DeltaTracker)}, rather than inside {@code alignWithEntity} itself:
 *
 * <ul>
 *   <li>{@code update} is public where {@code alignWithEntity} is private, so the injection is
 *       anchored to the more stable of the two names.</li>
 *   <li>{@code alignWithEntity(F)V} is invoked exactly once in the whole class, so the injection
 *       point needs no {@code ordinal} pinning it to one call among several.</li>
 *   <li>{@code partialTicks} comes from the public {@code getCameraEntityPartialTicks}, the same
 *       pure getter {@code update} calls one instruction earlier to build the argument, so the
 *       value need not be captured from a local.</li>
 *   <li>It is still ahead of {@code prepareCullFrustum}, which reads the camera position, so the
 *       cull frustum and the projection are prepared from the relocated viewpoint.</li>
 * </ul>
 *
 * <p>Running after {@code alignWithEntity} returns rather than mid-method costs nothing here: the
 * only things vanilla does to the position in between are the detached third-person zoom-out and
 * the sleeping nudge, and neither is reachable — the third-person case returns early below, and a
 * passenger cannot be asleep.
 *
 * <p>The mixin config declares this injector optional. If a future refactor moves or renames the
 * target, the injection is skipped and the player gets the vanilla camera and a log line, rather
 * than the game refusing to start.
 *
 * <p>Port note: in 1.21.1 this wrapped {@code Camera#setPosition(DDD)} inside {@code Camera#setup}
 * using MixinExtras' {@code @WrapOperation} and read {@code Camera.partialTickTime}; that field and
 * that method are both gone. {@code setPosition(DDD)V} is {@code protected}, not private, so a
 * {@code @Shadow} reaches it from inside the target and no access widener is needed.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {

    @Shadow private float eyeHeight;

    @Shadow private float eyeHeightOld;

    @Shadow protected abstract void setPosition(double x, double y, double z);

    @Inject(
        method = "update",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Camera;alignWithEntity(F)V",
            shift = At.Shift.AFTER
        )
    )
    private void simpleplanes$cockpitCamera(DeltaTracker deltaTracker, CallbackInfo ci) {
        Camera camera = (Camera) (Object) this;
        Entity player = camera.entity();
        if (player == null || !(player.getVehicle() instanceof PlaneEntity planeEntity)) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.options.getCameraType().isFirstPerson()) {
            return;
        }

        float partialTicks = camera.getCameraEntityPartialTicks(deltaTracker);

        float heightDiff = -0.3F;

        Quaternionf qPrev = planeEntity.getQ_Prev();
        Quaternionf qNow = planeEntity.getQ_Client();

        Vector3f eyePrev = new Vector3f(0.0F, this.eyeHeightOld + heightDiff, 0.0F);
        Vector3f eyeNow = new Vector3f(0.0F, this.eyeHeight + heightDiff, 0.0F);
        eyePrev.rotate(qPrev);
        eyeNow.rotate(qNow);

        this.setPosition(
            Mth.lerp(partialTicks, player.xo - eyePrev.x(), player.getX() - eyeNow.x()),
            Mth.lerp(partialTicks, player.yo + eyePrev.y(), player.getY() + eyeNow.y()) + 0.375,
            Mth.lerp(partialTicks, player.zo + eyePrev.z(), player.getZ() + eyeNow.z()));
    }
}
