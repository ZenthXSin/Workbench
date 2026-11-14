#!/usr/bin/env bash
set -euo pipefail
WORKBENCH_ROOT=$(cd "$(dirname "$0")/.." && pwd)
: "${JAVA_HOME:?Set JAVA_HOME to a JDK 21 installation}"
NATIVE_OUT="${1:-$WORKBENCH_ROOT/platform/desktop/workbench/build/install/workbench/lib}"
test -d "$NATIVE_OUT" || { echo 'Run :platform:desktop:workbench:installDist first.' >&2; exit 1; }
g++ -O2 -shared -fPIC -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
  -I"$WORKBENCH_ROOT/arc/extensions/freetype/native" $(pkg-config --cflags freetype2) \
  "$WORKBENCH_ROOT/arc/extensions/freetype/native/arc_freetype_FreeType.cpp" \
  -o "$NATIVE_OUT/libarc-freetype64.so" $(pkg-config --libs freetype2)
"$JAVA_HOME/bin/jar" --update --file "$NATIVE_OUT/backend-sdl.jar" -C "$NATIVE_OUT" libarc-freetype64.so
rm "$NATIVE_OUT/libarc-freetype64.so"
echo 'Host FreeType JNI built. Run the desktop launcher normally.'
