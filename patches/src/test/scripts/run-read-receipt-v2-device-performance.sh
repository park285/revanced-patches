#!/usr/bin/env bash
set -euo pipefail

readonly PACKAGE="com.kakao.talk"
readonly ACTIVITY="com.kakao.talk/.activity.SplashActivity"
readonly MARKER="/data/user/0/com.kakao.talk/no_backup/iris_read_receipt_v2.enabled"
readonly MARKER_BACKUP="/data/user/0/com.kakao.talk/no_backup/.iris_read_receipt_v2.performance-backup"
readonly PROBE_CLASS="app.revanced.extension.kakaotalk.chatlog.readreceipt.ReadReceiptPerformanceProbe"
readonly HARD_STARTUP_MS=5000
readonly SAMPLE_COUNT=11
readonly REMOTE_TIMEOUT_SECONDS=20
readonly STARTUP_P95_OVERHEAD_BUDGET_MS=2000
readonly DISABLED_CAPTURE_P99_BUDGET_US=1000
readonly ACTIVE_CAPTURE_P99_BUDGET_US=250000
readonly BURST_1_P99_BUDGET_US=250000
readonly BURST_10_P99_BUDGET_US=500000
readonly BURST_100_P99_BUDGET_US=2500000
readonly BURST_256_P99_BUDGET_US=5000000
readonly JANK_RATIO_BUDGET_PERCENT=75
readonly JANK_DELTA_BUDGET_PERCENTAGE_POINTS=25

repo_root=""
ssh_target=""
output_dir=""
tmp_dir=""
device_dex=""
remote_dex=""
marker_recovery_armed=0
declare -A startup_metric=()

fail() {
    echo "read-receipt device performance: $1" >&2
    exit 1
}

contains_live_path() {
    local argument
    for argument in "$@"; do
        [[ "$argument" == *"/data/user/0/com.kakao.talk/no_backup"* ||
            "$argument" == *"/data/user/0/com.kakao.talk/databases"* ||
            "$argument" == *"/data/data/com.kakao.talk"* ]] && return 0
    done
    return 1
}

require_digest() {
    [[ "$1" =~ ^[0-9a-f]{64}$ ]]
}

sha256_file() {
    sha256sum -- "$1" | awk '{ print $1 }'
}

source_bundle_sha256() {
    local source
    for source in "$@"; do
        printf '%s\n' "$(basename -- "$source")"
        sha256_file "$source"
    done | sha256sum | awk '{ print $1 }'
}

percentile() {
    local percentile_value="$1"
    shift
    printf '%s\n' "$@" | LC_ALL=C sort -n | awk -v percentile="$percentile_value" '
        { values[NR] = $1 }
        END {
            position = int((percentile * NR + 99) / 100)
            if (position < 1) position = 1
            print values[position]
        }'
}

parse_gfx_count() {
    local metric="$1"
    local input="$2"
    awk -v metric="$metric" '
        {
            line = $0
            if (metric == "total" &&
                    line ~ /^[[:space:]]*Total frames rendered:[[:space:]]+(0|[1-9][0-9]*)[[:space:]]*$/) {
                sub(/^[[:space:]]*Total frames rendered:[[:space:]]+/, "", line)
                sub(/[[:space:]]+$/, "", line)
                values[++count] = line
            } else if (metric == "janky" &&
                    line ~ /^[[:space:]]*Janky frames:[[:space:]]+(0|[1-9][0-9]*)[[:space:]]+\([0-9]+([.][0-9]+)?%\)[[:space:]]*$/) {
                sub(/^[[:space:]]*Janky frames:[[:space:]]+/, "", line)
                sub(/[[:space:]]+\(.*/, "", line)
                values[++count] = line
            }
        }
        END {
            if (count != 1 || values[1] !~ /^(0|[1-9][0-9]*)$/) exit 1
            print values[1]
        }
    ' <<<"$input"
}

parse_installed_apk_path() {
    awk -v package="$PACKAGE" '
        function valid_token(segment, prefix, encoded) {
            if (substr(segment, 1, length(prefix)) != prefix) return 0
            encoded = substr(segment, length(prefix) + 1)
            return length(encoded) == 24 && encoded ~ /^[A-Za-z0-9_-]+==$/
        }
        {
            prefix = "package:/data/app/"
            if (index($0, prefix) != 1) {
                invalid++
                next
            }
            path = substr($0, length("package:") + 1)
            if (split(path, segment, "/") != 6 || segment[1] != "" ||
                    segment[2] != "data" || segment[3] != "app" ||
                    !valid_token(segment[4], "~~") ||
                    !valid_token(segment[5], package "-") ||
                    segment[6] != "base.apk") {
                invalid++
                next
            }
            values[++count] = path
        }
        END {
            if (count != 1 || invalid != 0) exit 1
            print values[1]
        }
    ' <<<"$1"
}

remote_shell_script() {
    printf '%s\n' "$1" |
        timeout --signal=TERM "$REMOTE_TIMEOUT_SECONDS" \
            ssh -T -- "$ssh_target" 'adb shell sh -s'
}

bounded_ssh() {
    timeout --signal=TERM "$REMOTE_TIMEOUT_SECONDS" ssh -T -- "$ssh_target" "$@"
}

bounded_scp() {
    timeout --signal=TERM "$REMOTE_TIMEOUT_SECONDS" scp -q -- "$@"
}

marker_recovery_action() {
    case "$1" in
        "marker=exact backup=absent") printf 'stable\n' ;;
        "marker=absent backup=exact") printf 'restore\n' ;;
        *) printf 'reject\n'; return 1 ;;
    esac
}

