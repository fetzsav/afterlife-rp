#!/usr/bin/env python3
"""Minimal Minecraft RCON client for development.

Usage: rcon.py [--host HOST] [--port PORT] [--password-file FILE] <command...>
The target container is resolved from AFTERLIFE_SERVER_UUID (or pass --host);
the password is read from a file outside the repo (never commit it — rule 12).
"""
import argparse
import os
import socket
import struct
import subprocess
import sys

LOGIN, COMMAND = 3, 2
# Pterodactyl server UUID, used only to resolve the container IP when
# --host is not given. Environment-specific: keep it out of the repo.
SERVER_UUID = os.environ.get("AFTERLIFE_SERVER_UUID", "")
# Kept outside the repo (rule 12). Override with --password-file or
# the AFTERLIFE_RCON_PASSWORD_FILE environment variable.
DEFAULT_PASSWORD_FILE = os.path.expanduser("~/.afterlife/rcon-password.txt")


def container_ip():
    """The container IP can change across restarts; resolve it each run."""
    if not SERVER_UUID:
        raise SystemExit(
            "set AFTERLIFE_SERVER_UUID to the Pterodactyl server UUID, "
            "or pass --host")
    return subprocess.check_output(
        ["docker", "inspect", SERVER_UUID, "--format",
         "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}"],
        text=True).strip()


def send_packet(sock, req_id, ptype, payload):
    body = struct.pack("<ii", req_id, ptype) + payload.encode("utf-8") + b"\x00\x00"
    sock.sendall(struct.pack("<i", len(body)) + body)


def recv_packet(sock):
    raw_len = sock.recv(4)
    if len(raw_len) < 4:
        raise ConnectionError("connection closed")
    (length,) = struct.unpack("<i", raw_len)
    data = b""
    while len(data) < length:
        chunk = sock.recv(length - len(data))
        if not chunk:
            raise ConnectionError("connection closed mid-packet")
        data += chunk
    req_id, ptype = struct.unpack("<ii", data[:8])
    return req_id, ptype, data[8:-2].decode("utf-8", errors="replace")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default=None)
    parser.add_argument("--port", type=int, default=25575)
    parser.add_argument(
        "--password-file",
        default=os.environ.get("AFTERLIFE_RCON_PASSWORD_FILE",
                               DEFAULT_PASSWORD_FILE))
    parser.add_argument("command", nargs="+")
    args = parser.parse_args()

    with open(args.password_file, encoding="utf-8") as handle:
        password = handle.read().strip()

    host = args.host or container_ip()
    with socket.create_connection((host, args.port), timeout=10) as sock:
        send_packet(sock, 1, LOGIN, password)
        req_id, _, _ = recv_packet(sock)
        if req_id == -1:
            print("RCON auth failed", file=sys.stderr)
            return 1
        send_packet(sock, 2, COMMAND, " ".join(args.command))
        _, _, response = recv_packet(sock)
        print(response)
    return 0


if __name__ == "__main__":
    sys.exit(main())
