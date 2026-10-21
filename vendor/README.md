# Hanvon Ink SDK artifact

`maven/` contains the independently built `dev.hanvon.ink:ink-sdk:0.2.0-rc1`
AAR, sources, checksums and Maven/Gradle metadata. Source owner: sibling
`hanvon-ink-sdk` repository, `feature/reusable-native-ink`, based on `v0.1.1`.
SDK source commit: `872df5a01320f56e6b9e88e0dc08e13f0d38b985`, tag `v0.2.0-rc1`.
This experimental artifact does not claim final device acceptance.

Rebuild in that repository with JDK 17 and Android SDK 35:

    ./gradlew :ink-sdk:publishReleasePublicationToLocalArtifactsRepository

Copy the selected immutable version directory from `artifacts/maven/` here.
Increment the artifact version for subsequent integration releases. Ink Notes
consumes only this repository's artifact; no SDK source checkout is required.
Do not replace a release artifact with different bytes under the same version.

SDK license: [MIT](HANVON-INK-SDK-LICENSE). See the source revision in the release-candidate handoff.
