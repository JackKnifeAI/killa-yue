# Helpers that update themselves

Status: design, 2026-11-02, for 1.8.0.

Helpers carry the mixnet, so an old helper with a known hole weakens everyone who routes through it. Volunteers won't
all re-run an install line. From 1.8.0 a helper keeps itself current, by default, and says so at install.

The desktop companion and the helper are already one program: `HammerServer`, in `hammer-server.zip`. The flags
`--daemon --help-network` make it a helper. Everything below, and the 1.8 hangout work a computer does (carrying
stations, holding circle messages), goes into that one program.

## What must hold

- **Nobody can push code to helpers without the release key.** Not a relay, not another helper, not someone who takes
  over the GitHub account.
- **The network never restarts at once.** A release rolls out over a day.
- **A bad release heals itself.** A version that can't rejoin the network is rolled back without the owner's help.
- **It can't be walked backwards.** A helper never installs an older release, however well signed.
- **Stays a volunteer's computer.** It runs as the owner, never as root. The installer states that updates are
  automatic, and one command turns them off.

## The signed manifest

Every release publishes `update.json` beside the zip:

| Field | Meaning |
|---|---|
| `sequence` | Whole number, higher each release. A helper refuses anything not above the one it runs |
| `version` | `1.8.1` |
| `zip_sha256`, `zip_size` | The helper zip, per platform |
| `urgent` | Spread over one hour, not 24, for a security fix |
| `published` | Signing time, shown in the helper's status |
| `sig` | ed25519 over all of the above |

The public keys are built into the helper: a **release key** and a separate **recovery key**. Either verifies a
manifest. A manifest may name a replacement key, so a lost or stolen release key can be retired with the recovery key.
Both private keys stay offline, never on a machine that is always connected. The manifest is the only thing trusted;
where the bytes came from doesn't matter.

## How a helper finds and takes an update

1. **Find.** Every six hours it fetches `releases/latest/download/update.json`, and helpers hand the newest manifest
   they hold to each other, signed as it is, so a blocked GitHub doesn't freeze the network. Carrying the zip itself
   between helpers comes after that, by the same reasoning as an app that spreads without GitHub.
2. **Wait its turn.** It waits a delay taken from a hash of its peer ID and the version, up to 24 hours (one hour if
   `urgent`). The delay is fixed per helper, so restarting doesn't change it, and no two moments gather the network.
3. **Fetch and check.** It downloads the zip, checks the size and hash from the signed manifest, and unpacks it to
   `versions/<version>/`. On any mismatch it deletes the download and keeps running.
4. **Switch.** It points `next` at the new version and exits with code 75. systemd restarts it through a small launcher.
5. **Prove it works.** The new version writes `healthy` once it has held network peers for five minutes.
6. **Roll back.** If two starts go by with no `healthy`, the launcher returns to `previous`, records the version as bad
   and never takes it again.

```
~/.local/share/killa-yue-helper/
  launcher.sh        changes only through a new install line, so it can always recover
  current -> versions/1.8.1
  previous -> versions/1.8.0
  versions/
  state/             keys, music and mix seed: never touched by an update
```

## Turning it off

`--no-auto-update`, or `sh ~/.local/share/killa-yue-helper/update.sh off`. The helper still checks manifests and prints
a warning when it falls behind. Peers don't punish it.

## Versions side by side

During a rollout old and new helpers work together for a day, so a release must keep speaking the protocol of the one
before it. Mix descriptors already carry a version. A helper reports its version in `status` and `health.txt`.

## Honest limits

- The release key can run code on every helper that left this on. That risk is the price of updating everyone. It is why
  the keys stay offline, why there is a recovery key, and why the installer says this plainly.
- Today one person holds the keys. The manifest format leaves room for two signatures later, once there is a second
  trusted maintainer.
- A helper already running 1.7.x needs the install line once more to get all this.
- Linux with systemd first. Other platforms keep updating by hand.
- The app says a helper "updates itself". It never says updates are risk-free.

## Tests

`peer/test/update.sh`, with a test key built into a test helper and two local "releases":

- a good update is taken, after its wait;
- wrong signature, wrong hash, wrong key and a lower or equal `sequence` are refused;
- a version that never turns healthy is rolled back, and not taken again;
- `off` stops the update and keeps the warning;
- `state/` is byte for byte the same across an update and a rollback.
