#!/usr/bin/env bash
# 在没有 x86_64 aapt2 的 ARM64 主机上，对 Android app 模块做 Kotlin+Compose 类型检查。
# 前置：gradle :app:resolveAllArtifacts 已下载依赖；core/applogic 已编译。
set -euo pipefail
cd "$(dirname "$0")/.."
: "${JAVA_HOME:?}"; export PATH="$JAVA_HOME/bin:$PATH"
KOTLINC="${KOTLINC:-kotlinc}"
ANDROID_JAR="${ANDROID_JAR:?需要 android-34/android.jar}"
COMPOSE_PLUGIN="${COMPOSE_PLUGIN:?需要 kotlin-compose-compiler-plugin jar}"
GRADLE_CACHE="${GRADLE_CACHE:-$HOME/.gradle/caches/modules-2}"

WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
AAR="$WORK/aar"; EXTRA="$WORK/extra"; OUT="$WORK/out"; mkdir -p "$AAR" "$EXTRA" "$OUT"

find "$GRADLE_CACHE" -name "*.aar" | while read -r aar; do
  unzip -p "$aar" classes.jar > "$AAR/$(echo "$aar" | md5sum | cut -c1-16).jar" 2>/dev/null || true
done

# KMP common 库的 Android 变体不会作为独立构件被传递下载，显式补齐
base="https://dl.google.com/dl/android/maven2"
curl -sL -o "$EXTRA/room-common.jar"     "$base/androidx/room/room-common/2.6.1/room-common-2.6.1.jar"
curl -sL -o "$EXTRA/lifecycle-common.jar" "$base/androidx/lifecycle/lifecycle-common-jvm/2.8.6/lifecycle-common-jvm-2.8.6.jar"
curl -sL -o "$EXTRA/savedstate.aar-classes" "$base/androidx/savedstate/savedstate/1.2.1/savedstate-1.2.1.aar"
unzip -p "$EXTRA/savedstate.aar-classes" classes.jar > "$EXTRA/savedstate.jar" 2>/dev/null || true

CP="$ANDROID_JAR:core/build/classes/kotlin/main:applogic/build/classes/kotlin/main"
CP="$CP:$(find "$EXTRA" -name '*.jar' | tr '\n' ':')"
CP="$CP:$(find "$AAR" -name '*.jar' | tr '\n' ':')"
CP="$CP:$(find "$GRADLE_CACHE" -name '*.jar' ! -name '*sources*' \
           ! -path '*compiler-gradle-plugin*' ! -path '*kotlin-gradle-plugin*' | tr '\n' ':')"

find "$PWD/app/src/main/kotlin" -name '*.kt' > "$WORK/srcs.txt"
"$KOTLINC" @"$WORK/srcs.txt" -classpath "$CP" -d "$OUT" \
  -Xplugin="$COMPOSE_PLUGIN" -language-version 1.9 -jvm-target 17
echo "APP TYPECHECK OK: $(find "$OUT" -name '*.class' | wc -l) classes"
