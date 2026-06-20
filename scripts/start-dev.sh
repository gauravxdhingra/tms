#!/usr/bin/env bash
# TMS local developer startup script.
# Run once per dev session: checks all prerequisites, starts infra, waits for health.
# Usage: ./scripts/start-dev.sh [--infra-only] [--skip-infra]

set -euo pipefail

TMS_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$TMS_ROOT/docker-compose.infra.yml"

RED='\033[0;31m'; YELLOW='\033[1;33m'; GREEN='\033[0;32m'; NC='\033[0m'
info()  { echo -e "${GREEN}[TMS]${NC}  $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }
die()   { echo -e "${RED}[FAIL]${NC} $*"; exit 1; }

INFRA_ONLY=false
SKIP_INFRA=false
for arg in "$@"; do
  case $arg in
    --infra-only) INFRA_ONLY=true ;;
    --skip-infra) SKIP_INFRA=true ;;
  esac
done

# ── 1. Toolchain checks ────────────────────────────────────────────────────────

info "Checking toolchain..."

# Java 21
if ! java_ver=$(java -version 2>&1 | head -1); then
  die "Java not found. Run: sdk install java 21.0.7-tem && sdk default java 21.0.7-tem"
fi
java_major=$(java -version 2>&1 | grep -oE '"[0-9]+' | head -1 | tr -d '"')
[[ "$java_major" -ge 21 ]] || die "Java 21+ required. Found: $java_ver"
info "Java OK — $java_ver"

# Maven 3.9+
if ! mvn_ver=$(mvn --version 2>&1 | head -1); then
  die "Maven not found. Run: sdk install maven 3.9.9 && sdk default maven 3.9.9"
fi
mvn_minor=$(mvn --version 2>&1 | grep -oE 'Maven [0-9]+\.[0-9]+' | grep -oE '[0-9]+\.[0-9]+$')
mvn_major_minor_ok=$(echo "$mvn_minor" | awk -F. '($1==3 && $2>=9){print "ok"}')
[[ "$mvn_major_minor_ok" == "ok" ]] || die "Maven 3.9+ required. Found: $mvn_ver"
info "Maven OK — $mvn_ver"

# Node 20+
if ! node --version &>/dev/null; then
  die "Node.js not found. Install Node 20+ via nvm or https://nodejs.org"
fi
node_major=$(node --version | grep -oE '[0-9]+' | head -1)
[[ "$node_major" -ge 20 ]] || die "Node 20+ required. Found: $(node --version)"
info "Node OK — $(node --version)"

# Angular CLI
if ! ng version &>/dev/null; then
  die "Angular CLI not found. Run: npm install -g @angular/cli@19"
fi
ng_ver=$(ng version 2>&1 | grep "Angular CLI" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
info "Angular CLI OK — v${ng_ver}"

# Docker
if ! docker info &>/dev/null; then
  die "Docker daemon not running. Start Docker Desktop and retry."
fi
info "Docker OK"

# Docker Compose
if ! docker compose version &>/dev/null; then
  die "Docker Compose v2 plugin not found. Update Docker Desktop."
fi
info "Docker Compose OK — $(docker compose version --short)"

# ── 2. Infrastructure ──────────────────────────────────────────────────────────

if [[ "$SKIP_INFRA" == "false" ]]; then
  if [[ ! -f "$COMPOSE_FILE" ]]; then
    die "Infra compose file not found: $COMPOSE_FILE"
  fi

  info "Starting infrastructure services..."
  docker compose -f "$COMPOSE_FILE" up -d --remove-orphans

  info "Waiting for services to become healthy..."

  wait_for_healthy() {
    local service=$1 port=$2 path=${3:-""} max_wait=${4:-90}
    local elapsed=0
    while [[ $elapsed -lt $max_wait ]]; do
      if curl -sf "http://localhost:${port}${path}" &>/dev/null; then
        info "  ✓ ${service} ready on :${port}"
        return 0
      fi
      sleep 3; elapsed=$((elapsed + 3))
    done
    warn "  ✗ ${service} did not respond on :${port} after ${max_wait}s — check logs: docker compose -f $COMPOSE_FILE logs ${service}"
    return 1
  }

  wait_for_tcp() {
    local service=$1 port=$2 max_wait=${3:-90}
    local elapsed=0
    while [[ $elapsed -lt $max_wait ]]; do
      if nc -z localhost "$port" 2>/dev/null; then
        info "  ✓ ${service} ready on :${port}"
        return 0
      fi
      sleep 3; elapsed=$((elapsed + 3))
    done
    warn "  ✗ ${service} did not respond on :${port} after ${max_wait}s"
    return 1
  }

  wait_for_healthy "PostgreSQL"    5432  "" 60 || true   # nc-based below
  wait_for_tcp     "PostgreSQL"    5432  60 || true
  wait_for_tcp     "Kafka"         9092  90 || true
  wait_for_tcp     "Redis"         6379  60 || true
  wait_for_tcp     "RabbitMQ"      5672  60 || true
  wait_for_healthy "RabbitMQ UI"   15672 "/" 60 || true
  wait_for_healthy "Keycloak"      8180  "/health/ready" 120 || true
  wait_for_healthy "MinIO"         9000  "/minio/health/live" 60 || true
  wait_for_healthy "OpenSearch"    9200  "/" 90 || true
  wait_for_healthy "Mailpit"       8025  "/" 30 || true
  wait_for_healthy "WireMock"      8089  "/__admin/health" 30 || true

  info "Infrastructure ready."
fi

# ── 3. Summary ────────────────────────────────────────────────────────────────

echo ""
echo -e "${GREEN}══════════════════════════════════════════════════${NC}"
echo -e "${GREEN}  TMS Dev Environment Ready${NC}"
echo -e "${GREEN}══════════════════════════════════════════════════${NC}"
echo "  PostgreSQL   → localhost:5432  (db: tms, user: tms)"
echo "  Kafka        → localhost:9092"
echo "  Redis        → localhost:6379"
echo "  RabbitMQ     → localhost:5672  UI: http://localhost:15672  (guest/guest)"
echo "  Keycloak     → http://localhost:8180  (admin/admin)"
echo "  MinIO        → http://localhost:9001  (minioadmin/minioadmin)"
echo "  OpenSearch   → http://localhost:9200"
echo "  Mailpit      → http://localhost:8025"
echo "  WireMock     → http://localhost:8089"
echo ""

if [[ "$INFRA_ONLY" == "true" ]]; then
  exit 0
fi

echo "  To run a service:"
echo "    source ~/.sdkman/bin/sdkman-init.sh"
echo "    mvn -pl tms-payment-hub spring-boot:run -Dspring-boot.run.profiles=local"
echo ""
echo "  To run the UI:"
echo "    cd tms-ui && ng serve"
echo ""
