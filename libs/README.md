# Bundled build dependencies

Unmodified official [Baritone](https://github.com/cabaletta/baritone) API
jars, one per Minecraft version, each matching its release's `checksums.txt`:

| Jar | Minecraft | SHA-1 |
| --- | --- | --- |
| `baritone-api-fabric-1.18.0.jar` ([v1.18.0](https://github.com/cabaletta/baritone/releases/tag/v1.18.0)) | 26.1 | `3220ff86a50254488bfd86529ba7e4ac8048a36f` |
| `baritone-api-fabric-1.19.0.jar` ([v1.19.0](https://github.com/cabaletta/baritone/releases/tag/v1.19.0)) | 26.2 | `c415031f0a292f52daa3ae55e86f720e12b3552b` |
| `baritone-api-fabric-1.20.0.jar` ([v1.20.0](https://github.com/cabaletta/baritone/releases/tag/v1.20.0)) | 26.3 | `8c0de04b371a466f7c6808119ea18f5554653fb6` |

They are only used to **compile** the optional auto-mining integration
(`compileOnly` in `build.gradle`, chosen by the `-Pmc` version) and are not
included in the mod jar; players install Baritone themselves. Baritone is
licensed under the [GNU LGPL v3](https://github.com/cabaletta/baritone/blob/master/LICENSE).
