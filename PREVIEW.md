# Previewing changes with the Desktop Head Unit (DHU)

DHU is Google's Android Auto head unit emulator for the PC. It shows what the car screen would
show, so you can check a flag change without going to the car.

## One-time setup

1. **Install DHU.** Android Studio > SDK Manager > SDK Tools > tick
   *Android Auto Desktop Head Unit Emulator* > Apply. It lands in
   `<sdk>\extras\google\auto\desktop-head-unit.exe`.
2. **Put adb on PATH** (`<sdk>\platform-tools`) and enable USB debugging on the phone.
3. **Enable Android Auto developer mode.** Phone: Settings > search "Android Auto" > scroll to
   *Version* and tap it about ten times > accept.
4. In the same screen: overflow menu (three dots) > *Developer settings* > enable
   *Unknown sources* if you use sideloaded car apps.

## Every session

1. Connect the phone by USB and unlock it.
2. Phone: Android Auto settings > overflow menu > **Start head unit server**.
3. PC, from the repository folder:

   ```powershell
   .\scripts\dhu.ps1
   ```

   The script checks that one device is connected, runs `adb forward tcp:5277 tcp:5277`, and
   starts DHU with the rotary profile in `scripts\dhu-rotary.ini` (800x480, no touch, rotary
   controller). Use `-Touch` for DHU's default touch-screen profile, or `-SdkPath` if the SDK is
   not found automatically.

   If PowerShell refuses to run scripts:
   `powershell -ExecutionPolicy Bypass -File .\scripts\dhu.ps1`

The manual equivalent is:

```powershell
adb forward tcp:5277 tcp:5277
cd <sdk>\extras\google\auto
.\desktop-head-unit.exe -c <repo>\scripts\dhu-rotary.ini
```

## Seeing a flag change

Android Auto reads its flags when it starts, so after changing anything:

1. Close DHU (or disconnect the car).
2. Restart Android Auto: in this app, Menu > Override lab > *Restart Android Auto*, or
   `adb shell am force-stop com.google.android.projection.gearhead`.
3. Start the head unit server again (step 2 above) and relaunch DHU.

## Rotary controls in DHU

With `controller = true` DHU takes keyboard input in place of a knob: arrow keys move, Enter
selects, Backspace goes back. Type `help` in the DHU console window for the full list of keys
and commands on your DHU version, including the rotate-left/right keys.

## Limits

- DHU is not your car. Screen size, density and the head unit's declared capabilities decide
  which layout Android Auto picks, so match `resolution` and `dpi` in the ini to the real screen
  as closely as you can, and confirm anything important in the car.
- The profile values are a starting point for a 7" 800x480 screen, not measured from an Audi unit.
