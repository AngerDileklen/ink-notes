# Hanvon Ink SDK artifact

`maven/` contains the independently built `dev.hanvon.ink:ink-sdk:0.2.0`
AAR, sources, checksums and Maven/Gradle metadata. Source owner: sibling
`hanvon-ink-sdk` repository, branch `feature/reusable-native-ink`.
Source commit: `9d22b831e40d0c23d1f696c8a1803813845294b4`, local tag `v0.2.0`.
AAR SHA256: `b4fe79e6bcc4addcdf2ac33e75698d552f00ba7be75e4a8d0109a1015bccb689`.
All 11 Kotlin source files match the source JAR; every vendored artifact matches
the SDK publication. Library bytecode matches the physically accepted rc3 build.
The earlier tracked rc1 directory is historical; the app resolves only 0.2.0.

M10 physical acceptance covers pressure, editor tools/PDF, marker preview/editing,
lifecycle, all orientations, multiwindow fallback/recovery and stock Notes
coexistence. Other Hanvon devices remain unvalidated; capability probing allows
compatible firmware without a model-name gate. The SDK remains experimental.

Rebuild in that repository with JDK 17 and Android SDK 35:

    ./gradlew :ink-sdk:publishReleasePublicationToLocalArtifactsRepository

Copy the selected immutable version directory from `artifacts/maven/` here.
Increment the artifact version for subsequent integration releases. Ink Notes
consumes only this repository's artifact; no SDK source checkout is required.
Do not replace a release artifact with different bytes under the same version.

SDK license: [MIT](HANVON-INK-SDK-LICENSE). Local release evidence is in the SDK
repository's `docs/release-0.2.0.md` and `artifacts/validation/0.2.0/`.
No public publication or push was performed.
