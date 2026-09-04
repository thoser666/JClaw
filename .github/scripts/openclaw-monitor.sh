#!/usr/bin/env bash
#
# OpenClaw-Release- und Community-Scanner (JClaw)
#
# Wird wöchentlich vom GitHub-Workflow .github/workflows/openclaw-monitor.yml
# ausgeführt. Prüft, ob seit dem letzten Check eine neue OpenClaw-Version
# (github.com/openclaw/openclaw) erschienen ist, und durchsucht die OpenClaw-
# Community (Issues/PRs) nach neuen Feature-Requests. Ergebnis:
#   * Ein GitHub-Issue mit Zusammenfassung + roter Fährte auf die JClaw-Vision
#     (nur wenn es Neuigkeiten gibt)
#   * Aktualisierte STATE-Datei (.github/state/openclaw-last-checked.txt) mit
#     der zuletzt geprüften OpenClaw-Version (wird auto-committet)
#
# Das Issue ist eine Triage-Vorlage: Es trifft KEINE Roadmap-Entscheidung,
# sondern dokumentiert neue Features/Wünsche zur manuellen Prüfung gegen die
# JClaw-Vision (100% OpenClaw-Parität als Java/Spring-Port).
#
# Umgebung (vom Workflow gesetzt):
#   GH_TOKEN           GitHub-Token (lesend + issues:write + contents:write)
#   UPSTREAM           Repo, z. B. "openclaw/openclaw"
#   STATE_FILE         Pfad zur STATE-Datei (relativ zum Repo-Root)
#   LAST_N             Anzahl neuester Releases, die abgerufen werden (Default 20)
#   SCAN_DAYS          Community-Scan-Fenster in Tagen (Default 7)

set -euo pipefail

UPSTREAM="${UPSTREAM:-openclaw/openclaw}"
STATE_FILE="${STATE_FILE:-.github/state/openclaw-last-checked.txt}"
LAST_N="${LAST_N:-20}"
SCAN_DAYS="${SCAN_DAYS:-7}"

ROADMAP_URL="https://github.com/thoser666/JClaw/blob/develop/docs/parity-roadmap.md"
COMPAT_URL="https://github.com/thoser666/JClaw/blob/develop/docs/openclaw-compat.md"
README_URL="https://github.com/thoser666/JClaw#readme"

# ---------------------------------------------------------------- Hilfskonstrukte

die() { echo "❌ $*" >&2; exit 1; }
info() { echo "ℹ️  $*"; }
ok()   { echo "✅ $*"; }

# Extrahiert den stabilen numerischen Versionskern (YYYY.M.P) aus einem Tag-Namen.
# Beispiele: "v2026.8.2" -> 2026.8.2 | "2026.7.1-1" -> 2026.7.1 | "2026.8.1-beta.2" -> 2026.8.1
stable_core() {
  local tag="$1"
  tag="${tag#v}"
  sed -E 's/^([0-9]+\.[0-9]+\.[0-9]+).*/\1/' <<<"$tag"
}

# Vergleicht zwei Versionen a b: gibt 0 (true) aus, wenn a > b (sort -V nutzt Datums-/SemVer-Ordnung).
version_gt() {
  local a b
  a=$(stable_core "$1"); b=$(stable_core "$2")
  [ "$(printf '%s\n%s\n' "$a" "$b" | sort -V | tail -1)" = "$a" ] && [ "$a" != "$b" ]
}

# ---------------------------------------------------------------- STATE lesen

LAST_CHECKED=""
if [ -f "$STATE_FILE" ]; then
  LAST_CHECKED="$(head -n1 "$STATE_FILE" | tr -d '[:space:]')"
fi
info "Zuletzt geprüfte OpenClaw-Version: '${LAST_CHECKED:-<keine>}'"

# ---------------------------------------------------------------- Releases abrufen

info "Rufe Releases von $UPSTREAM ab..."
RELEASES_JSON="$(gh api "repos/$UPSTREAM/releases?per_page=$LAST_N")" || die "Release-API fehlgeschlagen"

# Höchste stabile Basis über alle abgerufenen Releases bestimmen.
LATEST_STABLE=""
mapfile -t TAGS < <(jq -r '.[] | .tag_name' <<<"$RELEASES_JSON")
for tag in "${TAGS[@]}"; do
  core=$(stable_core "$tag")
  if [ -z "$core" ] || [ "$core" = "$tag" ]; then
    continue  # kein YYYY.M.P-Muster → ignorieren (z. B. Pre-Release ohne Basis)
  fi
  if [ -z "$LATEST_STABLE" ] || version_gt "$core" "$LATEST_STABLE"; then
    LATEST_STABLE="$core"
  fi
done

ok "Neueste stabile OpenClaw-Basis: '$LATEST_STABLE'"

HAS_NEW_RELEASE=0
if [ -n "$LATEST_STABLE" ] && { [ -z "$LAST_CHECKED" ] || version_gt "$LATEST_STABLE" "$LAST_CHECKED"; }; then
  info "Neue OpenClaw-Version erkannt (letzte geprüft: ${LAST_CHECKED:-n/a})."
  HAS_NEW_RELEASE=1
else
  ok "Keine neue stabile OpenClaw-Version seit ${LAST_CHECKED:-n/a}."
