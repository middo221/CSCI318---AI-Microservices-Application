import pathlib
import sys
import tempfile


def check(D):
    """Every problem found under the diagrams folder D, as a list of strings; empty when clean."""
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
    return problems, sources


def self_test():
    """Feed every check a clean folder and then one broken in exactly one way."""
    failures = []

    def build(root, **flaws):
        root.mkdir(parents=True)
        (root / "svg").mkdir()
        names = [f"activity-fr-{n:02d}" for n in range(1, 19)]
        if flaws.get("no_activity"):
            names.remove("activity-fr-07")
        for name in names:
            (root / (name + ".puml")).write_text("@startuml\n@enduml\n", encoding="utf-8")
            if name != flaws.get("no_svg"):
                (root / "svg" / (name + ".svg")).write_text("<svg>" + flaws.get("svg_text", "") + "</svg>", encoding="utf-8")
        if flaws.get("orphan_svg"):
            (root / "svg" / "orphan.svg").write_text("<svg/>", encoding="utf-8")
        listed = [n + ".puml" for n in names] + ["data-ownership.md"]
        if flaws.get("unlisted"):
            listed.remove("activity-fr-03.puml")
        (root / "README.md").write_text("\n".join(f"- [`{n}`]" for n in listed) + "\n" + flaws.get("readme_text", ""),
                                        encoding="utf-8")
        (root / "data-ownership.md").write_text("Each service owns its data.\n", encoding="utf-8")

    def expect(description, flaws, wanted):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp) / "diagrams"
            build(root, **flaws)
            found, _ = check(root)
        text = "\n".join(found)
        good = (not found) if wanted is None else (wanted in text)
        if good:
            print(f"ok      {description}")
        else:
            print(f"FAILED: {description}\n        problems: {found!r}\n        wanted:   {wanted!r}")
            failures.append(description)

    expect("a clean folder passes", {}, None)
    expect("a source with no SVG fails", {"no_svg": "activity-fr-02"}, "no SVG for activity-fr-02.puml")
    expect("an SVG holding 'Syntax Error' fails", {"svg_text": "Syntax Error"}, "contains 'Syntax Error'")
    expect("an SVG holding the Smetana crash text fails", {"svg_text": "An error has occured"}, "contains 'An error has occured'")
    expect("an SVG with no source fails", {"orphan_svg": True}, "orphan.svg has no source")
    expect("a requirement with no activity diagram fails", {"no_activity": True}, "no activity diagram for FR-07")
    expect("a source the README index omits fails", {"unlisted": True}, "README index does not list activity-fr-03.puml")
    expect("a stale first-pass phrase fails", {"readme_text": "Second pass"}, "still says 'Second pass'")

    if failures:
        print(f"FAILED: {len(failures)} check(s) cannot be trusted")
        return 1
    print("every diagram check can fail")
    return 0


if len(sys.argv) > 1 and sys.argv[1] == "--self-test":
    sys.exit(self_test())

problems, sources = check(pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "docs/diagrams"))
for line in problems:
    print("FAILED:", line)
print(f"{len(sources)} diagram sources checked, {len(problems)} problem(s)")
sys.exit(1 if problems else 0)
