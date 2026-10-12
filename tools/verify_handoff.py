from datetime import datetime
from pathlib import Path
import re
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[1]
HANDOFF = ROOT / "NEXT_AGENT_HANDOFF.md"
SECTIONS = (
    "## Текущий статус",
    "## Следующая работа",
    "## Обязательные ограничения",
    "## Проверки и CI",
    "## Renderer: происхождение и SHA",
)


def validate(text: str) -> None:
    lines = text.splitlines()
    assert lines and lines[0] == "# NEXT_AGENT_HANDOFF", "invalid handoff title"
    headings = [line for line in lines if line.startswith("#")]
    assert headings == ["# NEXT_AGENT_HANDOFF", *SECTIONS], "missing, duplicate or obsolete section"
    for label in ("Last updated", "Следующий рабочий срез", "Code SHA", "Main baseline"):
        assert text.count(f"**{label}:**") == 1, f"missing/duplicate {label}"
    updated = re.search(r"^\*\*Last updated:\*\* (.+)$", text, re.M)
    assert updated is not None
    datetime.strptime(updated.group(1), "%Y-%m-%d %H:%M %z")
    for label in ("Code SHA", "Main baseline"):
        assert re.search(rf"^\*\*{label}:\*\* \x60[0-9a-f]{{40}}\x60", text, re.M), f"invalid {label}"
    next_step = re.search(r"^\*\*Следующий рабочий срез:\*\* (.+)$", text, re.M)
    assert next_step and next_step.group(1).strip(), "empty next step"
    assert text.count("\x60") % 2 == 0, "unbalanced code delimiters"
    for index, line in enumerate(lines):
        if line.startswith("#") and index:
            assert not lines[index - 1].strip(), "heading needs blank line"
            assert index + 1 < len(lines) and not lines[index + 1].strip(), "heading needs blank line"

    # These remain operational requirements; old PR histories/radars are not required.
    for token in ("Stop", "CAS", "fingerprint v1", "1h", "5h", "5M", "40 минут",
                  "платные API", "телефон", "unsigned", "Verify"):
        assert token in text, f"lost operational requirement: {token}"
    assert "docs/audits/2026-10-12-openai-followup.md" in text, "missing current task source"
    properties = (ROOT / "config/response-renderer-sha256.properties").read_text()
    for line in properties.splitlines():
        if line.strip() and not line.lstrip().startswith("#"):
            digest = line.split("=", 1)[1].strip()
            assert re.fullmatch(r"[0-9a-f]{64}", digest), "invalid renderer digest"
            assert digest in text, "renderer provenance lost from handoff"

    for target in re.findall(r"\[[^\]]*\]\(([^)]+)\)", text):
        parsed = urlsplit(target)
        if parsed.scheme or not parsed.path:
            continue
        path = (ROOT / unquote(parsed.path)).resolve()
        assert path.is_relative_to(ROOT) and path.is_file(), f"broken local link: {target}"


def mutation_checks(text: str) -> None:
    validate(text)
    mutations = [
        ("invalid code identity", text.replace("**Code SHA:** \x60", "**Code SHA:** \x60short", 1)),
        ("lost replay contract", text.replace("fingerprint v1", "legacy fingerprint")),
        ("missing task source", text.replace("docs/audits/2026-10-12-openai-followup.md", "docs/missing.md")),
        ("duplicate baseline", text + "\n**Main baseline:** \x60" + "a" * 40 + "\x60\n"),
        ("lost request boundary", text.replace("5h", "unlimited request")),
        ("broken source link", text.replace("](config/response-renderer-sha256.properties)", "](config/missing.properties)")),
    ]
    for name, changed in mutations:
        assert changed != text, f"ineffective mutation: {name}"
        try:
            validate(changed)
        except (AssertionError, ValueError):
            pass
        else:
            raise AssertionError(f"mutation incorrectly accepted: {name}")


def main() -> None:
    text = HANDOFF.read_text(encoding="utf-8")
    mutation_checks(text)
    print("NEXT_AGENT_HANDOFF verifier: PASS (positive + 6 semantic mutations)")


if __name__ == "__main__":
    main()
