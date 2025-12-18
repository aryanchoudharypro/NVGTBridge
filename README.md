# NVGT Bridge

NVGT Bridge is an Android Accessibility Service designed to seamlessly bridge the gap between TalkBack (or other screen readers) and audio games that require direct touch interaction.

It automatically disables "Explore by Touch" when you are inside a supported game, allowing you to use game-specific gestures (like swipes and taps) instantly. Crucially, it remains context-aware: if a keyboard or a dialog appears, standard screen reader gestures are immediately restored so you are never stuck.


## For Users

### How to Install & Setup

Since this is a system utility, it does not appear in your app drawer. You must configure it through Android Settings.

#### 1. Install the APK

Download the NVGT Bridge APK.

> **Note on Installation:** Android's "Play Protect" may flag this app because it uses high-level accessibility permissions. Furthermore, standard package installers might trigger "Restricted Settings" on newer Android versions. To ensure the service can be enabled, it is highly recommended to use a **Session-Based Installer** (such as the "App Manager" app from F-Droid or GitHub). These installers use the modern Android `PackageInstaller` session API, which helps bypass some security restrictions.

#### 2. Allow Restricted Settings (If Necessary)

On Android 13 and newer, you might see an error saying **"Restricted setting: For your security, this setting is currently unavailable"** when trying to turn on the service. To fix this:

1. Open your phone **Settings**.
2. Go to **Apps** (or **See all apps**).
3. Find **NVGT Bridge** in the list and tap it.
4. In the top right corner, tap the more options button.
5. Tap **Allow restricted settings**.
6. Confirm with your PIN or fingerprint. You can now enable the service in the Accessibility menu.

#### 3. Enable the Service

1. Go to **Settings > Accessibility**.
2. Find **NVGT Bridge** in the list of downloaded services.
3. Turn the switch **ON**.

#### 4. Select Your Games

1. Stay on the NVGT Bridge page in Accessibility settings.
2. Tap on **Settings**.
3. Find your audio games in the list eg constant motion or endless runner and toggle the switch to **ON**.

### How it Works

* **Automatic Mode:** When you open a game you have enabled, the Bridge automatically lets you touch the screen directly. You don't need to suspend TalkBack manually.
* **Safety Features:** TalkBack resumes normal behavior if you open the keyboard, pull down notifications, or lock the screen.

---

## For Developers

If you are developing an audio game, you can add **Native Support** so users don't have to manually find your app in the NVGT Bridge apps list and enable it.

### How to Add Native Support

Add the following `<meta-data>` tag to your `AndroidManifest.xml` inside either the `<application>` or `<activity>` block:

```xml
<meta-data
	android:name="org.nvgt.capability.DIRECT_TOUCH"
	android:value="true" />

```

---

## Known Issues & Planned Features

Nothing! Feel free to open issues if you want something to be added!
