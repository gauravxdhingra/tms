#!/usr/bin/env bash
# TMS local developer startup script.
# Checks prerequisites, starts infrastructure, waits for services to be healthy.
#
# Usage: ./scripts/start-dev.sh [--infra-only] [--skip-infra]
#
# Portability: macOS (Docker Desktop) and Ubuntu/Amazon Linux (Docker Engine).
# Linux note: OpenSearch requires vm.max_map_count=262144
#   sudo sysctl -w vm.max_map_count=262144

set -euo pipefail

TMS_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$TMS_ROOT/docker-compose.infra.yml"

# ── Color output: disabled when stdout is not a TTY or NO_COLOR is set ────────
if [[ -t 1 && -z "${NO_COLOR:-}" ]]; then
  RED='\033[0;31m'; YELLOW='\033[1;33m'; GREEN='\033[0;32m'; NC='\033[0m'
else
  RED=''; YELLOW=''; GREEN=''; NC=''
fi

info() { printf '%b[TMS]%b  %s\n' "$GREEN" "$NC" "$*"; }
warn() { printf '%b[WARN]%b %s\n' "$YELLOW" "$NC" "$*"; }
die()  { printf '%b[FAIL]%b %s\n' "$RED"    "$NC" "$*" >&2; exit 1; }

INFRA_ONLY=false
SKIP_INFRA=false
for arg in "$@"; do
  case $arg in
    --infra-only) INFRA_ONLY=true ;;
    --skip-infra) SKIP_INFRA=true ;;
    *) die "Unknown argument: $arg. Usage: $0 [--infra-only] [--skip-infra]" ;;
  esac
done

# ── TCP probe: nc where available, /dev/tcp fallback (bash built-in) ──────────
tcp_open() {
  local host=$1 port=$2
  if command -v nc &>/dev/null; then
    nc -z "$host" "$port" 2>/dev/null
  else
    # /dev/tcp is a bash built-in; works on Linux without nc installed
    (echo >/dev/tcp/"$host"/"$port") 2>/dev/null
  fi
}

# ── 1. Toolchain checks ────────────────────────────────────────────────────────

info "Checking toolchain..."

# Java 21+ (SDKMAN only)
command -v java &>/dev/null \
  || die "Java not found. Install via SDKMAN: sdk install java 21.0.7-tem && sdk default java 21.0.7-tem"
java_major=$(java -version 2>&1 | grep -oE '"[0-9]+' | head -1 | tr -d '"')
[[ "${java_major:-0}" -ge 21 ]] \
  || die "Java 21+ required. Found: $(java -version 2>&1 | head -1)"
info "Java OK — $(java -version 2>&1 | head -1)"

# Maven 3.9+ (SDKMAN only)
command -v mvn &>/dev/null \
  || die "Maven not found. Install via SDKMAN: sdk install maven 3.9.9 && sdk default maven 3.9.9"
