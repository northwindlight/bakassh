#!/usr/bin/env bash
set -euo pipefail
root_dir=$(cd -- "$(dirname -- "$0")/../.." && pwd)
: "${JSCH_JAR:?Set JSCH_JAR to the cached jsch-0.2.26.jar}"
test_dir=$(mktemp -d)
server_pid=
cleanup() {
    if [[ -n "$server_pid" ]]; then kill "$server_pid" 2>/dev/null || true; wait "$server_pid" 2>/dev/null || true; fi
    rm -rf -- "$test_dir"
}
trap cleanup EXIT
jdk_dir=${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}
cpp_dir="$root_dir/app/src/main/cpp"
gcc -std=c11 -shared -fPIC -O2 -Wall -Wno-maybe-uninitialized \
    -I"$jdk_dir/include" -I"$jdk_dir/include/linux" -I"$root_dir/tests/host" \
    -I"$cpp_dir/libvterm/include" -I"$cpp_dir/libvterm/src" \
    "$cpp_dir/libvterm_jni.c" "$cpp_dir"/libvterm/src/*.c -o "$test_dir/libbakasshterm.so"
"$jdk_dir/bin/javac" -cp "$JSCH_JAR" -d "$test_dir" \
    "$root_dir/tests/host/android/util/Log.java" \
    "$root_dir/app/src/main/java/dev/northwind/bakassh/NativeTerm.java" \
    "$root_dir/app/src/main/java/dev/northwind/bakassh/SshTransport.java" \
    "$root_dir/app/src/main/java/dev/northwind/bakassh/PendingResize.java" \
    "$root_dir/app/src/main/java/dev/northwind/bakassh/TerminalSession.java" \
    "$root_dir/tests/host/TransportRegression.java" \
    "$root_dir/tests/host/SessionRegression.java"
ssh-keygen -q -t rsa -b 2048 -m PEM -N '' -f "$test_dir/hostkey"
ssh-keygen -q -t rsa -b 2048 -m PEM -N '' -f "$test_dir/clientkey"
python3 "$root_dir/tests/host/server.py" "$test_dir/hostkey" "$test_dir/clientkey.pub" "$test_dir/port" > "$test_dir/server.log" 2>&1 &
server_pid=$!
test_port=$(python3 - "$test_dir/port" <<'PORT'
import pathlib, sys, time
port = pathlib.Path(sys.argv[1])
for _ in range(50):
    if port.exists():
        print(port.read_text())
        break
    time.sleep(.1)
else:
    raise RuntimeError("SSH fixture did not start")
PORT
)
if ! "$jdk_dir/bin/java" -Djava.library.path="$test_dir" -cp "$test_dir:$JSCH_JAR" \
    dev.northwind.bakassh.TransportRegression "$test_port" test-user "$test_dir/clientkey"; then
    cat "$test_dir/server.log"
    exit 1
fi
if ! "$jdk_dir/bin/java" -Djava.library.path="$test_dir" -cp "$test_dir:$JSCH_JAR" \
    dev.northwind.bakassh.SessionRegression "$test_port" test-user "$test_dir/clientkey"; then
    cat "$test_dir/server.log"
    exit 1
fi
