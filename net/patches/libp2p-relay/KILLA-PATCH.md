# libp2p-relay 0.22.0, patched for Killa YuE

The crate as published on crates.io (MIT licence, https://github.com/libp2p/rust-libp2p), with one change in
`src/behaviour.rs`, `on_connection_established`: relayed connections are not listed in `connections`.

Why: a relayed connection gets `dummy::ConnectionHandler`. When the relay's status changes (on or off),
`reconfigure_relay_status` sends `SetStatus` to every listed connection; the dummy handler of a relayed one cannot
take it, and libp2p-swarm's `Either` handler panics ("internal error: entered unreachable code", either.rs:110). The
panic ends the node's network thread: a phone that moves between Wi-Fi and mobile data, or any node whose
reachability changes while a peer is connected through a relay, loses the network until the app restarts.

Seen on 2026-10-22 in a test node on the Killa YuE network helper. Drop this patch once upstream fixes it.
