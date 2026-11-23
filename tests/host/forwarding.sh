#!/usr/bin/env bash
set -euo pipefail
root_dir=$(cd -- "$(dirname -- "$0")/../.." && pwd)
: "${JSCH_JAR:?Set JSCH_JAR to jsch-0.2.26.jar}"
test_dir=$(mktemp -d)
server_pid=
cleanup() {
    if [[ -n "$server_pid" ]]; then kill "$server_pid" 2>/dev/null || true; wait "$server_pid" 2>/dev/null || true; fi
    rm -rf -- "$test_dir"
}
trap cleanup EXIT
javac -cp "$JSCH_JAR" -d "$test_dir" \
    "$root_dir/tests/host/android/util/Log.java" "$root_dir/tests/host/android/util/Base64.java" \
    "$root_dir/app/src/main/java/dev/northwind/bakassh/HostKeyTrust.java" \
    "$root_dir/app/src/main/java/dev/northwind/bakassh/SshTransport.java" \
    "$root_dir/app/src/main/java/dev/northwind/bakassh/SshForwarding.java" \
    "$root_dir/tests/host/TestHostKeys.java" "$root_dir/tests/host/ForwardingRegression.java"
ssh-keygen -q -t rsa -b 2048 -m PEM -N '' -f "$test_dir/hostkey"
ssh-keygen -q -t rsa -b 2048 -m PEM -N '' -f "$test_dir/clientkey"
python3 "$root_dir/tests/host/forwarding_server.py" "$test_dir/hostkey" "$test_dir/clientkey.pub" "$test_dir/port" >"$test_dir/server.log" 2>&1 &
server_pid=$!
for ((i=0;i<50;i++)); do [[ -s "$test_dir/port" ]] && break; sleep .1; done
if ! java -cp "$test_dir:$JSCH_JAR" dev.northwind.bakassh.ForwardingRegression "$(cat "$test_dir/port")" "$test_dir/clientkey" "$test_dir/hostkey.pub"; then
    cat "$test_dir/server.log"
    exit 1
fi
