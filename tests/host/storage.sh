#!/usr/bin/env bash
set -euo pipefail
root_dir=$(cd -- "$(dirname -- "$0")/../.." && pwd)
: "${JSON_JAR:?Set JSON_JAR to android-json-0.0.20131108.vaadin1.jar}"
: "${JSCH_JAR:?Set JSCH_JAR to jsch-0.2.26.jar}"
test_dir=$(mktemp -d)
trap 'rm -rf -- "$test_dir"' EXIT
jdk_dir=${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}
source_dir="$root_dir/app/src/main/java/dev/northwind/bakassh"
"$jdk_dir/bin/javac" -cp "$JSON_JAR:$JSCH_JAR" -d "$test_dir" \
    "$root_dir"/tests/host/storage/android/content/*.java \
    "$root_dir/tests/host/storage/android/widget/Toast.java" \
    "$root_dir/tests/host/android/util/Base64.java" \
    "$root_dir/tests/host/storage/dev/northwind/bakassh/KeystoreSecrets.java" \
    "$source_dir/SecretBox.java" "$source_dir/PasswordProtection.java" \
    "$source_dir/Host.java" "$source_dir/HostStore.java" "$source_dir/KnownHostsStore.java" "$source_dir/HostKeyTrust.java" \
    "$root_dir/tests/host/StorageRegression.java"
"$jdk_dir/bin/java" -cp "$test_dir:$JSON_JAR:$JSCH_JAR" dev.northwind.bakassh.StorageRegression
