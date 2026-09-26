package xyz.przemyk.simpleplanes.items;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.autopilot.AircraftType;
import xyz.przemyk.simpleplanes.autopilot.AutopilotComponents;
import xyz.przemyk.simpleplanes.autopilot.Blast;
import xyz.przemyk.simpleplanes.autopilot.AutopilotConfig;
import xyz.przemyk.simpleplanes.autopilot.AutopilotFeedback;
import xyz.przemyk.simpleplanes.autopilot.AutopilotMath;
import xyz.przemyk.simpleplanes.autopilot.AutopilotSpawner;
import xyz.przemyk.simpleplanes.autopilot.AutopilotText;
import xyz.przemyk.simpleplanes.autopilot.RunwayOccupancy;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;
import xyz.przemyk.simpleplanes.setup.SimplePlanesComponents;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Scripted attack run. Right-clicking a block spawns an aircraft the configured distance away and
 * sends it at that block at full throttle.
 *
 * <ul>
 *   <li>right-click a block — launch a strike at it</li>
 *   <li>right-click the air — status report, including the blast setting</li>
 *   <li>sneak + right-click the air — cycle the spawn distance, and the blast each time it wraps</li>
 *   <li>a plane item in the other hand — that airframe, in that material, flies the strike</li>
 *   <li>a drone item in the other hand — that drone flies it, with its own small charge</li>
 * </ul>
 *
 * <p><b>Aircraft.</b> Chosen the way a bow chooses its arrow: whatever aircraft is in the other hand
 * is what gets sent, and it is spent outside creative; nothing is written back onto the tool. With
 * no aircraft there, the tool's own setting ({@code /autopilot tool type}) flies, and unset that is
 * the starter plane. Only {@link AircraftType#canStrike()} airframes are accepted; anything else is
 * refused by name rather than replaced, and the quadcopter crane is never a strike aircraft.
 *
 * <p><b>Settings.</b> The gesture cycles the two settings anyone changes in flight — spawn distance
 * and blast strength — because a held item offers exactly one spare gesture and cycling five
 * independent settings through it would be worse than not having them. The full set, including
 * whether the blast breaks blocks, whether it sets fire, and a pinned run-in bearing, is written
 * onto the held tool by {@code /autopilot tool}, which takes the same arguments in the same order as
 * {@code /autopilot strike}. Every setting is stored on the stack, so it survives logging out.
 *
 * <p>Unset means what a strike has always done: {@value Blast#DEFAULT_POWER} strength, blocks
 * broken, no fire, and a run-in worked out from where the player is standing.
 */
public class PlaneStrikeToolItem extends Item {

    private static final int[] DISTANCES = {100, 200, 400, 800};
    /** Blast strengths the tool cycles through. The first is vanilla TNT, i.e. the historic default. */
    private static final float[] BLASTS = {Blast.DEFAULT_POWER, 8.0F, Blast.MAX_POWER, 1.0F};

    public PlaneStrikeToolItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    public static int getDistance(ItemStack stack) {
        Integer distance = stack.get(AutopilotComponents.STRIKE_DISTANCE);
        return distance == null ? AutopilotConfig.STRIKE_SPAWN_DISTANCE : distance;
    }

    /** Blast the tool is set to; each field falls back to the historic default when unset. */
    public static Blast getBlast(ItemStack stack) {
        Float power = stack.get(AutopilotComponents.STRIKE_BLAST);
        Boolean blocks = stack.get(AutopilotComponents.STRIKE_BLOCKS);
        Boolean fire = stack.get(AutopilotComponents.STRIKE_FIRE);
        if (power == null && blocks == null && fire == null) {
            return Blast.DEFAULT;
        }
        return new Blast(power == null ? Blast.DEFAULT_POWER : power,
            blocks == null || blocks, fire != null && fire);
    }

    /** Airframe stored on the tool; unset is the starter plane, as before the setting existed. */
    public static AircraftType getType(ItemStack stack) {
        AircraftType type = stack.get(AutopilotComponents.STRIKE_TYPE);
        return type == null ? AircraftType.PLANE : type;
    }

    /** "plane, large, cargo, fighter, airliner or random", for refusals. */
    public static String strikeTypeList() {
        String all = AircraftType.strikeTypes().stream().map(AircraftType::getSerializedName)
            .collect(Collectors.joining(", "));
        int last = all.lastIndexOf(", ");
        return last < 0 ? all : all.substring(0, last) + " or " + all.substring(last + 2);
    }

    /**
     * Why an aircraft by this name (an {@code /autopilot} type or an entity id path) is not sent on
     * a strike, ending with the list of those that are.
     */
    public static String strikeRefusal(String name) {
        String reason = switch (name.toLowerCase(Locale.ROOT)) {
            case "quadcopter", "crane" -> "The quadcopter crane is peaceful and is never sent on a strike.";
            case "helicopter", "mini_helicopter" -> "A helicopter cannot fly an attack run: the run is a"
                + " fixed-wing control law and a rotorcraft does not answer it.";
            case "airship" -> "An airship cannot fly an attack run: it cannot dive, and drifts past the target.";
            default -> "A " + name + " cannot fly an attack run.";
        };
        return reason + " Strike aircraft: " + strikeTypeList() + ".";
    }

    /**
     * What the next launch flies: the airframe and skin of an aircraft item in the other hand, else
     * the tool's own setting. {@code refusal} is set, and nothing may be launched, when the aircraft
     * on offer is not one the attack run can fly. {@code held} is that item, or empty.
     */
    public record Selection(AircraftType type, @Nullable Block material, String label, @Nullable String refusal,
                            ItemStack held) {}

    public static Selection select(Player player, InteractionHand toolHand, ItemStack tool) {
        ItemStack other = player.getItemInHand(
            toolHand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
        if (other.getItem() instanceof QuadcopterItem) {
            return new Selection(getType(tool), null, "quadcopter (other hand, refused)", strikeRefusal("quadcopter"),
                other);
        }
        if (other.getItem() instanceof DroneItem droneItem) {
            AircraftType type = AircraftType.of(droneItem.droneEntityType.get());
            if (type != null && type.canStrike()) {
                return new Selection(type, null, type.getSerializedName() + " (other hand)", null, other);
            }
        }
        if (other.getItem() instanceof PlaneItem planeItem) {
            AircraftType type = AircraftType.of(planeItem.planeEntityType.get());
            if (type == null || !type.canStrike()) {
                String name = BuiltInRegistries.ENTITY_TYPE.getKey(planeItem.planeEntityType.get()).getPath();
                return new Selection(getType(tool), null, name + " (other hand, refused)", strikeRefusal(name), other);
            }
            return new Selection(type, materialOf(other), type.getSerializedName() + " (other hand)", null, other);
        }
        // Only reachable through /give or an edited stack: /autopilot tool refuses these names.
        AircraftType type = getType(tool);
        return type.canStrike()
            ? new Selection(type, null, type.getSerializedName(), null, ItemStack.EMPTY)
            : new Selection(type, null, type.getSerializedName() + " (refused)", strikeRefusal(type.getSerializedName()),
                ItemStack.EMPTY);
    }

    /** The material a plane item would be built in, or null for the airframe's default. */
    private static @Nullable Block materialOf(ItemStack planeItem) {
        CompoundTag tag = planeItem.get(SimplePlanesComponents.ENTITY_TAG);
        if (tag == null) {
            return null;
        }
        // tryParse and getOptional: player-supplied data, as in PlaneItem's tooltip.
        return tag.getString("material").map(Identifier::tryParse)
            .flatMap(BuiltInRegistries.BLOCK::getOptional).orElse(null);
    }

    /** The tool's blast, or for a drone the fixed charge it flies instead. */
    private static String describeBlast(Blast blast, AircraftType type) {
        return type.isDrone() ? type.warhead(blast).describe() + " (drone charge)" : blast.describe();
    }

    /** Pinned run-in bearing in compass degrees, or null to work one out from the player. */
    public static @Nullable Integer getBearing(ItemStack stack) {
        return stack.get(AutopilotComponents.STRIKE_BEARING);
    }

    /** ", bearing 050" for a pinned run-in, or nothing at all when it is worked out per launch. */
    private static String describeBearing(ItemStack stack) {
        Integer bearing = getBearing(stack);
        return bearing == null ? "" : String.format(", bearing %03d", bearing);
    }

    /** Next entry in a cycle, wrapping; returns index 0 for a value that is not in the list. */
    private static int nextIndex(float[] values, float current) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) {
                return (i + 1) % values.length;
            }
        }
        return 0;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }

        if (!RunwayOccupancy.canActivateAnother()) {
            AutopilotFeedback.warn(player, "Too many autopilot aircraft already flying ("
                + RunwayOccupancy.activeCount() + "/" + AutopilotConfig.MAX_ACTIVE_AUTOPILOTS + ").");
            return InteractionResult.CONSUME;
        }

        ItemStack stack = context.getItemInHand();
        Selection aircraft = select(player, context.getHand(), stack);
        if (aircraft.refusal() != null) {
            AutopilotFeedback.warn(player, aircraft.refusal());
            return InteractionResult.CONSUME;
        }
        BlockPos target = context.getClickedPos();
        int distance = getDistance(stack);
        Integer pinned = getBearing(stack);
        // Pinned bearing if the tool carries one, otherwise run in from the player's side so the
        // aircraft passes them on the way to the target.
        double bearing = pinned != null
            ? AutopilotMath.yawFromCompass(pinned)
            : AutopilotSpawner.approachBearingFrom(player.position(), target);
        Blast blast = getBlast(stack);
        PlaneEntity plane = AutopilotSpawner.launchStrike(level, target, distance, bearing, player, blast,
            aircraft.type());
        if (plane == null) {
            AutopilotFeedback.warn(player, "Could not create the aircraft.");
            return InteractionResult.CONSUME;
        }
        if (aircraft.material() != null) {
            plane.setMaterial(aircraft.material());
        }
        // Spent like a bow's arrow outside creative: a crashed strike aircraft drops its own item, so
        // a template that stayed in hand would print a new aircraft of that type with every click.
        if (!player.getAbilities().instabuild) {
            aircraft.held().shrink(1);
        }
        // Compass degrees, not the internal yaw: describeLaunch prints the number as a bearing, and
        // the two conventions are 180 degrees apart.
        AutopilotFeedback.success(player, AutopilotSpawner.describeLaunch(plane, target, distance,
            AutopilotMath.compassHeading(bearing)) + " Warhead: " + plane.warhead(blast).describe() + ". "
            + AutopilotSpawner.describeAirframe(plane));
        return InteractionResult.CONSUME;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        Selection aircraft = select(player, hand, stack);

        if (player.isShiftKeyDown()) {
            // One gesture, two settings. The distance advances on every use, and the blast advances
            // one step each time the distance wraps back to the start — so the pair walks through
            // every combination without inventing a second gesture the game does not offer for a
            // held item. Both values are printed on every press, which is what makes it legible.
            int current = getDistance(stack);
            int index = DISTANCES.length - 1;
            for (int i = 0; i < DISTANCES.length; i++) {
                if (DISTANCES[i] == current) {
                    index = i;
                    break;
                }
            }
            int next = DISTANCES[(index + 1) % DISTANCES.length];
            stack.set(AutopilotComponents.STRIKE_DISTANCE, next);

            Blast blast = getBlast(stack);
            if ((index + 1) % DISTANCES.length == 0) {
                // Strength only. The block-breaking and incendiary flags are left exactly as
                // /autopilot tool set them — a gesture meant for the two numbers must not quietly
                // undo the two settings it does not show.
                blast = new Blast(BLASTS[nextIndex(BLASTS, blast.power())], blast.breaksBlocks(), blast.fire());
                stack.set(AutopilotComponents.STRIKE_BLAST, blast.power());
            }
            AutopilotFeedback.info(player, "Strike spawn distance: " + next
                + " blocks, blast " + describeBlast(blast, aircraft.type()) + describeBearing(stack)
                + ", aircraft " + aircraft.label() + ".");
        } else {
            AutopilotFeedback.info(player, "Spawn distance " + getDistance(stack) + " blocks, blast "
                + describeBlast(getBlast(stack), aircraft.type()) + describeBearing(stack) + ", aircraft " + aircraft.label() + ". "
                + RunwayOccupancy.activeCount() + "/" + AutopilotConfig.MAX_ACTIVE_AUTOPILOTS
                + " autopilot aircraft active.");
        }
        if (aircraft.refusal() != null) {
            AutopilotFeedback.warn(player, aircraft.refusal());
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                Consumer<Component> builder, TooltipFlag flag) {
        builder.accept(Component.translatable(SimplePlanesMod.MODID + ".strike_tool_desc"));
        builder.accept(Component.translatable(SimplePlanesMod.MODID + ".strike_tool_distance", getDistance(stack)));
        AircraftType type = getType(stack);
        Blast blast = getBlast(stack);
        builder.accept(Component.translatable(SimplePlanesMod.MODID + ".strike_tool_blast", type.isDrone()
            ? Component.translatable(SimplePlanesMod.MODID + ".strike_tool_drone_charge", type.warhead(blast).describe())
            : Component.literal(blast.describe())));
        builder.accept(Component.translatable(SimplePlanesMod.MODID + ".strike_tool_aircraft",
            AutopilotText.tr("airframe." + type.getSerializedName(), type.getSerializedName())));
        Integer bearing = getBearing(stack);
        builder.accept(bearing == null
            ? Component.translatable(SimplePlanesMod.MODID + ".strike_tool_bearing_auto")
            : Component.translatable(SimplePlanesMod.MODID + ".strike_tool_bearing",
                String.format("%03d", bearing)));
        builder.accept(Component.translatable(SimplePlanesMod.MODID + ".strike_tool_aircraft_hint"));
    }
}
