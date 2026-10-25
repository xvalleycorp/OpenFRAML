#!/usr/bin/env python3
# Checks that the English and Vietnamese documentation match: the same pages, and in each pair of pages the same
# sequence of heading levels. A pull request that changes the docs must change both languages.
#
#   README.md        <-> docs/vi/README.md
#   docs/<page>.md   <-> docs/vi/<page>.md
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FENCE = re.compile(r"^(```|~~~)")
HEADING = re.compile(r"^(#{1,6})\s")


def headings(path):
    levels, in_code = [], False
    for line in path.read_text(encoding="utf-8").splitlines():
        if FENCE.match(line):
            in_code = not in_code
        elif not in_code and (match := HEADING.match(line)):
            levels.append(len(match.group(1)))
    return levels


def main():
    english = {"README.md": ROOT / "README.md"}
    english.update({p.name: p for p in (ROOT / "docs").glob("*.md")})
    vietnamese = {p.name: p for p in (ROOT / "docs" / "vi").glob("*.md")}
    problems = []
    for name in sorted(english.keys() - vietnamese.keys()):
        problems.append(f"docs/vi/{name} is missing (English: {english[name].relative_to(ROOT)})")
    for name in sorted(vietnamese.keys() - english.keys()):
        problems.append(f"docs/vi/{name} has no English page")
    for name in sorted(english.keys() & vietnamese.keys()):
        en, vi = headings(english[name]), headings(vietnamese[name])
        if en != vi:
            problems.append(f"{english[name].relative_to(ROOT)} and docs/vi/{name} have different headings: "
                            f"levels {en} and {vi}")
    for problem in problems:
        print(problem, file=sys.stderr)
    if not problems:
        print(f"{len(english)} pages match in English and Vietnamese")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
