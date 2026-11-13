"""Integration points in the author's HeadSwap edition of DriveDeck 0.7.0, code 348."""
import copy
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

from profiles import AUTHOR_070

root = Path(sys.argv[1])
launcher = root / "smali/ru/drivedeck/launcher"
resolver = "Lru/drivedeck/hsd/HSDHost;"


def once(source, old, new):
    if source.count(old) != 1:
        raise ValueError("Unexpected DriveDeck integration point: " + old[:90])
    return source.replace(old, new, 1)


def method(source, sig, transform):
    header = ".method " + sig + "\n"
    start = source.index(header) + len(header)
    end = source.index(".end method", start)
    return once(source, header + source[start:end] + ".end method",
                header + transform(source[start:end]) + ".end method")


# The renderer has one connection per process. Keep leases for different hosts isolated.
main = launcher / "MainActivity.smali"
source = once(main.read_text(), ".class public final Lru/drivedeck/launcher/MainActivity;",
              ".class public Lru/drivedeck/launcher/MainActivity;")
for event, arg, reg in [("onCreate", "Landroid/os/Bundle;", "v0"), ("onNewIntent", "Landroid/content/Intent;", "p1")]:
    call = f"    invoke-super {{p0, p1}}, Landroid/app/Activity;->{event}({arg})V"
    obtain = "\n    invoke-virtual {p0}, Landroid/app/Activity;->getIntent()Landroid/content/Intent;\n    move-result-object v0" if event == "onCreate" else ""
    source = once(source, call, call + obtain + f"\n    invoke-static {{p0, {reg}}}, {resolver}->attach(Landroid/app/Activity;Landroid/content/Intent;)V")

# The upstream projection activity initially opens its cards page, which immediately
# records "home" as the last page. Read its saved choice before that first write.
# Explicit music, phone and native-split actions keep the upstream dispatch path.
def restore_layout(body):
    cards = "    invoke-virtual {p0}, Lru/drivedeck/launcher/MainActivity;->d0()V"
    body = once(body, cards, """    const/4 v8, 0x0
    invoke-virtual {p0}, Landroid/app/Activity;->getIntent()Landroid/content/Intent;
    move-result-object v3
    invoke-virtual {v3}, Landroid/content/Intent;->getAction()Ljava/lang/String;
    move-result-object v3
    const-string v4, "android.intent.action.MAIN"
    invoke-virtual {v4, v3}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v3
    if-eqz v3, :hsd_cards
    iget-object v3, p0, Lru/drivedeck/launcher/MainActivity;->e:Lru/drivedeck/launcher/n5;
    invoke-virtual {v3}, Lru/drivedeck/launcher/n5;->C0()Z
    move-result v4
    if-eqz v4, :hsd_cards
    invoke-virtual {v3}, Lru/drivedeck/launcher/n5;->V()Ljava/lang/String;
    move-result-object v3
    const-string v4, "split"
    invoke-virtual {v4, v3}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v8
    if-eqz v8, :hsd_cards
    invoke-virtual {p0}, Lru/drivedeck/launcher/MainActivity;->f0()V
    goto :hsd_layout_ready
    :hsd_cards
""" + cards + "\n    :hsd_layout_ready")
    native = "    invoke-direct {v2, p0, v0, v1}, Lru/drivedeck/launcher/r5;-><init>(Lru/drivedeck/launcher/MainActivity;ZZ)V"
    return once(body, native, "    or-int/2addr v0, v8\n" + native)

source = method(source, "public final onCreate(Landroid/os/Bundle;)V", restore_layout)
main.write_text(source)
(launcher / "HsdActivity.smali").write_text(""".class public final Lru/drivedeck/launcher/HsdActivity;
.super Lru/drivedeck/launcher/MainActivity;

.method public constructor <init>()V
    .locals 0
    invoke-direct {p0}, Lru/drivedeck/launcher/MainActivity;-><init>()V
    return-void
.end method
""")
bridge = launcher / "P1.smali"
bridge.write_text(once(bridge.read_text(), "invoke-virtual {p0}, Landroid/app/Activity;->getDisplay()Landroid/view/Display;",
    "invoke-static {p0}, Lru/drivedeck/hsd/HostedDisplay;->of(Landroid/app/Activity;)Landroid/view/Display;"))
guard = launcher / "E9.smali"
guard.write_text(method(guard.read_text(), "public static g(Lru/drivedeck/launcher/MainActivity;)Z",
                        lambda body: once(body, "const/16 v1, 0x21", "const/16 v1, 0x1d")))

