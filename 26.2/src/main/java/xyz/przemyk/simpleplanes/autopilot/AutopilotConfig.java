package xyz.przemyk.simpleplanes.autopilot;

/**
 * Every tunable number the flight director uses, in one place.
 *
 * <p>Units: distances and altitudes are blocks, speeds are blocks per tick (the plane's
 * {@code getDeltaMovement().length()}), angles are degrees, rates are degrees per tick.
 *
 * <p>The angular accelerations mirror {@code PlaneEntity#tickPitch/tickYaw/tickRoll}, which all
 * ramp their control rate by 0.5 deg/tick per tick. Keeping them here means the bang-bang
 * controllers brake at the right moment instead of oscillating.
 */
public final class AutopilotConfig {

    private AutopilotConfig() {}

    // ---- control surface authority (must match PlaneEntity's tickPitch/tickYaw/tickRoll) ----
    public static final double PITCH_ACCEL = 0.5;
    public static final double YAW_ACCEL = 0.5;
    /**
     * Yaw rate ceiling, degrees per tick, as {@code PlaneEntity#tickYaw} clamps it (before the
     * airframe's own {@code getRotationSpeedMultiplier}). This is what sets the turn radius, and
     * therefore how close to a waypoint a fast aircraft can physically get.
     */
    public static final double MAX_YAW_RATE = 2.5;
    public static final double ROLL_ACCEL = 0.5;

    public static final double PITCH_DEADBAND = 0.4;
    public static final double YAW_DEADBAND = 0.6;
    public static final double ROLL_DEADBAND = 1.5;

    // ---- attitude envelope ----
    public static final double MAX_PITCH = 25.0;
    public static final double MAX_CLIMB_ANGLE = 18.0;
    public static final double MAX_DESCENT_ANGLE = 12.0;
    public static final double MAX_BANK = 25.0;
    public static final double BANK_PER_HEADING_ERROR = 1.2;

    // ---- vertical guidance ----
    /** Vertical speed commanded per block of altitude error. */
    public static final double ALTITUDE_TO_VSPEED = 0.03;
    public static final double MAX_CLIMB_RATE = 0.35;
    public static final double MAX_SINK_RATE = 0.30;
    /** How hard flight-path-angle error is converted into a pitch attitude change. */
    public static final double FPA_TO_PITCH = 0.8;

    // ---- speed schedule ----
    /**
     * Cruise speed a route, sortie or inbound flies when the command was given no speed argument.
     *
     * <p><b>This is a fast default on purpose</b>, and it replaces the old 0.80. Every aircraft the
     * autopilot creates now carries a booster and {@link #ROUTE_MAX_SPEED}, so the airframe under it
     * is not the one 0.80 was chosen for, and the point of fitting the booster was to use it.
     *
     * <p>2.60 rather than {@link #MAX_CRUISE_SPEED}. The thrust fade in {@code PlaneEntity#tickMotion}
     * gives each throttle notch its own equilibrium speed, and on this airframe the top of the range
     * is notch 9 at 2.66 and notch 10 at 2.82 (measured on the rig at 2.78-2.83 with the lever
     * pinned at 10). A commanded 2.80 therefore sits on the stop: the loop has no notch left to add
     * and nothing to regulate with, so the number is not so much commanded as accepted. 2.60 sits
     * inside the band, which means the aircraft holds the speed it was told rather than whatever
     * full throttle happens to produce, and keeps a notch in hand for a climb or a turn — while
     * still being 93 percent of the airframe's absolute maximum and more than three times the speed
     * this default used to be.
     */
    public static final double CRUISE_SPEED = 2.60;
    public static final double CLIMB_SPEED = 0.70;
    public static final double APPROACH_SPEED = 0.50;
    public static final double FINAL_SPEED = 0.40;
    /** Deliberately unreachable, so the strike run simply pins the throttle at maximum. */
    public static final double STRIKE_SPEED = 9.0;

