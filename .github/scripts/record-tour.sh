#!/bin/bash
# Records the simulator while one UI-test tour runs and turns the video into a GIF.
# usage: record-tour.sh <simulator udid> <test method> <out.gif> [vi|en]
set -uo pipefail
udid=$1
test=$2
out=$3
# xcodebuild hands TEST_RUNNER_* variables to the tests without the prefix.
export TEST_RUNNER_TOUR_LANGUAGE=${4:-vi}
video="$RUNNER_TEMP/$test-$TEST_RUNNER_TOUR_LANGUAGE.mp4"
log="$RUNNER_TEMP/$test-$TEST_RUNNER_TOUR_LANGUAGE.log"
mkdir -p "$(dirname "$out")"

reclog="$RUNNER_TEMP/$test-$TEST_RUNNER_TOUR_LANGUAGE.rec"
xcrun simctl io "$udid" recordVideo --codec h264 --force "$video" > "$reclog" 2>&1 &
recorder=$!
# The video's first frame is when simctl says so, a second or two after it starts.
for _ in $(seq 100); do grep -q "Recording started" "$reclog" && break; sleep 0.1; done
rec_start=$(python3 -c 'import time; print(time.time())')
sleep 1

xcodebuild test-without-building -project VespaNav.xcodeproj -scheme VespaNav \
  -destination "id=$udid" -derivedDataPath build \
  -only-testing:"VespaNavUITests/TourTests/$test" 2>&1 | tee "$log"
status=${PIPESTATUS[0]}

kill -INT "$recorder"
wait "$recorder" 2>/dev/null

# Cut times come from the test's own clock ("Start Test at <local time>", "t = 29.10s Tear Down"):
# xcodebuild prints in bursts, so the moment a line shows up here can be seconds late.
# Start ~3 s in (app launched), end 2 s into the 5 s tearDown hold, before the app is closed.
read -r ss duration < <(python3 - "$log" "$rec_start" <<'PY'
import re, sys
from datetime import datetime
text = open(sys.argv[1], errors="replace").read()
rec_start = float(sys.argv[2])
start = re.search(r"Start Test at (\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d+)", text)
teardown = re.search(r"t =\s+([\d.]+)s Tear Down", text)
if not start or not teardown:
    sys.exit(1)
t0 = datetime.strptime(start.group(1), "%Y-%m-%d %H:%M:%S.%f").timestamp()
first = t0 + 3 - rec_start
last = t0 + float(teardown.group(1)) + 2 - rec_start
print(f"{max(0, first):.2f} {max(1, last - first):.2f}")
PY
)
if [ -z "${ss:-}" ]; then
  echo "::error::$test did not run"
  exit 1
fi

ffmpeg -loglevel error -y -ss "$ss" -t "$duration" -i "$video" \
  -vf "fps=12,scale=320:-1:flags=lanczos,split[a][b];[a]palettegen=stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=4" \
  -loop 0 "$out"
echo "$out: $(du -h "$out" | cut -f1) (from ${ss}s, ${duration}s long)"
[ "$status" -eq 0 ] || echo "::warning::$test reported failures (GIF still made)"