marker_park_observation() {
    local command_status="$1"
    local snapshot="$2"
    [[ "$command_status" =~ ^[0-9]+$ ]] || return 1
    case "$snapshot" in
        "marker=absent backup=exact") printf 'parked\n' ;;
        "marker=exact backup=absent") printf 'not_applied\n'; return 1 ;;
        *) printf 'reject\n'; return 1 ;;
    esac
}

marker_snapshot() {
    remote_shell_script "
describe() {
    path=\"\$1\"
    if test ! -e \"\$path\" && test ! -L \"\$path\"; then
        printf absent
        return
    fi
    if test -f \"\$path\" && test ! -L \"\$path\" &&
        test \"\$(stat -c '%u %a %s' \"\$path\")\" = '$device_uid 600 11' &&
        test \"\$(sha256sum \"\$path\" | awk '{print \$1}')\" = '$marker_content_sha'; then
        printf exact
        return
    fi
    printf invalid
}
printf 'marker='; describe '$MARKER'; printf ' backup='; describe '$MARKER_BACKUP'; printf '\\n'
"
}

restore_marker() {
    local snapshot action
    (( marker_recovery_armed == 1 )) || return 0
    snapshot="$(marker_snapshot)" || return 1
    action="$(marker_recovery_action "$snapshot")" || return 1
    if [[ "$action" == "restore" ]]; then
        remote_shell_script "mv '$MARKER_BACKUP' '$MARKER'" >/dev/null || true
        snapshot="$(marker_snapshot)" || return 1
        [[ "$(marker_recovery_action "$snapshot")" == "stable" ]] || return 1
    fi
}

initialize_marker_state() {
    local snapshot action
    marker_recovery_armed=1
    snapshot="$(marker_snapshot)" || return 1
    action="$(marker_recovery_action "$snapshot")" || return 1
    if [[ "$action" == "restore" ]]; then
        restore_marker || return 1
    fi
    [[ "$(marker_snapshot)" == "marker=exact backup=absent" ]]
}

park_marker() {
    local snapshot command_status
    [[ "$(marker_snapshot)" == "marker=exact backup=absent" ]] || return 1
    if remote_shell_script "mv '$MARKER' '$MARKER_BACKUP'" >/dev/null; then
        command_status=0
    else
        command_status=$?
    fi
    snapshot="$(marker_snapshot)" || return 1
    [[ "$(marker_park_observation "$command_status" "$snapshot")" == "parked" ]]
}

cleanup() {
    local status=$?
    restore_marker || true
    if [[ -n "$device_dex" && -n "$ssh_target" ]]; then
        remote_shell_script "test ! -e '$device_dex' || rm -f '$device_dex'" >/dev/null 2>&1 || true
    fi
    if [[ -n "$remote_dex" && -n "$ssh_target" ]]; then
        bounded_ssh rm -f -- "$remote_dex" >/dev/null 2>&1 || true
    fi
    [[ -z "$tmp_dir" ]] || rm -rf -- "$tmp_dir"
    return "$status"
}

package_anr_count() {
    remote_shell_script "logcat -d -b events" |
        awk -v package="$PACKAGE" '$0 ~ /am_anr/ && index($0, package) { count++ }
            END { print count + 0 }'
}

require_probe_output() {
    local path="$1"
    local expected_exit="$2"
    local transport_status="$3"
    local probe_exit_status probe_failure_kind probe_failure_stage
    [[ "$transport_status" == "0" ]] || fail "probe transport status is not exact"
    awk '
        /^([a-z0-9_]+=[a-z0-9_-]+|result=[a-z0-9_]+,passed=(true|false))$/ { next }
        { exit 1 }
    ' "$path" || fail "probe emitted non-aggregate output"
    probe_exit_status="$(awk -F= '$1 == "probe_exit_status" { count++; value = $2 }
        END { if (count != 1 || value !~ /^[0-9]+$/) exit 1; print value }' "$path")" ||
        fail "probe exit status is unavailable"
    if [[ "$probe_exit_status" != "$expected_exit" ]]; then
        probe_failure_kind="$(awk -F= '$1 == "probe_failure_kind" { count++; value = $2 }
            END { if (count == 1 && value ~ /^[a-z_]+$/) print value; else print "none" }' "$path")"
        probe_failure_stage="$(awk -F= '$1 == "probe_failure_stage" { count++; value = $2 }
            END { if (count == 1 && value ~ /^[a-z_]+$/) print value; else print "none" }' "$path")"
        fail "probe exit status is not exact: expected=$expected_exit observed=$probe_exit_status failure=$probe_failure_kind stage=$probe_failure_stage"
    fi
    [[ "$(awk -F= '$1 == "cleanup_passed" { print $2 }' "$path")" == "1" ]] ||
        fail "probe cleanup did not pass"
}

