//! Killa YuE's research tools, used by the songwriter when it looks a subject up before writing.
//!
//! Everything runs on the phone and goes straight to the sites: several search engines are asked at
//! once and their answers merged (as SearXNG does, without a server in between), pages are fetched and
//! reduced to their article text, and the paragraphs that matter for the song's subject are picked
//! to fit the model's context. Results are JSON, for the app's Java side (see `android` below).

use scraper::{ElementRef, Html, Selector};
use serde_json::{Value, json};
use std::collections::HashMap;
use std::time::Duration;

const UA: &str = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0 Mobile Safari/537.36";
const MAX_PAGE: u64 = 4 << 20;

fn agent() -> ureq::Agent {
    agent_for(20)
}

/// Search engines get a short limit: one slow or blocking engine must never hold up the others.
fn agent_for(secs: u64) -> ureq::Agent {
    ureq::AgentBuilder::new()
        .timeout_connect(Duration::from_secs(secs.min(8)))
        .timeout(Duration::from_secs(secs))
        .user_agent(UA)
        .redirects(6)
        .build()
}

/// A results page that is really a "prove you are human" page.
fn blocked(html: &str) -> bool {
    let l = html.to_lowercase();
    (l.contains("captcha") || l.contains("unusual traffic") || l.contains("are you a robot")) && html.len() < 60_000
}

fn body(resp: ureq::Response) -> Result<String, String> {
    use std::io::Read;
    let mut buf = Vec::new();
    resp.into_reader().take(MAX_PAGE).read_to_end(&mut buf).map_err(|e| e.to_string())?;
    Ok(String::from_utf8_lossy(&buf).into_owned())
}

fn sel(s: &str) -> Selector {
    Selector::parse(s).expect("selector")
}

fn text_of(e: ElementRef) -> String {
    squash(&e.text().collect::<Vec<_>>().join(" "))
}

fn squash(s: &str) -> String {
    s.split_whitespace().collect::<Vec<_>>().join(" ")
}

// ---------------------------------------------------------------- search

/// One search result as an engine gave it.
#[derive(Clone)]
pub struct Hit {
    pub title: String,
    pub url: String,
    pub snippet: String,
    pub engine: &'static str,
    pub rank: usize,
}

fn duckduckgo(a: &ureq::Agent, q: &str) -> Result<Vec<Hit>, String> {
    let r = a.post("https://html.duckduckgo.com/html/").send_form(&[("q", q), ("kl", "wt-wt")]).map_err(|e| e.to_string())?;
    let page = body(r)?;
    if blocked(&page) {
        return Err("blocked (asked for a captcha)".into());
    }
    let doc = Html::parse_document(&page);
    let (res, link, snip) = (sel(".result"), sel("a.result__a"), sel(".result__snippet"));
    let mut out = Vec::new();
    for r in doc.select(&res) {
        let Some(l) = r.select(&link).next() else { continue };
        let mut href = l.value().attr("href").unwrap_or("").to_string();
        // results link through DuckDuckGo's redirect: the real address is in uddg=
        if let Ok(u) = url::Url::parse(&if href.starts_with("//") { format!("https:{href}") } else { href.clone() }) {
            if let Some((_, v)) = u.query_pairs().find(|(k, _)| k == "uddg") {
                href = v.into_owned();
            }
        }
        if !href.starts_with("http") || href.contains("duckduckgo.com/y.js") {
            continue; // ads
        }
        let snippet = r.select(&snip).next().map(text_of).unwrap_or_default();
        out.push(Hit { title: text_of(l), url: href, snippet, engine: "duckduckgo", rank: out.len() });
    }
    Ok(out)
}

fn mojeek(a: &ureq::Agent, q: &str) -> Result<Vec<Hit>, String> {
    let r = a.get("https://www.mojeek.com/search").query("q", q).call().map_err(|e| e.to_string())?;
    let page = body(r)?;
    if blocked(&page) {
        return Err("blocked (asked for a captcha)".into());
    }
    let doc = Html::parse_document(&page);
    let (res, link, snip) = (sel("ul.results-standard > li"), sel("h2 a"), sel("p.s"));
    let mut out = Vec::new();
    for r in doc.select(&res) {
        let Some(l) = r.select(&link).next() else { continue };
        let href = l.value().attr("href").unwrap_or("").to_string();
        if !href.starts_with("http") {
            continue;
        }
        let snippet = r.select(&snip).next().map(text_of).unwrap_or_default();
        out.push(Hit { title: text_of(l), url: href, snippet, engine: "mojeek", rank: out.len() });
    }
    Ok(out)
}