mvn_ver=$(mvn --version 2>&1 | head -1)
mvn_patch=$(echo "$mvn_ver" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
mvn_ok=$(echo "$mvn_patch" | awk -F. '($1==3 && $2>=9)||($1>3){print "ok"}')
[[ "${mvn_ok:-}" == "ok" ]] || die "Maven 3.9+ required. Found: $mvn_ver"
info "Maven OK — $mvn_ver"

# Docker (Engine or Desktop — both expose 'docker info')
command -v docker &>/dev/null \
  || die "Docker not found. Install Docker Desktop (Mac) or Docker Engine + Compose plugin (Linux)."
docker info &>/dev/null \
  || die "Docker daemon not running. Start Docker Desktop or: sudo systemctl start docker"
info "Docker OK"

# Docker Compose v2 plugin
docker compose version &>/dev/null \
  || die "Docker Compose v2 plugin not found. Update Docker Desktop or: apt-get install docker-compose-plugin"
info "Docker Compose OK — $(docker compose version --short 2>/dev/null || docker compose version)"

# Node.js and Angular CLI are only required once UI work starts.
# They are intentionally not checked here to avoid blocking backend-only workflows.

# ── 2. Infrastructure ──────────────────────────────────────────────────────────

if [[ "$SKIP_INFRA" == "false" ]]; then
  [[ -f "$COMPOSE_FILE" ]] || die "Compose file not found: $COMPOSE_FILE"

  info "Starting infrastructure services..."
  docker compose -f "$COMPOSE_FILE" up -d --remove-orphans

  info "Waiting for services to become healthy..."

  # wait_tcp SERVICE PORT [MAX_WAIT_SECONDS]
  wait_tcp() {
    local svc=$1 port=$2 max=${3:-90} elapsed=0
    while [[ $elapsed -lt $max ]]; do
      if tcp_open localhost "$port"; then
        info "  ✓ $svc :$port"
        return 0
      fi
      sleep 3; elapsed=$((elapsed + 3))
    done
    warn "  ✗ $svc did not respond on :$port after ${max}s"
    warn "    Logs: docker compose -f $COMPOSE_FILE logs $svc"
    return 1
  }

  # wait_http SERVICE PORT PATH [MAX_WAIT_SECONDS]
  wait_http() {
    local svc=$1 port=$2 path=$3 max=${4:-90} elapsed=0
    while [[ $elapsed -lt $max ]]; do
      if curl -sf "http://localhost:${port}${path}" &>/dev/null; then
        info "  ✓ $svc http://localhost:$port$path"
        return 0
      fi
      sleep 3; elapsed=$((elapsed + 3))
    done
    warn "  ✗ $svc did not respond at :$port$path after ${max}s"
    warn "    Logs: docker compose -f $COMPOSE_FILE logs $svc"
    return 1
  }

  # Core services are required — die on failure.
  # Optional/slow services warn but allow the script to proceed.
  wait_tcp  "PostgreSQL"  5432  60  || die "PostgreSQL failed to start. Aborting."
  wait_tcp  "Kafka"       9092  90  || die "Kafka failed to start. Aborting."
  wait_tcp  "Redis"       6379  60  || die "Redis failed to start. Aborting."
  wait_tcp  "RabbitMQ"    5672  60  || die "RabbitMQ failed to start. Aborting."

  wait_http "RabbitMQ UI" 15672 "/"                  60  || warn "RabbitMQ UI slow — continuing."
  wait_http "Keycloak"    8180  "/health/ready"      120  || warn "Keycloak slow on first run (realm import) — check logs."
  wait_http "MinIO"       9000  "/minio/health/live"  60  || warn "MinIO slow — continuing."
  wait_http "OpenSearch"  9200  "/"                   90  || warn "OpenSearch slow — on Linux ensure vm.max_map_count=262144."
  wait_http "Mailpit"     8025  "/"                   30  || warn "Mailpit slow — continuing."
  wait_http "WireMock"    8089  "/__admin/"            30  || warn "WireMock slow — continuing."

  info "Infrastructure ready."
fi

# ── 3. Summary ────────────────────────────────────────────────────────────────

printf '\n%b══════════════════════════════════════════════════%b\n' "$GREEN" "$NC"
printf '%b  TMS Dev Environment Ready%b\n' "$GREEN" "$NC"
printf '%b══════════════════════════════════════════════════%b\n\n' "$GREEN" "$NC"
printf '  PostgreSQL   → localhost:5432  (db: tms, user: tms)\n'
printf '  Kafka        → localhost:9092\n'
printf '  Redis        → localhost:6379\n'
printf '  RabbitMQ     → localhost:5672  UI: http://localhost:15672  (guest/guest)\n'
printf '  Keycloak     → http://localhost:8180  (admin/admin)\n'
printf '  MinIO        → http://localhost:9001  (minioadmin/minioadmin)\n'
printf '  OpenSearch   → http://localhost:9200\n'
printf '  Mailpit      → http://localhost:8025\n'
printf '  WireMock     → http://localhost:8089/__admin/\n\n'

[[ "$INFRA_ONLY" == "true" ]] && exit 0

printf '  Run the Payment Hub:\n'
printf '    source ~/.sdkman/bin/sdkman-init.sh\n'
printf '    mvn install -DskipTests\n'
printf '    mvn -pl tms-payment-hub spring-boot:run -Dspring-boot.run.profiles=local\n\n'
printf '  Run the UI (once Angular project is initialized):\n'
printf '    cd tms-ui && npx ng serve\n\n'
