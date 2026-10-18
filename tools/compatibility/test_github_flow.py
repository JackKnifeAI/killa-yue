import json
import unittest
from unittest.mock import patch
import github_flow as flow
from reports import make_request
from test_reports import example


class Workflow(unittest.TestCase):
    def setup_flow(self, status="math_pass"):
        baseline = example()
        request = make_request(baseline, 42, "d" * 64, "run-42")
        result = example()
        result.update(apk_sha256="d" * 64, request_id="run-42", issue=42, status=status)
        if status == "incomplete": result.update(process_status="timeout", tests=[])
        record = {"request": request, "pr": 7, "commit": "e" * 40, "baseline_apk_sha256": "a" * 64}
        comment = {"user": {"login": "github-actions[bot]"},
                   "body": flow.REQUEST_MARKER + "\n```json\n" + json.dumps(record) + "\n```"}
        return result, comment

    def exercise(self, result, comment, sha="e" * 40):
        calls = []
        def api(path, method="GET", data=None):
            calls.append((path, method, data))
            if path == "pulls/7": return {"head": {"sha": sha}}
            return {}
        with patch.object(flow, "issue", return_value={"body": "```json\n" + json.dumps(result) + "\n```"}), \
             patch.object(flow, "comments", return_value=[comment]), patch.object(flow, "api", side_effect=api), \
             patch.object(flow, "comment"), patch.object(flow, "label"):
            flow.triage(99)
        return [data for path, method, data in calls if path.startswith("statuses/")]

    def test_matching_retest_records_only_targeted_status(self):
        result, comment = self.setup_flow()
        statuses = self.exercise(result, comment)
        self.assertEqual(len(statuses), 1)
        self.assertEqual(statuses[0]["state"], "success")
        self.assertEqual(statuses[0]["context"], "compatibility/run-42")

    def test_incomplete_retest_requires_followup(self):
        result, comment = self.setup_flow("incomplete")
        self.assertEqual(self.exercise(result, comment)[0]["state"], "failure")

    def test_stale_pr_is_rejected(self):
        result, comment = self.setup_flow()
        with self.assertRaises(ValueError): self.exercise(result, comment, "f" * 40)

    def test_user_cannot_forge_workflow_request(self):
        result, comment = self.setup_flow()
        comment["user"]["login"] = "random-user"
        with self.assertRaises(ValueError): self.exercise(result, comment)

    def test_wrong_apk_cannot_update_status(self):
        result, comment = self.setup_flow()
        result["apk_sha256"] = "a" * 64
        with self.assertRaises(ValueError): self.exercise(result, comment)

if __name__ == "__main__": unittest.main()
