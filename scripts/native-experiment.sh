#!/usr/bin/env bash
# Experiment: the GraalVM native executable of the app.
#   scripts/native-experiment.sh --agent     run the normal app on GraalVM with the tracing agent, exercise it, and save what it
#                                            used by reflection under src/main/resources/META-INF/native-image/.../FinancialDashboard-agent
#   scripts/native-experiment.sh --rebuild   build the native executable again, then test it
#   scripts/native-experiment.sh             test the executable (build it first if there is none)
# Run it in an ordinary Terminal: Gradle and the app open local sockets. Your real data is never touched: the app runs from a
# temporary folder, on its own port, with its own database.
set -u
cd "$(dirname "$0")/.."
ROOT="$(pwd)"

# Spring Boot 4 writes its native-image metadata in the format of GraalVM 25 (GraalVM 24 stops with "Missing attribute(s) [glob]"),
# so this wants GraalVM 25 or newer: the newest graalvm-jdk-NN in ~/Library/Java/JavaVirtualMachines, or GRAALVM_HOME.
GRAAL="${GRAALVM_HOME:-}"
if [ -z "$GRAAL" ]; then
    GRAAL="$(ls -d "$HOME"/Library/Java/JavaVirtualMachines/graalvm-jdk-2[5-9]*/Contents/Home 2>/dev/null | sort -V | tail -1)"
fi
[ -n "$GRAAL" ] && [ -x "$GRAAL/bin/native-image" ] || { echo "No GraalVM 25+ with native-image found. Install Oracle GraalVM 25 or set GRAALVM_HOME."; exit 2; }
export JAVA_HOME="$GRAAL"
JAVA_MAJOR="$("$GRAAL/bin/java" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)"
echo "Using GraalVM $JAVA_MAJOR at $GRAAL"
BIN="$ROOT/build/native/nativeCompile/FinancialDashboard"
AGENT_OUT="$ROOT/src/main/resources/META-INF/native-image/com.learning.spring/FinancialDashboard-agent"
PORT=8099
URL="http://127.0.0.1:$PORT"
PASS_WORD="native-test-pw-1"
MODE="${1:-test}"

# the checks themselves are in scripts/smoke-test.sh (the release workflow runs the same ones)
SMOKE="$ROOT/scripts/smoke-test.sh"

# ---------------------------------------------------------------------------------------------------- the modes
if [ "$MODE" = "--agent" ]; then
    echo "== Building the normal jar =="
    ./gradlew bootJar -PjavaVersion="$JAVA_MAJOR" -x test -x jsTest || { echo "BUILD FAILED"; exit 1; }
    JAR="$(ls "$ROOT"/build/libs/FinancialDashboard-*.jar | grep -v plain | head -1)"
    rm -rf "$AGENT_OUT"; mkdir -p "$AGENT_OUT"
    "$SMOKE" "$GRAAL/bin/java" -agentlib:native-image-agent=config-output-dir="$AGENT_OUT" -jar "$JAR"
    status=$?
    sleep 2
    # Hibernate 7.4 picks its byte-code provider only through the Java service lookup. A native image must not see the ByteBuddy one (it
    # makes classes at run time), so what the recording saw of it is taken out again.
    python3 - "$AGENT_OUT/reachability-metadata.json" <<'PY'
import json, sys
p = sys.argv[1]
d = json.load(open(p))
d["resources"] = [r for r in d.get("resources", []) if "org.hibernate.bytecode.spi.BytecodeProvider" not in json.dumps(r)]
d["reflection"] = [r for r in d.get("reflection", []) if "org.hibernate.bytecode.internal.bytebuddy." not in json.dumps(r.get("type", ""))]
json.dump(d, open(p, "w"), indent=2)
PY
    echo; echo "Recorded: $(ls "$AGENT_OUT" | tr '\n' ' ')"
    echo "Now build the native executable with: scripts/native-experiment.sh --rebuild"
    exit $status
fi

if [ "$MODE" = "--rebuild" ] || [ ! -x "$BIN" ]; then
    echo "== Building the native executable (this takes several minutes) =="
    ./gradlew nativeCompile -PjavaVersion="$JAVA_MAJOR" -x test -x jsTest || { echo "BUILD FAILED"; exit 1; }
fi
ls -lh "$BIN" | awk '{print "Executable size: " $5}'
exec "$SMOKE" "$BIN"
