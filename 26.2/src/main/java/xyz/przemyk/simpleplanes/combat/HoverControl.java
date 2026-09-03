package xyz.przemyk.simpleplanes.combat;

import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * <b>The swap point.</b> Everything the gunship needs from a rotorcraft flight director, and nothing
 * else.
 *
 * <p>The gunship is a weapon system, not an autopilot. It needs the machine to climb to a height,
 * stay there while it shoots, and put itself back on the ground afterwards. That is the whole
 * contract, and it is stated in <em>quantities</em> — a wanted altitude, a wanted heading, "are you
 * there yet" — with no throttle, no collective, no notch and no tick constant anywhere in it. A
 * controller that flies the machine some completely different way still satisfies it.
 *
 * <p>{@link CollectiveHover} is the stopgap implementation that ships with this feature: an
 * integrating collective loop and nothing more. It exists because the pad-to-pad helicopter
 * autopilot is being written in parallel, and this feature could not wait for it. When that
 * controller lands, implement this interface in front of it and change the one line in
 * {@link GunshipSortie} that constructs a {@link CollectiveHover}; nothing else in
 * {@code combat/} knows how the aircraft is flown.
 *
 * <p>Deliberately <em>not</em> in this interface, because the gunship does not need them and
 * guessing at them would be inventing a second autopilot: waypoints, cruise speed, terrain
 * following, obstacle avoidance, runways, and any notion of horizontal translation beyond
 * {@link #faceTowards}.
 */
public interface HoverControl {

    /**
     * Hold this absolute Y, converging from wherever the aircraft is. Called every tick; the value
     * may change every tick (the descent walks it down).
     */
    void holdAltitude(double y);

    /**
     * Point the nose this way if the airframe can. Cosmetic for the gunship — the firing solution is
     * computed in world space and does not care where the nose is — so an implementation that
     * cannot yaw may ignore it.
     */
    void faceTowards(double headingDegrees);

    /**
     * True when the aircraft is within tolerance of the commanded altitude <em>and</em> settled
     * there, i.e. it is a stable weapons platform rather than merely passing through the right
     * height.
     */
    boolean onStation();

    /** Vertical speed, blocks/tick, positive up. Reported so the sortie can log an honest touchdown. */
    double verticalSpeed();

    /** Height of the aircraft above the ground under it, in blocks. Reporting only. */
    double heightAboveGround();

    /**
     * Stop holding an altitude and put the aircraft on the ground under it. Idempotent; call it once
     * and then keep calling {@link #tick}.
     */
    void descendAndLand();

    /**
     * The outcome of {@link #descendAndLand}, or null while it is still being attempted. A landing
     * that cannot be completed must be reported as a failure with a reason, never as a success.
     */
    @Nullable Landing landing();

    /** One tick of control. Must be called before the aircraft's own tick, not after. */
    void tick();

    /**
     * @param landed whether the aircraft came to rest on the ground under control
     * @param where  where it ended up
     * @param reason human-readable; on a failure it says what stopped it
     */
    record Landing(boolean landed, Vec3 where, String reason) {}
}