fn marginalia(a: &ureq::Agent, q: &str) -> Result<Vec<Hit>, String> {
    let u = format!("https://api.marginalia.nu/public/search/{}", url::form_urlencoded::byte_serialize(q.as_bytes()).collect::<String>());
    let r = a.get(&u).query("count", "10").call().map_err(|e| e.to_string())?;
    let v: Value = serde_json::from_str(&body(r)?).map_err(|e| e.to_string())?;
    let mut out = Vec::new();
    for h in v["results"].as_array().into_iter().flatten() {
        let href = h["url"].as_str().unwrap_or("").to_string();
        if href.starts_with("http") {
            out.push(Hit {
                title: squash(h["title"].as_str().unwrap_or("")),
                url: href,
                snippet: squash(h["description"].as_str().unwrap_or("")),
                engine: "marginalia",
                rank: out.len(),
            });
        }
    }
    Ok(out)
}

fn wikipedia(a: &ureq::Agent, q: &str, lang: &str) -> Result<Vec<Hit>, String> {
    let base = format!("https://{lang}.wikipedia.org");
    let r = a
        .get(&format!("{base}/w/api.php"))
        .query("action", "query")
        .query("list", "search")
        .query("srsearch", q)
        .query("srlimit", "5")
        .query("format", "json")
        .call()
        .map_err(|e| e.to_string())?;
    let v: Value = serde_json::from_str(&body(r)?).map_err(|e| e.to_string())?;
    let mut out = Vec::new();
    for h in v["query"]["search"].as_array().into_iter().flatten() {
        let title = h["title"].as_str().unwrap_or("").to_string();
        let snippet = Html::parse_fragment(h["snippet"].as_str().unwrap_or(""));
        out.push(Hit {
            url: format!("{base}/wiki/{}", title.replace(' ', "_")),
            title,
            snippet: squash(&snippet.root_element().text().collect::<String>()),
            engine: "wikipedia",
            rank: out.len(),
        });
    }
    Ok(out)
}

fn brave(a: &ureq::Agent, q: &str, key: &str) -> Result<Vec<Hit>, String> {
    let r = a
        .get("https://api.search.brave.com/res/v1/web/search")
        .query("q", q)
        .query("count", "10")
        .set("Accept", "application/json")
        .set("X-Subscription-Token", key)
        .call()
        .map_err(|e| e.to_string())?;
    let v: Value = serde_json::from_str(&body(r)?).map_err(|e| e.to_string())?;
    let mut out = Vec::new();
    for h in v["web"]["results"].as_array().into_iter().flatten() {
        out.push(Hit {
            title: squash(h["title"].as_str().unwrap_or("")),
            url: h["url"].as_str().unwrap_or("").to_string(),
            snippet: squash(&Html::parse_fragment(h["description"].as_str().unwrap_or("")).root_element().text().collect::<String>()),
            engine: "brave",
            rank: out.len(),
        });
    }
    Ok(out)
}

/// The same page under different addresses (http/https, www., trailing slash, tracking tags) counts once.
fn url_key(u: &str) -> String {
    match url::Url::parse(u) {
        Ok(p) => {
            let host = p.host_str().unwrap_or("").trim_start_matches("www.").to_lowercase();
            let keep: Vec<String> = p
                .query_pairs()
                .filter(|(k, _)| !k.starts_with("utm_") && k != "fbclid" && k != "gclid" && k != "ref")
                .map(|(k, v)| format!("{k}={v}"))
                .collect();
            format!("{host}{}?{}", p.path().trim_end_matches('/'), keep.join("&"))
        }
        Err(_) => u.to_string(),
    }
}

