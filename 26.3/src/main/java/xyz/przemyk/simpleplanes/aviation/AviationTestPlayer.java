package xyz.przemyk.simpleplanes.aviation;

import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * A fake player for {@code /aviation test}: stands where the test says, has operator permission or none, and
 * keeps the last action-bar message. It goes through {@link AviationService#handleLaunch} like a real client.
 */
final class AviationTestPlayer extends FakePlayer {

    private static final GameProfile OP = new GameProfile(UUID.fromString("a1a7e5c0-0000-4000-8000-0000000a0001"), "[AviationOp]");
    private static final GameProfile NON_OP = new GameProfile(UUID.fromString("a1a7e5c0-0000-4000-8000-0000000a0002"), "[AviationUser]");

    private final boolean operator;
    private @Nullable Component lastMessage;

    AviationTestPlayer(ServerLevel level, boolean operator) {
        super(level, operator ? OP : NON_OP);
        this.operator = operator;
    }

    @Override
    public PermissionSet permissions() {
        return operator ? LevelBasedPermissionSet.GAMEMASTER : PermissionSet.NO_PERMISSIONS;
    }

    @Override
    public void sendOverlayMessage(Component message) {
        lastMessage = message;
    }

    @Nullable Component lastMessage() {
        return lastMessage;
    }
}
