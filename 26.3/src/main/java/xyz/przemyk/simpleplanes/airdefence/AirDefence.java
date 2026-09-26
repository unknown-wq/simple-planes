package xyz.przemyk.simpleplanes.airdefence;

/**
 * Air defence: aircraft allegiance, target selection, pursuit guidance and the {@code /airdefence} command.
 * {@link #init()} is called once from the mod initialiser, after the autopilot and gunship commands are
 * registered (their spawning subcommands get the {@code hostile} keyword grafted on).
 */
public final class AirDefence {

    private AirDefence() {}

    public static void init() {
        AircraftRoster.init();
        Engagements.init();
        AirDefenceCommand.register();
    }
}
