package dev.aura.player;

/** Monotonic listening clock. Position jumps and stalled/buffering output earn no time. */
final class ListeningClock {
    private long elapsed, position;
    private boolean playing, ready;

    long sample(long now, long pos, boolean active, float speed) {
        long dt = now - elapsed, advance = pos - position, result = 0;
        if (ready
                && playing
                && dt > 0
                && dt <= 15000
                && advance > 0
                && Math.abs(advance - dt * Math.max(.1f, speed)) <= Math.max(1500, dt * .35))
            result = Math.min(dt, Math.round(advance / Math.max(.1f, speed)));
        elapsed = now;
        position = pos;
        playing = active;
        ready = true;
        return result;
    }

    void reset() {
        ready = false;
        playing = false;
    }
}