    /**
     * Lower bound on a commanded cruise speed. Below this the aircraft is close enough to
     * {@link #MIN_FLYING_SPEED} that the throttle loop spends the flight rescuing it from stalls.
     */
    public static final double MIN_CRUISE_SPEED = 0.40;
    /**
     * Upper bound on a commanded cruise speed, in blocks/tick.
     *
     * <p>Not an arbitrary cap: it is what the boosted airframe actually sustains.
     * {@code PlaneEntity#tickMotion} fades the thrust out towards
     * {@code maxSpeed * 10 * (push + 0.05)}, which at {@link #ROUTE_MAX_SPEED} and the booster's
     * throttle 10 is 3.375, and the drag polynomial balances that at 2.82. There is also a hard
     * limiter at 3.0 in the same method. Measured on the rig: a route commanded at 2.80 held
     * 2.78-2.83 for a 3000-block leg with the lever pinned at 10.
     *
     * <p>Asking for more than this does not go faster, it only removes the speed loop's ability to
     * regulate — which is why {@link #CRUISE_SPEED} sits a notch below rather than here.
     */
    public static final double MAX_CRUISE_SPEED = 2.80;

    /** Clamps a requested cruise speed into the range the airframe can actually fly. */
    public static double clampCruiseSpeed(double requested) {
        if (Double.isNaN(requested)) {
            return CRUISE_SPEED;
        }
        return Math.max(MIN_CRUISE_SPEED, Math.min(MAX_CRUISE_SPEED, requested));
    }

    /**
     * Speed ceiling given to a route/sortie aircraft, which now carries a booster like a strike
     * does. This is the point thrust fades out at, not a limiter — see {@link #MAX_CRUISE_SPEED}.
     * Raising the ceiling does not by itself make the aircraft fly fast; it decides how much thrust
     * each notch produces, and what the aircraft flies is whatever the flight director commands.
     */
    public static final float ROUTE_MAX_SPEED = 3.0f;

    /**
     * Safety factor on the computed deceleration distance.
     *
     * <p>{@link AutopilotMath#decelerationDistance} models level flight at throttle 0. The real
     * deceleration leg is not quite level — the aircraft is giving up cruise altitude for circuit
     * height at the same time, and that descent puts energy back in — and the throttle loop only
     * revises the lever every {@link #THROTTLE_INTERVAL} ticks. Starting the bleed this much earlier
     * than the ideal covers both, at the cost of arriving at the approach fix a little slow, which
     * the approach handles and the flare prefers.
     */
    public static final double DECELERATION_MARGIN = 1.35;
    public static final double SPEED_DEADBAND = 0.03;
    /** Ticks between throttle adjustments, to stop the engine lever from chattering. */
    public static final int THROTTLE_INTERVAL = 5;
    /**
     * Speed excess, in blocks/tick, above which the throttle goes to its floor in one step instead
     * of one notch every {@link #THROTTLE_INTERVAL} ticks.
     *
     * <p>The mirror image of {@link #MIN_FLYING_SPEED}'s immediate slam open, and it was found the
     * same way: by measuring. {@link AutopilotMath#decelerationDistance} models the bleed with the
     * throttle already shut, but from a fast cruise the lever starts at 10 and takes ten adjustments
     * — 50 ticks — to get there, and 50 ticks at nearly cruise speed is another 130 blocks of not
     * really braking. Measured on a straight-in deceleration from 2.80: the modelled 158 blocks came
     * out as 270 on the rig, so the aircraft was still doing 1.4 blocks/tick when it reached the
     * waypoint the bleed was aimed at. Cutting the lever in one step when the deficit is this large
     * makes the realised distance match the model the schedule is built on.
     *
     * <p>Set above the excess the loop sees in normal cruise regulation (a commanded 0.40 sits at
     * 0.43, an excess of 0.03) so ordinary station-keeping still moves one notch at a time.
     */
    public static final double THROTTLE_CUT_EXCESS = 0.20;

    /**
     * Horizontal speed below which the throttle loop stops being polite and slams the lever open.
     * <p>
     * {@code PlaneEntity#getLiftRatio} produces zero lift below
     * {@code takeOffSpeed * stallSpeedFactor} = 0.165 blocks/tick, so this is the stall speed with a
     * healthy margin. Recovery has to be immediate rather than one notch per
     * {@link #THROTTLE_INTERVAL} ticks: at one notch per 5 ticks a stalled aircraft needs 25 ticks
     * to reach full power, and it is on the ground long before that.
     */
    public static final double MIN_FLYING_SPEED = 0.32;

