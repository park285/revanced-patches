#!/usr/bin/env bash
set -euo pipefail

readonly STARTUP_P95_OVERHEAD_BUDGET_MS=2000
readonly DISABLED_CAPTURE_P99_BUDGET_US=1000
readonly ACTIVE_CAPTURE_P99_BUDGET_US=250000
readonly BURST_1_P99_BUDGET_US=250000
readonly BURST_10_P99_BUDGET_US=500000
readonly BURST_100_P99_BUDGET_US=2500000
readonly BURST_256_P99_BUDGET_US=5000000
readonly JANK_RATIO_BUDGET_PERCENT=75
readonly JANK_DELTA_BUDGET_PERCENTAGE_POINTS=25

receipt_keys=(
    format_version lineage_id signed_output_sha256 probe_source_sha256 probe_dex_sha256
    aggregate_output_file aggregate_output_sha256 cleanup_failure_preflight startup_empty
    startup_near_cap capture_bursts capture_disabled transfer_drain_256
    marker_enabled_a marker_disabled marker_enabled_b startup_status_ok anr_zero hard_safety_5s
    startup_overhead_budget disabled_callback_budget active_call_budget burst_budget jank_budget
    completed_at
)

aggregate_keys=(
    format_version signed_output_sha256 probe_source_sha256 probe_dex_sha256
    cleanup_failure_preflight startup_empty startup_near_cap capture_bursts capture_disabled
    transfer_drain_256 marker_enabled_a marker_disabled marker_enabled_b startup_status_ok anr_count
    hard_safety_5s startup_overhead_budget disabled_callback_budget active_call_budget
    burst_budget jank_budget budget_startup_p95_overhead_ms
    budget_disabled_capture_p99_us budget_active_capture_p99_us budget_burst_1_p99_us
    budget_burst_10_p99_us budget_burst_100_p99_us budget_burst_256_p99_us
    budget_jank_ratio_percent budget_jank_delta_percentage_points
    empty_database_rows empty_database_triplet_bytes
    near_cap_database_rows near_cap_database_triplet_bytes capture_database_rows
    capture_database_triplet_bytes disabled_capture_samples disabled_capture_p50_us
    disabled_capture_p95_us disabled_capture_p99_us disabled_capture_max_us
    enabled_a_samples enabled_a_p50_ms enabled_a_p95_ms
    enabled_a_p99_ms enabled_a_max_ms disabled_samples disabled_p50_ms disabled_p95_ms
    disabled_p99_ms disabled_max_ms enabled_b_samples enabled_b_p50_ms enabled_b_p95_ms
    enabled_b_p99_ms enabled_b_max_ms enabled_a_minus_disabled_p50_ms
    enabled_a_minus_disabled_p95_ms enabled_a_minus_disabled_p99_ms
    enabled_b_minus_disabled_p50_ms enabled_b_minus_disabled_p95_ms
    enabled_b_minus_disabled_p99_ms capture_1_p50_us capture_1_p95_us capture_1_p99_us
    capture_1_max_us capture_1_burst_p50_us capture_1_burst_p95_us capture_1_burst_p99_us
    capture_10_p50_us capture_10_p95_us capture_10_p99_us
    capture_10_burst_p50_us capture_10_burst_p95_us capture_10_burst_p99_us
    capture_10_max_us capture_100_p50_us capture_100_p95_us capture_100_p99_us
    capture_100_burst_p50_us capture_100_burst_p95_us capture_100_burst_p99_us
    capture_100_max_us capture_256_p50_us capture_256_p95_us capture_256_p99_us
    capture_256_max_us capture_256_burst_p50_us capture_256_burst_p95_us
    capture_256_burst_p99_us enabled_a_total_frames enabled_a_janky_frames
    disabled_total_frames disabled_janky_frames enabled_b_total_frames enabled_b_janky_frames
    transfer_before_acked transfer_before_highest transfer_before_outbox transfer_frame_events
    transfer_after_acked transfer_after_highest transfer_after_outbox transfer_elapsed_us
)

