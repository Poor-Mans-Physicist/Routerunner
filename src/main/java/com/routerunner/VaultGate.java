package com.routerunner;

import com.mojang.logging.LogUtils;
import com.routerunner.adaptive.Adaptive;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The per-vault density gate: a vault whose rooms hold fewer than {@link #MIN_DENSITY} target chests on average is
 * too sparse to learn from or to be worth a run log. Decided once, from the first {@link #DECIDE_ROOMS} rooms
 * entered (a room = a cell whose first scan held at least {@link DensityTracker#MIN_ROOM_CHESTS} chests of some type),
 * or at vault exit from however many rooms there were. The density is the tracked chest type's average first-scan
 * count, or under AUTO / ALL the highest average of any type. A rejected vault's run log is deleted, nothing more is
 * written for it, and the adaptive model drops what it had staged; the rejection sticks for that vault id until the
 * game restarts, so a reconnect does not start a fresh log.
 */
public final class VaultGate {
    private static final Logger LOG = LogUtils.getLogger();
    public static final double MIN_DENSITY = 150.0;
    public static final int DECIDE_ROOMS = 3;

    public enum State { UNDECIDED, PASS, FAIL }

    private static final Map<Long, int[]> firstCounts = new HashMap<>();
    private static final Set<Long> entered = new LinkedHashSet<>();
    private static final Set<String> rejectedVaults = new HashSet<>();
    private static State state = State.UNDECIDED;
    private static String vaultId = null;

    private VaultGate() {}

    public static synchronized State state() {
        return state;
    }

    /** Vault entry (or reconnect): start undecided, unless this vault id was already rejected this session. */
    public static synchronized void reset() {
        firstCounts.clear();
        entered.clear();
        state = State.UNDECIDED;
        vaultId = null;
    }

    /** The vault id resolved; a vault already rejected this session stays rejected. */
    public static void onVaultId(String id) {
        boolean again;
        synchronized (VaultGate.class) {
            vaultId = id;
            again = id != null && rejectedVaults.contains(id) && state != State.FAIL;
            if (again) state = State.FAIL;
        }
        if (again) {
            LOG.info("[Routerunner] vault {} was already rejected by the density gate this session; not logging or learning from it.", id);
            RunLog.discardVault("rejected earlier this session");
            Adaptive.onGateDecided(false);
        }
    }

    /** A cell was scanned with per-type target counts ({@link RoomGeometry#TYPES} order); only the first scan counts. */
    public static synchronized void onScan(long cellKey, int[] counts) {
        if (counts == null || firstCounts.containsKey(cellKey)) return;
        firstCounts.put(cellKey, counts.clone());
    }

    /** The player stands in the cell. */
    public static void onEnter(long cellKey) {
        boolean decide;
        synchronized (VaultGate.class) {
            if (state != State.UNDECIDED || !entered.add(cellKey)) return;
            decide = rooms() >= DECIDE_ROOMS;
        }
        if (decide) decide("first " + DECIDE_ROOMS + " rooms");
    }

    /** Vault exit: a vault that never reached the room count is judged on what it had (no rooms = rejected). */
    public static void onVaultExit() {
        boolean undecided;
        synchronized (VaultGate.class) {
            undecided = state == State.UNDECIDED;
        }
        if (undecided) decide("vault exit");
    }

    private static int rooms() {
        int n = 0;
        for (long key : entered) {
            int[] c = firstCounts.get(key);
            if (c != null && max(c) >= DensityTracker.MIN_ROOM_CHESTS) n++;
        }
        return n;
    }

    private static void decide(String when) {
        boolean pass;
        int n;
        double dens;
        String id;
        synchronized (VaultGate.class) {
            if (state != State.UNDECIDED) return;
            int types = RoomGeometry.TYPES.length;
            double[] sum = new double[types];
            n = 0;
            for (long key : entered) {
                int[] c = firstCounts.get(key);
                if (c == null || max(c) < DensityTracker.MIN_ROOM_CHESTS) continue;
                for (int i = 0; i < types && i < c.length; i++) sum[i] += c[i];
                n++;
            }
            int fixed = fixedTypeIndex();
            dens = 0;
            if (n > 0) {
                if (fixed >= 0) dens = sum[fixed] / n;
                else for (double v : sum) dens = Math.max(dens, v / n);
            }
            pass = n > 0 && dens >= MIN_DENSITY;
            state = pass ? State.PASS : State.FAIL;
            id = vaultId;
            if (!pass && id != null) rejectedVaults.add(id);
        }
        String what = String.format(Locale.ROOT, "%.0f %s chests/room over %d room(s) at %s", dens, typeLabel(), n, when);
        if (pass) {
            LOG.info("[Routerunner] density gate passed: {} (threshold {}).", what, (int) MIN_DENSITY);
            RunLog.densityGate(dens, n, MIN_DENSITY);
            Adaptive.onGateDecided(true);
        } else {
            LOG.info("[Routerunner] density gate rejected this vault: {}, under {}; no run log and no learning for it.", what, (int) MIN_DENSITY);
            RunLog.discardVault("density " + String.format(Locale.ROOT, "%.0f", dens) + " < " + (int) MIN_DENSITY);
            Adaptive.onGateDecided(false);
        }
    }

    /** Index of the configured chest type, or -1 when tracking is AUTO or ALL (then the densest type counts). */
    private static int fixedTypeIndex() {
        String t;
        switch (RouterunnerConfig.get().trackedChest) {
            case GILDED: t = "gilded"; break;
            case ORNATE: t = "ornate"; break;
            case LIVING: t = "living"; break;
            case WOODEN: t = "wooden"; break;
            default: return -1;
        }
        for (int i = 0; i < RoomGeometry.TYPES.length; i++) if (RoomGeometry.TYPES[i].equals(t)) return i;
        LOG.error("[Routerunner] density gate: tracked chest type {} is not a scanned type; judging the densest type instead.", t);
        return -1;
    }

    private static String typeLabel() {
        int i = fixedTypeIndex();
        return i >= 0 ? RoomGeometry.TYPES[i] : "densest-type";
    }

    private static int max(int[] c) {
        int m = 0;
        for (int v : c) m = Math.max(m, v);
        return m;
    }
}
