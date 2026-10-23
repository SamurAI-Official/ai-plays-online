#!/bin/sh
# Builds and installs the macOS launcher: "AI Plays Online.app".
#
# The app is a launcher over *this repository's build*: it starts the newest uber jar in
# build/compose/jars and builds one when it is missing, so double-clicking it always runs the
# current code (that is the bug this fixes: the old "AI Plays Pokemon.app" carried a jar copied
# on 19 October and kept starting that, whatever we changed here).
#
# Usage: ./make_launcher.sh [--no-build] [install-dir ...]
#   --no-build      reuse the uber jar already in build/compose/jars
#   install-dir     where to put the app (default: ~/Applications and ~/StreamBoyDS)
#
# Build the jar by hand with: ./gradlew packageUberJarForCurrentOS
set -eu

REPO="$(cd "$(dirname "$0")" && pwd)"
APP_NAME="AI Plays Online"
BUILD=1
INSTALL_DIRS=""

for arg in "$@"; do
    case "$arg" in
        --no-build) BUILD=0 ;;
        -h|--help) sed -n '2,15p' "$0"; exit 0 ;;
        *) INSTALL_DIRS="$INSTALL_DIRS $arg" ;;
    esac
done
if [ -z "$INSTALL_DIRS" ]; then
    INSTALL_DIRS="$HOME/Applications $HOME/StreamBoyDS"
fi

if [ "$BUILD" = 1 ]; then
    echo "== building the uber jar (./gradlew packageUberJarForCurrentOS) =="
    (cd "$REPO" && ./gradlew packageUberJarForCurrentOS)
fi

JAR="$(ls -t "$REPO"/build/compose/jars/"$APP_NAME"*.jar 2>/dev/null | head -1 || true)"
if [ -z "$JAR" ]; then
    echo "error: no uber jar in $REPO/build/compose/jars" >&2
    echo "       run: ./gradlew packageUberJarForCurrentOS" >&2
    exit 1
fi
echo "== launcher jar: $JAR =="

# The app version comes from the Gradle build, so the bundle never claims another one.
VERSION="$(sed -n 's/^ *packageVersion *= *"\(.*\)".*/\1/p' "$REPO/build.gradle.kts" | head -1)"
[ -n "$VERSION" ] || VERSION="1.0.0"

# --- build the bundle -------------------------------------------------------------------------
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
APP="$STAGE/$APP_NAME.app"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources" "$APP/Contents/Java"

sed -e "s|__REPO__|$REPO|g" "$REPO/launcher/launcher.sh" > "$APP/Contents/MacOS/launcher.sh"
chmod 755 "$APP/Contents/MacOS/launcher.sh"
sed -e "s|__VERSION__|$VERSION|g" "$REPO/launcher/Info.plist" > "$APP/Contents/Info.plist"
if [ -f "$REPO/launcher/icon.icns" ]; then
    cp "$REPO/launcher/icon.icns" "$APP/Contents/Resources/$APP_NAME.icns"
fi

# --- install ----------------------------------------------------------------------------------
for dir in $INSTALL_DIRS; do
    mkdir -p "$dir"
    rm -rf "$dir/$APP_NAME.app"
    cp -R "$APP" "$dir/$APP_NAME.app"
    echo "== installed: $dir/$APP_NAME.app =="
done

cat <<EOF

Double-click "$APP_NAME" in Finder, or:
  open -a "$APP_NAME"                          # the game picker
  open -a "$APP_NAME" --args "/path/to/game.nds"   # straight into a game

The app starts the newest jar of $REPO/build/compose/jars, so after any change here run
./make_launcher.sh (or ./gradlew packageUberJarForCurrentOS) and the launcher runs it.
Its output goes to ~/Library/Logs/AI plays Online.log.
EOF
