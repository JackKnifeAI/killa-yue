use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{
    fs::File,
    io::{self, Read},
    path::Path,
};

pub fn hash(path: &Path) -> io::Result<String> {
    let mut file = File::open(path)?;
    if file.metadata()?.len() > 256 * 1024 * 1024 {
        return Err(io::Error::other("diagnostic binary exceeds 256 MiB limit"));
    }
    let mut hash = Sha256::new();
    let mut buf = [0; 16384];
    loop {
        let n = file.read(&mut buf)?;
        if n == 0 {
            break;
        }
        hash.update(&buf[..n]);
    }
    Ok(format!("{:x}", hash.finalize()))
}

pub fn collect(exe: &Path, engine: Option<&Path>) -> io::Result<Value> {
    let mut files = serde_json::Map::new();
    files.insert("killa-desktop".into(), json!(hash(exe)?));
    if let Some(engine) = engine {
        files.insert(
            "engine/compatibility-test".into(),
            json!(hash(&engine.join("compatibility-test"))?),
        );
        let mut entries: Vec<_> =
            std::fs::read_dir(engine.join("lib"))?.collect::<Result<_, _>>()?;
        entries.sort_by_key(|e| e.file_name());
        if entries.len() > 64 {
            return Err(io::Error::other("engine bundle has too many files"));
        }
        for entry in entries {
            if !entry.path().is_file() {
                return Err(io::Error::other("engine lib must contain only files"));
            }
            let name = entry.file_name().to_string_lossy().into_owned();
            files.insert(format!("engine/lib/{name}"), json!(hash(&entry.path())?));
        }
    }
    Ok(
        json!({"sha256": files, "note": "Hashes identify the local binaries inspected before this run, not a signature or trust decision. System driver binaries are not hashed; driver versions appear in the inventory. No binaries are uploaded."}),
    )
}
