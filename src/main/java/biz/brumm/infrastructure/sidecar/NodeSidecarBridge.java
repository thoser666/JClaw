package biz.brumm.infrastructure.sidecar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Feasibility-Spike für die Architektur-Entscheidung ADR-0001: Java-Kern kommuniziert
 * per JSON-RPC 2.0 (Newline-delimited) über stdio mit einem Node.js-Sidecar-Prozess.
 * <p>
 * Der {@code ECHO_SCRIPT} ist ein Minimal-Sidecar, der einen {@code add}-Aufruf beantwortet
 * und damit die Prozess-Lebenszyklus- und Framing-Annahmen der Bridge validiert. Die
 * vollständige Bridge (Plugin-Registrierung, Hooks, Channels) entsteht in P1-03/P4-01.
 */
public class NodeSidecarBridge implements Closeable {

    public static final String ECHO_SCRIPT = """
            const readline = require('readline');
            const rl = readline.createInterface({ input: process.stdin });
            rl.on('line', (line) => {
              let req;
              try { req = JSON.parse(line); } catch { return; }
              if (req.method === 'add') {
                process.stdout.write(JSON.stringify({ jsonrpc: '2.0', id: req.id, result: req.params.a + req.params.b }) + '\\n');
              } else {
                process.stdout.write(JSON.stringify({ jsonrpc: '2.0', id: req.id, error: 'unbekannte Methode' }) + '\\n');
              }
            });
            """;

    private static final Logger log = LoggerFactory.getLogger(NodeSidecarBridge.class);

    private final Process process;
    private final BufferedWriter stdin;
    private final BufferedReader stdout;
    private final JsonRpcLineCodec codec;
    private long nextId = 1;

    private NodeSidecarBridge(Process process, ObjectMapper objectMapper) {
        this.process = process;
        this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        this.codec = new JsonRpcLineCodec(objectMapper);
    }

    public static NodeSidecarBridge start(ObjectMapper objectMapper) throws IOException {
        return start(ECHO_SCRIPT, objectMapper);
    }

    public static NodeSidecarBridge start(String script, ObjectMapper objectMapper) throws IOException {
        Process process = new ProcessBuilder("node", "-e", script).start();
        log.info("Node-Sidecar-Prozess gestartet (pid={}).", process.pid());
        return new NodeSidecarBridge(process, objectMapper);
    }

    public JsonRpcMessage call(String method, JsonNode params) throws IOException {
        long id = nextId++;
        stdin.write(codec.encode(JsonRpcMessage.request(id, method, params)));
        stdin.flush();

        String line;
        while ((line = stdout.readLine()) != null) {
            JsonRpcMessage response = codec.decode(line);
            if (response.id() != id) {
                continue;
            }
            if (response.error() != null) {
                throw new IllegalStateException("Sidecar-Fehler für '" + method + "': " + response.error());
            }
            return response;
        }
        throw new IOException("Sidecar-Prozess beendet ohne Antwort (exit=" + process.exitValue() + ").");
    }

    boolean processAlive() {
        return process.isAlive();
    }

    @Override
    public void close() {
        try {
            stdin.close();
        } catch (IOException ignored) {
            // Prozess ggf. schon beendet
        }
        process.destroy();
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (process.isAlive()) {
            process.destroyForcibly();
        }
        log.info("Node-Sidecar-Prozess beendet.");
    }
}
