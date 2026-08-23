package biz.brumm.infrastructure.adapter.out.hook;

import biz.brumm.config.HookProperties;
import biz.brumm.domain.model.Hook;
import biz.brumm.domain.model.HookContext;
import biz.brumm.domain.model.HookResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Führt Hook-Scripts via ProcessBuilder aus.
 * Das Script erhält den Hook-Kontext als Umgebungsvariablen (JCLAW_HOOK_*).
 * Exit-Code 0 = proceed, alles andere = block.
 */
@Component
public class HookScriptExecutor {

    private static final Logger log = LoggerFactory.getLogger(HookScriptExecutor.class);

    private static final String ENV_PREFIX = "JCLAW_HOOK_";
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    private final HookProperties properties;

    public HookScriptExecutor(HookProperties properties) {
        this.properties = properties;
    }

    /**
     * Führt ein Hook-Script aus und gibt das Ergebnis zurück.
     *
     * @param hook    Der auszuführende Hook
     * @param context Der Hook-Kontext
     * @return HookResult mit Exit-Code und Ausgabe
     */
    public HookResult execute(Hook hook, HookContext context) {
        int timeout = properties.scriptTimeout() > 0 ? properties.scriptTimeout() : DEFAULT_TIMEOUT_SECONDS;

        try {
            ProcessBuilder pb = new ProcessBuilder(hook.scriptPath().toString());
            pb.directory(hook.scriptPath().getParent().toFile());
            pb.redirectErrorStream(true);

            // Hook-Kontext als Umgebungsvariablen setzen
            Map<String, String> env = pb.environment();
            env.put(ENV_PREFIX + "STAGE", context.stage());
            putIfNotNull(env, ENV_PREFIX + "PROMPT", context.prompt());
            putIfNotNull(env, ENV_PREFIX + "TOOL_NAME", context.toolName());
            putIfNotNull(env, ENV_PREFIX + "TOOL_ARGS", context.toolArgs());
            putIfNotNull(env, ENV_PREFIX + "SESSION_ID", context.sessionId());
            for (Map.Entry<String, String> entry : context.metadata().entrySet()) {
                env.put(ENV_PREFIX + entry.getKey().toUpperCase(), entry.getValue());
            }

            log.debug("Hook '{}' wird ausgeführt: {} (Timeout: {}s)", hook.name(), hook.scriptPath(), timeout);

            Process process = pb.start();
            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);

            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();

            if (!finished) {
                process.destroyForcibly();
                log.warn("Hook '{}' hat {}s Timeout überschritten — abgebrochen.", hook.name(), timeout);
                return HookResult.block(hook.name(), "Timeout nach " + timeout + "s");
            }

            int exitCode = process.exitValue();
            if (exitCode == 0) {
                log.debug("Hook '{}' erfolgreich (exit=0).", hook.name());
                return HookResult.proceed(hook.name(), output.isEmpty() ? null : output);
            } else {
                log.warn("Hook '{}' blockiert (exit={}): {}", hook.name(), exitCode, output);
                return HookResult.block(hook.name(), output.isEmpty() ? "exit=" + exitCode : output);
            }
        } catch (IOException e) {
            log.error("Hook '{}' konnte nicht ausgeführt werden: {}", hook.name(), e.getMessage());
            return HookResult.block(hook.name(), "IOException: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Hook '{}' wurde unterbrochen.", hook.name());
            return HookResult.block(hook.name(), "Interrupted");
        }
    }

    private void putIfNotNull(Map<String, String> env, String key, String value) {
        if (value != null) {
            env.put(key, value);
        }
    }
}
