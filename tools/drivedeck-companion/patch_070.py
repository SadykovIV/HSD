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


# The split divider and the dashboard grid use the same design-unit gutter.
# Keep the dock, border, and the physical touch target independent of the visible gap.
layout_settings = "Lru/drivedeck/hsd/ProjectionSettings;"
gap_call = f"    invoke-static {{}}, {layout_settings}->paneGap()F\n"
app_class = launcher / "DriveDeckApplication.smali"
app_super = "    invoke-super {p0}, Landroid/app/Application;->onCreate()V"
app_class.write_text(once(app_class.read_text(), app_super, app_super +
    f"\n    invoke-static {{p0}}, {layout_settings}->initializeLayout(Landroid/content/Context;)V"))
page_class = launcher / "a0.1.smali"
page_super = "    invoke-direct {p0, p1}, Landroid/view/ViewGroup;-><init>(Landroid/content/Context;)V"
page_class.write_text(once(page_class.read_text(), page_super, page_super +
    f"\n    invoke-static {{p0}}, {layout_settings}->observePaneSpacing(Landroid/view/View;)V"))

split = launcher / "m2.1.smali"
split_src = split.read_text()
for signature, register in (("L(I)I", "v1"), ("onMeasure(II)V", "v10"),
                            ("onLayout(ZIIII)V", "v11"), ("y(Z)F", "v0")):
    split_src = method(split_src, "public final " + signature, lambda body: once(body,
        f"    const/high16 {register}, 0x41400000    # 12.0f", gap_call + f"    move-result {register}"))
# In portrait the upstream touch target starts at the upper pane's bottom.
# Center the full target on the boundary, as in landscape.
split_src = once(split_src, """    add-int/2addr v7, v3

    invoke-virtual {v14, v8, v3, v1, v7}, Landroid/view/View;->layout(IIII)V""", """    sub-int v9, v7, v11
    div-int/lit8 v9, v9, 0x2
    sub-int v9, v3, v9
    add-int/2addr v7, v9
    invoke-virtual {v14, v8, v9, v1, v7}, Landroid/view/View;->layout(IIII)V""")
split.write_text(split_src)

grid = launcher / "E9.smali"
# v2 also defines the outer dashboard inset. Keep it and derive the grid's
# inter-cell interval separately for row height, card bounds and editor snapping.
def grid_spacing(body):
    old = "    mul-float v2, v5, v18"
    body = once(body, old, old + f"\n    invoke-static {{v2}}, {layout_settings}->gridGap(F)F\n    move-result v21")
    body = once(body, "    sub-float/2addr v8, v2\n\n    invoke-static {v7, v8}, Ljava/lang/Math;->max(FF)F",
                "    sub-float v8, v8, v21\n\n    invoke-static {v7, v8}, Ljava/lang/Math;->max(FF)F")
    return once(body, "    move/from16 v21, v2\n", "")

grid.write_text(method(grid.read_text(), "public static q(Lru/drivedeck/launcher/S0;IIFFZZI)Lru/drivedeck/launcher/M0;",
    grid_spacing))
grid_model = launcher / "M0.smali"
def snap_joined_edges(body):
    # Downstream views round the origin and width separately. Align each shared
    # boundary once, so neighboring cells never overlap or expose a pixel seam.
    anchor = "    invoke-direct {v3, v11, v13, v0, p1}, Lru/drivedeck/launcher/L0;-><init>(FFFF)V"
    aligned = """    const/4 v1, 0x0
    cmpl-float v1, v10, v1
    if-nez v1, :hsd_grid_edges_ready
"""
    for edge in ("v11", "v13", "v0", "p1"):
        aligned += (f"    invoke-static {{{edge}}}, Ljava/lang/Math;->round(F)I\n"
                    f"    move-result {edge}\n    int-to-float {edge}, {edge}\n")
    return once(body, anchor, aligned + "    :hsd_grid_edges_ready\n" + anchor)