run_probe() {
    local mode="$1"
    local destination="$2"
    local expected_exit="${3:-0}"
    local command status
    command="CLASSPATH=$device_dex:$installed_apk app_process /system/bin $PROBE_CLASS $mode $probe_nonce; probe_exit_status=\$?; printf \"probe_exit_status=%s\\n\" \"\$probe_exit_status\""
    set +e
    remote_shell_script "su '$device_uid' -c '$command'" >"$destination"
    status=$?
    set -e
    require_probe_output "$destination" "$expected_exit" "$status"
}

probe_value() {
    local path="$1"
    local key="$2"
    local value
    value="$(awk -F= -v key="$key" '$1 == key { print $2 }' "$path")"
    [[ "$value" =~ ^[0-9]+$ ]] || fail "probe metric $key is not an integer"
    printf '%s\n' "$value"
}

measure_startups() {
    local label="$1"
    local index output status total_time gfx sample_total sample_janky
    local total_frames=0
    local janky_frames=0
    local -a samples=()
    remote_shell_script "dumpsys gfxinfo '$PACKAGE' reset >/dev/null 2>&1 || true" >/dev/null
    for (( index = 0; index < SAMPLE_COUNT; index++ )); do
        remote_shell_script "am force-stop '$PACKAGE'" >/dev/null
        output="$(remote_shell_script "am start -W -n '$ACTIVITY'")"
        status="$(awk -F: '$1 ~ /^Status$/ { gsub(/[[:space:]]/, "", $2); print $2 }' <<<"$output")"
        total_time="$(awk -F: '$1 ~ /^TotalTime$/ { gsub(/[[:space:]]/, "", $2); print $2 }' <<<"$output")"
        [[ "$status" == "ok" && "$total_time" =~ ^[0-9]+$ ]] ||
            fail "startup result is not Status ok"
        (( total_time < HARD_STARTUP_MS )) || fail "startup exceeded hard safety limit"
        samples+=("$total_time")
        gfx="$(remote_shell_script "dumpsys gfxinfo '$PACKAGE'")"
        sample_total="$(parse_gfx_count total "$gfx")" || fail "total frame count is unavailable"
        sample_janky="$(parse_gfx_count janky "$gfx")" || fail "janky frame count is unavailable"
        (( sample_total > 0 && sample_janky <= sample_total )) ||
            fail "startup frame aggregate is invalid"
        total_frames=$(( total_frames + sample_total ))
        janky_frames=$(( janky_frames + sample_janky ))
        remote_shell_script "dumpsys gfxinfo '$PACKAGE' reset >/dev/null 2>&1 || true" >/dev/null
    done
    (( total_frames > 0 && janky_frames <= total_frames )) ||
        fail "startup frame sum is invalid"
    startup_metric["${label}_samples"]="${#samples[@]}"
    startup_metric["${label}_p50_ms"]="$(percentile 50 "${samples[@]}")"
    startup_metric["${label}_p95_ms"]="$(percentile 95 "${samples[@]}")"
    startup_metric["${label}_p99_ms"]="$(percentile 99 "${samples[@]}")"
    startup_metric["${label}_max_ms"]="$(percentile 100 "${samples[@]}")"
    startup_metric["${label}_total_frames"]="$total_frames"
    startup_metric["${label}_janky_frames"]="$janky_frames"
}

validate_transfer_output() {
    local path="$1"
    [[ "$(probe_value "$path" transfer_before_acked)" == "0" &&
        "$(probe_value "$path" transfer_before_highest)" == "256" &&
        "$(probe_value "$path" transfer_before_outbox)" == "256" &&
        "$(probe_value "$path" transfer_frame_events)" == "256" &&
        "$(probe_value "$path" transfer_after_acked)" == "256" &&
        "$(probe_value "$path" transfer_after_highest)" == "256" &&
        "$(probe_value "$path" transfer_after_outbox)" == "0" ]] ||
        fail "transfer drain state is not exact"
    (( $(probe_value "$path" transfer_elapsed_us) < 5000000 )) ||
        fail "transfer drain exceeded hard safety limit"
}

capture_properties() {
    local path="$1"
    awk -F= '
        $1 == "capture_burst_size" { size = $2 }
        $1 == "capture_call_p50_us" { print "capture_" size "_p50_us=" $2 }
        $1 == "capture_call_p95_us" { print "capture_" size "_p95_us=" $2 }
        $1 == "capture_call_p99_us" { print "capture_" size "_p99_us=" $2 }
        $1 == "capture_call_max_us" { print "capture_" size "_max_us=" $2 }
        $1 == "capture_burst_p50_us" { print "capture_" size "_burst_p50_us=" $2 }
        $1 == "capture_burst_p95_us" { print "capture_" size "_burst_p95_us=" $2 }
        $1 == "capture_burst_p99_us" { print "capture_" size "_burst_p99_us=" $2 }
    ' "$path"
}

metric_from() {
    local file="$1"
    local key="$2"
    local value
    value="$(awk -F= -v key="$key" '$1 == key { print $2 }' "$file")"
    [[ "$value" =~ ^-?[0-9]+$ ]] || return 1
    printf '%s\n' "$value"
}

