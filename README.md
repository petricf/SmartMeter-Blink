# SmartMeter Blink

A small Android app that controls the optical button of a
**Landis+Gyr/EYKON E320** smart meter using the phone's torch or screen light —
no physical buttons, no PIN guessing. Comes with a preset profile for the
E320 meter and profile import/export.

> **Note:** This project currently targets the **Landis+Gyr E320** meter.
> It is designed so it can be expanded to other meters later (predefined
> profiles are JSON loaded from `app/src/main/assets/meter_profiles.json`).

## Documentation

- [Users guide (English)](docs/UsersGuide.html) — [PDF](docs/pdf/UsersGuide.pdf)
- [Benutzerhandbuch (Deutsch)](docs/Benutzerhandbuch.html) — [PDF](docs/pdf/Benutzerhandbuch.pdf)
- [Developer guide](docs/DevelopersGuide.html) — [PDF](docs/pdf/DevelopersGuide.pdf)

## Downloads

Download the release APK from the [Releases](https://github.com/petricf/SmartMeter-Blink/releases) page.

## Build

```sh
./gradlew :app:assembleRelease
```

The signed release APK is written to
`app/build/outputs/apk/release/SmartMeter-Blink.apk`. Release signing uses a
local keystore, see the [developer guide](docs/DevelopersGuide.html) for details.

## Features

- Predefined meter profiles loaded from `app/src/main/assets/meter_profiles.json`
  (JSON import/export compatible)
- Multiple saved profiles per meter model
- Torch and screen flash light sources
- English and German UI
- Quick actions: toggle the optical info interface (`inF`) and PIN protection (`Pin`)
- Import / export profiles as JSON (format 1)

## Legal notice

This app and its source code may only be used on meters you own or are
authorized to operate. Using or modifying the app or its source code to obtain
a meter PIN by brute force, or to otherwise gain unauthorized access to a
meter, is **not permitted**. See the user guides for the full notice.

## Acknowledgements

This project was created with the help of
[OpenCode](https://github.com/anomalyco/opencode), an open-source AI coding
assistant.

## License

[MIT](LICENSE) — SmartMeter Blink contributors.

Landis+Gyr, EYKON and E320 are trademarks of their respective owners. This
project is an independent hobby tool and is not affiliated with the
manufacturer.