package xyz.przemyk.simpleplanes.missile;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * Every visible effect of the feature, and nothing else: particles and sounds only. There is deliberately no
 * explosion, damage, fire or block change anywhere in here, and nothing calls the mod's blast code.
 */
public final class MissileFx {

    private MissileFx() {}

    /** Smoke from the silo mouth: the motor flame is hidden inside the shaft, so this carries the launch. */
    public static void vent(ServerLevel level, Vec3 mouth, MissileTier tier, double strength) {
        if (strength <= 0) return;
        double r = tier.footprint * 0.35;
        int n = (int) Math.ceil(strength * (2 + tier.tier));
        level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, true, true, mouth.x, mouth.y + 0.2, mouth.z, n, r, 0.1, r, 0.02);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, true, true, mouth.x, mouth.y + 0.3, mouth.z, n * 2, r, 0.2, r, 0.08);
        if (strength >= 1.0) level.sendParticles(ParticleTypes.CLOUD, true, true, mouth.x, mouth.y + 0.1, mouth.z, n, r * 2, 0.1, r * 2, 0.15);
    }

    /** The end of every flight, whatever ended it: a puff of smoke and particles, a firework bang. */
    public static void puff(ServerLevel level, Vec3 at, MissileTier tier) {
        double s = 0.5 + 0.35 * tier.tier;
        level.sendParticles(ParticleTypes.EXPLOSION, true, true, at.x, at.y, at.z, 1 + tier.tier, s * 0.5, s * 0.5, s * 0.5, 0.0);
        level.sendParticles(ParticleTypes.CLOUD, true, true, at.x, at.y, at.z, 20 * tier.tier, s, s, s, 0.08);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, true, true, at.x, at.y, at.z, 16 * tier.tier, s, s, s, 0.05);
        level.sendParticles(ParticleTypes.POOF, true, true, at.x, at.y, at.z, 12 * tier.tier, s, s, s, 0.12);
        level.sendParticles(ParticleTypes.FIREWORK, true, true, at.x, at.y, at.z, 10 * tier.tier, s * 0.6, s * 0.6, s * 0.6, 0.2);
        level.playSound(null, at.x, at.y, at.z,
            tier.tier >= 3 ? SoundEvents.FIREWORK_ROCKET_LARGE_BLAST : SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.NEUTRAL, 6.0F, 0.8F);
    }

    /** Tier 4 staging: the spent booster vanishes in a small cloud. */
    public static void staging(ServerLevel level, Vec3 at) {
        level.sendParticles(ParticleTypes.CLOUD, true, true, at.x, at.y, at.z, 30, 0.5, 0.5, 0.5, 0.05);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, true, true, at.x, at.y, at.z, 20, 0.5, 0.5, 0.5, 0.03);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.NEUTRAL, 3.0F, 1.4F);
    }
}
