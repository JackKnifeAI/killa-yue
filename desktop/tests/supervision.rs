// Exercise real processes, pipes and disk journals. The shell here is a test
// fixture only; the application never exposes a command-string job.
#![cfg(unix)]
use killa_desktop::runner::{Session, LOG_LIMIT};
use std::{
    path::PathBuf,
    process::Command,
    sync::{
        atomic::{AtomicBool, AtomicU64, Ordering},
        Arc,
    },
    time::{Duration, Instant},
};

static NEXT: AtomicU64 = AtomicU64::new(0);
struct Temp(PathBuf);
impl Temp {
    fn new() -> Self {
        Self(std::env::temp_dir().join(format!(
            "killa-lab-test-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::SeqCst)
        )))
    }
}
impl Drop for Temp {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}
fn shell(script: &str) -> Command {
    let mut command = Command::new("/bin/sh");
    command.args(["-c", script]);
    command
}

#[test]
fn timeout_leaves_a_finished_record_and_bounded_duration() {
    let temp = Temp::new();
    let mut session = Session::create(&temp.0, Arc::new(AtomicBool::new(false))).unwrap();
    let started = Instant::now();
    session
        .run(
            "hang",
            &mut shell("printf 'partial driver log'; sleep 60"),
            Duration::from_millis(150),
            false,
        )
        .unwrap();
    assert!(started.elapsed() < Duration::from_secs(4));
    assert_eq!(session.results[0].outcome, "timed_out");
    let report = session.finish().unwrap();
    assert_eq!(report["generation_compatibility"], "not_tested");
    assert_eq!(
        std::fs::read_to_string(temp.0.join("hang.stdout.log")).unwrap(),
        "partial driver log"
    );
    let journal = std::fs::read_to_string(temp.0.join("events.jsonl")).unwrap();
    assert!(journal.contains("job_started") && journal.contains("job_finished"));
}

#[test]
fn malformed_crashed_and_noisy_children_never_pass() {
    let temp = Temp::new();
    let mut session = Session::create(&temp.0, Arc::new(AtomicBool::new(false))).unwrap();
    session
        .run(
            "invalid",
            &mut shell("printf '{}'"),
            Duration::from_secs(2),
            false,
        )
        .unwrap();
    session
        .run(
            "crash",
            &mut shell("kill -SEGV $$"),
            Duration::from_secs(2),
            false,
        )
        .unwrap();
    session
        .run(
            "flood",
            &mut shell("head -c 1048576 /dev/zero; head -c 1048576 /dev/zero >&2"),
            Duration::from_secs(4),
            false,
        )
        .unwrap();
    assert_eq!(session.results[0].outcome, "invalid_output");
    assert_eq!(session.results[1].outcome, "failed");
    assert_eq!(session.results[2].outcome, "invalid_output");
    assert!(session.results[2].stdout_truncated && session.results[2].stderr_truncated);
    for stream in ["stdout", "stderr"] {
        assert_eq!(
            std::fs::metadata(temp.0.join(format!("flood.{stream}.log")))
                .unwrap()
                .len(),
            LOG_LIMIT as u64
        );
    }
}

#[test]
fn cancellation_stops_active_job_and_queued_jobs() {
    let temp = Temp::new();
    let cancel = Arc::new(AtomicBool::new(false));
    let mut session = Session::create(&temp.0, cancel.clone()).unwrap();
    let thread = std::thread::spawn(move || {
        std::thread::sleep(Duration::from_millis(100));
        cancel.store(true, Ordering::SeqCst);
    });
    session
        .run(
            "active",
            &mut shell("sleep 60"),
            Duration::from_secs(30),
            false,
        )
        .unwrap();
    thread.join().unwrap();
    session
        .run(
            "queued",
            &mut shell("exit 0"),
            Duration::from_secs(2),
            false,
        )
        .unwrap();
    assert_eq!(session.results.len(), 1);
    assert_eq!(session.results[0].outcome, "cancelled");
    assert_eq!(session.finish().unwrap()["session_status"], "cancelled");
    assert!(!temp.0.join("queued.stdout.log").exists());
}

#[test]
fn cancellation_file_and_no_overwrite_and_private_permissions() {
    use std::os::unix::fs::PermissionsExt;
    let temp = Temp::new();
    let mut session = Session::create(&temp.0, Arc::new(AtomicBool::new(false))).unwrap();
    assert!(Session::create(&temp.0, Arc::new(AtomicBool::new(false))).is_err());
    assert_eq!(
        std::fs::metadata(&temp.0).unwrap().permissions().mode() & 0o777,
        0o700
    );
    assert_eq!(
        std::fs::metadata(temp.0.join("events.jsonl"))
            .unwrap()
            .permissions()
            .mode()
            & 0o777,
        0o600
    );
    std::fs::write(temp.0.join("CANCEL"), b"").unwrap();
    session
        .run("never", &mut shell("exit 1"), Duration::from_secs(1), false)
        .unwrap();
    assert!(session.results.is_empty());
    assert_eq!(session.finish().unwrap()["session_status"], "cancelled");
}

#[test]
fn zero_exit_is_insufficient_but_valid_success_is_recorded() {
    let temp = Temp::new();
    let mut session = Session::create(&temp.0, Arc::new(AtomicBool::new(false))).unwrap();
    session
        .run("empty", &mut shell("exit 0"), Duration::from_secs(2), false)
        .unwrap();
    session
        .run(
            "valid",
            &mut shell("printf '{\"status\":\"pass\"}'"),
            Duration::from_secs(2),
            false,
        )
        .unwrap();
    assert_eq!(session.results[0].outcome, "invalid_output");
    assert_eq!(session.results[1].outcome, "passed");
    assert!(
        !session.finish().unwrap()["capabilities"]["remote_generation"]
            .as_bool()
            .unwrap()
    );
}
