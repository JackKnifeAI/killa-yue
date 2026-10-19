use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{
    fs::{File, OpenOptions},
    io::{self, Read, Write},
    path::{Path, PathBuf},
    process::{Child, Command, Stdio},
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc,
    },
    thread,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

pub const LOG_LIMIT: usize = 64 * 1024;

#[derive(Debug, Serialize, Deserialize)]
pub struct JobResult {
    pub name: String,
    pub outcome: String,
    pub elapsed_ms: u128,
    pub exit_code: Option<i32>,
    pub stdout_truncated: bool,
    pub stderr_truncated: bool,
    pub data: Value,
}

pub struct Session {
    pub directory: PathBuf,
    pub results: Vec<JobResult>,
    events: File,
    pub cancel: Arc<AtomicBool>,
}

pub fn private_file(path: &Path) -> io::Result<File> {
    let mut options = OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    options.open(path)
}

pub fn write_json(path: &Path, value: &Value) -> io::Result<()> {
    let mut file = private_file(path)?;
    serde_json::to_writer_pretty(&mut file, value)?;
    file.write_all(b"\n")?;
    file.sync_all()
}

impl Session {
    pub fn create(directory: &Path, cancel: Arc<AtomicBool>) -> io::Result<Self> {
        let mut builder = std::fs::DirBuilder::new();
        #[cfg(unix)]
        {
            use std::os::unix::fs::DirBuilderExt;
            builder.mode(0o700);
        }
        // A new directory per run prevents overwriting previous evidence and
        // makes two invocations with the same destination fail safely.
        builder.create(directory)?;
        let events = private_file(&directory.join("events.jsonl"))?;
        let mut session = Self {
            directory: directory.to_path_buf(),
            results: vec![],
            events,
            cancel,
        };
        session.event(json!({"event": "session_started", "schema": crate::SCHEMA, "test_revision": crate::TEST_REVISION}))?;
        Ok(session)
    }

    pub fn cancelled(&self) -> bool {
        self.cancel.load(Ordering::SeqCst) || self.directory.join("CANCEL").exists()
    }

    fn event(&mut self, mut value: Value) -> io::Result<()> {
        value["unix_ms"] = json!(SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis());
        serde_json::to_writer(&mut self.events, &value)?;
        self.events.write_all(b"\n")?;
        self.events.sync_data()
    }

