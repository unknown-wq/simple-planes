package xyz.przemyk.simpleplanes.client;

import com.google.common.collect.Maps;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.JukeboxSong;
import xyz.przemyk.simpleplanes.network.JukeboxPacket;

import java.util.Map;

@Environment(EnvType.CLIENT)
public class MovingSound extends AbstractTickableSoundInstance {

    public static final Map<Entity, SoundInstance> playingRecords = Maps.newHashMap();

    private final Entity entity;

    public MovingSound(SoundEvent soundEvent, Entity entity) {
        super(soundEvent, SoundSource.RECORDS, SoundInstance.createUnseededRandom());
        this.entity = entity;
    }

    @Override
    public void tick() {
        if (!entity.isAlive()) {
            stop();
        } else {
            x = entity.getX();
            y = entity.getY();
            z = entity.getZ();
        }
    }

    public static void playRecord(JukeboxPacket jukeboxPacket) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }

        Entity entity = minecraft.level.getEntity(jukeboxPacket.planeEntityID());
        Item item = BuiltInRegistries.ITEM.getValue(jukeboxPacket.record());
        if (item == null) {
            return;
        }

        JukeboxSong.fromStack(item.getDefaultInstance()).ifPresent(holder -> {
            JukeboxSong jukeboxSong = holder.value();
            SoundInstance soundInstance = playingRecords.get(entity);
            if (soundInstance != null) {
                minecraft.getSoundManager().stop(soundInstance);
                playingRecords.remove(entity);
            }

            minecraft.gui.hud.setNowPlaying(jukeboxSong.description());
            MovingSound movingSound = new MovingSound(jukeboxSong.soundEvent().value(), entity);
            playingRecords.put(entity, movingSound);
            minecraft.getSoundManager().play(movingSound);
        });
    }

    public static void play(SoundEvent event, Entity entity) {
        Minecraft.getInstance().getSoundManager().play(new MovingSound(event, entity));
    }

    public static void remove(Entity entity) {
        SoundInstance soundInstance = playingRecords.get(entity);
        if (soundInstance != null) {
            Minecraft.getInstance().getSoundManager().stop(soundInstance);
            playingRecords.remove(entity);
        }
    }
}
