# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jre-noble
RUN useradd --create-home --shell /usr/sbin/nologin ynabsync \
 && mkdir -p /data \
 && chown ynabsync:ynabsync /data
COPY build/install/ynab-psd2-sync /opt/ynab-psd2-sync
USER ynabsync
WORKDIR /data
ENTRYPOINT ["/opt/ynab-psd2-sync/bin/ynab-psd2-sync"]
