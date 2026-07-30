<p align="center">
  <img src="docs/assets/icon.png" width="104" alt="Anilili app icon" />
</p>

<h1 align="center">Anilili</h1>

<p align="center">
  <strong>Anime, beautifully native on Android.</strong><br />
  Fast multi-source playback, offline downloads, AniList/MAL sync, and a remote-first TV experience.
</p>

<p align="center">
  <a href="https://github.com/kompoti121/Anilili/releases/latest/download/Anilili.apk">
    <img src="https://img.shields.io/badge/Download_APK-8979F2?style=for-the-badge&logo=android&logoColor=white" alt="Download Anilili APK" />
  </a>
  <a href="https://t.me/anililiapk">
    <img src="https://img.shields.io/badge/Join_Telegram-229ED9?style=for-the-badge&logo=telegram&logoColor=white" alt="Join the Anilili Telegram group" />
  </a>
</p>

<p align="center">
  <a href="https://github.com/kompoti121/Anilili/releases/latest"><img src="https://img.shields.io/github/v/release/kompoti121/Anilili?style=flat-square&label=release&color=8979F2" alt="Latest GitHub release" /></a>
  <a href="https://github.com/kompoti121/Anilili/releases"><img src="https://img.shields.io/github/downloads/kompoti121/Anilili/total?style=flat-square&label=downloads&color=8979F2" alt="Total GitHub downloads" /></a>
  <img src="https://img.shields.io/badge/Android-5.1%2B-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android 5.1 and newer" />
  <img src="https://img.shields.io/badge/Kotlin-Native_UI-7F52FF?style=flat-square&logo=kotlin&logoColor=white" alt="Built with Kotlin" />
  <img src="https://img.shields.io/badge/Android_TV-Fire_TV-FF9900?style=flat-square&logo=amazonfiretv&logoColor=white" alt="Android TV and Fire TV" />
</p>

<p align="center">
  <a href="https://kompoti121.github.io/Anilili/">Website</a> ·
  <a href="https://github.com/kompoti121/Anilili/releases">All releases</a> ·
  <a href="https://github.com/kompoti121/Anilili/issues">Report a problem</a>
</p>

---

## Made for every Android screen

Anilili is a native Kotlin and Jetpack Compose anime client for phones, tablets, Android TV,
and Fire TV. It combines discovery, streaming, downloads, list management, and progress sync
in one adaptive interface—without wrapping the whole experience in a website.

| Watch your way | Your anime, in sync | Built for the couch |
| --- | --- | --- |
| Native Media3 playback, multiple sources, subtitles, skip intro/outro, quality selection, PiP, casting controls, and offline episodes. | Sign in with AniList or MyAnimeList, update your list from the player, resume episodes, and optionally sync watched progress. | Large-screen layouts, D-pad navigation, visible focus states, TV-safe spacing, and player controls designed for a remote. |

## Mobile experience

<table>
  <tr>
    <th width="33%">Home &amp; continue watching</th>
    <th width="33%">Discover &amp; filter</th>
    <th width="33%">Details &amp; airing info</th>
  </tr>
  <tr>
    <td><a href="showcase/mobile/home.webp"><img src="showcase/mobile/home.webp" width="100%" alt="Anilili mobile home with featured anime and continue watching" /></a></td>
    <td><a href="showcase/mobile/discover.webp"><img src="showcase/mobile/discover.webp" width="100%" alt="Anilili mobile anime discovery and search screen" /></a></td>
    <td><a href="showcase/mobile/details.webp"><img src="showcase/mobile/details.webp" width="100%" alt="Anilili mobile anime details screen" /></a></td>
  </tr>
</table>

<p align="center">
  <strong>Native player with episodes, captions, casting, fullscreen, and list controls</strong><br /><br />
  <a href="showcase/mobile/player.webp"><img src="showcase/mobile/player.webp" width="92%" alt="Anilili native mobile video player controls" /></a>
</p>

## TV experience

<table>
  <tr>
    <th width="50%">A cinematic, remote-friendly home</th>
    <th width="50%">Details and episodes side by side</th>
  </tr>
  <tr>
    <td><a href="showcase/tv/home.webp"><img src="showcase/tv/home.webp" width="100%" alt="Anilili home screen on Android TV" /></a></td>
    <td><a href="showcase/tv/details.webp"><img src="showcase/tv/details.webp" width="100%" alt="Anilili anime details and episode list on Android TV" /></a></td>
  </tr>
</table>

<p align="center">
  <strong>Fullscreen playback designed around D-pad controls</strong><br /><br />
  <a href="showcase/tv/player.webp"><img src="showcase/tv/player.webp" width="92%" alt="Anilili fullscreen Android TV player controls" /></a>
</p>

## Highlights

- **Multiple streaming sources:** automatic discovery and fallback across Miruro and
  Anivexa-backed providers, with server priority and sub/dub language filtering.