    pub fn run(
        &mut self,
        name: &str,
        command: &mut Command,
        deadline: Duration,
        ggml: bool,
    ) -> io::Result<()> {
        if name.is_empty() || !name.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-') {
            return Err(io::Error::other("invalid internal job name"));
        }
        if self.cancelled() {
            return Ok(());
        }
        let stdout = private_file(&self.directory.join(format!("{name}.stdout.log")))?;
        let stderr = private_file(&self.directory.join(format!("{name}.stderr.log")))?;
        self.event(
            json!({"event": "job_started", "job": name, "timeout_ms": deadline.as_millis()}),
        )?;
        command
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());
        // Fixed local executables only. No shell, remote command or URL enters
        // this path. Separate groups let cancellation stop helper descendants.
        #[cfg(unix)]
        {
            use std::os::unix::process::CommandExt;
            command.process_group(0);
        }
        #[cfg(target_os = "linux")]
        {
            use std::os::unix::process::CommandExt;
            let parent = std::process::id();
            unsafe {
                command.pre_exec(move || {
                    if libc::prctl(libc::PR_SET_PDEATHSIG, libc::SIGKILL) != 0 {
                        return Err(io::Error::last_os_error());
                    }
                    if libc::getppid() as u32 != parent {
                        return Err(io::Error::from_raw_os_error(libc::ECHILD));
                    }
                    let limit = libc::rlimit {
                        rlim_cur: 0,
                        rlim_max: 0,
                    };
                    if libc::setrlimit(libc::RLIMIT_CORE, &limit) != 0 {
                        return Err(io::Error::last_os_error());
                    }
                    Ok(())
                });
            }
        }
        let started = Instant::now();
        let spawned = command.spawn();
        let result = match spawned {
            Err(e) => JobResult {
                name: name.into(),
                outcome: "spawn_failed".into(),
                elapsed_ms: started.elapsed().as_millis(),
                exit_code: None,
                stdout_truncated: false,
                stderr_truncated: false,
                data: json!({"reason": e.to_string()}),
            },
            Ok(child) => self.monitor(name, child, (stdout, stderr), deadline, started, ggml)?,
        };
        write_json(
            &self.directory.join(format!("{name}.json")),
            &serde_json::to_value(&result)?,
        )?;
        self.event(json!({"event": "job_finished", "job": name, "outcome": result.outcome}))?;
        self.results.push(result);
        Ok(())
    }

    fn monitor(
        &self,
        name: &str,
        mut child: Child,
        logs: (File, File),
        deadline: Duration,
        started: Instant,
        ggml: bool,
    ) -> io::Result<JobResult> {
        let (stdout, stderr) = logs;
        let out = capture(child.stdout.take().unwrap(), stdout);
        let err = capture(child.stderr.take().unwrap(), stderr);
        let mut outcome = "completed";
        let status = loop {
            if self.cancelled() {
                outcome = "cancelled";
                terminate(&mut child);
                break child.wait()?;
            }
            if started.elapsed() >= deadline {
                outcome = "timed_out";
                terminate(&mut child);
                break child.wait()?;
            }
            match child.try_wait() {
                Ok(Some(status)) => break status,
                Ok(None) => thread::sleep(Duration::from_millis(20)),
                Err(e) => {
                    terminate(&mut child);
                    let _ = child.wait();
                    return Err(e);
                }
            }
        };
        // A descendant must not keep our pipe readers alive after the job exits.
        #[cfg(unix)]
        unsafe {
            libc::kill(-(child.id() as i32), libc::SIGKILL);
        }
        // Capture threads have a bounded result wait even on a hostile driver
        // helper that inherited a Windows pipe. This is not a security sandbox.
        let out = out.recv_timeout(Duration::from_secs(5));
        let err = err.recv_timeout(Duration::from_secs(5));
        let capture_failed = !matches!(out, Ok(Ok(_))) || !matches!(err, Ok(Ok(_)));
        let (bytes, stdout_truncated) = out.ok().and_then(Result::ok).unwrap_or((vec![], true));
        let (_, stderr_truncated) = err.ok().and_then(Result::ok).unwrap_or((vec![], true));
        let data = if ggml {
            parse_ggml(&bytes)
        } else {
            serde_json::from_slice::<Value>(&bytes)
                .unwrap_or_else(|_| json!({"status": "invalid_output"}))
        };
        if outcome == "completed" {
            outcome = if capture_failed {
                "capture_failed"
            } else if stdout_truncated {
                "invalid_output"
            } else if !status.success() {
                if ggml && status.code() == Some(2) && data["status"] == "unavailable" {
                    "unavailable"
                } else {
                    "failed"
                }
            } else {
                match data["status"].as_str() {
                    Some("pass") => "passed",
                    Some("unavailable") => "unavailable",
                    Some("fail") => "failed",
                    _ => "invalid_output",
                }
            };
        }
        Ok(JobResult {
            name: name.into(),
            outcome: outcome.into(),
            elapsed_ms: started.elapsed().as_millis(),
            exit_code: status.code(),
            stdout_truncated,
            stderr_truncated,
            data,
        })
    }

    pub fn finish(mut self) -> io::Result<Value> {
        let cancelled = self.cancelled();
        let report = json!({
            "schema": crate::SCHEMA, "tool_version": env!("CARGO_PKG_VERSION"), "test_revision": crate::TEST_REVISION,
            "session_status": if cancelled { "cancelled" } else { "completed" },
            "generation_compatibility": "not_tested",
            "capabilities": crate::capabilities(), "jobs": self.results,
            "privacy": "Local files only. report.json contains hardware models, OS/driver versions and test results. Raw logs can contain driver paths; review before manually sharing. Nothing is uploaded."
        });
        write_json(&self.directory.join("report.json"), &report)?;
        self.event(json!({"event": "session_finished", "cancelled": cancelled}))?;
        Ok(report)
    }
}