bind = launcher / "x1.1.smali"
source = bind.read_text()
start = source.index('    const-string v3, "com.headswap.desk"')
end = source.index('    const-string v2, "DriveDeckEmbed"', start)
source = source[:start] + f"""    sget-object v3, Lru/drivedeck/launcher/H2;->l:Landroid/content/Context;
    invoke-static {{v3}}, {resolver}->rendererGateway(Landroid/content/Context;)Landroid/content/ComponentName;
    move-result-object v3

""" + source[end:]
source = once(source, '    if-eqz v1, :cond_3\n\n    const-string v0, "HeadSwap host is missing or has a different signing certificate"',
    '    if-nez v3, :cond_3\n\n    const-string v0, "The selected host is unavailable or has a different signing certificate"')
start = source.index("    new-instance v4, Landroid/content/ComponentName;", start)
end = source.index("    invoke-virtual {v1, v4}, Landroid/content/Intent;->setComponent", start)
bind.write_text(source[:start] + "    move-object v4, v3\n\n" + source[end:])

fullscreen = launcher / "S1.smali"
source = method(fullscreen.read_text(), "public static d(Landroid/content/Context;)Z",
    lambda _: f"""    .locals 1
    invoke-static {{p0}}, {resolver}->rendererFullscreen(Landroid/content/Context;)Landroid/content/ComponentName;
    move-result-object v0
    if-eqz v0, :missing
    const/4 v0, 0x1
    return v0
    :missing
    const/4 v0, 0x0
    return v0
""")
for reg in ("v6", "v12"):
    old = f"    sget-object {reg}, Lru/drivedeck/launcher/S1;->a:Landroid/content/ComponentName;"
    new = f"    invoke-static {{v1}}, {resolver}->rendererFullscreen(Landroid/content/Context;)Landroid/content/ComponentName;\n    move-result-object {reg}\n    if-nez {reg}, :hsd_{reg}_available\n    return-void\n    :hsd_{reg}_available"
    source = once(source, old, new)
fullscreen.write_text(source)

# Upstream's app updates use its own signing key. HSD supplies the matching companion.
updater = launcher / "Y9.smali"
updater.write_text(method(updater.read_text(),
    "public static a(Landroid/content/Context;Lru/drivedeck/launcher/n5;ZLandroid/view/View;Lru/drivedeck/launcher/W9;)V",
    lambda _: f"""    .locals 1
    if-eqz p2, :done
    if-eqz p4, :done
    invoke-static {{p0}}, {resolver}->updateNotice(Landroid/content/Context;)Ljava/lang/String;
    move-result-object v0
    invoke-interface {{p4, v0}}, Lru/drivedeck/launcher/W9;->a(Ljava/lang/String;)V
    :done
    return-void
"""))

ns = "{http://schemas.android.com/apk/res/android}"
ET.register_namespace("android", ns[1:-1])
manifest = root / "AndroidManifest.xml"
tree = ET.parse(manifest)
m = tree.getroot()
assert m.get("package") == "ru.drivedeck.headswap"
for host in ("com.hsd.aa", "com.hsd.universal"):
    m.insert(0, ET.Element("uses-permission", {ns+"name": host + ".permission.EMBED_DISPLAY"}))
    ET.SubElement(m.find("queries"), "package", {ns+"name": host})
for permission in m.findall("uses-permission"):
    if permission.get(ns+"name") == "android.permission.REQUEST_INSTALL_PACKAGES":
        m.remove(permission)
app = m.find("application")
app.set(ns+"label", "DriveDeck · HSD")
standard = next(a for a in app.findall("activity") if a.get(ns+"name") == "ru.drivedeck.launcher.MainActivity")
dedicated = copy.deepcopy(standard)
dedicated.set(ns+"name", "ru.drivedeck.launcher.HsdActivity")
dedicated.set(ns+"process", ":hsd")
dedicated.set(ns+"taskAffinity", "ru.drivedeck.headswap.hsd")
dedicated.set(ns+"permission", "com.hsd.universal.permission.EMBED_DISPLAY")
for intent_filter in dedicated.findall("intent-filter"):
    dedicated.remove(intent_filter)
app.append(dedicated)
tree.write(manifest, encoding="utf-8", xml_declaration=True)
yml = root / "apktool.yml"
yml.write_text(once(yml.read_text(), "versionCode: 348", "versionCode: 349"))
(root.parent / "patch-report.json").write_text(json.dumps({
    "original_sha256": AUTHOR_070.original_sha256, "version_code": AUTHOR_070.code,
    "renderer": "upstream HeadSwap edition", "entry": "ru.drivedeck.launcher.HsdActivity",
    "package": "ru.drivedeck.headswap", "min_hsd_sdk": 29}, indent=2))
