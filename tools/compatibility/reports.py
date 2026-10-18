"""Bounded, fail-closed report/request validation. No shell execution or remote code loading."""
import hashlib
import json
import re

CASES = {f"{kind}_{cols}" for kind in ("f32", "f16", "q5_K", "q6_K") for cols in (1, 8)}
HASH = re.compile(r"[0-9a-f]{64}\Z")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def integer(value, low=0, high=2**63 - 1):
    return type(value) is int and low <= value <= high


def text(value, limit=200):
    return isinstance(value, str) and len(value) <= limit and all(ord(c) >= 32 for c in value)


def digest(value):
    return isinstance(value, str) and HASH.fullmatch(value) is not None


def load(value):
    require(isinstance(value, str) and len(value.encode("utf-8")) <= 65536, "Report exceeds 64 KiB")
    def reject(value):
        raise ValueError("Non-finite JSON number")
    def pairs(items):
        obj = {}
        for key, value in items:
            require(key not in obj, "Duplicate JSON key")
            obj[key] = value
        return obj
    return json.loads(value, parse_constant=reject, object_pairs_hook=pairs)


def fenced(body):
    require(isinstance(body, str) and len(body.encode("utf-8")) <= 65536, "Issue body exceeds limit")
    blocks = re.findall(r"```json\s*\n(.*?)\n```", body, re.S)
    require(len(blocks) == 1, "Expected one generated JSON report")
    return load(blocks[0])


def capabilities(caps):
    require(type(caps) is dict and set(caps) <= {"page_bytes", "memory_total_kib", "cpu", "devices"}, "Unknown capability field")
    require(integer(caps.get("page_bytes"), 1, 65536), "Invalid page size")
    require(integer(caps.get("memory_total_kib"), -1), "Invalid memory size")
    cpu = caps.get("cpu", {})
    require(type(cpu) is dict and set(cpu) == {"abi", "online_cores", "dotprod", "fp16_vector"}, "Invalid CPU capabilities")
    require(cpu["abi"] == "arm64-v8a" and integer(cpu["online_cores"], 1, 1024), "Invalid CPU description")
    require(type(cpu["dotprod"]) is bool and type(cpu["fp16_vector"]) is bool, "Invalid CPU features")
    devices = caps.get("devices", [])
    require(type(devices) is list and len(devices) <= 8, "Invalid GPU list")
    numbers = {"vendor_id", "device_id", "api_version", "driver_version", "max_storage_buffer_bytes", "max_allocations", "max_workgroup_invocations"}
    booleans = {"compute_queue", "shader_int16", "extended_features_queried", "storage_buffer_16bit", "shader_float16"}
    for device in devices:
        require(type(device) is dict and set(device) == numbers | booleans | {"name", "memory_heaps"}, "Invalid GPU capabilities")
        require(text(device["name"], 256), "Invalid GPU name")
        require(all(integer(device[k], 0, 2**32 - 1) for k in numbers), "Invalid GPU limit")
        require(all(type(device[k]) is bool for k in booleans), "Invalid GPU feature")
        heaps = device["memory_heaps"]
        require(type(heaps) is list and len(heaps) <= 16, "Invalid GPU memory heaps")
        for heap in heaps:
            require(type(heap) is dict and set(heap) == {"size_bytes", "device_local"}, "Invalid heap")
            require(integer(heap["size_bytes"]) and type(heap["device_local"]) is bool, "Invalid heap size")


