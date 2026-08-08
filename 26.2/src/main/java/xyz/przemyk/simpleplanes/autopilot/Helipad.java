package xyz.przemyk.simpleplanes.autopilot;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * A surveyed helicopter landing site: a square pad about a centre block, plus which bearings it can
 * be approached from.
 *
 * <h2>Why this is not an {@link Airfield}</h2>
 * It was the first thing tried and it does not fit. An {@code Airfield} is two thresholds and a
 * width, and <em>everything</em> derived from it is a function of the line between them:
 * {@link RunwayEnd} exists only to name one direction along that line, the glide slope is measured
 * back down it, the aim point is a fifth of the way along it, the approach funnel is the extended
 * centreline, the parking apron is an offset from it, {@code isOnStrip} is a rectangle in its
 * coordinates and the designators are its compass bearing. A pad has no line. Encoding one as an
 * airfield with the two thresholds on top of each other gives a length of zero, a heading of
 * whatever {@code atan2(0, 0)} returns, a designator that means nothing, an aim offset of six
 * blocks down a runway that is not there, and two "ends" that are the same point — and every one of
 * those numbers would then be printed by {@code /autopilot airfields}, sorted by
 * {@code AirfieldBrowser} and put on the tower board as a runway. Spreading the pad out into a short
 * strip instead is worse: it would make the arrival fly a centreline, which is the one thing a
 * helicopter arrival should not do.
 *
 * <p>So a helipad is its own record with its own registry list, stored in the same
 * {@link AutopilotSavedData} file under its own key. Nothing about a runway changes, and nothing in
 * the fixed-wing path can see a pad at all.
 *
 * <h2>What is stored, and what is derived</h2>
 * The centre and the radius, and the bitmask of approach sectors that were clear when it was
 * surveyed. Everything else — the elevation, the touchdown point, the departure bearing — is derived
 * from those, so a pad stays consistent if the constants change. The centre is stored as the
 * <em>surface block</em>, the same convention {@code Airfield} uses for a threshold, so
 * {@code centre.y + 1} is the height a machine's skids rest at.
 *
 * @param clearSectors bitmask over {@link RotorcraftConfig#APPROACH_SECTORS}: bit <i>i</i> set means
 *                     the sector centred on bearing {@code i * 360 / sectors} was clear at survey
 *                     time. Stored rather than re-measured for exactly the reason
 *                     {@link Airfield#approachObstaclesA} is: an arrival asks the question from
 *                     hundreds of blocks away, when the pad's own chunks are the ones nobody has
 *                     loaded, and an unloaded column reads as clear sky.
 */
public record Helipad(String name, BlockPos centre, int radius, int clearSectors) {

    public static final Codec<Helipad> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.STRING.fieldOf("name").forGetter(Helipad::name),
        BlockPos.CODEC.fieldOf("centre").forGetter(Helipad::centre),
        Codec.INT.fieldOf("radius").forGetter(Helipad::radius),
        // Optional so that a pad written before the sectors were recorded — there are none in the
        // wild yet, but the same courtesy the airfield codec extends to its own history — loads with
        // "every sector clear" rather than failing the codec.
        Codec.INT.optionalFieldOf("clear_sectors", allSectors()).forGetter(Helipad::clearSectors)
    ).apply(instance, Helipad::new));

    /** Every bit set, i.e. "approachable from any bearing". */
    public static int allSectors() {
        return (1 << RotorcraftConfig.APPROACH_SECTORS) - 1;
    }

    public Helipad withName(String newName) {
        return new Helipad(newName, centre, radius, clearSectors);
    }

    /**
     * The point a machine's skids rest on: the centre of the top face of the centre block.
     *
     * <p>Deliberately the same convention as {@link Airfield#pointA()}, so a pad elevation and a
     * runway elevation are directly comparable and the two halves of the feature cannot come to
     * disagree about what "the surface" means.
     */
    public Vec3 touchdown() {
        return new Vec3(centre.getX() + 0.5, centre.getY() + 1.0, centre.getZ() + 0.5);
    }

    /** Surface elevation of the pad. */
    public double elevation() {
        return centre.getY() + 1.0;
    }

    /** Side length of the pad in blocks: {@code 2 * radius + 1}. */
    public int size() {
        return 2 * radius + 1;
    }

    /** How far off the centre a touchdown may be and still be called a landing on the pad. */
    public double landingTolerance() {
        return Math.max(RotorcraftConfig.LANDING_TOLERANCE_FLOOR,
            radius * RotorcraftConfig.LANDING_TOLERANCE_FRACTION);
    }

    public int clearSectorCount() {
        return Integer.bitCount(clearSectors & allSectors());
    }

    /** Minecraft yaw of the centre of sector {@code index}. */
    public static double sectorHeading(int index) {
        return index * (360.0 / RotorcraftConfig.APPROACH_SECTORS);
    }

    /**
     * The bearing an arrival from {@code from} should run in on: the clear sector whose centre is
     * nearest the direction the machine is already coming from, or the pad's own best sector when
     * none is.
     *
     * <p>Returned as the heading the machine <em>flies</em> towards the pad, which is the opposite
     * of the bearing the sector is measured on — the sector is the ground the machine passes over,
     * so it lies on the far side of the pad from the pad's point of view and on the near side from
     * the machine's. Getting that backwards is the pad equivalent of landing downwind, and it is
     * worth the sentence because it is invisible in the numbers.
     */
    public double arrivalHeading(Vec3 from) {
        double bearingFromPad = AutopilotMath.headingTo(touchdown(), from);
        int best = -1;
        double bestError = Double.MAX_VALUE;
        for (int i = 0; i < RotorcraftConfig.APPROACH_SECTORS; i++) {
            if ((clearSectors & (1 << i)) == 0) {
                continue;
            }
            double error = Math.abs(AutopilotMath.angleDelta(sectorHeading(i), bearingFromPad));
            if (error < bestError) {
                bestError = error;
                best = i;
            }
        }
        if (best < 0) {
            // No sector was clear at survey time. The survey refuses to register such a pad, so this
            // is only reachable for a pad whose surroundings changed afterwards; running in straight
            // from where the machine is is no worse than any other guess and is at least honest.
            return AutopilotMath.headingTo(from, touchdown());
        }
        return sectorHeading(best) + 180.0;
    }

    /**
     * Where an arrival comes to a hover before it descends: {@code distance} blocks out from the pad
     * along the chosen approach bearing, at {@code height} above the pad.
     */
    public Vec3 approachPoint(Vec3 from, double distance, double height) {
        double inbound = arrivalHeading(from);
        Vec3 back = AutopilotMath.pointAlong(touchdown(), inbound + 180.0, distance);
        return new Vec3(back.x, elevation() + height, back.z);
    }

    /** True when {@code point} is over the pad itself. */
    public boolean covers(Vec3 point, double margin) {
        return Math.abs(point.x - (centre.getX() + 0.5)) <= radius + margin
            && Math.abs(point.z - (centre.getZ() + 0.5)) <= radius + margin;
    }

    // ------------------------------------------------------------------ occupancy

    /**
     * True when nothing is standing on this pad and nothing else is on its way to it.
     *
     * <p>The same two questions {@link Airfield#standFree} asks about a parking stand, and derived
     * the same way rather than stored: an aircraft that crashes, despawns or is killed stops
     * claiming the pad without anything having to notice. The entity search alone is not enough for
     * exactly the reason it is not enough for a stand — a machine parked on a pad renews no chunk
     * ticket, so 40 ticks after it lands its chunk unloads and it becomes invisible to
     * {@code Level#getEntities}. The claim walk over the live autopilots covers the arrival that is
     * still flying; {@link StandOccupancy} covers the one that has landed and gone quiet.
     *
     * @param asker excluded from every test, so a machine can ask about the pad it already holds
     */
    public boolean free(Level level, @Nullable PlaneEntity asker) {
        Vec3 point = touchdown();
        AABB box = AABB.ofSize(point, (radius + 1) * 2.0, 8.0, (radius + 1) * 2.0);
        if (!level.getEntities(EntityTypeTest.forClass(PlaneEntity.class), box,
            plane -> plane != asker).isEmpty()) {
            return false;
        }
        for (PlaneEntity plane : AutopilotRegistry.active()) {
            if (plane == asker || plane.level() != level) {
                continue;
            }
            PlaneAutopilot autopilot = plane.getAutopilot();
            if (autopilot != null && autopilot.claimsStand(centre)) {
                return false;
            }
        }
        return !StandOccupancy.isTaken(level, name, centre, asker);
    }

    // ------------------------------------------------------------------ the survey

    /** What a survey found, whether or not it was good enough to register. */
    public record Survey(@Nullable Helipad pad, BlockPos markedCentre, BlockPos derivedCentre,
                         int radius, int roughness, int obstacleHeight, boolean[] sectors,
                         List<String> refusals, List<String> warnings) {

        public boolean accepted() {
            return pad != null;
        }

        public double centreMoved() {
            double dx = markedCentre.getX() - derivedCentre.getX();
            double dz = markedCentre.getZ() - derivedCentre.getZ();
            return Math.sqrt(dx * dx + dz * dz);
        }

        public int clearSectorCount() {
            int count = 0;
            for (boolean clear : sectors) {
                if (clear) {
                    count++;
                }
            }
            return count;
        }
    }

    /**
     * Surveys the pad between two clicked corners.
     *
     * <p><b>The clicks define an area, not a line.</b> Two corners is the selection idiom every
     * Minecraft player already knows, and unlike a runway's two thresholds it gives the extent
     * directly: the centre is the middle of the box and the radius is the larger of its two
     * half-spans, so clicking opposite corners of a 7x7 pad produces exactly that pad. Clicking the
     * same block twice produces a 1x1 and is refused.
     *
     * <p><b>The marked shape and the used shape are then made to be the same thing.</b> This is the
     * lesson the runway survey learned the expensive way — it took the clicked blocks as the
     * thresholds, so a strip clicked on its edge was flown on its edge — and the fix here is the
     * same shape of fix: the seed centre is moved onto the middle of the pad the terrain actually
     * shows, iterated because moving it changes what the probes see. On ground with no edges (the
     * superflat, or a pad flush with the field around it) the probes find nothing to centre on, the
     * offset comes out zero and the clicked box is used unchanged. {@link Survey#markedCentre} and
     * {@link Survey#derivedCentre} are both reported so the correction is never silent, and
     * {@code HelicopterAutopilot} touches down on {@link #touchdown()} — the derived centre — so the
     * two coordinates in the log are directly comparable.
     */
    public static Survey survey(Level level, String name, BlockPos cornerA, BlockPos cornerB) {
        int spanX = Math.abs(cornerA.getX() - cornerB.getX());
        int spanZ = Math.abs(cornerA.getZ() - cornerB.getZ());
        int radius = Math.max(spanX, spanZ) / 2;
        int seedX = (cornerA.getX() + cornerB.getX()) / 2;
        int seedZ = (cornerA.getZ() + cornerB.getZ()) / 2;

        List<String> refusals = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        int surface = TerrainScanner.surfaceHeight(level, seedX + 0.5, seedZ + 0.5);
        BlockPos marked = new BlockPos(seedX, surface == TerrainScanner.UNKNOWN_HEIGHT
            ? cornerA.getY() : surface - 1, seedZ);
        if (surface == TerrainScanner.UNKNOWN_HEIGHT) {
            refusals.add("there is no ground at " + marked.toShortString()
                + " (the chunk is not loaded, or the column is empty)");
            return new Survey(null, marked, marked, radius, 0, 0,
                new boolean[RotorcraftConfig.APPROACH_SECTORS], refusals, warnings);
        }

        if (radius < RotorcraftConfig.MIN_PAD_RADIUS) {
            refusals.add("that is a " + (2 * radius + 1) + "x" + (2 * radius + 1)
                + " pad; mark two opposite corners at least "
                + (2 * RotorcraftConfig.MIN_PAD_RADIUS) + " blocks apart");
        }
        if (radius > RotorcraftConfig.MAX_PAD_RADIUS) {
            refusals.add("that is a " + (2 * radius + 1) + "x" + (2 * radius + 1)
                + " pad; the largest this registers is "
                + (2 * RotorcraftConfig.MAX_PAD_RADIUS + 1) + "x"
                + (2 * RotorcraftConfig.MAX_PAD_RADIUS + 1)
                + ". A landing area larger than that is a runway, not a pad");
        }
        if (!refusals.isEmpty()) {
            return new Survey(null, marked, marked, radius, 0, 0,
                new boolean[RotorcraftConfig.APPROACH_SECTORS], refusals, warnings);
        }

        BlockPos centre = levelOnPad(level, centreOnPad(level, marked, radius), radius);
        Helipad candidate = new Helipad(name, centre, radius, allSectors());

        int roughness = padRoughness(level, candidate, refusals);
        int obstacleHeight = columnObstruction(level, candidate, refusals);
        boolean[] sectors = scanSectors(level, candidate);

        int clear = 0;
        for (boolean sector : sectors) {
            if (sector) {
                clear++;
            }
        }
        if (clear < RotorcraftConfig.MIN_CLEAR_SECTORS) {
            refusals.add("no clear approach: every one of the "
                + RotorcraftConfig.APPROACH_SECTORS + " bearings has terrain across it inside "
                + RotorcraftConfig.APPROACH_LENGTH + " blocks. A machine could hover over this pad"
                + " but not fly to it");
        } else if (clear == 1) {
            warnings.add("only one clear approach bearing ("
                + AutopilotMath.compassDisplay(sectorHeading(firstSet(sectors)))
                + " deg); every arrival will run in from the same side");
        }
        if (roughness > 0) {
            warnings.add("the pad surface varies by " + roughness
                + " block; a helicopter settles on a point, so flat is better");
        }

        int mask = 0;
        for (int i = 0; i < sectors.length; i++) {
            if (sectors[i]) {
                mask |= 1 << i;
            }
        }
        Helipad pad = refusals.isEmpty() ? new Helipad(name, centre, radius, mask) : null;
        return new Survey(pad, marked, centre, radius, roughness, obstacleHeight, sectors,
            refusals, warnings);
    }

    private static int firstSet(boolean[] sectors) {
        for (int i = 0; i < sectors.length; i++) {
            if (sectors[i]) {
                return i;
            }
        }
        return 0;
    }

    /**
     * Moves a seed centre onto the middle of the pad the ground actually shows.
     *
     * <p>Probes north, south, east and west from the seed for as long as the surface stays within
     * {@link RotorcraftConfig#PAD_MAX_ROUGHNESS} of the seed's own elevation, then moves the centre
     * to the middle of what it found on each axis. Iterated {@code SURVEY_CENTRING_PASSES} times,
     * because moving the centre changes what the probes see — the same reason the runway survey
     * iterates, and the same constant, so the two behave alike.
     *
     * <p>The probe limit is the pad radius plus one: this is a correction to a marked pad, not a
     * search for a pad somewhere nearby, and letting it run further would let a large flat field
     * drag the centre of a small marked pad clean off it.
     */
    private static BlockPos centreOnPad(Level level, BlockPos seed, int radius) {
        BlockPos centre = seed;
        int limit = radius + 1;
        for (int pass = 0; pass < AutopilotConfig.SURVEY_CENTRING_PASSES; pass++) {
            double reference = centre.getY() + 1.0;
            int east = reach(level, centre, 1, 0, limit, reference);
            int west = reach(level, centre, -1, 0, limit, reference);
            int south = reach(level, centre, 0, 1, limit, reference);
            int north = reach(level, centre, 0, -1, limit, reference);
            int dx = (int) Math.round((east - west) / 2.0);
            int dz = (int) Math.round((south - north) / 2.0);
            if (dx == 0 && dz == 0) {
                break;
            }
            int x = centre.getX() + dx;
            int z = centre.getZ() + dz;
            int surface = TerrainScanner.surfaceHeight(level, x + 0.5, z + 0.5);
            if (surface == TerrainScanner.UNKNOWN_HEIGHT) {
                break;
            }
            centre = new BlockPos(x, surface - 1, z);
        }
        return centre;
    }

    /**
     * Puts the pad's stored elevation on the surface most of the pad actually has, rather than on
     * whatever the middle column happens to read.
     *
     * <p>Found on the rig with a single stone block floating five above the middle of an otherwise
     * perfect pad. The elevation came from that column, so the survey decided the pad was at that
     * height and then reported the other 48 columns as being <em>six blocks below the pad</em> — it
     * refused, which is right, but for a reason that reads as nonsense and with the obstacle check
     * saying "nothing standing over the pad". Taking the modal surface instead puts the elevation
     * where the pad is, and the same block is then described as what it is: something standing six
     * blocks above it.
     *
     * <p>Modal rather than lowest, because a pad with a one-block hole in it should not have its
     * datum dragged into the hole; both are refused anyway, and the modal answer is the one whose
     * message a player can act on.
     */
    private static BlockPos levelOnPad(Level level, BlockPos centre, int radius) {
        java.util.Map<Integer, Integer> counts = new java.util.HashMap<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int surface = TerrainScanner.surfaceHeight(level,
                    centre.getX() + dx + 0.5, centre.getZ() + dz + 0.5);
                if (surface != TerrainScanner.UNKNOWN_HEIGHT) {
                    counts.merge(surface, 1, Integer::sum);
                }
            }
        }
        int best = centre.getY() + 1;
        int bestCount = 0;
        for (java.util.Map.Entry<Integer, Integer> entry : counts.entrySet()) {
            // Ties go to the lower surface: two equally common levels on one pad means a step, and
            // the lower of the two is the one a machine can be put down on without being inside the
            // other.
            if (entry.getValue() > bestCount
                || (entry.getValue() == bestCount && entry.getKey() < best)) {
                best = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        return new BlockPos(centre.getX(), best - 1, centre.getZ());
    }

    /** How many blocks the pad surface continues in one direction, up to {@code limit}. */
    private static int reach(Level level, BlockPos from, int dx, int dz, int limit, double reference) {
        int found = 0;
        for (int step = 1; step <= limit; step++) {
            double x = from.getX() + dx * step + 0.5;
            double z = from.getZ() + dz * step + 0.5;
            int surface = TerrainScanner.surfaceHeight(level, x, z);
            if (surface == TerrainScanner.UNKNOWN_HEIGHT
                || Math.abs(surface - reference) > RotorcraftConfig.PAD_MAX_ROUGHNESS) {
                break;
            }
            found = step;
        }
        return found;
    }

    /**
     * How much the pad surface varies, in blocks, and the reasons it is unusable.
     *
     * <p><b>Every column, no sampling step.</b> A pad of radius 7 is 225 columns and each is one
     * O(1) heightmap lookup, which is nothing to pay once at survey time — and the alternative is
     * the bug the fixed-wing funnel had, where a sample every 10 blocks meant a 20-block wall
     * between two stations counted as no obstacle at all.
     *
     * <p>Three separate refusals, because they are three different problems for the pilot: ground
     * nobody has loaded, ground that is not ground (a pond or a lava pool reads as a perfectly
     * good surface to {@code MOTION_BLOCKING}), and ground that is not flat.
     */
    private static int padRoughness(Level level, Helipad pad, List<String> refusals) {
        double reference = pad.elevation();
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        BlockPos worst = null;
        boolean unloaded = false;
        BlockPos notLandable = null;
        for (int dx = -pad.radius(); dx <= pad.radius(); dx++) {
            for (int dz = -pad.radius(); dz <= pad.radius(); dz++) {
                double x = pad.centre().getX() + dx + 0.5;
                double z = pad.centre().getZ() + dz + 0.5;
                int surface = TerrainScanner.surfaceHeight(level, x, z);
                if (surface == TerrainScanner.UNKNOWN_HEIGHT) {
                    unloaded = true;
                    continue;
                }
                if (notLandable == null && !TerrainScanner.isLandable(level, x, z)) {
                    notLandable = BlockPos.containing(x, surface, z);
                }
                if (surface < lowest) {
                    lowest = surface;
                }
                if (surface > highest) {
                    highest = surface;
                    worst = BlockPos.containing(x, surface, z);
                }
            }
        }
        if (unloaded) {
            refusals.add("part of the pad is in an unloaded chunk; stand on it, or load it, and"
                + " survey again");
        }
        if (notLandable != null) {
            refusals.add("the pad is not all solid ground - " + notLandable.toShortString()
                + " is water or lava");
        }
        if (lowest == Integer.MAX_VALUE) {
            return 0;
        }
        int spread = highest - lowest;
        if (spread > RotorcraftConfig.PAD_MAX_ROUGHNESS) {
            refusals.add("the pad surface varies by " + spread + " blocks (highest at "
                + (worst == null ? "?" : worst.toShortString()) + "); flatten it to within "
                + RotorcraftConfig.PAD_MAX_ROUGHNESS);
        }
        return spread;
    }

    /**
     * How far anything stands above the pad inside the vertical clearance, and the refusal if it
     * does.
     *
     * <p>Uses {@code MOTION_BLOCKING}, which reports the top of the highest thing in the column — so
     * a branch overhanging the pad with air underneath it is caught, which is exactly the case a
     * "walk up from the ground" test would miss. Every column of the pad plus its clearance ring is
     * read; a vertical departure passes through all of them.
     */
    private static int columnObstruction(Level level, Helipad pad, List<String> refusals) {
        int reach = pad.radius() + RotorcraftConfig.PAD_CLEARANCE_MARGIN;
        double surfaceLevel = pad.elevation();
        int tallest = 0;
        BlockPos where = null;
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                double x = pad.centre().getX() + dx + 0.5;
                double z = pad.centre().getZ() + dz + 0.5;
                int surface = TerrainScanner.surfaceHeight(level, x, z);
                if (surface == TerrainScanner.UNKNOWN_HEIGHT) {
                    continue;
                }
                int above = (int) Math.round(surface - surfaceLevel);
                if (above > tallest) {
                    tallest = above;
                    where = BlockPos.containing(x, surface, z);
                }
            }
        }
        if (tallest > RotorcraftConfig.PAD_MAX_ROUGHNESS) {
            refusals.add("something stands " + tallest + " blocks above the pad at "
                + (where == null ? "?" : where.toShortString())
                + "; a departure is vertical, so the column above the pad and "
                + RotorcraftConfig.PAD_CLEARANCE_MARGIN + " blocks of ring around it must be clear to "
                + RotorcraftConfig.PAD_CLEAR_HEIGHT + " blocks");
        }
        return tallest;
    }

    /**
     * Which approach bearings are usable.
     *
     * <p>Each sector is a wedge of ground running out from the pad on one of
     * {@link RotorcraftConfig#APPROACH_SECTORS} bearings, checked against a sloped path that ends on
     * the pad. It is sampled every {@link RotorcraftConfig#APPROACH_STEP} blocks along track and
     * {@link RotorcraftConfig#APPROACH_LANES} columns across, so nothing narrower than the step and
     * nothing beside the centre line can hide in it — the two ways the fixed-wing funnel used to
     * miss a 20-block wall.
     *
     * <p>An unloaded column makes the sector unusable rather than being skipped. A survey is run
     * standing on the pad and refuses unloaded ground under it, so a sector that cannot be read is
     * genuinely unknown terrain, and "nobody has loaded it" must never be the cheapest way to pass.
     */
    private static boolean[] scanSectors(Level level, Helipad pad) {
        boolean[] blocked = new boolean[RotorcraftConfig.APPROACH_SECTORS];
        double gradient = Math.tan(Math.toRadians(RotorcraftConfig.APPROACH_SLOPE_DEGREES));
        double wedge = Math.tan(Math.toRadians(180.0 / RotorcraftConfig.APPROACH_SECTORS));
        int reach = RotorcraftConfig.APPROACH_LENGTH;
        Vec3 touchdown = pad.touchdown();

        // One pass over the square that contains every sector, reading each column once and then
        // testing it against whichever sectors it falls inside. That is what makes "every block" —
        // rather than every second block, or every tenth — affordable: the cost is the area, not the
        // area times the number of sectors, and a heightmap read is O(1).
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                double distance = Math.sqrt((double) dx * dx + (double) dz * dz);
                if (distance < 1.0 || distance > reach) {
                    continue;
                }
                double x = touchdown.x + dx;
                double z = touchdown.z + dz;
                // The path rises away from the pad, and the allowance never drops below the pad's own
                // surface: near the pad the sloped path is barely above the ground it lands on, and
                // without the floor the pad itself counts as an obstacle in every sector. The same
                // trap Airfield#countApproachObstacles documents.
                double allowed = Math.max(
                    pad.elevation() + gradient * distance - RotorcraftConfig.APPROACH_MARGIN,
                    pad.elevation());
                int terrain = TerrainScanner.surfaceHeight(level, x, z);
                // Unloaded counts against the sector rather than being skipped. A survey is run
                // standing on the pad and refuses unloaded ground under it, so a column out here
                // that cannot be read is genuinely unknown terrain — and "nobody has loaded it" must
                // never be the cheapest way to pass.
                boolean bad = terrain == TerrainScanner.UNKNOWN_HEIGHT || terrain > allowed;
                if (!bad) {
                    continue;
                }
                double bearing = AutopilotMath.headingTo(touchdown, new Vec3(x, touchdown.y, z));
                for (int sector = 0; sector < blocked.length; sector++) {
                    if (blocked[sector]) {
                        continue;
                    }
                    double off = Math.toRadians(AutopilotMath.angleDelta(sectorHeading(sector), bearing));
                    double along = distance * Math.cos(off);
                    double across = Math.abs(distance * Math.sin(off));
                    if (along <= 0) {
                        continue;
                    }
                    double halfWidth = Math.min(along * wedge, RotorcraftConfig.APPROACH_MAX_HALF_WIDTH);
                    if (across <= halfWidth) {
                        blocked[sector] = true;
                    }
                }
            }
        }
        boolean[] clear = new boolean[blocked.length];
        for (int i = 0; i < blocked.length; i++) {
            clear[i] = !blocked[i];
        }
        return clear;
    }

    /**
     * Cruise altitude for a leg between two pads: clear of the terrain under the whole leg.
     *
     * <p>Sampled every 16 blocks rather than at a fixed number of stations, so a long leg is not
     * sampled more coarsely than a short one. Unloaded columns are skipped — the machine flies its
     * own chunk bubble and will see them when it gets there, and the terrain follower in
     * {@link HelicopterAutopilot} climbs for anything this missed.
     */
    public static int cruiseAltitude(Level level, Helipad from, Helipad to) {
        Vec3 a = from.touchdown();
        Vec3 b = to.touchdown();
        double highest = Math.max(a.y, b.y);
        double distance = AutopilotMath.horizontalDistance(a, b);
        int samples = Math.max(2, (int) Math.ceil(distance / 16.0));
        for (int i = 0; i <= samples; i++) {
            double t = (double) i / samples;
            double x = a.x + (b.x - a.x) * t;
            double z = a.z + (b.z - a.z) * t;
            int surface = TerrainScanner.surfaceHeight(level, x, z);
            if (surface != TerrainScanner.UNKNOWN_HEIGHT) {
                highest = Math.max(highest, surface);
            }
        }
        return (int) Math.min(highest + RotorcraftConfig.CRUISE_CLEARANCE, level.getMaxY() - 10);
    }
}
