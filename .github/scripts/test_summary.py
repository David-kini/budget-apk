"""Publie le nombre réel de tests exécutés, réussis et échoués (annotation publique + résumé du job)."""
import glob
import os
import xml.etree.ElementTree as ET

total = failures = errors = skipped = 0
per_class: list[str] = []
for path in sorted(glob.glob("app/build/test-results/testDebugUnitTest/*.xml")):
    root = ET.parse(path).getroot()
    t, f, e, s = (int(root.get(k, 0)) for k in ("tests", "failures", "errors", "skipped"))
    total, failures, errors, skipped = total + t, failures + f, errors + e, skipped + s
    per_class.append(f"{root.get('name')}: {t - f - e - s}/{t} réussis")

passed = total - failures - errors - skipped
summary = f"Tests exécutés : {total} | réussis : {passed} | échoués : {failures + errors} | ignorés : {skipped}"
detail = "%0A".join([summary] + per_class)
level = "notice" if failures + errors == 0 and total > 0 else "error"
print(f"::{level} title=Bilan des tests::{detail}")

step_summary = os.environ.get("GITHUB_STEP_SUMMARY")
if step_summary:
    with open(step_summary, "a", encoding="utf-8") as fh:
        fh.write(f"### {summary}\n\n" + "\n".join(f"- {c}" for c in per_class) + "\n")
