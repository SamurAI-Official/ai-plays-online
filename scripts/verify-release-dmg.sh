#!/bin/bash
# Rehearses a release: builds the macOS app exactly as `.github/workflows/release.yml` does, for a given tag, and runs
# the same assertions on the result. Tagging is what triggers the real thing, so this is how to find out beforehand
# that the packaged app is right — the 1.0.1 release failed on the first ROM launch because its bundled runtime was
# missing java.net.http, which the module check below catches.
#
# usage: scripts/verify-release-dmg.sh v1.0.2-rc1
#
# It builds locally, so it needs macOS. On a machine whose JDK is Homebrew's, pass the vendor override the script
# already passes (Compose refuses that JDK for packaging; the release runner uses Temurin).
set -u
cd "$(dirname "$0")/.." || exit 1
TAG="${1:?usage: scripts/verify-release-dmg.sh <tag>}"

export GITHUB_REF_TYPE=tag
export GITHUB_REF_NAME="$TAG"
log="/tmp/verify-release-dmg-${TAG}.log"

echo "== packaging $TAG =="
if ! ./gradlew packageDmg -Pcompose.desktop.packaging.checkJdkVendor=false --console=plain > "$log" 2>&1; then
    echo "packageDmg FAILED for $TAG:"
    tail -15 "$log"
    exit 1
fi

app="build/compose/binaries/main/app/AI Plays Online.app"
plist="$app/Contents/Info.plist"
# Installers only take MAJOR.MINOR.BUILD, so a pre-release tag (v1.0.2-rc1) packages as 1.0.2.
expected="${TAG#v}"
expected="${expected%%-*}"

fail=0
check() { # check <what> <got> <want>
    if [ "$2" = "$3" ]; then
        echo "  ok: $1 = $2"
    else
        echo "  FAIL: $1 = '$2', expected '$3'"
        fail=1
    fi
}

check "architecture" "$(uname -m)" "arm64"
check "bundle id" "$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$plist")" "me.nathanfallet.aiplaysonline"
check "bundle version" "$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$plist")" "$expected"
check "category" "$(/usr/libexec/PlistBuddy -c 'Print :LSApplicationCategoryType' "$plist")" "public.app-category.games"
check "minimum macOS" "$(/usr/libexec/PlistBuddy -c 'Print :LSMinimumSystemVersion' "$plist")" "11.0"
check "main binary" "$(file "$app/Contents/MacOS/AI Plays Online" | grep -c arm64)" "1"

modules="/tmp/verify-release-dmg-${TAG}-modules.txt"
# `jimage` from the JDK that built it, or from the PATH: JAVA_HOME is not set everywhere (CI sets it, a shell may not).
if [ -x "${JAVA_HOME:-/nonexistent}/bin/jimage" ]; then
    jimage_bin="$JAVA_HOME/bin/jimage"
else
    jimage_bin="$(command -v jimage || echo jimage)"
fi
"$jimage_bin" list "$app/Contents/runtime/Contents/Home/lib/modules" > "$modules"
# jlink only ships what it is asked for: the core downloader needs java.net.http, and losing it is invisible until the
# first ROM launch (that is exactly how 1.0.1 broke).
for module in java.instrument java.management java.net.http jdk.unsupported; do
    check "runtime module $module" "$(grep -c "^Module: ${module}\$" "$modules")" "1"
done

dmg=$(ls build/compose/binaries/main/dmg/*.dmg 2>/dev/null | head -n 1)
check "DMG" "$([ -n "$dmg" ] && echo yes)" "yes"
[ "$fail" = "0" ] && echo "== $TAG: the packaged app is what the release workflow expects ($dmg) =="
exit "$fail"
