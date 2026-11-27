#!/usr/bin/env bash
set -euo pipefail

# Prevent Git Bash / MSYS2 on Windows from rewriting remote Android paths (/data/local/tmp/...).
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL="*"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
RESULTS_DIR="${SCRIPT_DIR}/results"
REMOTE_JAR="/data/local/tmp/velocast-server.jar"
REMOTE_OUT="/data/local/tmp/velocast-probe.json"
LOCAL_JAR="server-core/build/outputs/dex/velocast-server.jar"
MAIN_CLASS="com.mastersoft.velocast.server.CapabilityProbeMain"

SERIAL="${ADB_SERIAL:-}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    -s|--serial)
      if [[ $# -lt 2 ]]; then
        echo "Missing value for $1" >&2
        exit 1
      fi
      SERIAL="$2"
      shift 2
      ;;
    -h|--help)
      echo "Usage: $0 [-s <adb-serial>]"
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 1
      ;;
  esac
done

ADB_ARGS=()
if [[ -n "$SERIAL" ]]; then
  ADB_ARGS=(-s "$SERIAL")
else
  DEVICES=()
  while IFS= read -r line; do
    if [[ "$line" =~ ^([^[:space:]]+)[[:space:]]+device([[:space:]]|$) ]]; then
      DEVICES+=("${BASH_REMATCH[1]}")
    fi
  done < <(adb devices)

  if [[ ${#DEVICES[@]} -eq 0 ]]; then
    echo "No authorised ADB device detected. Check 'adb devices -l'." >&2
    exit 1
  elif [[ ${#DEVICES[@]} -gt 1 ]]; then
    echo "Multiple ADB devices detected; pass -s <serial>:" >&2
    adb devices -l >&2
    exit 1
  fi
  ADB_ARGS=(-s "${DEVICES[0]}")
fi

echo "==> Building :server-core dex jar..."
cd "$REPO_ROOT"
./gradlew :server-core:assembleDexJar --console=plain

if [[ ! -f "$LOCAL_JAR" ]]; then
  echo "Expected dex jar not found at $LOCAL_JAR" >&2
  exit 1
fi

MODEL_RAW="$(adb "${ADB_ARGS[@]}" shell getprop ro.product.model | tr -d '\r')"
API_RAW="$(adb "${ADB_ARGS[@]}" shell getprop ro.build.version.sdk | tr -d '\r')"
RELEASE_RAW="$(adb "${ADB_ARGS[@]}" shell getprop ro.build.version.release | tr -d '\r')"
MANUFACTURER_RAW="$(adb "${ADB_ARGS[@]}" shell getprop ro.product.manufacturer | tr -d '\r')"

MODEL_SAFE="$(printf '%s' "$MODEL_RAW" | tr -cs 'A-Za-z0-9._-' '-' | sed 's/^-//; s/-$//')"
if [[ -z "$MODEL_SAFE" ]]; then
  MODEL_SAFE="unknown-device"
fi
REL_OUT_FILE="tools/spike/results/${MODEL_SAFE}-android${API_RAW}.json"
mkdir -p "$RESULTS_DIR"

echo "==> Target: ${MANUFACTURER_RAW} ${MODEL_RAW} (Android ${RELEASE_RAW}, API ${API_RAW})"
echo "==> Pushing ${LOCAL_JAR} -> ${REMOTE_JAR}"
adb "${ADB_ARGS[@]}" push "$LOCAL_JAR" "$REMOTE_JAR" >/dev/null

echo "==> Running CapabilityProbe as shell user via app_process..."
adb "${ADB_ARGS[@]}" shell "rm -f '${REMOTE_OUT}' && CLASSPATH='${REMOTE_JAR}' app_process /system/bin '${MAIN_CLASS}' --out '${REMOTE_OUT}'"

echo "==> Pulling results -> ${REL_OUT_FILE}"
adb "${ADB_ARGS[@]}" pull "$REMOTE_OUT" "$REL_OUT_FILE" >/dev/null
adb "${ADB_ARGS[@]}" shell "rm -f '${REMOTE_OUT}'" >/dev/null || true

echo ""
echo "Summary (${REL_OUT_FILE}):"
if command -v python3 >/dev/null 2>&1 && python3 --version >/dev/null 2>&1; then
  python3 - "$REL_OUT_FILE" <<'PYEOF'
import json, sys
path = sys.argv[1]
rows = []
with open(path, "r", encoding="utf-8") as f:
    for line in f:
        line = line.strip()
        if line:
            rows.append(json.loads(line))
header = ("CAPABILITY", "STATUS", "REASON / PERMISSION", "DETAIL")
widths = [max(len(header[0]), *(len(r.get("capability", "")) for r in rows)),
          max(len(header[1]), *(len(r.get("status", "")) for r in rows)),
          max(len(header[2]), *(len(r.get("permission") or r.get("reason") or "-") for r in rows))]
fmt = f"{{:<{widths[0]}}}  {{:<{widths[1]}}}  {{:<{widths[2]}}}  {{}}"
print(fmt.format(*header))
print(fmt.format("-" * widths[0], "-" * widths[1], "-" * widths[2], "------"))
for r in rows:
    rp = r.get("permission") or r.get("reason") or "-"
    detail = r.get("detail") or "-"
    print(fmt.format(r.get("capability", ""), r.get("status", ""), rp, detail))
PYEOF
else
  awk '
  function extract(line, key,    pat, rest, out, c, esc) {
    pat = "\"" key "\":\""
    idx = index(line, pat)
    if (idx == 0) return "-"
    rest = substr(line, idx + length(pat))
    out = ""
    esc = 0
    for (i = 1; i <= length(rest); i++) {
      c = substr(rest, i, 1)
      if (esc) {
        out = out c
        esc = 0
      } else if (c == "\\") {
        esc = 1
      } else if (c == "\"") {
        break
      } else {
        out = out c
      }
    }
    return out
  }
  BEGIN {
    printf "%-20s  %-12s  %-48s  %s\n", "CAPABILITY", "STATUS", "REASON / PERMISSION", "DETAIL"
    printf "%-20s  %-12s  %-48s  %s\n", "--------------------", "------------", "------------------------------------------------", "------"
  }
  NF > 0 {
    cap = extract($0, "capability")
    st = extract($0, "status")
    rp = extract($0, "permission")
    if (rp == "-") rp = extract($0, "reason")
    if (length(rp) > 48) rp = substr(rp, 1, 45) "..."
    det = extract($0, "detail")
    printf "%-20s  %-12s  %-48s  %s\n", cap, st, rp, det
  }' "$REL_OUT_FILE"
fi
