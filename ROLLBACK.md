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

After 3.14.5/3.14.6 live was also "super laggy": in 3.14.4–3.14.6 a channel that fell back to the software decoder
kept the software decoder for every following channel (fixed in 3.14.7). 3.14.8 shows the decoder in Stream info
and a "⚠ Software decoder" badge, and translation waits for live caption lines to settle (less CPU).

3.15.0 (build after 1023): ONE shared live player for preview, mini player and full screen (Streams.shared) —
opening full screen continues the preview's stream (no reconnect/stutter), Back keeps the little screen playing.
CC button cycles subtitles; "Subtitle options" button opens the panel. If this causes playback trouble,
the previous version is 3.14.8 (build 1023), which played fine on the TV.

3.15.1: hidden-caption search is back ONLY as an opt-in (Settings → Playback → "Look for hidden captions", off by
default). If the picture fails while it's on, TuTu turns it off for that channel and retries. Notes are small, above
the button bar. Known good: 3.14.8 (build 1023), 3.15.0 (build 1024).

3.16.0: FFmpeg audio decoders added (org.jellyfin.media3:media3-ffmpeg-decoder 1.5.0+1, extension mode ON = TV decoders
first) for channels with no sound (likely MP2/AC-3 audio); Live TV reopens on the last watched channel.
If sound gets worse (e.g. soundbar Dolby), revert the extension mode / dependency. Previous: 3.15.1 (build 1025).

3.17.0: hold-OK menu on channels (favorite / hide channel / hide category) with Undo; Settings → Playlists → Hidden channels (Unhide / Unhide all); Choose categories has Live TV / Movies / Series tabs. Previous: 3.16.0 (build 1026).

- 3.17.1 = build 1028 (hidden channels list in Settings needs the parental PIN).
- 3.17.2 = build 1029 (holding OK no longer clicks "Add to favorites" in the channel menu).
