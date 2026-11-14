# Native Makapix projects

`MakapixCodec.decode(bytes)` imports v10 plain and compact `.mkpx` projects into `LayeredPixelDocument`.
`encode(document, compact = true)` writes the native compact format; `compact = false` writes plain.
There is no JNI or application Rust runtime dependency. CRC32C is a local table implementation compatible with Android API 26.

Compatibility is based on Makapix revision `e2d427f881dd561aae9d6091af7d54ca4bb264a6`:
https://github.com/fabkury/makapix-app
Sources: engine `io.rs`, `buffer.rs`, `util.rs`, codec `mkpx_compact.rs`, and v10 format specification.
The original implementation is Apache-2.0; see `MAKAPIX_LICENSE` and fixture `NOTICE`.

Preserved: every frame and layer, microsecond durations, per-frame active layer, loop mode,
visibility/lock/opacity, all 11 blend modes, straight RGBA including RGB beneath zero alpha,
canvas and off-canvas gutter pixels, palette colors and names, canvas selections and active indices.
Frame/layer opaque IDs are regenerated on export; they are internal allocator IDs, not artwork.
Present all-zero tiles may canonicalize to implicit transparent tiles; this changes storage identity,
not pixels, and the semantic hash is recomputed for the emitted tile census.

The editor budget is stricter than upstream: canvas 8..256, at most 256 frames, 64 layers/frame,
32 palettes, 256 swatches/palette, 64-character editable names, and 4,194,304 aggregate pixels
(including allocated gutter planes). Input/inflated bytes are capped at 32 MiB; dictionary is capped
at 8192 tiles. Every length/run/index is checked before allocation or access. Interruption cancels
CRC, inflate/deflate, frame/layer/tile loops. Array-backed tile keys avoid boxing pixel arrays.

Gutter pixels survive edits and exports; drawing tools edit canvas pixels. Layer merging with gutter
is currently rejected by the editor. Off-canvas selections, META/THMB or unknown ancillary chunks,
unsupported flags/modes and older format versions are explicitly rejected rather than discarded.
CRC corruption and semantic content-hash mismatch are errors (upstream production may only warn on
the latter). Compression uses JVM zlib: valid upstream-compatible bytes, but not guaranteed byte-identical
to miniz_oxide. The plain tile encoder uses smallest RAW/RLE/INDEXED with stable tie-breaking.

Tests use actual upstream-generated fixtures: `src/test/resources/makapix/generate-fixtures.rs` builds
against the unmodified dependency-free engine; `native-compact.mkpx` was compressed by upstream
`mkpx_compact.rs` using miniz_oxide 0.8.9. Fixtures cover all tile codecs, two frames/two layers,
blend/opacity/locks, non-millisecond durations, named swatches, irregular selection, hidden RGB,
and nonempty gutter. Kotlin-exported files have also been loaded by the strict upstream Rust loader
with identical original content hashes. `crosscheck.rs` is the reproducible checker; change its
upstream path to your checkout and link `makapix-engine` plus pinned miniz_oxide 0.8.9.

Run `:modules:pic:imaging:test --tests '*MakapixCodecTest'` for codec regressions; no APK is built.