def validate(report):
    allowed = {"schema", "suite_version", "suite", "driver", "app_version", "package", "manufacturer", "model",
               "android_api", "firmware", "apk_sha256", "native_sha256", "request_id", "issue", "scope",
               "probe_status", "probe_exit", "capabilities", "device_key", "status", "reason", "tests",
               "process_status", "exit_code", "last_started", "native_errors"}
    require(type(report) is dict and set(report) <= allowed, "Unexpected report field (possibly personal data)")
    require(integer(report.get("schema"), 1, 1) and integer(report.get("suite_version"), 1, 1), "Unsupported report version")
    require(report.get("suite") in {"math", "capabilities"}, "Invalid suite")
    require(report.get("driver") in {"system", "turnip", "cpu"}, "Invalid driver")
    require(report.get("package") in {"ai.jackknife.killayue", "ai.jackknife.killayue.compatlab"}, "Unknown app")
    for field in ("app_version", "manufacturer", "model", "firmware", "scope", "reason"):
        require(text(report.get(field, "")), "Invalid report text")
    require(integer(report.get("android_api"), 28, 100), "Unsupported Android API")
    require(digest(report.get("apk_sha256")), "Missing APK digest")
    hashes = report.get("native_sha256", {})
    natives = {"libcompatprobe.so", "libcompatmath.so", "libkyggml.so", "libkyggml-base.so", "libkyvulkan.so", "libturnip.so"}
    require(type(hashes) is dict and set(hashes) <= natives and len(hashes) >= 5, "Missing native build identities")
    require(all(digest(value) for value in hashes.values()), "Invalid native digest")
    require(report.get("probe_status") in {"finished", "cancelled", "timeout", "output_limit"}, "Invalid probe status")
    require(integer(report.get("probe_exit"), -1, 255), "Invalid probe exit")
    if "capabilities" in report:
        capabilities(report["capabilities"])
        require(digest(report.get("device_key")), "Missing device configuration digest")
    require(report.get("status") in {"math_pass", "math_failed", "capabilities_only", "unavailable", "deferred", "incomplete"}, "Invalid report status")
    request_id = report.get("request_id", "")
    require(isinstance(request_id, str) and re.fullmatch(r"[A-Za-z0-9_-]{0,64}", request_id), "Invalid request ID")
    require(integer(report.get("issue"), 0, 2**31 - 1), "Invalid source issue")
    require(bool(request_id) == bool(report["issue"]), "Incomplete request identity")
    require(report.get("last_started", "") in CASES | {""}, "Unknown active test")
    errors = report.get("native_errors", [])
    codes = {"VK_ERROR_DEVICE_LOST", "VK_ERROR_OUT_OF_HOST_MEMORY", "VK_ERROR_OUT_OF_DEVICE_MEMORY",
             "VK_ERROR_INITIALIZATION_FAILED", "VK_ERROR_FEATURE_NOT_PRESENT", "VK_ERROR_EXTENSION_NOT_PRESENT",
             "VK_ERROR_INCOMPATIBLE_DRIVER", "VK_ERROR_UNKNOWN"}
    require(type(errors) is list and len(errors) <= 8 and all(code in codes for code in errors), "Unknown diagnostic error code")
    tests = report.get("tests", [])
    require(type(tests) is list and len(tests) <= 8, "Invalid test list")
    seen = set()
    for test in tests:
        require(type(test) is dict and set(test) <= {"test", "status", "finite", "max_error"}, "Invalid test fields")
        name = test.get("test")
        require(name in CASES | {"backend"} and name not in seen, "Unknown or duplicate test")
        seen.add(name)
        require(test.get("status") in {"pass", "fail", "unsupported", "unavailable"}, "Invalid result")
        if "finite" in test:
            require(type(test["finite"]) is bool, "Invalid finite flag")
        if "max_error" in test:
            require(type(test["max_error"]) in (int, float) and 0 <= test["max_error"] <= 1e30, "Invalid numerical error")
    if report["status"] == "math_pass":
        require(report["suite"] == "math" and seen == CASES and all(t.get("status") == "pass" and t.get("finite") is True for t in tests), "Incomplete math pass")
        require(report.get("exit_code") == 0 and report.get("process_status") == "finished", "Unfinished process cannot pass")
        require(report["probe_status"] == "finished" and report["probe_exit"] == 0 and "capabilities" in report, "Missing completed probe")
        if report["driver"] != "cpu":
            require(bool(report["capabilities"].get("devices")), "GPU math pass needs an enumerated GPU")
    return report


def make_request(baseline, issue, candidate, request_id):
    validate(baseline)
    require(digest(baseline.get("device_key")), "Need a completed capability report before requesting a retest")
    require(digest(candidate) and candidate != baseline["apk_sha256"], "Candidate must be a different, identified APK")
    require(integer(issue, 1, 2**31 - 1), "Invalid issue number")
    require(isinstance(request_id, str) and re.fullmatch(r"[A-Za-z0-9_-]{1,64}", request_id), "Invalid request ID")
    return {"schema": 1, "suite_version": 1, "suite": "math", "driver": baseline["driver"],
            "request_id": request_id, "issue": issue, "candidate_apk_sha256": candidate, "device_key": baseline["device_key"]}


def retest(request, report):
    validate(report)
    for field in ("schema", "suite_version", "suite", "driver", "request_id", "issue", "device_key"):
        require(report.get(field) == request.get(field), f"Retest mismatch: {field}")
    require(report["apk_sha256"] == request.get("candidate_apk_sha256"), "Retest ran a different APK")
    require(report["package"] == "ai.jackknife.killayue.compatlab", "Retest must come from the separate beta app")
    return "targeted_pass" if report["status"] == "math_pass" else "needs_followup"
