#!/usr/bin/env bash
# Prints the unit-test plan for the changes between two commits.
# The first output line is RUN=none, RUN=full or RUN=filtered. For RUN=filtered the following lines
# are Gradle arguments, one per line. Usage: affected-unit-test-args.sh BASE HEAD
set -euo pipefail

ZERO_SHA=0000000000000000000000000000000000000000
# Kotlin hard keywords can never be identifiers, so they are the only words dropped from the names.
# Soft keywords and modifiers (data, value, open, inner, field, ...) stay selectable.
HARD_KEYWORDS=" as break class continue do else false for fun if in interface is null object package return super this throw true try typealias typeof val var when while "

print_full() {
  echo "RUN=full"
  exit 0
}

print_test_files() {
  local -a classes=()
  local file name found
  for file in "$@"; do
    found=0
    while IFS= read -r name; do
      if [[ -n "${name}" ]]; then classes+=("${name}"); found=1; fi
    done < <(declared_class_names "${file}")
    if [[ "${found}" -eq 0 ]]; then classes+=("$(basename "${file}" .kt)"); fi
  done
  echo "RUN=filtered"
  printf '%s\n' "${classes[@]}" | sort -u | while IFS= read -r class_name; do
    printf -- '--tests\n*.%s\n' "${class_name}"
  done
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

# Keeps each name once and drops only Kotlin hard keywords.
filter_names() {
  awk -v stop="${HARD_KEYWORDS}" '{ if (index(stop, " " $0 " ") == 0 && !seen[$0]++) print }'
}

# Every identifier-like token of a Kotlin file, including import lines (an aliased import hides the
# original name behind the alias, so the import line is the only place the dependency is visible).
file_tokens() {
  { grep -vE '^[[:space:]]*package[[:space:]]' "$1" || true; } \
    | { grep -oE '[A-Za-z_][A-Za-z0-9_]*' || true; } \
    | filter_names
}

# Names of the classes and objects a test file declares; each one gets its own --tests filter.
declared_class_names() {
  { grep -oE '(^|[^A-Za-z0-9_])(class|object) +[A-Za-z_][A-Za-z0-9_]*' "$1" || true; } \
    | awk '{ print $NF }' | sort -u
}

# Identifier tokens of the lines that declare something (class, object, interface, fun, val, var, typealias).
# Generic and receiver-qualified functions such as fun <T> List<T>.name() are covered, since every token
# on the line is kept.
declaration_tokens() {
  { grep -E '(^|[^A-Za-z0-9_])(class|object|interface|fun|val|var|typealias)([^A-Za-z0-9_]|$)' "$1" || true; } \
    | { grep -oE '[A-Za-z_][A-Za-z0-9_]*' || true; } \
    | filter_names
}

main() {
  local base="${1:-}" head="${2:-HEAD}" path file tok
  if [[ -z "${base}" || "${base}" == "${ZERO_SHA}" ]]; then print_full; fi
  if ! git cat-file -e "${base}^{commit}" 2>/dev/null; then print_full; fi
  if ! git cat-file -e "${head}^{commit}" 2>/dev/null; then print_full; fi

  local -a changed=() changed_main=() changed_tests=()
  mapfile -t changed < <(git diff --name-only "${base}" "${head}")
  for path in "${changed[@]}"; do
    if is_full_trigger "${path}"; then print_full; fi
  done
  for path in "${changed[@]}"; do
    case "${path}" in
      app/src/main/java/*.kt)
        if [[ ! -f "${path}" ]]; then print_full; fi
        changed_main+=("${path}")
        ;;
      app/src/test/*Test.kt)
        if [[ -f "${path}" ]]; then changed_tests+=("${path}"); fi
        ;;
    esac
  done

  if [[ ${#changed_main[@]} -eq 0 ]]; then
    if [[ ${#changed_tests[@]} -eq 0 ]]; then echo "RUN=none"; exit 0; fi
    print_test_files "${changed_tests[@]}"
  fi

  # Affected names: every name declared by a changed main file, grown transitively through main files
  # that mention an affected name.
  local -A names=() affected=() tokens=() declared=()
  local -a main_files=() test_files=()
  mapfile -t main_files < <(find app/src/main/java -name '*.kt' -type f | sort)
  for file in "${main_files[@]}"; do
    tokens["${file}"]="$(file_tokens "${file}")"
    declared["${file}"]="$(declaration_tokens "${file}")"
  done
  for file in "${changed_main[@]}"; do
    affected["${file}"]=1
    while IFS= read -r tok; do
      if [[ -n "${tok}" ]]; then names["${tok}"]=1; fi
    done <<<"${declared[${file}]}"
  done

  local progress=1 matched
  while [[ "${progress}" -eq 1 ]]; do
    progress=0
    for file in "${main_files[@]}"; do
      if [[ -n "${affected[${file}]:-}" ]]; then continue; fi
      matched=0
      while IFS= read -r tok; do
        if [[ -n "${tok}" && -n "${names[${tok}]:-}" ]]; then matched=1; break; fi
      done <<<"${tokens[${file}]}"
      if [[ "${matched}" -eq 1 ]]; then
        affected["${file}"]=1
        progress=1
        while IFS= read -r tok; do
          if [[ -n "${tok}" ]]; then names["${tok}"]=1; fi
        done <<<"${declared[${file}]}"
      fi
    done
  done

  # Every test that mentions an affected name runs, as do the changed test classes that still exist.
  # A shared test helper that mentions an affected name is not attributable to one class, so the
  # full suite runs instead.
  local -a selected=()
  mapfile -t test_files < <(find app/src/test -name '*.kt' -type f | sort)
  for file in "${test_files[@]}"; do
    matched=0
    while IFS= read -r tok; do
      if [[ -n "${tok}" && -n "${names[${tok}]:-}" ]]; then matched=1; break; fi
    done < <(file_tokens "${file}")
    if [[ "${matched}" -eq 1 ]]; then
      if [[ "${file}" != *Test.kt ]]; then print_full; fi
      selected+=("${file}")
    fi
  done
  for file in "${changed_tests[@]}"; do
    selected+=("${file}")
  done

  if [[ ${#selected[@]} -eq 0 ]]; then print_full; fi
  print_test_files "${selected[@]}"
}

main "$@"