    /**
     * Throttle floor while airborne <em>and still short of the commanded speed</em>.
     * <p>
     * Closing the throttle completely is not "no thrust", it is an airbrake:
     * {@code PlaneEntity#tickMotion} multiplies the whole drag polynomial by
     * {@code brakesMul = 5} at throttle 0. Leaving one notch in keeps that off, which is the
     * difference between a descent and a deceleration.
     * <p>
     * It is a floor on <em>needed</em> power only, and that qualifier is not decoration. On the
     * boosted airframe every autopilot aircraft now carries, one notch is a cruise setting in its
     * own right: {@code setMaxSpeed(3.0)} puts the thrust fade-out at {@code 1.6875} for throttle 1,
     * where the drag curve balances at about 1.0 blocks/tick. Applied unconditionally this floor
     * therefore <em>is</em> the minimum speed of the aircraft — measured on the rig, a cruise
     * commanded at 0.80 sat at 0.93 for a whole 2000-block leg with the lever on 1, unable to go
     * slower. {@code PlaneAutopilot#applyThrottle} drops the floor to 0 whenever the aircraft is
     * above its commanded speed, which is when there is no power worth protecting.
     */
    public static final int MIN_AIRBORNE_THROTTLE = 1;

    /**
     * Largest angle the nose is ever allowed to sit off the velocity vector, in degrees.
     *
     * <p>This is the anti-stall limiter, and it is the fix for aircraft pancaking out of a turn or a
     * landing descent. {@code PlaneEntity#tickRotateMotion} computes an angle-of-attack efficiency
     * {@code d = 1 - min(1, aoa/60)^2} and multiplies <i>both</i> the wing lift and the rate at
     * which the velocity vector follows the nose by it. At 60 degrees of angle of attack {@code d}
     * is exactly zero: the wings stop working, the velocity vector stops following the nose, and the
     * aircraft falls with the nose up and no way out. The altitude cascade then reads the growing
     * sink rate as "too low" and commands even more nose-up, which is a divergence, not an
     * oscillation — measured in the field at 104 degrees of angle of attack, 1.09 blocks/tick of
     * sink and the nose 188 degrees off the commanded heading.
     *
     * <p>20 degrees keeps {@code d} at 0.89 or better, so the wings always work and the flight path
     * always follows the nose.
     */
    public static final double MAX_ANGLE_OF_ATTACK = 20.0;

    /**
     * Bank is given up when the aircraft is slow: a level turn needs {@code 1/cos(bank)} times the
     * lift of level flight, and lift is exactly what a slow aircraft has none of. Below
     * {@link #MIN_FLYING_SPEED} the wings are levelled outright.
     */
    public static final double BANK_LIMIT_SPEED = 0.55;

    /**
     * Bank angle, in degrees, past which the aircraft is treated as manoeuvring and the throttle
     * loop stops being allowed to reduce power. See {@code PlaneAutopilot#applyThrottle}.
     */
    public static final double MANOEUVRE_BANK = 8.0;
    /** Heading error, in degrees, that likewise counts as manoeuvring. */
    public static final double MANOEUVRE_HEADING_ERROR = 15.0;

    /**
     * Ticks an aircraft may sit on the ground in a mode that is supposed to be flying before the
     * flight is declared over. Without this a sortie that mushes into a field simply trundles along
     * the surface at taxi speed for the rest of the session, reporting {@code cruise}, and never
     * produces an outcome line for anyone to assert on.
     */
    public static final int GROUNDED_TIMEOUT = 60;

    // ---- ground / takeoff ----
    public static final double ROTATE_SPEED = 0.35;
    public static final double TAKEOFF_CLEAR_HEIGHT = 10.0;
    public static final double ROLLOUT_STOP_SPEED = 0.04;

