#!/bin/bash
# AI Plays Online — macOS launcher (template: make_launcher.sh fills in __REPO__).
#
# This app starts the *current build of the repository*: it runs the newest uber jar in
# <repo>/build/compose/jars, and builds it when there is none. Double-clicking it therefore
# always runs the code that is in the repository, instead of a jar copied into the app once
# and left behind.
#
# Pass a ROM to skip the game picker:
#   open -a "AI Plays Online" --args "/path/to/your game.nds"
#
# Environment (all optional): AI_PLAYS_ONLINE_HOME (where the repository is), AI_PLAYS_ONLINE_ROM,
# AI_PLAYS_ONLINE_DATA_DIR, and the LLM settings below.
set -eu

REPO="${AI_PLAYS_ONLINE_HOME:-__REPO__}"
CONTENTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG="$HOME/Library/Logs/AI plays Online.log"
mkdir -p "$(dirname "$LOG")"

newest_jar() {
    ls -t "$REPO"/build/compose/jars/"AI Plays Online"*.jar 2>/dev/null | head -1
}

JAR="$(newest_jar || true)"
if [ -z "$JAR" ] && [ -d "$CONTENTS_DIR/Java" ]; then
    JAR="$(ls -t "$CONTENTS_DIR"/Java/*.jar 2>/dev/null | head -1 || true)"
fi
if [ -z "$JAR" ]; then
    echo "== $(date) no uber jar yet: building $REPO ==" >> "$LOG"
    (cd "$REPO" && ./gradlew packageUberJarForCurrentOS >> "$LOG" 2>&1) || {
        echo "build failed (see this log)" >> "$LOG"
        exit 1
    }
    JAR="$(newest_jar || true)"
fi
if [ -z "$JAR" ]; then
    echo "no uber jar in $REPO/build/compose/jars: run ./gradlew packageUberJarForCurrentOS there" >> "$LOG"
    exit 1
fi

# --- java -----------------------------------------------------------------------------------
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA="$JAVA_HOME/bin/java"
else
    JAVA="/usr/bin/java"
fi

# --- what the app needs ---------------------------------------------------------------------
# No ROM on purpose: the app opens on its game picker. Set AI_PLAYS_ONLINE_ROM to skip it.
export AI_PLAYS_ONLINE_DATA_DIR="${AI_PLAYS_ONLINE_DATA_DIR:-$HOME/.ai-plays-online}"
# The decision model (edit these, or unset them to use the settings saved in the app):
export DECISION_BACKEND="${DECISION_BACKEND:-llm}"
export LLM_PROVIDER="${LLM_PROVIDER:-openai}"
# The model loaded on the LM Studio box: it has to be one that actually loads (a name it lists in /v1/models but
# cannot load answers HTTP 400 "Failed to load model").
export LLM_MODEL="${LLM_MODEL:-ornith-1.0-9b}"
export LLM_THINKING="${LLM_THINKING:-false}"
export OPENAI_BASE_URL="${OPENAI_BASE_URL:-http://100.102.229.67:1234}"
export OPENAI_API_KEY="${OPENAI_API_KEY:-lm-studio}"

echo "== $(date) starting $JAR $* ==" >> "$LOG"
# The app's own output goes to the same log: that is all we can see of a Finder launch.
exec "$JAVA" -Xms1g -Xmx4g -XX:+UseZGC -Xdock:name="AI plays Online" -jar "$JAR" "$@" >> "$LOG" 2>&1
