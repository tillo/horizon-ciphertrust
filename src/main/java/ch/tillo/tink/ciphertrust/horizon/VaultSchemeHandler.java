// Copyright 2026 Martino Dell'Ambrogio
// Licensed under the Apache License, Version 2.0.
package ch.tillo.tink.ciphertrust.horizon;

import com.google.crypto.tink.Aead;
import java.security.GeneralSecurityException;
import java.util.Optional;

/**
 * SPI: one additional Tink vault master-key scheme for Horizon.
 *
 * <p>Implementations are discovered at runtime through {@link java.util.ServiceLoader}, so adding
 * a scheme requires <em>no change to this project</em>: ship a jar containing the implementation
 * and a {@code META-INF/services/ch.tillo.tink.ciphertrust.horizon.VaultSchemeHandler} provider
 * file, and place it on Horizon's classpath ({@code /horizon/lib}) next to this hook. The built-in
 * {@code ciphertrust://} support ({@link CipherTrustSchemeHandler}) registers itself through the
 * exact same mechanism.
 *
 * <p>{@link HorizonCipherTrustHook} asks each discovered handler whether it {@link #supports} the
 * configured master-key URI; the first match builds the KEK {@link Aead} used to unwrap the vault
 * keyset. Everything scheme-independent — reading Horizon's keyset configuration, unwrapping,
 * installing the vault primitive, logging and error shaping — stays in the hook. Schemes should be
 * disjoint; if two handlers claim the same URI, discovery order (classpath order) decides, and the
 * hook logs every discovered handler at start-up so the winner is never a mystery.
 *
 * <p>Implementations must be public, expose a public no-argument constructor (the {@code
 * ServiceLoader} contract), and be stateless or safely shareable: a single instance serves every
 * vault Horizon constructs.
 */
public interface VaultSchemeHandler {

  /** A short scheme label for log lines, e.g. {@code "ciphertrust"}. */
  String name();

  /** Returns whether this handler owns {@code masterKeyUri} (never null). */
  boolean supports(String masterKeyUri);

  /**
   * Builds the KEK {@link Aead} for {@code masterKeyUri}.
   *
   * @param credentialsPath Horizon's {@code defaultVault.credentialsPath} if configured — the same
   *     credential-file mechanism the built-in wrapped schemes use; empty means the handler should
   *     fall back to its own credential discovery (e.g. environment variables)
   * @throws GeneralSecurityException if the KEK cannot be constructed (bad URI, bad credentials,
   *     KMS unreachable); the hook logs it and fails the vault initialization
   */
  Aead newKekAead(String masterKeyUri, Optional<String> credentialsPath)
      throws GeneralSecurityException;
}
