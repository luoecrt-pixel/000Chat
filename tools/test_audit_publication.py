import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "tools" / "audit_publication.py"

class PublicationAuditorTests(unittest.TestCase):
    def test_deleted_historical_secret_is_reported_without_value(self):
        with tempfile.TemporaryDirectory() as td:
            path = Path(td)
            def git(*args):
                subprocess.run(["git", "-C", td, *args], check=True,
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            git("init", "-q")
            git("config", "user.name", "Example Tester")
            git("config", "user.email", "sensitive@example.com")
            (path / ".env").write_text("OPENAI_API_KEY=sk-proj-" + "A"*40 + "\n")
            git("add", ".env")
            git("commit", "-qm", "secret")
            (path / ".env").unlink()
            (path / "hello.txt").write_text("safe public example")
            git("add", "-A")
            git("commit", "-qm", "clean head")
            result = subprocess.run([sys.executable, str(SCRIPT), td], capture_output=True, text=True)
            self.assertEqual(result.returncode, 2)
            report = json.loads(result.stdout)
            self.assertTrue(report["secret_match_count"])
            self.assertIn(".env", report["sensitive_paths"])
            self.assertNotIn("sk-proj-" + "A"*40, result.stdout)
            self.assertNotIn("sensitive@example.com", result.stdout)

    def test_clean_noreply_repository_still_requires_external_review(self):
        with tempfile.TemporaryDirectory() as td:
            path = Path(td)
            def git(*args):
                subprocess.run(["git", "-C", td, *args], check=True,
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            git("init", "-q")
            git("config", "user.name", "Tester")
            git("config", "user.email", "12345+tester@users.noreply.github.com")
            (path / "hello.txt").write_text("hello")
            git("add", "hello.txt")
            git("commit", "-qm", "initial")
            result = subprocess.run([sys.executable, str(SCRIPT), td], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            report = json.loads(result.stdout)
            self.assertEqual(report["secret_match_count"], 0)
            self.assertEqual(report["publication_gate"], "INCOMPLETE_EXTERNAL_REVIEW")

    def test_bare_mirror(self):
        with tempfile.TemporaryDirectory() as td:
            repo = Path(td) / "work"
            repo.mkdir()
            def git(*args):
                subprocess.run(["git", "-C", str(repo), *args], check=True,
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            git("init", "-q")
            git("config", "user.name", "Tester")
            git("config", "user.email", "12345+tester@users.noreply.github.com")
            (repo / "file.txt").write_text("sample")
            git("add", "file.txt")
            git("commit", "-qm", "first")
            bare = Path(td) / "mirror.git"
            subprocess.run(["git", "clone", "-q", "--mirror", str(repo), str(bare)], check=True)
            result = subprocess.run([sys.executable, str(SCRIPT), str(bare)],
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(json.loads(result.stdout)["commits_scanned"], 1)

if __name__ == "__main__":
    unittest.main()
