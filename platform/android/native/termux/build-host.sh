#!/usr/bin/env bash
# 为桌面开发版编译宿主 Linux x86_64 的 libtermux.so（同一份 termux.c），输出到 platform/desktop 资源目录。
set -euo pipefail
cd "$(dirname "$0")"
JAVA_INC="${JAVA_INC:-$(dirname "$(dirname "$(readlink -f "$(which java)")")")/include}"
OUT=../../../desktop/src/main/resources/natives/linux-x86_64
mkdir -p "$OUT"
gcc -D_GNU_SOURCE -shared -fPIC -O2 -Wall -I"$JAVA_INC" -I"$JAVA_INC/linux" -o "$OUT/libtermux.so" termux.c -lutil
strip "$OUT/libtermux.so"
