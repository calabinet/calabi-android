"""Validate Android translations against the marketing site's supported locales."""

from collections import Counter
from pathlib import Path
import re
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "apps/client-android/app/src/main/res"
LANGUAGES = ROOT / "web/www/src/i18n/languages.ts"
QUALIFIERS = {"en": "values", "zh-CN": "values-zh-rCN", "zh-TW": "values-zh-rTW"}
SPECIFIER = re.compile(r"%(\d+)\$([dsf])")


def resources(directory):
    result = {}
    for path in sorted(directory.glob("*.xml")):
        for entry in ET.parse(path).getroot():
            if entry.tag not in ("string", "plurals"):
                continue
            name = entry.attrib["name"]
            assert name not in result, f"Duplicate {directory.name}/{name}"
            result[name] = entry
    return result


def formats(text):
    return Counter(SPECIFIER.findall(text.replace("%%", "")))


def check():
    locales = re.findall(r'code: "([^"]+)"', LANGUAGES.read_text(encoding="utf-8"))
    assert len(locales) == 10 and len(set(locales)) == 10
    allowed = {QUALIFIERS.get(locale, "values-" + locale) for locale in locales}
    actual = {p.name for p in RES.iterdir() if p.is_dir() and
              re.fullmatch(r"values-[a-z]{2}(?:-r[A-Z]{2})?", p.name)} | {"values"}
    assert actual == allowed, f"Locale directories differ from website: {actual ^ allowed}"
    base = resources(RES / "values")
    required = {k: v for k, v in base.items() if v.get("translatable") != "false"}
    for locale in locales:
        translated = resources(RES / QUALIFIERS.get(locale, "values-" + locale))
        missing = required.keys() - translated.keys()
        assert not missing, f"{locale}: missing {sorted(missing)}"
        extra = translated.keys() - base.keys()
        assert not extra, f"{locale}: unknown {sorted(extra)}"
        for name, original in required.items():
            entry = translated[name]
            assert entry.tag == original.tag, f"{locale}/{name}: resource type changed"
            expected_text = "".join((original.find("item[@quantity='other']")
                                     if original.tag == "plurals" else original).itertext())
            expected = formats(expected_text)
            variants = list(entry) if entry.tag == "plurals" else [entry]
            if entry.tag == "plurals":
                assert entry.find("item[@quantity='other']") is not None, f"{locale}/{name}: no other plural"
                quantities = [v.attrib["quantity"] for v in variants]
                assert len(quantities) == len(set(quantities)), f"{locale}/{name}: duplicate plural quantity"
                needed = {"other"} if locale in ("ja", "ko", "zh-CN", "zh-TW") else {"one", "other"}
                if locale in ("es", "fr", "it", "pt"):
                    needed.add("many")
                assert needed <= set(quantities), f"{locale}/{name}: missing plural categories {needed - set(quantities)}"
            for variant in variants:
                text = "".join(variant.itertext())
                assert text.strip(), f"{locale}/{name}: empty translation"
                assert formats(text) == expected, f"{locale}/{name}: format mismatch"
                assert not re.search(r"\{\{.+?\}\}", text), f"{locale}/{name}: unresolved web placeholder"
        print(f"{locale}: {len(required)} resources, format arguments and plural forms OK")


if __name__ == "__main__":
    check()
