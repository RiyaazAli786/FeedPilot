#!/usr/bin/env bash
# Runs ON the server (uploaded + executed by deploy-backend.ps1). Idempotent: safe to re-run.
# Touches only: $APP_DIR, the "$DB_NAME" database/role, one nginx vhost for $DOMAIN, one container.
set -euo pipefail

: "${DOMAIN:?}" "${APP_NAME:?}" "${APP_DIR:?}" "${DB_NAME:?}" "${DB_USER:?}" "${APP_PORT:?}"
cd "$APP_DIR"

echo "[server] checking prerequisites"
for c in docker nginx psql certbot tar; do command -v "$c" >/dev/null || { echo "missing: $c"; exit 1; }; done

# Refuse to collide with something already listening on our port (unless it is our own container).
if ss -ltn "sport = :$APP_PORT" | grep -q LISTEN && ! docker ps --format '{{.Names}}' | grep -qx "$APP_NAME"; then
  echo "port $APP_PORT is already in use by something else; change APP_PORT in deploy/config.env"; exit 1
fi

echo "[server] database $DB_NAME"
if [ ! -s .db_password ]; then
  umask 077; head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 28 > .db_password
fi
DB_PASS="$(cat .db_password)"
sudo -u postgres psql -v ON_ERROR_STOP=1 -qtA <<SQL
DO \$\$ BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '$DB_USER') THEN
    CREATE ROLE "$DB_USER" LOGIN PASSWORD '$DB_PASS';
  ELSE
    ALTER ROLE "$DB_USER" PASSWORD '$DB_PASS';
  END IF;
END \$\$;
SQL
if ! sudo -u postgres psql -qtAc "SELECT 1 FROM pg_database WHERE datname='$DB_NAME'" | grep -q 1; then
  sudo -u postgres createdb -O "$DB_USER" "$DB_NAME"
fi

echo "[server] runtime env"
# .env.base = uploaded secrets (Jwt, Admin, Telegram...). Override DB + storage for this server.
grep -vE '^(DATABASE_URL|AssetStorage__|ASPNETCORE_URLS|PORT)=' .env.base > .env
{
  echo "DATABASE_URL=postgresql://$DB_USER:$DB_PASS@127.0.0.1:5432/$DB_NAME"
  echo "ASPNETCORE_URLS=http://127.0.0.1:$APP_PORT"
  echo "AssetStorage__Provider=Local"
} >> .env
chmod 600 .env

echo "[server] building image (this takes a few minutes the first time)"
mkdir -p src apk storage/upgrade storage/comments storage/generator
rm -rf src/* && tar -xzf backend.tar.gz -C src && rm -f backend.tar.gz
docker build -t "$APP_NAME:latest" src

echo "[server] (re)starting container"
docker rm -f "$APP_NAME" >/dev/null 2>&1 || true
# Host networking so the API reaches Postgres on 127.0.0.1:5432; it binds loopback only (ASPNETCORE_URLS).
docker run -d --name "$APP_NAME" --restart unless-stopped --network host \
  --env-file .env \
  -v "$APP_DIR/apk:/app/wwwroot/apk" \
  -v "$APP_DIR/storage/upgrade:/app/wwwroot/upgrade" \
  -v "$APP_DIR/storage/comments:/app/wwwroot/comments" \
  -v "$APP_DIR/storage/generator:/app/wwwroot/generator" \
  "$APP_NAME:latest" >/dev/null

echo "[server] nginx vhost for $DOMAIN"
CONF="/etc/nginx/sites-available/$DOMAIN.conf"
if [ ! -f "$CONF" ]; then
cat > "$CONF" <<NGINX
server {
    listen 80;
    listen [::]:80;
    server_name $DOMAIN;

    client_max_body_size 300m;

    location / {
        proxy_pass http://127.0.0.1:$APP_PORT;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection "upgrade";   # SignalR / WebSocket
        proxy_read_timeout 300s;
        proxy_send_timeout 300s;
    }
}
NGINX
fi
ln -sf "$CONF" "/etc/nginx/sites-enabled/$DOMAIN.conf"
nginx -t
systemctl reload nginx

echo "[server] TLS certificate"
if [ ! -d "/etc/letsencrypt/live/$DOMAIN" ]; then
  certbot --nginx -d "$DOMAIN" --non-interactive --agree-tos --register-unsafely-without-email --redirect
fi

echo "[server] health check"
for i in $(seq 1 30); do
  if curl -fsS "http://127.0.0.1:$APP_PORT/" >/dev/null 2>&1; then echo "API up: $(curl -fsS http://127.0.0.1:$APP_PORT/)"; exit 0; fi
  sleep 2
done
echo "API did not become healthy; last logs:"; docker logs --tail 40 "$APP_NAME"; exit 1
