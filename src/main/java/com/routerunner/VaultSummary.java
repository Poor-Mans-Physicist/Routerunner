package com.routerunner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One completed-vault record for the history file/viewer. */
public class VaultSummary {
    public long timestamp;
    /** Tracked chest type: gilded, ornate, living, wooden or unknown. */
    public String type = "unknown";
    public int chests;
    /** Laps run in this vault; the other totals cover the whole vault. */
    public int laps = 1;
    /** Chests per wall-clock minute, pauses included. */
    public double netAvg;
    /** Chests per unpaused minute. */
    public double activeAvg;
    public double activeMinutes;
    public List<String> modifiers = new ArrayList<>();
    /** Tracked item id to total picked up. */
    public Map<String, Long> loot = new HashMap<>();
}
