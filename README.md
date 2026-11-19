# TV Arcade for Android TV

Seventeen games (including Math Pals, English Pals, My Quiz, Football 3D, Code Pals, Brick Builder and BlockCraft) in one small app. Made for low-end TVs (512 MB RAM, 4 GB storage): the APK is about 1 MB, it has no libraries, and it uses the TV's own WebView.

- **Works offline.** The games are plain HTML pages inside the app (`app/src/main/assets/www`). No server or PHP is needed on the TV.
- **TV remote:** arrows move, OK is the action, Back goes back (Back twice on the menu exits).
- **TV Pad phone controller:** the app connects to the phone app on port 8765 by itself. Join the TV to the phone's hotspot (the phone is then the TV's gateway) and open TV Pad on the phone. On a normal router, open "Phone controller" in the menu and type the phone's IP address.
  Buttons: D-pad/stick = arrows, A = OK, B = Back, X = key 2, Y = key 4, L1 = key 1, R1 = key 3, L2 = brake (Down), R2 = gas (Up), Start = pause/start, Select = 2D/3D.
- **Live updates from berry.gt.tc:** the app keeps itself current without a new APK. Every game (built in or uploaded in `admin.php`) is compared with the site. When a game changed, or a new one was added, or you edited quiz lessons in `quizadmin.php`, the app downloads just that game and then plays it offline. It checks when the app starts, when you come back to it, every few minutes while the menu is open (at most every 3 minutes), and at once with **Update games now** on the Phone controller page. A short message says what was updated; brand-new games show a NEW badge. Games you hide or delete online are removed from the TV.
  The download runs through a hidden browser inside the app, so the host's bot check does not block it. The app uses `https://berry.gt.tc/` by default (change it on the Phone controller page if the arcade is in a subfolder). The site needs `sync.php` from the TV Arcade zip, next to `admin.php`.
  The APK itself is not replaced by this. Only game pages and quiz lessons update live. A change to the app code (menu, controller) still needs a new APK from GitHub.
- **Open online arcade:** the same page can also open your live site inside the app. Back on its menu returns to the offline menu.

## Build the APK
No Android SDK is needed on your computer:
1. Create a new GitHub repository and upload this folder's contents.
2. Open the Actions tab and run **Build APK** (it also runs on every push to `main`).
3. Download `tv-arcade.apk` from the run's artifacts or from Releases.

Or open the folder in Android Studio and run `gradle assembleDebug`.

## Install on the TV
Copy the APK to a USB stick, or use "Send files to TV" / `adb install tv-arcade.apk`, and allow installs from unknown sources. It appears in the Android TV launcher as TV Arcade. It needs Android 5.0+ and Android System WebView (preinstalled on almost all TVs).

## Updating the games
When the PHP games change: `tools/prerender.sh /path/to/tvarcade` (needs PHP on the command line), commit, and rebuild. This also writes `versions.json`, so the app only downloads games that changed since the APK was built. You only need a new APK when you want first-time installs to start fully up to date.
