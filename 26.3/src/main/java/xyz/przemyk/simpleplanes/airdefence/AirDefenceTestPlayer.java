package xyz.przemyk.simpleplanes.airdefence;

import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/** A survival fake player for {@code /airdefence click} and {@code /airdefence place}; keeps the last action-bar message. */
final class AirDefenceTestPlayer extends FakePlayer {

    private static final GameProfile PROFILE = new GameProfile(UUID.fromString("5c1e0a11-0000-4000-8000-0000000ad0ad"), "[ADTest]");

    private @Nullable Component lastMessage;

    AirDefenceTestPlayer(ServerLevel level) {
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
