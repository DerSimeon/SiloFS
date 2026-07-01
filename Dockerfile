FROM gradle:8.10-jdk21 AS builder
WORKDIR /home/gradle/src
COPY --chown=gradle:gradle settings.gradle.kts build.gradle.kts gradle.properties detekt.yml ./
COPY --chown=gradle:gradle common/ common/
COPY --chown=gradle:gradle auth/ auth/
COPY --chown=gradle:gradle metadata/ metadata/
COPY --chown=gradle:gradle blob/ blob/
COPY --chown=gradle:gradle server/ server/
RUN gradle --no-daemon :server:installDist

FROM eclipse-temurin:21-jre-jammy
RUN useradd --create-home --shell /bin/bash silofs
USER silofs
WORKDIR /home/silofs
COPY --from=builder /home/gradle/src/server/build/install/silofs /home/silofs/silofs
RUN mkdir -p /var/lib/silofs/data
ENV S3_BIND_HOST=0.0.0.0 \
    S3_BIND_PORT=8080 \
    S3_REGION=us-east-1 \
    S3_DATA_DIR=/var/lib/silofs/data \
    S3_DB_URL=jdbc:postgresql://postgres:5432/silofs \
    S3_DB_USER=silofs \
    S3_DB_PASSWORD=silofs \
    S3_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE \
    S3_SECRET_ACCESS_KEY=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY \
    S3_ACCESS_KEY_SECRET_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
EXPOSE 8080
ENTRYPOINT ["/home/silofs/silofs/bin/silofs"]
