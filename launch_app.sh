#!/bin/sh
# Development runner: launches the app from source with the full runtime classpath.
# Usage: ./launch_app.sh [rom_path]
#
# For a double-clickable app, use ./make_launcher.sh instead: it builds the uber jar and
# installs "AI Plays Online.app", which always starts the newest jar of this repository.
cd "$(dirname "$0")"
ROM="${1:-}"
if [ -n "$ROM" ]; then
  ./gradlew startApp \
    -PappArgs="$ROM" \
    -PappJvmArgs="-Xmx2g -Duser.country=US -Duser.language=en" \
    > /tmp/ai-plays-online-app.log 2>&1
else
  ./gradlew startApp \
    -PappJvmArgs="-Xmx2g -Duser.country=US -Duser.language=en" \
    > /tmp/ai-plays-online-app.log 2>&1
fi
echo "gradle startApp exited: $?"
