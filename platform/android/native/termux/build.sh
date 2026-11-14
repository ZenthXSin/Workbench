#!/usr/bin/env bash
# 用 NDK 预编译 libtermux.so（PTY 子进程 JNI，源码来自 Termux terminal-emulator，Apache-2.0）到 ../../jniLibs。
# 用法：ANDROID_NDK=/opt/android-sdk/ndk/<ver> ./build.sh
set -euo pipefail
cd "$(dirname "$0")"
NDK="${ANDROID_NDK:-$(ls -d /opt/android-sdk/ndk/* | tail -1)}"
BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
API=26
build() { # abi target
  mkdir -p "../../jniLibs/$1"
  "$BIN/clang" --target="$2$API" -shared -fPIC -O2 -Wall -Wl,-z,max-page-size=16384 -o "../../jniLibs/$1/libtermux.so" termux.c
  "$BIN/llvm-strip" "../../jniLibs/$1/libtermux.so"
}
build arm64-v8a aarch64-linux-android
build armeabi-v7a armv7a-linux-androideabi
build x86_64 x86_64-linux-android
