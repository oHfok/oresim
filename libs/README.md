# Bundled build dependency

`baritone-api-fabric-1.18.0.jar` is the unmodified official
[Baritone](https://github.com/cabaletta/baritone) v1.18.0 release for
Minecraft 26.1 ([release page](https://github.com/cabaletta/baritone/releases/tag/v1.18.0),
SHA-1 `3220ff86a50254488bfd86529ba7e4ac8048a36f`, matching the release's
`checksums.txt`).

It is only used to **compile** the optional auto-mining integration
(`compileOnly` in `build.gradle`) and is not included in the mod jar; players
install Baritone themselves. Baritone is licensed under the
[GNU LGPL v3](https://github.com/cabaletta/baritone/blob/master/LICENSE).
