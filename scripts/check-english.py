#!/usr/bin/env python3
"""Fails when Portuguese shows up outside the Portuguese resources.

Code, comments, docs, logs and the default resources are in English (AGENTS.md); Portuguese
lives only in values-pt*/. A line is flagged when it has a letter only Portuguese uses here
(ã, õ, ç) or a common Portuguese word. Legitimate exceptions (a firmware label the code must
match, a test fixture, a language's own name) are listed in scripts/english-allowlist.txt as
`path<TAB>text`: the line is accepted when it is in that file and contains that text.

Usage: scripts/check-english.py   (from anywhere in the repository; lists the tracked files)
"""
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(subprocess.check_output(["git", "rev-parse", "--show-toplevel"], text=True).strip())
SKIP_DIRS = re.compile(r"(^|/)values-pt[^/]*/")
# This check's own word list.
SKIP_FILES = {"scripts/check-english.py", "scripts/english-allowlist.txt"}
SKIP_SUFFIXES = {".png", ".jpg", ".jpeg", ".webp", ".gif", ".ico", ".woff2", ".ttf", ".otf", ".jar", ".so",
                 ".zip", ".apk", ".aab", ".bundle", ".pcm", ".mp3", ".mp4", ".bin"}
PT_LETTERS = re.compile(r"[ãõçÃÕÇ]")
PT_WORDS = re.compile(
    r"\b(não|nao|você|vocês|está|estão|então|também|já|até|ação|ações|configuração|aplicativo|celular|"
    r"óculos|dispositivo|código|emparelhar|ajustes|programador|desenvolvedor|autoconfiguração|"
    r"notificação|notificações|obrigado|obrigada|olá|agora|ainda|depois|porque|sempre|nunca|aqui)\b",
    re.IGNORECASE,
)


def allowlist() -> list[tuple[str, str]]:
    path = ROOT / "scripts" / "english-allowlist.txt"
    entries = []
    for raw in path.read_text(encoding="utf-8").splitlines():
        if not raw.strip() or raw.startswith("#"):
            continue
        file, _, text = raw.partition("\t")
        entries.append((file.strip(), text))
    return entries


def main() -> int:
    allowed = allowlist()
    used = set()
    files = subprocess.check_output(["git", "ls-files"], cwd=ROOT, text=True).splitlines()
    problems = []
    for name in files:
        if name in SKIP_FILES or SKIP_DIRS.search(name) or Path(name).suffix.lower() in SKIP_SUFFIXES:
            continue
        try:
            lines = (ROOT / name).read_text(encoding="utf-8").splitlines()
        except (UnicodeDecodeError, FileNotFoundError, IsADirectoryError):
            continue
        for number, line in enumerate(lines, 1):
            if not (PT_LETTERS.search(line) or PT_WORDS.search(line)):
                continue
            match = next((i for i, (f, t) in enumerate(allowed) if f == name and t in line), None)
            if match is not None:
                used.add(match)
                continue
            problems.append(f"{name}:{number}: {line.strip()[:140]}")
    stale = [f"{f}\t{t}" for i, (f, t) in enumerate(allowed) if i not in used]
    for problem in problems:
        print(problem)
    for entry in stale:
        print(f"scripts/english-allowlist.txt: unused entry: {entry}")
    if problems or stale:
        print(f"\n{len(problems)} line(s) look Portuguese outside values-pt*/, {len(stale)} unused allowlist entr(y/ies).")
        return 1
    print("English check passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
