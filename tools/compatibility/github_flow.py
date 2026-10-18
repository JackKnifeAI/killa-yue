"""Trusted workflow glue. Issue text is parsed as data, never interpolated into code."""
import argparse
import hashlib
import json
import os
import pathlib
import re
import subprocess
import urllib.error
import urllib.parse
import urllib.request
from reports import fenced, load, make_request, require, retest, validate

REPO = os.environ.get("GITHUB_REPOSITORY", "JackKnifeAI/killa-yue")
require(re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", REPO), "Invalid repository")
REQUEST_MARKER = "<!-- killa-compatibility-retest-request-v1 -->"
TRIAGE_MARKER = "<!-- killa-compatibility-triage-v1 -->"


def api(path, method="GET", data=None):
    request = urllib.request.Request("https://api.github.com/repos/" + REPO + "/" + path,
        method=method, data=None if data is None else json.dumps(data).encode(),
        headers={"Authorization": "Bearer " + os.environ["GH_TOKEN"], "Accept": "application/vnd.github+json",
                 "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "killa-compatibility-workflow"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def issue(number):
    require(0 < number < 2**31, "Invalid issue")
    item = api(f"issues/{number}")
    require("pull_request" not in item, "Expected an issue, not a PR")
    return item


def comments(number):
    for page in range(1, 11):
        batch = api(f"issues/{number}/comments?per_page=100&page={page}")
        yield from batch
        if len(batch) < 100:
            return
    raise ValueError("Too many comments; maintainer review required")


def comment(number, body, marker=None):
    if marker:
        for item in comments(number):
            if item["user"]["login"] == "github-actions[bot]" and item["body"].startswith(marker):
                return api(f"issues/comments/{item['id']}", "PATCH", {"body": body})
    return api(f"issues/{number}/comments", "POST", {"body": body})


def label(number, name):
    try:
        api("labels", "POST", {"name": name, "color": "5319e7"})
    except urllib.error.HTTPError as error:
        if error.code != 422:
            raise
    api(f"issues/{number}/labels", "POST", {"labels": [name]})


def prepare(number, output):
    report = validate(fenced(issue(number)["body"]))
    pathlib.Path(output).write_text(json.dumps(report, indent=2) + "\n")


def pending_request(number, candidate, url, pr_number, request_id, output):
    baseline = validate(fenced(issue(number)["body"]))
    pr = api(f"pulls/{pr_number}")
    require(pr["state"] == "open" and pr["head"]["repo"]["full_name"] == REPO, "Candidate must be an open PR in this repository")
    sha = pr["head"]["sha"]
    require(re.fullmatch(r"[0-9a-f]{40}", sha), "Invalid commit")
    checks = api(f"commits/{sha}/check-runs?per_page=100")["check_runs"]
    require(checks and all(c["status"] == "completed" and c["conclusion"] in {"success", "skipped", "neutral"} for c in checks),
            "Candidate CI has not passed")
    required_checks = {"App compiles", "Nothing that must not be committed", "HAMMER encrypted peers and native audio", "Mobile patch stack and context safety"}
    require(required_checks <= {c["name"] for c in checks if c["conclusion"] == "success"}, "Required candidate CI checks are missing")
    # Restrict APK distribution to this repository's release assets. No arbitrary download URL.
    parsed = urllib.parse.urlparse(url)
    require(parsed.scheme == "https" and parsed.netloc == "github.com" and
            parsed.path.startswith("/" + REPO + "/releases/download/") and parsed.path.endswith(".apk") and
            not parsed.query and not parsed.fragment, "Use this repository's release APK URL")
    digest = hashlib.sha256(); total = 0
    with urllib.request.urlopen(url, timeout=30) as response:
        while True:
            chunk = response.read(65536)
            if not chunk: break
            total += len(chunk); require(total <= 200 * 1024 * 1024, "Candidate APK exceeds size limit")
            digest.update(chunk)
    require(digest.hexdigest() == candidate, "Downloaded APK does not match the specified SHA-256")
    request = make_request(baseline, number, candidate, request_id)
    pathlib.Path(output).write_text(json.dumps(request, indent=2) + "\n")
    record = {"request": request, "pr": pr_number, "commit": sha, "baseline_apk_sha256": baseline["apk_sha256"]}
    run_url = os.environ["GITHUB_SERVER_URL"] + "/" + REPO + "/actions/runs/" + os.environ["GITHUB_RUN_ID"]
    body = REQUEST_MARKER + "\nA targeted retest is ready. This does not grant remote access to your phone.\n\n"
    body += f"1. Review PR #{pr_number} at `{sha}`.\n2. Install the [candidate Compatibility Lab APK]({url}).\n"
    body += f"3. Download `retest-request.json` from this [workflow run's retest-request artifact]({run_url}).\n"
    body += "4. In Compatibility Lab choose **Open a retest request**, review it and run it.\n"
    body += "5. Use **Review and open GitHub** to return the report.\n\n"
    body += "The request checks the installed APK hash and device configuration. A targeted pass does not certify full songs.\n\n"
    body += "```json\n" + json.dumps(record, indent=2) + "\n```"
    comment(number, body)
    label(number, "compatibility-retest-requested")
    api(f"statuses/{sha}", "POST", {"state": "pending", "context": "compatibility/" + request_id,
        "description": "Awaiting volunteer retest of the identified candidate APK", "target_url": run_url})


def triage(number):
    item = issue(number)
    try:
        report = validate(fenced(item["body"]))
    except (ValueError, TypeError, KeyError) as error:
        comment(number, TRIAGE_MARKER + "\nThis report needs attention: " + str(error)
                + ". Export a fresh report from Settings or Compatibility Lab. Do not paste general engine logs.", TRIAGE_MARKER)
        return
    label(number, "compatibility")
    message = "Report format validated. Reported outcome: `" + report["status"] + "`. This is tester-supplied evidence, not device certification."
    if report["request_id"]:
        record = None
        for item in comments(report["issue"]):
            if item["user"]["login"] != "github-actions[bot]" or not item["body"].startswith(REQUEST_MARKER):
                continue
            candidate = fenced(item["body"])
            if candidate.get("request", {}).get("request_id") == report["request_id"]:
                require(record is None, "Duplicate retest request; maintainer review required")
                record = candidate
        require(record is not None, "No matching maintainer-issued retest request")
        outcome = retest(record["request"], report)
        sha = record["commit"]
        require(re.fullmatch(r"[0-9a-f]{40}", sha), "Invalid candidate commit")
        current_pr = api(f"pulls/{record['pr']}")
        require(current_pr["head"]["sha"] == sha, "The PR changed after this test request; issue a new request")
        state = "success" if outcome == "targeted_pass" else "failure"
        label(number, "compatibility-targeted-pass" if state == "success" else "compatibility-needs-followup")
        if state != "success": label(report["issue"], "compatibility-needs-followup")
        message += f"\n\nRetest `{report['request_id']}` matched its request: **{outcome}** for PR #{record['pr']} at `{sha}`."
        api(f"statuses/{sha}", "POST", {"state": state, "context": "compatibility/" + report["request_id"],
            "description": "Volunteer reports targeted math pass" if state == "success" else "Retest failed/incomplete; follow-up required",
            "target_url": f"https://github.com/{REPO}/issues/{number}"})
        marker = "<!-- killa-compatibility-return-" + report["request_id"] + " -->"
        comment(report["issue"], marker + f"\nRetest result: #{number}. {outcome} for `{report['request_id']}`. "
                "Human review and any full-generation testing remain required.", marker)
    comment(number, TRIAGE_MARKER + "\n" + message, TRIAGE_MARKER)


def apply_proposal(patch):
    # A fresh job applies only source/test/doc changes, never proposed CI or credential changes.
    raw = pathlib.Path(patch).read_bytes()
    require(len(raw) <= 2 * 1024 * 1024 and b"GIT binary patch" not in raw, "Invalid/oversized proposal")
    subprocess.run(["git", "apply", "--check", "--", patch], check=True)
    listing = subprocess.check_output(["git", "apply", "--numstat", "--", patch], text=True)
    require(bool(listing.strip()), "No code changes proposed")
    for row in listing.splitlines():
        added, removed, path = row.split("\t", 2)
        require(added.isdigit() and removed.isdigit(), "Binary patches are not accepted")
        require(".." not in pathlib.PurePosixPath(path).parts and not path.startswith("/"), "Unsafe path")
        require((path.startswith(("app/src/", "app/test/")) and path.endswith(".java")) or
                (path.startswith("engine/") and path.endswith((".cpp", ".h", ".patch"))) or
                (path.startswith("docs/") and path.endswith(".md")), "Proposal modifies a protected path")
    # Reject symlink modes even on otherwise permitted paths.
    require(not re.search(rb"(?:new file mode|new mode) 120000", raw), "Symlinks are not accepted")
    subprocess.run(["git", "apply", "--", patch], check=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=["prepare", "triage", "request", "apply-proposal"])
    parser.add_argument("--issue", type=int)
    parser.add_argument("--output", default="compatibility-report.json")
    parser.add_argument("--candidate"); parser.add_argument("--url"); parser.add_argument("--pr", type=int)
    parser.add_argument("--request-id"); parser.add_argument("--patch")
    args = parser.parse_args()
    if args.mode == "prepare": prepare(args.issue, args.output)
    elif args.mode == "triage": triage(args.issue)
    elif args.mode == "request": pending_request(args.issue, args.candidate, args.url, args.pr, args.request_id, args.output)
    else: apply_proposal(args.patch)

if __name__ == "__main__": main()
