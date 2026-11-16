# TV Arcade for Android TV

Eleven games (Block Hero, Snake, Brick Smash, Star Blaster, Sky Hop, 2048, Train Rush, Driving School, TV Racer, Subway Rush, BlockCraft) in one small app. Made for low-end TVs (512 MB RAM, 4 GB storage): the APK is about 1 MB, it has no libraries, and it uses the TV's own WebView.

- **Works offline.** The games are plain HTML pages inside the app (`app/src/main/assets/www`). No server or PHP is needed on the TV.
- **TV remote:** arrows move, OK is the action, Back goes back (Back twice on the menu exits).
- **TV Pad phone controller:** the app connects to the phone app on port 8765 by itself. Join the TV to the phone's hotspot (the phone is then the TV's gateway) and open TV Pad on the phone. On a normal router, open "Phone controller" in the menu and type the phone's IP address.
  Buttons: D-pad/stick = arrows, A = OK, B = Back, X = key 2, Y = key 4, L1 = key 1, R1 = key 3, L2 = brake (Down), R2 = gas (Up), Start = pause/start, Select = 2D/3D.
- **New games from berry.gt.tc:** when you upload a game in `admin.php`, the app downloads it the next time it starts or you open its menu (at most every 10 minutes), or at once with **Update games now** on the Phone controller page. Downloaded games appear at the top of the menu with a NEW badge and then play offline. Games you hide or delete online are removed from the TV. The download runs through a hidden browser inside the app, so the host's bot check does not block it.
  The app uses `https://berry.gt.tc/` by default (change it on the Phone controller page if the arcade is in a subfolder, for example `https://berry.gt.tc/arcade/`). The site needs `sync.php` from the TV Arcade zip, next to `admin.php`.
- **Open online arcade:** the same page can also open your live site inside the app. Back on its menu returns to the offline menu.

## Build the APK
No Android SDK is needed on your computer:
1. Create a new GitHub repository and upload this folder's contents.
2. Open the Actions tab and run **Build APK** (it also runs on every push to `main`).
3. Download `app-release.apk` from the run's artifacts or from Releases.

Or open the folder in Android Studio and run `gradle assembleDebug`.

## Install on the TV
Copy the APK to a USB stick, or use "Send files to TV" / `adb install app-release.apk`, and allow installs from unknown sources. It appears in the Android TV launcher as TV Arcade. It needs Android 5.0+ and Android System WebView (preinstalled on almost all TVs).

## Updating the games
When the PHP games change: `tools/prerender.sh /path/to/tvarcade` (needs PHP on the command line), commit, and rebuild.
