#!/bin/bash
set -euo pipefail

case "${1:-build}" in
  build) flatmark build -i docs ;;
  serve) flatmark run -i docs ;;
  *) echo "Usage: $0 [build|serve]" >&2; exit 1 ;;
esac
