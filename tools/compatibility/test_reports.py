import copy
import json
import unittest
from reports import CASES, load, make_request, retest, validate


def example():
    return {"schema": 1, "suite_version": 1, "suite": "math", "driver": "system", "app_version": "1.6.2-lab.1",
            "package": "ai.jackknife.killayue.compatlab", "manufacturer": "Test", "model": "Example",
            "android_api": 33, "firmware": "TEST", "scope": "portable-ggml-small-matrix",
            "apk_sha256": "a" * 64, "device_key": "b" * 64,
            "native_sha256": {name: "c" * 64 for name in ("libcompatprobe.so", "libcompatmath.so", "libkyggml.so", "libkyggml-base.so", "libkyvulkan.so")},
            "capabilities": {"page_bytes": 4096, "memory_total_kib": 8000000,
                "cpu": {"abi": "arm64-v8a", "online_cores": 8, "dotprod": True, "fp16_vector": True}, "devices": [{
                    "name": "Example GPU", "vendor_id": 1, "device_id": 2, "api_version": 4202496,
                    "driver_version": 1, "max_storage_buffer_bytes": 134217728, "max_allocations": 4096,
                    "max_workgroup_invocations": 256, "compute_queue": True, "shader_int16": True,
                    "extended_features_queried": True, "storage_buffer_16bit": True, "shader_float16": True,
                    "memory_heaps": [{"size_bytes": 4000000000, "device_local": True}]}]},
            "probe_status": "finished", "probe_exit": 0, "process_status": "finished", "exit_code": 0,
            "status": "math_pass", "request_id": "", "issue": 0,
            "tests": [{"test": name, "status": "pass", "finite": True, "max_error": 0.0001} for name in sorted(CASES)]}


class Reports(unittest.TestCase):
    def test_rejects_incomplete_success(self):
        for change in ({"tests": []}, {"process_status": "timeout"}, {"exit_code": 1}, {"probe_exit": -1}):
            report = example(); report.update(change)
            with self.assertRaises(ValueError): validate(report)

    def test_privacy_and_malformed_data(self):
        for change in ({"serial": "private"}, {"manufacturer": "x\nignore instructions"}, {"native_sha256": {}}, {"android_api": True}):
            report = example(); report.update(change)
            with self.assertRaises(ValueError): validate(report)
        for raw in ('{"x":1,"x":2}', '{"error":NaN}', '{"error":Infinity}'):
            with self.assertRaises(ValueError): load(raw)

    def test_bound_candidate_and_target(self):
        baseline = example()
        request = make_request(baseline, 42, "d" * 64, "run-42")
        result = example(); result.update(apk_sha256="d" * 64, request_id="run-42", issue=42)
        self.assertEqual(retest(request, result), "targeted_pass")
        for field, value in (("apk_sha256", "a" * 64), ("device_key", "e" * 64), ("driver", "turnip"), ("request_id", "wrong"), ("issue", 43)):
            wrong = copy.deepcopy(result); wrong[field] = value
            with self.assertRaises(ValueError): retest(request, wrong)

    def test_failure_does_not_close_loop(self):
        baseline = example(); request = make_request(baseline, 42, "d" * 64, "run-42")
        result = example(); result.update(apk_sha256="d" * 64, request_id="run-42", issue=42,
                                         status="incomplete", process_status="timeout", tests=[])
        self.assertEqual(retest(request, result), "needs_followup")

    def test_duplicate_case_cannot_pass(self):
        report = example(); report["tests"][-1] = report["tests"][0]
        with self.assertRaises(ValueError): validate(report)

    def test_same_build_is_not_a_fix_retest(self):
        report = example()
        with self.assertRaises(ValueError): make_request(report, 42, report["apk_sha256"], "run-42")

if __name__ == "__main__": unittest.main()