jank_budget_passes() {
    local aggregate="$1"
    local label enabled_total enabled_janky disabled_total disabled_janky
    disabled_total="$(metric_from "$aggregate" disabled_total_frames)" || return 1
    disabled_janky="$(metric_from "$aggregate" disabled_janky_frames)" || return 1
    (( disabled_total > 0 && disabled_janky * 100 <= disabled_total * JANK_RATIO_BUDGET_PERCENT )) ||
        return 1
    for label in enabled_a enabled_b; do
        enabled_total="$(metric_from "$aggregate" "${label}_total_frames")" || return 1
        enabled_janky="$(metric_from "$aggregate" "${label}_janky_frames")" || return 1
        (( enabled_total > 0 )) || return 1
        (( enabled_janky * 100 <= enabled_total * JANK_RATIO_BUDGET_PERCENT )) || return 1
        (( enabled_janky * 100 * disabled_total <=
            disabled_janky * 100 * enabled_total +
                JANK_DELTA_BUDGET_PERCENTAGE_POINTS * enabled_total * disabled_total )) || return 1
    done
}

release_budgets_pass() {
    local aggregate="$1"
    local label size limit
    local disabled_p95 enabled_p95
    disabled_p95="$(metric_from "$aggregate" disabled_p95_ms)" || return 1
    for label in enabled_a enabled_b; do
        enabled_p95="$(metric_from "$aggregate" "${label}_p95_ms")" || return 1
        (( enabled_p95 - disabled_p95 <= STARTUP_P95_OVERHEAD_BUDGET_MS )) || return 1
    done
    (( $(metric_from "$aggregate" disabled_capture_p99_us) <=
        DISABLED_CAPTURE_P99_BUDGET_US )) || return 1
    for size in 1 10 100 256; do
        (( $(metric_from "$aggregate" "capture_${size}_p99_us") <=
            ACTIVE_CAPTURE_P99_BUDGET_US )) || return 1
        case "$size" in
            1) limit=$BURST_1_P99_BUDGET_US ;;
            10) limit=$BURST_10_P99_BUDGET_US ;;
            100) limit=$BURST_100_P99_BUDGET_US ;;
            256) limit=$BURST_256_P99_BUDGET_US ;;
        esac
        if [[ "$size" == "256" ]]; then
            (( $(metric_from "$aggregate" "capture_${size}_burst_p99_us") < limit )) || return 1
        else
            (( $(metric_from "$aggregate" "capture_${size}_burst_p99_us") <= limit )) || return 1
        fi
    done
    jank_budget_passes "$aggregate"
}

verify_output_templates() {
    local script="$1"
    local aggregate_keys receipt_keys required_key
    aggregate_keys="$(awk '
        /^cat >"\$aggregate" <<EOF$/ { capture = 1; next }
        capture && /^EOF$/ { exit }
        capture && /^[a-z0-9_]+=/ { sub(/=.*/, ""); print }
    ' "$script")"
    receipt_keys="$(awk '
        /^cat >"\$receipt" <<EOF$/ { capture = 1; next }
        capture && /^EOF$/ { exit }
        capture && /^[a-z0-9_]+=/ { sub(/=.*/, ""); print }
    ' "$script")"
    [[ -n "$aggregate_keys" && -n "$receipt_keys" ]] || return 1
    [[ -z "$(LC_ALL=C sort <<<"$aggregate_keys" | uniq -d)" &&
        -z "$(LC_ALL=C sort <<<"$receipt_keys" | uniq -d)" ]] || return 1
    [[ "$(awk '$0 == "capture_database_triplet_bytes" { count++ } END { print count + 0 }' \
        <<<"$aggregate_keys")" == "1" ]] || return 1
    for required_key in transfer_drain_256 capture_disabled startup_overhead_budget \
        disabled_callback_budget active_call_budget burst_budget jank_budget; do
        [[ "$(awk -v key="$required_key" '$0 == key { count++ } END { print count + 0 }' \
            <<<"$aggregate_keys")" == "1" &&
            "$(awk -v key="$required_key" '$0 == key { count++ } END { print count + 0 }' \
            <<<"$receipt_keys")" == "1" ]] || return 1
    done
    for required_key in budget_startup_p95_overhead_ms budget_disabled_capture_p99_us \
        budget_active_capture_p99_us budget_burst_1_p99_us budget_burst_10_p99_us \
        budget_burst_100_p99_us budget_burst_256_p99_us budget_jank_ratio_percent \
        budget_jank_delta_percentage_points; do
        [[ "$(awk -v key="$required_key" '$0 == key { count++ } END { print count + 0 }' \
            <<<"$aggregate_keys")" == "1" ]] || return 1
    done
}

write_budget_self_test_fixture() {
    cat >"$1" <<EOF
disabled_p95_ms=100
enabled_a_p95_ms=2100
enabled_b_p95_ms=2100
disabled_capture_p99_us=1000
capture_1_p99_us=250000
capture_10_p99_us=250000
capture_100_p99_us=250000
capture_256_p99_us=250000
capture_1_burst_p99_us=250000
capture_10_burst_p99_us=500000
capture_100_burst_p99_us=2500000
capture_256_burst_p99_us=4999999
enabled_a_total_frames=100
enabled_a_janky_frames=75
disabled_total_frames=100
disabled_janky_frames=50
enabled_b_total_frames=100
enabled_b_janky_frames=75
EOF
}

