# Validation — 2026-11-14

JDK 21.0.13, Gradle 8.14.3, Android platform 36 and Build Tools 35.0.0.

## Standalone foundation

`./gradlew verifyWorkbenchIsolation test :platform:android:workbench:assembleDebug :platform:desktop:workbench:installDist :platform:desktop:workbench:testClasses --no-daemon`

- 318 unit-test cases, zero failures; one optional sealed-APK interoperability
  test skipped because no external sealed APK fixture was supplied.
- The expanded SDL editor smoke passed 20 checks: documents, undo/redo,
  read-only editing, completion and navigation, search, outline, saved tabs,
  Markdown preview, image preview, image tool assembly, bundled AGPL text and
  an actual pixel-editor window. Run
  `xvfb-run -a ./gradlew :platform:desktop:workbench:editorSmokeTest -PeditorHostNative=true`.
  Its scenario clock uses elapsed time and disables vsync to reproduce fast
  headless rendering. The host-native preparation runs after installDist, so
  rebuilding the distribution cannot overwrite the JNI adaptation before testing.
- Android identity labels read the consuming product's AppIdentity. Android
  platform unit tests and the standalone debug APK build passed after this change.
- APK package `io.github.workbench.debug`, version `0.3.0-debug`. The APK contains
  the AGPL text, attribution and generic image skills; binary/source checks found
  no RW game classes/resources, private resource-key library or original OAuth
  configuration. Android installation/runtime was not tested on a physical device.

## Consuming product

Rustwright's 958 business/product unit-test cases passed. The actual Git
submodule checkout passed dependency/source-ownership checks, desktop assembly
and Android product assembly. Its editor `initest` completed 471 steps with
100 checks passed and zero failures. Product tests verify original service
identity/signature configuration, free-model prefix, resource-protection paths
and explicit configuration overrides.

## Publication inputs

Native corresponding-source archives were verified against the fixed Termux
build recipes; complete recipes/build framework and third-party notices are
retained under `platform/android/third_party/`. Public-source audit excludes
private deployment keys, signing material and RW dependencies. Original Arc
and Termux source formatting/headers remain intact.