validation_error=""
tmp_dir=""

fail() {
    echo "read-receipt performance receipt verification: $1" >&2
    exit 1
}

reject() {
    validation_error="$1"
    return 1
}

sha256_file() {
    sha256sum -- "$1" | awk '{ print $1 }'
}

property_from() {
    local file="$1"
    local key="$2"
    awk -F= -v key="$key" '$1 == key { sub(/^[^=]*=/, ""); print }' "$file"
}

validate_property_file() {
    local file="$1"
    shift
    local invalid_line duplicate_key key
    local -a expected=("$@")
    local -A allowed=()
    invalid_line="$(awk '!/^[a-z0-9_]+=[^\r\n]*$/ { print NR; exit }' "$file")"
    [[ -z "$invalid_line" ]] || reject "invalid properties line $invalid_line" || return
    duplicate_key="$(cut -d= -f1 "$file" | LC_ALL=C sort | uniq -d | head -n 1)"
    [[ -z "$duplicate_key" ]] || reject "duplicate key $duplicate_key" || return
    for key in "${expected[@]}"; do
        allowed["$key"]=1
        [[ -n "$(property_from "$file" "$key")" ]] || reject "missing property $key" || return
    done
    while IFS='=' read -r key _; do
        [[ -n "${allowed[$key]:-}" ]] || reject "unknown property $key" || return
    done <"$file"
    [[ "$(wc -l <"$file")" == "${#expected[@]}" ]] || \
        reject "properties field count is not exact"
}

require_digest() {
    local value="$1"
    [[ "$value" =~ ^[0-9a-f]{64}$ ]]
}

require_passed() {
    local file="$1"
    shift
    local key
    for key in "$@"; do
        [[ "$(property_from "$file" "$key")" == "passed" ]] || return 1
    done
}

require_non_negative() {
    local file="$1"
    shift
    local key value
    for key in "$@"; do
        value="$(property_from "$file" "$key")"
        [[ "$value" =~ ^[0-9]+$ ]] || return 1
    done
}

require_signed_integer() {
    local file="$1"
    shift
    local key value
    for key in "$@"; do
        value="$(property_from "$file" "$key")"
        [[ "$value" =~ ^-?[0-9]+$ ]] || return 1
    done
}

