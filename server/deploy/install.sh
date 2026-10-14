#!/usr/bin/env bash
# Run on the Linux server from the directory holding pood-server, pood.service and pood.example.toml:
#   sudo ./install.sh
set -euo pipefail
cd "$(dirname "$0")"

install -m 755 pood-server /usr/local/bin/pood-server
install -m 644 pood.service /etc/systemd/system/pood.service
install -d -m 755 /etc/pood
if [ ! -f /etc/pood/pood.toml ]; then
  sed 's|^dbPath = .*|dbPath = "/var/lib/pood/pood.db"|' pood.example.toml > /etc/pood/pood.toml
  echo "Created /etc/pood/pood.toml: set auth.allowedUsers and qwen.realtimeUrl"
fi
if [ ! -f /etc/pood/env ]; then
  install -m 600 /dev/null /etc/pood/env
  echo "DASHSCOPE_API_KEY=" > /etc/pood/env
  echo "Created /etc/pood/env: set DASHSCOPE_API_KEY"
fi
systemctl daemon-reload
systemctl enable pood
systemctl restart pood || true
echo "Then expose it on the tailnet: sudo tailscale serve --bg --https=8790 http://127.0.0.1:8790"
