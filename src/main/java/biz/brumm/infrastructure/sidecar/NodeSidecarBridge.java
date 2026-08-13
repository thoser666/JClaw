package biz.brumm.infrastructure.sidecar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Verwaltete JSON-RPC-Bridge zwischen dem Java-Kern und einem Node.js-Sidecar-Prozess
 * (JSON-RPC 2.0, Newline-delimited über stdio, siehe {@code docs/bridge-protocol.md}).
 * <p>
 * Die Bridge startet den Sidecar, wartet auf den {@code sidecar.ready}-Handshake, versendet
 * Requests asynchron über einen Reader-Thread und liefert Ergebnisse über
 * {@link CompletableFuture} zurück. Aufrufe laufen gegen ein konfigurierbares Call-Timeout
 * ({@link SidecarTimeoutException}); strukturierte Sidecar-Fehler werden als
 * {@link SidecarCallException} (mit JSON-RPC-Fehlercode) gemeldet. Ein abgestürzter Prozess
 * kann über {@link #restart()} neu gestartet werden.
 */
public class NodeSidecarBridge implements Closeable {

    public static final String METHOD_READY = "sidecar.ready";
    public static final String METHOD_PING = "sidecar.ping";
    public static final String METHOD_INFO = "sidecar.info";
    public static final String METHOD_LIST_TOOLS = "sidecar.listTools";
    public static final String METHOD_CALL_TOOL = "tool.call";

    public static final int ERROR_METHOD_NOT_FOUND = -32601;
    public static final int ERROR_TOOL_NOT_FOUND = -32001;
    public static final int ERROR_TOOL_EXECUTION = -32002;
    public static final int ERROR_INTERNAL = -32003;
    public static final int ERROR_TIMEOUT = -32004;

    public static final long DEFAULT_CALL_TIMEOUT_MILLIS = 15_000;
    public static final long DEFAULT_READY_TIMEOUT_MILLIS = 5_000;

    private static final long PROCESS_STOP_TIMEOUT_SECONDS = 5;

    private static final Logger log = LoggerFactory.getLogger(NodeSidecarBridge.class);

    private final ObjectMapper objectMapper;
    private final JsonRpcLineCodec codec;
    private final String script;
    private final long callTimeoutMillis;
    private final long readyTimeoutMillis;

    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<Long, CompletableFuture<JsonRpcMessage>> pending = new ConcurrentHashMap<>();

    private volatile Process process;
    private volatile BufferedWriter stdin;
    private volatile Thread readerThread;
    private volatile CountDownLatch readyLatch = new CountDownLatch(1);
    private volatile JsonNode readyInfo;

    private NodeSidecarBridge(String script, ObjectMapper objectMapper, long callTimeoutMillis, long readyTimeoutMillis) {
        this.script = script;
        this.objectMapper = objectMapper;
        this.codec = new JsonRpcLineCodec(objectMapper);
        this.callTimeoutMillis = callTimeoutMillis;
        this.readyTimeoutMillis = readyTimeoutMillis;
    }

    /** Lädt das mitgelieferte Referenz-Sidecar ({@code sidecar/protocol-sidecar.js}) vom Classpath. */
    public static String defaultScript() {
        try (InputStream in = NodeSidecarBridge.class.getResourceAsStream("/sidecar/protocol-sidecar.js")) {
            if (in == null) {
                throw new IllegalStateException("Referenz-Sidecar /sidecar/protocol-sidecar.js fehlt im Classpath.");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Referenz-Sidecar konnte nicht geladen werden.", e);
        }
    }

    public static NodeSidecarBridge start(ObjectMapper objectMapper) throws IOException {
        return start(defaultScript(), objectMapper);
    }

    public static NodeSidecarBridge start(String script, ObjectMapper objectMapper) throws IOException {
        return start(script, objectMapper, DEFAULT_CALL_TIMEOUT_MILLIS, DEFAULT_READY_TIMEOUT_MILLIS);
    }

    public static NodeSidecarBridge start(String script, ObjectMapper objectMapper, long callTimeoutMillis, long readyTimeoutMillis)
            throws IOException {
        NodeSidecarBridge bridge = new NodeSidecarBridge(script, objectMapper, callTimeoutMillis, readyTimeoutMillis);
        bridge.startProcess();
        return bridge;
    }

    /** Pingt den Sidecar an; {@code true}, wenn er mit {@code {pong: true}} antwortet. */
    public boolean ping() throws IOException, SidecarCallException, SidecarTimeoutException {
        JsonNode result = execute(METHOD_PING, null);
        return result != null && result.path("pong").asBoolean(false);
    }

    /** Liefert Metadaten des Sidecars ({@code name}, {@code version}, {@code node}). */
    public JsonNode info() throws IOException, SidecarCallException, SidecarTimeoutException {
        return execute(METHOD_INFO, null);
    }

    /** Liefert die vom Sidecar registrierten Tools (Name, Beschreibung, Argument-Schema). */
    public List<SidecarToolDescriptor> listTools() throws IOException, SidecarCallException, SidecarTimeoutException {
        JsonNode result = execute(METHOD_LIST_TOOLS, null);
        List<SidecarToolDescriptor> tools = new ArrayList<>();
        if (result != null && result.isArray()) {
            for (JsonNode node : result) {
                tools.add(new SidecarToolDescriptor(
                        node.path("name").asString(),
                        node.path("description").asString(),
                        node.hasNonNull("parameters") ? node.get("parameters") : null));
            }
        }
        return tools;
    }

    /** Ruft ein Sidecar-Tool mit den gegebenen Argumenten auf und liefert sein Ergebnis. */
    public JsonNode callTool(String name, JsonNode arguments) throws IOException, SidecarCallException, SidecarTimeoutException {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("name", name);
        if (arguments != null) {
            params.set("arguments", arguments);
        }
        return execute(METHOD_CALL_TOOL, params);
    }

    /** Beendet den laufenden Prozess und startet einen neuen (Handshake inklusive). */
    public void restart() throws IOException {
        if (closed.get()) {
            throw new IOException("Die Sidecar-Bridge ist geschlossen; ein Neustart ist nicht möglich.");
        }
        log.info("Node-Sidecar wird neu gestartet.");
        closeProcess();
        startProcess();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            closeProcess();
        }
    }

    JsonNode execute(String method, JsonNode params) throws IOException, SidecarCallException, SidecarTimeoutException {
        ensureRunning();
        long id = nextId.getAndIncrement();
        CompletableFuture<JsonRpcMessage> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            stdin.write(codec.encode(JsonRpcMessage.request(id, method, params)));
            stdin.flush();
        } catch (IOException e) {
            pending.remove(id);
            throw new IOException("Sidecar-Aufruf '" + method + "' konnte nicht gesendet werden: " + e.getMessage(), e);
        }

        JsonRpcMessage response = awaitResponse(method, id, future);
        if (response.error() != null) {
            throw new SidecarCallException(response.errorCode(), response.errorMessage(), method);
        }
        return response.result();
    }

    private JsonRpcMessage awaitResponse(String method, long id, CompletableFuture<JsonRpcMessage> future)
            throws IOException, SidecarTimeoutException {
        try {
            return future.get(callTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.remove(id);
            throw new SidecarTimeoutException(method, callTimeoutMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pending.remove(id);
            throw new IOException("Unterbrochen während Sidecar-Aufruf '" + method + "'.", e);
        } catch (ExecutionException e) {
            pending.remove(id);
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException("Sidecar-Aufruf '" + method + "' fehlgeschlagen.", cause);
        }
    }

    private void ensureRunning() throws IOException {
        if (closed.get()) {
            throw new IOException("Die Sidecar-Bridge ist geschlossen.");
        }
        Process process = this.process;
        if (process == null || !process.isAlive()) {
            throw new IOException("Der Node-Sidecar-Prozess ist nicht aktiv.");
        }
    }

    private void startProcess() throws IOException {
        Process process = new ProcessBuilder("node", "-e", script).start();
        this.process = process;
        this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        drainStderr(process);
        readyInfo = null;
        readyLatch = new CountDownLatch(1);
        pending.clear();

        this.readerThread = new Thread(() -> readerLoop(process, reader), "jclaw-sidecar-reader");
        this.readerThread.setDaemon(true);
        this.readerThread.start();

        log.info("Node-Sidecar-Prozess gestartet (pid={}); warte auf {} ...", process.pid(), METHOD_READY);
        try {
            if (!readyLatch.await(readyTimeoutMillis, TimeUnit.MILLISECONDS)) {
                log.error("Node-Sidecar (pid={}) hat sich nicht innerhalb von {} ms bereitgemeldet.", process.pid(), readyTimeoutMillis);
                closeProcess();
                throw new SidecarTimeoutException(METHOD_READY, readyTimeoutMillis);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            closeProcess();
            throw new IOException("Unterbrochen während des Sidecar-Handshakes.", e);
        }
        log.info("Node-Sidecar bereit (pid={}): {}.", process.pid(), readyInfo);
    }

    private void readerLoop(Process process, BufferedReader reader) {
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                JsonRpcMessage message;
                try {
                    message = codec.decode(line);
                } catch (IllegalArgumentException e) {
                    log.warn("Ungültige Sidecar-Ausgabe ignoriert: {}", e.getMessage());
                    continue;
                }
                if (message.isRequest() || message.isNotification()) {
                    if (message.isNotification() && METHOD_READY.equals(message.method())) {
                        readyInfo = message.params();
                        readyLatch.countDown();
                    }
                    continue;
                }
                CompletableFuture<JsonRpcMessage> future = pending.remove(message.id());
                if (future != null) {
                    future.complete(message);
                }
            }
            log.debug("Sidecar-Ausgabe beendet (pid={}).", process.pid());
        } catch (IOException e) {
            if (!closed.get() && process.isAlive()) {
                log.warn("Sidecar-Leseschleife abgebrochen (pid={}): {}", process.pid(), e.getMessage());
            }
        } finally {
            boolean intentionalStop = closed.get() || stdin == null;
            if (process.isAlive() && !intentionalStop) {
                log.warn("Node-Sidecar (pid={}) hat die Ausgabe ohne close() beendet.", process.pid());
            }
            failPending(new IOException("Der Node-Sidecar-Prozess hat die Ausgabe beendet (pid=" + process.pid() + ")."));
        }
    }

    private void failPending(IOException cause) {
        for (CompletableFuture<JsonRpcMessage> future : pending.values()) {
            future.completeExceptionally(cause);
        }
        pending.clear();
    }

    private void drainStderr(Process process) {
        Thread stderrThread = new Thread(() -> {
            try (BufferedReader err = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = err.readLine()) != null) {
                    log.debug("Sidecar-stderr (pid={}): {}", process.pid(), line);
                }
            } catch (IOException ignored) {
                // Prozess beendet: Schleife endet von selbst
            }
        }, "jclaw-sidecar-stderr");
        stderrThread.setDaemon(true);
        stderrThread.start();
    }

    private void closeProcess() {
        BufferedWriter writer = stdin;
        stdin = null;
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignored) {
                // Prozess ggf. schon beendet
            }
        }
        Process process = this.process;
        this.process = null;
        if (process != null) {
            if (process.isAlive()) {
                process.destroy();
                try {
                    if (!process.waitFor(PROCESS_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                        process.waitFor(PROCESS_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
            }
            log.info("Node-Sidecar-Prozess beendet (pid={}).", process.pid());
        }
    }

    boolean processAlive() {
        Process process = this.process;
        return process != null && process.isAlive();
    }

    long pid() {
        Process process = this.process;
        return process != null ? process.pid() : -1;
    }

    JsonNode readyInfo() {
        return readyInfo;
    }
}
