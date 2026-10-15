//! Tries the research tools from a terminal:
//!   scout search "insulin price hikes"
//!   scout read https://example.com/article "insulin prices" [budget]
//!   scout history nso.com site
use serde_json::json;

fn main() {
    let a: Vec<String> = std::env::args().collect();
    let out = match a.get(1).map(String::as_str) {
        Some("search") if a.len() > 2 => killascout::search(&json!({"query": a[2..].join(" ")})),
        Some("history") if a.len() > 2 => killascout::history(&json!({
            "url": a[2],
            "scope": a.get(3).cloned().unwrap_or_else(|| "page".into()),
        })),
        Some("read") if a.len() > 2 => killascout::read(&json!({
            "url": a[2],
            "topic": a.get(3).cloned().unwrap_or_default(),
            "budget": a.get(4).and_then(|b| b.parse::<u64>().ok()).unwrap_or(3000),
        })),
        _ => {
            eprintln!("usage: scout search QUERY | scout read URL [TOPIC] [BUDGET] | scout history URL [page|site|prefix]");
            std::process::exit(2);
        }
    };
    println!("{}", serde_json::to_string_pretty(&out).unwrap());
}
