# Playback incident — Nov 10 2026 (and how to roll back)

## What happened
| Version | Build | Commit | Playback on the TV (Hisense / Sony) |
|---|---|---|---|
| 3.13.2 | 1013 | `1733652` | ✅ **Last version confirmed working** (Cinemax Hits played, 6:52 PM) |
| 3.13.3 | 1014 | `e65ddac` | ❌ **Broke playback** — added a forced search for hidden CEA-608 captions in every TS stream (`Streams.extractors()` passed to `DefaultMediaSourceFactory`) |
| 3.14.0 | 1015 | `13156d9` | ❌ new movie/series controls (still had the 3.13.3 change) |
| 3.14.1 | 1016 | `9422614` | ❌ Stream info (TS/HLS) |
| 3.14.2 | 1017 | `0058bd8` | ❌ caption search limited to "translation on" (user had it on) |
| 3.14.3 | 1018 | `5e62f7e` | ❌ legacy subtitle decoding; subtitles turned off on errors |
| 3.14.4 | 1019 | `cf13595` | ? stops previews before full screen, decoder retry, software-decoder fallback, decoder name in errors |
| 3.14.5 | 1020 | `87c33d6` | ? **removes the 3.13.3 caption search** — streams read exactly like 3.13.2 again |
| 3.14.6 | 1021 | (next) | movie errors show the video format (e.g. 3840×2160 · HEVC 10-bit · HDR) and offer "Play in another app" |

| 3.14.7 | 1022 | (next) | no automatic software decoder (it made movies laggy); restarts wait for the TV to release its decoder; "Try software decoder (slow)" button instead |

Test notes: after unplugging the TV, 3.14.4 plays live channels fine. Some movies/episodes fail with
`video/hevc` (hardware decoder refused, software decoder `c2.android.hevc.decoder` too weak) — a per-file format
limit of the TV (likely 4K / 10-bit / HDR / Dolby Vision), not the 3.13.3 bug.

Symptoms: "This device can't decode this stream (picture)" on every channel and "Can't play this title (decoding failed)"
on movies/series — TV only; the phone kept working. After many failed starts the TV's hardware video decoder can stay
stuck: unplug the TV for 30 s.

## How to roll back (without uninstalling)
Android refuses to install an older build number over a newer one, and the in-app updater only offers higher builds.
So a rollback = **the old code, released again with a new (higher) build number**:

```
git checkout -b rollback 1733652          # 3.13.2 code
# in app/build.gradle.kts set appVersion = "3.13.2-r" (or 3.15.0); versionCode is 1000 + CI run number, so it's higher
git checkout main -- .github/workflows/build.yml app/arena.jks.enc   # keep the current release pipeline + signing key
git commit -am "Roll back to 3.13.2 playback" && git push origin rollback:main --force-with-lease
```
GitHub builds it, publishes it as the newest release, and the Updates tile offers it.

Lighter alternative: keep main and only revert the player files to 3.13.2:
`git checkout 1733652 -- app/src/main/java/com/arena/tv/ui/player/` (then fix anything newer that uses them).

Any APK can also be downloaded from the repo's Releases page (each build is kept), but installing an older one
needs uninstalling first (loses settings unless you use Settings → Backup & restore first).

3.14.5/3.14.6 on the TV: movies played but some went "subtitles couldn't be read" → "Using the software decoder" →
very slow and laggy. Cause: the restart after the subtitle error came too fast, the hardware decoder was still busy,
so it fell to the software decoder. Fixed in 3.14.7.
