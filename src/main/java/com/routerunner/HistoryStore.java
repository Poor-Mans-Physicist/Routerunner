package com.routerunner;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only history of completed vaults at config/routerunner/history.json. Uncapped — the file
 * is only read/written once per vault completion and once when the viewer opens, and each record
 * is tiny, so unbounded growth is not a practical performance concern.
 */
public final class HistoryStore {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LIST_TYPE = new TypeToken<List<VaultSummary>>() {}.getType();

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("routerunner").resolve("history.json");
    }

    public static synchronized List<VaultSummary> load() {
        Path p = file();
        if (!Files.exists(p)) return new ArrayList<>();
        try (Reader r = Files.newBufferedReader(p)) {
            List<VaultSummary> list = GSON.fromJson(r, LIST_TYPE);
            return list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            LOG.error("[Routerunner] Failed to read vault history.", e);
            return new ArrayList<>();
        }
    }

    public static synchronized void append(VaultSummary summary) {
        try {
            List<VaultSummary> list = load();
            list.add(summary);
            Path p = file();
            Files.createDirectories(p.getParent());
            try (Writer w = Files.newBufferedWriter(p)) {
                GSON.toJson(list, w);
            }
        } catch (Exception e) {
            LOG.error("[Routerunner] Failed to append vault history.", e);
        }
    }

    private HistoryStore() {}
}