    // ---- taxi ----
    /**
     * Ground speed held while taxiing, in blocks/tick (~4 blocks/s). Comfortably under
     * {@code takeOffSpeed} (0.3) so the aircraft cannot fly itself off the taxiway, and above the
     * 0.1 threshold at which {@code PlaneEntity#tickOnGround} applies its static-friction penalty.
     */
    public static final double TAXI_SPEED = 0.20;
    /** Throttle ceiling while taxiing, so the aircraft creeps rather than charging off. */
    public static final int TAXI_MAX_THROTTLE = 3;
    /** Distance from the lineup point at which the aircraft stops steering to it and aligns. */
    public static final double TAXI_LINEUP_RADIUS = 6.0;
    /** Heading error the aircraft must be inside, lined up on the threshold, before it may go. */
    public static final double TAXI_ALIGNED_ERROR = 8.0;
    /**
     * How far the parking spot sits to the side of the runway centreline, beyond the measured runway
     * half-width. Far enough to be off the strip, close enough that the taxi is a short one.
     */
    public static final double PARKING_LATERAL_OFFSET = 4.0;
    /** How far back from the threshold the parking spot sits, along the runway. */
    public static final double PARKING_BEHIND_THRESHOLD = 12.0;
    /**
     * How far a parking spot's surface may differ from the runway elevation, in blocks. Applied to
     * every candidate — the aprons, the ground behind the threshold and every sample along the taxi
     * line — because the aircraft has no way to taxi up or down a step.
     */
    public static final double PARKING_MAX_ELEVATION_DIFFERENCE = 2.0;
    /**
     * Where on the strip an aircraft parks when nothing off it is level enough. Kept inside
     * {@link #TAXI_LINEUP_RADIUS} so the taxi phase lines up and departs instead of rolling
     * backwards towards a point behind the aircraft.
     */
    public static final double PARKING_ON_RUNWAY_OFFSET = 3.0;
    /** Spacing of the level-ground samples along a taxi route, in blocks. */
    public static final double TAXI_PATH_SAMPLE_STEP = 2.0;
    /**
     * How far a <em>marked</em> parking spot may be from the nearest threshold.
     *
     * <p>The taxi is a straight line with no obstacle avoidance (see {@code PlaneAutopilot#tickTaxi}),
     * so distance is not free: every block of it is ground that has to be level and clear. Far
     * enough to put an apron off the side of a wide strip and a little way back, short enough that
     * the taxi stays the short roll the ground handling is written for.
     */
    public static final double PARKING_MAX_TAXI_DISTANCE = 64.0;
    /**
     * How far apart marked parking spots must be, and the radius searched for an aircraft already
     * standing on one. Roughly two plane lengths, so a queue of departures does not overlap.
     */
    public static final double PARKING_SPOT_CLEARANCE = 5.0;
    /** Most parking spots one airfield may have marked, so a stray tool cannot fill the save. */
    public static final int MAX_PARKING_SPOTS = 8;
    /** Ticks a taxi may take before the aircraft gives up and departs from where it stands. */
    public static final int TAXI_TIMEOUT = 900;
    /**
     * Longest departure delay {@code /autopilot flight … delay <seconds>} accepts.
     *
     * <p>An hour, and the bound exists because a parked aircraft still occupies one of the
     * {@link #MAX_ACTIVE_AUTOPILOTS} slots and keeps a chunk bubble alive the whole time it waits.
     * A mistyped delay is otherwise indistinguishable from an aircraft that never launched.
     */
    public static final int MAX_DEPARTURE_DELAY_SECONDS = 3600;
    /**
     * Ticks between a parked aircraft's attempts to take the departure runway.
     *
     * <p>Deliberately the same 20 ticks {@code PlaneAutopilot#tickHold} polls at, because it is the
     * same rule: whoever polls a free runway first takes it. Matching the two means a departure and
     * an arrival compete on equal terms rather than one of them being able to poll the other out.
     *
     * <p>There is no timeout behind this. Rolling anyway after some number of failed polls would
     * put an aircraft on a runway that is genuinely occupied, which is the one thing the gate
     * exists to prevent; a departure therefore waits for as long as it takes, and
     * {@code /autopilot tower} is what makes that visible.
     */
    public static final int DEPARTURE_POLL_INTERVAL = 20;

