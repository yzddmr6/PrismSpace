#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION_FILE="$ROOT_DIR/version.properties"
BUMP="none"
EXPLICIT_VERSION=""
OUTPUT_FILE=""

usage() {
  cat <<'USAGE'
Usage: scripts/resolve-release-version.sh [--bump none|patch|minor|major] [--version x.y.z] [--write-env path]

Resolves the target release version from version.properties. If a newer target
version is selected, the script updates version.properties and increments
VERSION_CODE by one.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --bump)
      BUMP="${2:-}"
      shift 2
      ;;
    --version)
      EXPLICIT_VERSION="${2:-}"
      shift 2
      ;;
    --write-env)
      OUTPUT_FILE="${2:-}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ ! -f "$VERSION_FILE" ]]; then
  echo "Missing $VERSION_FILE" >&2
  exit 1
fi

case "$BUMP" in
  none|patch|minor|major) ;;
  *)
    echo "--bump must be one of: none, patch, minor, major" >&2
    exit 2
    ;;
esac

if [[ -n "$EXPLICIT_VERSION" && "$BUMP" != "none" ]]; then
  echo "Use either --version or --bump, not both." >&2
  exit 2
fi

read_property() {
  local key="$1"
  sed -n "s/^${key}=//p" "$VERSION_FILE" | tail -1 | tr -d '\r'
}

validate_semver() {
  [[ "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]
}

semver_cmp() {
  local a_major a_minor a_patch b_major b_minor b_patch
  IFS=. read -r a_major a_minor a_patch <<<"$1"
  IFS=. read -r b_major b_minor b_patch <<<"$2"
  for part in major minor patch; do
    local a_var="a_${part}"
    local b_var="b_${part}"
    local a_value="${!a_var}"
    local b_value="${!b_var}"
    if ((10#$a_value > 10#$b_value)); then echo 1; return; fi
    if ((10#$a_value < 10#$b_value)); then echo -1; return; fi
  done
  echo 0
}

bump_version() {
  local current="$1"
  local bump="$2"
  local major minor patch
  IFS=. read -r major minor patch <<<"$current"
  case "$bump" in
    patch) patch=$((10#$patch + 1)) ;;
    minor) minor=$((10#$minor + 1)); patch=0 ;;
    major) major=$((10#$major + 1)); minor=0; patch=0 ;;
  esac
  echo "${major}.${minor}.${patch}"
}

CURRENT_VERSION="$(read_property VERSION_NAME)"
CURRENT_CODE="$(read_property VERSION_CODE)"

if ! validate_semver "$CURRENT_VERSION"; then
  echo "VERSION_NAME must use x.y.z format, got '$CURRENT_VERSION'" >&2
  exit 1
fi

if [[ ! "$CURRENT_CODE" =~ ^[1-9][0-9]*$ ]]; then
  echo "VERSION_CODE must be a positive integer, got '$CURRENT_CODE'" >&2
  exit 1
fi

if [[ -n "$EXPLICIT_VERSION" ]]; then
  TARGET_VERSION="${EXPLICIT_VERSION#v}"
  TARGET_VERSION="${TARGET_VERSION#V}"
else
  TARGET_VERSION="$CURRENT_VERSION"
  if [[ "$BUMP" != "none" ]]; then
    TARGET_VERSION="$(bump_version "$CURRENT_VERSION" "$BUMP")"
  fi
fi

if ! validate_semver "$TARGET_VERSION"; then
  echo "Target version must use x.y.z format, got '$TARGET_VERSION'" >&2
  exit 1
fi

CMP="$(semver_cmp "$TARGET_VERSION" "$CURRENT_VERSION")"
if [[ "$CMP" == "-1" ]]; then
  echo "Target version $TARGET_VERSION is older than current version $CURRENT_VERSION" >&2
  exit 1
fi

CHANGED=false
TARGET_CODE="$CURRENT_CODE"
if [[ "$CMP" == "1" ]]; then
  CHANGED=true
  TARGET_CODE=$((10#$CURRENT_CODE + 1))
  {
    echo "# PrismSpace release version. GitHub Actions updates this file for formal releases."
    echo "VERSION_NAME=$TARGET_VERSION"
    echo "VERSION_CODE=$TARGET_CODE"
  } > "$VERSION_FILE"
fi

TAG="v$TARGET_VERSION"
echo "Resolved release $TAG (versionCode $TARGET_CODE, changed=$CHANGED)"

if [[ -n "$OUTPUT_FILE" ]]; then
  {
    echo "version=$TARGET_VERSION"
    echo "version_code=$TARGET_CODE"
    echo "tag=$TAG"
    echo "changed=$CHANGED"
  } >> "$OUTPUT_FILE"
fi