run_self_test() {
    local rejected=0 runner_dir budget_fixture tampered gfx_fixture probe_fixture
    contains_live_path safe.apk lineage host output || true
    if contains_live_path safe.apk /data/user/0/com.kakao.talk/databases/live.db; then
        rejected=1
    fi
    [[ "$rejected" == "1" ]] || fail "self-test did not reject a live DB path"
    [[ "$(percentile 50 4 1 3 2)" == "2" &&
        "$(percentile 95 4 1 3 2)" == "4" &&
        "$(percentile 99 4 1 3 2)" == "4" ]] || fail "percentile self-test failed"
    gfx_fixture=$'Total frames rendered: 2048\nJanky frames: 785 (3.85%)'
    [[ "$(parse_gfx_count total "$gfx_fixture")" == "2048" &&
        "$(parse_gfx_count janky "$gfx_fixture")" == "785" ]] ||
        fail "gfx count parser self-test failed"
    if parse_gfx_count janky $'Janky frames: 785 (3.85%)\nJanky frames: 1 (1%)' \
        >/dev/null 2>&1; then
        fail "gfx count parser accepted duplicate input"
    fi
    if parse_gfx_count total 'Total frames rendered: 2048 (3.85%)' >/dev/null 2>&1; then
        fail "gfx total parser accepted a suffix"
    fi
    apk_path='/data/app/~~abcdefghijklmnopqrstuv==/com.kakao.talk-ABCDEFGHIJKLMNOPQRSTUV==/base.apk'
    [[ "$(parse_installed_apk_path "package:$apk_path")" == "$apk_path" ]] ||
        fail "installed APK path parser self-test failed"
    if parse_installed_apk_path "package=$apk_path" >/dev/null 2>&1; then
        fail "installed APK path parser accepted a non-canonical prefix"
    fi
    if parse_installed_apk_path $'package:'"$apk_path"$'\npackage:'"$apk_path" \
        >/dev/null 2>&1; then
        fail "installed APK path parser accepted duplicate output"
    fi
    if parse_installed_apk_path "package:${apk_path}.tmp" >/dev/null 2>&1; then
        fail "installed APK path parser accepted a suffix"
    fi
    for invalid_apk_path in \
        '/data/app/~~abcdefghijklmnopqrstuv==/com.example.app-ABCDEFGHIJKLMNOPQRSTUV==/base.apk' \
        '/data/app/abcdefghijklmnopqrstuv==/com.kakao.talk-ABCDEFGHIJKLMNOPQRSTUV==/base.apk' \
        '/data/app/~~abcdefghijklmnopqrstu==/com.kakao.talk-ABCDEFGHIJKLMNOPQRSTUV==/base.apk' \
        '/data/app/~~abcdefghijklmnopqrstuv==/com.kakao.talk-ABCDEFGHIJKLMNOPQRSTUV==/../base.apk' \
        "/data/app/~~abcdefghijklmnopqrstuv==/com.kakao.talk-ABCDEFGHIJKLMNOPQRSTUV=='/base.apk"; do
        if parse_installed_apk_path "package:$invalid_apk_path" >/dev/null 2>&1; then
            fail "installed APK path parser accepted an invalid path"
        fi
    done
    if parse_installed_apk_path $'package:'"$apk_path"$'\npackage:/data/app/~~abcdefghijklmnopqrstuv==/com.kakao.talk-ABCDEFGHIJKLMNOPQRSTUV==/split_config.apk' \
        >/dev/null 2>&1; then
        fail "installed APK path parser accepted split output"
    fi
    probe_fixture="$(mktemp)"
    printf 'cleanup_passed=1\nprobe_exit_status=3\n' >"$probe_fixture"
    require_probe_output "$probe_fixture" 3 0
    if ( require_probe_output "$probe_fixture" 3 255 ) 2>/dev/null; then
        fail "probe output verifier accepted transport failure"
    fi
    printf 'probe_exit_status=3\n' >>"$probe_fixture"
    if ( require_probe_output "$probe_fixture" 3 0 ) 2>/dev/null; then
        fail "probe output verifier accepted duplicate exit status"
    fi
    rm -f -- "$probe_fixture"
    require_digest "$(printf 'a%.0s' {1..64})" || fail "digest self-test failed"
    [[ "$(marker_recovery_action "marker=exact backup=absent")" == "stable" &&
        "$(marker_recovery_action "marker=absent backup=exact")" == "restore" ]] ||
        fail "marker recovery reducer self-test failed"
    [[ "$(marker_park_observation 124 "marker=absent backup=exact")" == "parked" &&
        "$(marker_recovery_action "marker=absent backup=exact")" == "restore" ]] ||
        fail "marker timeout recovery self-test failed"
    if marker_park_observation 255 "marker=exact backup=absent" >/dev/null; then
        fail "marker response-loss self-test trusted command status"
    fi
    [[ "$(marker_recovery_action "marker=exact backup=absent")" == "stable" ]] ||
        fail "marker response-loss recovery self-test failed"
    if marker_recovery_action "marker=exact backup=exact" >/dev/null; then
        fail "marker recovery accepted an ambiguous state"
    fi
    budget_fixture="$(mktemp)"
    tampered="$(mktemp)"
    write_budget_self_test_fixture "$budget_fixture"
    release_budgets_pass "$budget_fixture" || fail "budget boundary self-test failed"
    for mutation in \
        's/enabled_a_p95_ms=2100/enabled_a_p95_ms=2101/' \
        's/disabled_capture_p99_us=1000/disabled_capture_p99_us=1001/' \
        's/capture_10_p99_us=250000/capture_10_p99_us=250001/' \
        's/capture_256_burst_p99_us=4999999/capture_256_burst_p99_us=5000000/' \
        's/enabled_a_janky_frames=75/enabled_a_janky_frames=76/'; do
        sed "$mutation" "$budget_fixture" >"$tampered"
        if release_budgets_pass "$tampered"; then
            fail "budget exceedance self-test was accepted"
        fi
    done
    rm -f -- "$budget_fixture" "$tampered"
    verify_output_templates "${BASH_SOURCE[0]}" || fail "output template self-test failed"
    runner_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
    bash "$runner_dir/verify-read-receipt-v2-performance-receipt.sh" --self-test >/dev/null
    echo "RRV2-DEVICE-PERFORMANCE-RUNNER-SELF-TEST passed: livePathReject=1 percentile=nearestRank gfxCountExact=1 gfxDuplicateReject=1 gfxSuffixReject=1 installedApkPathExact=1 installedApkPathReject=9 probeExitExact=1 probeTransportReject=1 probeExitDuplicateReject=1 modes=9 markerTimeoutRecovery=1 markerResponseLossRecovery=1 ambiguousMarkerReject=1 budgetBoundary=1 budgetExceedReject=5 templateExact=1 receiptVerifier=1"
}

