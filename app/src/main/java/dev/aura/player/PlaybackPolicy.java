package dev.aura.player;

/** Decisions shared by focus handling and tests; bit-perfect streams never get software ducked. */
public final class PlaybackPolicy {
    public static boolean pauseForDuck(boolean requested,boolean perfect,boolean direct){return requested||perfect||direct;}
    public static boolean resumeAfterDetach(boolean wasPlaying,boolean pause){return wasPlaying&&!pause;}
}
