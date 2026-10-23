#!/bin/sh
# Launches the AI plays Online app directly with the full runtime classpath.
# Usage: ./launch_app.sh [rom_path]
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