grid_model.write_text(method(grid_model.read_text(),
    "public final a(Lru/drivedeck/launcher/P0;)Lru/drivedeck/launcher/L0;", snap_joined_edges))
preview = launcher / "h4.1.smali"
preview.write_text(once(preview.read_text(), "    const/high16 v17, 0x41400000    # 12.0f",
    gap_call + "    move-result v17"))

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

# The cards page can be opened automatically on a head unit. Ask for optional
# location from the permissions page, never as a side effect of showing the desktop.
def remove_startup_location(body):
    start = body.index('    const-string v0, "android.permission.ACCESS_FINE_LOCATION"')
    end = body.index('    :cond_0\n', body.index('->requestPermissions(', start))
    return body[:start] + "    const/4 v2, 0x1\n    const/4 v3, 0x0\n" + body[end:]

source = method(source, "public final d0()V", remove_startup_location)
awake = f"{resolver}->keepLauncherDisplayAwake(Landroid/app/Activity;)V"
source = method(source, "public final onResume()V", lambda body: once(body,
    """    invoke-virtual {p0}, Landroid/app/Activity;->getWindow()Landroid/view/Window;

    move-result-object v1

    const/16 v2, 0x80

    invoke-virtual {v1, v2}, Landroid/view/Window;->addFlags(I)V""",
    f"    invoke-static {{p0}}, {awake}"))
screen_events = launcher / "G5.smali"
screen_events.write_text(once(screen_events.read_text(), """    invoke-virtual {p1}, Landroid/app/Activity;->getWindow()Landroid/view/Window;

    move-result-object p2

    invoke-virtual {p2, v3}, Landroid/view/Window;->addFlags(I)V""", f"    invoke-static {{p1}}, {awake}"))
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

# Keep the author's geometry result and coordinate transform. Compute its buffer with the
# same policy as the host, including the shared pixel budget of both tiles on large displays.
geometry = "z(IIIZ)Lru/drivedeck/launcher/O2;"
view_geometry = "z(IIIZLandroid/view/View;)Lru/drivedeck/launcher/O2;"
def share_geometry(body):
    start = body.index("    sget-object v2, Lc/a;->a:Ljava/util/Set;")
    pack = body.index("    :goto_0\n", start)
    reject = body.index("    :cond_2\n", pack)
    return body[:start] + """    invoke-static/range {p0 .. p4}, Lru/drivedeck/hsd/HostedDisplay;->renderGeometry(IIIZLandroid/view/View;)[I
    move-result-object v2
""" + body[pack:reject]

source = method(guard.read_text(), "public static " + geometry, share_geometry)
guard.write_text(once(source, ".method public static " + geometry, ".method public static " + view_geometry))
renderer = launcher / "H2.smali"
def configure_tile(body):
    # onServiceConnected retries each pending tile with its real gateway capabilities.
    # A layout callback before that point must not reject QHD under the legacy limits.
    read_caps = "    sget v0, Lru/drivedeck/launcher/H2;->k:I"
    body = once(body, read_caps, """    sget-object v0, Lru/drivedeck/launcher/H2;->h:Lb/c;
    if-nez v0, :hsd_gateway_ready
    return v1
    :hsd_gateway_ready
""" + read_caps)
    return once(body, "    invoke-static {p1, p2, v2, v0}, Lru/drivedeck/launcher/E9;->" + geometry,
                "    iget-object v3, p0, Lru/drivedeck/launcher/E2;->a:Lru/drivedeck/launcher/g8;\n"
                "    invoke-static {p1, p2, v2, v0, v3}, Lru/drivedeck/launcher/E9;->" + view_geometry)

renderer.write_text(method(renderer.read_text(), "public static y(Lru/drivedeck/launcher/E2;IILjava/lang/String;)Z", configure_tile))

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
# Use the same effective options in the system shortcut and the normal launcher path.
for key_reg in ("v0", "v2"):
    read = f"    invoke-interface {{v3, {key_reg}, v9}}, Landroid/content/SharedPreferences;->getBoolean(Ljava/lang/String;Z)Z"
    assert source.count(read) == 2
    source = source.replace(read, f"    invoke-static {{v3, {key_reg}, v1}}, {layout_settings}->navigationOption(Landroid/content/SharedPreferences;Ljava/lang/String;Landroid/content/Context;)Z")
