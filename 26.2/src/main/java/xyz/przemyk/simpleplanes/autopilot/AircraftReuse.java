package xyz.przemyk.simpleplanes.autopilot;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;

import java.util.List;
import java.util.UUID;

/**
 * Finds an airframe already parked at a field so a ground departure can fly it instead of building
 * another one.
 *
 * <h2>What this is for</h2>
 * Every sortie used to create an entity and nothing ever removed one: a flight that ends on a stand
 * calls {@code PlaneAutopilot#stop}, which lets go of the runway and the traffic slot and leaves the
 * aircraft standing there for good. A field has at most
 * {@link AutopilotConfig#MAX_PARKING_SPOTS} stands, so a field flown out of and into for an evening
 * silts itself shut: the stands fill with hulks, {@code Airfield#arrivalStand} returns null for every
 * later arrival, and they stop on the runway. Reusing one of those hulks is the only fix that does
 * not involve deleting an aircraft, and deleting is not on the table — an autopilot sortie is the
 * mod's freight story, so an aircraft that lands and evaporates destroys whatever it was carrying.
 *
 * <h2>What may be claimed, and why a stand booking is the right identity</h2>
 * Only an aircraft that {@link StandOccupancy} has a booking for. That is not a convenience, it is
 * the whole safety argument.
 *
 * <p>A landed autopilot aircraft's NBT is indistinguishable from a plane a player crafted, flew there
 * and parked — {@code PlaneAutopilot#save} writes nothing once the flight is over — so there is no
 * marker on the entity to read, and adding one is a trap: {@code addAdditionalSaveData} also backs
 * {@code PlaneEntity#getItemStack}, so a "fleet" flag would ride into the item a destroyed aircraft
 * drops and out again into a player's inventory.
 *
 * <p>A booking has none of that problem, because it is not on the aircraft. It is written in exactly
 * two places, {@code PlaneAutopilot#finishTaxiIn} and {@code HelicopterAutopilot}, both of which run
 * only at the end of a flight this mod dispatched. <b>An aircraft with a booking is therefore an
 * aircraft this mod flew onto that square itself.</b> A player's own plane parked on a marked stand
 * has no booking and can never be claimed; if a player flies a fleet aircraft away, the booking is
 * released the first time anything looks at the stand and sees the aircraft elsewhere.
 *
 * <h2>Entities load late, and this is written so that it does not matter</h2>
 * The hard part of reuse is that {@code AutopilotSpawner#launchSortie} is synchronous and entity
 * deserialisation is not. {@code loadAirfield} pulls the stands' chunks in with a blocking
 * {@code getChunk} and the parking decision is made a few statements later in the same tick — but a
 * chunk gives back its blocks synchronously and its entities a tick or more later, which is the
 * measurement {@link StandOccupancy} was built around and the reason
 * {@code EMPTY_CONFIRM_TICKS} is not zero. So on a cold field the aircraft a claim would want is
 * simply not in the level yet, and no amount of loading inside that tick brings it there.
 *
 * <p>The answer here is not to wait, it is to <b>never claim what cannot be seen</b>. A claim is made
 * only from a booking that resolves through {@code ServerLevel#getEntity} <em>now</em>, on an
 * aircraft that is alive, idle, empty and of the right airframe. There are exactly two outcomes and
 * both are correct:
 *
 * <ul>
 *   <li><b>The field's entities are loaded</b> — a player is at it, a flight has just used it, or
 *       another sortie was ordered out of it within the last {@code loadAirfield} ticket — and the
 *       parked airframe is claimed and re-tasked.</li>
 *   <li><b>They are not</b>, and nothing is claimed. This case used to be the dangerous one: the
 *       stand read free and the new aircraft was spawned inside the parked one. It no longer is,
 *       because the booking is persisted and {@code StandOccupancy#isTaken} answers "taken" for a
 *       stand it cannot see, so the fresh aircraft is placed on a different stand. Reuse simply does
 *       not fire, and the outcome is the pre-existing one.</li>
 * </ul>
 *
 * <p>What is given up by refusing to wait is real and worth stating: on a field nobody has been near,
 * a sortie still creates an aircraft, so accumulation is slowed rather than stopped. The alternative
 * — booking the sortie now and starting it twenty ticks later once the entity has materialised — was
 * considered and rejected. It would make {@code /autopilot flight} stop returning the aircraft it
 * launched, and that line ("Plane #1 parked at airfield-1 …") is the identifier every recipe in
 * {@code TESTING.md} reads before it polls anything; it would also introduce a window in which a
 * server shutdown loses a sortie silently, with nothing durable to recover it from and no dispatcher
 * in this mod to own such a queue. Trading a guaranteed contract for an opportunistic one is the
 * wrong way round.
 *
 * <h2>What a claimed airframe brings with it</h2>
 * {@link #scrub} makes it indistinguishable from a fresh spawn wherever that is possible, because
 * today's behaviour <em>is</em> a fresh spawn and reuse should not be a behaviour change: the
 * airframe is repaired to full, its controls are centred, its motion and attitude are zeroed. What
 * it keeps is its upgrades and whatever is in them, which is the point — a transport aircraft that
 * arrived with cargo is the same aircraft when it leaves. What it may never have is a passenger:
 * an aircraft carrying anything, a player or the livestock a large airframe collects on the ground,
 * is not claimed at all.
 */
