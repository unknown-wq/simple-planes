package xyz.przemyk.simpleplanes.api.drone;

/**
 * What a patrol drone is doing. Part of the stable drone API ({@link PatrolDrones#API_VERSION} 1).
 */
public enum DroneState {
    /** On the ground at or near its home, rotors off. Holds no chunk tickets. */
    PARKED,
    /** Climbing to cruise height above its home before starting the route. */
    TAKEOFF,
    /** Flying its route (or loitering over home when it has none). */
    PATROL,
    /** Orbiting a tracked entity. */
    TRACKING,
    /** Flying back to its home at cruise height. */
    RETURNING,
    /** Descending onto its home. */
    LANDING,
    /** Shot down, falling; removed when it hits the ground. */
    FALLING;

    /** Whether the drone is in the air and keeping its chunks loaded. */
    public boolean airborne() {
        return this != PARKED;
    }
}
