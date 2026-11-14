# Contributing

Contributions to original Workbench code are accepted under AGPL-3.0-only with
the reasonable attribution term in ATTRIBUTION.md. Submit only code you have
the right to contribute and identify third-party sources and licenses.

Keep interfaces in API modules and implementations in implementation modules.
The host modules (`core`, `ui`, `app-shared`, Linux) must not depend on plugins.
Generic image modules may depend on the host; product/domain integrations
contribute through extension points and live in their own repositories.

Run `./gradlew verifyWorkbenchIsolation test` with JDK 21. Android builds also
need Android SDK 36 and Build Tools 35.0.0. See VALIDATION.md for verified tasks.
