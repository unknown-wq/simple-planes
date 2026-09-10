package xyz.przemyk.simpleplanes.autopilot;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The autopilot's dispatcher: the durable, tick-driven schedule of departures that fly themselves.
 *
 * <h2>What this is</h2>
 * Everything else in this package flies an aircraft that something else decided to launch — a
 * command, a tool, a datapack function. This is the thing that decides. It owns a per-dimension set
 * of scheduled departures, persisted in {@link AutopilotSavedData}, and services it from the level
 * tick: when a departure comes due it loads what it needs, launches the leg, and books the next one
 * from the arrival.
 *
 * <p>There is exactly one kind of schedule today, the {@link Shuttle} — one aircraft between two
 * airfields for ever, with a turnaround wait at each end — and the split between the two classes is
 * where a second kind would go. {@link Shuttle} is state and knows no rules; every rule about when a
 * leg is due, what may fly it and what happens when it cannot is here.
 *
 * <p><b>This used to be documented as something the mod deliberately did not have.</b>
 * {@code AUTOPILOT.md} said there was no dispatcher and {@link AircraftReuse} declined to keep a
 * durable queue of pending flights on that basis. The prohibition is gone; the reasoning it carried
 * is not all wrong and is restated where it still applies, but the premise has changed and the docs
 * have been corrected rather than worked around.
 *
 * <h2>What is still bounded, and why</h2>
 * The bounds were always about load rather than about principle, so they stay and they are tight.
 * At most {@link AutopilotConfig#MAX_SHUTTLES} schedules per dimension. One aircraft per schedule,
 * reused rather than rebuilt. One pending departure per schedule, never a backlog. Chunk tickets
 * that are dropped the moment the thing holding them stops. And every failure ends in something a
 * player can read — see {@link #defer} and {@link #pause} — because a machine that runs unattended
 * for hours and retries in silence is indistinguishable from one that is broken.
 *
 * <h2>Unattended operation, which is the hard part</h2>
 * A shuttle runs between fields nobody is standing near, so every state it can be in has to work
 * with no player in the world. Three of the four do already:
 *
 * <ul>
 *   <li><b>In the air</b> — nothing new is needed. A flying autopilot aircraft carries its own chunk
 *       bubble, renewed from {@link AutopilotRegistry} on the level tick, and the terrain following
 *       depends on it. A leg flies itself.</li>
 *   <li><b>Taxiing</b> — the same, it is still an active autopilot.</li>
 *   <li><b>Waiting on a stand</b> — this is the gap, and this class closes it. The moment a flight
 *       ends the aircraft is unregistered, its ticket lapses within 40 ticks, and the chunk unloads
 *       unless a player happens to be near. A parked aircraft in an unloaded chunk cannot be
 *       resolved, so it cannot be re-tasked, so the departure would either not happen or would have
 *       to build a second aircraft — and the second of those is how a world ends up with a hundred
 *       derelict planes. So a waiting schedule keeps a small ticket alive over its own parked
 *       aircraft, renewed on the level tick beside the flight tickets, for as long as it is waiting
 *       and no longer. See {@link #hold} for the cost and the limits.</li>
 *   <li><b>Paused</b> — nothing is held and nothing is retried.</li>
 * </ul>
 *
 * <p><b>What that does to the reuse argument.</b> {@link AircraftReuse}'s javadoc says reuse "does
 * not fire on a field nobody has been near", because the parked airframe is not in the level to be
 * seen. For an aircraft a schedule owns that stops being true: the chunk it is standing in has been
 * held loaded by this class for the whole turnaround, so the entity is resolvable in the tick the
 * departure is launched. The next reader will have read that javadoc first, so it is worth saying
 * plainly — the general case is unchanged, and only an aircraft a schedule is actively waiting on
 * gets this treatment.
 *
 * <p><b>A restart still starts cold.</b> Nothing holds a chunk across a shutdown. On load the
 * aircraft is on disk in an unloaded chunk, and the answer is not to build a new one: the schedule
 * persisted {@link Shuttle#parked}, so the first hold tick after load puts the ticket back over that
 * chunk and the aircraft deserialises a tick or two later. If a departure is due in that window,
 * {@link AutopilotConfig#SHUTTLE_WAKE_TICKS} gives the load five seconds to complete before the
 * departure is judged to have failed.
 *
 * <h2>Cost</h2>
 * A dimension with no schedules costs one map lookup and an {@code isEmpty}, every
 * {@link AutopilotConfig#SHUTTLE_CHECK_INTERVAL} ticks. With schedules, the state machine is a
 * comparison of a stored game time against the clock, and on the tick a departure is due it is that
 * plus one {@code getEntity}. The ticket renewal runs every
 * {@link AutopilotConfig#SHUTTLE_HOLD_INTERVAL} ticks and is one {@code addTicketWithRadius} per
 * waiting schedule.
 *
 * <p><b>One path is much more expensive than that, and it is fenced off rather than described
 * away.</b> {@code AutopilotSpawner#loadAirfield} makes a whole field resident with a blocking
 * {@code getChunk} per chunk of the strip and per stand — on a 180-block field, something like 120
 * of them. This class used to call it on every service tick a departure was due, and again on every
 * retry, for a field whose aircraft was already loaded and resolvable. It is now reached only when
 * the airframe cannot be found without it: the first departure after a restart, where the field
 * genuinely is cold and the alternative is building a second aircraft. See {@link #serviceWaiting}.
 */
public final class AutopilotDispatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger("simpleplanes-autopilot");

    private AutopilotDispatcher() {}

    public static void init() {
        ServerTickEvents.END_LEVEL_TICK.register(AutopilotDispatcher::onLevelTick);
    }

    /**
     * The level tick, and the one place in this class that must not be allowed to throw.
     *
     * <p>Everything below it loads chunks, resolves entities and spawns aircraft, on a field nobody
     * is watching, hours into a run. An exception escaping here does not stop a shuttle — it stops
     * the <em>level tick</em>, and a schedule nobody asked for taking the world down with it is a
     * far worse outcome than any fault it could be reporting. So each schedule's work is fenced
     * separately: a shuttle that throws is logged with its id and paused, the rest of the dimension
     * carries on, and the tick returns.
     *
     * <p>Paused rather than retried, and this is the one place where that is the mild answer: an
     * exception is a defect, the next tick would hit it again a second later, and an hour of the
     * same stack trace once a second is how a log becomes useless. The record and its reason stay in
     * {@code /autopilot shuttle list}.
     */
    private static void onLevelTick(ServerLevel level) {
        long now = level.getGameTime();
        boolean hold = now % AutopilotConfig.SHUTTLE_HOLD_INTERVAL == 0;
        boolean service = now % AutopilotConfig.SHUTTLE_CHECK_INTERVAL == 0;
        if (!hold && !service) {
            return;
        }
        AutopilotSavedData data = AutopilotSavedData.get(level);
        if (!data.hasShuttles()) {
            return;
        }
        if (hold) {
            // Read-only pass over the live collection: renewing a ticket does not change a shuttle.
            for (Shuttle shuttle : data.shuttles()) {
                try {
                    hold(level, shuttle);
                } catch (Exception e) {
                    LOGGER.error("Shuttle {} in {}: could not renew its chunk hold",
                        shuttle.id(), level.dimension().identifier(), e);
                }
            }
        }
        if (service) {
            // Copied, because servicing rewrites the map it is walking.
            for (Shuttle shuttle : data.shuttleList()) {
                try {
                    service(level, data, shuttle, now);
                } catch (Exception e) {
                    LOGGER.error("Shuttle {} in {}: failed while being serviced; pausing it",
                        shuttle.id(), level.dimension().identifier(), e);
                    fail(level, data, shuttle, e);
                }
            }
        }
    }

    /**
     * Pauses a shuttle that threw, without giving the pause a chance to throw as well.
     *
     * <p>{@link #pause} removes a chunk ticket, writes saved data and talks to a player, all of
     * which is more than a handler for an unknown fault should be trusting. If even that fails there
     * is nothing left to do but keep the tick alive; the error above it has already been logged.
     */
    private static void fail(ServerLevel level, AutopilotSavedData data, Shuttle shuttle, Exception cause) {
        try {
            pause(level, data, shuttle, "an internal error while servicing it: " + cause);
        } catch (Exception e) {
            LOGGER.error("Shuttle {}: could not even be paused", shuttle.id(), e);
        }
    }

    // ------------------------------------------------------------------ the chunk hold

    /**
     * Keeps a waiting shuttle's parked aircraft loaded, so it can be found again when it is due out.
     *
     * <p>The one thing this feature adds to the world's load, and the terms are deliberately narrow.
     * <b>One aircraft, one waiting shuttle</b> — not the airfield, not stands in general, which is
     * the thing the base branch ruled out and which would put a permanent ticket on every square any
     * aircraft has ever parked on. It is dropped the moment the wait ends, whether that is a
     * departure, a pause or {@code /autopilot shuttle stop}, rather than being left to expire
     * unnoticed; see {@link #release}.
     *
     * <p>What it costs, plainly: {@link AutopilotConfig#SHUTTLE_HOLD_RADIUS} entity-ticks a 3x3 block
     * of chunks around the stand, so a running shuttle keeps nine chunks resident at whichever end it
     * is currently waiting at, and at {@link AutopilotConfig#MAX_SHUTTLES} that is at most four such
     * areas at any instant. A shuttle in the air holds none of them — its aircraft's own bubble is
     * the flight's, and it moves with it.
     */
    private static void hold(ServerLevel level, Shuttle shuttle) {
        BlockPos parked = shuttle.parkedAt();
        if (shuttle.state() != Shuttle.State.WAITING || parked == null) {
            return;
        }
        level.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL,
            ChunkPos.containing(parked), AutopilotConfig.SHUTTLE_HOLD_RADIUS);
    }

    /**
     * Drops the hold now rather than waiting for the ticket to time out.
     *
     * <p>{@code TicketType.ENDER_PEARL} expires 40 ticks after it was last placed, so simply ceasing
     * to renew would also work and would be invisible for two seconds. Removing it is the honest
     * version: a player who stops a shuttle has said the world may unload that ground again, and a
     * hold that outlives the thing holding it is exactly the kind of leak this feature must not
     * introduce.
     */
    private static void release(ServerLevel level, Shuttle shuttle) {
        BlockPos parked = shuttle.parkedAt();
        if (parked != null) {
            level.getChunkSource().removeTicketWithRadius(TicketType.ENDER_PEARL,
                ChunkPos.containing(parked), AutopilotConfig.SHUTTLE_HOLD_RADIUS);
        }
    }

    // ------------------------------------------------------------------ the state machine

    private static void service(ServerLevel level, AutopilotSavedData data, Shuttle shuttle, long now) {
        if (shuttle.state() == Shuttle.State.PAUSED) {
            return;
        }
        if (shuttle.state() == Shuttle.State.FLYING) {
            // Deliberately no "do both fields still exist" test here. It used to run for every
            // state, which meant a rename made mid-leg paused the schedule while its aircraft was
            // still in the air: the leg went on flying, nothing was waiting for it at the far end,
            // and it landed owned by nothing. Pausing it did not stop it and could not. A leg in the
            // air is finished first and judged afterwards -- serviceFlying and #arrived both work
            // from where the aircraft actually ends up, and both already refuse a field that is not
            // one of this shuttle's two, which is what a rename produces.
            serviceFlying(level, data, shuttle, now);
            return;
        }
        // Both ends have to still exist, and this is checked every second rather than only at a
        // departure, so a player who removes or renames an airfield finds out what it did to the
        // shuttle immediately instead of a turnaround later. A rename is a removal as far as this is
        // concerned: AirfieldBrowser#rename re-files the field under the new key, and a shuttle
        // records names rather than references precisely so that it can say so.
        Airfield a = data.get(shuttle.fieldA());
        Airfield b = data.get(shuttle.fieldB());
        if (a == null || b == null) {
            pause(level, data, shuttle, "airfield \"" + (a == null ? shuttle.fieldA() : shuttle.fieldB())
                + "\" no longer exists (removed, or renamed under it)");
            return;
        }
        serviceWaiting(level, data, shuttle, a, b, now);
    }

    /**
     * A shuttle standing at one end. Departs when it is due, and only ever with its own airframe.
     */
    private static void serviceWaiting(ServerLevel level, AutopilotSavedData data, Shuttle shuttle,
                                       Airfield a, Airfield b, long now) {
        if (now < shuttle.nextDeparture()) {
            return;
        }
        Airfield from = shuttle.outbound() ? a : b;
        Airfield to = shuttle.outbound() ? b : a;
        if (!AutopilotRegistry.canActivateAnother()) {
            // Transient by nature: the slots are shared with every other flight on the server, and
            // whatever is using them will land. Deferred rather than skipped so the leg still flies,
            // and counted so a shuttle cannot spend the evening being crowded out in silence.
            defer(level, data, shuttle, now, "all " + AutopilotConfig.MAX_ACTIVE_AUTOPILOTS
                + " autopilot slots are in use");
            return;
        }

        UUID airframe = shuttle.aircraftId();
        if (airframe == null) {
            // The very first departure, and the only one that may create an aircraft. A shuttle is
            // created by a player asking for one, so building the airframe here is a launch somebody
            // ordered; every departure after this one flies that same airframe or does not fly.
            launch(level, data, shuttle, from, to, now, null);
            return;
        }

        // Look before loading, not after. In the normal case the hold has kept the aircraft's own
        // chunk resident for the whole turnaround, so it resolves here and the field is never walked
        // -- which is the point: loadAirfield is a blocking getChunk per chunk of the strip plus one
        // per stand, roughly 120 of them on a 180-block field, and it used to run on every service
        // tick a departure was due and again on every retry. It is only needed when the aircraft
        // cannot be found, which is the first departure after a restart, where nothing has been
        // loaded by anyone and the stands' entities are still on disk.
        PlaneEntity plane = resolve(level, airframe);
        if (plane == null) {
            AutopilotSpawner.loadAirfield(level, from);
            plane = resolve(level, airframe);
        }
        if (plane == null) {
            if (now - shuttle.nextDeparture() < AutopilotConfig.SHUTTLE_WAKE_TICKS) {
                // Still inside the window the chunk load is allowed to take. Not a failure yet, and
                // deliberately not reported: a normal restart passes through here for a tick or two.
                return;
            }
            // Past the wake window, having just force-loaded the whole field, and the airframe is
            // still not in the level. The first few of these are deferred, because a server coming
            // back up under chunk-load pressure can take longer than the window; past that the
            // honest reading is that the aircraft is gone, which is a fault a player has to act on
            // and not a condition that clears itself. Retrying it for ever would also mean loading
            // an entire airfield, on a blocking getChunk per chunk, every five minutes for the rest
            // of the world's life.
            if (shuttle.misses() + 1 > AutopilotConfig.SHUTTLE_QUIET_MISSES) {
                pause(level, data, shuttle, "its aircraft could not be found at " + from.name()
                    + " after " + (shuttle.misses() + 1) + " attempts; it is gone");
                return;
            }
            defer(level, data, shuttle, now, "its aircraft could not be found at " + from.name()
                + " (the chunk did not load, or the aircraft is gone)");
            return;
        }
        if (plane.isVehicle()) {
            defer(level, data, shuttle, now, "aircraft #" + plane.getId() + " has somebody aboard");
            return;
        }
        PlaneAutopilot flying = plane.getAutopilot();
        if (flying != null && flying.isActive()) {
            // Somebody else is using it -- a player who climbed in and switched the autopilot on,
            // or anything else that starts a flight on an existing airframe. It is no longer
            // /autopilot flight: the opportunistic reuse claim now asks #owns first and leaves a
            // schedule's aircraft alone. Transient either way, so it is deferred: that flight will
            // end, and if it ends somewhere else the position check below catches it.
            defer(level, data, shuttle, now, "aircraft #" + plane.getId()
                + " is flying another autopilot flight");
            return;
        }
        if (!atField(from, plane)) {
            // Somebody has flown it away, or it diverted and this was not noticed. Not deferred:
            // waiting will not bring it back, and the one thing that must not happen next is a
            // replacement being built.
            pause(level, data, shuttle, "aircraft #" + plane.getId() + " is at "
                + describe(plane.position()) + ", not at " + from.name());
            return;
        }
        launch(level, data, shuttle, from, to, now, airframe);
    }

    /**
     * A leg in the air. Nothing to drive — the autopilot flies it — so this only watches for the
     * endings the arrival hook does not cover.
     *
     * <p>{@link #arrived} handles the normal one, called from {@code PlaneAutopilot#finishTaxiIn} at
     * the moment the taxi in completes and the stand booking is written, which is the only moment
     * that is not a guess about when the aircraft has stopped. What is left here is every other way a
     * leg can end: an arrival that found no free stand and stopped on the runway without ever
     * starting a taxi, an approach that gave up, {@code /autopilot stop} typed at the aircraft, and
     * the aircraft being destroyed.
     */
    private static void serviceFlying(ServerLevel level, AutopilotSavedData data, Shuttle shuttle, long now) {
        UUID airframe = shuttle.aircraftId();
        PlaneEntity plane = airframe == null ? null : resolve(level, airframe);
        if (plane == null) {
            // A flying autopilot aircraft renews its own chunk ticket every five ticks, so it is
            // resolvable on essentially every tick of its flight. Not finding it means it is gone.
            // Counted rather than believed at once, so a single tick of chunk handover cannot end a
            // shuttle; see SHUTTLE_LOST_TICKS.
            int misses = shuttle.misses() + 1;
            if ((long) misses * AutopilotConfig.SHUTTLE_CHECK_INTERVAL >= AutopilotConfig.SHUTTLE_LOST_TICKS) {
                pause(level, data, shuttle, "its aircraft was lost in flight between "
                    + shuttle.from() + " and " + shuttle.to());
            } else {
                data.putShuttle(shuttle.withMisses(misses));
            }
            return;
        }
        PlaneAutopilot autopilot = plane.getAutopilot();
        if (autopilot != null && autopilot.isActive()) {
            if (shuttle.misses() != 0) {
                data.putShuttle(shuttle.withMisses(0));
            }
            return;
        }
        // Present and idle: the leg ended and the arrival hook did not fire. Believe where the
        // aircraft actually is rather than where it was sent -- the same rule StandOccupancy uses
        // about a booking -- so an arrival that stopped on the runway starts its turnaround and gets
        // lifted off it by the next departure, which is the only thing that clears it.
        Airfield here = fieldAt(data, shuttle, plane);
        if (here == null) {
            pause(level, data, shuttle, "aircraft #" + plane.getId() + " ended its leg to "
                + shuttle.to() + " at " + describe(plane.position()) + ", at neither airfield");
            return;
        }
        finish(level, data, shuttle, here.name(), plane, now,
            "leg to " + shuttle.to() + " ended at " + here.name() + " without parking on a stand");
    }

    /** Launches one leg. {@code airframe} is null only for a shuttle's very first departure. */
    private static void launch(ServerLevel level, AutopilotSavedData data, Shuttle shuttle,
                               Airfield from, Airfield to, long now, @Nullable UUID airframe) {
        // The sortie's own departure delay is zero: the shuttle's wait has already happened, on the
        // stand, and stacking a second one on top would double every turnaround.
        PlaneEntity plane = AutopilotSpawner.launchSortie(level, from, to, owner(level, shuttle),
            AutopilotConfig.CRUISE_SPEED, Blast.DEFAULT, 0, shuttle.type(), airframe);
        if (plane == null) {
            defer(level, data, shuttle, now, airframe == null
                ? "the aircraft could not be created"
                : "its aircraft could not be put on a departure spot at " + from.name());
            return;
        }
        // The hold goes now rather than when the ticket lapses. From here the aircraft is an active
        // autopilot and carries its own bubble.
        release(level, shuttle);
        data.putShuttle(shuttle.departed(plane.getUUID(), now));
        progress(level, shuttle, "Shuttle " + shuttle.id() + ": plane #" + plane.getId()
            + " departing " + from.name() + " for " + to.name() + ".");
    }

    /**
     * Ends a leg: the aircraft is standing at {@code airfield} and the next departure leaves from
     * there after the turnaround.
     */
    private static void finish(ServerLevel level, AutopilotSavedData data, Shuttle shuttle,
                               String airfield, PlaneEntity plane, long now, String problem) {
        if (!shuttle.uses(airfield)) {
            pause(level, data, shuttle, "aircraft #" + plane.getId() + " ended up at \"" + airfield
                + "\", which is not one of this shuttle's two airfields");
            return;
        }
        Shuttle updated = shuttle.arrivedAt(airfield, plane.blockPosition(), now, problem);
        data.putShuttle(updated);
        // Immediately, not on the next hold tick: between here and then the aircraft is unregistered
        // and its own ticket is expiring, and the gap is what would let the chunk unload.
        hold(level, updated);
        // One line per leg, which on a shuttle whose turnaround is measured in minutes is not
        // chatter. It is the line that says the cycle is still turning: the arrival report beside it
        // is about one flight, and a shuttle that has quietly stopped between legs looks exactly
        // like one whose next departure has not come round yet.
        progress(level, shuttle, "Shuttle " + shuttle.id() + ": plane #" + plane.getId() + " down at "
            + airfield + (problem.isEmpty() ? "" : " (" + problem + ")") + ", leaving for "
            + updated.to() + " in " + updated.delayTicks() / 20 + "s.");
    }

    /**
     * A departure that could not be flown this time: try again later, remember why, and say so
     * until saying so stops being news.
     *
     * <p><b>This no longer gives up.</b> It used to pause the shuttle for good after
     * three consecutive failures, which read as "a thing that runs unattended must not retry for
     * ever" — but every condition that reaches this method is transient by construction. The
     * autopilot slots are full and whatever is using them will land; somebody is sitting in the
     * aircraft and will get out; another sortie is flying the airframe and will finish. Three
     * failures thirty seconds apart is ninety seconds of a busy evening, and on a dimension running
     * the full {@link AutopilotConfig#MAX_SHUTTLES} schedules the slots being full is the ordinary
     * case; a shuttle that stopped there stayed stopped, because {@code PAUSED} does not clear
     * itself and there is no verb to clear it. The permanent answer to a temporary problem was the
     * defect, not the retrying.
     *
     * <p>What is bounded instead is the cost of retrying. The interval grows with the miss count to
     * {@link AutopilotConfig#SHUTTLE_MAX_BACKOFF} times {@link AutopilotConfig#SHUTTLE_RETRY_TICKS},
     * so a condition nobody clears settles at one attempt every five minutes; and the report stops
     * after {@link AutopilotConfig#SHUTTLE_QUIET_MISSES}, so a stuck shuttle is not still telling a
     * player about it at midnight. Neither of those hides anything: the reason and the attempt count
     * are in the record, {@code /autopilot shuttle list} prints both, and the shuttle departs on its
     * own within one interval of whatever was wrong being fixed.
     *
     * <p>{@link #pause} is still there and is still reached — by the faults that are <em>not</em>
     * transient: the field is gone, the aircraft is gone, the aircraft is somewhere else.
     */
    private static void defer(ServerLevel level, AutopilotSavedData data, Shuttle shuttle, long now,
                              String problem) {
        int misses = shuttle.misses() + 1;
        long wait = (long) AutopilotConfig.SHUTTLE_RETRY_TICKS
            * Math.min(misses, AutopilotConfig.SHUTTLE_MAX_BACKOFF);
        data.putShuttle(shuttle.deferred(now + wait, problem));
        if (misses <= AutopilotConfig.SHUTTLE_QUIET_MISSES) {
            report(level, shuttle, "Shuttle " + shuttle.id() + " could not depart " + shuttle.from()
                + ": " + problem + ". Retrying in " + wait / 20 + "s"
                + (misses == AutopilotConfig.SHUTTLE_QUIET_MISSES
                    ? ", and quietly after this; /autopilot shuttle list keeps the reason." : "."));
        }
    }

    /** Stops a shuttle for good, keeping the record and the reason so a player can see both. */
    private static void pause(ServerLevel level, AutopilotSavedData data, Shuttle shuttle, String problem) {
        release(level, shuttle);
        data.putShuttle(shuttle.paused(problem));
        report(level, shuttle, "Shuttle " + shuttle.id() + " (" + shuttle.fieldA() + " <-> "
            + shuttle.fieldB() + ") has stopped: " + problem
            + ". /autopilot shuttle list shows it; /autopilot shuttle stop " + shuttle.id()
            + " removes it.");
    }

    // ------------------------------------------------------------------ hooks

    /**
     * A taxi in has completed, which is where a turnaround starts.
     *
     * <p>Called from {@code PlaneAutopilot#finishTaxiIn} — the moment the aircraft is on its stand
     * and the stand booking has been written — rather than from anything that guesses when it has
     * stopped moving. A no-op for every aircraft that is not the airframe of a flying shuttle, which
     * is nearly all of them, and cheap to establish: one saved-data lookup and a walk of at most
     * {@link AutopilotConfig#MAX_SHUTTLES} records.
     *
     * @param airfield the field it landed at, which is the field the next leg departs from
     * @param problem  empty when it parked on a stand, otherwise what was wrong with the arrival
     */
    public static void arrived(PlaneEntity plane, String airfield, String problem) {
        if (!(plane.level() instanceof ServerLevel level)) {
            return;
        }
        AutopilotSavedData data = AutopilotSavedData.get(level);
        if (!data.hasShuttles()) {
            return;
        }
        Shuttle shuttle = flying(data, plane.getUUID());
        if (shuttle == null) {
            return;
        }
        finish(level, data, shuttle, airfield, plane, level.getGameTime(), problem);
    }

    /**
     * Whether a schedule in this dimension owns this airframe, and so nothing else may re-task it.
     *
     * <p>Asked by {@link AircraftReuse} before it takes an airframe off a stand. A waiting shuttle's
     * aircraft passes every test that class makes — parked, idle, empty, booked onto a stand, of the
     * right type — so without this an ordinary {@code /autopilot flight} out of the same field flew
     * away with it, and the schedule that owned it could only watch: it defers while the other
     * sortie is in the air, and if that sortie ends anywhere else it pauses naming a field its
     * aircraft is not at. On a dimension running the full {@link AutopilotConfig#MAX_SHUTTLES} that
     * is the ordinary case rather than a corner of it.
     *
     * <p><b>A paused schedule owns nothing.</b> It will never fly again without a player stopping
     * and recreating it, and until they do, its airframe is an idle aircraft parked on a stand like
     * any other — which is precisely what reuse is for. Holding an airframe out of the fleet on
     * behalf of a schedule that cannot use it is how a field silts up.
     *
     * <p>Cheap by construction: no entity is resolved and nothing is loaded, just a UUID compared
     * against at most {@link AutopilotConfig#MAX_SHUTTLES} records, and short-circuited on the
     * common case of a dimension with no schedules at all.
     */
    public static boolean owns(ServerLevel level, UUID airframe) {
        AutopilotSavedData data = AutopilotSavedData.get(level);
        if (!data.hasShuttles()) {
            return false;
        }
        for (Shuttle shuttle : data.shuttles()) {
            if (shuttle.state() != Shuttle.State.PAUSED && airframe.equals(shuttle.aircraftId())) {
                return true;
            }
        }
        return false;
    }

    /** The shuttle whose aircraft this is and which believes it is in the air, or null. */
    private static @Nullable Shuttle flying(AutopilotSavedData data, UUID airframe) {
        for (Shuttle shuttle : data.shuttles()) {
            if (shuttle.state() == Shuttle.State.FLYING && airframe.equals(shuttle.aircraftId())) {
                return shuttle;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ the command's side

    /**
     * Creates a shuttle, or returns why it cannot be created.
     *
     * <p>Every refusal here is one that would otherwise become a fault hours later on a field nobody
     * is watching, which is why they are all made at creation time and none of them is a warning.
     * The dimension case is not in the list because it cannot arise: {@link AutopilotSavedData} is
     * per-dimension, so both names are resolved in the source's own dimension and a field in another
     * one is simply not found.
     *
     * @return null on success, with the shuttle written to {@code data}
     */
    public static @Nullable String create(ServerLevel level, String fieldA, String fieldB,
                                          int delaySeconds, AircraftType type, @Nullable Player owner) {
        AutopilotSavedData data = AutopilotSavedData.get(level);
        if (fieldA.equals(fieldB)) {
            return "A shuttle needs two different airfields; \"" + fieldA + "\" is both ends of this one.";
        }
        Airfield a = data.get(fieldA);
        Airfield b = data.get(fieldB);
        if (a == null || b == null) {
            return "No such airfield in this dimension: " + (a == null ? fieldA : fieldB)
                + ". Use /autopilot airfields to list them.";
        }
        // The same two refusals /autopilot flight makes, at both ends, and for a stronger reason
        // here: a single sortie that cannot park is one aircraft on a runway, whereas a shuttle that
        // cannot park is one aircraft on a runway every turnaround for ever.
        for (Airfield airfield : List.of(a, b)) {
            var refusal = AirfieldBrowser.usabilityRefusal(airfield);
            if (refusal == null) {
                refusal = AirfieldBrowser.standsRefusal(airfield);
            }
            if (refusal != null) {
                return refusal.getString();
            }
        }
        // The cap is on schedules that are actually running. It used to count stored records, which
        // meant a paused one -- holding no chunk ticket, flying nothing, and unable to run again --
        // occupied a slot in a cap whose whole justification is resident chunks and autopilot slots;
        // a player whose four shuttles had all paused had to delete the records that said what went
        // wrong before they could create a fifth. Paused records are bounded separately, because a
        // record nobody stops is written to disk for ever.
        int running = 0;
        int paused = 0;
        for (Shuttle shuttle : data.shuttles()) {
            if (shuttle.state() == Shuttle.State.PAUSED) {
                paused++;
            } else {
                running++;
            }
        }
        if (running >= AutopilotConfig.MAX_SHUTTLES) {
            return "Too many shuttles running in this dimension (" + running + "/"
                + AutopilotConfig.MAX_SHUTTLES + "). Stop one with /autopilot shuttle stop <id>.";
        }
        if (paused >= AutopilotConfig.MAX_PAUSED_SHUTTLES) {
            return "There are " + paused + " paused shuttles in this dimension, which is as many as "
                + "are kept. /autopilot shuttle list shows why each of them stopped; clear one with "
                + "/autopilot shuttle stop <id>.";
        }
        data.putShuttle(Shuttle.created(data.nextShuttleId(), fieldA, fieldB, delaySeconds * 20,
            type, owner == null ? null : owner.getUUID(), level.getGameTime()));
        return null;
    }

    /** The id the last {@link #create} used, for the success line. */
    public static int lastCreated(ServerLevel level) {
        List<Shuttle> all = AutopilotSavedData.get(level).shuttleList();
        return all.isEmpty() ? 0 : all.get(all.size() - 1).id();
    }

    /**
     * Stops one shuttle, or every one in this dimension.
     *
     * <p>Removes the record and drops the chunk hold; the aircraft is left standing where it is,
     * exactly as {@code /autopilot stop} leaves a flight's aircraft. Deleting it would destroy
     * whatever it was carrying, which is the same reason {@link AircraftReuse} exists.
     *
     * @return how many were stopped
     */
    public static int stop(ServerLevel level, @Nullable Integer id) {
        AutopilotSavedData data = AutopilotSavedData.get(level);
        int stopped = 0;
        for (Shuttle shuttle : data.shuttleList()) {
            if (id != null && shuttle.id() != id) {
                continue;
            }
            release(level, shuttle);
            data.removeShuttle(shuttle.id());
            stopped++;
        }
        return stopped;
    }

    /**
     * The listing, one shuttle per group of lines.
     *
     * <p>Everything a player needs to tell a healthy shuttle from a stuck one: both fields, which
     * aircraft, which leg, when the next departure is due, and the last thing that went wrong. The
     * aircraft is shown by the entity id every other line of this feature names it by, and falls back
     * to its UUID when it cannot be resolved — which for a waiting shuttle means the hold has failed
     * and is itself worth seeing.
     */
    public static List<String> describe(ServerLevel level) {
        List<Shuttle> all = AutopilotSavedData.get(level).shuttleList();
        List<String> lines = new ArrayList<>();
        long paused = all.stream().filter(shuttle -> shuttle.state() == Shuttle.State.PAUSED).count();
        lines.add((all.size() - paused) + "/" + AutopilotConfig.MAX_SHUTTLES + " shuttles running in "
            + "this dimension" + (paused == 0 ? "." : ", and " + paused + " paused."));
        long now = level.getGameTime();
        for (Shuttle shuttle : all) {
            lines.add("  shuttle " + shuttle.id() + ": " + shuttle.fieldA() + " <-> " + shuttle.fieldB()
                + ", " + shuttle.delayTicks() / 20 + "s turnaround, " + shuttle.legs()
                + (shuttle.legs() == 1 ? " leg flown, " : " legs flown, ") + aircraftOf(level, shuttle));
            lines.add("    " + switch (shuttle.state()) {
                case WAITING -> "waiting at " + shuttle.from() + ", next departure to " + shuttle.to()
                    + " in " + TowerWatch.clock(Math.max(0, shuttle.nextDeparture() - now))
                    // A retried departure looks exactly like a turnaround from here, and the count
                    // is the only thing that tells one from the other: a shuttle that has failed
                    // forty times is stuck, even though it is still trying.
                    + (shuttle.misses() > 0 ? " (retry " + shuttle.misses() + ")" : "");
                case FLYING -> "leg " + (shuttle.legs() + 1) + " in the air, " + shuttle.from()
                    + " to " + shuttle.to();
                case PAUSED -> "PAUSED - /autopilot shuttle stop " + shuttle.id() + " to remove it";
            });
            if (!shuttle.note().isEmpty()) {
                lines.add("    last problem: " + shuttle.note());
            }
        }
        return lines;
    }

    // ------------------------------------------------------------------ helpers

    /** The aircraft, resolved and alive, or null. */
    private static @Nullable PlaneEntity resolve(ServerLevel level, UUID airframe) {
        return level.getEntity(airframe) instanceof PlaneEntity plane
            && plane.isAlive() && !plane.isRemoved() ? plane : null;
    }

    private static String aircraftOf(ServerLevel level, Shuttle shuttle) {
        UUID airframe = shuttle.aircraftId();
        if (airframe == null) {
            return "no aircraft yet";
        }
        PlaneEntity plane = resolve(level, airframe);
        return plane != null ? "plane #" + plane.getId()
            : "plane " + airframe.toString().substring(0, 8) + " (not loaded)";
    }

    /**
     * Whether the aircraft is close enough to this field to be flown out of it.
     *
     * <p>Half the runway plus {@link AutopilotConfig#SHUTTLE_AT_FIELD_MARGIN}, measured from the
     * centre: generous enough for a stand well off the centreline or an arrival that stopped short
     * of one, tight enough that an aircraft somebody has flown to the next valley is not dragged back
     * onto a departure spot.
     */
    private static boolean atField(Airfield field, PlaneEntity plane) {
        return AutopilotMath.horizontalDistance(plane.position(), field.centre())
            <= field.length() / 2.0 + AutopilotConfig.SHUTTLE_AT_FIELD_MARGIN;
    }

    /** Whichever of the shuttle's two fields the aircraft is standing at, or null for neither. */
    private static @Nullable Airfield fieldAt(AutopilotSavedData data, Shuttle shuttle, PlaneEntity plane) {
        // The destination first: an aircraft that has landed is nearly always at it, and two fields
        // close enough to be ambiguous would rather be answered with the one it was sent to.
        Airfield to = data.get(shuttle.to());
        if (to != null && atField(to, plane)) {
            return to;
        }
        Airfield from = data.get(shuttle.from());
        return from != null && atField(from, plane) ? from : null;
    }

    private static String describe(Vec3 position) {
        return Math.round(position.x) + ", " + Math.round(position.y) + ", " + Math.round(position.z);
    }

    /**
     * The player who created the shuttle if they are online, or null so the report goes to the log.
     *
     * <p>Resolved every time rather than held, because a shuttle outlives a session and a stored
     * reference to a logged-out player is a leak. A shuttle created from the console has no owner and
     * every report about it is logged, which is the same rule {@link AutopilotFeedback#report} already
     * applies to a console-launched sortie.
     */
    private static @Nullable Player owner(ServerLevel level, Shuttle shuttle) {
        UUID id = shuttle.ownerId();
        return id == null ? null : level.getServer().getPlayerList().getPlayer(id);
    }

    private static void report(ServerLevel level, Shuttle shuttle, String message) {
        AutopilotFeedback.report(owner(level, shuttle), message);
    }

    /**
     * A leg of the cycle turning over, which is only news to somebody watching for it.
     *
     * <p>A shuttle runs unattended and for ever, so one line per departure and one per arrival is a
     * feed that never ends and that nobody asked to start — the schedule was set up once, days ago.
     * What a player has to see is the schedule going wrong, and that is {@link #defer} and
     * {@link #pause}, both of which still report. The rest is {@code /autopilot shuttle list}, which
     * shows every shuttle's state and reason on demand, and {@code /autopilot debug true} for anybody
     * who wants to watch a cycle live.
     */
    private static void progress(ServerLevel level, Shuttle shuttle, String message) {
        AutopilotFeedback.progress(owner(level, shuttle), message);
    }
}
