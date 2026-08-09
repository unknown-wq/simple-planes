package xyz.przemyk.simpleplanes.combat;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.entities.HelicopterEntity;
import xyz.przemyk.simpleplanes.misc.MathUtil;
import xyz.przemyk.simpleplanes.setup.SimplePlanesEntities;
import xyz.przemyk.simpleplanes.setup.SimplePlanesItems;
import xyz.przemyk.simpleplanes.upgrades.engines.furnace.FurnaceEngineUpgrade;

import java.util.List;

/**
 * {@code /gunship} — spawns an armed helicopter, holds it over a point, and lets it shoot.
 *
 * <pre>
 * /gunship launch &lt;x y z&gt; [arrows] [rate] [ammunition] [altitude]
 * /gunship status
 * /gunship stop
 * </pre>
 *
 * <p>Like {@code /autopilot}, <b>no subcommand requires a player</b>: the position is explicit, so
 * the whole thing runs from the server console, a command block or a datapack function. A player is
 * only an optional owner for the reports, and the source of {@code ~ ~ ~}.
 *
 * <h2>The arguments</h2>
 * <table>
 *   <tr><td>{@code arrows}</td><td>1–1024, default {@value GunshipSortie#DEFAULT_MAGAZINE}</td>
 *       <td>rounds in the magazine — two full stacks by default</td></tr>
 *   <tr><td>{@code rate}</td><td>0.5–20.0, default {@code 10.0}</td>
 *       <td><b>rounds per second</b></td></tr>
 *   <tr><td>{@code ammunition}</td><td>any projectile item, default {@code minecraft:arrow}</td>
 *       <td>resolved from the item registry</td></tr>
 *   <tr><td>{@code altitude}</td><td>2–120, default {@value GunshipSortie#DEFAULT_ALTITUDE}</td>
 *       <td>blocks above the ground the gunship holds</td></tr>
 * </table>
 *
 * <p><b>Rounds per second, not a tick interval.</b> Both were on the table and the unit was chosen
 * on how the command reads: "ten rounds a second" is what a person means, while "every second tick"
 * is what a programmer means, and the two are not even the same kind of number — halving a rate
 * halves the volume of fire, halving an interval doubles it. The rate is also the more expressive of
 * the two, because it is not quantised: 7.5 rounds/s is a legal rate and there is no tick interval
 * that expresses it. It is honoured exactly, by a credit accumulator rather than a modulo, so 7.5/s
 * really is 15 rounds in 40 ticks and not 13 or 20. The ceiling of 20 is the tick rate itself — one
 * arrow entity per tick is the hard limit of "one arrow entity per round".
 *
 * <p>The ammunition is <b>an item argument</b>, not a list of names, so it tab-completes from the
 * registry, accepts data components ({@code minecraft:tipped_arrow[potion=strong_harming]}), and
 * works for modded arrows without knowing they exist. See {@link ArrowLoadout}.
 */
public final class GunshipCommand {

    private static final int MAX_MAGAZINE = 1024;
    /** Coal loaded into the furnace engine. One coal is 1600 burn ticks against a fuel cost of 6. */
    private static final int FUEL_COAL = 64;

