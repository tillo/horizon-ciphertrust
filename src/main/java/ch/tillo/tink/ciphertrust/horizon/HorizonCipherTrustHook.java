// Copyright 2026 Martino Dell'Ambrogio
// Licensed under the Apache License, Version 2.0.
package ch.tillo.tink.ciphertrust.horizon;

import com.google.crypto.tink.Aead;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.RegistryConfiguration;
import com.google.crypto.tink.TinkJsonProtoKeysetFormat;
import com.google.crypto.tink.aead.AeadConfig;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Optional;
import models.tink.TinkKeyset;
import models.tink.TinkKeysetConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Option;

/**
 * Adds master-key schemes to Evertrust Horizon's Tink vault.
 *
 * <p>A single call to {@link #tryInstall} is injected at the top of the {@code TinkKeyset.$init$}
 * trait initializer (see {@code PatchTinkKeyset}). It returns {@code false} for every master-key
 * URI that no registered {@link VaultSchemeHandler} owns — so Horizon's original dispatch
 * (aws-kms/gcp-kms/pkcs11/plaintext) runs byte-for-byte unchanged — and for an owned URI it
 * reproduces exactly what Horizon does for the built-in wrapped schemes: build a KEK {@link Aead},
 * unwrap the encrypted keyset with it, then set the vault's working {@code aead} to the primitive
 * derived from the unwrapped keyset (not the KEK itself).
 *
 * <p>Vault initialization is on Horizon's boot path, so this class is deliberately loud: it logs
 * the attempt, the outcome and the elapsed time through Horizon's own logging (slf4j/logback). If
 * the KMS is unreachable the underlying client fails within its bounded transport envelope and the
 * error below names the URI and the elapsed time — a boot stuck elsewhere is then immediately
 * distinguishable from a KMS problem.
 *
 * <p>To add a scheme, implement {@link VaultSchemeHandler} and list it in {@link #HANDLERS}.
 */
public final class HorizonCipherTrustHook {

  private static final Logger log = LoggerFactory.getLogger(HorizonCipherTrustHook.class);

  /** All schemes this hook adds, tried in order; first {@code supports()} match wins. */
  private static final List<VaultSchemeHandler> HANDLERS =
      List.of(new CipherTrustSchemeHandler());

  private HorizonCipherTrustHook() {}

  /**
   * Installs the vault keyset when the configured master-key URI belongs to one of this hook's
   * schemes.
   *
   * @return {@code true} if the vault was fully initialized here (the caller — the patched {@code
   *     $init$} — must return immediately); {@code false} to fall through to Horizon's original
   *     dispatch
   * @throws Exception if the URI is ours but the KEK cannot be built or the keyset cannot be
   *     unwrapped; propagating fails the vault construction, which is the correct outcome — a
   *     vault silently running on the wrong key material must be impossible
   */
  public static boolean tryInstall(TinkKeyset self) throws Exception {
    TinkKeysetConfiguration config = self.keysetConfig();
    Option<String> masterKeyUri = config.masterKeyUri();
    if (masterKeyUri.isEmpty() || masterKeyUri.get() == null) {
      return false;
    }
    String uri = masterKeyUri.get();

    VaultSchemeHandler handler = null;
    for (VaultSchemeHandler candidate : HANDLERS) {
      if (candidate.supports(uri)) {
        handler = candidate;
        break;
      }
    }
    if (handler == null) {
      return false; // not ours — let Horizon's own dispatch handle it
    }

    log.info(
        "Initializing Tink vault master key via {} handler: {} (hook {})",
        handler.name(),
        uri,
        hookVersion());
    long start = System.nanoTime();
    try {
      AeadConfig.register();
      Optional<String> credentialsPath =
          config.credentialsPath().isDefined()
              ? Optional.of(config.credentialsPath().get())
              : Optional.empty();
      Aead kek = handler.newKekAead(uri, credentialsPath);
      KeysetHandle keysetHandle =
          TinkJsonProtoKeysetFormat.parseEncryptedKeyset(config.keySet(), kek, new byte[0]);
      // Mirror Horizon's own field assignment: keysetHandle = unwrapped keyset, aead = its
      // primitive (never the KEK — the KEK only unwraps).
      self.models$tink$TinkKeyset$_setter_$models$tink$TinkKeyset$$keysetHandle_$eq(keysetHandle);
      self.models$tink$TinkKeyset$_setter_$aead_$eq(
          keysetHandle.getPrimitive(RegistryConfiguration.get(), Aead.class));
      log.info(
          "Tink vault master key ready via {} in {} ms", handler.name(), elapsedMs(start));
      return true;
    } catch (Exception e) {
      // The URI names only host + key (never credentials), so it is safe to log.
      log.error(
          "Tink vault master key initialization FAILED via {} after {} ms for {}",
          handler.name(),
          elapsedMs(start),
          uri,
          e);
      throw new GeneralSecurityException(
          "vault master key initialization failed for " + uri + " (" + handler.name() + ")", e);
    }
  }

  private static long elapsedMs(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }

  /** The hook jar's Implementation-Version, or a placeholder when run from raw classes. */
  private static String hookVersion() {
    String version = HorizonCipherTrustHook.class.getPackage().getImplementationVersion();
    return version != null ? version : "dev";
  }
}
