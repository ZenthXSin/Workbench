#!/usr/bin/env bash
# 重新编译 Arc 的 libarc-freetype（FreeType 2.14.3 + Arc JNI 胶水），比上游多带 CFF / PostScript 驱动，
# 以便直接加载 Android 自带的 NotoSansCJK.ttc（CFF 轮廓）。
# JNI 胶水（arc_freetype_FreeType*.cpp/.h）由 jnigen 3.1.2 的 NativeCodeGenerator 从 FreeType.java 生成，已提交在本目录。
# 用法：./build.sh [freetype 源码目录]（缺省自动下载到 build/）
set -euo pipefail
cd "$(dirname "$0")"
HERE=$(pwd)
FT=${1:-$HERE/build/freetype-2.14.3}
if [ ! -d "$FT" ]; then
  mkdir -p build && curl -sSL https://download.savannah.gnu.org/releases/freetype/freetype-2.14.3.tar.gz | tar xz -C build
fi
cat > "$FT/include/freetype/config/ftmodule.h" <<'M'
FT_USE_MODULE( FT_Module_Class, autofit_module_class )
FT_USE_MODULE( FT_Driver_ClassRec, tt_driver_class )
FT_USE_MODULE( FT_Driver_ClassRec, cff_driver_class )
FT_USE_MODULE( FT_Module_Class, psaux_module_class )
FT_USE_MODULE( FT_Module_Class, psnames_module_class )
FT_USE_MODULE( FT_Module_Class, pshinter_module_class )
FT_USE_MODULE( FT_Module_Class, sfnt_module_class )
FT_USE_MODULE( FT_Renderer_Class, ft_smooth_renderer_class )
M
SRC="base/ftsystem.c base/ftinit.c base/ftdebug.c base/ftbase.c base/ftbbox.c base/ftglyph.c base/ftbitmap.c base/ftstroke.c base/ftmm.c
truetype/truetype.c sfnt/sfnt.c cff/cff.c psaux/psaux.c pshinter/pshinter.c psnames/psnames.c smooth/smooth.c autofit/autofit.c gzip/ftgzip.c"
CF="-O2 -fPIC -DFT2_BUILD_LIBRARY -I$FT/include -I$HERE"

build() { # cc cxx out extra-flags...
  local cc=$1 cxx=$2 out=$3; shift 3
  local obj=$(mktemp -d)
  for s in $SRC; do $cc $CF "$@" -c "$FT/src/$s" -o "$obj/$(echo $s | tr / _).o"; done
  $cxx $CF "$@" -c "$HERE/arc_freetype_FreeType.cpp" -o "$obj/glue.o"
  mkdir -p "$(dirname "$out")"
  $cxx -shared "$@" -o "$out" $obj/*.o -static-libstdc++ -Wl,-z,max-page-size=16384
  rm -rf "$obj"
}

NDK="${ANDROID_NDK:-$(ls -d /opt/android-sdk/ndk/* | tail -1)}"
B="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
OUT=$HERE/../../../natives
for pair in arm64-v8a:aarch64-linux-android armeabi-v7a:armv7a-linux-androideabi x86:i686-linux-android x86_64:x86_64-linux-android; do
  abi=${pair%%:*}; tgt=${pair#*:}
  build "$B/clang --target=${tgt}26" "$B/clang++ --target=${tgt}26" "$OUT/android/$abi/libarc-freetype.so"
  "$B/llvm-strip" "$OUT/android/$abi/libarc-freetype.so"
done
# 桌面 Linux x86_64（开发 / 无头验收用）
JI="${JAVA_INC:-$(dirname "$(dirname "$(readlink -f "$(which java)")")")/include}"
build gcc g++ "$OUT/desktop/libarc-freetype64.so" -I"$JI" -I"$JI/linux"
strip "$OUT/desktop/libarc-freetype64.so"
echo done
