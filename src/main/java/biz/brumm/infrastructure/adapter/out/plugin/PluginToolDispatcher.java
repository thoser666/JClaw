package biz.brumm.infrastructure.adapter.out.plugin;

import tools.jackson.databind.JsonNode;

/**
 * Funktionale Dispatcher-Schnittstelle zwischen einem Plugin-Tool-Namen und dessen Ausfuehrung
 * durch die Sidecar-Laufzeit (Bridge-Protokoll, Abschnitt 9: "Tool-Schema -> Spring-AI").
 * <p>
 * Implementiert wird der Dispatcher in der Regel durch {@code NodeSidecarPluginRuntime::callTool},
 * das den Aufruf ueber {@code sidecar.tool.call} an den Node-Sidecar delegiert. Die Schnittstelle
 * ist absichtlich schmal (Name + Argumente -> Ergebnis), damit der aufrufende
 * {@link PluginToolCallback} hermetisch ohne Node-Prozess unit-getestet werden kann.
 */
@FunctionalInterface
public interface PluginToolDispatcher {

    /**
     * Fuehrt ein Plugin-Tool im Sidecar aus.
     *
     * @param toolName  Name des im Sidecar registrierten Tools
     * @param arguments JSON-Objekt mit den konkreten Argumenten des Aufrufs
     * @return Ergebnis des Tool-Aufrufs (frei strukturiertes JSON)
     * @throws Exception bei Sidecar-, Timeout- oder Serialisierungsfehlern
     */
    JsonNode dispatch(String toolName, JsonNode arguments) throws Exception;
}