    // ---- chunk loading ----
    /**
     * Radius, in chunks, of the ticket the autopilot keeps around each aircraft.
     *
     * <p>Not a cosmetic number. {@code TicketStorage#addTicketWithRadius} gives the centre chunk
     * level {@code 33 - radius} and the level rises by one per chunk outwards, while
     * {@code ChunkLevel#isEntityTicking} needs level 31 or lower. So a ticket of radius {@code r}
     * only makes chunks within {@code r - 2} of the centre tick their entities: vanilla's ender
     * pearl radius of 2, which this used to copy, produces an entity-ticking area of exactly one
     * chunk. An aircraft at 3 blocks/tick leaves that chunk in five ticks and stops ticking.
     *
     * <p>4 gives an entity-ticking area two chunks (32 blocks) in every direction, which at
     * {@link #CHUNK_TICKET_INTERVAL} is several renewals' worth of travel.
     */
    public static final int CHUNK_TICKET_RADIUS = 4;
    /** Ticks between chunk-ticket renewals. Well inside the 40-tick ticket timeout. */
    public static final int CHUNK_TICKET_INTERVAL = 5;
    /**
     * How far ahead of the aircraft a second ticket is placed, in ticks of travel. Loading the
     * ground it is about to fly over both keeps it ticking and gives the terrain scanner real
     * heightmaps to read instead of the "unknown, hold altitude" fallback.
     *
     * <p>40 rather than 20, because deciding to go <em>round</em> something needs more warning than
     * deciding to climb over it. At {@link #CRUISE_SPEED} a 20-tick lead put the far edge of the
     * loaded area about 116 blocks ahead — half of {@link #SCAN_DISTANCE}, so the profile was blind
     * over its outer half — and 116 blocks is 45 ticks, against a turn radius of 60 blocks at that
     * speed. The aircraft could see the ridge but not in time to fly beside it. 40 ticks puts the
     * edge near 170 blocks and is still an overlap rather than a gap: each ticket makes chunks
     * resident for {@link #CHUNK_TICKET_RADIUS} = 4 chunks, i.e. 64 blocks, so the two bubbles meet
     * as long as the lead is under 128 blocks — 104 at cruise speed.
     */
    public static final int CHUNK_TICKET_LEAD_TICKS = 40;

    // ---- terrain safety ----
    /** Minimum clearance kept above the highest terrain ahead. */
    public static final double TERRAIN_CLEARANCE = 22.0;
    /** How far ahead the terrain profile is sampled. */
    public static final int SCAN_DISTANCE = 220;
    public static final int SCAN_SAMPLES = 12;
    /** Half-angle of the two side probes used to pick a direction to dodge towards. */
    public static final double SCAN_SIDE_ANGLE = 35.0;
    /** Heading bias applied when dodging around a ridge. */
    public static final double AVOID_HEADING_BIAS = 30.0;
    /** Cruise altitude used when a flight plan does not specify one. */
    public static final double DEFAULT_CRUISE_ALTITUDE = 110.0;

    // ---- lateral route planning (see RoutePlanner) ----
    /**
     * How many blocks of track one block of climb is worth, when deciding between climbing over
     * terrain and going round it.
     *
     * <p>A block of height at {@link #MAX_CLIMB_ANGLE} costs {@code 1/tan(18 deg)} = 3.1 blocks of
     * track, and every block bought to cross a ridge is given back on the far side at the same rate.
     * Six is that round trip. Deliberately no larger: this exists to stop an arrival buying 90
     * blocks of height to cross a summit a 60-block sidestep clears, not to make the aircraft
     * detour around every hummock.
     */
    public static final double CLIMB_TRACK_COST = 6.0;
    /** Ticks between route searches. The result is held between them, so this is the whole cost. */
    public static final int ROUTE_PLAN_INTERVAL = 20;
    /** Largest heading offset the search will consider, in degrees. */
    public static final double ROUTE_PLAN_MAX_DEVIATION = 60.0;
    /** Spacing of the candidate headings, in degrees. */
    public static final double ROUTE_PLAN_DEVIATION_STEP = 10.0;
    /** Terrain samples taken along each candidate heading. */
    public static final int ROUTE_PLAN_SAMPLES = 8;
    /**
     * Loaded ground, in blocks, needed ahead before a deviation is planned at all. Below this the
     * aircraft cannot see far enough for a sidestep to mean anything and the heightmap terrain
     * following — which holds altitude over ground it cannot see — is the safer behaviour.
     */
    public static final double ROUTE_PLAN_MIN_HORIZON = 80.0;
    /**
     * How much cheaper a deviation must be before it is taken, in blocks of equivalent track, and
     * the bonus a side already being flown keeps. Both are the same number because they are the same
     * idea: do not leave the direct track, or swap sides halfway round an obstacle, for a margin
     * that is inside the noise of an 8-sample profile.
     */
    public static final double ROUTE_PLAN_COMMIT_MARGIN = 25.0;
    /** Ticks a chosen deviation is held for before the search may change its mind. */
    public static final int ROUTE_PLAN_COMMIT_TICKS = 60;

