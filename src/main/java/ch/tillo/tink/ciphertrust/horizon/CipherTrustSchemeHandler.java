// Copyright 2026 Martino Dell'Ambrogio
// Licensed under the Apache License, Version 2.0.
package ch.tillo.tink.ciphertrust.horizon;

import ch.tillo.tink.ciphertrust.CipherTrustKmsClient;
import ch.tillo.tink.ciphertrust.CipherTrustTransport;
import com.google.crypto.tink.Aead;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Optional;

/**
 * The {@code ciphertrust://} master-key scheme, backed by {@code ch.tillo.tink:tink-ciphertrust}.
 *
 * <p>Credentials come from Horizon's {@code defaultVault.credentialsPath} (a JSON file with {@code
 * username}/{@code password} or {@code refresh_token}) or, when unset, from the {@code
 * CIPHERTRUST_USERNAME}/{@code CIPHERTRUST_PASSWORD}/{@code CIPHERTRUST_REFRESH_TOKEN}
 * environment.
 *
 * <p>The transport envelope (how long a boot-time unwrap may take before failing loudly) has safe
 * defaults ({@link CipherTrustTransport#DEFAULT}) and can be tuned per deployment without a
 * rebuild through optional environment variables:
 *
 * <ul>
 *   <li>{@code CIPHERTRUST_CONNECT_TIMEOUT_MS} — TCP/TLS connect timeout
 *   <li>{@code CIPHERTRUST_REQUEST_TIMEOUT_MS} — one full HTTP exchange
 *   <li>{@code CIPHERTRUST_RETRY_ATTEMPTS} — total tries per call ({@code 1} = no retries)
 *   <li>{@code CIPHERTRUST_RETRY_BACKOFF_MS} — first retry delay, doubled per further retry
 * </ul>
 *
 * <p>A malformed value fails vault initialization with a clear message rather than being silently
 * ignored — a mistyped timeout should never masquerade as the default.
 *
 * <p>Registered via {@code META-INF/services} like any third-party {@link VaultSchemeHandler} —
 * the built-in scheme takes no shortcut through the discovery mechanism.
 */
public final class CipherTrustSchemeHandler implements VaultSchemeHandler {

  /** Public no-arg constructor for {@link java.util.ServiceLoader}. */
  public CipherTrustSchemeHandler() {}

  @Override
  public String name() {
    return "ciphertrust";
  }

  @Override
  public boolean supports(String masterKeyUri) {
    return masterKeyUri.startsWith(CipherTrustKmsClient.PREFIX);
  }

  @Override
  public Aead newKekAead(String masterKeyUri, Optional<String> credentialsPath)
      throws GeneralSecurityException {
    CipherTrustKmsClient client = new CipherTrustKmsClient(masterKeyUri);
    client.withTransport(transportFromEnvironment());
    if (credentialsPath.isPresent()) {
      client.withCredentials(credentialsPath.get());
    } else {
      client.withDefaultCredentials();
    }
    return client.getAead(masterKeyUri);
  }

  /** Builds the transport from {@link CipherTrustTransport#DEFAULT} + environment overrides. */
  static CipherTrustTransport transportFromEnvironment() throws GeneralSecurityException {
    CipherTrustTransport defaults = CipherTrustTransport.DEFAULT;
    try {
      return CipherTrustTransport.of(
          envDuration("CIPHERTRUST_CONNECT_TIMEOUT_MS", Duration.ofSeconds(10)),
          envDuration("CIPHERTRUST_REQUEST_TIMEOUT_MS", Duration.ofSeconds(30)),
          (int) envLong("CIPHERTRUST_RETRY_ATTEMPTS", 3),
          envDuration("CIPHERTRUST_RETRY_BACKOFF_MS", Duration.ofMillis(300)));
    } catch (IllegalArgumentException e) {
      throw new GeneralSecurityException(
          "invalid CipherTrust transport configuration (defaults: " + defaults + ")", e);
    }
  }

  private static Duration envDuration(String name, Duration fallback) {
    return Duration.ofMillis(envLong(name, fallback.toMillis()));
  }

  private static long envLong(String name, long fallback) {
    String raw = System.getenv(name);
    if (raw == null || raw.isEmpty()) {
      return fallback;
    }
    try {
      return Long.parseLong(raw.trim());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(name + " must be a number of milliseconds/count", e);
    }
  }
}
