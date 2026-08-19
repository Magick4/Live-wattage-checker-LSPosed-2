# Live Wattage Checker (LSPosed module)

A small **LSPosed (Xposed)** module that shows the **live charging wattage** right in the
status bar, next to the clock, e.g. `⚡6.45W`.

It hooks `com.android.systemui` and inserts a label after the status bar clock.
The power is computed as `voltage × current`, read from the battery's
`power_supply` sysfs nodes (`/sys/class/power_supply/battery/{voltage_now,current_now}`),
with fallbacks to `BatteryManager` properties and the battery status broadcast for
devices/ROMs where sysfs is not readable from SystemUI.

- Works on AOSP / near-stock ROMs (Android 12–14). The clock class used is
  `com.android.systemui.statusbar.policy.Clock`.
- Requires a **rooted** phone with **Magisk + LSPosed (Zygisk)**.
- The label is only shown while the battery status reports `Charging`
  (blank otherwise). It refreshes every 1.5 s.

## Getting the APK

The easiest way is the **Build APK** GitHub Actions workflow, which builds both a
debug and a release APK and uploads them as run artifacts. A ready-made workflow
file is included at [`ci/build.yml`](ci/build.yml).

> Note: GitHub only runs workflows that live in `.github/workflows/`. The bot
> that set up this repo cannot write to that path, so the workflow is stored at
> `ci/build.yml`. One-time activation (30 seconds):

1. In the repo, go to the **Actions** tab → **New workflow** → **set up a workflow yourself**.
2. Replace the generated file's content with the content of `ci/build.yml`, then **Commit changes**.

After that, every push to `main` builds the APKs automatically, and you can also
run **Actions → Build APK → Run workflow** on demand:

1. Open the **Actions** tab of this repository.
2. Select **Build APK** → **Run workflow** → **Run workflow**.
3. When the job finishes, open the run and download the
   **`live-wattage-debug`** (or **`live-wattage-release`**) artifact.
4. Extract the `.apk` from the downloaded zip and install it on the phone.

You can also build it yourself:

```sh
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Installing on the phone

1. **Root**: the phone must be rooted (e.g. Magisk).
2. **LSPosed**: install the **LSPosed (Zygisk)** module via Magisk and reboot.
   (The module needs the Xposed API ≥ 93, which LSPosed provides.)
3. **Install the APK**: open the downloaded `app-debug.apk` (or `app-release.apk`)
   in a file manager and install it. If Play Protect complains about an unknown
   app, confirm you still want to install it.
4. **Enable the module**: open the **LSPosed manager** app →
   **Modules** → enable **Live Wattage**.
5. **Scope it to SystemUI**: LSPosed should offer **SystemUI** as the
   recommended scope automatically — make sure `com.android.systemui` is ticked
   in the module's scope.
6. **Reboot** the phone.

After rebooting, plug in the charger: you should see `⚡X.XXW` appear next to
the clock in the status bar. It disappears when the phone is unplugged or the
battery is full.

## Verifying / troubleshooting

- Open **LSPosed manager → Logs**. On boot you should see
  `LiveWattage: hooked SystemUI` and `LiveWattage: label injected after clock`.
- **No label shown**: check the log for
  `LiveWattage: clock class not found` (the ROM uses a custom clock class —
  the module currently only supports the AOSP clock) or
  `no voltage/current source available` (the device blocks battery sysfs reads
  and reports no current through `BatteryManager`).
- The label only appears **while charging** and when the phone reports a
  non-zero current. If the charging current is reported as 0 (some vendor
  drivers), the label stays blank — nothing the module can do about that.

## License

GPL-3.0 — see [LICENSE](LICENSE).
