package xyz.przemyk.simpleplanes.missile;

import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/** A survival fake player for {@code /missile item}: uses and breaks like a player and keeps the last action-bar message. */
final class SiloTestPlayer extends FakePlayer {

    private static final GameProfile PROFILE = new GameProfile(UUID.fromString("5c1e0a11-0000-4000-8000-00000000510a"), "[SiloTest]");

    private @Nullable Component lastMessage;

    SiloTestPlayer(ServerLevel level) {
        super(level, PROFILE);
    }

    @Override
    public void sendOverlayMessage(Component message) {
        lastMessage = message;
    }

    @Nullable Component lastMessage() {
        return lastMessage;
    }
}
