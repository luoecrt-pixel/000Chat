import importlib.util
from datetime import datetime, timedelta, timezone
from pathlib import Path
import unittest

SOURCE = Path(__file__).with_name("prune_actions_artifacts.py")
spec = importlib.util.spec_from_file_location("prune_actions_artifacts", SOURCE)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


class CleanupTests(unittest.TestCase):
    now = datetime(2026, 10, 11, 17, 0, tzinfo=timezone.utc)

    def artifact(self, ident, age_hours, name="OpenAIApiChat-Astra-debug", expired=False):
        stamp = self.now - timedelta(hours=age_hours)
        return {"id": ident, "name": name, "expired": expired,
                "created_at": stamp.isoformat().replace("+00:00", "Z"),
                "size_in_bytes": 5000000}

    def test_keep_four_latest_even_if_all_old(self):
        items = [self.artifact(i, i * 10 + 30) for i in range(1, 11)]
        remove, protected = mod.plan(items, self.now)
        self.assertEqual([a["id"] for a in remove], [10, 9, 8, 7, 6, 5])
        self.assertEqual(protected["OpenAIApiChat-Astra-debug"], [1, 2, 3, 4])

    def test_recent_and_unknown_artifacts_protected(self):
        items = [self.artifact(i, 8 + i) for i in range(1, 9)]
        items += [self.artifact(99, 1000, "user-unknown")]
        remove, _ = mod.plan(items, self.now)
        self.assertEqual(remove, [])

    def test_each_name_has_separate_protected_set(self):
        items = [self.artifact(i, 30 + i) for i in range(1, 9)]
        items += [self.artifact(i + 100, 30 + i, "OpenAIApiChat-Astra-release") for i in range(1, 9)]
        remove, protected = mod.plan(items, self.now)
        self.assertEqual(len(remove), 8)
        self.assertEqual(len(protected), 2)
        self.assertEqual(len(protected["OpenAIApiChat-Astra-debug"]), 4)

    def test_expired_artifacts_ignored(self):
        items = [self.artifact(i, 100 + i) for i in range(1, 10)]
        items[0]["expired"] = True
        remove, _ = mod.plan(items, self.now)
        self.assertEqual(len(remove), 4)
        self.assertNotIn(1, [x["id"] for x in remove])

    def test_refuse_unsafe_window_and_malformed_records(self):
        with self.assertRaises(ValueError):
            mod.plan([], self.now, keep_latest=1)
        with self.assertRaises(ValueError):
            mod.plan([], self.now, min_age_hours=1)
        with self.assertRaises(ValueError):
            mod.plan([{"id": "abc", "name": "OpenAIApiChat-Astra-debug", "created_at": "2026-10-08T00:00:00Z"}], self.now)


if __name__ == "__main__":
    unittest.main()
