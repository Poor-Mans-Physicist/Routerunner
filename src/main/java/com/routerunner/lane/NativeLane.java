package com.routerunner.lane;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Locale;

/**
 * The Rust lane planner behind JNI ({@code native/lane}). The library ships inside the jar as
 * {@code natives/windows-x64/routerunner_lane.dll}; {@link #init} copies it to a writable directory (named by a
 * content hash, so a newer jar never fights a loaded copy) and loads it. Any failure leaves {@link #ready()}
 * false with the reason in {@link #status()}, and the Java planner stays in charge.
 */
public final class NativeLane {
    private static volatile boolean ready = false;
    private static volatile boolean tried = false;
    private static volatile String status = "not initialised";

    private NativeLane() {}

    public static synchronized void init(Path dir) {
        if (tried) return;
        tried = true;
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (!os.contains("win") || !(arch.contains("amd64") || arch.contains("x86_64"))) {
            status = "no native build for " + os + "/" + arch;
            return;
        }
        String res = "/natives/windows-x64/routerunner_lane.dll";
        try (InputStream in = NativeLane.class.getResourceAsStream(res)) {
            if (in == null) {
                status = "library missing from the jar (" + res + ")";
                return;
            }
            Files.createDirectories(dir);
            byte[] bytes = in.readAllBytes();
            String hash = Integer.toHexString(Arrays.hashCode(bytes));
            Path target = dir.resolve("routerunner_lane-" + hash + ".dll");
            if (!Files.exists(target) || Files.size(target) != bytes.length) {
                Path tmp = dir.resolve("routerunner_lane-" + hash + ".tmp");
                Files.write(tmp, bytes);
                try {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.FileSystemException e) {
                    if (!Files.exists(target)) throw e;
                }
            }
            System.load(target.toAbsolutePath().toString());
            String v = version();
            ready = true;
            status = "loaded " + target.getFileName() + " (" + v + ")";
        } catch (Throwable t) {
            status = "load failed: " + t;
        }
    }

    public static boolean ready() {
        return ready;
    }

    public static String status() {
        return status;
    }

    static native long create(int sx, int sy, int sz, byte[] solidBits, int[] chests, int chainRange, int chainLimit, double[] model);

    static native String plan(long handle, int ex, int ey, int ez, int xx, int xy, int xz, byte[] mask, double[] params);

    static native String path(long handle, int ax, int ay, int az, int bx, int by, int bz);

    static native void destroy(long handle);

    static native String version();
}
