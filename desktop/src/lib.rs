pub mod inventory;
pub mod math;
pub mod provenance;
pub mod runner;
pub mod vulkan;

pub const SCHEMA: u32 = 1;
pub const TEST_REVISION: &str = "desktop-lab-v1";

// A diagnosis is evidence, never permission to accept remote work or to change
// the music engine's backend. Those require a separate consent and engine path.
pub fn capabilities() -> serde_json::Value {
    serde_json::json!({
        "schema": SCHEMA,
        "remote_generation": false,
        "remote_diagnostics": false,
        "network_listener": false,
        "automatic_upload": false,
        "engine_selection_changed": false,
        "accepted_remote_job_types": [],
        "reason": "Local diagnostic preview. Full generation, model validation and remote job consent are not implemented."
    })
}
