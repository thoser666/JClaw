package biz.brumm.domain.service;

import biz.brumm.domain.model.Hook;
import biz.brumm.domain.model.HookContext;
import biz.brumm.domain.model.HookResult;
import biz.brumm.domain.port.out.HookCallback;
import biz.brumm.domain.port.out.HookProvider;
import biz.brumm.infrastructure.adapter.out.hook.HookScriptExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Orchestriert die Ausführung von Lifecycle-Hooks.
 * Sammelt alle Hooks für einen Stage, sortiert nach Priorität (absteigend),
 * und führt sie sequenziell aus. Bei Blockierung durch einen Hook wird die
 * Kette abgebrochen.
 */
@Service
public class HookService implements HookCallback {

    private static final Logger log = LoggerFactory.getLogger(HookService.class);

    private final HookProvider hookProvider;
    private final HookScriptExecutor executor;

    public HookService(HookProvider hookProvider, HookScriptExecutor executor) {
        this.hookProvider = hookProvider;
        this.executor = executor;
    }

    /**
     * Führt alle Hooks für den angegebenen Stage aus.
     *
     * @param stage   Der Lifecycle-Stage
     * @param context Der Hook-Kontext
     * @return Liste aller Ergebnisse (in Ausführungsreihenfolge)
     */
    public List<HookResult> executeHooks(String stage, HookContext context) {
        List<Hook> hooks = hookProvider.findByStage(stage);
        if (hooks.isEmpty()) {
            return List.of();
        }

        log.info("Führe {} Hook(s) für Stage '{}' aus.", hooks.size(), stage);
        List<HookResult> results = new ArrayList<>();

        for (Hook hook : hooks) {
            HookResult result = executor.execute(hook, context);
            results.add(result);

            if (!result.allowed()) {
                log.info("Hook '{}' hat Stage '{}' blockiert: {}", hook.name(), stage, result.output());
                break;
            }
        }

        return results;
    }

    /**
     * Führt alle Hooks für einen Stage aus und gibt zurück, ob die Ausführung fortgesetzt werden soll.
     */
    public boolean executeAndProceed(String stage, HookContext context) {
        List<HookResult> results = executeHooks(stage, context);
        return results.stream().allMatch(HookResult::allowed);
    }

    /**
     * Liefert alle registrierten Hooks.
     */
    public List<Hook> listAll() {
        return hookProvider.findAll();
    }

    /**
     * Liefert alle Hooks für einen bestimmten Stage.
     */
    public List<Hook> listByStage(String stage) {
        return hookProvider.findByStage(stage);
    }

    @Override
    public List<HookResult> executeStage(String stage, HookContext context) {
        return executeHooks(stage, context);
    }

    @Override
    public HookResult beforeToolCall(String toolName, String toolArgs) {
        List<Hook> hooks = hookProvider.findByStage("before_tool_call");
        if (hooks.isEmpty()) {
            return HookResult.proceed("no-hooks");
        }

        HookContext context = HookContext.forToolCall("before_tool_call", toolName, toolArgs, null);
        for (Hook hook : hooks) {
            HookResult result = executor.execute(hook, context);
            if (!result.allowed()) {
                return result;
            }
        }
        return HookResult.proceed("all-hooks");
    }

    @Override
    public void afterToolCall(String toolName, String result) {
        List<Hook> hooks = hookProvider.findByStage("after_tool_call");
        if (hooks.isEmpty()) {
            return;
        }

        HookContext context = HookContext.forToolCall("after_tool_call", toolName, result, null);
        for (Hook hook : hooks) {
            executor.execute(hook, context);
        }
    }
}
