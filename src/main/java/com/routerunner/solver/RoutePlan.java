package com.routerunner.solver;

import java.util.List;

/** Result of {@link RoutePlanner#planRoute} — all positions in the grid's LOCAL coordinate space. */
public final class RoutePlan {
    /** Waypoints in follow order (the HUD sequence) with per-waypoint planned data. */
    public List<WP> waypoints;
    /** Route polylines to draw: walk legs (floor paths), trident bursts, and open-space sprint straight-shots. */
    public List<Seg> segments;
    /** The final leg — the routed path from the last stop to the exit gate (for exit guidance). */
    public Seg exitLeg;
    /** Per-chest-index state: 'b' = break waypoint, 'c' = chain-cleared, 's' = skipped. */
    public char[] state;
    public int collected;
    public double walkBlocks;
    public double flyBlocks; // total trident/dash burst distance (kept name for compatibility)
    public double hotSpotRate; // top-quartile break marginal (chests per travel/aim cost) — the room's opportunity rate

    /** Continuous densified render path (local coords), entrance→exit, drawn from the player forward. */
    public List<P> path;
    public char[] pathMode;     // per point: 'w' walk, 'd' drop (fall off a ledge), 't' trident, 's' sprint straight-shot, 'x' gap (don't draw)
    public boolean[] pathWhite; // per point: edge i->i+1 is the final approach into a turnaround (render white)
    /** Walk graph retained from solving, so a from-player connector can be pathed cheaply at runtime. */
    public WalkGraph graph;

    /** A route segment: 'w' walk floor path, 'd' drop (fall off a ledge), 't' trident burst, 's' sprint straight-shot, 'x' gap; with its polyline. */
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
        public final int plannedCleared;   // chain-group size this trigger is expected to remove
        public final char segMode;         // 'w', 'd' or 't' — mode of the route segment leading into it
        public final double cumDist;       // cumulative planned route distance (blocks) at this waypoint
        public final int pathIndex;        // index into RoutePlan.path where this chest sits (continuous render)
        /** The route REVERSES here: you arrive, break, and leave back the way you came (see Params.turnaroundDeg). */
        public boolean turnaround = false;
        public double outDirX = 0;         // unit horizontal direction the route LEAVES this waypoint in
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
