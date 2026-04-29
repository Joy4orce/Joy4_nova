# Joy4_nova

Nova Video Player monorepo with audio file playback support — consolidates the
three audio-enabled forks plus the FileCoreLibrary upstream into one repo.
Subdirectory names match the legacy `repo` manifest paths so
`Video/settings.gradle`'s `settingsDir.parentFile`-relative project lookups
still resolve.

## Layout

```
nova/
├── AVP/              (subtree of Joy4orce/nova-audio-AVP, history preserved)
├── MediaLib/         (subtree of Joy4orce/nova-audio-MediaLib, history preserved)
├── Video/            (subtree of Joy4orce/nova-audio-Video, history preserved)
├── FileCoreLibrary/  (submodule of nova-video-player/aos-FileCoreLibrary @ v6.4)
├── apk/              (gitignored — build artifacts)
└── prebuilt-ffmpeg/  (gitignored — fetch separately for local builds)
```

CI assembles the rest of the build tree (`nova/native/*`, `nova/Makefile`)
on demand; those paths are gitignored.

## Cloning

```sh
git clone --recurse-submodules https://github.com/Joy4orce/Joy4_nova.git
```

If you already cloned without `--recurse-submodules`:

```sh
git submodule update --init --recursive
```

## Prebuilt ffmpeg

The `nova/prebuilt-ffmpeg/` directory is gitignored. Fetch it from the
[upstream nova-video-player release artifacts](https://github.com/nova-video-player/aos-AVP/releases)
or copy from a previous local checkout.

## History

This repo was consolidated on 2026-04-29 from:
- [Joy4orce/nova-audio-AVP](https://github.com/Joy4orce/nova-audio-AVP) (archived)
- [Joy4orce/nova-audio-MediaLib](https://github.com/Joy4orce/nova-audio-MediaLib) (archived)
- [Joy4orce/nova-audio-Video](https://github.com/Joy4orce/nova-audio-Video) (archived)

Each subtree preserves the full commit history of its source.
