#!/usr/bin/env python3
"""Apply extra UI translations and a read-only diagnostics page to Scene 5 Alpha."""
import json
import re
import shutil
import sys
from pathlib import Path
import xml.etree.ElementTree as ET

upstream = Path(sys.argv[1]).resolve()
patch_dir = Path(__file__).resolve().parent
dictionary = json.loads((patch_dir / "ui-strings.json").read_text(encoding="utf-8"))
chinese = re.compile(r"[\u3400-\u9fff]")
attribute = re.compile(r'''(?P<name>[\w:.-]+)=(?P<quote>["'])(?P<value>.*?)(?P=quote)''', re.S)
changed = 0
translated = 0
untranslated = {}

# Exact-match translation is intentional. Paths, scripts and configuration keys are not translated.
for filename in upstream.glob("*/src/main/res/layout/*.xml"):
    original = filename.read_text(encoding="utf-8")
    hits = [0]
    def convert_attr(match):
        value = match.group("value")
        if not chinese.search(value):
            return match.group(0)
        new = dictionary.get(value)
        if new is None and value.strip() in dictionary:
            new = dictionary[value.strip()]
        if new is None:
            untranslated.setdefault(str(filename.relative_to(upstream)), []).append(value)
            return match.group(0)
        hits[0] += 1
        return match.group("name") + "=" + match.group("quote") + new + match.group("quote")
    modified = attribute.sub(convert_attr, original)
    if modified != original:
        try:
            ET.fromstring(modified)
        except Exception as exc:
            raise RuntimeError(f"Translated XML is invalid: {filename}: {exc}")
        filename.write_text(modified, encoding="utf-8")
        changed += 1
        translated += hits[0]

# Translate exact quoted literals also used by original Kotlin and Java UI.
code_translated = 0
literal = re.compile(r'"(?:\\.|[^"\\])*"')
for filename in upstream.glob("*/src/main/java/**/*"):
    if filename.suffix not in (".kt", ".java"):
        continue
    source = filename.read_text(encoding="utf-8")
    count = [0]
    def replace_literal(match):
        word = match.group(0)[1:-1]
        if chinese.search(word) and word in dictionary:
            count[0] += 1
            return '"' + dictionary[word].replace('"', '\\"') + '"'
        return match.group(0)
    fixed = literal.sub(replace_literal, source)
    # Android 16 no longer needs to be displayed as "SDK(36)".
    if filename.name == "FragmentHome.kt":
        fixed = fixed.replace(
            'else -> "SDK(" + Build.VERSION.SDK_INT + ")"',
            'else -> "Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")"'
        )
    if fixed != source:
        filename.write_text(fixed, encoding="utf-8")
        code_translated += count[0]

# Add an actual button in the main bar. Using a system icon avoids a new drawable dependency.
main_layout = upstream / "app/src/main/res/layout/activity_main.xml"
source = main_layout.read_text(encoding="utf-8")
marker = '        <ImageButton\n            android:alpha="0.7"\n            android:id="@+id/action_graph"'
assert marker in source, "Main toolbar marker missing"
new_button = '''        <ImageButton
            android:id="@+id/action_diagnostics"
            style="?android:buttonBarButtonStyle"
            android:layout_width="40dp"
            android:layout_height="40dp"
            android:layout_marginRight="3dp"
            android:background="@null"
            android:contentDescription="Диагностика устройства"
            android:padding="7dp"
            android:src="@android:drawable/ic_menu_info_details"
            android:tint="?android:textColorPrimary" />

'''
main_layout.write_text(source.replace(marker, new_button + marker), encoding="utf-8")
ET.parse(main_layout)

main_kt = upstream / "app/src/main/java/com/omarea/vtools/activities/ActivityMain.kt"
source = main_kt.read_text(encoding="utf-8")
marker = '        action_settings.setOnClickListener {'
assert marker in source, "ActivityMain click marker missing"
source = source.replace(marker, '''        action_diagnostics.setOnClickListener {
            startActivity(Intent(this, ActivityDiagnostics::class.java))
        }
''' + marker)
main_kt.write_text(source, encoding="utf-8")

manifest = upstream / "app/src/main/AndroidManifest.xml"
source = manifest.read_text(encoding="utf-8")
marker = '        <activity\n            android:name=".activities.ActivityMain"'
assert marker in source, "ActivityMain manifest marker missing"
source = source.replace(marker,
    '''        <activity
            android:name=".activities.ActivityDiagnostics"
            android:exported="false"
            android:label="Диагностика" />
''' + marker)
manifest.write_text(source, encoding="utf-8")
ET.parse(manifest)
destination = upstream / "app/src/main/java/com/omarea/vtools/activities/ActivityDiagnostics.java"
shutil.copyfile(patch_dir / "ActivityDiagnostics.java", destination)

# Report remaining Chinese labels in layout resources. Some custom XML widgets may still need work.
print(f"Additional localized XML files: {changed}; attributes: {translated}")
print(f"Translated Java/Kotlin quoted literals: {code_translated}")
print(f"Untranslated layout texts: {sum(len(v) for v in untranslated.values())} in {len(untranslated)} files")
for filename, values in sorted(untranslated.items())[:30]:
    print("  " + filename + ": " + " | ".join(v[:85] for v in list(dict.fromkeys(values))[:3]))
# Scene 5 uses sourceCompatibility 1.7 by default; the diagnostics screen needs Java 8 lambdas.
gradle_file = upstream / "app/build.gradle"
gradle_source = gradle_file.read_text(encoding="utf-8")
assert "android {" in gradle_source
gradle_file.write_text(gradle_source.replace("android {", """android {
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }""", 1), encoding="utf-8")
print("Read-only diagnostics activity installed successfully")
