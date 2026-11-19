#!/usr/bin/env python3
"""Read-only catalog check with a real X-T30 II. Grant USB permission first."""
import re
import subprocess
import time
import xml.etree.ElementTree as ET

PACKAGE = "dev.malordin.fuji.usb"


def adb(*args, check=True):
    return subprocess.run(["adb", *args], capture_output=True, text=True, check=check).stdout


def main():
    adb("shell", "am", "start", "-n", PACKAGE + "/dev.malordin.fuji.MainActivity")
    adb("shell", "uiautomator", "dump", "/sdcard/fujirelay-check.xml")
    root = ET.fromstring(adb("shell", "cat", "/sdcard/fujirelay-check.xml"))
    more = next(node for node in root.iter("node") if node.get("content-desc") == "Дополнительно")
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", more.get("bounds")))
    adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))
    adb("shell", "uiautomator", "dump", "/sdcard/fujirelay-check.xml")
    root = ET.fromstring(adb("shell", "cat", "/sdcard/fujirelay-check.xml"))
    for node in root.iter("node"):
        if node.get("text") == "Обновить каталог":
            assert node.get("enabled") == "true", "Wait for the current transfer to finish"
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
            adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))
            break
    else:
        raise AssertionError("Open the app, scroll to the top, and allow USB access first")
    time.sleep(1)
    deadline = time.monotonic() + 240
    while time.monotonic() < deadline:
        log = adb("shell", "run-as", PACKAGE, "cat", "files/last-import.log", check=False)
        assert "Ошибка:" not in log, log
        if "Проверка завершена" in log:
            assert "FUJIFILM X-T30 II" in log and "Найдено JPEG:" in log, log
            print(log)
            print("PASS: real camera catalog; no photos imported or uploaded")
            return
        time.sleep(1)
    raise AssertionError("Timed out: check the camera and USB permission dialog")


if __name__ == "__main__":
    main()
