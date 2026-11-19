"""Reserve a native dock slot for the signed HSD car-menu handoff control."""
from pathlib import Path
import shutil


def patch(root, launcher, once, method):
    dock = launcher.file("c2.1.smali")
    source = once(dock.read_text(), "# instance fields", "# instance fields\n.field private hsdCarButton:Landroid/widget/ImageButton;\n")
    constructor = "public constructor <init>(Lru/drivedeck/launcher/MainActivity;Lru/drivedeck/launcher/V;Ljava/util/ArrayList;Lru/drivedeck/launcher/n9;ZZ)V"
    source = method(source, constructor, lambda body: once(body, "    return-void", """    invoke-static {p0}, Lru/drivedeck/hsd/CarMenuButton;->attach(Landroid/widget/FrameLayout;)Landroid/widget/ImageButton;
    move-result-object v0
    iput-object v0, p0, Lru/drivedeck/launcher/c2;->hsdCarButton:Landroid/widget/ImageButton;
    return-void"""))
    source = method(source, "public final g(FF)I", lambda body: once(body, "    :goto_2\n", """    :goto_2
    invoke-virtual {p0, p1}, Lru/drivedeck/launcher/c2;->hsdBeforeControls(F)F
    move-result p1
"""))

    def draw(body):
        if launcher.names:
            # v19 needs an 8-bit register instruction. v36 is overwritten by the
            # next upstream instruction in this branch.
            start = "    :goto_f\n    add-float v19, v19, v17"
            body = once(body, start, start + """
    invoke-direct {v0}, Lru/drivedeck/launcher/c2;->hsdCarSpace()F
    move-result v36
    add-float v19, v19, v36""")
            portrait, position, gutter = ":goto_36", "v4", "v5"
        else:
            start = "    :goto_f\n    add-float v12, v12, v17"
            body = once(body, start, start + """
    invoke-virtual {v0, v12}, Lru/drivedeck/launcher/c2;->hsdAfterControls(F)F
    move-result v12""")
            portrait, position, gutter = ":goto_3a", "v5", "v7"
        end = (f"    {portrait}\n    invoke-virtual {{v6, {gutter}}}, Lru/drivedeck/launcher/c2;->m(F)F\n\n"
               f"    move-result {gutter}\n\n    sub-float/2addr {position}, {gutter}")
        body = once(body, end, end +
                    f"\n    invoke-virtual {{v6, {position}}}, Lru/drivedeck/launcher/c2;->hsdBeforeControls(F)F\n    move-result {position}\n")
        if body.count("    return-void") != 2:
            raise ValueError("Unexpected DriveDeck dock drawing exits")
        return body.replace("    return-void", """    invoke-virtual/range {p0 .. p0}, Lru/drivedeck/launcher/c2;->hsdCarLayout()V
    return-void""")

    source = method(source, "public final onDraw(Landroid/graphics/Canvas;)V", draw)
    source += """
.method private hsdCarSpace()F
    .locals 2
    iget-object v0, p0, Lru/drivedeck/launcher/c2;->hsdCarButton:Landroid/widget/ImageButton;
    if-eqz v0, :hidden
    iget-boolean v0, p0, Lru/drivedeck/launcher/c2;->B:Z
    if-nez v0, :hidden
    iget-object v0, p0, Lru/drivedeck/launcher/c2;->O:Lru/drivedeck/launcher/n9;
    if-nez v0, :hidden
    const/high16 v0, 0x42800000    # 64.0f
    invoke-virtual {p0, v0}, Lru/drivedeck/launcher/c2;->I(F)F
    move-result v0
    const/high16 v1, 0x41400000    # 12.0f
    invoke-virtual {p0, v1}, Lru/drivedeck/launcher/c2;->m(F)F
    move-result v1
    add-float/2addr v0, v1
    return v0
    :hidden
    const/4 v0, 0x0
    return v0
.end method

.method public final hsdAfterControls(F)F
    .locals 1
    invoke-direct {p0}, Lru/drivedeck/launcher/c2;->hsdCarSpace()F
    move-result v0
    add-float/2addr p1, v0
    return p1
.end method

.method public final hsdBeforeControls(F)F
    .locals 1
    invoke-direct {p0}, Lru/drivedeck/launcher/c2;->hsdCarSpace()F
    move-result v0
    sub-float/2addr p1, v0
    return p1
.end method

.method public final hsdCarLayout()V
    .locals 7
    iget-object v0, p0, Lru/drivedeck/launcher/c2;->hsdCarButton:Landroid/widget/ImageButton;
    iget-object v1, p0, Lru/drivedeck/launcher/c2;->z:Landroid/graphics/RectF;
    iget-object v2, p0, Lru/drivedeck/launcher/c2;->E:Landroid/graphics/RectF;
    const/high16 v3, 0x41400000    # 12.0f
    invoke-virtual {p0, v3}, Lru/drivedeck/launcher/c2;->m(F)F
    move-result v3
    invoke-virtual {p0}, Lru/drivedeck/launcher/c2;->x()Z
    move-result v4
    iget-boolean v5, p0, Lru/drivedeck/launcher/c2;->B:Z
    iget-object v6, p0, Lru/drivedeck/launcher/c2;->O:Lru/drivedeck/launcher/n9;
    if-eqz v6, :normal
    const/4 v5, 0x1
    :normal
    iget v6, p0, Lru/drivedeck/launcher/c2;->l:I
    invoke-static/range {v0 .. v6}, Lru/drivedeck/hsd/CarMenuButton;->place(Landroid/widget/ImageButton;Landroid/graphics/RectF;Landroid/graphics/RectF;FZZI)V
    return-void
.end method
"""
    dock.write_text(source)
    icon = Path(__file__).resolve().parents[2] / "android/app/src/main/res/drawable/desktop_car_menu.xml"
    shutil.copyfile(icon, root / "res/drawable/hsd_car_menu.xml")
