#!/usr/bin/env bash
# Runs affected-unit-test-args.sh against throwaway git repositories and checks the printed plan.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
script="${script_dir}/affected-unit-test-args.sh"
work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT

ZERO=0000000000000000000000000000000000000000
MISSING=1234567890123456789012345678901234567890
FILTER_FOO=$'RUN=filtered\n--tests\n*.FooTest'
FILTER_BAR=$'RUN=filtered\n--tests\n*.BarTest'
FILTER_BOTH=$'RUN=filtered\n--tests\n*.BarTest\n--tests\n*.FooTest'
failures=0
count=0
base_sha=""

write_file() {
  mkdir -p "$(dirname "$1")"
  printf '%s\n' "$2" > "$1"
}

setup_repo() {
  local repo="$1"
  mkdir -p "${repo}"
  cd "${repo}"
  git init -q
  git config user.name "test"
  git config user.email "test@example.com"
  write_file app/build.gradle.kts 'plugins {}'
  write_file settings.gradle.kts 'rootProject.name = "x"'
  write_file gradle.properties 'org.gradle.jvmargs=-Xmx1g'
  write_file gradle/wrapper/gradle-wrapper.properties 'distributionUrl=x'
  write_file .github/workflows/ci.yml 'name: CI'
  write_file README.md '# readme'
  write_file app/src/main/res/values/strings.xml '<resources />'
  write_file app/src/main/java/pkg/Foo.kt 'package pkg

data class Foo(val value: String)'
  write_file app/src/main/java/pkg/Bar.kt 'package pkg

class Bar'
  write_file app/src/main/java/pkg/Unused.kt 'package pkg

class Unused'
  write_file app/src/test/java/pkg/FooTest.kt 'package pkg

class FooTest {
    val foo = Foo("a")
}'
  write_file app/src/test/java/pkg/BarTest.kt 'package pkg

class BarTest {
    val bar = Bar()
}'
  write_file app/src/test/java/pkg/Helpers.kt 'package pkg

object Helpers'
  git add -A
  git commit -q -m base
  base_sha="$(git rev-parse HEAD)"
}

m_none() { :; }
m_readme() { printf 'more\n' >> README.md; }
m_app_build() { printf '\n' >> app/build.gradle.kts; }
m_wrapper() { printf '\n' >> gradle/wrapper/gradle-wrapper.properties; }
m_workflow() { printf '\n' >> .github/workflows/ci.yml; }
m_resource() { printf '\n' >> app/src/main/res/values/strings.xml; }
m_helper() { printf '\n' >> app/src/test/java/pkg/Helpers.kt; }
m_foo() { printf '\n' >> app/src/main/java/pkg/Foo.kt; }
m_bartest() { printf '\n' >> app/src/test/java/pkg/BarTest.kt; }
m_unused() { printf '\n' >> app/src/main/java/pkg/Unused.kt; }
m_foo_and_bartest() { m_foo; m_bartest; }
m_delete_bar() { git rm -q app/src/main/java/pkg/Bar.kt; }

run_case() {
  local name="$1" base_kind="$2" expected="$3" mutate="$4"
  count=$((count + 1))
  setup_repo "${work}/case-${count}"
  "${mutate}"
  git add -A
  git commit -q --allow-empty -m head
  local head_sha base actual rc=0
  head_sha="$(git rev-parse HEAD)"
  case "${base_kind}" in
    previous) base="${base_sha}" ;;
    zero) base="${ZERO}" ;;
    missing) base="${MISSING}" ;;
  esac
  actual="$(bash "${script}" "${base}" "${head_sha}" 2>&1)" || rc=$?
  if [[ "${rc}" -eq 0 && "${actual}" == "${expected}" ]]; then
    echo "PASS ${name}"
  else
    echo "FAIL ${name} (exit ${rc})"
    failures=$((failures + 1))
  fi
  sed 's/^/    | /' <<<"${actual}"
}

run_case "no changes" previous 'RUN=none' m_none
run_case "readme only" previous 'RUN=none' m_readme
run_case "zero base sha runs the full suite" zero 'RUN=full' m_none
run_case "unknown base commit runs the full suite" missing 'RUN=full' m_none
run_case "app build file changed" previous 'RUN=full' m_app_build
run_case "gradle wrapper properties changed" previous 'RUN=full' m_wrapper
run_case "workflow file changed" previous 'RUN=full' m_workflow
run_case "main resource changed" previous 'RUN=full' m_resource
run_case "test helper changed" previous 'RUN=full' m_helper
run_case "main class with a test" previous "${FILTER_FOO}" m_foo
run_case "test class edited" previous "${FILTER_BAR}" m_bartest
run_case "main class without a test" previous 'RUN=none' m_unused
run_case "main class and an unrelated test" previous "${FILTER_BOTH}" m_foo_and_bartest
run_case "main class deleted" previous 'RUN=full' m_delete_bar

echo "${count} cases, ${failures} failures"
[[ "${failures}" -eq 0 ]]
