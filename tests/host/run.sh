#!/usr/bin/env bash
set -euo pipefail
root_dir=$(cd -- "$(dirname -- "$0")/../.." && pwd)
test_dir=$(mktemp -d)
trap 'rm -rf -- "$test_dir"' EXIT
jdk_dir=${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}
cpp_dir="$root_dir/app/src/main/cpp"
gcc -std=c11 -shared -fPIC -O2 -Wall -Wno-maybe-uninitialized \
    -I"$jdk_dir/include" -I"$jdk_dir/include/linux" -I"$root_dir/tests/host" \
    -I"$cpp_dir/libvterm/include" -I"$cpp_dir/libvterm/src" \
    "$cpp_dir/libvterm_jni.c" "$cpp_dir"/libvterm/src/*.c \
    -o "$test_dir/libbakasshterm.so"
"$jdk_dir/bin/javac" -d "$test_dir" "$root_dir/app/src/main/java/dev/northwind/bakassh/NativeTerm.java" \
    "$root_dir/app/src/main/java/dev/northwind/bakassh/TerminalSelection.java" \
    "$root_dir/app/src/main/java/dev/northwind/bakassh/TerminalZoom.java" \
    "$root_dir/app/src/main/java/dev/northwind/bakassh/TerminalGeometry.java" \
    "$root_dir/app/src/main/java/dev/northwind/bakassh/PendingResize.java" \
    "$root_dir/app/src/main/java/dev/northwind/bakassh/KeyLayout.java" \
    "$root_dir/app/src/main/java/dev/northwind/bakassh/TerminalKeys.java" \
    "$root_dir/tests/host/NativeRegression.java" "$root_dir/tests/host/SelectionRegression.java" \
    "$root_dir/tests/host/ZoomRegression.java" "$root_dir/tests/host/InteractionRegression.java" \
    "$root_dir/tests/host/KeyLayoutRegression.java"
"$jdk_dir/bin/java" -Djava.library.path="$test_dir" -cp "$test_dir" dev.northwind.bakassh.NativeRegression
"$jdk_dir/bin/java" -Djava.library.path="$test_dir" -cp "$test_dir" dev.northwind.bakassh.SelectionRegression
"$jdk_dir/bin/java" -Djava.library.path="$test_dir" -cp "$test_dir" dev.northwind.bakassh.ZoomRegression
"$jdk_dir/bin/java" -Djava.library.path="$test_dir" -cp "$test_dir" dev.northwind.bakassh.InteractionRegression
"$jdk_dir/bin/java" -cp "$test_dir" dev.northwind.bakassh.KeyLayoutRegression