fi

# ---------------------------------------------------------------- Community-Scan

info "Scanne OpenClaw-Community (Issues/PRs der letzten ${SCAN_DAYS} Tage)..."
SINCE="$(date -u -d "-$SCAN_DAYS days" +%Y-%m-%d)"

COMMUNITY_ISSUES="$(gh search issues --repo "$UPSTREAM" \
  "is:open created:>$SINCE (label:feature OR label:enhancement OR label:rfc OR label:feature-request) sort:reactions-desc" \
  --limit 8 --json number,title,html_url,createdAt,reactions 2>/dev/null | jq -c '.[]' || true)"

COMMUNITY_PRS="$(gh search issues --repo "$UPSTREAM" \
  "is:open is:pr created:>$SINCE head:feat sort:updated-desc" \
  --limit 6 --json number,title,html_url,createdAt 2>/dev/null | jq -c '.[]' || true)"

if [ -z "$COMMUNITY_ISSUES" ] && [ -z "$COMMUNITY_PRS" ]; then
  ok "Keine neuen Community-Feature-Requests im Zeitfenster."
fi

# ---------------------------------------------------------------- Body bauen

BODY_NEEDS=0
{
  echo "## Zusammenfassung (automatisch erzeugt: $(date -u +%Y-%m-%dT%H:%MZ))"
  echo ""
  echo "Referenz-Gateway: **\`$UPSTREAM\`** — neueste stabile Version: **\`$LATEST_STABLE\`**"
  echo ""
  echo "### OpenClaw-Release"
  echo ""
  if [ "$HAS_NEW_RELEASE" = "1" ]; then
    echo "- 🟢 **NEUE Version erkannt:** \`${LAST_CHECKED:-<keine>}\` → **\`$LATEST_STABLE\`**"
    echo "- Release: https://github.com/$UPSTREAM/releases"
  else
    echo "- Keine neue stabile Version seit \`${LAST_CHECKED:-<keine>}\`."
  fi
  echo ""
  echo "### Neue Community-Feature-Requests (letzte ${SCAN_DAYS} Tage)"
  echo ""
  if [ -n "$COMMUNITY_ISSUES" ] || [ -n "$COMMUNITY_PRS" ]; then
    echo "**Issues (Feature-Requests):**"
    if [ -n "$COMMUNITY_ISSUES" ]; then
      while IFS= read -r line; do
        [ -z "$line" ] && continue
        n=$(jq -r '.number' <<<"$line")
        t=$(jq -r '.title' <<<"$line")
        u=$(jq -r '.html_url' <<<"$line")
        r=$(jq -r '.reactions.total_count // 0' <<<"$line")
        echo "- #$n (👍 $r) — $t — $u"
      done <<<"$COMMUNITY_ISSUES"
    fi
    echo ""
    echo "**PRs (feat):**"
    if [ -n "$COMMUNITY_PRS" ]; then
      while IFS= read -r line; do
        [ -z "$line" ] && continue
        n=$(jq -r '.number' <<<"$line")
        t=$(jq -r '.title' <<<"$line")
        u=$(jq -r '.html_url' <<<"$line")
        echo "- #$n — $t — $u"
      done <<<"$COMMUNITY_PRS"
    fi
    BODY_NEEDS=1
  else
    echo "- Keine neuen Feature-Requests im Zeitfenster."
  fi
  echo ""
  echo "### Triage gegen die JClaw-Vision"
  echo ""
  echo "JClaw-Ziel: **100 % OpenClaw-Parität als Java/Spring-Port** ([README]($README_URL))."
  echo "Für jedes neue Feature/Wunsch prüfen:"
  echo ""
  echo "- [ ] Relevanz: passt es zur Roadmap-Themengruppe ([parity-roadmap]($ROADMAP_URL))?"
  echo "- [ ] Paritätsbezug: neues OpenClaw-Feature → als P-Item einordnen ([openclaw-compat]($COMPAT_URL))."
  echo "- [ ] Differenzierung: ist es eine JClaw-Chance (Stable API, eigene Datenhaltung)?"
  echo "- [ ] Community-Wunsch: besonders oft angefragte Features priorisieren."
  echo "- [ ] Roadmap aktualisieren und dieses Issue referenzieren/schließen."
  echo ""
  echo "---"
  echo "_Dieses Issue wurde automatisch vom weekly OpenClaw-Monitor erzeugt._"
} > /tmp/openclaw-body.md

# ---------------------------------------------------------------- STATE aktualisieren

if [ -n "$LATEST_STABLE" ]; then
  mkdir -p "$(dirname "$STATE_FILE")"
  printf '%s\n' "$LATEST_STABLE" > "$STATE_FILE"
  ok "STATE aktualisiert: $LATEST_STABLE"
fi

# ---------------------------------------------------------------- Ausgabe an den Workflow

if [ "$BODY_NEEDS" = "1" ] || [ "$HAS_NEW_RELEASE" = "1" ]; then
  echo "NEWS_DETECTED=1"
  echo "TITLE=OpenClaw $LATEST_STABLE — Release- & Community-Check"
  echo "BODY_FILE=/tmp/openclaw-body.md"
else
  echo "NEWS_DETECTED=0"
  echo "TITLE="
  echo "BODY_FILE="
fi
