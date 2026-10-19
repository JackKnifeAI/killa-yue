use killa_desktop::{
    inventory, math,
    runner::{self, Session},
    vulkan,
};
use serde_json::{json, Value};
use std::{
    path::PathBuf,
    process::Command,
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc,
    },
    time::Duration,
};

const HELP: &str = "Killa Desktop Lab (local preview)\n\n  killa-desktop diagnose --output NEW_DIRECTORY [--vulkan-compute] [--engine-dir TRUSTED_LOCAL_BUNDLE]\n  killa-desktop cancel REPORT_DIRECTORY\n  killa-desktop capabilities\n\nDiagnosis collects CPU/RAM/OS and Vulkan driver details, then runs a tiny CPU test.\n--vulkan-compute also runs a tiny F32 shader on each exposed Vulkan adapter (up to 8).\n--engine-dir runs the fixed GGML CPU fixture from a locally built bundle; with\n--vulkan-compute it also tests GGML Vulkan0. Only use a bundle you trust: it is native code.\nEvery probe is a separate process with a 15-second deadline (GGML: 30 seconds).\nCtrl+C or cancel stops the queue; existing logs and completed results remain.\nNo models downloaded, network connection, upload, remote job listener or engine setting change.\nA smoke-test pass does not certify song generation. New report directory required.\n";

fn main() {
    if let Err(error) = run() {
        eprintln!("Desktop Lab: {error}");
        std::process::exit(2);
    }
}

fn run() -> Result<(), Box<dyn std::error::Error>> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    match args.first().map(String::as_str) {
        None | Some("--help" | "help") => {
            print!("{HELP}");
            return Ok(());
        }
        Some("capabilities") if args.len() == 1 => {
            println!(
                "{}",
                serde_json::to_string_pretty(&killa_desktop::capabilities())?
            );
            return Ok(());
        }
        Some("cancel") if args.len() == 2 => {
            let dir = PathBuf::from(&args[1]);
            if !dir.join("events.jsonl").is_file() {
                return Err("not a Desktop Lab report directory".into());
            }
            if !dir.join("CANCEL").exists() {
                runner::private_file(&dir.join("CANCEL"))?.sync_all()?;
            }
            println!(
                "Cancellation requested; completed results remain in {}",
                dir.display()
            );
            return Ok(());
        }
        // Private fixed probe vocabulary, not a general command runner.
        Some("__probe") if args.len() >= 2 => {
            let value = match (args[1].as_str(), args.len()) {
                ("inventory", 2) => inventory::collect(),
                ("cpu", 2) => math::cpu(),
                ("vulkan-inventory", 2) => vulkan::inventory(),
                ("vulkan-matrix", 3) => vulkan::matrix(args[2].parse()?),
                _ => return Err("unknown internal probe".into()),
            };
            println!("{value}");
            return Ok(());
        }
        Some("diagnose") => {}
        _ => return Err(HELP.into()),
    }
    let mut output = None;
    let mut engine = None;
    let mut compute = false;
    let mut i = 1;
    while i < args.len() {
        match args[i].as_str() {
            "--output" if output.is_none() && i + 1 < args.len() => {
                i += 1;
                output = Some(PathBuf::from(&args[i]));
            }
            "--engine-dir" if engine.is_none() && i + 1 < args.len() => {
                i += 1;
                engine = Some(PathBuf::from(&args[i]).canonicalize()?);
            }
            "--vulkan-compute" if !compute => compute = true,
            _ => {
                return Err(format!("unknown, duplicate or incomplete option: {}", args[i]).into())
            }
        }
        i += 1;
    }
    let output = output.ok_or("--output NEW_DIRECTORY is required")?;
    if let Some(dir) = &engine {
        if !dir.join("compatibility-test").is_file() || !dir.join("lib").is_dir() {
            return Err(
                "engine bundle needs compatibility-test and lib/ (see desktop/README.md)".into(),
            );
        }
    }
    let cancel = Arc::new(AtomicBool::new(false));
    let handler = cancel.clone();
    ctrlc::set_handler(move || handler.store(true, Ordering::SeqCst))?;
    let exe = std::env::current_exe()?;
    let provenance = killa_desktop::provenance::collect(&exe, engine.as_deref())?;
    let mut session = Session::create(&output, cancel)?;
    runner::write_json(&output.join("provenance.json"), &provenance)?;
    for (name, probe) in [
        ("hardware", "inventory"),
        ("cpu-matrix", "cpu"),
        ("vulkan-inventory", "vulkan-inventory"),
    ] {
        eprintln!("Checking {name}…");
        session.run(
            name,
            Command::new(&exe).args(["__probe", probe]),
            Duration::from_secs(15),
            false,
        )?;
    }
    let devices: Vec<Value> = session
        .results
        .iter()
        .find(|r| r.name == "vulkan-inventory" && r.outcome == "passed")
        .and_then(|r| r.data["devices"].as_array().cloned())
        .unwrap_or_default();
    if compute {
        for (index, expected) in devices.iter().enumerate().take(8) {
            if session.cancelled() {
                break;
            }
            let name = format!("vulkan-matrix-{index}");
            eprintln!("Checking {name} ({})…", expected["name"]);
            session.run(
                &name,
                Command::new(&exe).args(["__probe", "vulkan-matrix", &index.to_string()]),
                Duration::from_secs(15),
                false,
            )?;
            // Enumeration order can change between processes; each compute
            // result reports its own identity. Never transfer a pass to another
            // adapter based on its index in the earlier inventory.
        }
    }
    if let Some(engine) = engine {
        for (name, backend) in [("ggml-cpu", "CPU"), ("ggml-vulkan0", "Vulkan0")] {
            if backend != "CPU" && !compute {
                continue;
            }
            if session.cancelled() {
                break;
            }
            eprintln!("Checking {name}…");
            let mut command = Command::new(engine.join("compatibility-test"));
            command
                .arg(engine.join("lib"))
                .arg(backend)
                .current_dir(&engine);
            session.run(name, &mut command, Duration::from_secs(30), true)?;
        }
    }
    let report = session.finish()?;
    let summary = json!({"session_status": report["session_status"], "report": output.join("report.json"), "generation_compatibility": report["generation_compatibility"], "jobs": report["jobs"].as_array().unwrap().iter().map(|r| json!({"name": r["name"], "outcome": r["outcome"]})).collect::<Vec<_>>()});
    println!("{}", serde_json::to_string_pretty(&summary)?);
    Ok(())
}
