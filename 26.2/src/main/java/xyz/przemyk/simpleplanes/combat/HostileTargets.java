package xyz.przemyk.simpleplanes.combat;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;

/**
 * What the gunship shoots at.
 *
 * <h2>Which of the three vanilla answers, and why</h2>
 * There are three plausible definitions of "hostile" in {@code /opt/mc-src} and they do not agree.
 *
 * <ul>
 *   <li><b>{@code net.minecraft.world.entity.monster.Enemy}</b> — a marker interface with nothing in
 *       it but XP constants. It is what <em>vanilla's own defenders</em> use to decide what to
 *       attack: {@code IronGolem} ({@code target instanceof Enemy && !(target instanceof Creeper)}),
 *       {@code SnowGolem}, {@code Shulker} and {@code ConduitBlockEntity} all test exactly this. It
 *       is also the thing a mod implements when it means "this mob is a monster".</li>
 *   <li><b>{@code MobCategory.MONSTER}</b> — a <em>spawning</em> classification, not a behavioural
 *       one. In 26.2 forty-five entity types carry it, and one of them is {@code ZOMBIE_HORSE}: a
 *       completely passive rideable animal that does not extend {@code Monster} and is not
 *       {@code Enemy}. Shooting a zombie horse out from under a player is the kind of thing that
 *       gets a feature turned off. It also misses anything a mod registers in another category and
 *       still means to be hostile.</li>
 *   <li><b>{@code Mob#getTarget() != null}</b> — "hostile right now". Attractive, and wrong on its
 *       own as the <em>primary</em> rule for a reason that is fatal here: a mob only acquires a
 *       target when it has one, and a skeleton has no interest in an unmanned helicopter hovering
 *       twenty blocks up. With no player standing underneath, nothing in a hundred-block radius
 *       would ever have a target and the gunship would hover with a full magazine and never fire.
 *       It is the hostility test that cannot see a hostile mob. It is worse than that:
 *       {@code Mob#getTarget()} returns a {@code LivingEntity}, and a {@code PlaneEntity} is not one,
 *       so a mob <em>cannot</em> target the gunship at all — the rule can never fire on the aircraft
 *       it is meant to defend.</li>
 * </ul>
 *
 * <p><b>Chosen: {@code Enemy}, plus {@code getTarget()} as a second, additive rule.</b> {@code Enemy}
 * is the primary test, so the gunship engages exactly what an iron golem would engage — the same
 * answer the vanilla world already gives, including for modded mobs, and with no dependency on
 * spawn categories. The {@code getTarget()} clause is then added on top to catch <em>provoked
 * neutrals</em>: a wolf pack, an angry bee swarm, an iron golem that has decided a player is a
 * criminal. Those are not {@code Enemy} and are genuinely trying to kill somebody, and the clause
 * only fires when their current target is a player or the gunship itself, so an owned wolf fighting
 * a zombie is left alone. It is deliberately written against a player rather than against the
 * gunship: mobs cannot target a vehicle, so "is it attacking me" is not a question that has an
 * answer here, and "is it attacking a person" is.
 *
 * <p><b>Creepers are engaged</b>, unlike an iron golem's rule. The golem excludes them because it
 * fights at arm's length and would be inside the blast; a gunship is twenty blocks up and is exactly
 * the thing that should be killing creepers before they reach anyone.
 *
 * <p><b>Endermen are engaged</b>, because they are {@code Enemy} and vanilla's golems shoot at them
 * too. They teleport when hit, which reads as a miss and is a genuine limitation rather than an
 * aiming defect.
 *
 * <p><b>Players are never a target, under any rule.</b> See {@link GunshipSortie} for the rest of
 * the fire-discipline rule — not targeting a player is not the same as not hitting one.
 */
public final class HostileTargets {

    private HostileTargets() {}

    /**
     * @param gunship the aircraft doing the shooting, so a mob that has decided to attack <em>it</em>
     *                counts as hostile
     */
    public static boolean isHostile(Entity candidate, Entity gunship) {
        if (candidate instanceof Player || !candidate.isAlive() || candidate == gunship) {
            return false;
        }
        if (!(candidate instanceof LivingEntity)) {
            return false;
        }
        if (candidate instanceof Enemy) {
            return true;
        }
        if (candidate instanceof Mob mob) {
            return mob.getTarget() instanceof Player;
        }
        return false;
    }
}
