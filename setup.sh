#!/usr/bin/env bash
# JTBS Setup Wizard Shell Launcher
set -e
command -v node >/dev/null 2>&1 || { echo >&2 "Node.js is required but not installed. Aborting."; exit 1; }
node setup.js
