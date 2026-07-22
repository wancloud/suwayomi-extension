# Suwayomi Extensions

A source-extension repository for [Suwayomi-Server](https://github.com/Suwayomi/Suwayomi-Server)
(Tachiyomi/Mihon-compatible extensions). Based on the build scaffolding of
[keiyoushi/extensions-source](https://github.com/keiyoushi/extensions-source) (Apache-2.0).

## Extensions

| Extension | Language | Site |
|---|---|---|
| ManHuaGui (漫画柜) | zh | https://www.manhuagui.com |

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
./gradlew :src:zh:manhuagui:assembleRelease
```

The APK is written to `src/zh/manhuagui/build/outputs/apk/release/`.
If no `signingkey.jks` keystore exists at the repository root, the release build is signed with
the debug key, which Suwayomi-Server accepts.

## Serving the repo to Suwayomi

Run `python scripts/create-repo.py` after building to produce a `repo/` directory containing
`index.min.json` and the APKs. Serve it over HTTP (e.g. `python -m http.server -d repo`) and add
the URL of `index.min.json` as an extension repo in Suwayomi's settings
(Settings → Browse → Extension repos).

## Adding another extension

Create `src/<lang>/<name>/` with a `build.gradle.kts` (see `src/zh/manhuagui/build.gradle.kts`
for the DSL), Kotlin sources under `src/`, and launcher icons under `res/`. Gradle discovers the
module automatically — no settings changes needed.

## License

Apache License 2.0 — see [LICENSE](LICENSE). Portions copyright the
[keiyoushi](https://github.com/keiyoushi/extensions-source) contributors and Javier Tomás.
