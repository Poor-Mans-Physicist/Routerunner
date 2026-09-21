package com.routerunner.solver;

import java.util.List;

/** Result of {@link RoutePlanner#planRoute} — all positions in the grid's LOCAL coordinate space. */
public final class RoutePlan {
    /** Waypoints in follow order (the HUD sequence) with per-waypoint planned data. */
    public List<WP> waypoints;
    /** Route polylines to draw: walk legs (floor paths), trident bursts, and open-space sprint straight-shots. */
    public List<Seg> segments;
    /** The final leg: the routed path from the last stop to the exit gate. */
    public Seg exitLeg;
    /** Per-chest-index state: 'b' = break waypoint, 'c' = chain-cleared, 's' = skipped. */
    public char[] state;
    public int collected;
    public double walkBlocks;
    /** Total trident/dash burst distance (blocks). */
    public double flyBlocks;
    /** Top-quartile break marginal (chests per unit travel/aim cost): the room's opportunity rate. */
    public double hotSpotRate;

    /** Continuous densified render path (local coords), entrance to exit. */
    public List<P> path;
    /** Per point: 'w' walk, 'd' drop, 't' trident, 's' sprint straight-shot, 'x' gap (not drawn). */
    public char[] pathMode;
    /** Per point: edge i→i+1 is the final approach into a turnaround (rendered white). */
    public boolean[] pathWhite;
    /** Walk graph retained from solving, so a from-player connector can be pathed cheaply at runtime. */
    public WalkGraph graph;

    /** A route segment and its polyline; mode is 'w' walk, 'd' drop, 't' trident, 's' sprint straight-shot or 'x' gap. */
    public static final class Seg {
        public final char mode;
        public final List<P> poly;

        public Seg(char mode, List<P> poly) {
            this.mode = mode;
            this.poly = poly;
        }
    }

    /** One waypoint: the chest to break, what the chain is expected to clear, and route bookkeeping. */
    public static final class WP {
        public final P pos;
        /** Chain-group size this break is expected to clear. */
        public final int plannedCleared;
        /** Mode of the route segment leading into this waypoint ('w', 'd' or 't'). */
        public final char segMode;
        /** Cumulative planned route distance at this waypoint (blocks). */
        public final double cumDist;
        /** Index into {@link RoutePlan#path} where this chest sits. */
        public final int pathIndex;
        /** The route reverses here: arrive, break, and leave back the way you came (see Params.turnaroundDeg). */
        public boolean turnaround = false;
        /** Unit horizontal direction the route leaves this waypoint in. */
        public double outDirX = 0;
        public double outDirZ = 0;

        public WP(P pos, int plannedCleared, char segMode, double cumDist, int pathIndex) {
            this.pos = pos;
            this.plannedCleared = plannedCleared;
            this.segMode = segMode;
            this.cumDist = cumDist;
            this.pathIndex = pathIndex;
        }
    }
}