public final class AircraftReuse {

    /** An airframe taken off a stand, and the stand it was taken off, for the launch report. */
    public record Claimed(PlaneEntity plane, BlockPos stand) {}

    private AircraftReuse() {}

    /**
     * An idle airframe parked at this field that this departure may fly, or null when there is
     * none that can be seen and used right now.
     *
     * <p>The booking of whatever is returned is released, because the aircraft is leaving the stand.
     * Nothing books the square it is going to: a departure is protected by the live entity search
     * and by the chunk ticket it holds while it sits there, which is the rule departures already
     * followed before this existed.
     *
     * @param field  the airfield or helipad name the bookings are filed under
     * @param stands the squares to consider, in the field's own order
     * @param wanted the airframe the sortie was ordered with; {@code RANDOM} takes any fixed-wing
     * @param spawn  where the aircraft is about to be put, so the nearest candidate wins and so the
     *               square can be checked for anything other than the candidate itself
     */
    public static @Nullable Claimed claim(ServerLevel level, String field, List<BlockPos> stands,
                                          AircraftType wanted, Vec3 spawn) {
        PlaneEntity best = null;
        BlockPos bestStand = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos stand : stands) {
            UUID booked = StandOccupancy.heldBy(level, field, stand);
            // No booking means either an empty stand or a player's own aircraft standing on it. Both
            // are the same answer here, and it is the answer that keeps this safe.
            if (booked == null || !(level.getEntity(booked) instanceof PlaneEntity plane)) {
                continue;
            }
            if (!claimable(plane, wanted)) {
                continue;
            }
            // Nearest to where it is going, so the shortest tow across the apron wins. Only a
            // tie-break: every candidate is equally flyable.
            double distance = AutopilotMath.horizontalDistance(plane.position(), spawn);
            if (distance < bestDistance) {
                best = plane;
                bestStand = stand;
                bestDistance = distance;
            }
        }
        if (best == null) {
            return null;
        }
        // The departure square has to be clear of everything except the candidate itself. It nearly
        // always is -- Airfield#parkingPosition only returns a square it found free -- but its last
        // resort walks down the runway and gives up when the take-off run runs out, and that one
        // branch can hand back an occupied square. Moving an airframe into another airframe is the
        // one outcome this whole feature must not produce, so it is checked rather than assumed;
        // failing it falls through to the ordinary spawn, which is what happens today.
        if (!Airfield.standFree(level, spawn, null, best)) {
            return null;
        }
        StandOccupancy.release(level, field, bestStand);
        return new Claimed(best, bestStand);
    }

    /** Whether this parked aircraft may be re-tasked as {@code wanted}. */
    private static boolean claimable(PlaneEntity plane, AircraftType wanted) {
        if (plane.isRemoved() || !plane.isAlive()) {
            return false;
        }
        // Anything aboard, and it is not ours to move: a player sitting in it, or the livestock a
        // large airframe picks up from the ground on every tick it spends parked near animals.
        if (plane.isVehicle()) {
            return false;
        }
        PlaneAutopilot autopilot = plane.getAutopilot();
        if (autopilot != null && autopilot.isActive()) {
            return false;
        }
        AircraftType actual = AircraftType.of(plane);
        if (actual == null) {
            return false;
        }
        // Never across airframes. The three fixed-wing types differ by 5:1 in rotation rate and the
        // arrival geometry is sized from it, so a sortie ordered "type cargo" must not quietly get a
        // starter plane because one happened to be parked. RANDOM asked for any of the three and is
        // the one case where a substitution is what was ordered.
        return wanted == AircraftType.RANDOM ? !actual.isRotorcraft() : actual == wanted;
    }

    /**
     * Undoes the last flight, so the airframe leaves in the state a new one would have been born in.
     *
     * <p>Repaired rather than screened on health, and the reason is that reuse should be invisible:
     * a fresh spawn departs at full health, so any rule that dispatched a damaged airframe would be a
     * change in what {@code /autopilot flight} does rather than a change in how it gets an aircraft.
     * Screening on full health instead would have retired an airframe permanently on its first firm
     * landing, which is most of them.
     *
     * <p>Deliberately does <b>not</b> touch upgrades or their contents. Placement and heading are the
     * caller's, because that is the spawner's own rule about the quaternion the physics reads.
     */
    public static void scrub(PlaneEntity plane) {
        plane.setAutopilot(null);
        plane.setHealth(plane.getMaxHealth());
        plane.setDeltaMovement(Vec3.ZERO);
        plane.setThrottle(0);
        plane.setPitchUp((byte) 0);
        plane.setYawRight((byte) 0);
        plane.setXRot(0.0f);
        plane.rotationRoll = 0.0f;
        plane.prevRotationRoll = 0.0f;
    }
}
