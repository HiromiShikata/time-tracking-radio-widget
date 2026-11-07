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

class Foo(val value: String)'
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
  write_file app/src/main/java/pkg/Ext.kt 'package pkg

fun <T> List<T>.summarize(): Int = size'
  write_file app/src/test/java/pkg/SummarizeTest.kt 'package pkg

class SummarizeTest {
    val total = listOf(1).summarize()
}'
  write_file app/src/main/java/pkg/HelperA.kt 'package pkg

fun helperA(): Int = 1'
  write_file app/src/main/java/pkg/UseB.kt 'package pkg

fun useB(): Int = helperA()'
  write_file app/src/test/java/pkg/UsesBTest.kt 'package pkg

class UsesBTest {
    val x = useB()
}'
  write_file app/src/main/java/pkg/Settings.kt 'package pkg

val data = 1'
  write_file app/src/test/java/pkg/DataUserTest.kt 'package pkg

class DataUserTest {
    val x = data
}'
  write_file app/src/main/java/pkg/Solo.kt 'package pkg

class Solo'
  write_file app/src/test/java/pkg/MultiTest.kt 'package pkg

class MultiTest {
    val m = Solo()
}

class OtherTest {
    val o = 1
}'
  write_file app/src/main/java/pkg/Target.kt 'package pkg

class Target'
  write_file app/src/main/java/pkg/Consumer.kt 'package pkg

import pkg.Target as Alias

fun consume(): Any = Alias()'
  write_file app/src/test/java/pkg/AliasTest.kt 'package pkg

class AliasTest {
    val a = consume()
}'
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
m_settings() { printf '\n' >> app/src/main/java/pkg/Settings.kt; }
m_solo() { printf '\n' >> app/src/main/java/pkg/Solo.kt; }
m_target() { printf '\n' >> app/src/main/java/pkg/Target.kt; }
m_ext() { printf '\n' >> app/src/main/java/pkg/Ext.kt; }
m_helper_a() { printf '\n' >> app/src/main/java/pkg/HelperA.kt; }
m_delete_foo_test() { git rm -q app/src/test/java/pkg/FooTest.kt; }

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
run_case "main class without a test runs the full suite" previous 'RUN=full' m_unused
run_case "main class and an unrelated test" previous "${FILTER_BOTH}" m_foo_and_bartest
run_case "main class deleted" previous 'RUN=full' m_delete_bar
run_case "generic extension function" previous $'RUN=filtered\n--tests\n*.SummarizeTest\n--tests\n*.UsesBTest' m_ext
run_case "transitive dependency runs the test of the dependant" previous $'RUN=filtered\n--tests\n*.SummarizeTest\n--tests\n*.UsesBTest' m_helper_a
run_case "deleted test only" previous 'RUN=none' m_delete_foo_test
run_case "top-level declaration named after a soft keyword" previous $'RUN=filtered\n--tests\n*.DataUserTest' m_settings
run_case "test file with two classes runs every class" previous $'RUN=filtered\n--tests\n*.MultiTest\n--tests\n*.OtherTest' m_solo
run_case "aliased import keeps the dependency" previous $'RUN=filtered\n--tests\n*.AliasTest' m_target

echo "${count} cases, ${failures} failures"
[[ "${failures}" -eq 0 ]]
