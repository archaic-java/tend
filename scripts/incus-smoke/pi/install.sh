#!/bin/sh
set -eu

# The bootstrap runs as root once via cloud-init; Pi runs as an unprivileged
# service user. Node is pinned separately from Debian's older packaged version.
test "$(uname -m)" = x86_64
cd /opt/homelab-pi
curl --fail --show-error --location --retry 3 \
  https://nodejs.org/dist/v24.19.0/node-v24.19.0-linux-x64.tar.xz \
  --output /var/tmp/homelab-node.tar.xz
printf '%s  %s\n' \
  14b342e71204f811bde6153be8e04b62aef63c236fef92b55f9c83154b409647 \
  /var/tmp/homelab-node.tar.xz | sha256sum --check
mkdir -p /opt/node
tar -xJf /var/tmp/homelab-node.tar.xz --strip-components=1 -C /opt/node
rm /var/tmp/homelab-node.tar.xz
export PATH=/opt/node/bin:$PATH
# Install the reviewed service release, including prebuilt frontend assets.
curl --fail --show-error --location --retry 3 \
  https://github.com/mitschwimmer/pi-web-sandbox/releases/download/${release_ref}/pi-web-sandbox.tgz \
  --output /var/tmp/pi-web-sandbox.tgz
printf '%s  %s\n' '${archive_sha256}' /var/tmp/pi-web-sandbox.tgz | sha256sum --check
mkdir -p /opt/pi-web-sandbox
tar -xzf /var/tmp/pi-web-sandbox.tgz --no-same-owner -C /opt/pi-web-sandbox
rm /var/tmp/pi-web-sandbox.tgz
cd /opt/pi-web-sandbox
npm ci --omit=dev --ignore-scripts
ln -s /usr/bin/fdfind /usr/local/bin/fd
install -d -o pi -g pi -m 0700 /var/lib/pi/sessions
systemctl disable --now ssh.service ssh.socket 2>/dev/null || true
systemctl daemon-reload
systemctl enable --now homelab-pi.service
