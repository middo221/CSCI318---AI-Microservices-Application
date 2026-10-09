import pathlib
import sys

D = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "docs/diagrams")
problems = []
sources = sorted(p.name for p in D.glob("*.puml"))

# Every source has a clean SVG. "An error has occured" is how a Smetana crash looks: PlantUML
# still writes the file and exits 0, so only the content gives it away.
for name in sources:
    svg = D / "svg" / (name[:-len(".puml")] + ".svg")
    if not svg.exists():
        problems.append(f"no SVG for {name}")
        continue
    text = svg.read_text(encoding="utf-8", errors="replace")
    for marker in ("Syntax Error", "[From ", "Cannot find Graphviz", "An error has occured"):
        if marker in text:
            problems.append(f"{svg.name} contains '{marker}'")
for svg in sorted((D / "svg").glob("*.svg")):
    if not (D / (svg.stem + ".puml")).exists():
        problems.append(f"{svg.name} has no source")

# One activity diagram per functional requirement.
for n in range(1, 19):
    if not (D / f"activity-fr-{n:02d}.puml").exists():
        problems.append(f"no activity diagram for FR-{n:02d}")

# The README index lists every source.
readme = (D / "README.md").read_text(encoding="utf-8")
for name in sources + ["data-ownership.md"]:
    if f"[`{name}`]" not in readme:
        problems.append(f"README index does not list {name}")

# Nothing from the first pass that the code has since made untrue.
stale = ["Second pass", "planned M3", "skeleton", "(empty on main)", "No consumer on main yet",
         "no caller on main", "nothing on main calls", "REFUNDED exists, but no use",
         "from M3", "M3 funnel", "(M3)", "will aggregate", "which no handler maps\n  (Spring"]
texts = list(D.glob("*.puml")) + list((D / "include").glob("*.iuml")) + [D / "data-ownership.md", D / "README.md"]
for path in texts:
    text = path.read_text(encoding="utf-8")
    for phrase in stale:
        if phrase in text:
            problems.append(f"{path.name} still says '{phrase.splitlines()[0]}'")

for line in problems:
    print("FAILED:", line)
print(f"{len(sources)} diagram sources checked, {len(problems)} problem(s)")
sys.exit(1 if problems else 0)
