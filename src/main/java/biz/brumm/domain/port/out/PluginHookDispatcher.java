package biz.brumm.domain.port.out;

import java.util.Map;

/**
 * Dispatcher für OpenClaw-Plugin-Hooks (P4-01 Folgearbeit „Voll-Hook-Katalog").
 * <p>
 * Die Plugin-Runtime ({@code NodeSidecarPluginRuntime}) delegiert jeden Aufruf als
 * {@code plugin.callHook} an den Sidecar; ohne aktivierte Laufzeit greift eine
 * No-op-Bean ({@code jclaw.agent.plugins.runtime.enabled=false} oder nicht gesetzt),
 * die immer {@link HookOutcome#proceed()} liefert.
 * <p>
 * Hooks sind additive Lifecycle-Beobachtung: Ein Sidecar-Fehler (Timeout, Prozess-
 * Ende) darf den Kern-Ablauf nicht kippen — die Implementierung loggt und fährt
 * fail-open fort. Blocking wird nur durch die Hook-Entscheidung ausgelöst
 * (werfender Handler oder {@code {block: true}}/{{@code blocked: true}}-Rückgabe).
 */
public interface PluginHookDispatcher {

    /**
     * Dispatcht einen Plugin-Hook.
     *
     * @param event der Hook-Stage (OpenClaw-Katalog 2026.8.x, z. B. {@code before_agent_run});
     *              JClaw emittiert einen Teil der Stages (Verdrahtung siehe docs/parity-roadmap.md)
     * @param name  optionaler Matcher-Name (z. B. Tool-Name bei tool-spezifischen Stages);
     *              {@code null}, wenn keiner
     * @param ctx   Hook-Kontext (freie Schlüssel/Werte, vom Sidecar an alle Handler gereicht)
     * @return {@link HookOutcome} — {@code blocked=false} (Procedure) oder {@code blocked=true} mit Nachricht
     */
    HookOutcome dispatch(String event, String name, Map<String, Object> ctx);

    /** Dispatcht einen Plugin-Hook ohne Matcher-Name. */
    default HookOutcome dispatch(String event, Map<String, Object> ctx) {
        return dispatch(event, null, ctx);
    }

    /** Ergebnis eines Hook-Dispatchs. */
    record HookOutcome(boolean blocked, String message) {

        public static HookOutcome proceed() {
            return new HookOutcome(false, null);
        }

        public static HookOutcome block(String message) {
            return new HookOutcome(true, message);
        }
    }

    /** No-op-Dispatcher: liefert immer {@code proceed()} (Standard ohne Plugin-Runtime). */
    static PluginHookDispatcher noop() {
        return (event, name, ctx) -> HookOutcome.proceed();
    }
}