    private GunshipCommand() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("gunship")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));

            // Progressively optional positionals, every level running the same method: the optional
            // arguments are read back by name from the parsed node list, so the tree stays flat
            // instead of gaining a lambda per combination. Same shape as /autopilot strike.
            root.then(Commands.literal("launch")
                .then(Commands.argument("at", BlockPosArgument.blockPos())
                    .executes(GunshipCommand::launch)
                    .then(Commands.argument("arrows", IntegerArgumentType.integer(1, MAX_MAGAZINE))
                        .executes(GunshipCommand::launch)
                        .then(Commands.argument("rate", DoubleArgumentType.doubleArg(0.5, 20.0))
                            .executes(GunshipCommand::launch)
                            .then(Commands.argument("ammunition", ItemArgument.item(registry))
                                .executes(GunshipCommand::launch)
                                .then(Commands.argument("altitude", DoubleArgumentType.doubleArg(2.0, 120.0))
                                    .executes(GunshipCommand::launch)))))));

            root.then(Commands.literal("status").executes(GunshipCommand::status));
            root.then(Commands.literal("stop").executes(GunshipCommand::stop));

            dispatcher.register(root);
        });
    }

    private static int launch(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();

        if (!GunshipRegistry.canLaunchAnother()) {
            source.sendFailure(Component.literal("Already flying " + GunshipRegistry.MAX_ACTIVE
                + " gunships, which is the limit. /gunship stop first."));
            return 0;
        }

        int magazine = optionalInt(context, "arrows", GunshipSortie.DEFAULT_MAGAZINE);
        double rate = optionalDouble(context, "rate", GunshipSortie.DEFAULT_RATE);
        double altitude = optionalDouble(context, "altitude", GunshipSortie.DEFAULT_ALTITUDE);

        ItemStack ammunition = has(context, "ammunition")
            ? ItemArgument.getItem(context, "ammunition").createItemStack(1)
            : new ItemStack(Items.ARROW);
        ArrowLoadout loadout = ArrowLoadout.of(ammunition);
        if (loadout == null) {
            source.sendFailure(Component.literal(ammunition.getItem().getName(ammunition).getString()
                + " cannot be fired: the gunship loads anything the registry says is a projectile"
                + " (arrows, tipped arrows, spectral arrows, modded arrows), and that item is not one."));
            return 0;
        }

        BlockPos at = BlockPosArgument.getBlockPos(context, "at");
        ServerPlayer owner = source.getPlayer();

        GunshipSortie sortie = spawn(level, at, loadout, magazine, rate, altitude, owner);
        if (sortie == null) {
            source.sendFailure(Component.literal("Could not create the helicopter."));
            return 0;
        }
        GunshipRegistry.add(sortie);
        source.sendSuccess(() -> Component.literal(sortie.launchLine()), true);
        return 1;
    }

    /**
     * Creates the aircraft, arms it and hands it to a sortie.
     *
     * <p>Two things are done to the airframe and both are ordinary: a <b>furnace engine loaded with
     * coal</b>, because {@code PlaneEntity#isPowered} needs a real power source for an aircraft with
     * nobody aboard and there is no reason for a gunship to be an exception, and the chunks under it
     * are made resident before it is added, because an entity put into a chunk that is not loaded
     * does not tick.
     */
    private static @Nullable GunshipSortie spawn(ServerLevel level, BlockPos at, ArrowLoadout loadout,
                                                 int magazine, double rate, double altitude,
                                                 @Nullable ServerPlayer owner) {
        GunshipRegistry.loadAround(level, at);

        HelicopterEntity helicopter = SimplePlanesEntities.HELICOPTER.get()
            .create(level, EntitySpawnReason.COMMAND);
        if (helicopter == null) {
            return null;
        }
        helicopter.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        // The physics reads the attitude off the quaternion rather than off yRot, so all three have
        // to agree with the spawn heading or the machine snaps back to whatever they held.
        double heading = owner == null ? 0.0 : owner.getYRot();
        helicopter.setYRot((float) heading);
        helicopter.yRotO = (float) heading;
        helicopter.setQ(MathUtil.toQuaternionf(heading, 0, 0));
        helicopter.setQ_Client(MathUtil.toQuaternionf(heading, 0, 0));
        helicopter.setQ_prev(MathUtil.toQuaternionf(heading, 0, 0));

        FurnaceEngineUpgrade engine = new FurnaceEngineUpgrade(helicopter);
        helicopter.addUpgradeUsingWrench(SimplePlanesItems.FURNACE_ENGINE.get().getDefaultInstance(), engine);
        engine.container.setItem(0, new ItemStack(Items.COAL, FUEL_COAL));

        level.addFreshEntity(helicopter);
        GunshipRegistry.keepChunksLoaded(level, helicopter.position());

        return new GunshipSortie(helicopter, level, loadout, magazine, rate, altitude, owner);
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        List<GunshipSortie> active = GunshipRegistry.active();
        if (active.isEmpty()) {
            context.getSource().sendSuccess(() -> Component.literal("No gunships airborne."), false);
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.literal(active.size() + " gunship(s):"), false);
        for (GunshipSortie sortie : active) {
            context.getSource().sendSuccess(() -> Component.literal(sortie.statusLine()), false);
        }
        return active.size();
    }

    private static int stop(CommandContext<CommandSourceStack> context) {
        int stopped = GunshipRegistry.stopAll();
        context.getSource().sendSuccess(
            () -> Component.literal(stopped == 0 ? "No gunships airborne." : "Recalled " + stopped + " gunship(s)."),
            true);
        return stopped;
    }

    // ------------------------------------------------------------------

    /**
     * Brigadier has no notion of a default: an argument that was not parsed is simply absent, and
     * asking for it throws. The list of nodes that <em>were</em> matched is public and an argument
     * node carries its name, so walking it is the exception-free way to ask.
     */
    private static boolean has(CommandContext<CommandSourceStack> context, String name) {
        for (ParsedCommandNode<CommandSourceStack> node : context.getNodes()) {
            if (node.getNode().getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static int optionalInt(CommandContext<CommandSourceStack> context, String name, int fallback) {
        return has(context, name) ? IntegerArgumentType.getInteger(context, name) : fallback;
    }

    private static double optionalDouble(CommandContext<CommandSourceStack> context, String name, double fallback) {
        return has(context, name) ? DoubleArgumentType.getDouble(context, name) : fallback;
    }
}
