# Hanvon Ink SDK artifact

This repository vendors `dev.hanvon.ink:ink-sdk:0.2.1`, including AAR, source JAR,
checksums and Maven/Gradle metadata. Source:
[Hanvon Ink SDK v0.2.1](https://github.com/jdkruzr/hanvon-ink-sdk/tree/v0.2.1).
Source commit: `8c941c7897c7a481927cfc183e3e5c307364feb8`.
AAR SHA256: `229715de52ee85d7091259e73326d8162af4a9c3d8efd224d09c737da1f51aa5`.
The [SDK release](https://github.com/jdkruzr/hanvon-ink-sdk/releases/tag/v0.2.1)
provides the same immutable Maven bundle and independent build instructions.

SDK 0.2.1 is licensed under [Apache-2.0](HANVON-INK-SDK-LICENSE), with its
[notice](HANVON-INK-SDK-NOTICE). Ink Notes' own code remains MIT-licensed.
No proprietary firmware binaries are bundled. All SDK Kotlin sources match the
source JAR; vendored files match the SDK publication. Compiled drawing code matches
the physically accepted 0.2.0 implementation; 0.2.1 changes licensing and packaging.

Build the SDK with JDK 17 and Android SDK 35:

    ./gradlew :ink-sdk:publishReleasePublicationToLocalArtifactsRepository

Copy its selected immutable version directory from `artifacts/maven/` into this
repository's `vendor/maven/`. Never overwrite a published version with different
bytes. Ink Notes resolves only this local Maven dependency; a sibling SDK checkout,
GitHub Packages credentials or Maven Central publication is unnecessary.

Physical acceptance is on the M10 / Penstar eNote Pro, Android 14. Capability
probing allows other compatible Hanvon firmware, but does not validate its behavior.
See [integration and compatibility](../docs/native-ink.md).
