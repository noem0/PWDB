# PornhwaReader — Komikku extension

Standalone English-language Komikku source for [pornhwareader.com](https://pornhwareader.com).

## Implementation

- Uses the current Keiyoushi `KeiSource` extension API (`libVersion = 1.6`).
- Reads catalogue cards only from links whose paths match a series URL; series covers are kept separate from chapter reader images.
- Walks the series chapter catalogue using the site's observed `?page=N` pagination and actual chapter-row links.
- Extracts the complete ordered reader page array from the Next.js RSC flight stream. Page URLs are taken directly from the chapter payload; no media URLs are synthesized and no fixed page-count limit is applied.
- Detects numbered and UUID chapter links. Edition labels are retained when the site exposes them in chapter rows.
- Builds search filters from the live browse form's actual select and checkbox controls. Multi-value controls are rendered as real checkbox groups; values are submitted using the form's discovered field names and action.

## Build

The extension module is kept standalone in this repository. CI checks it out into a sparse working copy of the upstream Keiyoushi extensions-source build, then builds only this module:

```bash
./gradlew :src:en:pornhwareader:assembleRelease
```

See [the build workflow](.github/workflows/build.yml). A release is only published when a `v*` tag is pushed after a successful APK build and validation.

## Verification notes

The independent reference implementation used for the chapter catalogue and reader extraction is documented in [manga-downloader's PornhwaReader grabber](https://github.com/elboletaire/manga-downloader/blob/e4ff1389e48c92bd890cd0731b85185f507eecc1/grabber/pornhwareader.go). It reports server-rendered chapter rows, `?page=N` chapter pagination, numbered/UUID chapter identifiers, and a `pages` array in the Next.js RSC payload. The live site could not be fetched from this environment, so form-driven catalogue filters and edition-switch links still require live runtime verification; the parser deliberately avoids inventing endpoints or query parameters.

This source is intended for adults and is marked NSFW in its extension metadata.
