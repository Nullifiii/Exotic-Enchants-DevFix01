package com.exoticenchants.core;

import java.util.List;
import java.util.UUID;

/**
 * A player taking part in a roll round. The Bukkit layer builds this from the online,
 * non-AFK player holding an eligible item, and supplies the candidate pool for that item type
 * (already filtered by the exotic-pool mode and capped at vanilla max levels).
 */
public record Participant(UUID player, String material, boolean hasExotic, List<Candidate> pool) {
}
