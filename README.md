# AA Tweaker (personal fork)

A companion app for customising Android Auto on a rooted phone. This fork no longer changes flags
itself: on current Google Play Services the database tables the original app wrote to
(`FlagOverrides`, `Flags`) no longer exist. Flags are changed with
[AA Experiments](https://github.com/HighwindBR/AA-Experiments) (an LSPosed module); this app is
the reference and tooling around it.

## What it does

- **Flag reference.** About 100 Android Auto flags with a plain-English name, a short description
  of what changes in the car, a risk label (safe / may break / experimental / driving safety) and
  a suggested value. Where the effect is not known, it says so. Searchable and grouped by category.
- **Your real values.** For each flag it shows the built-in default, the value Google's server set
  on your phone (read from Play Services with root), and your own override once you import an
  AA Experiments export (menu > Import).
- **Your own results.** Mark each flag as works / no visible effect / broke something, with a note.
  The Android Auto version is stored with the mark, and the app points out marks made on a
  different version.
- **Preview loop.** One tap (or a Quick Settings tile) restarts Android Auto and starts its
  developer head unit server, ready for the Desktop Head Unit. See [PREVIEW.md](PREVIEW.md).
- **Messenger relay.** Re-posts Facebook Messenger notifications in a form Android Auto accepts and
  routes replies back through Messenger.
- **Diagnostics.** A read-only report of versions, root, and the Play Services flag database.

The app has no network permission. Nothing leaves the phone.

## Requirements

- Root (Magisk or similar) for restart, head unit server and reading Google's flag values.
- AA Experiments with an LSPosed-compatible framework to actually change flags.

## Build

JDK 17 or newer and the Android SDK (platform 35).

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/
./gradlew testDebugUnitTest
```

Release builds are signed with the debug key unless `local.properties` contains `STORELOCATION`,
`STOREPASSWORD` and `KEYPASSWORD`.

## Credits and license

GPL-2.0, see [LICENSE](LICENSE). Forked from
[AA AIO Tweaker](https://github.com/shmykelsa/AA-Tweaker) by shmykelsa, itself based on
AA Phenotype Patcher by Eselter. Flag names, types and defaults in the reference are facts read
from Android Auto and from the AA Experiments feature catalog; the descriptions are this
project's own wording. The layout of Play Services' stored flag lists was worked out by the
GMS Flags Reborn project.
