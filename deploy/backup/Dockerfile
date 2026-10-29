# Nightly encrypted backups (deploy/compose.yml): pg_dump from the same PostgreSQL version as the
# server, age to encrypt, rclone to upload to R2.
FROM postgres:18-alpine
RUN apk add --no-cache bash age rclone curl
COPY backup.sh restore.sh schedule.sh /usr/local/bin/
RUN chmod 755 /usr/local/bin/backup.sh /usr/local/bin/restore.sh /usr/local/bin/schedule.sh
USER postgres
WORKDIR /tmp
ENTRYPOINT ["/usr/local/bin/schedule.sh"]