options = "    invoke-static {v1, v0}, Lru/drivedeck/launcher/S1;->f(Landroid/content/Context;Landroid/content/Intent;)Landroid/os/Bundle;"
source = once(source, options, f"    invoke-static {{v1, v0}}, {layout_settings}->addMenuRequest(Landroid/content/Context;Landroid/content/Intent;)V\n" + options)
admission = "    :goto_6\n    new-instance v18, Lru/drivedeck/launcher/R1;"
source = once(source, admission, "    :goto_6\n" +
    f"    invoke-static {{v15, v1}}, {layout_settings}->needsControls(ZLandroid/content/Context;)Z\n    move-result v15\n" +
    "    new-instance v18, Lru/drivedeck/launcher/R1;")
# After admission the upstream callback constructs its own intent. Resolve its destination
# from the Activity's actual host as well, including on a device with both hosts installed.
callback = launcher / "R1.smali"
body = once(callback.read_text(), "    sget-object v3, Lru/drivedeck/launcher/S1;->a:Landroid/content/ComponentName;",
    f"    invoke-static {{v2}}, {resolver}->rendererFullscreen(Landroid/content/Context;)Landroid/content/ComponentName;\n" +
    "    move-result-object v3\n    if-eqz v3, :cond_1")
start_host = "    :try_start_0\n    invoke-static {v2, p1}, Lru/drivedeck/launcher/S1;->f("
callback.write_text(once(body, start_host, f"    invoke-static {{v2, p1}}, {layout_settings}->addMenuRequest(Landroid/content/Context;Landroid/content/Intent;)V\n\n" + start_host))
# An unused static read in the app-picker only initialized the upstream fixed component.
guard.write_text(once(guard.read_text(), "    sget-object p1, Lru/drivedeck/launcher/S1;->a:Landroid/content/ComponentName;\n", ""))
source = method(source, "static constructor <clinit>()V", lambda _: "    .locals 0\n    return-void\n")
source = once(source, ".field public static final a:Landroid/content/ComponentName;\n", "")
fullscreen.write_text(source)

# The HeadSwap edition omits the rollback card. Its settings layout still tries to
# distribute four children by position, so opening the dialog adds a null view.
# Reparent the cards actually built, preserving their order and callbacks.
def distribute_update_cards(body):
    lookup = "    iget-object v2, v0, Lru/drivedeck/launcher/N3;->G:Landroid/widget/LinearLayout;"
    start = body.index(lookup, body.index(":cond_3c\n    iget-object"))
    # The first card (v1) has its own layout. Remove it before accessing the others.
    start = body.index(lookup, body.index("->removeView(Landroid/view/View;)V", start))
    end = body.index("    const/4 v15, 0x0", start)
    body = once(body, body[start:end], """    iget-object v5, v0, Lru/drivedeck/launcher/N3;->G:Landroid/widget/LinearLayout;
    invoke-virtual {v5, v14}, Landroid/view/ViewGroup;->getChildAt(I)Landroid/view/View;
    move-result-object v2
    invoke-virtual {v5, v2}, Landroid/view/ViewGroup;->removeView(Landroid/view/View;)V

""")
    start = body.index("    invoke-virtual {v0, v1, v3, v2, v14}, Lru/drivedeck/launcher/N3;->r0", start)
    end = body.index("    new-instance v2, Lru/drivedeck/launcher/m9;", start)
    return once(body, body[start:end], """    :hsd_next_update_card
    invoke-virtual {v5}, Landroid/view/ViewGroup;->getChildCount()I
    move-result v3
    if-eqz v3, :hsd_update_cards_done
    invoke-virtual {v5, v14}, Landroid/view/ViewGroup;->getChildAt(I)Landroid/view/View;
    move-result-object v3
    invoke-virtual {v5, v3}, Landroid/view/ViewGroup;->removeView(Landroid/view/View;)V
    invoke-virtual {v0, v1, v3, v2, v14}, Lru/drivedeck/launcher/N3;->r0(Landroid/widget/LinearLayout;Landroid/view/View;II)V
    invoke-static {v3}, Lru/drivedeck/launcher/N3;->j(Landroid/view/View;)V
    goto :hsd_next_update_card
    :hsd_update_cards_done

""")

