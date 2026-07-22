# Suwayomi Extensions

A source-extension repository for [Suwayomi-Server](https://github.com/Suwayomi/Suwayomi-Server)
(Tachiyomi/Mihon-compatible extensions). Based on the build scaffolding of
[keiyoushi/extensions-source](https://github.com/keiyoushi/extensions-source) (Apache-2.0).

## Extensions

| Extension | Language | Site |
|---|---|---|
| ManHuaGui (WanCloud) (漫画柜) | zh | https://www.manhuagui.com |

## Repository layout

```
├── gradle/build-logic/   Gradle convention plugins (extension build, manifest generation, signing)
├── common/               Shared AndroidManifest template and ProGuard rules
├── core/                 Shared runtime code compiled into every extension
├── compiler/             KSP processor that generates the extension/source factory classes
├── lib/                  Small shared libraries (only the ones extensions here actually use)
└── src/<lang>/<name>/    One directory per extension
```

## Building

Requirements: JDK 17+ and the Android SDK (set `sdk.dir` in `local.properties`).

Build a single extension:

```
./gradlew :src:zh:manhuaguiwan:assembleRelease
```

The APK is written to `src/zh/manhuaguiwan/build/outputs/apk/release/`.
If no `signingkey.jks` keystore exists at the repository root, the release build is signed with
the debug key, which Suwayomi-Server accepts.

## Using this repo in Suwayomi

Add this URL as an extension repo in Suwayomi (Settings → Browse → Extension repos):

```
https://raw.githubusercontent.com/wancloud/suwayomi-extension/repo/index.min.json
```

## Publishing updates

Run `python scripts/create-repo.py` after building to regenerate the `repo/` directory
(`index.min.json`, APKs, icons), then push its contents to the `repo` branch. For local testing,
serve it directly with `python -m http.server -d repo` and use
`http://localhost:8000/index.min.json` instead.

## Adding another extension

Create `src/<lang>/<name>/` with a `build.gradle.kts` (see `src/zh/manhuaguiwan/build.gradle.kts`
for the DSL), Kotlin sources under `src/`, and launcher icons under `res/`. Gradle discovers the
module automatically — no settings changes needed.

## License

Apache License 2.0 — see [LICENSE](LICENSE). Portions copyright the
[keiyoushi](https://github.com/keiyoushi/extensions-source) contributors and Javier Tomás.
