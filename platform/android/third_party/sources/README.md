# Corresponding source for Android executables

These archives accompany the independent native programs in `../../jniLibs`.
PRoot 5.1.107.100, libandroid-shmem 0.7 and talloc 2.5.0 source archives are
verified against the SHA-256 values in Termux's build recipes. The complete
Termux package build framework, recipes and patches are retained at commit
`f1dadd27342d1d0b05f7523261997fae58eb1b6b` (the PRoot 5.1.107.100 update).
Extract that archive and follow its README/build-package.sh instructions for
the Android architecture in question. Relevant packages are `proot`,
`libandroid-shmem` and `libtalloc`. PRoot depends on the latter two libraries.

The locally built PTY JNI source and build instructions are already in
`../../native/termux/`; Termux terminal-emulator source is in the repository's `core/terminal`.
No resource-key library is included in this repository.

Archive URLs, checksums and the pinned recipe commit are in MANIFEST.json.
See ../licenses/ and ../NOTICE-proot.md. Published copies of this repository
include these corresponding sources alongside the binaries, rather than
requiring a request to the original application maintainer.