- **Native video player:** Media3 playback with quality selection, audio tracks, captions,
  caption styling and timing, playback speed, content scaling, gestures, PiP, and casting controls.
- **Offline viewing:** background episode downloads, external subtitles, progress notifications,
  offline playback, and optional MP4 export to `Downloads/Anilili/`.
- **AniList and MyAnimeList:** optional login, list views, Add to My List from the player,
  continue watching, resume positions, and watched-episode progress sync.
- **Smart episode controls:** autoplay, skip intro/outro, episode drawer, next/previous navigation,
  and subtitle delay that can persist across a season.
- **Phone and TV interfaces:** adaptive Compose layouts for touch, D-pad, Android TV, Fire TV,
  tablets, landscape playback, and older low-memory devices.
- **Useful diagnostics:** shareable support reports containing app, playback, device, network,
  and recent diagnostic information when troubleshooting is needed.

<details>
  <summary><strong>Streaming providers</strong></summary>

Anilili can resolve episodes from Miruro, Senshi, AniBD, AniKoto, KickAssAnime, AllAnime,
AnimeKai, ReAnime, AniZone, AnimeGG, AniNeko, 2DHive, RareAnimes, and additional compatible
sources. Availability can vary by title, language, region, and provider uptime.

</details>

## Install

1. Download the **Universal APK** using the button below.
2. Open the APK and allow installation from your browser or file manager if Android asks.
3. Launch Anilili. Signing in to AniList or MyAnimeList is optional.

<p align="center">
  <a href="https://github.com/kompoti121/Anilili/releases/latest/download/Anilili.apk">
    <img src="https://img.shields.io/badge/Download_Universal_APK-8979F2?style=for-the-badge&logo=android&logoColor=white" alt="Download universal APK" />
  </a>
</p>

For a TV or Fire TV device, download the APK with a TV browser/Downloader app or transfer it
from another device, then open it with the system package installer.

| APK | Best for |
| --- | --- |
| [Anilili.apk](https://github.com/kompoti121/Anilili/releases/latest/download/Anilili.apk) | **Recommended.** Universal build for phones, tablets, Android TV, and Fire TV. |
| [Anilili_arm64-v8a.apk](https://github.com/kompoti121/Anilili/releases/latest/download/Anilili_arm64-v8a.apk) | Most modern 64-bit phones and TV devices. |
| [Anilili_armeabi-v7a.apk](https://github.com/kompoti121/Anilili/releases/latest/download/Anilili_armeabi-v7a.apk) | Older 32-bit Android and Fire OS devices. |

**Compatibility:** Android 5.1 / Fire OS 5 (API 22) or newer.

## Community and support

The Telegram group is the quickest place for release announcements, help, feedback, and
provider-status discussions. For reproducible bugs, you can also open a GitHub issue and attach
the app's shared diagnostics ZIP when appropriate.

<p align="center">
  <a href="https://t.me/anililiapk">
    <img src="https://img.shields.io/badge/Join_the_Telegram_Group-229ED9?style=for-the-badge&logo=telegram&logoColor=white" alt="Join the Telegram group" />
  </a>
  <a href="https://github.com/kompoti121/Anilili/issues/new">
    <img src="https://img.shields.io/badge/Report_a_Bug-24292F?style=for-the-badge&logo=github&logoColor=white" alt="Report a bug on GitHub" />
  </a>
</p>

## Build from source

<details>
  <summary>Developer setup and release notes</summary>

### Requirements

- JDK 17
- Android Studio or Android SDK API 36
- Gradle 8.13 when building without the included wrapper

### Build

```bash
./gradlew assembleDebug
```

On Windows:

```powershell
gradlew.bat assembleDebug
```

Debug and release builds produce a universal APK plus `arm64-v8a` and `armeabi-v7a` variants.
The in-app updater depends on these release asset names:

- `Anilili.apk`
- `Anilili_arm64-v8a.apk`
- `Anilili_armeabi-v7a.apk`

The underscore before the ABI is intentional. It keeps the universal APK first in GitHub's asset
ordering so older app versions do not accidentally download an incompatible architecture build.

### Project map

| Path | Purpose |
| --- | --- |
| `app/src/main/java/com/miruronative/data` | Models, provider catalog, storage, and sync |
| `app/src/main/java/com/miruronative/data/remote` | AniList, MAL, Miruro, and provider clients |
| `app/src/main/java/com/miruronative/ui` | Compose screens and player UI |
| `showcase/mobile` and `showcase/tv` | Optimized screenshots used in this README |
| `docs/` | GitHub Pages website and protocol notes |
| `docs/PIPE_PROTOCOL.md` | Miruro pipe protocol documentation |

</details>

## Disclaimer

Anilili is a personal and educational project. It is not affiliated with AniList,
MyAnimeList, or any streaming provider. The app hosts no video content; streams are resolved
from third-party providers at playback time. Availability and legality can vary by region, and
users are responsible for following the laws and terms that apply to them.
