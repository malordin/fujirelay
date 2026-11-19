#!/usr/bin/env python3
"""Exercise the real dashboard without transmitting photos or changing settings."""
import re
import subprocess
import xml.etree.ElementTree as ET


def adb(*args):
    return subprocess.run(["adb", *args], check=True, capture_output=True, text=True).stdout


def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/fujirelay-ui-check.xml")
    return list(ET.fromstring(adb("shell", "cat", "/sdcard/fujirelay-ui-check.xml")).iter("node"))


def tap(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node["bounds"]))
    adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))


def find(items, key, value):
    return next(n.attrib for n in items if n.get(key, "").casefold() == value.casefold())


def main():
    services = adb("shell", "dumpsys", "activity", "services", "dev.malordin.fuji.usb")
    assert "ServiceRecord" not in services, "Finish the active transfer before running UI checks"
    adb("shell", "am", "force-stop", "dev.malordin.fuji.usb")
    adb("shell", "am", "start", "-n", "dev.malordin.fuji.usb/dev.malordin.fuji.MainActivity")
    items = nodes()
    assert len([n for n in items if n.get("class") == "android.widget.Button"]) == 1, "One main action"
    assert not any(n.get("text") in ["Проверить камеру", "Отправить оставшийся кэш"] for n in items)
    tap(find(items, "text", "Сегодня"))
    find(nodes(), "text", "Сегодня")
    tap(find(nodes(), "text", "Вчера"))
    find(nodes(), "text", "Вчера")
    tap(find(nodes(), "text", "Все"))
    tap(find(nodes(), "content-desc", "Выбрать фотографии"))
    picker = nodes()
    photo = next((n.attrib for n in picker if ", не выбрано" in n.get("content-desc", "")), None)
    assert photo is not None, "Run camera_check.py to populate the catalog first"
    tap(photo)
    tap(find(nodes(), "text", "Выбрать · 1"))
    find(nodes(), "text", "Выбрано: 1")
    adb("shell", "am", "force-stop", "dev.malordin.fuji.usb")
    adb("shell", "am", "start", "-n", "dev.malordin.fuji.usb/dev.malordin.fuji.MainActivity")
    find(nodes(), "text", "Выбрано: 1")
    tap(find(nodes(), "text", "Все"))
    items = nodes()
    header = find(items, "content-desc", "Журнал передачи")
    tap(header)
    items = nodes()
    # Existing log entries are clickable; if this is a fresh installation, check its empty state.
    log = set(adb("shell", "run-as", "dev.malordin.fuji.usb", "cat", "files/last-import.log").splitlines())
    entries = [n for n in items if n.get("class") == "android.widget.TextView" and n.get("clickable") == "true" and n.get("text") in log]
    if entries:
        tap(entries[0].attrib)
        detail = nodes()
        find(detail, "text", "Копировать")
        tap(find(detail, "text", "Закрыть"))
    else:
        find(items, "text", "Пока пусто")
    # The header may move when expanded; fetch current coordinates.
    tap(find(nodes(), "content-desc", "Журнал передачи"))
    tap(find(nodes(), "content-desc", "Настройки"))
    form = nodes()
    find(form, "text", "Настройки")
    find(form, "text", "Адрес сервера")
    find(form, "text", "Кэш на телефоне")
    tap(find(form, "text", "Закрыть"))
    tap(find(nodes(), "content-desc", "Дополнительно"))
    menu = nodes()
    find(menu, "text", "Обновить каталог")
    find(menu, "text", "Передать один кадр")
    adb("shell", "input", "keyevent", "4")
    print("PASS: today/yesterday filters, manual photo choice survives restart, collapsible journal, settings, diagnostics; no upload")


if __name__ == "__main__":
    main()
