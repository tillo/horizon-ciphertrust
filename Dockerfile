# Custom Evertrust Horizon image that adds a `ciphertrust://` Tink vault master-key scheme.
#
# Build stage compiles the hook jar against the exact TinkKeyset interface shipped in the target
# Horizon image; the final stage adds the extension + hook to the classpath and patches
# TinkKeyset.$init$ so a `ciphertrust://` master key routes to ch.tillo.tink:tink-ciphertrust.
# Every other master-key scheme (aws-kms/gcp-kms/pkcs11/plaintext) is left byte-for-byte unchanged.

ARG HORIZON_IMAGE=registry.evertrust.io/horizon
ARG HORIZON_VERSION=2.10.2

# ---- stage 1: extract Horizon's TinkKeyset for compilation ----
FROM ${HORIZON_IMAGE}:${HORIZON_VERSION} AS horizon

# ---- stage 2: build the hook jar + collect runtime deps ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# The Horizon app jar (contains models.tink.TinkKeyset) to compile against.
COPY --from=horizon /horizon/lib/fr.evertrust.horizon-*.jar /build/horizon.jar
COPY ci_settings.xml pom.xml ./
COPY src ./src
# CI_JOB_TOKEN (buildkit secret) lets Maven read ch.tillo.tink:tink-ciphertrust from project 212.
RUN --mount=type=secret,id=ci_job_token,env=CI_JOB_TOKEN \
    mvn -B --no-transfer-progress -s ci_settings.xml -Dhorizon.jar=/build/horizon.jar package

# ---- stage 3: the patched Horizon image ----
FROM ${HORIZON_IMAGE}:${HORIZON_VERSION}
USER root

# 1. Add the extension + hook to the wildcard classpath (lib/*).
COPY --from=build /build/target/deps/tink-ciphertrust-*.jar /horizon/lib/
COPY --from=build /build/target/horizon-ciphertrust-hook-*.jar /horizon/lib/

# 2. Patch TinkKeyset.$init$ and place the result in etc/, which precedes lib/* on the classpath
#    (java ... -cp etc/:lib/*), so the patched class deterministically shadows the vendor one.
COPY --from=build /build/target/deps/javassist-*.jar /tmp/patch/javassist.jar
RUN set -eux; \
    mkdir -p /tmp/patch/orig; \
    HJAR="$(ls /horizon/lib/fr.evertrust.horizon-*.jar)"; \
    (cd /tmp/patch/orig && jar xf "$HJAR" models/tink/TinkKeyset.class); \
    java -cp "/horizon/lib/*:/tmp/patch/javassist.jar" \
        ch.tillo.tink.ciphertrust.horizon.PatchTinkKeyset \
        /tmp/patch/orig/models/tink/TinkKeyset.class /horizon/etc; \
    test -f /horizon/etc/models/tink/TinkKeyset.class; \
    rm -rf /tmp/patch

# Fail the build if the patched class does not actually carry the hook call.
RUN javap -p -c -classpath /horizon/etc models.tink.TinkKeyset \
      | grep -q 'HorizonCipherTrustHook.tryInstall' \
      || (echo 'ERROR: TinkKeyset patch did not apply' && exit 1)

USER 1001
