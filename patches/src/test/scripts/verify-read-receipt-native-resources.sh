#!/usr/bin/env bash
set -Eeuo pipefail

trap 'status=$?; echo "read-receipt native resource verification failed: line=$LINENO status=$status" >&2; exit "$status"' ERR

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
patches_dir="$(cd -- "$script_dir/../../.." && pwd)"
resource_root="$patches_dir/src/main/resources/kakaotalk/readreceipt-native"
source_verifier="$script_dir/verify-read-receipt-native-fs.sh"

"$source_verifier"

resolve_ndk() {
    local candidate
    for candidate in \
        "${ANDROID_NDK_HOME:-}" \
        "${ANDROID_NDK_ROOT:-}" \
        "${ANDROID_HOME:-$HOME/Android/Sdk}/ndk/27.2.12479018" \
        "$HOME/Android/Sdk/ndk/27.2.12479018"; do
        if [[ -n "$candidate" && -x "$candidate/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" ]]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done
    return 1
}

ndk="$(resolve_ndk)" || {
    echo "read-receipt native resource verification: NDK 27.2 not found" >&2
    exit 1
}
toolchain="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
readelf_tool="$toolchain/llvm-readelf"
nm_tool="$toolchain/llvm-nm"
tmp_dir="$(mktemp -d)"
trap 'rm -rf -- "$tmp_dir"' EXIT
expected_exports="$tmp_dir/expected-exports.txt"
printf '%s\n' \
    'Java_app_revanced_extension_kakaotalk_chatlog_readreceipt_ReadReceiptNativeFs_nativeFsyncDirectory' \
    'Java_app_revanced_extension_kakaotalk_chatlog_readreceipt_ReadReceiptNativeFs_nativeFsyncPath' \
    'Java_app_revanced_extension_kakaotalk_chatlog_readreceipt_ReadReceiptNativeFs_nativePublish' \
    'Java_app_revanced_extension_kakaotalk_chatlog_readreceipt_ReadReceiptNativeFs_nativeUnlinkIfExactInode' \
    >"$expected_exports"

verify_one() {
    local abi="$1"
    local machine="$2"
    local library="$resource_root/$abi/libreadreceiptfs.so"
    local actual_exports="$tmp_dir/$abi-exports.txt"
    [[ -f "$library" ]]
    "$readelf_tool" -h "$library" | grep -Fq "Machine:                           $machine"
    local unexpected_dependency
    unexpected_dependency="$(
        "$readelf_tool" -d "$library" \
            | awk '/\(NEEDED\)/ { value=$NF; gsub(/^\[|\]$/, "", value); print value }' \
            | LC_ALL=C sort -u \
            | awk '$0 != "libc.so" && $0 != "libdl.so" && $0 != "libm.so" { print }'
    )"
    if [[ -n "$unexpected_dependency" ]]; then
        echo "read-receipt native resource verification: unexpected $abi dependency" >&2
        return 1
    fi
    while read -r alignment; do
        (( alignment >= 0x4000 )) || {
            echo "read-receipt native resource verification: $abi PT_LOAD alignment below 0x4000" >&2
            return 1
        }
    done < <("$readelf_tool" -lW "$library" | awk '$1 == "LOAD" { print $NF }')
    "$nm_tool" -D --defined-only --format=posix "$library" \
        | awk '{print $1}' \
        | LC_ALL=C sort >"$actual_exports"
    cmp --silent "$expected_exports" "$actual_exports"
}

verify_one "arm64-v8a" "AArch64"
verify_one "armeabi-v7a" "ARM"
echo "RRV2-NATIVE-001 packaged native resources passed: api=28 page=16384 abis=2 exports=4"
