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
RUN useradd --create-home --shell /bin/bash s3server
USER s3server
WORKDIR /home/s3server
COPY --from=builder /home/gradle/src/server/build/install/server /home/s3server/server
RUN mkdir -p /var/lib/s3server/data
ENV S3_BIND_HOST=0.0.0.0 \
    S3_BIND_PORT=8080 \
    S3_REGION=us-east-1 \
    S3_DATA_DIR=/var/lib/s3server/data \
    S3_DB_URL=jdbc:postgresql://postgres:5432/s3server \
    S3_DB_USER=s3server \
    S3_DB_PASSWORD=s3server \
    S3_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE \
    S3_SECRET_ACCESS_KEY=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY
EXPOSE 8080
ENTRYPOINT ["/home/s3server/server/bin/server"]