fn terminate(child: &mut Child) {
    #[cfg(unix)]
    unsafe {
        libc::kill(-(child.id() as i32), libc::SIGKILL);
    }
    let _ = child.kill();
}

type Captured = io::Result<(Vec<u8>, bool)>;
fn capture<R: Read + Send + 'static>(
    mut pipe: R,
    mut file: File,
) -> std::sync::mpsc::Receiver<Captured> {
    let (tx, rx) = std::sync::mpsc::channel();
    thread::spawn(move || {
        let result = (|| {
            let mut kept = Vec::new();
            let mut truncated = false;
            let mut buf = [0u8; 4096];
            loop {
                let n = pipe.read(&mut buf)?;
                if n == 0 {
                    break;
                }
                let take = n.min(LOG_LIMIT - kept.len());
                truncated |= take < n;
                if take > 0 {
                    file.write_all(&buf[..take])?;
                    // File is unbuffered: make partial logs visible immediately.
                    // fsync per 4 KiB can stall pipe draining on slow disks and
                    // falsely time out healthy children. Journal/results use
                    // fsync; raw logs use the OS cache (may lose a tail on power loss).
                    file.flush()?;
                    kept.extend_from_slice(&buf[..take]);
                }
            }
            Ok((kept, truncated))
        })();
        let _ = tx.send(result);
    });
    rx
}

// Share the exact phone Lab fixture. Merely exiting zero, printing one pass,
// or silently falling back does not establish that all eight cases ran.
pub fn parse_ggml(bytes: &[u8]) -> Value {
    let mut finished = std::collections::BTreeSet::new();
    let mut started = std::collections::BTreeSet::new();
    let mut records = Vec::new();
    for line in String::from_utf8_lossy(bytes).lines() {
        if let Some(line) = line.strip_prefix("KYTEST ") {
            let Ok(value) = serde_json::from_str::<Value>(line) else {
                return json!({"status": "invalid_output"});
            };
            let test = value["test"].as_str().unwrap_or_default().to_string();
            match value["status"].as_str() {
                Some("started") if started.insert(test.clone()) => {}
                Some("pass")
                    if started.contains(&test)
                        && value["finite"] == true
                        && finished.insert(test.clone()) => {}
                Some("unavailable") => return json!({"status": "unavailable", "records": [value]}),
                _ => return json!({"status": "fail", "records": [value]}),
            }
            records.push(value);
        }
    }
    let expected: std::collections::BTreeSet<_> = ["f32", "f16", "q5_K", "q6_K"]
        .into_iter()
        .flat_map(|kind| [1, 8].map(move |n| format!("{kind}_{n}")))
        .collect();
    json!({"status": if started == expected && finished == expected { "pass" } else { "fail" }, "records": records,
        "note": "Fixed GGML matrices only; no model loading, full-song or thermal qualification."})
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn rejects_empty_and_incomplete_engine_success() {
        assert_eq!(parse_ggml(b"")["status"], "fail");
        assert_eq!(
            parse_ggml(b"KYTEST {\"test\":\"f32_1\",\"status\":\"pass\",\"finite\":true}\n")
                ["status"],
            "fail"
        );
    }
    #[test]
    fn complete_engine_suite_and_duplicates() {
        let mut text = String::new();
        for kind in ["f32", "f16", "q5_K", "q6_K"] {
            for n in [1, 8] {
                text.push_str(&format!("KYTEST {{\"test\":\"{kind}_{n}\",\"status\":\"started\"}}\nKYTEST {{\"test\":\"{kind}_{n}\",\"status\":\"pass\",\"finite\":true}}\n"));
            }
        }
        assert_eq!(parse_ggml(text.as_bytes())["status"], "pass");
        text.push_str("KYTEST {\"test\":\"f32_1\",\"status\":\"pass\",\"finite\":true}\n");
        assert_eq!(parse_ggml(text.as_bytes())["status"], "fail");
    }
}
