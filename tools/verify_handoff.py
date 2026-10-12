from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
REQUIRED = ("AGENTS.md", "NEXT_AGENT_HANDOFF.md", "AGENT_TASKS.md", "PROJECT_HISTORY.md")


def validate(documents: dict[str, str]) -> None:
    for name in REQUIRED:
        assert name in documents and documents[name].strip(), f"missing or empty: {name}"

    entry = documents["AGENTS.md"]
    rules = documents["NEXT_AGENT_HANDOFF.md"]
    tasks = documents["AGENT_TASKS.md"]
    history = documents["PROJECT_HISTORY.md"]

    assert rules.startswith("# NEXT_AGENT_HANDOFF\n")
    assert tasks.startswith("# AGENT_TASKS")
    assert history.startswith("# PROJECT_HISTORY")
    for filename in REQUIRED[1:]:
        assert filename in entry, f"AGENTS.md must link {filename}"

    assert "AGENT_TASKS.md" in rules and "PROJECT_HISTORY.md" in rules
    assert "не журнал действий" in rules, "history must remain selective"
    assert "## Текущие задачи" in tasks and "## Планы на потом" in tasks
    active = tasks.split("## Текущие задачи", 1)[1].split("## Планы на потом", 1)[0]
    later = tasks.split("## Планы на потом", 1)[1]
    assert re.search(r"^### \d+\. ", active, re.M), "missing active tasks"
    assert active.count("— **следующий**") == 1, "mark exactly one next task"
    assert re.search(r"^### \d+\. ", later, re.M), "missing future tasks"
    assert "instructions" in active, "confirmed GPT blocker should remain until fixed"
    assert "## Владение состоянием" in history, "essential history missing"

    # No fixed PR count, hard-coded SHA, timestamp, or mandatory log of finished tasks.
    # Validate direct references between documents; old Git history is not in-tree.
    for name, content in documents.items():
        for target in re.findall(r"\]\(([^)#?]+\.md)\)", content):
            if "://" in target:
                continue
            assert (ROOT / target).is_file() or target in documents, f"broken link {name} -> {target}"


def self_test(documents: dict[str, str]) -> None:
    validate(documents)
    mutants = (
        ("missing plan", {k: v for k, v in documents.items() if k != "AGENT_TASKS.md"}),
        ("unmarked priority", {**documents, "AGENT_TASKS.md": documents["AGENT_TASKS.md"].replace("— **следующий**", "")}),
        ("missing history policy", {**documents, "NEXT_AGENT_HANDOFF.md": documents["NEXT_AGENT_HANDOFF.md"].replace("не журнал действий", "протокол")}),
    )
    for name, altered in mutants:
        try:
            validate(altered)
        except AssertionError:
            continue
        raise AssertionError(f"negative mutation was not detected: {name}")


def main() -> None:
    docs = {name: (ROOT / name).read_text(encoding="utf-8") for name in REQUIRED}
    self_test(docs)
    print("Agent docs verifier: PASS (positive + three negative controls)")


if __name__ == "__main__":
    main()