/// Asks the engines in parallel and merges their lists by reciprocal rank fusion: a page several
/// engines rank high comes first. `spec` is {"query", "max"?, "engines"?: [...], "brave_key"?, "lang"?}.
pub fn search(spec: &Value) -> Value {
    let q = spec["query"].as_str().unwrap_or("").trim().to_string();
    if q.is_empty() {
        return json!({"error": "no query"});
    }
    let max = spec["max"].as_u64().unwrap_or(8) as usize;
    let lang = spec["lang"].as_str().unwrap_or("en").to_string();
    let key = spec["brave_key"].as_str().unwrap_or("").to_string();
    let mut engines: Vec<String> = match spec["engines"].as_array() {
        Some(a) => a.iter().filter_map(|e| e.as_str().map(String::from)).collect(),
        None => vec!["duckduckgo".into(), "mojeek".into(), "marginalia".into(), "wikipedia".into()],
    };
    if !key.is_empty() && !engines.iter().any(|e| e == "brave") {
        engines.push("brave".into());
    }
    let a = agent_for(8);
    let results: Vec<(String, Result<Vec<Hit>, String>)> = std::thread::scope(|s| {
        let jobs: Vec<_> = engines
            .iter()
            .map(|e| {
                let (a, q, e, lang, key) = (a.clone(), q.clone(), e.clone(), lang.clone(), key.clone());
                s.spawn(move || {
                    let r = match e.as_str() {
                        "duckduckgo" => duckduckgo(&a, &q),
                        "mojeek" => mojeek(&a, &q),
                        "marginalia" => marginalia(&a, &q),
                        "wikipedia" => wikipedia(&a, &q, &lang),
                        "brave" => brave(&a, &q, &key),
                        other => Err(format!("unknown engine {other}")),
                    };
                    (e, r)
                })
            })
            .collect();
        jobs.into_iter().map(|j| j.join().unwrap_or_else(|_| ("?".into(), Err("engine crashed".into())))).collect()
    });

    let mut score: HashMap<String, (f64, Hit, Vec<&'static str>)> = HashMap::new();
    let mut status = serde_json::Map::new();
    for (e, r) in &results {
        match r {
            Ok(hits) => {
                status.insert(e.clone(), json!(hits.len()));
                for h in hits {
                    let k = url_key(&h.url);
                    let entry = score.entry(k).or_insert_with(|| (0.0, h.clone(), Vec::new()));
                    entry.0 += 1.0 / (60.0 + h.rank as f64);
                    entry.2.push(h.engine);
                    if entry.1.snippet.len() < h.snippet.len() {
                        entry.1.snippet = h.snippet.clone();
                    }
                }
            }
            Err(err) => {
                status.insert(e.clone(), json!(format!("failed: {err}")));
            }
        }
    }
    let mut merged: Vec<_> = score.into_values().collect();
    merged.sort_by(|x, y| y.0.partial_cmp(&x.0).unwrap_or(std::cmp::Ordering::Equal));
    let list: Vec<Value> = merged
        .into_iter()
        .take(max)
        .map(|(s, h, by)| {
            json!({"title": h.title, "url": h.url, "site": site(&h.url), "snippet": h.snippet, "engines": by, "score": (s * 1000.0).round() / 1000.0})
        })
        .collect();
    json!({"query": q, "results": list, "engines": status})
}

fn site(u: &str) -> String {
    url::Url::parse(u).ok().and_then(|p| p.host_str().map(|h| h.trim_start_matches("www.").to_string())).unwrap_or_default()
}

// ---------------------------------------------------------------- reading a page

// footnote marks as reader views drop them: [1], [ 12 ], [a], [citation needed]
static CITE: std::sync::LazyLock<regex_lite::Regex> =
    std::sync::LazyLock::new(|| regex_lite::Regex::new(r"\s*\[\s*(\d+|[a-z]|citation needed|update|edit)\s*\]").unwrap());

// site furniture that reads like a paragraph
static JUNK: std::sync::LazyLock<regex_lite::Regex> = std::sync::LazyLock::new(|| {
    regex_lite::Regex::new(r"(?i)^(follow this|sign up|subscribe|get (our|the) newsletter|we use cookies|this site uses cookies|advertisement|all rights reserved|copyright ©|share this|read more:|related:|click here|log in to|create an account|support (our|independent) journalism|you have \d+ free)").unwrap()
});

const DROP: &str = "script, style, noscript, nav, header, footer, aside, form, button, svg, iframe, figure figcaption, \
    [role=navigation], [role=banner], [role=contentinfo], [aria-hidden=true], .ad, .ads, .advert, .newsletter, .related, \
    .share, .social, .comments, #comments, .cookie, .subscribe, .paywall, .promo, .sidebar, .breadcrumb, .byline-share";

/// The page's article text as paragraphs: the container with the most paragraph text wins
/// (article, main, or the densest block), the way reader views do it.
fn paragraphs(doc: &Html) -> Vec<String> {
    let drop: Vec<_> = doc.select(&sel(DROP)).map(|e| e.id()).collect();
    let dropped = |e: &ElementRef| e.ancestors().any(|a| drop.contains(&a.id())) || drop.contains(&e.id());
    let blocks = sel("p, li, h2, h3, blockquote, pre");
    // score candidate containers by the paragraph text directly inside them
    let mut best: Option<(usize, Vec<String>)> = None;
    for c in doc.select(&sel("article, main, [role=main], section, div")) {
        if dropped(&c) {
            continue;
        }
        let mut paras = Vec::new();
        let mut chars = 0;
        for p in c.select(&blocks) {
            if dropped(&p) {
                continue;
            }
            // only the innermost blocks, so a list inside a paragraph is not counted twice
            if p.select(&blocks).next().is_some() && p.value().name() != "blockquote" {
                continue;
            }
            let t = CITE.replace_all(&text_of(p), "").trim().to_string();
            let words = t.split(' ').count();
            let heading = matches!(p.value().name(), "h2" | "h3");
            if JUNK.is_match(&t) {
                continue;
            }
            if (heading && words >= 2) || words >= 8 {
                chars += if heading { 0 } else { t.len() };
                paras.push(if heading { format!("## {t}") } else { t });
            }
        }
        // a whole-page wrapper also holds menus and teasers: prefer the tightest container with the text
        let bonus = match c.value().name() {
            "article" => 1.3,
            "main" => 1.15,
            _ => 1.0,
        };
        let score = (chars as f64 * bonus) as usize;
        if best.as_ref().map(|b| score > b.0 + b.0 / 20).unwrap_or(true) {
            best = Some((score, paras));
        }
    }
    best.map(|b| b.1).unwrap_or_default()
}

fn meta(doc: &Html, names: &[&str]) -> String {
    for n in names {
        let s = format!("meta[property=\"{n}\"], meta[name=\"{n}\"], meta[itemprop=\"{n}\"]");
        if let Some(m) = doc.select(&sel(&s)).next() {
            if let Some(c) = m.value().attr("content") {
                let c = squash(c);
                if !c.is_empty() {
                    return c;
                }
            }
        }
    }
    String::new()
}

fn words(s: &str) -> Vec<String> {
    s.to_lowercase()
        .split(|c: char| !c.is_alphanumeric())
        .filter(|w| w.len() > 2 && !STOP.contains(w))
        .map(String::from)
        .collect()
}

const STOP: &[&str] = &[
    "the", "and", "for", "that", "with", "this", "from", "was", "were", "are", "but", "not", "have", "has", "had", "you",
    "his", "her", "they", "their", "them", "its", "our", "who", "what", "when", "where", "which", "will", "would", "can",
    "could", "about", "into", "over", "after", "said", "also", "more", "than", "been", "being", "there", "these", "those",
    "how", "all", "any", "out", "one", "two", "she", "him", "song", "write",
];

/// Picks the paragraphs most about `topic` (BM25 over the page's own paragraphs), keeps them in page
/// order, and stops at `budget` characters. The first paragraph (the lede) always comes along.
pub fn pick(paras: &[String], topic: &str, budget: usize) -> Vec<String> {
    let q = words(topic);
    if q.is_empty() || paras.is_empty() {
        let mut out = Vec::new();
        let mut used = 0;
        for p in paras {
            if used + p.len() > budget {
                break;
            }
            used += p.len();
            out.push(p.clone());
        }
        return out;
    }
    let docs: Vec<Vec<String>> = paras.iter().map(|p| words(p)).collect();
    let n = docs.len() as f64;
    let avg = docs.iter().map(|d| d.len()).sum::<usize>() as f64 / n.max(1.0);
    let df = |t: &str| docs.iter().filter(|d| d.iter().any(|w| w == t)).count() as f64;
    let (k1, b) = (1.4, 0.7);
    let mut scored: Vec<(usize, f64)> = docs
        .iter()
        .enumerate()
        .map(|(i, d)| {
            let len = d.len() as f64;
            let s: f64 = q
                .iter()
                .map(|t| {
                    let tf = d.iter().filter(|w| *w == t).count() as f64;
                    if tf == 0.0 {
                        return 0.0;
                    }
                    let idf = ((n - df(t) + 0.5) / (df(t) + 0.5) + 1.0).ln();
                    idf * tf * (k1 + 1.0) / (tf + k1 * (1.0 - b + b * len / avg.max(1.0)))
                })
                .sum();
            (i, s)
        })
        .collect();
    scored.sort_by(|x, y| y.1.partial_cmp(&x.1).unwrap_or(std::cmp::Ordering::Equal));
    let mut keep = vec![0usize];
    let mut used = paras[0].len();
    for (i, s) in scored {
        if i == 0 || s <= 0.0 {
            continue;
        }
        if used + paras[i].len() > budget {
            continue;
        }
        used += paras[i].len();
        keep.push(i);
    }
    // bring each chosen paragraph's heading along with it
    let heads: Vec<usize> = keep.iter().filter(|&&i| i > 0 && paras[i - 1].starts_with("## ") && !keep.contains(&(i - 1))).map(|i| i - 1).collect();
    keep.extend(heads);
    keep.sort_unstable();
    keep.dedup();
    let mut out: Vec<String> = Vec::new();
    for (n, &i) in keep.iter().enumerate() {
        // a heading only comes along when the paragraph after it does
        if paras[i].starts_with("## ") && keep.get(n + 1) != Some(&(i + 1)) {
            continue;
        }
        out.push(paras[i].clone());
    }
    out
}

/// Fetches a page and returns its article. `spec` is {"url", "topic"?, "budget"?: characters for "excerpt"}.
pub fn read(spec: &Value) -> Value {
    let u = spec["url"].as_str().unwrap_or("").trim();
    if !u.starts_with("http://") && !u.starts_with("https://") {
        return json!({"error": "not a web address", "url": u});
    }
    let topic = spec["topic"].as_str().unwrap_or("");
    let budget = spec["budget"].as_u64().unwrap_or(3000) as usize;
    let resp = match agent().get(u).set("Accept", "text/html,application/xhtml+xml").call() {
        Ok(r) => r,
        Err(ureq::Error::Status(code, _)) => return json!({"error": format!("the site answered {code}"), "url": u}),
        Err(e) => return json!({"error": e.to_string(), "url": u}),
    };
    let final_url = resp.get_url().to_string();
    let ctype = resp.content_type().to_string();
    if !ctype.contains("html") && !ctype.contains("xml") && !ctype.is_empty() {
        return json!({"error": format!("not a web page ({ctype})"), "url": u});
    }
    let html = match body(resp) {
        Ok(h) => h,
        Err(e) => return json!({"error": e, "url": u}),
    };
    let doc = Html::parse_document(&html);
    let title = {
        let t = meta(&doc, &["og:title", "twitter:title"]);
        if t.is_empty() { doc.select(&sel("title")).next().map(text_of).unwrap_or_default() } else { t }
    };
    let paras = paragraphs(&doc);
    let text = paras.join("\n\n");
    let excerpt = pick(&paras, topic, budget).join("\n\n");
    json!({
        "url": u,
        "final_url": final_url,
        "site": site(&final_url),
        "title": title,
        "published": meta(&doc, &["article:published_time", "datePublished", "date", "pubdate", "og:updated_time"]),
        "author": meta(&doc, &["author", "article:author"]),
        "description": meta(&doc, &["og:description", "description"]),
        "chars": text.len(),
        "text": text,
        "excerpt": excerpt,
    })
}

// ---------------------------------------------------------------- the app's side (JNI)

#[cfg(feature = "android")]
mod android {
    use jni::JNIEnv;
    use jni::objects::{JClass, JString};
    use jni::sys::jstring;

    fn call(env: &mut JNIEnv, input: JString, f: fn(&serde_json::Value) -> serde_json::Value) -> jstring {
        let s: String = env.get_string(&input).map(|s| s.into()).unwrap_or_default();
        // a panic must never reach Java: it comes back as an error instead
        let out = std::panic::catch_unwind(|| match serde_json::from_str(&s) {
            Ok(v) => f(&v),
            Err(e) => serde_json::json!({"error": format!("bad request: {e}")}),
        })
        .unwrap_or_else(|_| serde_json::json!({"error": "the research tool failed"}));
        env.new_string(out.to_string()).map(|j| j.into_raw()).unwrap_or(std::ptr::null_mut())
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_ai_jackknife_killayue_Scout_search(mut env: JNIEnv, _: JClass, spec: JString) -> jstring {
        call(&mut env, spec, super::search)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_ai_jackknife_killayue_Scout_read(mut env: JNIEnv, _: JClass, spec: JString) -> jstring {
        call(&mut env, spec, super::read)
    }
}
