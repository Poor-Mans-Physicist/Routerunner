package com.routerunner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One completed-vault record for the history file/viewer. */
public class VaultSummary {
    public long timestamp;
    public String type = "unknown";       // gilded/ornate/living/wooden, or unknown
    public int chests;
    public int laps = 1;                   // laps run in this vault (New Lap presses + 1); totals stay whole-vault
    public double netAvg;                  // chests/min incl. breaks
    public double activeAvg;               // chests/min unpaused
    public double activeMinutes;           // active time spent in the vault
    public List<String> modifiers = new ArrayList<>();
    public Map<String, Long> loot = new HashMap<>(); // tracked-item id -> total picked up
}
