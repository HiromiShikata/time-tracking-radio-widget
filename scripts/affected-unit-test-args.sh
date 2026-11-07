#!/usr/bin/env bash
# Prints the unit-test plan for the changes between two commits.
# The first output line is RUN=none, RUN=full or RUN=filtered. For RUN=filtered the following lines
# are Gradle arguments, one per line. Usage: affected-unit-test-args.sh BASE HEAD
set -euo pipefail

ZERO_SHA=0000000000000000000000000000000000000000

print_full() {
  echo "RUN=full"
  exit 0
}

is_full_trigger() {
  case "$1" in
    app/src/main/java/*.kt) return 1 ;;
    app/src/test/*Test.kt) return 1 ;;
    *build.gradle.kts | settings.gradle.kts | gradle.properties | gradle/* | gradlew | gradlew.bat) return 0 ;;
    .github/* | .githooks/* | scripts/*) return 0 ;;
    app/*) return 0 ;;
    *) return 1 ;;
  esac
}

main_identifiers() {
  local file="$1"
  {
    basename "${file}" .kt
    grep -oP '(?<=\b(?:class|object|interface|fun|val|var|typealias) )[A-Za-z_][A-Za-z0-9_]*' "${file}" || true
  } | sort -u
}

main() {
  local base="${1:-}" head="${2:-HEAD}" path identifier referencing
  if [[ -z "${base}" || "${base}" == "${ZERO_SHA}" ]]; then print_full; fi
  if ! git cat-file -e "${base}^{commit}" 2>/dev/null; then print_full; fi
  if ! git cat-file -e "${head}^{commit}" 2>/dev/null; then print_full; fi

  local -a changed=() classes=()
  mapfile -t changed < <(git diff --name-only "${base}" "${head}")
  for path in "${changed[@]}"; do
    if is_full_trigger "${path}"; then print_full; fi
  done

  for path in "${changed[@]}"; do
    case "${path}" in
      app/src/test/*Test.kt)
        classes+=("$(basename "${path}" .kt)")
        ;;
      app/src/main/java/*.kt)
        if [[ ! -f "${path}" ]]; then print_full; fi
        while IFS= read -r identifier; do
          while IFS= read -r referencing; do
            if [[ "${referencing}" != app/src/test/*Test.kt ]]; then print_full; fi
            classes+=("$(basename "${referencing}" .kt)")
          done < <(grep -rlw --include='*.kt' -- "${identifier}" app/src/test 2>/dev/null || true)
        done < <(main_identifiers "${path}")
        ;;
    esac
  done

  if [[ ${#classes[@]} -eq 0 ]]; then
    echo "RUN=none"
    exit 0
  fi
  echo "RUN=filtered"
  printf '%s\n' "${classes[@]}" | sort -u | while IFS= read -r class_name; do
    printf -- '--tests\n*.%s\n' "${class_name}"
  done
}

main "$@"
