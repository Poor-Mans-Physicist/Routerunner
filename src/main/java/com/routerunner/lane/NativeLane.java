package com.routerunner.lane;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Locale;

/**
 * The Rust lane planner behind JNI ({@code native/lane}). The library ships inside the jar per platform as
 * {@code natives/<os>-<arch>/<library>}: {@code windows-x64/routerunner_lane.dll}, {@code linux-x64} and
 * {@code linux-arm64/librouterunner_lane.so}, {@code macos-x64} and {@code macos-arm64/librouterunner_lane.dylib}
 * (the non-Windows builds come from the {@code natives} GitHub workflow). {@link #init} copies the one for this
 * machine to a writable directory (named by a content hash, so a newer jar never fights a loaded copy) and loads it.
 * Any failure, including a platform the jar has no build for, leaves {@link #ready()} false with the reason in
 * {@link #status()}, and the Java planner stays in charge.
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
        String plat = platform(os, arch);
        if (plat == null) {
            status = "no native build for " + os + "/" + arch;
            return;
        }
        String file = plat.startsWith("windows") ? "routerunner_lane.dll"
                : plat.startsWith("macos") ? "librouterunner_lane.dylib" : "librouterunner_lane.so";
        String ext = file.substring(file.lastIndexOf('.'));
        String res = "/natives/" + plat + "/" + file;
        try (InputStream in = NativeLane.class.getResourceAsStream(res)) {
            if (in == null) {
                status = "library missing from the jar (" + res + ")";
                return;
            }
            Files.createDirectories(dir);
            byte[] bytes = in.readAllBytes();
            String hash = Integer.toHexString(Arrays.hashCode(bytes));
            Path target = dir.resolve("routerunner_lane-" + hash + ext);
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

    /** The jar's platform folder for an os.name / os.arch pair, or null when no build exists for it. */
    static String platform(String os, String arch) {
        String a = arch.contains("amd64") || arch.contains("x86_64") ? "x64"
                : arch.contains("aarch64") || arch.contains("arm64") ? "arm64" : null;
        if (a == null) return null;
        if (os.contains("win")) return a.equals("x64") ? "windows-x64" : null;
        if (os.contains("mac") || os.contains("darwin")) return "macos-" + a;
        if (os.contains("linux")) return "linux-" + a;
        return null;
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