metric_from() {
    local file="$1"
    local key="$2"
    local value
    value="$(property_from "$file" "$key")"
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
    local label size limit disabled_p95 enabled_p95
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

verify_receipt() {
    local receipt="$1"
    local expected_lineage="$2"
    local expected_apk_sha="$3"
    local aggregate receipt_dir
    [[ -f "$receipt" && ! -L "$receipt" ]] || fail "receipt is not a regular file"
    validate_property_file "$receipt" "${receipt_keys[@]}" || fail "$validation_error"
    [[ "$(property_from "$receipt" format_version)" == "2" ]] || fail "receipt format is unsupported"
    [[ "$expected_lineage" =~ ^[0-9a-f]{64}$ && "$expected_apk_sha" =~ ^[0-9a-f]{64}$ ]] || \
        fail "expected binding is not canonical"
    [[ "$(property_from "$receipt" lineage_id)" == "$expected_lineage" ]] || \
        fail "lineage binding mismatch"
    [[ "$(property_from "$receipt" signed_output_sha256)" == "$expected_apk_sha" ]] || \
        fail "signed artifact binding mismatch"
    require_digest "$(property_from "$receipt" probe_source_sha256)" || fail "source digest is not canonical"
    require_digest "$(property_from "$receipt" probe_dex_sha256)" || fail "DEX digest is not canonical"
    require_digest "$(property_from "$receipt" aggregate_output_sha256)" || \
        fail "aggregate digest is not canonical"
    [[ "$(property_from "$receipt" aggregate_output_file)" == \
        "read-receipt-v2-performance-output.properties" ]] || fail "aggregate filename is not exact"
    require_passed "$receipt" cleanup_failure_preflight startup_empty startup_near_cap \
        capture_bursts capture_disabled transfer_drain_256 marker_enabled_a marker_disabled \
        marker_enabled_b startup_status_ok anr_zero hard_safety_5s startup_overhead_budget \
        disabled_callback_budget active_call_budget burst_budget jank_budget || \
        fail "required performance mode or budget is not passed"
    [[ "$(property_from "$receipt" completed_at)" =~ \
        ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(Z|[+-][0-9]{2}:[0-9]{2})$ ]] || \
        fail "completion timestamp is not canonical"

    receipt_dir="$(cd -- "$(dirname -- "$receipt")" && pwd)"
    aggregate="$receipt_dir/$(property_from "$receipt" aggregate_output_file)"
    [[ -f "$aggregate" && ! -L "$aggregate" ]] || fail "aggregate output is not a regular file"
    [[ "$(sha256_file "$aggregate")" == "$(property_from "$receipt" aggregate_output_sha256)" ]] || \
        fail "aggregate output digest mismatch"
    validate_property_file "$aggregate" "${aggregate_keys[@]}" || fail "$validation_error"
    [[ "$(property_from "$aggregate" format_version)" == "2" ]] || fail "aggregate format is unsupported"
    [[ "$(property_from "$aggregate" signed_output_sha256)" == "$expected_apk_sha" &&
        "$(property_from "$aggregate" probe_source_sha256)" == \
            "$(property_from "$receipt" probe_source_sha256)" &&
        "$(property_from "$aggregate" probe_dex_sha256)" == \
            "$(property_from "$receipt" probe_dex_sha256)" ]] || fail "aggregate binding mismatch"
    require_passed "$aggregate" cleanup_failure_preflight startup_empty startup_near_cap \
        capture_bursts capture_disabled transfer_drain_256 marker_enabled_a marker_disabled \
        marker_enabled_b startup_status_ok hard_safety_5s startup_overhead_budget \
        disabled_callback_budget active_call_budget burst_budget jank_budget || \
        fail "aggregate required mode or budget is not passed"
    [[ "$(property_from "$aggregate" anr_count)" == "0" ]] || fail "ANR count is nonzero"
    [[ "$(property_from "$aggregate" near_cap_database_rows)" == "250000" ]] || \
        fail "near-cap row count is not exact"
    require_non_negative "$aggregate" \
        budget_startup_p95_overhead_ms budget_disabled_capture_p99_us \
        budget_active_capture_p99_us budget_burst_1_p99_us budget_burst_10_p99_us \
        budget_burst_100_p99_us budget_burst_256_p99_us budget_jank_ratio_percent \
        budget_jank_delta_percentage_points \
        empty_database_rows empty_database_triplet_bytes near_cap_database_rows \
        near_cap_database_triplet_bytes capture_database_rows capture_database_triplet_bytes \
        disabled_capture_samples disabled_capture_p50_us disabled_capture_p95_us \
        disabled_capture_p99_us disabled_capture_max_us \
        enabled_a_samples enabled_a_p50_ms enabled_a_p95_ms enabled_a_p99_ms enabled_a_max_ms \
        disabled_samples disabled_p50_ms disabled_p95_ms disabled_p99_ms disabled_max_ms \
        enabled_b_samples enabled_b_p50_ms enabled_b_p95_ms enabled_b_p99_ms enabled_b_max_ms \
        capture_1_p50_us capture_1_p95_us capture_1_p99_us capture_1_max_us \
        capture_1_burst_p50_us capture_1_burst_p95_us capture_1_burst_p99_us \
        capture_10_p50_us capture_10_p95_us capture_10_p99_us capture_10_max_us \
        capture_10_burst_p50_us capture_10_burst_p95_us capture_10_burst_p99_us \
        capture_100_p50_us capture_100_p95_us capture_100_p99_us capture_100_max_us \
        capture_100_burst_p50_us capture_100_burst_p95_us capture_100_burst_p99_us \
        capture_256_p50_us capture_256_p95_us capture_256_p99_us capture_256_max_us \
        capture_256_burst_p50_us capture_256_burst_p95_us capture_256_burst_p99_us \
        enabled_a_total_frames enabled_a_janky_frames disabled_total_frames \
        disabled_janky_frames enabled_b_total_frames enabled_b_janky_frames \
        transfer_before_acked transfer_before_highest transfer_before_outbox \
        transfer_frame_events transfer_after_acked transfer_after_highest \
        transfer_after_outbox transfer_elapsed_us || \
        fail "aggregate measurement is not a non-negative integer"
    [[ "$(property_from "$aggregate" budget_startup_p95_overhead_ms)" == \
            "$STARTUP_P95_OVERHEAD_BUDGET_MS" &&
        "$(property_from "$aggregate" budget_disabled_capture_p99_us)" == \
            "$DISABLED_CAPTURE_P99_BUDGET_US" &&
        "$(property_from "$aggregate" budget_active_capture_p99_us)" == \
            "$ACTIVE_CAPTURE_P99_BUDGET_US" &&
        "$(property_from "$aggregate" budget_burst_1_p99_us)" == "$BURST_1_P99_BUDGET_US" &&
        "$(property_from "$aggregate" budget_burst_10_p99_us)" == "$BURST_10_P99_BUDGET_US" &&
        "$(property_from "$aggregate" budget_burst_100_p99_us)" == "$BURST_100_P99_BUDGET_US" &&
        "$(property_from "$aggregate" budget_burst_256_p99_us)" == "$BURST_256_P99_BUDGET_US" &&
        "$(property_from "$aggregate" budget_jank_ratio_percent)" == "$JANK_RATIO_BUDGET_PERCENT" &&
        "$(property_from "$aggregate" budget_jank_delta_percentage_points)" == \
            "$JANK_DELTA_BUDGET_PERCENTAGE_POINTS" ]] || fail "release budget constants drifted"
    require_signed_integer "$aggregate" enabled_a_minus_disabled_p50_ms \
        enabled_a_minus_disabled_p95_ms enabled_a_minus_disabled_p99_ms \
        enabled_b_minus_disabled_p50_ms enabled_b_minus_disabled_p95_ms \
        enabled_b_minus_disabled_p99_ms || fail "aggregate delta is not an integer"
    for label in enabled_a enabled_b; do
        for percentile_value in p50 p95 p99; do
            [[ "$(property_from "$aggregate" "${label}_minus_disabled_${percentile_value}_ms")" == \
                "$(( $(property_from "$aggregate" "${label}_${percentile_value}_ms") -
                    $(property_from "$aggregate" "disabled_${percentile_value}_ms") ))" ]] ||
                fail "startup delta is inconsistent"
        done
    done
    [[ "$(property_from "$aggregate" enabled_a_samples)" == "11" &&
        "$(property_from "$aggregate" disabled_samples)" == "11" &&
        "$(property_from "$aggregate" enabled_b_samples)" == "11" ]] || \
        fail "startup sample count is not exact"
    [[ "$(property_from "$aggregate" disabled_capture_samples)" == "1000" ]] ||
        fail "disabled callback sample count is not exact"
    [[ "$(property_from "$aggregate" transfer_before_acked)" == "0" &&
        "$(property_from "$aggregate" transfer_before_highest)" == "256" &&
        "$(property_from "$aggregate" transfer_before_outbox)" == "256" &&
        "$(property_from "$aggregate" transfer_frame_events)" == "256" &&
        "$(property_from "$aggregate" transfer_after_acked)" == "256" &&
        "$(property_from "$aggregate" transfer_after_highest)" == "256" &&
        "$(property_from "$aggregate" transfer_after_outbox)" == "0" ]] || \
        fail "transfer drain state is not exact"
    (( $(property_from "$aggregate" transfer_elapsed_us) < 5000000 )) || \
        fail "transfer drain exceeded hard safety limit"
    release_budgets_pass "$aggregate" || fail "release performance budget exceeded"
}

write_self_test_aggregate() {
    local path="$1"
    local apk_sha="$2"
    local source_sha="$3"
    local dex_sha="$4"
    local key value
    : >"$path"
    for key in "${aggregate_keys[@]}"; do
        value="1"
        case "$key" in
            format_version) value="2" ;;
            signed_output_sha256) value="$apk_sha" ;;
            probe_source_sha256) value="$source_sha" ;;
            probe_dex_sha256) value="$dex_sha" ;;
            budget_startup_p95_overhead_ms) value="$STARTUP_P95_OVERHEAD_BUDGET_MS" ;;
            budget_disabled_capture_p99_us) value="$DISABLED_CAPTURE_P99_BUDGET_US" ;;
            budget_active_capture_p99_us) value="$ACTIVE_CAPTURE_P99_BUDGET_US" ;;
            budget_burst_1_p99_us) value="$BURST_1_P99_BUDGET_US" ;;
            budget_burst_10_p99_us) value="$BURST_10_P99_BUDGET_US" ;;
            budget_burst_100_p99_us) value="$BURST_100_P99_BUDGET_US" ;;
            budget_burst_256_p99_us) value="$BURST_256_P99_BUDGET_US" ;;
            budget_jank_ratio_percent) value="$JANK_RATIO_BUDGET_PERCENT" ;;
            budget_jank_delta_percentage_points) value="$JANK_DELTA_BUDGET_PERCENTAGE_POINTS" ;;
            cleanup_failure_preflight|startup_empty|startup_near_cap|capture_bursts|capture_disabled|transfer_drain_256|marker_enabled_a|marker_disabled|marker_enabled_b|startup_status_ok|hard_safety_5s|startup_overhead_budget|disabled_callback_budget|active_call_budget|burst_budget|jank_budget) value="passed" ;;
            anr_count) value="0" ;;
            near_cap_database_rows) value="250000" ;;
            enabled_a_samples|disabled_samples|enabled_b_samples) value="11" ;;
            disabled_capture_samples) value="1000" ;;
            disabled_p95_ms) value="100" ;;
            enabled_a_p95_ms|enabled_b_p95_ms) value="2100" ;;
            disabled_capture_p99_us) value="1000" ;;
            capture_1_p99_us|capture_10_p99_us|capture_100_p99_us|capture_256_p99_us) value="250000" ;;
            capture_1_burst_p99_us) value="250000" ;;
            capture_10_burst_p99_us) value="500000" ;;
            capture_100_burst_p99_us) value="2500000" ;;
            capture_256_burst_p99_us) value="4999999" ;;
            enabled_a_total_frames|disabled_total_frames|enabled_b_total_frames) value="100" ;;
            disabled_janky_frames) value="50" ;;
            enabled_a_janky_frames|enabled_b_janky_frames) value="75" ;;
            transfer_before_acked|transfer_after_outbox) value="0" ;;
            transfer_before_highest|transfer_before_outbox|transfer_frame_events|transfer_after_acked|transfer_after_highest) value="256" ;;
            enabled_a_minus_disabled_p95_ms|enabled_b_minus_disabled_p95_ms) value="2000" ;;
            *_minus_disabled_*) value="0" ;;
        esac
        printf '%s=%s\n' "$key" "$value" >>"$path"
    done
}

