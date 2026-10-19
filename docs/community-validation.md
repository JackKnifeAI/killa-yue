# Community preview validation

Base: `a6f0a89` (released 1.6.1). The branch adds community metadata/UI and
companion operation; it does not replace generation engines or select new GPUs.

## Checks completed

- Android resources, Java and dex compiled with Android API 37. The new UI
  retains plain Java 8 anonymous listeners for the existing dex build workflow.
- Existing app logic regression suite passed, including the 46 engine selection
  checks and the Studio/project/audio logic tests.
- HAMMER native audio and encrypted peer regression: 138 assertions passed.
- New community integration: 42 assertions passed, including signed timestamp
  posts, duplicate/conflicting IDs, forged moderation, artist-owned board roots,
  locks, hiding, pins, private metadata boundaries, remix identity and capability
  checks, public rehosting, persistence, recipient signatures, host binding,
  expiration, circle revocation, unregistering, monthly rotation/dedup/removal,
  counter disabled by default, TLS transport and native transcoding.
- Existing Rust network regression passed discovery on the same network, DHT
  discovery through an entry peer, and a forced relay path. The relay test also
  passed private-circle access and rejection of a forged HAMMER pin.
- Signed preview APK audited: all 34 native payloads match the released 1.6.1
  APK byte for byte. Baseline SHA-256:
  `03d2095763a7b65f7496f9168bf4108f36d3f5c4ef50c43dd82ea54c2e699179`.
  The Android runtime/dependency audit passed. The APK's bundled desktop
  companion adds the desktop Rust network library separately.

## First ops volunteer node

Installed as user service `killa-community.service` on ops. Its private state is
under `~/.local/share/killa-community/state`; the application package is in the
sibling `app/hammer-server` directory. The service is enabled across user-service
restarts and limited to 200% CPU, 1 GiB memory, 128 tasks and 1,024 file descriptors.
It is not root, and the unit enables `NoNewPrivileges` and a private temporary
directory. UPnP/router changes are not enabled by this deployment.

Roles enabled: hosting, signed community metadata, Rust peer networking/helper
mode, native audio transcoding, and the optional monthly count endpoint. Remote
music generation is disabled. No existing user music was published. One generated
private verification tone is retained for operator checks.

From the development computer, both direct pinned TLS over Tailscale and a
libp2p connection with an explicit Tailscale entry address read the node's public
profile, board and monthly count. Verification reads left the count at zero.
A deployed-node private audio request transcoded 96,000 synthetic frames to a
12,730-byte Opus segment in 72 ms including TLS; the fixture was absent from the
public catalog. This is a small correctness/availability check, not a load test
or music-generation benchmark.

Public network card (contains no private capability):

```
hammer://12D3KooWLRPuZ5ce9YGFvuJLcNdUmVH1hwM8CJ7b9favwqxTepSJ.p2p:4001/#ab07ac21b20aa2afb4f13af9f4c3d575e1e1d9c5795b74a05af3cf6beb809da3
```

The preview suggests this card for counting; consent remains off by default.
An explicit-entry test over the tailnet is not proof of worldwide reachability.
Before promotion, test an unrelated external network without Tailscale or manual
entry, including NAT/relay availability. No global availability claim is made.

Operator commands:

```
systemctl --user status killa-community.service
systemctl --user restart killa-community.service
systemctl --user disable --now killa-community.service
```

The last command stops participation and disables automatic startup; it keeps
identity and hosted files. Stop the service before opening its state with an
interactive companion. Back up keys privately, excluding `community-count.bin`.
Do not publish state, private invitations, request contents or logs of peer IPs.

## Remaining release gates

- Physical Android/DeX UI testing: replies, timestamp playback, remix navigation,
  moderation, registering/inviting/accepting/revoking, and count opt-in/out.
- Android foreground notification/reconnect tests and device lifecycle checks.
- Worldwide node reachability from an independent external network.
- Sustained multi-peer load, thread capacity/archiving UX, and moderation review.
- Integration with the separate compatibility-lab and Studio PRs, plus their
  own regression checks. This preview deliberately stays on the 1.6.1 base.

No stable release was published, no existing phone installation was replaced,
and no remote-generation work was accepted. The complete desktop app, GPU
support matrix and volunteer generation queue are in the
[desktop roadmap](community-and-desktop-roadmap.md).
