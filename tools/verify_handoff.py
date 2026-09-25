from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
HANDOFF = ROOT / "NEXT_AGENT_HANDOFF.md"

def require(text: str, needle: str) -> None:
    assert needle in text, f"missing required text: {needle}"

def main() -> None:
    text = HANDOFF.read_text(encoding="utf-8")
    lines = text.splitlines()

    assert lines and lines[0] == "# NEXT_AGENT_HANDOFF"
    assert text.count("**Last updated:**") == 1
    assert re.search(r"\*\*Last updated:\*\* \d{4}-\d{2}-\d{2} \d{2}:\d{2} [+-]\d{2}:\d{2}", text)
    assert text.count("**Следующий рабочий срез:**") == 1

    required_sections = [
        "# START HERE — ОБЯЗАТЕЛЬНО",
        "## 30-секундная карта проекта",
        "## Фактический baseline",
        "## Подтверждённые lifecycle/recovery контракты",
        "## АРХИТЕКТУРНЫЕ ПРИНЦИПЫ — стабильные правила",
        "## АКТИВНЫЙ ПЛАН — только незавершённое",
        "## Репозиторий / branches",
        "## Контекстный режим для агента",
        "## Как проверять новую работу",
        "## История последних рабочих заходов — последние 5 PR",
        "## Product radar — идеи с реальной ценностью",
        "## Документация",
        "## Короткий отчёт пользователю",
    ]
    headings = [line.strip() for line in lines if line.startswith("#")]
    assert headings.count("# NEXT_AGENT_HANDOFF") == 1
    assert len(headings) == len(set(headings)), "duplicate markdown heading detected"
    for section in required_sections:
        assert headings.count(section) == 1, f"section count mismatch: {section}"

    positions = [headings.index(section) for section in required_sections]
    assert positions == sorted(positions), "required sections out of order"

    for index, line in enumerate(lines):
        if not line.startswith("#"):
            continue
        if index > 0:
            assert lines[index - 1].strip() == "", f"heading without blank line before: {line}"
        j = index + 1
        while j < len(lines) and not lines[j].strip():
            j += 1
        assert j < len(lines), f"heading without content: {line}"
        if lines[j].startswith("#"):
            level = len(line) - len(line.lstrip("#"))
            next_level = len(lines[j]) - len(lines[j].lstrip("#"))
            assert next_level > level, f"heading without content: {line}"

    assert text.count("`") % 2 == 0, "unbalanced inline backticks"

    active = text[text.index("## АКТИВНЫЙ ПЛАН — только незавершённое"):text.index("## Репозиторий / branches")]
    active_headings = [line.strip() for line in active.splitlines() if line.startswith("### ")]
    assert active_headings, "active plan should contain at least one plan item"
    next_items = [heading for heading in active_headings if "— **следующий**" in heading]
    assert len(next_items) == 1, f"expected exactly one active-plan item marked as next, found {len(next_items)}"

    next_slice_line = next(line for line in lines if line.startswith("**Следующий рабочий срез:**"))
    declared_slice = next_slice_line.split(":", 1)[1].strip().split(" — ", 1)[0].strip()
    next_heading_title = re.sub(r"^###\s+\d+\.\s*", "", next_items[0])
    next_heading_title = next_heading_title.replace(" — **следующий**", "").strip()
    assert declared_slice.lower() in next_heading_title.lower(), "active-plan next item does not match the declared next work slice"

    for index, heading in enumerate(active_headings):
        start = active.find(heading)
        end = active.find(active_headings[index + 1], start + len(heading)) if index + 1 < len(active_headings) else len(active)
        item = active[start:end]
        require(item, "**Первый шаг:**")

    # One trigger for architecture/product review; START HERE is the canonical rule.
    assert text.count("каждые 5 merged PR или раньше") == 1

    radar = text[text.index("## Product radar — идеи с реальной ценностью"):text.index("## Документация")]
    candidate_headings = [line.strip() for line in radar.splitlines() if line.startswith("### ") and not line.startswith("### Что сознательно")]
    assert candidate_headings, "product radar should contain at least one candidate idea"
    candidate_numbers = []
    for heading in candidate_headings:
        match = re.match(r"### (\d+)\. ", heading)
        assert match, f"candidate heading is not numbered: {heading}"
        candidate_numbers.append(int(match.group(1)))
        start = radar.find(heading)
        idx = candidate_headings.index(heading)
        end = radar.find(candidate_headings[idx + 1], start + len(heading)) if idx + 1 < len(candidate_headings) else radar.find("### Что сознательно", start)
        item = radar[start:end if end >= 0 else len(radar)]
        require(item, "Первый шаг:")
    assert candidate_numbers == list(range(1, len(candidate_numbers) + 1)), "product radar numbering is not sequential"

    history = text[text.index("## История последних рабочих заходов — последние 5 PR"):text.index("## Product radar — идеи с реальной ценностью")]
    prs = re.findall(r"\*\*(#\d+):\*\*", history)
    assert len(prs) == 5, f"expected exactly 5 individually-listed PRs, found {len(prs)}"
    assert len(set(prs)) == 5, "duplicate PR entry in recent history"
    pr_numbers = [int(pr[1:]) for pr in prs]
    assert pr_numbers == sorted(pr_numbers), "recent PR history should be listed in chronological order"

    require(text, "python3 tools/verify_handoff.py")
    print("NEXT_AGENT_HANDOFF verifier: PASS")

if __name__ == "__main__":
    main()