    // ---- approach geometry ----
    /**
     * Steeper than a real 3-degree ILS on purpose. These three constants have to agree: the circuit
     * height must be roughly {@code tan(slope) * intercept distance}, or the aircraft arrives at the
     * initial approach fix far above the glide slope and has to dive at it. At 8 degrees and 300
     * blocks the slope sits at 42, just under the 45-block circuit height, so the descent is
     * continuous and the aircraft captures the slope from slightly above.
     */
    public static final double GLIDE_SLOPE_DEGREES = 8.0;
    /** Distance before the threshold at which the aircraft joins the final approach course. */
    public static final double FINAL_INTERCEPT_DISTANCE = 300.0;
    /** Circuit height above the runway threshold. */
    public static final double PATTERN_HEIGHT = 45.0;
    /**
     * Longest final {@link ArrivalPlan} will extend to, and the step it extends in.
     *
     * <p>Extending the intercept is how an aircraft that arrives high gets down without circling: it
     * joins the same glide slope further out, where the slope is higher and there is more track to
     * lose the height over. The ceiling exists because the extension is flown on the runway heading,
     * so a very long one is a long way flown in the wrong direction if the aircraft is coming from
     * the other side; past that an orbit really is the cheaper answer. At 8 degrees, 900 blocks is
     * 126 above the threshold, which is more than three times the circuit height.
     */
    public static final double MAX_INTERCEPT_DISTANCE = 900.0;
    public static final double INTERCEPT_EXTENSION_STEP = 150.0;
    /**
     * Speed the approach is flown at until it is time to brake for the landing, in blocks/tick.
     *
     * <p>The approach used to be flown at {@link #APPROACH_SPEED} from the top of descent, and that
     * is where the "landings take far too long" report comes from: measured on the rig, a routine
     * arrival spent <b>65 seconds</b> between top of descent and the flare, covering some 650 blocks
     * at 0.5 blocks/tick, most of it in a straight line with nothing to do. It is not a stability
     * requirement — only the flare and the landing gates need the aircraft slow, and they only start
     * at {@link #FINAL_HANDOVER_DISTANCE}.
     *
     * <p>1.20 rather than more: on the {@value #GLIDE_SLOPE_DEGREES}-degree slope the sink rate is
     * {@code v * tan(8 deg)}, so {@link #MAX_SINK_RATE} caps the useful approach speed at 2.13, and
     * the deceleration from there to {@link #FINAL_SPEED} needs more than the final leg has. 1.20
     * sinks at 0.17, holds on throttle notch 1 (measured: commanded 1.20, flew 1.23), and brakes to
     * final speed inside the handover distance with the margin the schedule already applies.
     */
    public static final double APPROACH_TRANSIT_SPEED = 1.20;
    /**
     * Distance before the threshold at which the approach hands over to {@code FINAL}: the gates
     * start being enforced and the aircraft is slowed to {@link #FINAL_SPEED}.
     */
    public static final double FINAL_HANDOVER_DISTANCE = 150.0;
    /**
     * Distance before the threshold by which the approach must be back at {@link #APPROACH_SPEED},
     * i.e. flying exactly the profile it flew before the transit speed existed.
     *
     * <p>Two measurements pin this down, one at each end.
     *
     * <p><b>Why it is not the handover distance.</b> The landing gates are armed the instant
     * {@code FINAL} begins at {@link #FINAL_HANDOVER_DISTANCE}, and they ask for a settled aircraft:
     * inside 10 degrees of the runway heading and 12 degrees of bank. An aircraft still braking
     * there is still banking to hold the centreline and fails the gates on the first tick they are
     * checked — measured with the deceleration aimed at the handover, four arrivals in a row went
     * around with "banked 14 deg" or "heading 10 deg off the runway" and none landed.
     *
     * <p><b>Why the schedule aims at approach speed and not final speed.</b> Because slower is not
     * safer here. Aiming the same schedule at {@link #FINAL_SPEED} put the aircraft below the old
     * profile for the last 240 blocks, where the throttle sits on its floor with the airbrake on,
     * and three arrivals in a row went around on "sinking 0.46 blocks/tick" against a 0.45 gate.
     * {@code FINAL} still takes it to final speed over the last 150 blocks, as it always did.
     */
    public static final double APPROACH_SETTLED_DISTANCE = 240.0;
    /**
     * Heading change, in degrees, past which the approach is flown at {@link #APPROACH_SPEED} rather
     * than {@link #APPROACH_TRANSIT_SPEED}. A turn of this size has to be flown at a radius the
     * centreline can absorb; see {@code PlaneAutopilot#speedAtFix}.
     */
    public static final double APPROACH_TURN_SLOW_ANGLE = 30.0;
    /** How far down the runway the aircraft aims. */
    public static final double TOUCHDOWN_AIM_OFFSET = 12.0;
    public static final double FLARE_HEIGHT = 4.0;
    public static final double FLARE_PITCH = 4.0;

