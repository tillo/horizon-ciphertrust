# horizon-ciphertrust

A custom Evertrust Horizon image that adds a **`ciphertrust://` Tink vault
master-key scheme**, so Horizon's vault keyset can be wrapped by a key held in
**Thales CipherTrust Manager** instead of stored as plaintext (or wrapped by
AWS/GCP KMS or a PKCS#11 HSM — the only schemes Horizon supports out of the box).

It is backed by the standalone extension
[`ch.tillo.tink:tink-ciphertrust`](https://gitlab.mdapi.ch/mdapi/tink-java-ciphertrust)
and is the interim/bridge deployment until Horizon supports CipherTrust natively
(see [`EVERTRUST-REQUEST.md`](EVERTRUST-REQUEST.md)).

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

## Build

CI builds and pushes the image (then mirrors it to `zot.mdapi.ch`). It needs:

- `EVERTRUST_REGISTRY_USER` / `EVERTRUST_REGISTRY_PASSWORD` CI/CD variables to
  pull `registry.evertrust.io/horizon:$HORIZON_VERSION`.
- Read access to project 212's Maven registry (via `CI_JOB_TOKEN`, passed to the
  in-image Maven build as a buildkit secret).

Local build:

```bash
CI_JOB_TOKEN=<PAT> docker build \
  --secret id=ci_job_token,env=CI_JOB_TOKEN \
  --build-arg HORIZON_VERSION=2.10.2 -t horizon-ciphertrust:2.10.2 .
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