settings = launcher / "N3.smali"
source = method(settings.read_text(), "public final b0()V", distribute_update_cards)

def navigation_options(body):
    # Insert below the two native Dock behaviour switches, before transparency.
    anchor = "    new-instance v0, Ljava/lang/StringBuilder;"
    pos = body.index(anchor, body.index("->o0()Z"))
    return body[:pos] + """    invoke-virtual {v14}, Lru/drivedeck/launcher/n5;->l()Ljava/lang/String;
    move-result-object v2
    invoke-static {v12, v0, v2}, Lru/drivedeck/hsd/ProjectionSettings;->addNavigation(Landroid/widget/LinearLayout;Landroid/widget/Switch;Ljava/lang/String;)V
""" + body[pos:]

def projection_permissions(body):
    # Keep the original scroll layout, music listener, location status and their Android
    # settings actions. The dedicated companion does not implement HOME or a global overlay.
    first = body.index("    if-eqz v1, :cond_1")
    music = body.index("    invoke-static {v1}, Lru/drivedeck/launcher/NotificationBridgeService;->b")
    end = body.index("    sget-object v9, Lru/drivedeck/launcher/f5;")
    row = "    invoke-virtual {p0, v0, v7, v8, v2}, Lru/drivedeck/launcher/N3;->c(Landroid/widget/LinearLayout;Landroid/widget/LinearLayout;Landroid/widget/LinearLayout;I)V"
    assert row in body[end:]
    return body[:first] + body[music:end] + "    const/16 v2, 0xc\n" + row + "\n    return-void\n"

source = method(source, "public final V()V", navigation_options)
def spacing_option(body):
    anchor = "    invoke-virtual {v6}, Lru/drivedeck/launcher/n5;->z0()Z"
    return once(body, anchor, """    invoke-virtual {v6}, Lru/drivedeck/launcher/n5;->l()Ljava/lang/String;
    move-result-object v3
    invoke-static {v1, v4, v3}, Lru/drivedeck/hsd/ProjectionSettings;->addPaneSpacing(Landroid/widget/LinearLayout;Landroid/widget/Switch;Ljava/lang/String;)V
""" + anchor)

source = method(source, "public final X()V", spacing_option)
settings.write_text(method(source, "public final a0()V", projection_permissions))

# Include the restored preferences in the author's normal backup/import format.
schema_file = root / "assets/settings_schema.json"
schema = json.loads(schema_file.read_text())
for key in ("headswap_fullscreen_back", "headswap_fullscreen_home", "hsd_fullscreen_menu", "hsd_join_panes"):
    schema["drive_deck"]["exact"][key] = "Boolean"
schema_file.write_text(json.dumps(schema, ensure_ascii=False, separators=(",", ":")))

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
ET.SubElement(app, "meta-data", {ns+"name": "com.hsd.projection.NATIVE_TILE_GEOMETRY", ns+"value": "true"})
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
yml.write_text(once(yml.read_text(), f"versionCode: {AUTHOR_070.upstream_code}", f"versionCode: {AUTHOR_070.code}"))
(root.parent / "patch-report.json").write_text(json.dumps({
    "original_sha256": AUTHOR_070.original_sha256, "version_code": AUTHOR_070.code,
    "renderer": "upstream HeadSwap edition", "entry": "ru.drivedeck.launcher.HsdActivity",
    "package": "ru.drivedeck.headswap", "min_hsd_sdk": 29}, indent=2))