if (( $# == 1 )) && [[ "$1" == "--self-test" ]]; then
    run_self_test
    exit 0
fi

contains_live_path "$@" && fail "live DB/no_backup paths are not configurable"
if (( $# != 5 )) || [[ "$1" != "--approved-live-marker" ]]; then
    echo "usage: run-read-receipt-v2-device-performance.sh --approved-live-marker <signed-apk> <lineage-id> <ssh-target> <output-dir>" >&2
    exit 2
fi

signed_apk="$(realpath -- "$2")"
lineage_id="$3"
ssh_target="$4"
output_dir="$(realpath -m -- "$5")"
[[ -f "$signed_apk" && ! -L "$signed_apk" ]] || fail "signed APK is not a regular file"
require_digest "$lineage_id" || fail "lineage ID is not canonical"
[[ "$ssh_target" =~ ^[A-Za-z0-9._-]+$ ]] || fail "SSH target is not canonical"
[[ ! -e "$output_dir" || ( -d "$output_dir" && ! -L "$output_dir" ) ]] ||
    fail "output directory is unsafe"
mkdir -p -- "$output_dir"
for evidence_name in read-receipt-v2-performance-output.properties \
    read-receipt-v2-performance-receipt.properties; do
    [[ ! -e "$output_dir/$evidence_name" && ! -L "$output_dir/$evidence_name" ]] ||
        fail "performance evidence output already exists"
done

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../../.." && pwd)"
tmp_dir="$(mktemp -d)"
trap cleanup EXIT
trap 'exit 130' INT TERM
android_jar="$(find /opt/android-sdk/platforms -mindepth 2 -maxdepth 2 -name android.jar -print | LC_ALL=C sort -V | tail -n 1)"
d8="$(find /opt/android-sdk/build-tools -mindepth 2 -maxdepth 2 -type f -name d8 -print | LC_ALL=C sort -V | tail -n 1)"
[[ -f "$android_jar" && -x "$d8" ]] || fail "Android compile tools are unavailable"

probe_dir="$repo_root/extensions/kakaotalk/src/test/java/app/revanced/extension/kakaotalk/chatlog/readreceipt"
probe_sources=(
    "$probe_dir/ReadReceiptPerformanceProbe.java"
    "$probe_dir/ReadReceiptPerformanceFixture.java"
    "$probe_dir/ReadReceiptPerformanceSandbox.java"
)
for source in "${probe_sources[@]}"; do [[ -f "$source" ]] || fail "probe source is missing"; done

(
    cd "$repo_root"
    GITHUB_ACTOR=offline GITHUB_TOKEN=offline ./gradlew \
        --offline --no-daemon --max-workers=1 --dependency-verification=strict \
        --rerun-tasks --no-build-cache -PreadReceiptV2PinnedOfflineLane=true \
        :extensions:kakaotalk:compileReleaseJavaWithJavac
)
main_classes="$repo_root/extensions/kakaotalk/build/intermediates/javac/release/compileReleaseJavaWithJavac/classes"
[[ -d "$main_classes" ]] || fail "canonical extension classes are missing"
mkdir -p -- "$tmp_dir/probe-classes" "$tmp_dir/dex"
javac --release 17 -cp "$android_jar:$main_classes" \
    -d "$tmp_dir/probe-classes" "${probe_sources[@]}"
mapfile -t probe_classes < <(find "$tmp_dir/probe-classes" -type f -name '*.class' -print | LC_ALL=C sort)
(( ${#probe_classes[@]} > 0 )) || fail "probe classes are missing"
"$d8" --lib "$android_jar" --classpath "$signed_apk" --output "$tmp_dir/dex" \
    "${probe_classes[@]}"
probe_dex="$tmp_dir/dex/classes.dex"
[[ -f "$probe_dex" ]] || fail "probe DEX is missing"

signed_output_sha256="$(sha256_file "$signed_apk")"
probe_source_sha256="$(source_bundle_sha256 "${probe_sources[@]}")"
probe_dex_sha256="$(sha256_file "$probe_dex")"
probe_nonce="rrv2-perf-${probe_dex_sha256:0:16}"
remote_dex="/tmp/${probe_nonce}.dex"
device_dex="/data/user/0/$PACKAGE/code_cache/${probe_nonce}.dex"

command -v timeout >/dev/null 2>&1 || fail "timeout is unavailable"
device_count="$(bounded_ssh adb devices |
    awk 'NR > 1 && $2 == "device" { count++ } END { print count + 0 }')"
[[ "$device_count" == "1" ]] || fail "exactly one authorized adb device is required"
bounded_ssh test ! -e "$remote_dex" || fail "remote probe staging path already exists"
remote_shell_script "test ! -e '$device_dex'" >/dev/null ||
    fail "device probe staging path already exists"

installed_apk="$(parse_installed_apk_path "$(remote_shell_script "pm path '$PACKAGE'")")" ||
    fail "installed APK path is not canonical"
installed_sha="$(remote_shell_script "sha256sum '$installed_apk'" | awk '{ print $1 }')"
[[ "$installed_sha" == "$signed_output_sha256" ]] || fail "installed APK does not match signed artifact"
device_uid="$(remote_shell_script "cmd package list packages -U '$PACKAGE'" |
    awk -v package="$PACKAGE" '$1 == "package:" package && $2 ~ /^uid:[0-9]+$/ {
        sub(/^uid:/, "", $2); print $2
    }')"
[[ "$device_uid" =~ ^[0-9]+$ ]] || fail "app UID is unavailable"
marker_content_sha="$(printf 'enabled-v2\n' | sha256sum | awk '{ print $1 }')"
initialize_marker_state || fail "feature marker recovery state is invalid"
bounded_scp "$probe_dex" "$ssh_target:$remote_dex"
bounded_ssh adb push "$remote_dex" "$device_dex" >/dev/null
remote_shell_script "chown '$device_uid:$device_uid' '$device_dex' && chmod 0600 '$device_dex'" >/dev/null

unsupported="$tmp_dir/unsupported.properties"
run_probe unsupported-cleanup-preflight "$unsupported" 3
empty="$tmp_dir/startup-empty.properties"
near_cap="$tmp_dir/startup-near-cap.properties"
capture="$tmp_dir/capture-bursts.properties"
capture_disabled="$tmp_dir/capture-disabled.properties"
transfer="$tmp_dir/transfer-drain-256.properties"
run_probe startup-empty "$empty"
run_probe startup-near-cap "$near_cap"
run_probe capture-bursts "$capture"
run_probe transfer-drain-256 "$transfer"
validate_transfer_output "$transfer"
[[ "$(probe_value "$near_cap" database_rows)" == "250000" ]] ||
    fail "near-cap probe row count is not exact"

[[ "$(marker_snapshot)" == "marker=exact backup=absent" ]] ||
    fail "feature marker is not exact before measurement"
anr_before="$(package_anr_count)"
[[ "$anr_before" =~ ^[0-9]+$ ]] || fail "initial ANR aggregate is unavailable"
measure_startups enabled_a
park_marker || fail "feature marker disable transition failed"
run_probe capture-disabled "$capture_disabled"
measure_startups disabled
restore_marker || fail "feature marker restore failed"
measure_startups enabled_b
[[ "$(marker_snapshot)" == "marker=exact backup=absent" ]] ||
    fail "feature marker is not exact after measurement"

anr_after="$(package_anr_count)"
[[ "$anr_after" =~ ^[0-9]+$ && "$anr_after" -ge "$anr_before" ]] ||
    fail "final ANR aggregate is unavailable"
anr_count=$(( anr_after - anr_before ))
[[ "$anr_count" == "0" ]] || fail "ANR evidence is nonzero"

aggregate="$output_dir/read-receipt-v2-performance-output.properties"
capture_metrics="$(capture_properties "$capture")"
cat >"$aggregate" <<EOF
format_version=2
signed_output_sha256=$signed_output_sha256
probe_source_sha256=$probe_source_sha256
probe_dex_sha256=$probe_dex_sha256
cleanup_failure_preflight=passed
startup_empty=passed
startup_near_cap=passed
capture_bursts=passed
capture_disabled=passed
transfer_drain_256=passed
marker_enabled_a=passed
marker_disabled=passed
marker_enabled_b=passed
startup_status_ok=passed
anr_count=$anr_count
hard_safety_5s=passed
startup_overhead_budget=passed
disabled_callback_budget=passed
active_call_budget=passed
burst_budget=passed
jank_budget=passed
budget_startup_p95_overhead_ms=$STARTUP_P95_OVERHEAD_BUDGET_MS
budget_disabled_capture_p99_us=$DISABLED_CAPTURE_P99_BUDGET_US
budget_active_capture_p99_us=$ACTIVE_CAPTURE_P99_BUDGET_US
budget_burst_1_p99_us=$BURST_1_P99_BUDGET_US
budget_burst_10_p99_us=$BURST_10_P99_BUDGET_US
budget_burst_100_p99_us=$BURST_100_P99_BUDGET_US
budget_burst_256_p99_us=$BURST_256_P99_BUDGET_US
budget_jank_ratio_percent=$JANK_RATIO_BUDGET_PERCENT
budget_jank_delta_percentage_points=$JANK_DELTA_BUDGET_PERCENTAGE_POINTS
empty_database_rows=$(probe_value "$empty" database_rows)
empty_database_triplet_bytes=$(probe_value "$empty" database_triplet_bytes)
near_cap_database_rows=$(probe_value "$near_cap" database_rows)
near_cap_database_triplet_bytes=$(probe_value "$near_cap" database_triplet_bytes)
capture_database_rows=$(probe_value "$capture" database_rows)
capture_database_triplet_bytes=$(probe_value "$capture" database_triplet_bytes)
disabled_capture_samples=$(probe_value "$capture_disabled" disabled_capture_samples)
disabled_capture_p50_us=$(probe_value "$capture_disabled" disabled_capture_p50_us)
disabled_capture_p95_us=$(probe_value "$capture_disabled" disabled_capture_p95_us)
disabled_capture_p99_us=$(probe_value "$capture_disabled" disabled_capture_p99_us)
disabled_capture_max_us=$(probe_value "$capture_disabled" disabled_capture_max_us)
enabled_a_samples=${startup_metric[enabled_a_samples]}
enabled_a_p50_ms=${startup_metric[enabled_a_p50_ms]}
enabled_a_p95_ms=${startup_metric[enabled_a_p95_ms]}
enabled_a_p99_ms=${startup_metric[enabled_a_p99_ms]}
enabled_a_max_ms=${startup_metric[enabled_a_max_ms]}
disabled_samples=${startup_metric[disabled_samples]}
disabled_p50_ms=${startup_metric[disabled_p50_ms]}
disabled_p95_ms=${startup_metric[disabled_p95_ms]}
disabled_p99_ms=${startup_metric[disabled_p99_ms]}
disabled_max_ms=${startup_metric[disabled_max_ms]}
enabled_b_samples=${startup_metric[enabled_b_samples]}
enabled_b_p50_ms=${startup_metric[enabled_b_p50_ms]}
enabled_b_p95_ms=${startup_metric[enabled_b_p95_ms]}
enabled_b_p99_ms=${startup_metric[enabled_b_p99_ms]}
enabled_b_max_ms=${startup_metric[enabled_b_max_ms]}
enabled_a_minus_disabled_p50_ms=$(( startup_metric[enabled_a_p50_ms] - startup_metric[disabled_p50_ms] ))
enabled_a_minus_disabled_p95_ms=$(( startup_metric[enabled_a_p95_ms] - startup_metric[disabled_p95_ms] ))
enabled_a_minus_disabled_p99_ms=$(( startup_metric[enabled_a_p99_ms] - startup_metric[disabled_p99_ms] ))
enabled_b_minus_disabled_p50_ms=$(( startup_metric[enabled_b_p50_ms] - startup_metric[disabled_p50_ms] ))
enabled_b_minus_disabled_p95_ms=$(( startup_metric[enabled_b_p95_ms] - startup_metric[disabled_p95_ms] ))
enabled_b_minus_disabled_p99_ms=$(( startup_metric[enabled_b_p99_ms] - startup_metric[disabled_p99_ms] ))
$capture_metrics
enabled_a_total_frames=${startup_metric[enabled_a_total_frames]}
enabled_a_janky_frames=${startup_metric[enabled_a_janky_frames]}
disabled_total_frames=${startup_metric[disabled_total_frames]}
disabled_janky_frames=${startup_metric[disabled_janky_frames]}
enabled_b_total_frames=${startup_metric[enabled_b_total_frames]}
enabled_b_janky_frames=${startup_metric[enabled_b_janky_frames]}
transfer_before_acked=$(probe_value "$transfer" transfer_before_acked)
transfer_before_highest=$(probe_value "$transfer" transfer_before_highest)
transfer_before_outbox=$(probe_value "$transfer" transfer_before_outbox)
transfer_frame_events=$(probe_value "$transfer" transfer_frame_events)
transfer_after_acked=$(probe_value "$transfer" transfer_after_acked)
transfer_after_highest=$(probe_value "$transfer" transfer_after_highest)
transfer_after_outbox=$(probe_value "$transfer" transfer_after_outbox)
transfer_elapsed_us=$(probe_value "$transfer" transfer_elapsed_us)
EOF

release_budgets_pass "$aggregate" || fail "release performance budget exceeded"

receipt="$output_dir/read-receipt-v2-performance-receipt.properties"
cat >"$receipt" <<EOF
format_version=2
lineage_id=$lineage_id
signed_output_sha256=$signed_output_sha256
probe_source_sha256=$probe_source_sha256
probe_dex_sha256=$probe_dex_sha256
aggregate_output_file=read-receipt-v2-performance-output.properties
aggregate_output_sha256=$(sha256_file "$aggregate")
cleanup_failure_preflight=passed
startup_empty=passed
startup_near_cap=passed
capture_bursts=passed
capture_disabled=passed
transfer_drain_256=passed
marker_enabled_a=passed
marker_disabled=passed
marker_enabled_b=passed
startup_status_ok=passed
anr_zero=passed
hard_safety_5s=passed
startup_overhead_budget=passed
disabled_callback_budget=passed
active_call_budget=passed
burst_budget=passed
jank_budget=passed
completed_at=$(date --iso-8601=seconds)
EOF

"$repo_root/patches/src/test/scripts/verify-read-receipt-v2-performance-receipt.sh" \
    "$receipt" "$lineage_id" "$signed_output_sha256"
