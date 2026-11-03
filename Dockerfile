# Custom Evertrust Horizon image that adds a `ciphertrust://` Tink vault master-key scheme.
#
# The build stage compiles the hook jar against the exact TinkKeyset shipped in the target
# Horizon image, then patches TinkKeyset.$init$ so a `ciphertrust://` master key routes to
# ch.tillo.tink:tink-ciphertrust. Every other master-key scheme (aws-kms/gcp-kms/pkcs11/
# plaintext) is left byte-for-byte unchanged. Patching happens in the build stage because the
# Horizon runtime image ships no JDK tools (`jar`/`javap` are unavailable there); the final
# stage is COPY-only.

ARG HORIZON_IMAGE=registry.evertrust.io/horizon
# The ONE place the Horizon version lives: CI reads it from here for the image tags, and
# Renovate moves it (fleet's renovate job carries the registry.evertrust.io login).
ARG HORIZON_VERSION=2.10.2

# ---- stage 1: the target Horizon image ----
FROM ${HORIZON_IMAGE}:${HORIZON_VERSION} AS horizon

# ---- stage 2: build the hook jar, patch TinkKeyset, verify ----
FROM maven:3.10-eclipse-temurin-21 AS build
ARG HORIZON_VERSION
WORKDIR /build
# The glob matches both the app jar and its -assets sibling; select the one that actually
# contains models.tink.TinkKeyset (content-checked, not name-guessed).
COPY --from=horizon /horizon/lib/fr.evertrust.horizon-*.jar /build/horizon-jars/
RUN set -eux; \
    HJAR=""; \
    for j in /build/horizon-jars/*.jar; do \
        if jar tf "$j" | grep -qx 'models/tink/TinkKeyset.class'; then HJAR="$j"; fi; \
    done; \
    test -n "$HJAR"; \
    cp "$HJAR" /build/horizon.jar
COPY ci_settings.xml pom.xml ./
COPY src ./src
# The optional CI_JOB_TOKEN buildkit secret lets Maven also consult the extension's GitLab package
# registry (for unreleased versions); without it the dependency resolves from Maven Central.
RUN --mount=type=secret,id=ci_job_token,env=CI_JOB_TOKEN \
    mvn -B --no-transfer-progress -s ci_settings.xml -Dhorizon.jar=/build/horizon.jar \
        -Dhorizon.version="${HORIZON_VERSION}" package
# The provided jars are compiled against, never shipped: Horizon's own copy is what runs. Fail
# the build unless pom.xml names exactly the versions this image ships in /horizon/lib, so a
# HORIZON_VERSION bump that moves one of them cannot build until the pom follows.
COPY --from=horizon /horizon/lib/ /build/horizon-lib/
COPY check-provided-jars.sh ./
RUN --mount=type=secret,id=ci_job_token,env=CI_JOB_TOKEN \
    mvn -B -q --no-transfer-progress -s ci_settings.xml -Dhorizon.jar=/build/horizon.jar \
        -Dhorizon.version="${HORIZON_VERSION}" dependency:list -DincludeScope=provided \
        -DexcludeTransitive=true -DoutputFile=/build/provided.txt \
 && sh check-provided-jars.sh /build/provided.txt /build/horizon-lib
# Patch $init$, then fail the build unless the patched class actually carries the hook call.
RUN set -eux; \
    mkdir -p /build/patch/orig; \
    (cd /build/patch/orig && jar xf /build/horizon.jar models/tink/TinkKeyset.class); \
    HOOK_JAR="$(ls /build/target/horizon-ciphertrust-hook-*.jar)"; \
    JAVASSIST_JAR="$(ls /build/target/deps/javassist-*.jar)"; \
    java -cp "/build/horizon.jar:$HOOK_JAR:$JAVASSIST_JAR" \
        ch.tillo.tink.ciphertrust.horizon.PatchTinkKeyset \
        /build/patch/orig/models/tink/TinkKeyset.class /build/patched; \
    test -f /build/patched/models/tink/TinkKeyset.class; \
    javap -p -c -classpath /build/patched models.tink.TinkKeyset \
        | grep -q 'HorizonCipherTrustHook.tryInstall' \
        || { echo 'ERROR: TinkKeyset patch did not apply'; exit 1; }

# ---- stage 3: the patched Horizon image (COPY-only) ----
FROM ${HORIZON_IMAGE}:${HORIZON_VERSION}

# 1. Extension + hook on the wildcard classpath (lib/*). Horizon already ships the other
#    runtime deps (tink 1.21.0, gson 2.13.2).
COPY --from=build /build/target/deps/tink-ciphertrust-*.jar /horizon/lib/
COPY --from=build /build/target/horizon-ciphertrust-hook-*.jar /horizon/lib/

# 2. The patched class goes in etc/, which precedes lib/* on the classpath
#    (java ... -cp etc/:lib/*), so it deterministically shadows the vendor one.
COPY --from=build /build/patched/models/tink/TinkKeyset.class /horizon/etc/models/tink/TinkKeyset.class
