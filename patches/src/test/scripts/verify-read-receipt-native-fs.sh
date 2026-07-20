#!/usr/bin/env bash
set -Eeuo pipefail

trap 'status=$?; echo "read-receipt native verification failed: line=$LINENO status=$status" >&2; exit "$status"' ERR

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source_file="$(cd -- "$script_dir/../../main/cpp/readreceipt" && pwd)/read_receipt_native_fs.cpp"

resolve_ndk() {
    local candidate
    for candidate in \
        "${ANDROID_NDK_HOME:-}" \
        "${ANDROID_NDK_ROOT:-}" \
        "${ANDROID_HOME:-$HOME/Android/Sdk}/ndk/27.2.12479018" \
        "$HOME/Android/Sdk/ndk/27.2.12479018"; do
        if [[ -n "$candidate" && -x "$candidate/toolchains/llvm/prebuilt/linux-x86_64/bin/clang++" ]]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done
    return 1
}

ndk="$(resolve_ndk)" || {
    echo "read-receipt native verification: NDK 27.2 not found" >&2
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

if grep -Eq '(^|[^[:alnum:]_])(link|linkat|unlink)[[:space:]]*\(' "$source_file"; then
    echo "read-receipt native verification: forbidden hardlink/path cleanup syscall" >&2
    exit 1
fi
grep -Fq 'SYS_renameat2' "$source_file"
grep -Fq 'RENAME_NOREPLACE' "$source_file"
grep -Fq 'publish_errno_result' "$source_file"
grep -Fq 'status.st_nlink == 1' "$source_file"
if [[ "$(grep -c 'syscall(SYS_renameat2' "$source_file")" -ne 1 ]]; then
    echo "read-receipt native verification: no-replace rename syscall is not exact" >&2
    exit 1
fi
if [[ "$(grep -c 'static_assert(publish_errno_result' "$source_file")" -ne 8 ]]; then
    echo "read-receipt native verification: publish errno mapping assertions are not exact" >&2
    exit 1
fi
if [[ "$(grep -c 'if (unlinkat' "$source_file")" -ne 1 ]]; then
    echo "read-receipt native verification: exact temp unlink is not bounded" >&2
    exit 1
fi
grep -Fq 'valid_temp_basename(basename)' "$source_file"
grep -Fq 'unlinked_status.st_nlink == 0' "$source_file"

compile_and_verify() {
    local abi="$1"
    local compiler="$2"
    local expected_machine="$3"
    local output="$tmp_dir/$abi/libreadreceiptfs.so"
    local actual_exports="$tmp_dir/$abi-exports.txt"
    mkdir -p -- "$(dirname -- "$output")"

    "$toolchain/$compiler" \
        -std=c++17 \
        -fPIC \
        -fvisibility=hidden \
        -Wall -Wextra -Werror \
        -Wconversion -Wsign-conversion \
        -shared \
        -Wl,--no-undefined \
        -Wl,-z,defs \
        -Wl,-z,max-page-size=16384 \
        -o "$output" \
        "$source_file"

    "$readelf_tool" -h "$output" | grep -F "Machine:                           $expected_machine" >/dev/null
    local load_count=0
    while read -r alignment; do
        load_count=$((load_count + 1))
        if (( alignment < 0x4000 )); then
            echo "read-receipt native verification: $abi PT_LOAD alignment below 0x4000" >&2
            return 1
        fi
    done < <("$readelf_tool" -lW "$output" | awk '$1 == "LOAD" { print $NF }')
    if (( load_count == 0 )); then
        echo "read-receipt native verification: $abi has no PT_LOAD segments" >&2
        return 1
    fi
    "$nm_tool" -D --defined-only --format=posix "$output" \
        | awk '{print $1}' \
        | LC_ALL=C sort >"$actual_exports"
    cmp --silent "$expected_exports" "$actual_exports"
}

compile_and_verify "arm64-v8a" "aarch64-linux-android28-clang++" "AArch64"
compile_and_verify "armeabi-v7a" "armv7a-linux-androideabi28-clang++" "ARM"

echo "read-receipt native verification passed: api=28 page=16384 abis=arm64-v8a,armeabi-v7a exports=4"
