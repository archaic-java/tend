#!/bin/sh
set -eu
OAUTH_CLIENT_SECRET=$(cat /etc/tend-webui-client/value)
WEBUI_SECRET_KEY=$(cat /etc/tend-webui-session/value)
test -n "$OAUTH_CLIENT_SECRET"
test -n "$WEBUI_SECRET_KEY"
export OAUTH_CLIENT_SECRET WEBUI_SECRET_KEY
# Do not inherit an image/operator header authentication override.
unset WEBUI_AUTH_TRUSTED_EMAIL_HEADER WEBUI_AUTH_TRUSTED_NAME_HEADER
unset WEBUI_AUTH_TRUSTED_GROUPS_HEADER WEBUI_AUTH_TRUSTED_ROLE_HEADER
cd /app/backend
exec bash start.sh --no-access-log
