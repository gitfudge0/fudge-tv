# Device inventory and HOME recovery

These optional Python 3 tools use an already installed `adb`. Connect only to your own TV using its supported debugging route and accept its authorization prompt. The tools never discover devices, connect to an IP address, enable network ADB, obtain root, or select an Android user automatically.

Run `adb devices` and obtain the exact model and Android SDK API number with `adb -s SERIAL shell getprop ro.product.model` and `adb -s SERIAL shell getprop ro.build.version.sdk`. The SDK API number is different from the Android version name. Check available users with `adb -s SERIAL shell pm list users`. Replace every uppercase example below with your own value; quote values containing spaces.

```sh
python3 tools/backup-tv.py --serial SERIAL --model 'EXACT MODEL' --sdk API_NUMBER --user USER_ID
```

The tool verifies the authorized connection, exact model, SDK, and user before reading anything else. It saves package inventories, disabled packages, all three settings namespaces, device properties, and resolved HOME under a new private `.local/tv-backup-*` directory. A `COMPLETE` file marks a fully finished snapshot. An interrupted snapshot remains partial. This is an inventory, not a backup of application data, DRM licenses, streaming sessions, or the operating system.

**Keep `.local/` out of Git and sharing.** Properties/settings can contain device identifiers, network names, account-related values, or other personal information. Directories are created with private permissions and files with owner-only permissions. Do not attach raw inventories to public issues. Keep a separate private copy before any device change.

## Restore a known HOME component

Use the original stock HOME component recorded in your own `identity.json`. Review it against your TV's own settings; do not copy a component from another model. Start with a read-only identity check:

```sh
python3 tools/restore-home.py --serial SERIAL --model 'EXACT MODEL' --sdk API_NUMBER --user USER_ID --component STOCK_PACKAGE/STOCK_ACTIVITY
```

After reviewing the proposed component, repeat with `--confirm` to enable exactly that component and request it as the default HOME. The tool records the previous HOME and command results privately under `.local/home-restore-*`, then checks actual HOME resolution. It does not uninstall or disable anything, enable entire packages, alter recovery components, reboot, or bulk-restore settings. If the stock package itself was disabled previously, stop and use the original device-specific restoration journal; this generic helper deliberately does not guess how to restore it.

Android or TV-vendor priority rules can prevent the requested default from taking effect. A mismatch stops the script and leaves evidence; it is not reported as success. A successful resolver check still requires testing the physical Home button, Back, Inputs, Settings, voice control, Chromecast, playback, and a manual reboot on the TV. Maintain access to stock Settings and the physical remote throughout.

These public tools do not replace device-specific rollback journals. Do not replay someone else's package states or settings. Restore only changes whose original values and ownership you can establish.

## Finish debugging securely

After testing, use the TV's settings to turn off ADB/debugging and revoke computer authorizations unless you explicitly choose to retain access. Disconnect the session with `adb disconnect SERIAL` for a network device; this disconnect alone does not close the TV's listening service. Never expose an ADB port to the internet. If debugging must remain available, restrict it to your trusted local network and retain the authorization deliberately.

Offline tool checks (no device connection):

```sh
python3 -m unittest discover -s tools -p 'test_tv_tools.py'
python3 tools/backup-tv.py --help
python3 tools/restore-home.py --help
```
