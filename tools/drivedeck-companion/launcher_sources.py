"""Read and write pinned APK classes using one vocabulary for common integration points."""
import re


class LauncherSources:
    # R8 renumbered these classes between the two author releases. Each pair is
    # disjoint. Swapping both directions preserves unrelated references on write.
    RENAMED_360 = {
        "E9": "V9", "N3": "L3", "Y9": "pa", "h4": "f4", "x4": "w4",
        "W9": "na", "g8": "h8", "m9": "s9", "n9": "t9",
    }
    DESCRIPTOR = re.compile(r"(Lru/drivedeck/launcher/)([\w$]+)(?=;)")

    METHODS_360 = {
        "MainActivity": {"d0()V": "e0()V", "f0()V": "g0()V", "L(Ljava/lang/String;Z)Z": "M(Ljava/lang/String;Z)Z"},
        "n5": {"C0()Z": "E0()Z", "V()Ljava/lang/String;": "W()Ljava/lang/String;",
               "l()Ljava/lang/String;": "m()Ljava/lang/String;", "v0()Z": "x0()Z",
               "z()Ljava/lang/String;": "A()Ljava/lang/String;", "z0()Z": "B0()Z", "o0()Z": "q0()Z"},
    }

    def __init__(self, root, upstream_code):
        self.directory = root / "smali/ru/drivedeck/launcher"
        self.names = self.RENAMED_360 if upstream_code == 360 else {}
        self.swaps = {**self.names, **{new: old for old, new in self.names.items()}}

    def translate(self, source):
        if not self.swaps:
            return source
        for owner, names in self.METHODS_360.items():
            swaps = {**names, **{new: old for old, new in names.items()}}
            prefix = "Lru/drivedeck/launcher/" + owner + ";->"
            # Only exact method references and the owner's own declarations participate.
            choices = "|".join(re.escape(key) for key in swaps)
            pattern = re.escape(prefix)
            if re.search(r"^\.class [^\n]*Lru/drivedeck/launcher/" + owner + r";$", source, re.M):
                pattern += r"|^\.method [^\n]*? "
            source = re.sub("(" + pattern + ")(" + choices + ")", lambda m: m[1] + swaps[m[2]], source, flags=re.M)
        return self.DESCRIPTOR.sub(lambda m: m[1] + self.swaps.get(m[2], m[2]), source)

    def file(self, name):
        stem, suffix = name.split(".", 1)
        return LauncherFile(self.directory / (self.names.get(stem, stem) + "." + suffix), self.translate)


class LauncherFile:
    def __init__(self, path, translate):
        self.path = path
        self.translate = translate

    def read_text(self):
        return self.translate(self.path.read_text())

    def write_text(self, source):
        self.path.write_text(self.translate(source))
