package com.routerunner;

import com.mojang.logging.LogUtils;
import com.routerunner.solver.P;
import com.routerunner.solver.RoutePlanner;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The run log: one JSONL file per vault at {@code config/routerunner/runs/vault_<stamp>_<vaultId>.jsonl}.
 * Field names are a contract; offline tooling reads them.
 *
 * <p>Every record carries {@code ev}, {@code ts} (wall clock) and {@code t} (the vault's ACTIVE clock,
 * monotonic for the whole vault — laps never reset it). A reconnect APPENDS to the existing file for the
 * same vault id, so one vault is always exactly one file. Events raised before the vault id resolves are
 * buffered in memory and flushed on {@link #open(String)}; nothing is ever written to an {@code _unknown}
 * file.
 *
 * <p>Thread safety: {@code room_solve} arrives from the solver thread, everything else from the client
 * tick, so every public entry point is {@code synchronized} on this class.
 */
public final class RunLog {
    private static final Logger LOG = LogUtils.getLogger();
    /** Run-log format version, stamped on vault_enter. */
    public static final int LOG_VERSION = 25;
    private static final SimpleDateFormat FILE_FMT = new SimpleDateFormat("yyyyMMdd_HHmmss");
    /** Maximum events buffered before the vault id resolves. */
    private static final int BUFFER_CAP = 3000;
    /** The pos stream flushes every this many records (~1 s at 20 Hz). */
    private static final int POS_FLUSH_EVERY = 20;

    private static BufferedWriter writer = null;
    private static Path currentFile = null;
    private static final Deque<String> pending = new ArrayDeque<>();
    private static int posSinceFlush = 0;
    private static boolean overflowLogged = false;
    /** The density gate turned this vault's logging off: every record is dropped and nothing is opened. */
    private static boolean suppressed = false;

    private RunLog() {}

    /** Folder holding every per-vault run log (also what the config screen's "Open Log Folder" opens). */
    public static Path runsDir() {
        return FMLPaths.CONFIGDIR.get().resolve("routerunner").resolve("runs");
    }

    /**
     * Bind the log to a vault id, appending to that vault's existing file if there is one.
     *
     * @return true if an existing file for this vault was resumed (a reconnect), false if a new one was created
     */
    public static synchronized boolean open(String vaultId) {
        if (suppressed) {
            pending.clear();
            return false;
        }
        try {
            Path dir = runsDir();
            Files.createDirectories(dir);
            String sane = sanitize(vaultId);
            Path existing = findExisting(dir, sane);
            boolean resumed = existing != null;
            Path file = resumed ? existing
                    : dir.resolve("vault_" + FILE_FMT.format(new Date()) + "_" + sane + ".jsonl");
            closeWriter();
            writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            currentFile = file;
            drainPending();
            if (!resumed) pruneToCap(file);
            return resumed;
        } catch (IOException | RuntimeException e) {
            LOG.error("[Routerunner] failed to open the run log for vault {}; this vault will not be logged.", vaultId, e);
            writer = null;
            currentFile = null;
            return false;
        }
    }

    /** Flush and close the current file (kept events stay buffered for whatever opens next), then enforce the size cap. */
    public static synchronized void close() {
        Path just = currentFile;
        closeWriter();
        pruneToCap(just);
    }

    /** Full teardown on vault entry: close any open file, drop anything still buffered, and log again. */
    public static synchronized void reset() {
        closeWriter();
        pending.clear();
        posSinceFlush = 0;
        overflowLogged = false;
        suppressed = false;
    }

    /**
     * The density gate rejected this vault: close and delete its file, drop anything buffered, and write nothing
     * more until the next vault.
     */
    public static synchronized void discardVault(String why) {
        Path file = currentFile;
        closeWriter();
        pending.clear();
        suppressed = true;
        if (file == null) {
            LOG.info("[Routerunner] run log off for this vault ({}); nothing had been written.", why);
            return;
        }
        try {
            Files.deleteIfExists(file);
            LOG.info("[Routerunner] run log off for this vault ({}); deleted {}.", why, file.getFileName());
        } catch (IOException | RuntimeException e) {
            LOG.error("[Routerunner] could not delete the run log {} after the density gate rejected the vault; it stays on disk.", file, e);
        }
    }

    /**
     * Delete the oldest run logs until the folder is under the configured cap (config {@code runLogCap} on,
     * {@code runLogCapMB}). {@code keep} (the file just opened or just closed) is never deleted. Every deletion is logged.
     */
    private static void pruneToCap(Path keep) {
        RouterunnerConfig cfg = RouterunnerConfig.get();
        if (!cfg.runLogCap) return;
        long cap = (long) cfg.runLogCapMB * 1024L * 1024L;
        Path dir = runsDir();
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> logs = files.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith("vault_") && n.endsWith(".jsonl");
            }).sorted(Comparator.comparing(p -> p.getFileName().toString())).collect(java.util.stream.Collectors.toList());
            long total = 0;
            long[] size = new long[logs.size()];
            for (int i = 0; i < logs.size(); i++) {
                size[i] = Files.size(logs.get(i));
                total += size[i];
            }
            if (total <= cap) return;
            long before = total;
            int deleted = 0;
            for (int i = 0; i < logs.size() && total > cap; i++) {
                Path p = logs.get(i);
                if (p.equals(keep) || p.equals(currentFile)) continue;
                Files.deleteIfExists(p);
                total -= size[i];
                deleted++;
                LOG.info("[Routerunner] run-log cap: deleted the oldest log {} ({} MB).", p.getFileName(),
                        String.format(Locale.ROOT, "%.1f", size[i] / 1048576.0));
            }
            LOG.warn("[Routerunner] run logs were {} MB, over the {} MB cap; deleted {} oldest file(s), now {} MB. Set runLogCap to false in config.json to keep every log.",
                    String.format(Locale.ROOT, "%.0f", before / 1048576.0), cfg.runLogCapMB, deleted,
                    String.format(Locale.ROOT, "%.0f", total / 1048576.0));
        } catch (IOException | RuntimeException e) {
            LOG.error("[Routerunner] run-log cap check failed in {}; no logs were deleted.", dir, e);
        }
    }

    /** Version of the reference solver's built-in weights, stamped on vault_enter for the analysis tools. */
    static final int REFERENCE_WEIGHTS_VERSION = 14;

    /** First tick with a resolved vault id: stamps the mod version, reference-weights version and the solver's parameters. */
    public static synchronized void vaultEnter(String vaultId, int lap) {
        RouterunnerConfig cfg = RouterunnerConfig.get();
        StringBuilder sb = head("vault_enter", 512);
        sb.append(",\"vaultId\":").append(quote(vaultId))
          .append(",\"profile\":").append(quote("default"))
          .append(",\"modVersion\":").append(quote(Routerunner.MOD_VERSION))
          .append(",\"logVersion\":").append(LOG_VERSION)
          .append(",\"weightsVersion\":").append(REFERENCE_WEIGHTS_VERSION)
          .append(",\"dashSpec\":").append(quote(DashInfo.specId()))
          .append(",\"mineSpec\":").append(quote(ChainMinerInfo.current().spec()))
          .append(",\"weights\":").append(weightsJson())
          .append(",\"adaptive\":").append(cfg.adaptiveLearning)
          .append(",\"lap\":").append(lap)
          .append("}\n");
        write(sb.toString(), true);
    }

    /** The crystal's modifier list, once it resolves and again whenever it changes. */
    public static synchronized void vaultInfo(List<String> modifiers) {
        StringBuilder sb = head("vault_info", 256);
        sb.append(",\"modifiers\":").append(strArray(modifiers)).append("}\n");
        write(sb.toString(), true);
    }

    /** The New Lap button: HUD counters restart, the vault clocks and this file do not. */
    public static synchronized void lap(int lap, int chestsSoFar, long activeMs) {
        StringBuilder sb = head("lap", 128);
        sb.append(",\"lap\":").append(lap)
          .append(",\"chestsSoFar\":").append(chestsSoFar)
          .append(",\"activeMs\":").append(activeMs)
          .append("}\n");
        write(sb.toString(), true);
    }

    /** reason ∈ disabled, suspend. */
    public static synchronized void pause(String reason) {
        write(head("pause", 96).append(",\"reason\":").append(quote(reason)).append("}\n").toString(), true);
    }

    /** reason ∈ enabled, reconnect. */
    public static synchronized void resume(String reason) {
        write(head("resume", 96).append(",\"reason\":").append(quote(reason)).append("}\n").toString(), true);
    }

    /**
     * MOVEMENT_SPEED sample. Logged on enter/resume/lap, when the persistent value moves more than 1 %, and on
     * the tick the set of transient modifiers changes.
     *
     * @param attr       the player's actual current speed, all modifiers included ({@code Params.speedAttr})
     * @param persistent the same attribute with transient modifiers removed (gear + prestige only)
     * @param sprint     whether the player was sprinting when this was sampled
     * @param fastRun    ParCool FastRun's ADDITION amount at the sample, 0 when not applied
     * @param entity     {@code LivingEntity.getSpeed()} — outside the attribute system; the Zephyr charm
     *                   overwrites it with 0.2 and is invisible to {@code attr}
     * @param flying     {@code LivingEntity.flyingSpeed} — likewise written by the Zephyr charm
     * @param mods       every transient modifier, pre-rendered as JSON objects (see
     *                   {@link PlayerSpeed#transientModifiers}); the full breakdown of {@code attr}
     */
    public static synchronized void speed(double attr, double persistent, boolean sprint, double fastRun,
                                          double entity, double flying, java.util.List<String> mods) {
        StringBuilder sb = head("speed", 256)
                .append(",\"attr\":").append(r4(attr))
                .append(",\"persistent\":").append(r4(persistent))
                .append(",\"sprint\":").append(sprint)
                .append(",\"fastRun\":").append(r4(fastRun))
                .append(",\"entitySpeed\":").append(r4(entity))
                .append(",\"flyingSpeed\":").append(r4(flying))
                .append(",\"mods\":[");
        for (int i = 0; i < mods.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(mods.get(i));
        }
        write(sb.append("]}\n").toString(), true);
    }

    /**
     * The 20 Hz movement stream — one record per client tick. When a lane route is live and routing is shown, the
     * index of the run being followed ({@code run}) rides along so the planned and actual paths can be aligned.
     */
    public static synchronized void pos(Player player) {
        if (player == null) return;
        long now = System.currentTimeMillis();
        try {
            Vec3 vel = player.getDeltaMovement();
            StringBuilder sb = new StringBuilder(220);
            sb.append("{\"ev\":\"pos\",\"ts\":").append(now).append(",\"t\":").append(activeMs())
              .append(",\"pos\":[").append(r2(player.getX())).append(',').append(r2(player.getY())).append(',').append(r2(player.getZ())).append(']')
              .append(",\"yaw\":").append(r1(player.getYRot()))
              .append(",\"pitch\":").append(r1(player.getXRot()))
              .append(",\"onGround\":").append(player.isOnGround())
              .append(",\"sprint\":").append(player.isSprinting())
              .append(",\"spd\":").append(r2(PlayerSpeed.realizedHorizontal(player)))
              .append(",\"vy\":").append(r2(vel.y * 20.0))
              .append(",\"clr\":").append(clearanceAtPlayer(player))
              .append(",\"atk\":").append(attackDown());
            if (TeleportDetector.teleportedThisTick()) sb.append(",\"tp\":true");
            RouteService.SolvedRoute sr = RouteService.current();
            if (sr != null && RouterunnerConfig.get().routingEnabled) {
                com.routerunner.lane.LaneRoute lr = sr.lane;
                if (lr != null) sb.append(",\"run\":").append(lr.cur);
                if (sr.roomId != null) sb.append(",\"roomId\":").append(quote(sr.roomId));
            }
            sb.append("}\n");
            boolean flush = ++posSinceFlush >= POS_FLUSH_EVERY;
            if (flush) posSinceFlush = 0;
            write(sb.toString(), flush);
        } catch (RuntimeException e) {
            LOG.error("[Routerunner] failed to log a position sample; the pos stream has a gap.", e);
        }
    }

    /**
     * One client tick's worth of per-FRAME look samples, batched into a single line so the frame-rate aim
     * detail costs one record per tick. Sample = {@code [dtMs, yaw, pitch]}, dtMs relative to {@code ts}.
     * Written unflushed: the {@code pos} stream's flush (every {@link #POS_FLUSH_EVERY} records) carries it out.
     *
     * @param baseTs  wall-clock ms of the batch's first sample
     * @param samples {offsetMs, yaw, pitch} per buffered frame, in order
     */
    public static synchronized void look(long baseTs, List<double[]> samples) {
        if (samples == null || samples.isEmpty()) return;
        try {
            StringBuilder sb = new StringBuilder(samples.size() * 24 + 64);
            sb.append("{\"ev\":\"look\",\"ts\":").append(baseTs).append(",\"t\":").append(activeMs()).append(",\"s\":[");
            for (int i = 0; i < samples.size(); i++) {
                double[] s = samples.get(i);
                if (i > 0) sb.append(',');
                sb.append('[').append((long) s[0]).append(',').append(r1(s[1])).append(',').append(r1(s[2])).append(']');
            }
            sb.append("]}\n");
            write(sb.toString(), false);
        } catch (RuntimeException e) {
            LOG.error("[Routerunner] failed to log a look batch; the look stream has a gap.", e);
        }
    }

    /** Whether the attack key (mouse button by default) is held this tick, so hold-to-mine and click-to-mine are separable. */
    private static boolean attackDown() {
        try {
            return net.minecraft.client.Minecraft.getInstance().options.keyAttack.isDown();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * The player's position jumped between two ticks by far more than their velocity explains (a Dash Warp
     * landing, a portal, a server correction — see {@link TeleportDetector}). {@code jump} is the one-tick
     * displacement in blocks, {@code vel} the tick's own speed in blk/s; the {@code pos} record of the same
     * tick carries {@code tp:true}.
     */
    public static synchronized void teleport(double fx, double fy, double fz, double tx, double ty, double tz,
                                             double jump, double velPerTick) {
        StringBuilder sb = head("teleport", 200);
        sb.append(",\"from\":[").append(r2(fx)).append(',').append(r2(fy)).append(',').append(r2(fz)).append(']')
          .append(",\"to\":[").append(r2(tx)).append(',').append(r2(ty)).append(',').append(r2(tz)).append(']')
          .append(",\"jump\":").append(r2(jump))
          .append(",\"vel\":").append(r2(velPerTick * 20.0))
          .append("}\n");
        write(sb.toString(), true);
    }

    /** The lane planner's time model was switched ({@code learned} / {@code simple}), by key or menu. */
    public static synchronized void timeModel(String model, String source) {
        StringBuilder sb = head("time_model", 120);
        sb.append(",\"model\":").append(quote(model))
          .append(",\"source\":").append(quote(source))
          .append("}\n");
        write(sb.toString(), true);
    }

    /**
     * A warp (teleport) mapped onto the live route: the run and progress the player was on, the nearest route point
     * to the take-off and to the landing (index into the concatenated run polylines as logged in {@code lane_plan},
     * the run it belongs to, and the distance off the route), and the route length between them.
     */
    public static synchronized void warp(long cellKeyRaw, int run, int prog, int fromRun, int fromIdx, double fromOff,
                                         int toRun, int toIdx, double toOff, double routeSkipped, String timeModel) {
        StringBuilder sb = head("warp", 260);
        sb.append(",\"cellKey\":").append(quote(cellKey(cellKeyRaw)))
          .append(",\"run\":").append(run)
          .append(",\"prog\":").append(prog)
          .append(",\"fromRun\":").append(fromRun)
          .append(",\"fromIdx\":").append(fromIdx)
          .append(",\"fromOff\":").append(r2(fromOff))
          .append(",\"toRun\":").append(toRun)
          .append(",\"toIdx\":").append(toIdx)
          .append(",\"toOff\":").append(r2(toOff))
          .append(",\"routeSkipped\":").append(r2(routeSkipped))
          .append(",\"timeModel\":").append(quote(timeModel))
          .append("}\n");
        write(sb.toString(), true);
    }

    /**
     * The lane planner finished a room (or replanned it): mode, size, the model's predicted time and every run as a
     * WORLD polyline with its yield, so the replay and the analysis can reconstruct exactly what was drawn. Also the
     * time model that priced it and every planned trigger in firing order ({@code trig}: lane, kind 0 = transition /
     * 1 = lane / 2 = exit, the ROOM-LOCAL firing cell, the target chest, and the flat local coordinates of every chest it
     * is planned to clear), so each planned click can be timed against the breaks offline.
     */
    public static synchronized void lanePlan(long cellKeyRaw, String roomId, String mode, String reason, int nLanes,
                                             com.routerunner.lane.LaneRoute lr) {
        try {
            StringBuilder sb = head("lane_plan", 2048);
            sb.append(",\"cellKey\":").append(quote(cellKey(cellKeyRaw)))
              .append(",\"roomId\":").append(quote(roomId))
              .append(",\"mode\":").append(quote(mode))
              .append(",\"reason\":").append(quote(reason))
              .append(",\"lanes\":").append(nLanes)
              .append(",\"runs\":").append(lr.runs.size())
              .append(",\"cover\":").append(r4(lr.plan.cover))
              .append(",\"bail\":").append(r2(lr.plan.bail))
              .append(",\"opportunity\":").append(r2(lr.plan.opportunity))
              .append(",\"bailFloor\":").append(r2(lr.planner.P.bailFloor))
              .append(",\"modelS\":").append(r2(lr.plan.tTotal))
              .append(",\"yield\":").append(lr.plan.yieldTotal)
              .append(",\"pace\":").append(r4(lr.planner.P.pace))
              .append(",\"triggerS\":").append(r4(lr.planner.P.triggerS))
              .append(",\"timeModel\":").append(quote(lr.planner.P.timeModel))
              .append(",\"turnaround\":[").append(r2(lr.planner.P.turnaroundDeg)).append(',').append(r4(lr.planner.P.turnaroundPenaltyS)).append(']')
              .append(",\"origin\":[").append(lr.ox).append(',').append(lr.oy).append(',').append(lr.oz).append(']')
              .append(",\"miner\":{\"range\":").append(lr.planner.chainModel().range)
                  .append(",\"limit\":").append(lr.planner.chainModel().limit)
                  .append(",\"compMax\":").append(lr.planner.chainModel().compMax)
                  .append(",\"reach\":").append(r2(lr.planner.P.breakReach)).append('}')
              .append(",\"prune\":{\"threshold\":").append(r2(lr.pruneThreshold))
                  .append(",\"lambda\":").append(r2(lr.pruneLambda))
                  .append(",\"tHit\":").append(r4(lr.pruneTHit))
                  .append(",\"groups\":").append(lr.prunedGroups)
                  .append(",\"chests\":").append(lr.prunedChests).append('}');
            if (lr.plan.exitHop) sb.append(",\"exitHop\":true");
            if (lr.plan.exitStraight) sb.append(",\"exitStraight\":true");
            sb.append(",\"runList\":[");
            for (int i = 0; i < lr.runs.size(); i++) {
                com.routerunner.lane.LaneRoute.Run r = lr.runs.get(i);
                if (i > 0) sb.append(',');
                sb.append("{\"yield\":").append(r.yield).append(",\"s\":").append(r2(r.seconds)).append(",\"brush\":").append(r.brush.size())
                  .append(",\"exit\":").append(r.exit).append(",\"laneStart\":").append(r.laneStart).append(",\"shafts\":").append(r.shafts.size())
                  .append(",\"nTrig\":").append(r2(r.nTrig)).append(",\"tTravel\":").append(r2(r.travelS)).append(",\"tPen\":").append(r2(r.penaltyS))
                  .append(",\"uturn\":").append(r.uturn).append(",\"priority\":").append(r.priority.size())
                  .append(",\"poly\":[");
                for (int k = 0; k < r.poly.size(); k++) {
                    BlockPos p = r.poly.get(k);
                    if (k > 0) sb.append(',');
                    sb.append('[').append(p.getX()).append(',').append(p.getY()).append(',').append(p.getZ()).append(']');
                }
                sb.append("]}");
            }
            sb.append("],\"trig\":[");
            boolean first = true;
            for (int li = 0; li < lr.plan.lanes.size(); li++) {
                com.routerunner.lane.LanePlanner.Lane e = lr.plan.lanes.get(li);
                if (e.transTriggers != null) {
                    for (com.routerunner.lane.LanePlanner.Trigger t : e.transTriggers) {
                        if (!first) sb.append(',');
                        first = false;
                        trigger(sb, lr.planner, li, 0, t);
                    }
                }
                if (e.triggers != null) {
                    for (com.routerunner.lane.LanePlanner.Trigger t : e.triggers) {
                        if (!first) sb.append(',');
                        first = false;
                        trigger(sb, lr.planner, li, 1, t);
                    }
                }
            }
            for (com.routerunner.lane.LanePlanner.Trigger t : lr.plan.exitTriggers) {
                if (!first) sb.append(',');
                first = false;
                trigger(sb, lr.planner, -1, 2, t);
            }
            sb.append("]}\n");
            write(sb.toString(), true);
        } catch (RuntimeException e) {
            LOG.error("[Routerunner] failed to log the lane plan for {}; this room has no lane_plan record.", roomId, e);
        }
    }

    private static void trigger(StringBuilder sb, com.routerunner.lane.LanePlanner planner, int lane, int kind,
                                com.routerunner.lane.LanePlanner.Trigger t) {
        com.routerunner.solver.P c = planner.chestAt(t.chest);
        sb.append('[').append(lane).append(',').append(kind).append(',')
          .append(t.cell.x()).append(',').append(t.cell.y()).append(',').append(t.cell.z()).append(',')
          .append(c.x()).append(',').append(c.y()).append(',').append(c.z()).append(",[");
        for (int k = 0; k < t.cleared.length; k++) {
            com.routerunner.solver.P q = planner.chestAt(t.cleared[k]);
            if (k > 0) sb.append(',');
            sb.append(q.x()).append(',').append(q.y()).append(',').append(q.z());
        }
        sb.append("]]");
    }

    /** A lane follow event: {@code done} / {@code off} / {@code advance} / {@code replan} / {@code finished}, with the pointer index. */
    public static synchronized void laneEvent(long cellKeyRaw, String ev, int run, int runs, String reason, int aliveAhead, int prog) {
        StringBuilder sb = head("lane", 200);
        sb.append(",\"cellKey\":").append(quote(cellKey(cellKeyRaw)))
          .append(",\"what\":").append(quote(ev))
          .append(",\"run\":").append(run)
          .append(",\"runs\":").append(runs)
          .append(",\"reason\":").append(quote(reason))
          .append(",\"aliveAhead\":").append(aliveAhead)
          .append(",\"prog\":").append(prog)
          .append("}\n");
        write(sb.toString(), true);
    }

    /** A tracked chest disappeared (world coords + the block id it was). */
    public static synchronized void breakEvent(BlockPos pos, String chestId) {
        StringBuilder sb = head("break", 160);
        sb.append(",\"pos\":[").append(pos.getX()).append(',').append(pos.getY()).append(',').append(pos.getZ()).append(']')
          .append(",\"chest\":").append(quote(chestId))
          .append("}\n");
        write(sb.toString(), true);
    }

    /**
     * A room was planned: its snapshot (chests, grid, exit — all ROOM-LOCAL) and the settings it ran with, so the room
     * can be re-planned offline against the {@code pos} stream. The lane plan itself is the {@code lane_plan} record.
     */
    public static synchronized void roomSolve(RouteService.SolvedRoute sr) {
        try {
            StringBuilder sb = head("room_solve", 4096);
            sb.append(",\"cellKey\":").append(quote(cellKey(sr.cellKey)))
              .append(",\"roomId\":").append(quote(sr.roomId))
              .append(",\"target\":").append(quote(sr.targetType))
              .append(",\"origin\":[").append(sr.ox).append(',').append(sr.oy).append(',').append(sr.oz).append(']')
              .append(",\"chests\":").append(sr.targetsWorld.size())
              .append(",\"solveMs\":").append(sr.solveMs)
              .append(",\"geomMs\":").append(sr.geomMs)
              .append(",\"queueMs\":").append(sr.queueMs)
              .append(",\"laneMs\":").append(sr.laneMs)
              .append(",\"prefetchLeadMs\":").append(sr.prefetchLeadMs)
              .append(",\"exit\":[").append(sr.exitLocal.x()).append(',').append(sr.exitLocal.y()).append(',').append(sr.exitLocal.z()).append(']')
              .append(",\"chestList\":").append(localChestList(sr))
              .append(",\"otherChests\":").append(otherChestList(sr))
              .append(",\"slots\":").append(sr.grid == null ? 0 : sr.grid.slotCount())
              .append(",\"grid\":").append(gridObj(sr.grid))
              .append(",\"weights\":").append(sr.params.toJson())
              .append("}\n");
            write(sb.toString(), true);
        } catch (RuntimeException e) {
            LOG.error("[Routerunner] failed to log the solved route for {}; this room has no room_solve record.", sr.roomId, e);
        }
    }

    /**
     * The room template behind a route cell, logged when it resolves LATE — the solver usually finishes before
     * the client knows which room it is in, which is why most {@code room_solve} records carry a null roomId.
     * Join on {@code cellKey} to label them offline.
     */
    public static synchronized void roomId(long cellKeyRaw, String roomId) {
        StringBuilder sb = head("room_id", 160);
        sb.append(",\"cellKey\":").append(quote(cellKey(cellKeyRaw)))
          .append(",\"roomId\":").append(quote(roomId))
          .append("}\n");
        write(sb.toString(), true);
    }

    /**
     * One room's you-vs-plan comparison, scored by the same walk-cost model. {@code cost} fields are model-blocks;
     * {@code sec}/{@code chestsPerMin} are wall-clock ground truth. The ts/t bounds slice the {@code pos} and
     * {@code break} streams back to exactly this room. The {@code solver} side is the room's lane plan (its drawn path
     * and planned chests); without one, {@code solver}, {@code delta} and {@code follow} are null.
     *
     * @param user         {dist,turn,vert,clr,total} for the player's path (model-blocks)
     * @param solver       {dist,turn,vert,clr,total} for the lane plan's drawn path, or null without a plan
     * @param solverChests the lane plan's planned chests (0 without a plan)
     * @param solverWalk   the lane plan's drawn path length (blocks)
     * @param follow       {avgOff, maxOff, pctOver2} deviation of the player's path from the drawn route, or null
     * @param accuracy     {n, rho, rhoNN, lift, spatial, score} order-prediction score, or null when unscorable
     */
    public static synchronized void roomDiff(RouteService.SolvedRoute room, int lap, boolean routing,
                                             double sec, double[] user, double[] solver, int solverChests, double solverWalk,
                                             double[] follow, double[] accuracy) {
        try {
            RoutePlanner.Params pm = room.params;
            int userChests = room.userChests;
            double userMin = sec / 60.0;
            double userCpm = userMin > 0 ? userChests / userMin : 0;
            double userCostPerChest = userChests > 0 ? user[4] / userChests : 0;
            double solverCostPerChest = solver != null && solverChests > 0 ? solver[4] / solverChests : 0;
            StringBuilder sb = head("room_diff", 900);
            sb.append(",\"cellKey\":").append(quote(cellKey(room.cellKey)))
              .append(",\"roomId\":").append(quote(room.roomId))
              .append(",\"target\":").append(quote(room.targetType))
              .append(",\"lap\":").append(lap)
              .append(",\"chestsInRoom\":").append(room.targetsWorld.size())
              .append(",\"routing\":").append(routing)
              .append(",\"firstTs\":").append(room.firstTs)
              .append(",\"lastTs\":").append(room.lastTs)
              .append(",\"firstT\":").append(room.userFirstMs)
              .append(",\"lastT\":").append(room.userLastMs)
              .append(",\"breaks\":").append(room.userBreaks.size())
              .append(",\"trail\":").append(trailArray(room))
              .append(",\"breakList\":").append(breakArray(room))
              .append(",\"weights\":").append(pm.toJson())
              .append(",\"chain\":{\"mode\":").append(quote(pm.miner)).append(",\"spec\":").append(quote(pm.minerSpec))
                  .append(",\"tier\":").append(pm.minerTier).append(",\"range\":").append(pm.chainRange).append(",\"limit\":").append(pm.chainLimit).append('}')
              .append(",\"user\":{\"chests\":").append(userChests)
                  .append(",\"sec\":").append(r1(sec))
                  .append(",\"chestsPerMin\":").append(r1(userCpm))
                  .append(",\"costPerChest\":").append(r2(userCostPerChest))
                  .append(",\"model\":").append(modelObj(user)).append('}')
              .append(",\"solver\":");
            if (solver == null) {
                sb.append("null,\"delta\":null");
            } else {
                sb.append("{\"chests\":").append(solverChests)
                  .append(",\"costPerChest\":").append(r2(solverCostPerChest))
                  .append(",\"walkBlocks\":").append(r1(solverWalk))
                  .append(",\"model\":").append(modelObj(solver)).append('}')
                  .append(",\"delta\":{\"total\":").append(r2(user[4] - solver[4]))
                  .append(",\"costPerChest\":").append(r2(userCostPerChest - solverCostPerChest)).append('}');
            }
            sb.append(",\"follow\":");
            if (follow == null) {
                sb.append("null");
            } else {
                sb.append("{\"avgOff\":").append(r2(follow[0]))
                  .append(",\"maxOff\":").append(r2(follow[1]))
                  .append(",\"pctOver2\":").append(r1(follow[2])).append('}');
            }
            sb              .append(",\"accuracy\":").append(accuracyObj(accuracy));
            if (room.bigGroups != null) {
                sb.append(",\"bigGroups\":{\"groups\":").append(room.bigGroups[0])
                  .append(",\"hit\":").append(room.bigGroups[1])
                  .append(",\"missed\":").append(room.bigGroups[2])
                  .append(",\"missedChests\":").append(room.bigGroups[3])
                  .append(",\"minSize\":").append(room.bigGroups[4]).append('}');
            }
            sb.append("}\n");
            write(sb.toString(), true);
        } catch (RuntimeException e) {
            LOG.error("[Routerunner] failed to log the room diff for {}; this room has no room_diff record.", room.roomId, e);
        }
    }

    /**
     * The route service's human-readable status changed (solving, following, skipped, …). The caller
     * throttles this to one record a second; the routing states carry the lane index, so they move every run.
     */
    public static synchronized void state(String state) {
        write(head("state", 128).append(",\"state\":").append(quote(state)).append("}\n").toString(), true);
    }

    /**
     * A fresh solver weight snapshot, written a few seconds into the vault once the ability tree and
     * MOVEMENT_SPEED have synced; {@code vault_enter}'s snapshot may still carry defaults for those.
     *
     * @param weightsJson a {@code RoutePlanner.Params} dump (the same object {@code vault_enter} carries)
     */
    public static synchronized void weights(String weightsJson) {
        write(head("weights", 1024)
                .append(",\"dashSpec\":").append(quote(DashInfo.specId()))
                .append(",\"mineSpec\":").append(quote(ChainMinerInfo.current().spec()))
                .append(",\"weights\":").append(weightsJson == null || weightsJson.isEmpty() ? "{}" : weightsJson)
                .append("}\n").toString(), true);
    }

    /**
     * The room picker's exit for a room: {@code reason} solve/prefetch (the snapshot's exit) or switch (moved mid-room).
     * Walls are W/E/N/S; {@code values} are each wall's path value in chests (null = the entrance or no door), absent
     * when the exit is simply the door opposite the entrance. {@code neigh} are the four neighbouring rooms' target
     * chest counts (W, E, N, S; -1 = not loaded when asked), {@code unknown} how many candidate exits led to an
     * unloaded room, {@code lambda} the planned chest rate the values are priced at.
     */
    public static synchronized void roomPick(long cellKeyRaw, String reason, int entryWall, int exitWall, RoomPicker.Decision d,
                                             int[] neigh, double lambda) {
        StringBuilder sb = head("room_pick", 256);
        sb.append(",\"cellKey\":").append(quote(cellKey(cellKeyRaw)))
          .append(",\"reason\":").append(quote(reason))
          .append(",\"entry\":").append(quote(entryWall < 0 ? "?" : RoomPicker.WALL_NAMES[entryWall]))
          .append(",\"exit\":").append(quote(exitWall < 0 ? "?" : RoomPicker.WALL_NAMES[exitWall]))
          .append(",\"picked\":").append(d != null);
        if (d != null) {
            sb.append(",\"values\":[");
            for (int w = 0; w < 4; w++) {
                if (w > 0) sb.append(',');
                double v = d.values()[w];
                sb.append(Double.isNaN(v) ? "null" : r1(v));
            }
            sb.append("],\"unknown\":").append(d.unknownNeighbours()).append(",\"planned\":").append(d.plannedNeighbours())
              .append(",\"seen\":").append(d.seenRooms());
            if (d.fallback()) sb.append(",\"fallback\":true");
        }
        sb.append(",\"neigh\":[").append(neigh[0]).append(',').append(neigh[1]).append(',').append(neigh[2]).append(',').append(neigh[3]).append(']')
          .append(",\"lambda\":").append(Double.isNaN(lambda) ? "null" : r2(lambda))
          .append("}\n");
        write(sb.toString(), true);
    }

    /** The density gate passed this vault: the qualifying room's chest count (any type) and the room count it used (1). */
    /**
     * One room measured for the player calibration (research/2026-09-28_player_calib): idle-free real seconds, idle,
     * the shape price of the player's own trail and hits, the benchmark seconds for it, clumpiness, chests broken, the
     * benchmark plan (chests, seconds) the lap predictions use, whether the drawn route was followed, the switch into
     * the room, and the player's pace after it (not yet counted while the density gate is undecided).
     */
    public static synchronized void playerCalib(long cellKeyRaw, com.routerunner.calib.PlayerCalibration.Room m, double benchS, double pace) {
        write(head("player_calib", 320).append(",\"cellKey\":").append(quote(cellKey(cellKeyRaw)))
                .append(",\"vein\":").append(m.vein())
                .append(",\"realS\":").append(r2(m.realS()))
                .append(",\"idleS\":").append(r2(m.idleS()))
                .append(",\"pricedS\":").append(r2(m.pricedS()))
                .append(",\"benchS\":").append(r2(benchS))
                .append(",\"clump\":").append(r1(m.clump()))
                .append(",\"chests\":").append(m.chests())
                .append(",\"planYield\":").append(r1(m.planYield()))
                .append(",\"planS\":").append(r2(m.planS()))
                .append(",\"followed\":").append(m.followed())
                .append(",\"switchS\":").append(r2(m.switchS()))
                .append(",\"pace\":").append(r4(pace)).append("}\n").toString(), false);
    }

    public static synchronized void densityGate(double density, int rooms, double threshold) {
        write(head("density_gate", 128).append(",\"density\":").append(r1(density)).append(",\"rooms\":").append(rooms)
                .append(",\"threshold\":").append(r1(threshold)).append(",\"pass\":true}\n").toString(), true);
    }

    /**
     * The mining ability the planner routes with: on the first solve of a vault and whenever it changes. Range 1 is
     * Vein Miner (touching chests only); {@code mode} {@code default} means the ability tree was unreadable and the
     * planner fell back to a 6/32 chain. Also carries the block reach ({@code reach} = min of Forge's survival reach
     * and the vault-capped attribute), the reach the player's hits actually use ({@code usedReach}, the learned quantile
     * over {@code reachHits} hits, or the prior) and the break reach the lane planner uses ({@code planReach}).
     */
    public static synchronized void miner(ChainMinerInfo.Miner m, String reason, double[] reach, double[] used, double planReach) {
        write(head("miner", 240).append(",\"reason\":").append(quote(reason))
                .append(",\"mode\":").append(quote(m.mode()))
                .append(",\"spec\":").append(quote(m.spec()))
                .append(",\"tier\":").append(m.tier())
                .append(",\"range\":").append(m.range())
                .append(",\"limit\":").append(m.limit())
                .append(",\"reach\":").append(r2(reach[0]))
                .append(",\"reachForge\":").append(r2(reach[1]))
                .append(",\"reachAttr\":").append(r2(reach[2]))
                .append(",\"usedReach\":").append(r2(used[0]))
                .append(",\"reachHits\":").append((int) used[1])
                .append(",\"planReach\":").append(r2(planReach))
                .append("}\n").toString(), true);
    }

    /** Tier 0 of the adaptive model moved (or a vault started): pace a, seconds per burst b, runs learned. */
    public static synchronized void calib(String reason, String miner, double a, double b, long n, long rejected) {
        write(head("calib", 180).append(",\"reason\":").append(quote(reason)).append(",\"miner\":").append(quote(miner))
                .append(",\"a\":").append(r4(a))
                .append(",\"b\":").append(r4(b)).append(",\"n\":").append(n).append(",\"rejected\":").append(rejected)
                .append("}\n").toString(), true);
    }

    /** Tier 1 of the adaptive model learned a room's legs: counts, both models' recent R2, the applied coefficients. */
    public static synchronized void adaptModel(long cellKeyRaw, int rows, int pathFails, long n, double r2Bundled, double r2Adapted,
                                               double interceptShift, boolean fellBack, double[] applied) {
        StringBuilder sb = head("adapt_model", 400);
        sb.append(",\"cellKey\":").append(quote(cellKey(cellKeyRaw)))
          .append(",\"rows\":").append(rows).append(",\"pathFails\":").append(pathFails).append(",\"n\":").append(n)
          .append(",\"r2Bundled\":").append(orNull(r2Bundled)).append(",\"r2Adapted\":").append(orNull(r2Adapted))
          .append(",\"interceptShift\":").append(r4(interceptShift)).append(",\"fellBack\":").append(fellBack)
          .append(",\"theta\":[");
        for (int i = 0; i < applied.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(r4(applied[i]));
        }
        write(sb.append("]}\n").toString(), true);
    }

    /** A measurement that isn't confident yet logs as JSON null, never as a number the tooling would trust. */
    private static String orNull(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? "null" : r4(v);
    }

    /** Leaving the vault dimension while connected: the whole-vault totals (laps included). */
    public static synchronized void vaultExit(int chests, int laps, long netMs, long activeMs,
                                              double netAvg, double activeAvg, String type,
                                              List<String> modifiers, Map<String, Long> loot) {
        StringBuilder sb = head("vault_exit", 512);
        sb.append(",\"chests\":").append(chests)
          .append(",\"laps\":").append(laps)
          .append(",\"netMs\":").append(netMs)
          .append(",\"activeMs\":").append(activeMs)
          .append(",\"netAvg\":").append(r2(netAvg))
          .append(",\"activeAvg\":").append(r2(activeAvg))
          .append(",\"type\":").append(quote(type))
          .append(",\"modifiers\":").append(strArray(modifiers))
          .append(",\"loot\":").append(longMap(loot))
          .append("}\n");
        write(sb.toString(), true);
    }

    private static StringBuilder head(String ev, int cap) {
        return new StringBuilder(cap)
                .append("{\"ev\":\"").append(ev).append("\",\"ts\":").append(System.currentTimeMillis())
                .append(",\"t\":").append(activeMs());
    }

    private static long activeMs() {
        try {
            return MetricsTracker.get().getActiveMs();
        } catch (RuntimeException e) {
            LOG.error("[Routerunner] active clock unreadable; stamping this record with t=0.", e);
            return 0L;
        }
    }

    /** The solver weight snapshot a solve would run with right now (for vault_enter). */
    private static String weightsJson() {
        try {
            return RouteService.snapshotParams().toJson();
        } catch (Throwable t) {
            LOG.error("[Routerunner] could not snapshot the solver weights; logging an empty object.", t);
            return "{}";
        }
    }

    /** Route cells are logged as "rx,rz" — stable per room and readable, unlike the packed long. */
    private static String cellKey(long key) {
        return ((int) (key >> 32)) + "," + ((int) key);
    }

    private static void write(String line, boolean flush) {
        if (suppressed) return;
        if (writer == null) {
            if (pending.size() >= BUFFER_CAP) {
                pending.pollFirst();
                if (!overflowLogged) {
                    overflowLogged = true;
                    LOG.error("[Routerunner] run-log buffer hit {} events before the vault id resolved; dropping the oldest events.", BUFFER_CAP);
                }
            }
            pending.addLast(line);
            return;
        }
        try {
            writer.write(line);
            if (flush) writer.flush();
        } catch (IOException e) {
            LOG.error("[Routerunner] run-log write failed ({}); closing the file, this vault is now unlogged.", currentFile, e);
            closeWriter();
        }
    }

    private static void drainPending() throws IOException {
        while (!pending.isEmpty()) {
            writer.write(pending.pollFirst());
        }
        writer.flush();
        overflowLogged = false;
    }

    private static void closeWriter() {
        if (writer == null) return;
        try {
            writer.flush();
            writer.close();
        } catch (IOException e) {
            LOG.error("[Routerunner] failed to close the run log {}; trailing events may be lost.", currentFile, e);
        } finally {
            writer = null;
            currentFile = null;
            posSinceFlush = 0;
        }
    }

    /** The newest existing file for this vault id, or null — the "one file per vault" rule across reconnects. */
    private static Path findExisting(Path dir, String sanitizedVaultId) {
        String suffix = "_" + sanitizedVaultId + ".jsonl";
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith("vault_") && n.endsWith(suffix);
            }).max(Comparator.comparing(p -> p.getFileName().toString())).orElse(null);
        } catch (IOException | RuntimeException e) {
            LOG.error("[Routerunner] could not scan {} for this vault's run log; starting a new file.", dir, e);
            return null;
        }
    }

    /** Walls-only clearance at the block the player stands in, in the current solved room; -1 when unknown. */
    private static int clearanceAtPlayer(Player player) {
        RouteService.SolvedRoute room = RouteService.current();
        if (room == null || room.grid == null) return -1;
        int lx = (int) Math.floor(player.getX()) - room.ox;
        int ly = (int) Math.floor(player.getY()) - room.oy;
        int lz = (int) Math.floor(player.getZ()) - room.oz;
        return clearanceOrMinusOne(room.grid, lx, ly, lz);
    }

    private static int clearanceOrMinusOne(com.routerunner.solver.SolidGrid g, int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= g.sx || y >= g.sy || z >= g.sz) return -1;
        return g.clearanceFlyAt(x, y, z);
    }

    /** The room's non-target chests and strongboxes, LOCAL, each with its block id: [[x,y,z,"id"],...]. */
    private static String otherChestList(RouteService.SolvedRoute sr) {
        if (sr.otherChestsLocal == null || sr.otherChestIds == null || sr.otherChestsLocal.size() != sr.otherChestIds.size()) {
            LOG.error("[Routerunner] room_solve for {}: other-chest lists missing or mismatched; logging none.", sr.roomId);
            return "[]";
        }
        StringBuilder sb = new StringBuilder(sr.otherChestsLocal.size() * 40 + 2).append('[');
        for (int i = 0; i < sr.otherChestsLocal.size(); i++) {
            P p = sr.otherChestsLocal.get(i);
            if (i > 0) sb.append(',');
            sb.append('[').append(p.x()).append(',').append(p.y()).append(',').append(p.z())
              .append(',').append(quote(sr.otherChestIds.get(i))).append(']');
        }
        return sb.append(']').toString();
    }

    /** Every target chest present at solve time, room-local [x,y,z]. */
    private static String localChestList(RouteService.SolvedRoute sr) {
        StringBuilder sb = new StringBuilder(sr.targetsWorld.size() * 12 + 2).append('[');
        boolean first = true;
        for (BlockPos p : sr.targetsWorld) {
            if (!first) sb.append(',');
            first = false;
            sb.append('[').append(p.getX() - sr.ox).append(',').append(p.getY() - sr.oy).append(',').append(p.getZ() - sr.oz).append(']');
        }
        return sb.append(']').toString();
    }

    /**
     * The room's occupancy, walls and non-target chests only (target chests are in chestList), as a gzip+base64
     * bitset: bit index = (x*sy + y)*sz + z, {@link java.util.BitSet#toByteArray()} layout (bit i = byte i/8, LSB first).
     */
    private static String gridObj(com.routerunner.solver.SolidGrid g) {
        if (g == null) return "null";
        try {
            java.util.BitSet bits = new java.util.BitSet(g.sx * g.sy * g.sz);
            for (int x = 0; x < g.sx; x++) {
                for (int y = 0; y < g.sy; y++) {
                    for (int z = 0; z < g.sz; z++) {
                        if (g.isSolidFly(x, y, z)) bits.set((x * g.sy + y) * g.sz + z);
                    }
                }
            }
            byte[] raw = bits.toByteArray();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(raw.length / 4 + 64);
            try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(bos)) {
                gz.write(raw);
            }
            String b64 = java.util.Base64.getEncoder().encodeToString(bos.toByteArray());
            return "{\"sx\":" + g.sx + ",\"sy\":" + g.sy + ",\"sz\":" + g.sz
                    + ",\"enc\":\"gzip+base64 BitSet, idx=(x*sy+y)*sz+z\",\"solidZ\":\"" + b64 + "\"}";
        } catch (IOException | RuntimeException e) {
            LOG.error("[Routerunner] failed to encode the room grid for the run log; room_solve has no grid.", e);
            return "null";
        }
    }

    /** Your deduped 10 Hz trail through the room: [lx,ly,lz,t,yaw,pitch,clr] per sample (clr = walls-only, -1 outside the grid). */
    private static String trailArray(RouteService.SolvedRoute room) {
        StringBuilder sb = new StringBuilder(room.userTrail.size() * 48 + 2).append('[');
        for (int i = 0; i < room.userTrail.size(); i++) {
            double[] t = room.userTrail.get(i);
            if (i > 0) sb.append(',');
            int clr = room.grid == null ? -1
                    : clearanceOrMinusOne(room.grid, (int) Math.round(t[0]), (int) Math.round(t[1]), (int) Math.round(t[2]));
            sb.append('[').append(r2(t[0])).append(',').append(r2(t[1])).append(',').append(r2(t[2]))
              .append(',').append((long) t[3])
              .append(',').append(r1(t.length > 4 ? t[4] : 0.0)).append(',').append(r1(t.length > 5 ? t[5] : 0.0))
              .append(',').append(clr)
              .append(',').append(t.length > 6 && t[6] > 0 ? 1 : 0).append(']');
        }
        return sb.append(']').toString();
    }

    /** Target chests you broke in this room: [t, lx, ly, lz] per break (t = active clock ms). */
    private static String breakArray(RouteService.SolvedRoute room) {
        StringBuilder sb = new StringBuilder(room.userBreaks.size() * 20 + 2).append('[');
        for (int i = 0; i < room.userBreaks.size(); i++) {
            double[] b = room.userBreaks.get(i);
            if (i > 0) sb.append(',');
            sb.append('[').append((long) b[0]).append(',').append((int) b[1]).append(',').append((int) b[2]).append(',').append((int) b[3]).append(']');
        }
        return sb.append(']').toString();
    }

    /** The room's route-accuracy score, or a JSON null when too few planned clicks matched your breaks. */
    private static String accuracyObj(double[] a) {
        if (a == null) return "null";
        if (a.length < 6) {
            LOG.error("[Routerunner] accuracy array has {} entries, expected 6; logging null for this room.", a.length);
            return "null";
        }
        return "{\"n\":" + (int) a[0] + ",\"rho\":" + r4(a[1]) + ",\"rhoNN\":" + r4(a[2])
                + ",\"lift\":" + r4(a[3]) + ",\"spatial\":" + r4(a[4]) + ",\"score\":" + r4(a[5]) + "}";
    }

    private static String modelObj(double[] m) {
        return "{\"dist\":" + r2(m[0]) + ",\"turn\":" + r2(m[1]) + ",\"vert\":" + r2(m[2])
                + ",\"clr\":" + r2(m[3]) + ",\"total\":" + r2(m[4]) + "}";
    }

    private static String strArray(List<String> values) {
        if (values == null || values.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder(32 + values.size() * 24).append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(quote(values.get(i)));
        }
        return sb.append(']').toString();
    }

    private static String longMap(Map<String, Long> values) {
        if (values == null || values.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder(32 + values.size() * 32).append('{');
        boolean first = true;
        for (Map.Entry<String, Long> e : values.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(quote(e.getKey())).append(':').append(e.getValue() == null ? 0L : e.getValue());
        }
        return sb.append('}').toString();
    }

    private static String sanitize(String s) {
        if (s == null) return "unknown";
        String out = s.replaceAll("[^a-zA-Z0-9._-]", "_");
        return out.length() > 24 ? out.substring(0, 24) : out;
    }

    private static String quote(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String r1(double v) { return String.format(Locale.ROOT, "%.1f", v); }
    private static String r2(double v) { return String.format(Locale.ROOT, "%.2f", v); }
    private static String r4(double v) { return String.format(Locale.ROOT, "%.4f", v); }
}
