#!/usr/bin/env bash
# Generates a self-signed CA + server certificate for the PersonalRecorder relay.
#
# Usage:  ./gen_cert.sh <server-ip-or-hostname> [output-dir]
#   - IPs and hostnames both work (SAN is set automatically).
#
# After generating:
#   1) Run the relay with TLS:
#        python3 relay_server.py --cert certs/server.pem --key certs/server.key
#   2) Ship the CA to the app so it trusts your cert (build-time only):
#        cp certs/ca.pem ../app/src/main/assets/ca.pem
#      then rebuild the APK. The app will then trust this CA for wss:// URLs.
set -euo pipefail

HOST="${1:?usage: ./gen_cert.sh <server-ip-or-hostname> [output-dir]}"
DIR="${2:-./certs}"

mkdir -p "$DIR"
cd "$DIR"

# SAN must be IP: or DNS: depending on what the user passed.
if [[ "$HOST" =~ ^[0-9.]+$ ]]; then
    SAN="subjectAltName=IP:$HOST"
else
    SAN="subjectAltName=DNS:$HOST"
fi

openssl genrsa -out ca.key 2048
openssl req -x509 -new -nodes -key ca.key -sha256 -days 3650 \
    -subj "/CN=PersonalRecorder Lab CA" -out ca.pem
openssl genrsa -out server.key 2048
openssl req -new -key server.key -subj "/CN=$HOST" -out server.csr
printf "%s\n" "$SAN" > san.ext
openssl x509 -req -in server.csr -CA ca.pem -CAkey ca.key -CAcreateserial \
    -out server.pem -days 825 -sha256 -extfile san.ext
rm -f server.csr san.ext ca.srl

echo
echo "Generated: $DIR/ca.pem  $DIR/server.pem  $DIR/server.key"
echo "1) Run relay with TLS:     python3 relay_server.py --cert certs/server.pem --key certs/server.key"
echo "2) Ship CA to the app:     cp certs/ca.pem ../app/src/main/assets/ca.pem   (then rebuild the APK)"
echo "3) Connect the app with:   wss://$HOST:8765"