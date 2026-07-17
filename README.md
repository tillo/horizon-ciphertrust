# horizon-ciphertrust

A custom Evertrust Horizon image that adds a **`ciphertrust://` Tink vault
master-key scheme**, so Horizon's vault keyset can be wrapped by a key held in
**Thales CipherTrust Manager** instead of stored as plaintext (or wrapped by
AWS/GCP KMS or a PKCS#11 HSM — the only schemes Horizon supports out of the box).

It is backed by the standalone extension
[`ch.tillo.tink:tink-ciphertrust`](https://github.com/tillo/tink-java-ciphertrust)
(on Maven Central as `ch.tillo.tink:tink-ciphertrust`),
and further schemes can be plugged in without touching this project (see
"Adding another scheme" below).

## How it works

Horizon 2.10's `models.tink.TinkKeyset` trait dispatches the master-key URI
through a fixed `startsWith` chain (`pkcs11://`, `pkcs11-aes-cbc://`,
`aws-kms://`, `gcp-kms://`, else `IllegalArgumentException`). There is no
plugin hook: it never consults Tink's `KmsClients` registry or `ServiceLoader`,
so a third-party `KmsClient` jar on the classpath is not picked up by
configuration alone.

This image therefore makes two additive changes at **build time**:

1. **Extension + hook on the classpath.** The `tink-ciphertrust` extension and a
   tiny `horizon-ciphertrust-hook` jar are added to `/horizon/lib`.

2. **A one-instruction bytecode hook.** `PatchTinkKeyset` (Javassist) injects a
   single call at the very top of `TinkKeyset.$init$`:

   ```
   if (HorizonCipherTrustHook.tryInstall($this)) return;
   ```

   `tryInstall` returns `false` for every non-CipherTrust master-key URI, so the
   original dispatch runs **byte-for-byte unchanged** for `aws-kms`/`gcp-kms`/
   `pkcs11`/plaintext. For a `ciphertrust://` URI it reproduces exactly what
   Horizon does for the built-in wrapped schemes: build the KEK `Aead`, unwrap
   the encrypted keyset, and set the vault's working `aead` to the primitive of
   the unwrapped keyset (not the KEK).

   The patched class is written to `/horizon/etc/models/tink/TinkKeyset.class`.
   Horizon runs with `-cp etc/:lib/*`, and `etc/` precedes `lib/*`, so the
   patched class deterministically shadows the vendor one — no jar is rewritten.

The patch is re-derived from whatever `TinkKeyset` the base image ships, so a
Horizon upgrade is just a `HORIZON_VERSION` bump + rebuild.

## Key URI and credentials

```
ciphertrust://<cm-host>/<key-name>
```

`<key-name>` is an AES key in CipherTrust Manager (Encrypt+Decrypt usage). The
credentials come from Horizon's existing `defaultVault.credentialsPath` — a JSON
file `{"username": "...", "password": "..."}` or `{"refresh_token": "..."}` —
mounted from a Kubernetes secret. Use a dedicated CM service user that owns only
the KEK.

## Boot behaviour and tuning

Vault initialization is on Horizon's boot path, so the hook is deliberately
loud: it logs the attempt, the outcome and the elapsed time (with the hook's
own version) through Horizon's logging. An unreachable CipherTrust Manager
fails the boot with a clear error within a bounded envelope — by default
3 attempts × (10 s connect + 30 s request) plus backoff — after which
Kubernetes restarts the pod and the boot retries; Horizon recovers by itself
once CM is back.

The envelope can be tuned per deployment (pod env vars, no rebuild):

| Variable | Default | Meaning |
|----------|---------|---------|
| `CIPHERTRUST_CONNECT_TIMEOUT_MS` | `10000` | TCP/TLS connect timeout |
| `CIPHERTRUST_REQUEST_TIMEOUT_MS` | `30000` | one full HTTP exchange |
| `CIPHERTRUST_RETRY_ATTEMPTS` | `3` | tries per call (`1` = no retries) |
| `CIPHERTRUST_RETRY_BACKOFF_MS` | `300` | first retry delay, doubled per retry |

A malformed value fails vault initialization with a clear message instead of
being silently ignored.

## Adding another scheme (no rebuild of this project)

The hook dispatches through a `ServiceLoader` SPI: `VaultSchemeHandler`
(`name()` + `supports(uri)` + `newKekAead(uri, credentialsPath)`). All
scheme-independent work — reading Horizon's keyset configuration, unwrapping,
installing the vault primitive, logging, error shaping — stays in
`HorizonCipherTrustHook`; the built-in `ciphertrust://` handler is itself
registered through the same mechanism.

To add a scheme (say `vault-transit://`):

1. In your own project, depend on the hook jar and implement
   `VaultSchemeHandler` (public class, public no-arg constructor).
2. Register it in
   `META-INF/services/ch.tillo.tink.ciphertrust.horizon.VaultSchemeHandler`.
3. Add your jar (plus its runtime deps) to `/horizon/lib` — e.g. a one-line
   `COPY` in a derived image `FROM` this one.

At boot the hook logs every discovered handler
(`Discovered N vault scheme handler(s): …`), so what won a URI is never a
mystery. A provider that fails to load is logged and skipped — a broken
third-party jar cannot take down the schemes that do load, and if discovery
itself dies the hook degrades to a no-op (Horizon's built-in schemes are
never affected).

## Build

CI builds and pushes the image (then mirrors it to the registry the Helm
values pull from). Every build gets two tags: the moving `<HORIZON_VERSION>`
and an **immutable** `<HORIZON_VERSION>-b<pipeline iid>`. Deployments pin the
immutable tag, so a rollout is always an explicit tag bump and a rollback is
the previous tag — rebuilding the same Horizon version can never silently
change what a cluster runs. It needs:

- `EVERTRUST_REGISTRY_USER` / `EVERTRUST_REGISTRY_PASSWORD` CI/CD variables to
  pull `registry.evertrust.io/horizon:$HORIZON_VERSION`.
- Nothing else: `ch.tillo.tink:tink-ciphertrust` resolves from Maven Central.
  (CI additionally passes `CI_JOB_TOKEN` as a buildkit secret, which activates
  a profile in `ci_settings.xml` consulting the extension's own GitLab package
  registry — only needed to build against unreleased versions.)

Local build (credentials for `registry.evertrust.io` required):

```bash
docker build --build-arg HORIZON_VERSION=2.10.2 -t horizon-ciphertrust:2.10.2 .
```

The build fails if the patch does not apply (a `javap | grep tryInstall` gate).

## Deploy

Deployment and the plaintext→CipherTrust keyset migration are **operational,
approval-gated** steps — see [`RUNBOOK.md`](RUNBOOK.md). Do not point the live
vault at a new master key without following it (a bad keyset load bricks the
single-replica `horizon-0` at boot).

## Verified

The mechanism was proven end-to-end against Horizon 2.10.2's real classes and a
live CipherTrust Manager 2.22: the patched `TinkKeyset` unwraps a keyset wrapped
by a CM KEK and the vault round-trips, while unknown schemes still fall through
to Horizon's original `Master key format '…' unsupported` error.

## License

[Apache License 2.0](LICENSE). The hook links Horizon's interfaces at compile
time only; no Evertrust code is redistributed (the base image is pulled from
Evertrust's own registry at build time).
