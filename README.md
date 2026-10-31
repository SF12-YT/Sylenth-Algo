# Sylenth AI — Android prototype (v0.2.0)

An offline-first Android prototype for building a paired Sylenth1 audio/FXP library and generating an estimated `.fxp` preset from a target audio clip.

## Main workflow

1. Tap **Add training audio** and **Add training preset** to select a matching render and `.fxp` separately, or tap **Import ZIP training pack** to import a ZIP containing mixed audio and preset files.
2. The ZIP importer identifies Sylenth1 FXPs by their VST/Sylenth1 structure and validates them with the preset parser. It recognises common audio file extensions and audio signatures, then passes each paired audio/preset combination into the same analysis and local training pipeline.
3. Files with matching filename stems are paired automatically. If the ZIP contains exactly one valid FXP, all supported audio files in that archive are associated with it, allowing several recordings of one preset. Ambiguous files are reported rather than guessed.
4. Tap **Input audio** to choose the sound you want to recreate.
5. Tap **Export Sylenth preset** and choose where to save the generated `SylenthAI_Match.fxp`.

Training files and the index stay in the app's private storage. The app requests no network, account, microphone, or broad storage permissions.

## Algorithm selected for version 0.2

This version uses a local **weighted k-nearest-neighbour (kNN) preset regressor** rather than a large neural network. That is a deliberate choice for a small, incrementally growing dataset and an Android-only workflow:

- Decode WAV and Android-supported compressed audio formats to PCM.
- Produce a compact audio fingerprint from normalised log-mel spectral statistics, MFCC statistics, temporal envelope/spectral descriptors, and stereo width.
- Standardise feature differences against the local training library.
- Score each unique preset using its closest one-to-three recordings, so multiple examples of the same preset help without creating duplicate preset records.
- Use up to the three closest unique presets as neighbours, while allowing multiple recordings of the same preset to improve its distance estimate.
- Start from the closest real Sylenth1 preset. Continuous parameters are interpolated only when the neighbours share the same chunk version and the same discrete synth topology (oscillator waveforms, filter modes, effect switches, modulation destinations, and other discrete fields). Otherwise the closest real preset is kept intact. Frequency/rate-like fields use log-domain interpolation.
- Write a new `.fxp` by preserving the valid Sylenth1 binary container and updating its preset name and estimated parameter values.

This is a practical retrieval/regression baseline, not a guarantee of exact inverse synthesis. Its 0–100 match indicator is a distance-derived ranking aid, not a calibrated probability or objective confidence score. Different Sylenth1 patches can produce very similar recordings; a target containing other instruments, heavy mastering, external effects or a different processing chain will reduce match quality. The app does not host Sylenth1, so it cannot render a candidate patch for closed-loop audio optimisation. That would require a desktop Sylenth1 render/validation stage or an authorised synthesis engine.

## Important data guidance

- Use audio exported directly from Sylenth1, ideally without extra DAW effects, compression, limiting or normalisation.
- Keep the audio and preset paired correctly. A wrong pair teaches the model the wrong relationship.
- Multiple audio examples can share one FXP. This is particularly useful when the same preset appears in multiple melodies or cutoff-automation contexts.
- A standardised test MIDI across contributors will improve consistency.
- The target audio should be as isolated as possible. A full mastered mix is not a reliable source for recovering one synth patch.

## ZIP training-pack import (v0.2.0)

- The Android file picker accepts ZIP files without requiring broad storage access.
- The importer extracts only candidate `.fxp` and audio files into a private temporary directory; unrelated entries are skipped. Classification uses filename extensions plus file signatures for FXP, WAV, AIFF, FLAC, Ogg, MP3, AAC/ADTS and MP4-family audio.
- ZIP path traversal and absolute-path entries are rejected. Import limits are 3,000 entries, 200 MB per extracted entry, 750 MB total uncompressed content, and 1 GB for the selected archive.
- FXP files are parsed and validated before they are considered presets. Audio candidates are decoded by the same Android audio decoder used for ordinary training imports.
- Matching is conservative: identical filename stems are paired first (with same-folder disambiguation); where there is only one valid FXP, all audio files may be paired with it. With multiple presets and no unique filename match, files remain unmatched and are reported.
- Temporary extracted files and the copied ZIP are deleted after import. Only the analysed training audio, deduplicated FXP presets, and feature index remain in the app's private library.

