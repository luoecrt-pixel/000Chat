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
        assert not lines[j].startswith("#"), f"heading without content: {line}"

    assert text.count("`") % 2 == 0, "unbalanced inline backticks"
    assert "exeatId" not in text, "known fork-radar corruption detected"
    require(text, "### 7. Fork / ветвление чата")

    active = text[text.index("## АКТИВНЫЙ ПЛАН — только незавершённое"):text.index("## Репозиторий / branches")]
    assert "Response/recovery controller — срез завершён" not in active
    require(active, "### 1. Attachment/upload/composer controller — **следующий**")
    for phrase in ["Regression tests", "Executor/Worker → Activity", "retention/clear policy diagnostics", "minification/shrinker", "bounded screen-state surface", "AGP/Kotlin/compileSdk drift"]:
        require(active, phrase)

    # One trigger for architecture/product review; START HERE is the canonical rule.
    assert text.count("каждые 5 merged PR или раньше") == 1

    radar = text[text.index("## Product radar — идеи с реальной ценностью"):text.index("## Документация")]
    assert radar.count("### ") == 7, "product radar should contain exactly seven candidate ideas"
    for phrase in [
        "MCP/custom functions и Computer Use",
        "Полный Compose rewrite",
        "«AI сам организует чаты/саммари/теги»",
        "Синхронизация между устройствами",
    ]:
        require(radar, phrase)
    assert radar.count("Первый шаг:") >= 7

    history = text[text.index("## История последних рабочих заходов — последние 5 PR"):text.index("## Product radar — идеи с реальной ценностью")]
    for pr in ["#38", "#39", "#40", "#41", "#42"]:
        require(history, f"**{pr}:**")
    for pr in ["#19", "#20", "#21", "#22", "#23", "#24", "#25", "#26", "#27", "#28", "#29", "#30", "#31", "#32", "#33", "#34"]:
        assert f"**{pr}:**" not in history

    require(text, "python3 tools/verify_handoff.py")
    print("NEXT_AGENT_HANDOFF verifier: PASS")

if __name__ == "__main__":
    main()