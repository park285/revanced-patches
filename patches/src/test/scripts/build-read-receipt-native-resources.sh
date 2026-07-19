#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
patches_dir="$(cd -- "$script_dir/../../.." && pwd)"
source_file="$patches_dir/src/main/cpp/readreceipt/read_receipt_native_fs.cpp"
resource_root="$patches_dir/src/main/resources/kakaotalk/readreceipt-native"

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
    echo "read-receipt native resource build: NDK 27.2 not found" >&2
    exit 1
}
toolchain="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"

build_one() {
    local abi="$1"
    local compiler="$2"
    local output="$resource_root/$abi/libreadreceiptfs.so"
    mkdir -p -- "$(dirname -- "$output")"
    SOURCE_DATE_EPOCH=0 "$toolchain/$compiler" \
        -std=c++17 \
        -fPIC \
        -fvisibility=hidden \
        -fno-exceptions \
        -fno-rtti \
        -nostdlib++ \
        -ffile-prefix-map="$patches_dir"=. \
        -Wall -Wextra -Werror \
        -Wconversion -Wsign-conversion \
        -shared \
        -Wl,--no-undefined \
        -Wl,-z,defs \
        -Wl,-z,max-page-size=16384 \
        -Wl,--build-id=none \
        -o "$output" \
        "$source_file"
}

build_one "arm64-v8a" "aarch64-linux-android28-clang++"
build_one "armeabi-v7a" "armv7a-linux-androideabi28-clang++"
sha256sum \
    "$resource_root/arm64-v8a/libreadreceiptfs.so" \
    "$resource_root/armeabi-v7a/libreadreceiptfs.so"