    // ---- what counts as having landed ----
    /*
     * The roll-out has to decide whether the aircraft is standing on the runway it was cleared for
     * or somewhere else entirely, and it is the only thing that ever does: nothing earlier in the
     * arrival re-checks the position once the flare is committed. Both tolerances are therefore
     * sized to accept an untidy but real landing and refuse anything that is not one, rather than to
     * be generous.
     */
    /**
     * How far outside the surveyed strip, along it or across it, an aircraft may come to rest and
     * still be reported as landed on it. One plane length, so a touchdown that stops just short of
     * the threshold still counts and one that stops a hundred blocks out to sea does not.
     */
    public static final double LANDING_POSITION_TOLERANCE = 5.0;
    /**
     * How far the resting elevation may differ from the runway surface underneath. A parked aircraft
     * sits within a block of the surveyed elevation, so this only has to absorb a sloping strip and
     * the block the aircraft settles into; anything larger means it is not on the runway at all.
     */
    public static final double LANDING_ELEVATION_TOLERANCE = 3.0;

    // ---- landing gates: violated on short final means go around ----
    public static final double GATE_HEADING_ERROR = 10.0;
    public static final double GATE_LATERAL_OFFSET = 10.0;
    public static final double GATE_BANK = 12.0;
    public static final double GATE_SINK_RATE = 0.45;
    /** Height above threshold below which the gates are enforced. */
    public static final double GATE_CHECK_HEIGHT = 30.0;

    // ---- holding ----
    public static final double HOLD_RADIUS = 90.0;
    /** Degrees the hold target advances around the fix each tick (a slow racetrack orbit). */
    public static final double HOLD_TURN_RATE = 1.1;
    public static final int MAX_GO_AROUNDS = 3;
    /**
     * Vertical spacing between aircraft orbiting the same fix, in blocks, and how many levels the
     * stack has before it wraps.
     *
     * <p>Planes are hard-colliding entities — {@code PlaneEntity#canBeCollidedWith} is
     * unconditionally true — so two of them orbiting one fix at one altitude eventually block each
     * other's {@code move()}, which {@code PlaneCollisions} reads as an impact. Seen in the field:
     * two aircraft destroyed three blocks apart, at the same altitude, in the same tick window, both
     * in {@code HOLD}. Deriving the level and the starting angle from the entity id costs nothing
     * and separates them without needing a sequencer.
     *
     * <p>Small on purpose. The stack is bought with height the aircraft has to give back before it
     * can land, which is the very cost this whole change exists to remove: at 20 blocks a level the
     * fourth aircraft held 60 blocks above the fix and then could not get down again. 10 blocks is
     * five times a plane's own height and is separation, not a flight level.
     */
    public static final double HOLD_LEVEL_SPACING = 10.0;
    public static final int HOLD_LEVELS = 4;

