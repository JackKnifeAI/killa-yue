use serde_json::{json, Value};
use sysinfo::System;

pub fn collect() -> Value {
    // Never refresh processes, disks, users or networks: only the fields below
    // belong in a compatibility report. Do not use System::new_all().
    let mut system = System::new();
    system.refresh_cpu_list(sysinfo::CpuRefreshKind::nothing());
    system.refresh_memory();
    let mut features = Vec::<&str>::new();
    #[cfg(any(target_arch = "x86", target_arch = "x86_64"))]
    {
        if std::is_x86_feature_detected!("sse2") {
            features.push("sse2");
        }
        if std::is_x86_feature_detected!("avx") {
            features.push("avx");
        }
        if std::is_x86_feature_detected!("avx2") {
            features.push("avx2");
        }
        if std::is_x86_feature_detected!("fma") {
            features.push("fma");
        }
        if std::is_x86_feature_detected!("f16c") {
            features.push("f16c");
        }
    }
    #[cfg(target_arch = "aarch64")]
    {
        if std::arch::is_aarch64_feature_detected!("neon") {
            features.push("neon");
        }
        if std::arch::is_aarch64_feature_detected!("dotprod") {
            features.push("dotprod");
        }
        if std::arch::is_aarch64_feature_detected!("fp16") {
            features.push("fp16");
        }
    }
    json!({
        "status": "pass",
        "os": std::env::consts::OS,
        "architecture": std::env::consts::ARCH,
        "os_version": System::os_version(),
        "kernel_version": System::kernel_version(),
        "cpu_brand": system.cpus().first().map(|cpu| cpu.brand()),
        "logical_cpus": system.cpus().len(),
        "available_parallelism": std::thread::available_parallelism().ok().map(|n| n.get()),
        "cpu_features": features,
        "host_memory_total_bytes": system.total_memory(),
        "host_memory_available_bytes": system.available_memory(),
        "linux_display_devices": display_devices(),
        "note": "Host RAM is not a job budget; container/service limits and other workloads may leave less memory. No serials, UUIDs, hostnames, accounts, network interfaces or process lists are collected."
    })
}

// Retain useful GPU evidence even when there is no functioning Vulkan ICD.
// PCI vendor/device numbers identify a model, not an individual device.
#[cfg(target_os = "linux")]
fn display_devices() -> Vec<Value> {
    let mut result = Vec::new();
    if let Ok(entries) = std::fs::read_dir("/sys/class/drm") {
        let mut entries: Vec<_> = entries.flatten().collect();
        entries.sort_by_key(|e| e.file_name());
        for entry in entries {
            let name = entry.file_name();
            let name = name.to_string_lossy();
            if !name
                .strip_prefix("card")
                .is_some_and(|s| !s.is_empty() && s.bytes().all(|b| b.is_ascii_digit()))
            {
                continue;
            }
            let device = entry.path().join("device");
            let read = |file| {
                std::fs::read_to_string(device.join(file))
                    .ok()
                    .map(|v| v.trim().chars().take(128).collect::<String>())
            };
            let driver = std::fs::read_link(device.join("driver"))
                .ok()
                .and_then(|p| p.file_name().map(|s| s.to_string_lossy().into_owned()));
            result.push(json!({"pci_vendor": read("vendor"), "pci_device": read("device"), "kernel_driver": driver}));
            if result.len() >= 16 {
                break;
            }
        }
    }
    result
}

#[cfg(not(target_os = "linux"))]
fn display_devices() -> Vec<Value> {
    Vec::new()
}
