# Watchparty Android shell

Native WebView host for the `stremio-web` bundle in this repository.

- Package / applicationId: `com.imluckii.watchparty` (installs alongside Stremio)
- Min SDK 26, target SDK 35, compile SDK 36
- The bundle is built by webpack, trimmed by `scripts/prepare-android.mjs`, and shipped
  uncompressed in `assets/web/`.

## Why the UI is served from a loopback port

`LocalServer` serves the bundle from `http://127.0.0.1:11471/app/` and proxies every other
path to the Stremio engine on `127.0.0.1:11470`. The page therefore shares an origin with
the engine. That is required: the engine answers cross-origin requests only for strem.io
hosts, so a WebView-asset-loader origin (`appassets.androidplatform.net`) gets
`No 'Access-Control-Allow-Origin'` on `/settings`, `/device-info`, HLS playlists and
subtitles. One origin also keeps the page in a secure context (workers, wasm, storage) and
out of Chrome's private-network rules.

`prepare-android.mjs` repoints the core's built-in engine default (two literals in the Rust
wasm, plus one in `stremio-video`) from `:11470` to `:11471`, so the app starts already
pointed at the proxy and no profile migration is needed. Both are same-length string
replacements; the script fails the build if the wasm literal disappears.

## Engine absence

When nothing answers on `127.0.0.1:11470` the shell shows a native setup screen
(`Open Stremio` / `Check again` / `Browse without playback`) over the still-loading UI, and
re-probes on resume — so launching Stremio once and coming back clears it. The web
bundle's own amber "streaming server not available" banner is suppressed in the shell build.

`AndroidManifest.xml` declares a `<queries>` block for `com.stremio.one`; without it
Android 11+ package visibility makes `getLaunchIntentForPackage()` return null and the
setup screen falls through to the Play Store.

## Build locally

```bash
pnpm install --frozen-lockfile
SERVICE_WORKER_DISABLED=true WATCHPARTY_STREAMING_SERVER_URL=http://127.0.0.1:11471/ pnpm build
node scripts/prepare-android.mjs
cd android && ./gradlew assembleRelease
# -> android/app/build/outputs/apk/release/app-release.apk
```

The release build is signed with the committed keystore at
`android/keystore/watchparty-release.jks` (alias `watchparty`, password `watchparty`) so
side-loaded updates keep installing over each other. Override with
`WP_KEYSTORE_PASSWORD`, `WP_KEY_ALIAS`, `WP_KEY_PASSWORD`.

## CI

`.github/workflows/android.yml` builds the bundle and the APK on every push to
`development`/`main`, uploads the artifact, and replaces the `android-latest` release.

## Upstream

This repository is a fork of `Stremio/stremio-web` (GPL-2.0). The shell lives under
`android/`, `scripts/prepare-android.mjs` and `.github/workflows/android.yml`. Three
upstream files carry small Android-only changes, each guarded or explained in place:
`src/common/CONSTANTS.js` (build-time default streaming server), `src/App/SearchParamsHandler.js`
(no toast when the URL is the app's own origin) and `src/routes/Board/Board.js`
(suppress the web streaming-server banner in the shell).
