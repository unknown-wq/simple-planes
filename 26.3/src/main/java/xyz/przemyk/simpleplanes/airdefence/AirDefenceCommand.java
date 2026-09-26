package xyz.przemyk.simpleplanes.airdefence;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.autopilot.AircraftType;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;
import xyz.przemyk.simpleplanes.items.PlaneItem;
import xyz.przemyk.simpleplanes.missile.LaunchSiloBlockEntity;
import xyz.przemyk.simpleplanes.missile.MissileTier;
import xyz.przemyk.simpleplanes.missile.SiloStructure;
import xyz.przemyk.simpleplanes.setup.SimplePlanesComponents;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * {@code /airdefence}: aircraft allegiance, the aircraft AD can see, a dry run of a silo's target choice and
 * the per-tier interceptor figures. Permission level 2. Documented in MISSILES.md.
 */
public final class AirDefenceCommand {

    private AirDefenceCommand() {}

    static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("airdefence")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));

            root.then(Commands.literal("aircraft")
                .executes(c -> aircraft(c, Double.MAX_VALUE))
                .then(Commands.argument("radius", DoubleArgumentType.doubleArg(1.0))
                    .executes(c -> aircraft(c, DoubleArgumentType.getDouble(c, "radius")))));

            root.then(Commands.literal("allegiance")
                .then(Commands.argument("targets", EntityArgument.entities())
                    .executes(c -> allegiance(c, null))
                    .then(Commands.literal("friendly").executes(c -> allegiance(c, Allegiance.FRIENDLY)))
                    .then(Commands.literal("hostile").executes(c -> allegiance(c, Allegiance.HOSTILE)))));

            root.then(Commands.literal("item")
                .then(Commands.literal("friendly").executes(c -> item(c, Allegiance.FRIENDLY)))
                .then(Commands.literal("hostile").executes(c -> item(c, Allegiance.HOSTILE))));

            root.then(Commands.literal("scan")
                .then(Commands.argument("silo", BlockPosArgument.blockPos()).executes(AirDefenceCommand::scan)));

            root.then(Commands.literal("spec").executes(AirDefenceCommand::spec));

            dispatcher.register(root);
            AllegianceOption.graft(dispatcher);
        });
    }

    private static int aircraft(CommandContext<CommandSourceStack> c, double radius) {
        ServerLevel level = c.getSource().getLevel();
        Vec3 from = c.getSource().getPosition();
        List<PlaneEntity> planes = AircraftRoster.loaded(level).stream()
            .filter(p -> p.position().distanceTo(from) <= radius)
            .sorted(Comparator.comparingDouble(p -> p.position().distanceTo(from)))
            .toList();
        ok(c, planes.size() + " aircraft loaded" + (radius < Double.MAX_VALUE ? " within " + (int) radius : "") + ".");
        for (PlaneEntity p : planes) ok(c, "  " + line(p, from));
        return planes.size();
    }

    static String line(PlaneEntity p, Vec3 from) {
        AircraftType type = AircraftType.of(p);
        Vec3 pos = p.position();
        return String.format(Locale.ROOT, "#%d %s %s pos=%.1f,%.1f,%.1f spd=%.2f dist=%.1f%s", p.getId(),
            type == null ? net.minecraft.world.entity.EntityType.getKey(p.getType()).getPath() : type.getSerializedName(), p.getAllegiance().getSerializedName(),
            pos.x, pos.y, pos.z, p.getDeltaMovement().length(), pos.distanceTo(from),
            p.isAutopilotEngaged() ? " autopilot" : "");
    }

    private static int allegiance(CommandContext<CommandSourceStack> c, @Nullable Allegiance set) throws CommandSyntaxException {
        Collection<? extends Entity> targets = EntityArgument.getEntities(c, "targets");
        Vec3 from = c.getSource().getPosition();
        int n = 0;
        for (Entity e : targets) {
            if (!(e instanceof PlaneEntity p)) continue;
            if (set != null) p.setAllegiance(set);
            ok(c, "  " + line(p, from));
            n++;
        }
        if (n == 0) return fail(c, "No aircraft among the targets (only planes and helicopters have an allegiance).");
        if (set != null) ok(c, n + " aircraft set " + set.getSerializedName() + ".");
        return n;
    }

    private static int item(CommandContext<CommandSourceStack> c, Allegiance set) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof PlaneItem)) stack = player.getOffhandItem();
        if (!(stack.getItem() instanceof PlaneItem)) return fail(c, "Hold a plane or helicopter item.");
        CompoundTag tag = stack.get(SimplePlanesComponents.ENTITY_TAG);
        tag = tag == null ? new CompoundTag() : tag.copy();
        tag.putString(Allegiance.NBT_KEY, set.getSerializedName());
        stack.set(SimplePlanesComponents.ENTITY_TAG, tag);
        return ok(c, "The held " + stack.getHoverName().getString() + " now places a " + set.getSerializedName() + " aircraft.");
    }

    private static int scan(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getLevel();
        BlockPos master = SiloStructure.masterOf(level, BlockPosArgument.getBlockPos(c, "silo"));
        if (master == null || !(level.getBlockEntity(master) instanceof LaunchSiloBlockEntity silo)) return fail(c, "No silo there.");
        MissileTier tier = silo.tier();
        InterceptorSpec spec = InterceptorSpec.of(tier);
        Vec3 mouth = SiloStructure.mouth(master, tier);
        long now = level.getGameTime();
        ok(c, String.format(Locale.ROOT, "Silo T%d at %s: detection %.0f, range %.0f, speed %.1f, fuse %.1f.",
            tier.tier, master.toShortString(), spec.detectionRadius(), spec.range, spec.speed(), spec.fuseRadius));
        for (PlaneEntity p : AircraftRoster.loaded(level)) {
            double d = AircraftRoster.aimPoint(p).distanceTo(mouth);
            String why = !p.isHostile() ? "friendly, ignored"
                : d > spec.detectionRadius() ? "out of detection"
                : Engagements.saturated(p.getUUID(), now, Engagements.silo(level, master)) ? "already engaged by " + InterceptorSpec.MAX_PER_TARGET
                : "candidate";
            ok(c, String.format(Locale.ROOT, "  #%d %s d=%.1f: %s", p.getId(), p.getAllegiance().getSerializedName(), d, why));
        }
        PlaneEntity pick = TargetSelector.select(level, master, tier, Engagements.silo(level, master));
        return pick == null ? ok(c, "Target: none.") : ok(c, "Target: #" + pick.getId() + ".");
    }

    private static int spec(CommandContext<CommandSourceStack> c) {
        for (InterceptorSpec s : InterceptorSpec.values()) {
            ok(c, String.format(Locale.ROOT, "T%d: speed %.1f b/t, accel %.2f, range %.0f, detection %.0f, fuse %.1f, turn %.0f deg/t, hatch %d t, flight limit %d t",
                s.tier.tier, s.speed(), s.tier.accel, s.range, s.detectionRadius(), s.fuseRadius, s.turnRate, s.hatchTicks, s.maxFlightTicks()));
        }
        return ok(c, "At most " + InterceptorSpec.MAX_PER_TARGET + " missiles per target; silos scan every "
            + InterceptorSpec.SCAN_INTERVAL + " ticks.");
    }

    static int ok(CommandContext<CommandSourceStack> c, String text) {
        c.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    static int fail(CommandContext<CommandSourceStack> c, String text) {
        c.getSource().sendFailure(Component.literal(text));
        return 0;
    }
}
