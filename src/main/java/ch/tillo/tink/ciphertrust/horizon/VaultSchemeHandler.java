// Copyright 2026 Martino Dell'Ambrogio
// Licensed under the Apache License, Version 2.0.
package ch.tillo.tink.ciphertrust.horizon;

import com.google.crypto.tink.Aead;
import java.security.GeneralSecurityException;
import java.util.Optional;

/**
 * One additional Tink vault master-key scheme for Horizon.
 *
 * <p>This is the extension seam of the hook: {@link HorizonCipherTrustHook} asks each registered
 * handler whether it {@link #supports} the configured master-key URI, and the first match builds
 * the KEK {@link Aead} used to unwrap the vault keyset. Everything scheme-independent — reading
 * Horizon's keyset configuration, unwrapping, installing the vault primitive, logging and error
 * shaping — stays in the hook, so adding a scheme (say {@code vault-transit://}) is exactly one
 * new handler class plus one entry in {@code HorizonCipherTrustHook.HANDLERS}.
 *
 * <p>Handlers must be stateless or safely shareable: a single instance serves every vault Horizon
 * constructs.
 */
interface VaultSchemeHandler {

  /** A short scheme label for log lines, e.g. {@code "ciphertrust"}. */
  String name();

  /** Returns whether this handler owns {@code masterKeyUri} (non-null, already lowercased-safe). */
  boolean supports(String masterKeyUri);

  /**
   * Builds the KEK {@link Aead} for {@code masterKeyUri}.
   *
   * @param credentialsPath Horizon's {@code defaultVault.credentialsPath} if configured — the same
   *     credential-file mechanism the built-in wrapped schemes use; empty means the handler should
   *     fall back to its environment-based credentials
   * @throws GeneralSecurityException if the KEK cannot be constructed (bad URI, bad credentials,
   *     KMS unreachable); the hook logs it and fails the vault initialization
   */
  Aead newKekAead(String masterKeyUri, Optional<String> credentialsPath)
      throws GeneralSecurityException;
}
