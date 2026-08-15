/* JClaw Control-UI – schlankes Frontend ohne Frameworks.
 * Alle API-Daten werden ausschließlich über textContent gerendert (XSS-sicher). */

"use strict";

(function () {
    const $ = (selector) => document.querySelector(selector);
    const $$ = (selector) => Array.from(document.querySelectorAll(selector));

    /* ---------- Hilfsfunktionen ---------- */

    function el(tag, attrs, children) {
        const node = document.createElement(tag);
        if (attrs) {
            for (const [key, value] of Object.entries(attrs)) {
                if (key === "class") node.className = value;
                else if (key === "text") node.textContent = value;
                else node.setAttribute(key, value);
            }
        }
        if (children) {
            for (const child of children) {
                node.appendChild(child);
            }
        }
        return node;
    }

    function empty(node) {
        node.replaceChildren();
    }

    function setStatus(node, text, kind) {
        node.textContent = text || "";
        node.className = "status" + (kind ? " " + kind : "");
    }

    function badge(text, type) {
        return el("span", { class: "badge " + type, text: text });
    }

    function formatTime(timestamp) {
        if (!timestamp) return "";
        return new Date(timestamp).toLocaleString("de-DE");
    }

    async function api(path, options) {
        let response;
        try {
            response = await fetch(path, options);
        } catch (error) {
            throw new Error("Verbindung zum Server fehlgeschlagen: " + error.message);
        }
        if (!response.ok) {
            let message = "HTTP " + response.status;
            try {
                const body = await response.json();
                if (body && body.error) message = body.error;
            } catch (_) {
                /* Antwort ohne JSON-Body – Standardmeldung behalten */
            }
            throw new Error(message);
        }
        if (response.status === 204) return null;
        return response.json();
    }

    function buttonBusy(button, busy, label) {
        button.disabled = busy;
        button.textContent = busy ? "…" : label;
    }

    /* ---------- Navigation ---------- */

    const panels = $$(".panel");

    function showPanel(name) {
        panels.forEach((panel) => {
            const active = panel.id === "panel-" + name;
            panel.classList.toggle("is-active", active);
            panel.hidden = !active;
        });
        $$(".nav-item").forEach((item) => {
            const active = item.dataset.panel === name;
            item.classList.toggle("is-active", active);
            if (active) item.setAttribute("aria-current", "page");
            else item.removeAttribute("aria-current");
        });
        if (name === "skills" && !skillsLoaded) loadSkills();
        if (name === "plugins" && !pluginsLoaded) loadPlugins();
    }

    $$(".nav-item").forEach((item) => {
        item.addEventListener("click", () => showPanel(item.dataset.panel));
    });

    /* ---------- Agent ---------- */

    const taskForm = $("#task-form");
    const taskSubmit = $("#task-submit");
    const taskStatus = $("#task-status");
    const taskResult = $("#task-result");

    taskForm.addEventListener("submit", async (event) => {
        event.preventDefault();
        const prompt = $("#task-prompt").value.trim();
        const contextId = $("#task-context").value.trim() || null;
        if (!prompt) {
            setStatus(taskStatus, "Bitte gib eine Aufgabe ein.", "error");
            return;
        }
        setStatus(taskStatus, "Agent arbeitet …");
        empty(taskResult);
        taskResult.classList.add("hidden");
        buttonBusy(taskSubmit, true, "Ausführen");
        try {
            const response = await api("/api/v1/tasks", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ prompt, contextId }),
            });
            renderTaskResult(response);
            setStatus(taskStatus, "Fertig.");
        } catch (error) {
            setStatus(taskStatus, error.message, "error");
        } finally {
            buttonBusy(taskSubmit, false, "Ausführen");
        }
    });

    function renderTaskResult(response) {
        empty(taskResult);
        taskResult.classList.remove("hidden");

        const content = el("p", { class: "result-content", text: response.content });

        const meta = el("div", { class: "result-meta" });
        meta.appendChild(el("span", { text: response.iterations + " Iteration(en)" }));
        if (response.timestamp) {
            meta.appendChild(el("span", { text: formatTime(response.timestamp) }));
        }

        taskResult.appendChild(content);
        taskResult.appendChild(meta);

        if (response.toolInvocations && response.toolInvocations.length > 0) {
            const calls = el("details", { class: "tool-calls" });
            calls.appendChild(el("summary", { text: "Tool-Aufrufe (" + response.toolInvocations.length + ")" }));
            response.toolInvocations.forEach((invocation) => {
                const card = el("div", { class: "tool-call" });
                const head = el("div", { class: "tool-call-head" });
                head.appendChild(badge(invocation.name, "badge-type"));
                head.appendChild(el("span", { class: "tool-call-label", text: "Tool" }));
                card.appendChild(head);
                if (invocation.arguments) {
                    card.appendChild(el("span", { class: "tool-call-label", text: "Argumente" }));
                    card.appendChild(el("pre", { text: invocation.arguments }));
                }
                card.appendChild(el("span", { class: "tool-call-label", text: "Ergebnis" }));
                card.appendChild(el("pre", { text: invocation.result }));
                calls.appendChild(card);
            });
            taskResult.appendChild(calls);
        }
    }

    /* ---------- Konversationen ---------- */

    const conversationForm = $("#conversation-form");
    const conversationStatus = $("#conversation-status");
    const conversationList = $("#conversation-list");
    const deleteButton = $("#conversation-delete");
    let loadedConversationId = null;

    conversationForm.addEventListener("submit", async (event) => {
        event.preventDefault();
        const contextId = $("#conversation-context").value.trim();
        if (!contextId) {
            setStatus(conversationStatus, "Bitte gib eine Kontext-ID ein.", "error");
            return;
        }
        setStatus(conversationStatus, "Lädt Konversation …");
        try {
            const messages = await api("/api/v1/conversations/" + encodeURIComponent(contextId));
            renderConversation(messages);
            loadedConversationId = contextId;
            setStatus(conversationStatus,
                messages.length === 0 ? "Keine Nachrichten für diese Konversation." : "Geladen.");
        } catch (error) {
            loadedConversationId = null;
            empty(conversationList);
            setStatus(conversationStatus, error.message, "error");
        }
    });

    deleteButton.addEventListener("click", async () => {
        const contextId = $("#conversation-context").value.trim();
        if (!contextId) {
            setStatus(conversationStatus, "Bitte gib eine Kontext-ID ein.", "error");
            return;
        }
        setStatus(conversationStatus, "Löscht Konversation …");
        try {
            await api("/api/v1/conversations/" + encodeURIComponent(contextId), { method: "DELETE" });
            empty(conversationList);
            loadedConversationId = null;
            setStatus(conversationStatus, "Konversation gelöscht.");
        } catch (error) {
            setStatus(conversationStatus, error.message, "error");
        }
    });

    function renderConversation(messages) {
        empty(conversationList);
        if (messages.length === 0) {
            conversationList.appendChild(el("p", { class: "empty", text: "Keine Nachrichten." }));
            return;
        }
        messages.forEach((message) => {
            const role = String(message.role || "").toLowerCase();
            const css = role === "user" ? "msg msg-user"
                : role === "system" || role === "tool" ? "msg msg-system"
                : "msg";
            conversationList.appendChild(el("div", { class: css }, [
                el("span", { class: "msg-role", text: message.role || "?" }),
                el("span", { text: message.text }),
            ]));
        });
    }

    /* ---------- Skills ---------- */

    let skillsLoaded = false;
    const skillsStatus = $("#skills-status");
    const skillsList = $("#skills-list");

    async function loadSkills() {
        setStatus(skillsStatus, "Lädt Skills …", "info");
        try {
            const skills = await api("/api/v1/skills");
            renderSkills(skills);
            setStatus(skillsStatus, "");
        } catch (error) {
            empty(skillsList);
            setStatus(skillsStatus, error.message, "error");
        } finally {
            skillsLoaded = true;
        }
    }

    function renderSkills(skills) {
        empty(skillsList);
        if (skills.length === 0) {
            skillsList.appendChild(el("p", { class: "empty", text: "Keine Skills geladen." }));
            return;
        }
        skills.forEach((skill) => {
            const tile = el("div", { class: "tile" });
            const head = el("div", { class: "tile-head" });
            head.appendChild(el("h2", { class: "tile-title", text: skill.name }));
            head.appendChild(badge(skill.enabled ? "aktiv" : "inaktiv", skill.enabled ? "badge-on" : "badge-off"));
            tile.appendChild(head);
            tile.appendChild(el("p", { class: "tile-desc", text: skill.description }));
            skillsList.appendChild(tile);
        });
    }

    /* ---------- Plugins ---------- */

    let pluginsLoaded = false;
    const pluginsStatus = $("#plugins-status");
    const pluginsList = $("#plugins-list");

    async function loadPlugins() {
        setStatus(pluginsStatus, "Lädt Plugins …", "info");
        try {
            const plugins = await api("/api/v1/plugins");
            renderPlugins(plugins);
            setStatus(pluginsStatus, "");
        } catch (error) {
            empty(pluginsList);
            setStatus(pluginsStatus, error.message, "error");
        } finally {
            pluginsLoaded = true;
        }
    }

    function renderPlugins(plugins) {
        empty(pluginsList);
        if (plugins.length === 0) {
            pluginsList.appendChild(el("p", { class: "empty", text: "Keine Plugins erkannt." }));
            return;
        }
        plugins.forEach((plugin) => {
            const tile = el("div", { class: "tile" });
            const head = el("div", { class: "tile-head" });
            const title = plugin.name || plugin.id || "(ohne Namen)";
            head.appendChild(el("h2", { class: "tile-title", text: title }));
            head.appendChild(badge(plugin.valid ? "gültig" : "ungültig", plugin.valid ? "badge-on" : "badge-bad"));
            tile.appendChild(head);

            const meta = el("div", { class: "tile-meta" });
            meta.appendChild(badge(plugin.type, "badge-type"));
            meta.appendChild(document.createTextNode("  Version " + (plugin.version || "–")));
            tile.appendChild(meta);

            if (plugin.description) {
                tile.appendChild(el("p", { class: "tile-desc", text: plugin.description }));
            }
            if (!plugin.valid && plugin.validationMessage) {
                tile.appendChild(el("p", { class: "tile-desc", text: plugin.validationMessage }));
            }
            pluginsList.appendChild(tile);
        });
    }
})();
