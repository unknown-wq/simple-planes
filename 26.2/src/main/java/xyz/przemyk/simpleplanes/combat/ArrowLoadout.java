package xyz.przemyk.simpleplanes.combat;

import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileItem;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The magazine: what kind of arrow, and how one round becomes one entity.
 *
 * <h2>Resolved from the registry, not from an enum</h2>
 * The ammunition argument is a plain {@code ItemArgument}, so anything in {@code BuiltInRegistries.ITEM}
 * can be loaded and tab completion is vanilla's. The only test applied is that the item can make a
 * projectile — {@code item instanceof ProjectileItem} — which is exactly the interface a dispenser
 * uses and which {@code ArrowItem} and its subclasses implement.
 *
 * <p>That single test covers everything asked for and more:
 * <ul>
 *   <li>{@code minecraft:arrow} — {@code ArrowItem#asProjectile} builds a plain {@code Arrow};</li>
 *   <li>{@code minecraft:tipped_arrow} — the same class, and the potion rides along in the
 *       {@code ItemStack} that is handed to the {@code Arrow} constructor, so
 *       {@code minecraft:tipped_arrow[potion=strong_harming]} is loaded and fired exactly as written;</li>
 *   <li>{@code minecraft:spectral_arrow} — {@code SpectralArrowItem} overrides
 *       {@code asProjectile} and returns a {@code SpectralArrow};</li>
 *   <li>a modded arrow, or in fact a fireball or a snowball, works with no code here knowing about it.</li>
 * </ul>
 *
 * <p>Writing a hand-maintained enum of the vanilla arrow types would have meant three names, no
 * potion argument, and a fourth code path per mod. There is no branch on item identity anywhere in
 * this class.
 *
 * <h2>Why {@code asProjectile} and not {@code createArrow}</h2>
 * {@code ArrowItem#createArrow} takes a {@code LivingEntity} owner, and a helicopter is not one.
 * {@code ProjectileItem#asProjectile} is the ownerless dispenser path; the owner is attached
 * afterwards with {@code Projectile#setOwner(Entity)}, which accepts any entity. That matters for
 * more than bookkeeping — see {@link #spawn}.
 */
public final class ArrowLoadout {

    /**
     * Launch speed, blocks/tick. A fully drawn vanilla bow ({@code BowItem}) fires at 3.0, and
     * {@code AbstractArrow#onHitEntity} computes {@code damage = ceil(|v| * baseDamage)}, so this is
     * also what sets the round's punch: 6 HP at the muzzle for a plain arrow, decaying with range as
     * the arrow slows.
     */
    public static final float MUZZLE_VELOCITY = 3.0f;

    private final ItemStack round;

    private ArrowLoadout(ItemStack round) {
        this.round = round;
    }

    /** @return null if the item cannot be fired as a projectile */
    public static @Nullable ArrowLoadout of(ItemStack stack) {
        return stack.getItem() instanceof ProjectileItem ? new ArrowLoadout(stack) : null;
    }

    public Item item() {
        return round.getItem();
    }

    /**
     * Creates one round, points it along {@code velocity} and puts it in the world.
     *
     * <p><b>The owner is the helicopter.</b> {@code Projectile#canHitEntity} refuses any entity that
     * shares a root vehicle with the owner while the projectile has not yet left it, and an arrow
     * that starts inside the hull it was fired from is exactly that case — without the owner set,
     * the first round of every burst hits the gunship. It also makes the kill attributable: the
     * damage source is {@code arrow(projectile, owner)}.
     *
     * <p>Pickup is disabled. The magazine was conjured by a command and never existed as items, so
     * letting it be picked up off the ground would mint arrows out of nothing.
     *
     * @return the arrow, or null if the item's projectile is not an arrow after all
     */
    public @Nullable Projectile spawn(ServerLevel level, Vec3 muzzle, Vec3 velocity, Entity owner) {
        Projectile projectile = ((ProjectileItem) round.getItem())
            .asProjectile(level, muzzle, round, Direction.UP);
        projectile.setPos(muzzle.x, muzzle.y, muzzle.z);
        projectile.setOwner(owner);
        // shoot() normalises the direction and scales it by the power, so handing it the solved
        // velocity and its own length reproduces that velocity exactly. Zero inaccuracy: the spread
        // a bow gives a mob is a handicap, and this weapon's whole point is that it is aimed.
        projectile.shoot(velocity.x, velocity.y, velocity.z, (float) velocity.length(), 0.0f);
        if (projectile instanceof AbstractArrow arrow) {
            arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
        }
        level.addFreshEntity(projectile);
        return projectile;
    }
}
