from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
HANDOFF = ROOT / "NEXT_AGENT_HANDOFF.md"

text = HANDOFF.read_text(encoding="utf-8")
lines = text.splitlines()

assert lines and lines[0] == "# NEXT_AGENT_HANDOFF"
assert re.match(r"\*\*Last updated:\*\* \d{4}-\d{2}-\d{2} \d{2}:\d{2} [+-]\d{2}:\d{2}", lines[2])
assert lines[3].strip().startswith("**Следующий рабочий срез:**")

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
for section in required_sections:
    assert text.count(section) == 1, f"section count mismatch: {section}"

assert "### 1. Response/recovery controller — срез завершён" not in text
assert "### Что сознательно не тянуть сейчас" in text
assert "execution surface" in text
assert "- Полный Compose rewrite — инфраструктурная стадия, не пользовательская функция." in text
assert "- «AI сам организует чаты/саммари/теги»" in text
assert "- Синхронизация между устройствами — отдельная security/backend задача" in text

fork = text.index("### 7. Fork / ветвление чата")
not_now = text.index("### Что сознательно не тянуть сейчас")
assert fork < not_now

assert text.count("`") % 2 == 0, "unbalanced backticks"

for i, line in enumerate(lines):
    if line.startswith("#"):
        if i > 0:
            assert lines[i - 1].strip() == "", f"heading without blank line before: {line}"
        j = i + 1
        while j < len(lines) and not lines[j].strip():
            j += 1
        assert j < len(lines), f"heading without content: {line}"
        assert not lines[j].startswith("#"), f"heading without content: {line}"

for line in lines:
    assert "exeatId" not in line

print("NEXT_AGENT_HANDOFF verifier: PASS")
