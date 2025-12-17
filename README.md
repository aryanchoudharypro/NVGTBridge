# NVGT Bridge

NVGT Bridge is an Android Accessibility Service designed to seamlessly bridge the gap between TalkBack (or other screen readers) and audio games that require direct touch interaction.

It automatically disables "Explore by Touch" when you are inside a supported game, allowing you to use game-specific gestures (like swipes and taps) instantly. Crucially, it remains context-aware: if a keyboard, volume panel, or system notification appears, standard screen reader gestures are immediately restored so you are never stuck.

## For Users

### How to Install & Setup

Since this is a system utility, it does not appear in your app drawer. You must configure it through Android Settings.

1. **Install the App:** Download and install the NVGT Bridge APK.
2. **Enable the Service:**
* Go to **Settings > Accessibility**.
* Find **NVGT Bridge** in the list of downloaded services.
* Turn the switch **ON**.


3. **Select Your Games:**
* Stay on the NVGT Bridge page in Accessibility settings.
* Tap on **Settings.
* You will see a list of all your installed apps.
* Find your audio games (e.g., endless runner, constant motion) and toggle the switch to **ON**.



### How it Works

* **Automatic Mode:** When you open a game you have enabled, the Bridge automatically lets you touch the screen directly. You don't need to suspend TalkBack manually.
* **Safety Features:**
* If you type on a keyboard, TalkBack works normally.
* If the volume panel or notification shade appears, TalkBack works normally.
* If you lock the screen or leave the app, the service should reset automatically.



## For Developers

If you are developing an audio game or an app that requires direct touch input, you can add **Native Support** for NVGT Bridge. This allows your app to work automatically without requiring the user to manually enable it in the NVGT Bridge settings list.

### How to Add Native Support

You simply need to add a specific `<meta-data>` tag to your `AndroidManifest.xml` file. You can place this tag inside either the `<application>` block or a specific `<activity>` block.

Copy and paste this line into your manifest:

```xml
<meta-data
	android:name="org.nvgt.capability.DIRECT_TOUCH"
	android:value="true" />

```

## Known Issues & Planned Features

* **Native Dialog Detection:** I am currently working on a feature to automatically detect native alert dialogs (popups) within a game. The goal is to temporarily disable Direct Touch when a dialog appears so users can navigate it with standard swipes.
