package com.routerunner;

import com.mojang.logging.LogUtils;
import com.routerunner.adaptive.Adaptive;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The per-vault density gate: a vault passes as soon as the player enters a room holding at least
 * {@link #MIN_DENSITY} chests of any scanned type (the room's largest count over every scan of it, so a room first
 * seen half-loaded from the hallway still counts once it fills in). Until then it is undecided: the run log keeps
 * writing and the adaptive model stages what it sees. A vault that never passes is rejected at exit, or when a
 * rejected vault id comes back after a reconnect: its run log is deleted (unless config {@code forceRunLog} is on,
 * which keeps every log) and the adaptive model drops what it staged. The rejection sticks for that vault id until the
 * game restarts.
 */
public final class VaultGate {
    private static final Logger LOG = LogUtils.getLogger();
    public static final double MIN_DENSITY = 150.0;

    public enum State { UNDECIDED, PASS, FAIL }

    private static final Map<Long, int[]> maxCounts = new HashMap<>();
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
        maxCounts.clear();
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
            LOG.info("[Routerunner] vault {} was already rejected by the density gate this session; not learning from it.", id);
            dropLog("rejected earlier this session");
            Adaptive.onGateDecided(false);
        com.routerunner.calib.PlayerCalibration.onGateDecided(false);
        }
    }

    /** A cell was scanned with per-type target counts ({@link RoomGeometry#TYPES} order); the gate keeps each type's largest count. */
    public static void onScan(long cellKey, int[] counts) {
        if (counts == null) return;
        boolean check;
        synchronized (VaultGate.class) {
            int[] m = maxCounts.get(cellKey);
            if (m == null) {
                maxCounts.put(cellKey, counts.clone());
            } else {
                for (int i = 0; i < m.length && i < counts.length; i++) m[i] = Math.max(m[i], counts[i]);
            }
            check = state == State.UNDECIDED && entered.contains(cellKey);
        }
        if (check) tryPass(cellKey);
    }

    /** The player stands in the cell. */
    public static void onEnter(long cellKey) {
        synchronized (VaultGate.class) {
            if (state != State.UNDECIDED || !entered.add(cellKey)) return;
        }
        tryPass(cellKey);
    }

    /** Vault exit: a vault that never had a room of {@link #MIN_DENSITY} chests is rejected now. */
    public static void onVaultExit() {
        int rooms;
        int densest;
        String id;
        synchronized (VaultGate.class) {
            if (state != State.UNDECIDED) return;
            state = State.FAIL;
            id = vaultId;
            if (id != null) rejectedVaults.add(id);
            rooms = 0;
            densest = 0;
            for (long key : entered) {
                int[] c = maxCounts.get(key);
                if (c == null || max(c) < DensityTracker.MIN_ROOM_CHESTS) continue;
                rooms++;
                densest = Math.max(densest, max(c));
            }
        }
        LOG.info("[Routerunner] density gate rejected this vault at exit: no entered room held {} chests of any type ({} room(s), densest {}); no learning from it.",
                (int) MIN_DENSITY, rooms, densest);
        dropLog("no room of " + (int) MIN_DENSITY + "+ chests");
        Adaptive.onGateDecided(false);
        com.routerunner.calib.PlayerCalibration.onGateDecided(false);
    }

    /** Pass the vault if the entered cell holds at least {@link #MIN_DENSITY} chests of some type. */
    private static void tryPass(long cellKey) {
        int best;
        String type;
        synchronized (VaultGate.class) {
            if (state != State.UNDECIDED) return;
            int[] c = maxCounts.get(cellKey);
            if (c == null) return;
            int bi = 0;
            for (int i = 1; i < c.length; i++) if (c[i] > c[bi]) bi = i;
            best = c[bi];
            if (best < MIN_DENSITY) return;
            type = bi < RoomGeometry.TYPES.length ? RoomGeometry.TYPES[bi] : "type " + bi;
            state = State.PASS;
        }
        LOG.info("[Routerunner] density gate passed: a room with {} {} chests (threshold {} of any type).", best, type, (int) MIN_DENSITY);
        RunLog.densityGate(best, 1, MIN_DENSITY);
        Adaptive.onGateDecided(true);
        com.routerunner.calib.PlayerCalibration.onGateDecided(true);
    }

    /** Delete this vault's run log, unless forced logging keeps it. */
    private static void dropLog(String why) {
        if (RouterunnerConfig.get().forceRunLog) {
            LOG.info("[Routerunner] forced logging is on: keeping this vault's run log ({}).", why);
            return;
        }
        RunLog.discardVault(why);
    }

    private static int max(int[] c) {
        int m = 0;
        for (int v : c) m = Math.max(m, v);
        return m;
    }
}
