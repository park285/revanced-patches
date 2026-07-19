#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
metadata="$script_dir/read-receipt-v2-dependency-sha256.properties"
tmp_dir=""

fail() {
    echo "read-receipt dependency integrity: $1" >&2
    exit 1
}

property_from() {
    local file="$1"
    local key="$2"
    awk -F= -v key="$key" '$1 == key { sub(/^[^=]*=/, ""); print }' "$file"
}

validate_metadata() {
    local file="$1"
    local invalid_line duplicate_key
    [[ -f "$file" ]] || return 1
    invalid_line="$(awk '!/^[a-z0-9_]+=[^\r\n]*$/ { print NR; exit }' "$file")"
    [[ -z "$invalid_line" ]] || return 1
    duplicate_key="$(cut -d= -f1 "$file" | LC_ALL=C sort | uniq -d | head -n 1)"
    [[ -z "$duplicate_key" ]] || return 1
    [[ "$(wc -l <"$file")" == "4" ]] || return 1
    [[ "$(property_from "$file" format_version)" == "2" ]] || return 1
    [[ "$(property_from "$file" gson_gson_2_14_0_jar)" =~ ^[0-9a-f]{64}$ ]] || return 1
    [[ "$(property_from "$file" junit_junit_4_13_2_jar)" =~ ^[0-9a-f]{64}$ ]] || return 1
    [[ "$(property_from "$file" kotlinx_coroutines_core_jvm_1_10_2_jar)" =~ ^[0-9a-f]{64}$ ]] || return 1
}

verify_jar() {
    local path="$1"
    local expected_name="$2"
    local expected_hash="$3"
    [[ -f "$path" ]] || return 1
    [[ "$(basename -- "$path")" == "$expected_name" ]] || return 1
    [[ "$(sha256sum -- "$path" | awk '{ print $1 }')" == "$expected_hash" ]]
}

verify_set() {
    local metadata_file="$1"
    local junit_jar="$2"
    local coroutines_jar="$3"
    local gson_jar="$4"
    validate_metadata "$metadata_file" || return 1
    verify_jar "$junit_jar" junit-4.13.2.jar \
        "$(property_from "$metadata_file" junit_junit_4_13_2_jar)" || return 1
    verify_jar "$coroutines_jar" kotlinx-coroutines-core-jvm-1.10.2.jar \
        "$(property_from "$metadata_file" kotlinx_coroutines_core_jvm_1_10_2_jar)" || return 1
    verify_jar "$gson_jar" gson-2.14.0.jar \
        "$(property_from "$metadata_file" gson_gson_2_14_0_jar)"
}

run_self_test() {
    local junit_jar="$1"
    local coroutines_jar="$2"
    local gson_jar="$3"
    local tampered_metadata
    tmp_dir="$(mktemp -d)"
    trap 'rm -rf -- "$tmp_dir"' EXIT
    verify_set "$metadata" "$junit_jar" "$coroutines_jar" "$gson_jar" || \
        fail "self-test rejected exact resolved artifacts"

    mkdir -p "$tmp_dir/junit" "$tmp_dir/coroutines" "$tmp_dir/gson"
    cp -- "$junit_jar" "$tmp_dir/junit/junit-4.13.2.jar"
    cp -- "$coroutines_jar" "$tmp_dir/coroutines/kotlinx-coroutines-core-jvm-1.10.2.jar"
    cp -- "$gson_jar" "$tmp_dir/gson/gson-2.14.0.jar"
    printf 'tamper\n' >>"$tmp_dir/gson/gson-2.14.0.jar"
    if verify_set "$metadata" "$tmp_dir/junit/junit-4.13.2.jar" \
        "$tmp_dir/coroutines/kotlinx-coroutines-core-jvm-1.10.2.jar" \
        "$tmp_dir/gson/gson-2.14.0.jar"; then
        fail "self-test accepted actual runner Gson artifact tamper"
    fi

    tampered_metadata="$tmp_dir/checksums.properties"
    cp -- "$metadata" "$tampered_metadata"
    printf 'junit_junit_4_13_2_jar=%064d\n' 0 >>"$tampered_metadata"
    if verify_set "$tampered_metadata" "$junit_jar" "$coroutines_jar" "$gson_jar"; then
        fail "self-test accepted duplicate checksum metadata"
    fi
    echo "RRV2-DEPENDENCY-INTEGRITY-SELF-TEST passed: exact=3 runnerArtifactTamperReject=1 metadataDuplicateReject=1"
}

if (( $# == 4 )) && [[ "$1" == "--self-test" ]]; then
    run_self_test "$2" "$3" "$4"
    exit 0
fi

if (( $# != 3 )); then
    echo "usage: verify-read-receipt-v2-dependency-integrity.sh [--self-test] <junit-jar> <coroutines-core-jvm-jar> <gson-jar>" >&2
    exit 2
fi

verify_set "$metadata" "$1" "$2" "$3" || fail "resolved dependency checksum mismatch"
echo "RRV2-DEPENDENCY-INTEGRITY passed: components=3 artifacts=3 offlineChecksums=3"