run_self_test() {
    local aggregate receipt lineage apk_sha source_sha dex_sha stale pristine mutation
    tmp_dir="$(mktemp -d)"
    trap 'rm -rf -- "$tmp_dir"' EXIT
    aggregate="$tmp_dir/read-receipt-v2-performance-output.properties"
    receipt="$tmp_dir/read-receipt-v2-performance-receipt.properties"
    lineage="$(printf '1%.0s' {1..64})"
    apk_sha="$(printf '2%.0s' {1..64})"
    source_sha="$(printf '3%.0s' {1..64})"
    dex_sha="$(printf '4%.0s' {1..64})"
    write_self_test_aggregate "$aggregate" "$apk_sha" "$source_sha" "$dex_sha"
    cat >"$receipt" <<EOF
format_version=2
lineage_id=$lineage
signed_output_sha256=$apk_sha
probe_source_sha256=$source_sha
probe_dex_sha256=$dex_sha
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
completed_at=2026-07-19T00:00:00+09:00
EOF
    verify_receipt "$receipt" "$lineage" "$apk_sha"
    pristine="$tmp_dir/pristine-aggregate.properties"
    cp -- "$aggregate" "$pristine"
    for mutation in \
        's/enabled_a_p95_ms=2100/enabled_a_p95_ms=2101/; s/enabled_a_minus_disabled_p95_ms=2000/enabled_a_minus_disabled_p95_ms=2001/' \
        's/disabled_capture_p99_us=1000/disabled_capture_p99_us=1001/' \
        's/capture_10_p99_us=250000/capture_10_p99_us=250001/' \
        's/capture_256_burst_p99_us=4999999/capture_256_burst_p99_us=5000000/' \
        's/enabled_a_janky_frames=75/enabled_a_janky_frames=76/'; do
        sed "$mutation" "$pristine" >"$aggregate"
        sed "s/^aggregate_output_sha256=.*/aggregate_output_sha256=$(sha256_file "$aggregate")/" \
            "$receipt" >"$tmp_dir/budget-receipt.properties"
        if ( verify_receipt "$tmp_dir/budget-receipt.properties" "$lineage" "$apk_sha" ) \
            2>/dev/null; then
            fail "self-test accepted a release budget exceedance"
        fi
    done
    sed 's/budget_active_capture_p99_us=250000/budget_active_capture_p99_us=250001/' \
        "$pristine" >"$aggregate"
    sed "s/^aggregate_output_sha256=.*/aggregate_output_sha256=$(sha256_file "$aggregate")/" \
        "$receipt" >"$tmp_dir/budget-receipt.properties"
    if ( verify_receipt "$tmp_dir/budget-receipt.properties" "$lineage" "$apk_sha" ) \
        2>/dev/null; then
        fail "self-test accepted release budget constant drift"
    fi
    cp -- "$pristine" "$aggregate"
    stale="$tmp_dir/stale.properties"
    sed 's/^capture_bursts=passed$/capture_bursts=pending/' "$receipt" >"$stale"
    if ( verify_receipt "$stale" "$lineage" "$apk_sha" ) 2>/dev/null; then
        fail "self-test accepted a missing required mode"
    fi
    printf 'tamper\n' >>"$aggregate"
    if ( verify_receipt "$receipt" "$lineage" "$apk_sha" ) 2>/dev/null; then
        fail "self-test accepted aggregate output tamper"
    fi
    echo "RRV2-PERFORMANCE-RECEIPT-SELF-TEST passed: exact=1 requiredModeReject=1 budgetExceedReject=5 budgetDriftReject=1 aggregateTamperReject=1"
}

if (( $# == 1 )) && [[ "$1" == "--self-test" ]]; then
    run_self_test
    exit 0
fi

if (( $# != 3 )); then
    echo "usage: verify-read-receipt-v2-performance-receipt.sh <receipt> <lineage-id> <signed-apk-sha256>" >&2
    exit 2
fi

verify_receipt "$1" "$2" "$3"
echo "RRV2-PERFORMANCE-RECEIPT passed: modes=9 startupSamples=33 anr=0 hardSafety5s=passed releaseBudgets=passed"
