package biz.brumm.domain.port.out;

import biz.brumm.domain.model.HookContext;
import biz.brumm.domain.model.HookResult;

import java.util.List;

/**
 * Callback-Schnittstelle für Lifecycle-Hocks.
 * Wird vom OllamaAiAdapter (Tool-Level) und HookableAiProviderPort (Agent-Level) aufgerufen.
 */
public interface HookCallback {

    /**
     * Führt alle Hooks für einen bestimmten Stage aus.
     *
     * @param stage   Der Lifecycle-Stage
     * @param context Der Hook-Kontext
     * @return Liste der Hook-Ergebnisse
     */
    List<HookResult> executeStage(String stage, HookContext context);

    /**
     * Wird vor einem Tool-Aufruf ausgeführt.
     *
     * @param toolName Name des Tools
     * @param toolArgs Argumente (JSON)
     * @return HookResult — blockiert der Hook, wird der Tool-Aufruf übersprungen
     */
    HookResult beforeToolCall(String toolName, String toolArgs);

    /**
     * Wird nach einem Tool-Aufruf ausgeführt.
     *
     * @param toolName Name des Tools
     * @param result   Ergebnis des Tool-Aufrufs
     */
    void afterToolCall(String toolName, String result);
}
