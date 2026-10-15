"""Transforme les erreurs de compilation et de tests en annotations GitHub.

Les annotations d'un check-run sont lisibles publiquement via l'API GitHub,
ce qui permet de diagnostiquer un échec sans accès authentifié aux journaux.
"""
import glob
import os
import re
import xml.etree.ElementTree as ET


def esc(text: str) -> str:
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def annotate(title: str, lines: list[str]) -> None:
    if not lines:
        return
    # Une annotation par bloc de 40 lignes, 8 blocs au maximum.
    for i in range(0, min(len(lines), 320), 40):
        chunk = "\n".join(lines[i:i + 40])[:60000]
        print(f"::error title={title} ({i // 40 + 1})::{esc(chunk)}")


def log_errors() -> list[str]:
    out: list[str] = []
    for name in ("tests.log", "apk.log"):
        if not os.path.exists(name):
            continue
        with open(name, encoding="utf-8", errors="replace") as fh:
            lines = fh.read().splitlines()
        for idx, line in enumerate(lines):
            if re.match(r"^e: ", line) or "error:" in line.lower() and ("ksp" in line.lower() or ".kt" in line):
                out.append(line.strip())
            elif line.startswith("* What went wrong:"):
                out.extend(l.strip() for l in lines[idx:idx + 8])
            elif re.search(r"(FAILED|Could not resolve|Unresolved reference)", line):
                out.append(line.strip())
    seen = set()
    uniq = []
    for l in out:
        if l and l not in seen:
            seen.add(l)
            uniq.append(l)
    return uniq


def test_failures() -> list[str]:
    out: list[str] = []
    for path in sorted(glob.glob("app/build/test-results/**/*.xml", recursive=True)):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        for case in root.iter("testcase"):
            for tag in ("failure", "error"):
                node = case.find(tag)
                if node is not None:
                    name = f"{case.get('classname')}.{case.get('name')}"
                    msg = (node.get("message") or "").strip()
                    body = (node.text or "").strip().splitlines()[:12]
                    out.append(f"[TEST] {name}: {msg}")
                    out.extend("    " + b.strip() for b in body)
    return out


annotate("Erreurs de compilation", log_errors())
annotate("Tests en echec", test_failures())
