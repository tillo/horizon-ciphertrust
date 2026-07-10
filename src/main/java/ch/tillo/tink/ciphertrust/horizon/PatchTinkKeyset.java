// Copyright 2026 Martino Dell'Ambrogio
// Licensed under the Apache License, Version 2.0.
package ch.tillo.tink.ciphertrust.horizon;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;

/**
 * Injects a call to {@link HorizonCipherTrustHook#tryInstall} at the top of Horizon's
 * {@code models.tink.TinkKeyset.$init$} trait initializer, leaving the rest of the method (and
 * every existing master-key scheme) byte-for-byte unchanged.
 *
 * <p>Usage: {@code PatchTinkKeyset <input TinkKeyset.class> <output dir root>} — writes the patched
 * class to {@code <output dir>/models/tink/TinkKeyset.class}.
 */
public final class PatchTinkKeyset {

  public static void main(String[] args) throws Exception {
    String input = args[0];
    String outRoot = args[1];

    ClassPool pool = ClassPool.getDefault();
    // Make the Horizon + Tink + Scala + hook types resolvable to the Javassist compiler.
    for (String cp : System.getProperty("java.class.path").split(File.pathSeparator)) {
      pool.appendClassPath(cp);
    }

    CtClass cls;
    try (var in = Files.newInputStream(Paths.get(input))) {
      cls = pool.makeClass(in);
    }

    CtMethod init = null;
    for (CtMethod m : cls.getDeclaredMethods()) {
      if (m.getName().equals("$init$")) {
        init = m;
        break;
      }
    }
    if (init == null) {
      throw new IllegalStateException("no $init$ method found in " + input);
    }

    // $1 is the first (and only) argument of the static $init$: the TinkKeyset instance.
    init.insertBefore(
        "{ if (ch.tillo.tink.ciphertrust.horizon.HorizonCipherTrustHook.tryInstall($1)) return; }");

    File outFile =
        new File(outRoot, cls.getName().replace('.', '/') + ".class");
    outFile.getParentFile().mkdirs();
    try (FileOutputStream out = new FileOutputStream(outFile)) {
      out.write(cls.toBytecode());
    }
    System.out.println("patched -> " + outFile.getAbsolutePath());
  }

  private PatchTinkKeyset() {}
}
