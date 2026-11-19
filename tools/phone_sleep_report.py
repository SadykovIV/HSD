"""Read the handset's power state during projection, without waking it or changing settings."""
import argparse
import json
import re
import subprocess


def capture(shell):
    power = shell("dumpsys", "power")
    display = shell("dumpsys", "display")
    trust = shell("dumpsys", "trust")
    windows = shell("dumpsys", "window", "displays")

    def field(key):
        value = re.search(r"(?m)^\s*" + key + r"=([^\s]+)", power)
        return value[1] if value else None

    panel = re.search(r"mBaseDisplayInfo=DisplayInfo\{[^\n]*?displayId 0\b[^\n]*?\bstate (\w+)", display)
    current_lock = re.search(r"(?m)^.*\(current\):[^\n]*?\bdeviceLocked=(true|false|1|0)\b", trust)
    listing = re.search(r"(?m)^\s*Wake Locks: size=(\d+)[^\n]*\n((?:[ \t]+[^\n]+\n)*)", power)
    screen_locks = None
    hsd_locks = None
    if listing and (int(listing[1]) == 0 or listing[2].strip()):
        screen_locks = []
        uid_line = shell("cmd", "package", "list", "packages", "-U", "com.hsd.universal")
        package_uid = re.search(r"(?m)^package:com\.hsd\.universal\s+uid:(\d+)\s*$", uid_line)
        if package_uid:
            hsd_locks = []
        for line in listing[2].splitlines():
            owner = re.search(r"\buid=(\d+)\b", line)
            if hsd_locks is not None and (owner and owner[1] == package_uid[1] or
                                          re.search(r"'(?:HSD[-:]|com\.hsd\.universal\b)", line, re.I)):
                hsd_locks.append(line.strip())
            lock = re.search(r"\b(SCREEN_(?:BRIGHT|DIM)_WAKE_LOCK|FULL_WAKE_LOCK)\s+'([^']+)'", line)
            if lock:
                group = re.search(r"powerGroupId=(-?\d+)", line)
                uid = re.search(r"\buid=(\d+)", line)
                screen_locks.append(dict(level=lock[1], tag=lock[2][:140], group=int(group[1]) if group else None,
                                         uid=int(uid[1]) if uid else None, disabled="DISABLED" in line))
    # Report only package names of currently holding windows. An empty list and an
    # unrecognised OEM dump are different outcomes.
    holders = []
    found = False
    target = None
    for line in windows.splitlines():
        heading = re.search(r"^\s*Display: mDisplayId=(\d+)\b", line)
        if heading:
            target = int(heading[1])
        holder = re.match(r"\s*mHold(?:ing)?Screen(?:On)?Window=(.*)", line)
        if holder:
            found = True
            if holder[1].strip() != "null":
                package = re.search(r"\bu\d+\s+([a-zA-Z0-9_.]+)(?:/|[ }])", holder[1])
                holders.append(dict(display=target, package=package[1] if package else None))

    clocks = {}
    section = re.search(r"(?m)^(?:Power|Display) Group User Activity:\s*\n((?:[^\n]+\n)*)", power)
    if section:
        group = None
        for line in section[1].splitlines():
            group_id = re.match(r"\s*(?:groupId:|displayGroupId=)\s*(\d+)\s*$", line)
            if group_id:
                group = clocks.setdefault(group_id[1], {})
            item = re.match(r"\s*(lastUserActivityTime(?:NoChangeLights)?|mWakeLockSummary|mWakefulness|mScreenOffTimeout)=(\S+)", line)
            if group is not None and item:
                group[item[1]] = item[2]
    return {
        "model": shell("getprop", "ro.product.model"),
        "phone_display": panel[1] if panel else None,
        "user_locked": current_lock[1] in ("true", "1") if current_lock else None,
        "screen_timeout_ms": field("mScreenOffTimeoutSetting"),
        "lock_after_ms": shell("settings", "get", "secure", "lock_screen_lock_after_timeout"),
        "stay_on_mask": field("mStayOnWhilePluggedInSetting"),
        "plug_type": field("mPlugType"),
        "effective_stay_on": field("mStayOn"),
        "wakefulness": field("mWakefulness"),
        "display_suspend_blocker": field("mHoldingDisplaySuspendBlocker"),
        "hsd_wake_locks": hsd_locks,
        "screen_locks": screen_locks, "holding_windows": holders if found else None, "activity_by_group": clocks,
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", help="Select the connected phone when ADB lists more than one device.")
    args = parser.parse_args()
    adb = [args.adb] + (["-s", args.serial] if args.serial else [])
    def shell(*parts):
        return subprocess.check_output([*adb, "shell", *parts], text=True, timeout=15).strip()
    try:
        print(json.dumps(capture(shell), ensure_ascii=False, indent=2))
    except (OSError, subprocess.SubprocessError) as error:
        parser.exit(1, f"Cannot read Android power diagnostics over ADB: {error}\n")
