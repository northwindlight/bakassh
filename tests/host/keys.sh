#!/usr/bin/env bash
set -euo pipefail
root_dir=$(cd -- "$(dirname -- "$0")/../.." && pwd)
: "${JSCH_JAR:?Set JSCH_JAR}"
: "${BC_JAR:?Set BC_JAR to bcprov-jdk18on-1.79.jar}"
test_dir=$(mktemp -d)
trap 'rm -rf -- "$test_dir"' EXIT
for key_type in rsa ecdsa ed25519; do
    ssh-keygen -q -t "$key_type" -N '' -f "$test_dir/$key_type"
done
ssh-keygen -q -t rsa -m PEM -N '' -f "$test_dir/rsa-pem"
ssh-keygen -q -t rsa -m PEM -N ' pass phrase ' -f "$test_dir/rsa-pem-locked"
ssh-keygen -q -t rsa -N ' pass phrase ' -f "$test_dir/rsa-locked"
ssh-keygen -q -t ed25519 -N ' pass phrase ' -f "$test_dir/ed25519-locked"
openssl pkcs8 -topk8 -nocrypt -in "$test_dir/rsa-pem" -out "$test_dir/rsa-pkcs8"
cp "$test_dir/rsa-pem.pub" "$test_dir/rsa-pkcs8.pub"
javac -cp "$JSCH_JAR:$BC_JAR" -d "$test_dir" \
    "$root_dir/app/src/main/java/dev/northwind/bakassh/PrivateKeyImport.java" \
    "$root_dir/tests/host/KeyImportRegression.java"
java -cp "$test_dir:$JSCH_JAR:$BC_JAR" dev.northwind.bakassh.KeyImportRegression "$test_dir"