    // ---- how much runway is actually needed ----
    /*
     * Both numbers below are derived from the ground physics rather than guessed, because "is this
     * runway long enough" is the first question the airfield browser has to answer and a wrong
     * answer either rejects usable fields or launches sorties that cannot finish.
     *
     * The ground roll is short in this flight model. PlaneEntity#tickOnGround multiplies dragMul by
     * 20*(3 - blockFriction), which on grass (friction 0.6) is 48x, so rolling drag is 0.024*v
     * against a thrust of 0.00625 per throttle notch. Simulating that tick loop from a standstill to
     * the ROTATE_SPEED of 0.35 b/t gives 3.8 blocks and 29 ticks at throttle 5, and 1.9 blocks and
     * 15 ticks at the booster's throttle 10. Braking from 0.40 b/t to a stop at throttle 0, where
     * brakesMul is 5, takes 2.1 blocks and 14 ticks.
     *
     * So neither roll is what limits a runway: the landing does, and it is dominated by where the
     * aircraft aims rather than by how long it takes to stop.
     */
    /** Runway consumed by a departure: the parked position plus the roll to rotation, with margin. */
    public static final double TAKEOFF_LENGTH_NEEDED = PARKING_ON_RUNWAY_OFFSET + 4.0 * 2.0;
    /**
     * Runway consumed by an arrival: the aircraft aims {@link #TOUCHDOWN_AIM_OFFSET} blocks in,
     * may float past it in the flare, and then needs its roll-out. Doubled, because a go-around
     * that is committed rather than flown again lands long.
     */
    public static final double LANDING_LENGTH_NEEDED = (TOUCHDOWN_AIM_OFFSET + 3.0) * 2.0;
    /**
     * Shortest runway the autopilot will call usable. Reported by the airfield browser and checked
     * before a sortie is launched, so a field that cannot be flown out of is refused at the command
     * rather than discovered by an aircraft in the air.
     */
    public static final double MIN_USABLE_RUNWAY_LENGTH =
        Math.max(TAKEOFF_LENGTH_NEEDED, LANDING_LENGTH_NEEDED);

    // ---- runway survey ----
    public static final int SURVEY_MAX_WIDTH = 24;
    public static final int SURVEY_APPROACH_LENGTH = 200;
    public static final int SURVEY_APPROACH_STEP = 10;
    /**
     * What one flagged column in an approach funnel is worth, in blocks of track, when an arrival
     * chooses which end to land on. Large on purpose: a column poking through the glide slope is a
     * go-around or a hillside, and no amount of saved detour is worth flying at one.
     */
    public static final double APPROACH_OBSTACLE_COST = 400.0;
    /** Tie-break bonus for landing uphill, in blocks of track. Decides a level choice, buys nothing. */
    public static final double UPHILL_END_BONUS = 40.0;
    /** Extra clearance an obstacle must leave under the approach path to not be flagged. */
    public static final double SURVEY_OBSTACLE_MARGIN = 3.0;

    // ---- limits ----
    /** Hard cap on simultaneously active autopilots per server, to bound the tick cost. */
    public static final int MAX_ACTIVE_AUTOPILOTS = 24;
    /** Default spawn distance of the strike tool. */
    public static final int STRIKE_SPAWN_DISTANCE = 400;
    /**
     * Height above the ground the strike aircraft is spawned at, and the height above ground its
     * run-in is flown at. Kept equal so the aircraft never has to trade speed for height after
     * launch.
     *
     * <p>High on purpose. A run-in flown low crosses whatever stands between the launch point and
     * the target — and what it usually meets first is a tree: a glancing hit on a canopy blocks only
     * the small vertical part of the motion, so the aircraft settles into the branches at walking
     * pace and sits there undamaged instead of reaching the target. Above the tree line there is
     * nothing to snag on.
     */
    public static final int STRIKE_RUN_IN_AGL = 100;
    /**
     * Flight path angle the terminal dive is entered at, which is what decides <i>when</i> it is
     * entered: the run-in is held until the target sits this far below the nose, so the dive point
     * follows the run-in height instead of being a fixed distance that only suits one altitude.
     *
     * <p>An earlier build dived from a fixed 350 blocks out, which at a 35-block run-in meant a
     * 6-degree glide starting almost immediately after launch — a long, shallow, treetop-scraping
     * descent. From 100 blocks up, 32 degrees commits the dive about 160 blocks out.
     *
     * <p>Past that point the nose is aimed straight at the target rather than at an altitude, so the
     * commanded angle is {@code atan(height / distance)}: it stays near this value for most of the
     * dive and steepens hyperbolically towards vertical over the last few blocks, which is both the
     * shape that hits accurately and the shape a dive bomber actually flies.
     */
    public static final double STRIKE_DIVE_ANGLE = 32.0;
    /** Floor and ceiling on the computed dive point, so an odd target height cannot produce an
     *  un-flyable one. */
    public static final double STRIKE_MIN_DIVE_DISTANCE = 60.0;
    public static final double STRIKE_MAX_DIVE_DISTANCE = 320.0;
    /** Speed under which an aircraft on a strike run is considered to have hit something. */
    public static final double STRIKE_STALLED_SPEED = 0.35;

    // ---- waypoints ----
    public static final double WAYPOINT_ARRIVAL_RADIUS = 30.0;
}
