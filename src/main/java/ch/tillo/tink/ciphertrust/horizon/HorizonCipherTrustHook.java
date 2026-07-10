// Copyright 2026 Martino Dell'Ambrogio
// Licensed under the Apache License, Version 2.0.
package ch.tillo.tink.ciphertrust.horizon;

import ch.tillo.tink.ciphertrust.CipherTrustCredentials;
import ch.tillo.tink.ciphertrust.CipherTrustKmsClient;
import com.google.crypto.tink.Aead;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.RegistryConfiguration;
import com.google.crypto.tink.TinkJsonProtoKeysetFormat;
import com.google.crypto.tink.aead.AeadConfig;
import models.tink.TinkKeyset;
import models.tink.TinkKeysetConfiguration;
import scala.Option;

/**
 * Adds a {@code ciphertrust://} master-key scheme to Evertrust Horizon's Tink vault.
 *
 * <p>A single call to {@link #tryInstall} is injected at the top of the {@code TinkKeyset.$init$}
 * trait initializer. It returns {@code false} for every non-CipherTrust master-key URI (so the
 * original dispatch runs byte-for-byte unchanged), and for a {@code ciphertrust://} URI it
 * reproduces exactly what Horizon does for the built-in wrapped schemes: build a KEK {@link Aead},
 * unwrap the encrypted keyset with it, then set the vault's working {@code aead} to the primitive
 * derived from the unwrapped keyset (not the KEK itself).
 */
public final class HorizonCipherTrustHook {

  private HorizonCipherTrustHook() {}

  public static boolean tryInstall(TinkKeyset self) throws Exception {
    TinkKeysetConfiguration config = self.keysetConfig();
    Option<String> masterKeyUri = config.masterKeyUri();
    if (masterKeyUri.isEmpty()) {
      return false;
    }
    String uri = masterKeyUri.get();
    if (uri == null || !uri.startsWith(CipherTrustKmsClient.PREFIX)) {
      return false; // not ours — let Horizon's own dispatch handle it
    }

    AeadConfig.register();
    CipherTrustKmsClient client = new CipherTrustKmsClient(uri);
    Option<String> credentialsPath = config.credentialsPath();
    if (credentialsPath.isDefined()) {
      client.withCredentials(credentialsPath.get());
    } else {
      client.withDefaultCredentials();
    }

    Aead kek = client.getAead(uri);
    KeysetHandle keysetHandle =
        TinkJsonProtoKeysetFormat.parseEncryptedKeyset(config.keySet(), kek, new byte[0]);
    // Mirror Horizon's own field assignment: keysetHandle = unwrapped keyset, aead = its primitive.
    self.models$tink$TinkKeyset$_setter_$models$tink$TinkKeyset$$keysetHandle_$eq(keysetHandle);
    self.models$tink$TinkKeyset$_setter_$aead_$eq(
        keysetHandle.getPrimitive(RegistryConfiguration.get(), Aead.class));
    return true;
  }
}
