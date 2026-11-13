#!/bin/bash
# Compile the pinned KSU-derived liquid primitives; not an APK build.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
KOTLIN_HOME=${KOTLIN_HOME:-/opt/kotlin/kotlinc}
ANDROID_JAR=${ANDROID_JAR:-/usr/lib/android-sdk/platforms/android-33/android.jar}
DEPS=${CAIRN_COMPOSE_JARS:-/opt/kotlin/maven/cls}
mkdir -p "$HERE/build"
CP="$ANDROID_JAR"
for f in "$DEPS"/*.jar; do CP="$CP:$f"; done
"$KOTLIN_HOME/bin/kotlinc" -Xplugin="$KOTLIN_HOME/lib/compose-compiler-plugin.jar" -jvm-target 17 -classpath "$CP" -d "$HERE/build/liquid-components.jar" "$HERE"/src/com/cairn/manager/ui/liquid/*.kt
