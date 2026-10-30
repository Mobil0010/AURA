package dev.aura.player;

/** New Bluetooth connections replace the phone fallback, but never steal an external route. */
final class RoutePolicy {
    static boolean selectNew(boolean enabled, boolean wired, boolean bluetooth, boolean external) {
        return enabled && (wired || bluetooth && !external);
    }
}