## FXP handling

The parser currently supports chunk-based Sylenth1 single-preset files (`FPCh`, plugin id `syl1`) and the known internal data versions 2011, 2202 and 2211. It deliberately rejects unknown layouts instead of writing potentially corrupted presets. Generated files inherit the binary version and discrete configuration of the closest compatible example. The format map is based on a public binary template; the parser/writer has been smoke-tested against synthetic fixtures for old and new layouts, but has not yet been validated against a genuine user-exported Sylenth1 FXP. Before relying on generated files, import a real FXP from the Sylenth1 version you use and verify that it loads in Sylenth1.

## Current limitations

- Version 0.2 uses whole-clip spectral statistics. It does not identify or remove an external DAW automation lane. A manually automated Filter Control movement in the target audio can therefore influence the estimated preset parameters; multiple examples of the same preset help, but do not mathematically separate automation from the patch.
- It does not run Sylenth1 on Android and cannot render a candidate FXP for iterative listening-based optimisation.
- Use short, isolated synth renders where possible. Very long files are capped at 180 seconds, and Android must support the selected compressed audio codec.

The Sylenth1 format map used for the parameter offsets is based on the public binary template by Per Ivar Nerseth:
https://github.com/perivar/AudioVSTToolbox/blob/master/PresetConverter/Assorted/FXPSylenth1Preset.bt

Official Sylenth1 user manual:
https://www.lennardigital.com/files/Sylenth1Manual.pdf

Synthesiser parameter estimation background:
https://pixl.cs.princeton.edu/pubs/Yang_2023_WBS/index.php

## Build

Open this folder in Android Studio, or use the included GitHub Actions workflow. The workflow installs Java 17, Android SDK and Gradle 8.10.2, then runs:

```bash
gradle --no-daemon :app:assembleDebug
```

The debug APK is produced at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Minimum Android version: Android 8.0 (API 26). Compile/target SDK: 35.

## Persistent Android release signing (v0.2.1)

The release workflow signs every APK with the same private keystore restored from GitHub Actions secrets. Configure these repository secrets before running the workflow:

- `SYLENTH_KEYSTORE_BASE64`
- `SYLENTH_KEYSTORE_PASSWORD`
- `SYLENTH_KEY_ALIAS`
- `SYLENTH_KEY_PASSWORD`

The workflow creates `app/sylenth-release.jks` temporarily, builds `:app:assembleRelease`, uploads `app-release.apk`, and removes the temporary keystore. The keystore must never be committed to the repository. Keep a secure offline backup of it: losing the signing key prevents Android from accepting future APKs as updates to this installation.

The package ID remains `com.sf12.sylenthai`. Future updates must keep that ID and the same signing key, and must increase `versionCode` in `app/build.gradle`. This version is `0.2.1` / versionCode `3`.

Note: an already-installed debug APK may have been signed with a temporary GitHub runner debug key. A release APK signed with this persistent key cannot update an app signed with a different key. That transition may require one final uninstall/reinstall; after the first install of this persistent-key release, subsequent signed releases can update in place and preserve app-private data.
\n\n## v0.2.2 build workflow fix\n\nThis version removes `android-actions/setup-android@v3`, which can attempt to install the retired Android SDK package `tools`. It uses the Android SDK already present on GitHub-hosted Ubuntu runners, locates `sdkmanager`, and installs only `platform-tools`, `platforms;android-35`, and `build-tools;35.0.0`. The signing secrets and release-signing flow are unchanged.\n