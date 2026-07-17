# Runbook — cut the Horizon vault over to a CipherTrust-wrapped master key

**Approval-gated.** This migrates a live Horizon vault (typically a
single-replica `horizon-0`) from a plaintext Tink keyset to one wrapped by a
CipherTrust Manager KEK. The keyset is loaded at boot and decrypts every
escrowed key/secret in Mongo, so a mistake prevents startup. **Rehearse on a
Mongo copy first; keep the plaintext keyset until the new path is proven.**

## Preconditions

- Custom image `<your-registry>/horizon-ciphertrust:2.10.2` built and mirrored.
- CM KEK created for production (separate from any test KEK), owned by a
  dedicated service user with Encrypt+Decrypt on that key only, e.g.
  `ciphertrust://cm.example.com/horizon-vault-kek`.
- Service-user credentials JSON in your secret manager, surfaced to the pod as
  a mounted secret file (e.g. ExternalSecret → `credentialsPath`).
- A **full, restorable backup** of the Horizon MongoDB and the current
  plaintext keyset secret.

## Step 0 — rehearse on a disposable copy (mandatory)

1. Clone the Horizon Mongo into a scratch namespace (or a storage snapshot
   restore), and stand up a throwaway Horizon from the custom image pointed at
   the copy.
2. Wrap a **copy** of the current keyset (Step 1) and boot the throwaway with
   the `ciphertrust://` master key. Confirm it starts, the license/API work, and
   existing escrowed material decrypts. Only proceed once this is clean.

## Step 1 — wrap the existing keyset (do NOT rotate key material)

Re-wrap the *same* keyset bytes so all existing Mongo data stays decryptable —
this is an envelope change, not a key rotation. Using Evertrust's `tinkey`
distribution (or the extension's own `serializeEncryptedKeyset`):

```
# input: current plaintext keyset (from the vault keyset secret)
# output: same keyset, encrypted under the CM KEK
tinkey convert-keyset \
  --in horizon.keyset --in-format json \
  --new-master-key-uri ciphertrust://cm.example.com/horizon-vault-kek \
  --credential /path/to/creds.json \
  --out horizon.keyset.wrapped --out-format json
```

Store `horizon.keyset.wrapped` as the new keyset secret value.

## Step 2 — stage the Helm values (show diff, get sign-off)

In your Horizon Helm values:

```yaml
image:
  repository: <your-registry>/horizon-ciphertrust   # was registry.evertrust.io/horizon
  tag: "2.10.2"
defaultVault:
  masterKeyURI: "ciphertrust://cm.example.com/horizon-vault-kek"   # was "" (plaintext)
  keyset:
    secretName: horizon-vault-keyset                               # now holds the WRAPPED keyset
    secretKey: keyset
  # credentialsPath / VAULT_TINK_CREDENTIALS_PATH -> mounted svc-user creds JSON
```

Present the rendered diff for sign-off before applying.

## Step 3 — cut over

1. Schedule a maintenance window; `horizon-0` will restart (single replica →
   brief API outage, as with any Horizon restart).
2. Apply the wrapped keyset secret + the Helm values together.
3. Watch startup logs: expect the Pekko `ClusterSingletonProxy … Singleton
   identified` line and no keyset-load error. The vault master key is now the CM
   KEK.

## Step 4 — verify

- The license API responds (proves the singleton + vault are up).
- A search/aggregation and a test enrollment succeed (proves escrowed material
  decrypts under the re-wrapped keyset).
- CipherTrust Manager shows recent decrypt activity for the KEK at boot.

## Rollback

Fast and total, because Step 1 preserved the key material:

1. Restore the Helm values: `image.repository` back to
   `registry.evertrust.io/horizon`, `defaultVault.masterKeyURI` back to `""`.
2. Restore the plaintext keyset secret from backup.
3. `helm upgrade` / reconcile → `horizon-0` restarts on the vendor image with
   the plaintext keyset. No data migration is involved either way, so rollback
   is just a redeploy.

## Notes

- Availability: CipherTrust Manager is now on Horizon's boot path. If CM is
  down, `horizon-0` cannot start. CM Community Edition is single-node — weigh
  this before cutover; keep the plaintext-keyset rollback ready.
- On every Horizon upgrade: bump `HORIZON_VERSION`, rebuild the image (re-derives
  the patch), and re-run Step 0 on a copy before touching prod.
- Deployments pin the **immutable** tag (`<version>-b<pipeline iid>`, printed at
  the end of the CI mirror job). Rolling out a rebuild = bump the tag in the
  Helm values; rolling back = restore the previous tag. The moving
  `<version>` tag exists for humans only — never pin it.
- Boot diagnostics: the hook logs `Initializing Tink vault master key via
  ciphertrust handler: <uri> (hook <version>)` and either `ready ... in N ms`
  or a bounded `FAILED after N ms` with the cause. If a boot hangs with the
  init line but neither outcome, the stall is elsewhere in Horizon, not in the
  CipherTrust path. Transport knobs: `CIPHERTRUST_CONNECT_TIMEOUT_MS`,
  `CIPHERTRUST_REQUEST_TIMEOUT_MS`, `CIPHERTRUST_RETRY_ATTEMPTS`,
  `CIPHERTRUST_RETRY_BACKOFF_MS` (see README).
