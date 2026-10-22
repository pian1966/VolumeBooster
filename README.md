# Volume Booster

Android app that boosts audio output up to 600% using the platform `LoudnessEnhancer` effect.

- Boost slider from 100% to 600% (600% ≈ +15.6 dB) with presets
- Runs as a foreground service with a notification and Stop action
- Quick Settings tile to toggle the boost
- System media volume slider
- Warning before enabling more than 200%

Works on Android 7.0 (API 24) and newer. It attaches to the global output mix (session 0) and to every audio session that players announce. Some devices refuse the global effect; the boost then only applies to apps that announce their session.

## Build

Requires JDK 17+ and the Android SDK (API 36).

```bash
./gradlew assembleRelease
```

The APK is written to `app/build/outputs/apk/release/app-release.apk`.

## Warning

High boost levels can distort audio, damage speakers, and harm your hearing. Start with a low media volume.
