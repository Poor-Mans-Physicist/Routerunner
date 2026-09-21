package com.routerunner.solver;

/** Integer block position in the solver's LOCAL voxel space (room-cell relative). MC-free. */
public record P(int x, int y, int z) {
    public P offset(int dx, int dy, int dz) {
        return new P(x + dx, y + dy, z + dz);
    }
}
