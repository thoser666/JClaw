package biz.brumm.infrastructure.sidecar;

import tools.jackson.databind.JsonNode;

/**
 * Ein Tool, das der Node-Sidecar über {@code sidecar.listTools} registriert hat.
 * {@code parameters} ist das JSON-Schema der Tool-Argumente (kann {@code null} sein).
 */
public record SidecarToolDescriptor(String name, String description, JsonNode parameters) {
}
