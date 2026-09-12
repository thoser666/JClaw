package biz.brumm.domain.model;

import java.time.Instant;

/**
 * Dauerhafter Polling-Checkpoint eines Channel-Ingress (P3-08).
 * <p>
 * Hält die Position des zuletzt übernommenen Items je Channel, damit
 * Polling-Adapter nach einem Neustart an derselben Stelle fortfahren können.
 */
public record IngressCursor(String channelId, String lastItemId, Instant updatedAt) {
}