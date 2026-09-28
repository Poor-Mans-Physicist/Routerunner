package com.routerunner.calib;

import com.routerunner.lane.LegTimeModel;
import com.routerunner.lane.TrailPricer;
import com.routerunner.solver.ChainModel;
import com.routerunner.solver.P;
import com.routerunner.solver.SolidGrid;

import java.util.ArrayList;
import java.util.List;

/** The room-exit measurement behind the player calibration and the per-lap predictions. */
public final class RoomMeasure {
    private RoomMeasure() {}

    /** Room clumpiness: the size of the touching group the average chest sits in (Σ group size² / chests); 0 when empty. */
    public static double clump(List<P> chests) {
        if (chests.isEmpty()) return 0.0;
        int[][] comps = ChainModel.components(1, chests);
        double sum = 0;
        for (int i = 0; i < chests.size(); i++) sum += comps[1][comps[0][i]];
        return sum / chests.size();
    }

    /**
     * Measure one room visit. {@code trail} and {@code breaks} as in {@link TrailPricer#price}, {@code ticks}
     * {activeMs, x, z, yaw, pitch, onGround} per client tick, {@code chests} the room's target chests at solve (local),
     * {@code sec} the visit's active seconds, {@code planYield}/{@code planS} the benchmark (shape) plan from the entry
     * point (0 when none), {@code switchS} the seconds since the previous room's last sample (0 when unknown).
     */
    public static PlayerCalibration.Room measure(SolidGrid grid, boolean vein, double speed, List<double[]> trail, List<double[]> breaks,
                                                 List<double[]> ticks, List<P> chests, double sec, double planYield, double planS,
                                                 boolean followed, double switchS) {
        LegTimeModel model = LegTimeModel.shape().forMiner(vein, speed);
        TrailPricer.Price p = TrailPricer.price(grid, model, trail, breaks);
        List<Double> bt = new ArrayList<>(breaks.size());
        for (double[] b : breaks) bt.add(b[0]);
        double idle = IdleFilter.idleSeconds(ticks, bt);
        return new PlayerCalibration.Room(vein, Math.max(0.0, sec - idle), idle, p.seconds(), clump(chests), breaks.size(),
                planYield, planS, followed, switchS);
    }
}
