#!/bin/zsh
# Runs the unit tests and prints a short summary instead of Gradle's output (the test task shows
# every test's stdout, thousands of lines). The full log is kept in mod/build/test-run.log: grep it,
# never read it whole.
#
#   mod/scripts/test.sh                       all tests
#   mod/scripts/test.sh Std140LayoutTest      one class (any --tests pattern; several allowed)
#   mod/scripts/test.sh 'ProgramTranslatorTest.glint*'
#
# Prints: compile errors if the build failed; pass/fail/skip counts; each failure's test name and
# first lines; the step 4 scoreboard line if TranslateRealPackTest ran. Exit code is Gradle's.
cd "${0:A:h}/.."
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
args=()
for t in "$@"; do args+=(--tests "$t"); done
log=build/test-run.log
mkdir -p build
start=$SECONDS
./gradlew test $args > $log 2>&1
rc=$?
print "== tests: gradle exit $rc, $((SECONDS - start))s (log: mod/$log)"
if grep -qE '\.java:[0-9]+: error:' $log; then
  print "compile errors:"
  grep -E '\.java:[0-9]+: error:' $log | sort -u | head -15 | sed 's|.*/src/|  src/|'
elif (( rc != 0 )) && grep -q "What went wrong" $log; then
  grep -A3 "What went wrong" $log | head -4
fi
grep -q "Task :test UP-TO-DATE" $log && print "(test task up to date: results are from the last run)"
python3 -I - <<'EOF'
import glob, re, xml.etree.ElementTree as ET
t = f = s = 0
fails = []
for x in sorted(glob.glob('build/test-results/test/*.xml')):
    root = ET.parse(x).getroot()
    t += int(root.get('tests', 0)); s += int(root.get('skipped', 0))
    for case in root.iter('testcase'):
        for bad in list(case.iter('failure')) + list(case.iter('error')):
            f += 1
            msg = (bad.get('message') or bad.text or '').strip().splitlines()[:3]
            fails.append((case.get('classname', '').split('.')[-1] + '.' + case.get('name', ''), msg))
print(f"tests={t} failed={f} skipped={s}")
for name, msg in fails[:20]:
    print(f"FAIL {name}")
    for line in msg:
        print(f"    {line[:200]}")
if len(fails) > 20:
    print(f"... and {len(fails) - 20} more")
EOF
grep -h "furthest step reached\|programs (vertex+fragment" $log | head -6 | sed 's/^/scoreboard: /'
exit $rc
