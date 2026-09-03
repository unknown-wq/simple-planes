package xyz.przemyk.simpleplanes.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.lwjgl.glfw.GLFW;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.entities.HelicopterEntity;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;
import xyz.przemyk.simpleplanes.network.ChangeThrottlePacket;
import xyz.przemyk.simpleplanes.network.HeliCyclicPacket;
import xyz.przemyk.simpleplanes.network.MoveHeliUpPacket;
import xyz.przemyk.simpleplanes.network.OpenPlaneInventoryPacket;
import xyz.przemyk.simpleplanes.network.PitchPacket;
import xyz.przemyk.simpleplanes.network.YawPacket;

/**
 * Key bindings + the per-tick client input handling that used to sit on the NeoForge
 * {@code PlayerTickEvent} bus. Registered from {@link SimplePlanesClient}.
 */
@Environment(EnvType.CLIENT)
public final class ClientEventHandler {

    private ClientEventHandler() {}

    public static final KeyMapping.Category CATEGORY =
        KeyMapping.Category.register(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "main"));

    public static KeyMapping moveHeliUpKey;
    public static KeyMapping openPlaneInventoryKey;
    public static KeyMapping dropPayloadKey;
    public static KeyMapping throttleUp;
    public static KeyMapping throttleDown;
    public static KeyMapping pitchUp;
    public static KeyMapping pitchDown;
    public static KeyMapping yawRight;
    public static KeyMapping yawLeft;

    private static KeyMapping bind(String name, int key) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping(name, InputConstants.Type.KEYSYM, key, CATEGORY));
    }

    public static void registerKeyBindings() {
        moveHeliUpKey = bind("key.move_heli_up.desc", GLFW.GLFW_KEY_SPACE);
        openPlaneInventoryKey = bind("key.plane_inventory_open.desc", GLFW.GLFW_KEY_X);
        dropPayloadKey = bind("key.plane_drop_payload.desc", GLFW.GLFW_KEY_C);
        throttleUp = bind("key.plane_throttle_up.desc", GLFW.GLFW_KEY_UP);
        throttleDown = bind("key.plane_throttle_down.desc", GLFW.GLFW_KEY_DOWN);
        pitchUp = bind("key.plane_pitch_up.desc", GLFW.GLFW_KEY_W);
        pitchDown = bind("key.plane_pitch_down.desc", GLFW.GLFW_KEY_S);
        yawRight = bind("key.plane_yaw_right.desc", GLFW.GLFW_KEY_RIGHT);
        yawLeft = bind("key.plane_yaw_left.desc", GLFW.GLFW_KEY_LEFT);
    }

    private static boolean oldMoveHeliUpState = false;
    private static boolean oldPitchUpState = false;
    private static boolean oldPitchDownState = false;
    private static boolean oldYawRightState = false;
    private static boolean oldYawLeftState = false;
    private static byte oldCyclicForward = 0;
    private static byte oldCyclicRight = 0;

    public static void onClientTick(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }

        if (player.getVehicle() instanceof PlaneEntity planeEntity) {
            if (mc.options.getCameraType() != CameraType.FIRST_PERSON) {
                planeEntity.applyYawToEntity(player);
            }

            if (mc.gui.screen() == null && openPlaneInventoryKey.consumeClick()) {
                ClientPlayNetworking.send(new OpenPlaneInventoryPacket());
            } else if (dropPayloadKey.consumeClick() && planeEntity.getControllingPassenger() == player) {
                // Must match DropPayloadPacket's server-side guard: dropPayload() removes the
                // upgrade locally before sending, so a passenger the server rejects would desync.
                planeEntity.dropPayload();
            }

            if (throttleUp.consumeClick()) {
                ClientPlayNetworking.send(new ChangeThrottlePacket(ChangeThrottlePacket.Direction.UP));
            } else if (throttleDown.consumeClick()) {
                ClientPlayNetworking.send(new ChangeThrottlePacket(ChangeThrottlePacket.Direction.DOWN));
            }

            boolean isMoveHeliUp = moveHeliUpKey.isDown();
            boolean isPitchUp = pitchUp.isDown();
            boolean isPitchDown = pitchDown.isDown();
            boolean isYawRight = yawRight.isDown();
            boolean isYawLeft = yawLeft.isDown();

            if (isMoveHeliUp != oldMoveHeliUpState) {
                ClientPlayNetworking.send(new MoveHeliUpPacket(isMoveHeliUp));
            }

            if (planeEntity instanceof HelicopterEntity) {
                // The helicopter's translation controls are the cyclic, not the elevator: W/S tip
                // the disc fore and aft, A/D bank it. The strafe axis cannot be read on the server
                // (Player.xxa is written only by LocalPlayer.aiStep), so it has to be a packet, and
                // the fore/aft sign is inverted relative to PitchPacket - W means "go forward",
                // which on a helicopter is nose *down*. Sending PitchPacket as well would leave
                // PITCH_UP non-zero, which PlaneEntity.tick() reads as "force onGround before the
                // move", so the elevator packet is deliberately not sent for this airframe.
                byte cyclicForward = (byte) (Boolean.compare(isPitchUp, isPitchDown) * HelicopterEntity.CYCLIC_FULL);
                byte cyclicRight = (byte) (Boolean.compare(mc.options.keyRight.isDown(), mc.options.keyLeft.isDown())
                    * HelicopterEntity.CYCLIC_FULL);
                if (cyclicForward != oldCyclicForward || cyclicRight != oldCyclicRight) {
                    ClientPlayNetworking.send(new HeliCyclicPacket(cyclicForward, cyclicRight));
                }
                oldCyclicForward = cyclicForward;
                oldCyclicRight = cyclicRight;
            } else if (isPitchUp != oldPitchUpState || isPitchDown != oldPitchDownState) {
                ClientPlayNetworking.send(new PitchPacket((byte) Boolean.compare(isPitchUp, isPitchDown)));
            }

            if (isYawRight != oldYawRightState || isYawLeft != oldYawLeftState) {
                ClientPlayNetworking.send(new YawPacket((byte) Boolean.compare(isYawRight, isYawLeft)));
            }

            oldMoveHeliUpState = isMoveHeliUp;
            oldPitchUpState = isPitchUp;
            oldPitchDownState = isPitchDown;
            oldYawRightState = isYawRight;
            oldYawLeftState = isYawLeft;
        } else {
            oldMoveHeliUpState = false;
            oldPitchUpState = false;
            oldPitchDownState = false;
            oldYawRightState = false;
            oldYawLeftState = false;
            oldCyclicForward = 0;
            oldCyclicRight = 0;
        }
    }

    // TODO(port-26.2): DISABLED — three NeoForge-only client events with no Fabric equivalent:
    //  * RenderLivingEvent.Pre/Post   — rotated riding players with the plane's orientation.
    //  * ViewportEvent.ComputeCameraAngles — rolled the first-person camera with the plane.
    //  * CalculateDetachedCameraDistanceEvent — scaled the third-person camera distance per plane.
    // Fabric has no equivalent hook; re-implementing them needs bespoke mixins into
    // LivingEntityRenderer / GameRenderer / Camera, which is out of scope for the port.
    /*
    public static void onRenderPre(RenderLivingEvent.Pre<LivingEntity, ?> event) { ... }
    public static void onRenderPost(RenderLivingEvent.Post event) { ... }
    public static void onCameraSetup(ViewportEvent.ComputeCameraAngles event) { ... }
    public static void onCalculateDetachedCameraDistance(CalculateDetachedCameraDistanceEvent event) { ... }
    */
}
