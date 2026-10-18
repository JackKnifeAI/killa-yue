# Library, Discover and publication security

This change is a preview on top of PR #32, not a claim of an attack-proof network.

The normal product flow is Library → Song sharing → Public network, or Discover → My artist profile → Publish artist profile. Importing audio and selecting artwork do not publish them. Publishing starts hosting; the Library hosting controls stop serving without deleting recordings. Profiles contain bounded signed text and a selected image; generated song covers are reused. Downloads enter Library directly and preserve the original publisher identity when rehosted. WAV, FLAC and MP3 can be imported from Android's document picker regardless of the platform that produced them.

## Authorization boundary

Network operations accept recording hashes or public object identifiers, never filenames or directories. The local publication registry maps those identifiers to selected files. Audio, codec requests, cover metadata and cover bytes all check publication visibility. A guessed hash is not authorization. Public artist portraits must be referenced by a currently published profile. Cached images without a live reference cannot be fetched. Private circle tokens never enter public discovery records.

Artist profiles and song cover attachments are signed separately from audio. A profile signature identifies a key, not a verified human or a legal claim to authorship. Revisions must advance for local profile updates. Cover signatures must match the recording publisher. Artwork upload is local only; peers cannot write arbitrary images or files to the host.

Android codec workers are non-exported isolated services with no app permissions. They receive read-only file descriptors or bounded Opus packets, not paths or signing keys. C++ consumes duplicated descriptors through miniaudio callbacks rather than reopening filesystem paths. Worker instances are scoped to an input file or incoming Opus decoding, so a decoder does not retain handles from other input scopes. Workers terminate when unbound and expire after idle time. A 12-second watchdog terminates hung media work. Android 28 uses the single isolated service fallback; later Android versions support separate service instances.

Images are decoded in the isolated worker with file-size and pixel-count limits, resized to at most 384 pixels per side, and re-encoded as JPEG without original embedded metadata. Native artist pages render plain text and artwork, never HTML, scripts or remote URLs.

Admission is bounded before TLS: at most 22 admitted connections globally and 6 per address, with global and per-address token budgets. The existing two request workers, bounded queue, 16 subscription workers, deadlines and codec cache limits remain. These limits bound local work; they do not prevent an upstream bandwidth flood or solve identity farming.

## Discovery delivered and outstanding

Android NSD finds public hosts on the local network. Candidate identities must still pass pinned TLS authentication. Private-only hosts are not intentionally advertised. Existing invitation links remain available under Library sharing settings.

Public hosts can exchange signed, expiring directory announcements. Announcements contain numeric public endpoints and no circle capability. The receiving host only accepts a new announcement matching the announcing connection's source address. Discovery follows bounded batches of signed public routing hints and searches fetched artist fields and recording titles/tags locally. No peer-supplied hostname is resolved for automatic directory introductions, and private, loopback, link-local and carrier-NAT destinations are rejected.

This is bounded discovery through reachable peers, not a complete global index. A fresh installation without nearby peers or a configured reachable entry peer cannot join a worldwide network. Internet bootstrap deployment, NAT traversal, encrypted relays, persistent multi-source indexes, large-network pagination and resistance to eclipse/Sybil attacks remain required before claiming automatic worldwide discovery. No public entry server or relay is silently deployed by this change.

The original nearby browser-download feature uses HTTP and is separate from HAMMER. HAMMER does not downgrade to it. Public metadata can be read by any compatible client; an open-source app cannot prove that every client is benign or prevent public indexing. IP addresses and traffic timing are not hidden.

## Required validation

Protocol tests exercise unpublished audio access, private cover access by known hash, cross-owner image access, forged artist records, profile replay, withdrawn profile images, expired/wrong-address announcements, unsafe automatic destinations, resource budgets, existing private circles, TLS pinning, streaming and replica failover. Android tests must additionally exercise descriptor decoding, worker isolation and restart, artwork processing, Library import/sharing, profile publication and nearby discovery. Existing media files must retain their hashes.

Independent security review, sustained adversarial load testing, unplugged battery/deep-sleep testing, other Android vendors, and non-Linux desktop sandboxing remain release work. The desktop native codec is not yet isolated by this Android service implementation.
