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

Connect the phone by USB, unlock it, and from the repository folder run:

```powershell
.\scripts\dhu.ps1
```

The script does the whole loop:

1. force-stops Android Auto, so it re-reads its flags;
2. starts Android Auto's head unit server (it looks up the service name on your Android Auto
   version, and needs root on the phone);
3. runs `adb forward tcp:5277 tcp:5277`;
4. starts DHU with the rotary profile in `scripts\dhu-rotary.ini` (800x480, no touch).

Options: `-NoRestart` leaves Android Auto running, `-Touch` uses DHU's default touch profile,
`-SdkPath` points at the SDK if it is not found automatically.

If PowerShell refuses to run scripts:
`powershell -ExecutionPolicy Bypass -File .\scripts\dhu.ps1`

If the script cannot find or start the head unit server, start it by hand: phone > Android Auto
settings > three-dot menu > **Start head unit server**.

The same restart is available on the phone: the app's home screen has
**Restart Android Auto + start head unit server**, and there is a Quick Settings tile
("Restart AA for DHU") you can add to the notification shade.

The manual equivalent is:

```powershell
adb shell "su -c 'am force-stop com.google.android.projection.gearhead'"
# start the head unit server on the phone
adb forward tcp:5277 tcp:5277
cd <sdk>\extras\google\auto
.\desktop-head-unit.exe -c <repo>\scripts\dhu-rotary.ini
```

## Seeing a flag change

Android Auto reads its flags when it starts. After changing a flag in AA Experiments, close DHU
and run the script again; it restarts Android Auto for you.

## Rotary controls in DHU

With `controller = true` DHU takes keyboard input in place of a knob: arrow keys move, Enter
selects, Backspace goes back. Type `help` in the DHU console window for the full list of keys
and commands on your DHU version, including the rotate-left/right keys.

## Limits

- DHU is not your car. Screen size, density and the head unit's declared capabilities decide
  which layout Android Auto picks, so match `resolution` and `dpi` in the ini to the real screen
  as closely as you can, and confirm anything important in the car.
- The profile values are a starting point for a 7" 800x480 screen, not measured from an Audi unit.
