#!/usr/bin/env bash
set -euo pipefail

PINNED_INPUT_SHA256="c6e5d6faa527d29c7472db97c4c867f9b8cd575328dfb3786c54aac32a3c39dc"

required_keys=(
    format_version generated_at evidence_phase artifact_status release_readiness
    input_path input_sha256 input_package input_version_name input_version_code input_signer_sha256
    output_path output_sha256 output_bytes output_package output_version_name output_version_code
    output_signature_status output_signer_sha256 extension_mpe_path extension_mpe_sha256
    revanced_head revanced_origin_dev revanced_tracked_diff_sha256
    revanced_untracked_file_manifest_sha256 revanced_status_paths_sha256
    iris_worktree_path iris_task1_head iris_origin_main iris_tracked_diff_sha256
    iris_untracked_file_manifest_sha256 iris_status_paths_sha256
    gradle_version kotlin_version java_version aapt2_version apksigner_version kernel
    capture_hooks accessors native_abis existing_show_message_read_receipts_patch_reapplied
    staged_hook_boundary cap011_dex_gate transfer_256_host_gate source_epoch_boundary_gate
    durable_property_gate wal_cap_fail_closed_gate unsigned_wrong_signer_rejection_gate
    boot_id_security_review final_delta_review patches_build_android
    signed_final_verification android_device_smoke iris_integration
    lineage_id dependency_lane apply_receipt_path apply_receipt_sha256 unsigned_output_sha256
    canonical_payload_sha256 parent_provenance_path parent_provenance_sha256
    parent_artifact_sha256 parent_payload_sha256 smoke_receipt_path smoke_receipt_sha256
    performance_receipt_path performance_receipt_sha256
)

apply_receipt_keys=(
    format_version lineage_id artifact_kind dependency_lane input_sha256 extension_mpe_sha256
    revanced_head revanced_tree_sha256 iris_head iris_tree_sha256
    output_sha256 output_payload_sha256 output_bytes
)

smoke_receipt_keys=(
    format_version lineage_id signed_output_sha256 performance_receipt_sha256
    device_smoke completed_at
)

validation_error=""
provenance=""
tmp_dir=""

fail() {
    echo "read-receipt provenance verification: $1" >&2
    exit 1
}

reject() {
    validation_error="$1"
    return 1
}

property() {
    local key="$1"
    awk -F= -v key="$key" '$1 == key { sub(/^[^=]*=/, ""); print }' "$provenance"
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
    local -a expected_keys=()
    local -A allowed=()
    if (( $# > 0 )); then
        expected_keys=("$@")
    else
        expected_keys=("${required_keys[@]}")
    fi
    invalid_line="$(awk '!/^[a-z0-9_]+=[^\r\n]*$/ { print NR; exit }' "$file")"
    [[ -z "$invalid_line" ]] || reject "invalid properties line $invalid_line" || return
    duplicate_key="$(cut -d= -f1 "$file" | LC_ALL=C sort | uniq -d | head -n 1)"
    [[ -z "$duplicate_key" ]] || reject "duplicate key $duplicate_key" || return
    for key in "${expected_keys[@]}"; do
        allowed["$key"]=1
    done
    while IFS='=' read -r key _; do
        [[ -n "${allowed[$key]:-}" ]] || reject "unknown property $key" || return
    done <"$file"
    [[ "$(wc -l <"$file")" == "${#expected_keys[@]}" ]] || \
        reject "properties field count is not exact" || return
}

require_properties() {
    local key value
    for key in "${required_keys[@]}"; do
        value="$(property "$key")"
        [[ -n "$value" ]] || reject "missing property $key" || return
    done
}

require_file_properties() {
    local file="$1"
    shift
    local key value
    for key in "$@"; do
        value="$(property_from "$file" "$key")"
        [[ -n "$value" ]] || reject "missing property $key" || return
    done
}

validate_gate_value() {
    local key="$1"
    local value
    value="$(property "$key")"
    [[ "$value" == "pending" || "$value" == "passed" ]] || \
        reject "$key must be pending or passed"
}

validate_phase() {
    local requested_phase="$1"
    local gate
    [[ "$(property evidence_phase)" == "$requested_phase" ]] || \
        reject "evidence_phase does not match requested phase" || return
    for gate in patches_build_android signed_final_verification android_device_smoke iris_integration; do
        validate_gate_value "$gate" || return
    done
    case "$requested_phase" in
        unsigned)
            [[ "$(property artifact_status)" == "unsigned_evidence" ]] || \
                reject "unsigned artifact_status is not exact" || return
            [[ "$(property release_readiness)" == "pending_prerequisites" ]] || \
                reject "unsigned evidence cannot be release-ready" || return
            [[ "$(property output_signature_status)" == "unsigned_does_not_verify" ]] || \
                reject "unsigned signature status is not exact" || return
            [[ "$(property output_signer_sha256)" == "none" ]] || \
                reject "unsigned evidence records a signer" || return
            [[ "$(property signed_final_verification)" == "pending" ]] || \
                reject "unsigned evidence cannot pass signed verification" || return
            [[ "$(property android_device_smoke)" == "pending" ]] || \
                reject "unsigned evidence cannot pass device smoke" || return
            ;;
        pre_sign)
            [[ "$(property artifact_status)" == "pre_sign_ready" ]] || \
                reject "pre-sign artifact_status is not exact" || return
            [[ "$(property release_readiness)" == "pending_signature_and_device_smoke" ]] || \
                reject "pre-sign evidence cannot be release-ready" || return
            [[ "$(property output_signature_status)" == "unsigned_does_not_verify" ]] || \
                reject "pre-sign output must remain unsigned" || return
            [[ "$(property output_signer_sha256)" == "none" ]] || \
                reject "pre-sign evidence records a signer" || return
            [[ "$(property patches_build_android)" == "passed" ]] || \
                reject "pre-sign requires patches_build_android=passed" || return
            [[ "$(property iris_integration)" == "passed" ]] || \
                reject "pre-sign requires iris_integration=passed" || return
            [[ "$(property signed_final_verification)" == "pending" ]] || \
                reject "pre-sign cannot pass signed verification" || return
            [[ "$(property android_device_smoke)" == "pending" ]] || \
                reject "pre-sign cannot pass device smoke" || return
            ;;
        post_sign)
            [[ "$(property artifact_status)" == "signed_candidate" ]] || \
                reject "post-sign artifact_status is not exact" || return
            [[ "$(property release_readiness)" == "pending_device_smoke" ]] || \
                reject "post-sign evidence cannot be release-ready" || return
            [[ "$(property output_signature_status)" == "verified" ]] || \
                reject "post-sign output is not verified" || return
            [[ "$(property output_signer_sha256)" =~ ^[0-9a-f]{64}$ ]] || \
                reject "post-sign signer digest is not canonical" || return
            [[ "$(property patches_build_android)" == "passed" ]] || \
                reject "post-sign requires patches_build_android=passed" || return
            [[ "$(property iris_integration)" == "passed" ]] || \
                reject "post-sign requires iris_integration=passed" || return
            [[ "$(property signed_final_verification)" == "passed" ]] || \
                reject "post-sign requires signed_final_verification=passed" || return
            [[ "$(property android_device_smoke)" == "pending" ]] || \
                reject "post-sign device smoke must remain pending until final" || return
            ;;
        final)
            [[ "$(property artifact_status)" == "release_ready" ]] || \
                reject "final artifact_status is not release_ready" || return
            [[ "$(property release_readiness)" == "release_ready" ]] || \
                reject "final release_readiness is not exact" || return
            [[ "$(property output_signature_status)" == "verified" ]] || \
                reject "final output is not verified" || return
            [[ "$(property output_signer_sha256)" =~ ^[0-9a-f]{64}$ ]] || \
                reject "final signer digest is not canonical" || return
            for gate in patches_build_android iris_integration signed_final_verification android_device_smoke; do
                [[ "$(property "$gate")" == "passed" ]] || reject "final requires $gate=passed" || return
            done
            ;;
        *)
            reject "unsupported phase $requested_phase"
            ;;
    esac
}

validate_signer_pin() {
    local requested_phase="$1"
    local expected_signer="$2"
    if [[ "$requested_phase" == "post_sign" || "$requested_phase" == "final" ]]; then
        [[ "$expected_signer" =~ ^[0-9a-f]{64}$ ]] || \
            reject "signed phase requires a canonical external signer pin" || return
        [[ "$(property output_signer_sha256)" == "$expected_signer" ]] || \
            reject "provenance signer does not match the external pin" || return
    else
        [[ -z "$expected_signer" ]] || reject "unsigned phase received a signer pin"
    fi
}

sha256_file() {
    sha256sum -- "$1" | awk '{ print $1 }'
}

hash_matches() {
    local path="$1"
    local expected="$2"
    [[ -f "$path" && "$expected" =~ ^[0-9a-f]{64}$ && "$(sha256_file "$path")" == "$expected" ]]
}

signer_report_matches() {
    local report="$1"
    local expected="$2"
    local -a digests=()
    mapfile -t digests < <(
        sed -n 's/^Signer #[0-9][0-9]* certificate SHA-256 digest: //p' "$report" |
            tr 'A-F' 'a-f'
    )
    [[ "${#digests[@]}" == "1" && "${digests[0]}" == "$expected" ]]
}

badging_report_matches() {
    local report="$1"
    local expected_package="$2"
    local expected_version_name="$3"
    local expected_version_code="$4"
    local -a records=()
    local actual_package actual_version_code actual_version_name
    mapfile -t records < <(
        sed -n "s/^package: name='\([^']*\)' versionCode='\([0-9][0-9]*\)' versionName='\([^']*\)'.*$/\1\t\2\t\3/p" \
            "$report"
    )
    [[ "${#records[@]}" == "1" ]] || return 1
    IFS=$'\t' read -r actual_package actual_version_code actual_version_name <<<"${records[0]}"
    [[ "$actual_package" == "$expected_package" &&
        "$actual_version_name" == "$expected_version_name" &&
        "$actual_version_code" == "$expected_version_code" ]]
}

verify_apk_metadata() {
    local path="$1"
    local prefix="$2"
    local report="$tmp_dir/${prefix}-badging.txt"
    aapt2 dump badging "$path" >"$report" 2>&1 || fail "$prefix APK metadata inspection failed"
    badging_report_matches "$report" "$(property "${prefix}_package")" \
        "$(property "${prefix}_version_name")" "$(property "${prefix}_version_code")" || \
        fail "$prefix APK metadata mismatch"
}

stable_file_identity() {
    local path="$1"
    [[ -f "$path" && ! -L "$path" ]] || return 1
    printf '%s:%s\n' "$(stat -c '%d:%i:%s:%Y' -- "$path")" "$(sha256_file "$path")"
}

tree_evidence_sha256() {
    local prefix="$1"
    local head_key="${prefix}_head"
    [[ "$prefix" != "iris" ]] || head_key="iris_task1_head"
    printf 'head=%s\ntracked=%s\nuntracked=%s\nstatus=%s\n' \
        "$(property "$head_key")" \
        "$(property "${prefix}_tracked_diff_sha256")" \
        "$(property "${prefix}_untracked_file_manifest_sha256")" \
        "$(property "${prefix}_status_paths_sha256")" | sha256sum | awk '{ print $1 }'
}

is_signature_entry() {
    local upper="${1^^}"
    [[ "$upper" == "META-INF/MANIFEST.MF" ||
        ( "$upper" == META-INF/* &&
            ( "$upper" == *.SF || "$upper" == *.RSA || "$upper" == *.DSA ||
                "$upper" == *.EC || "${upper#META-INF/}" == SIG-* ) ) ]]
}

canonical_payload_sha256() {
    local apk="$1"
    local entry pattern
    {
        while IFS= read -r entry; do
            [[ "$entry" != */ ]] || continue
            is_signature_entry "$entry" && continue
            [[ "$entry" != *$'\r'* ]] || return 1
            printf '%s\0' "$entry"
            pattern="$(printf '%s' "$entry" | sed 's/[][?*]/[&]/g')"
            unzip -p "$apk" "$pattern" | sha256sum | awk '{ print $1 }'
        done < <(zipinfo -1 "$apk" | LC_ALL=C sort)
    } | sha256sum | awk '{ print $1 }'
}

validate_apply_receipt() {
    local receipt_path receipt_output_sha receipt_payload_sha receipt_dependency_lane
    receipt_path="$(property apply_receipt_path)"
    [[ -f "$receipt_path" && ! -L "$receipt_path" ]] || fail "apply receipt is not a regular owned file"
    [[ "$(stat -c '%A' -- "$receipt_path")" != *w* ]] || fail "apply receipt is writable"
    hash_matches "$receipt_path" "$(property apply_receipt_sha256)" || fail "apply_receipt_sha256 mismatch"
    validate_property_file "$receipt_path" "${apply_receipt_keys[@]}" || fail "$validation_error"
    require_file_properties "$receipt_path" "${apply_receipt_keys[@]}" || fail "$validation_error"
    [[ "$(property_from "$receipt_path" format_version)" == "1" ]] || fail "apply receipt format is unsupported"
    [[ "$(property_from "$receipt_path" artifact_kind)" == "unsigned" ]] || \
        fail "apply receipt must bind the unsigned exact-delta output"
    receipt_dependency_lane="$(property_from "$receipt_path" dependency_lane)"
    [[ "$receipt_dependency_lane" == "release" || "$receipt_dependency_lane" == "pinned_offline" ]] || \
        fail "apply receipt dependency lane is unsupported"
    [[ "$receipt_dependency_lane" == "$(property dependency_lane)" ]] || \
        fail "apply receipt dependency lane mismatch"
    [[ "$(property_from "$receipt_path" lineage_id)" == "$(property lineage_id)" ]] || \
        fail "apply receipt lineage mismatch"
    [[ "$(property_from "$receipt_path" input_sha256)" == "$PINNED_INPUT_SHA256" ]] || \
        fail "apply receipt input hash mismatch"
    [[ "$(property_from "$receipt_path" extension_mpe_sha256)" == "$(property extension_mpe_sha256)" ]] || \
        fail "apply receipt extension hash mismatch"
    [[ "$(property_from "$receipt_path" revanced_head)" == "$(property revanced_head)" ]] || \
        fail "apply receipt ReVanced head mismatch"
    [[ "$(property_from "$receipt_path" revanced_tree_sha256)" == "$(tree_evidence_sha256 revanced)" ]] || \
        fail "apply receipt ReVanced tree mismatch"
    [[ "$(property_from "$receipt_path" iris_head)" == "$(property iris_task1_head)" ]] || \
        fail "apply receipt Iris head mismatch"
    [[ "$(property_from "$receipt_path" iris_tree_sha256)" == "$(tree_evidence_sha256 iris)" ]] || \
        fail "apply receipt Iris tree mismatch"
    receipt_output_sha="$(property_from "$receipt_path" output_sha256)"
    receipt_payload_sha="$(property_from "$receipt_path" output_payload_sha256)"
    [[ "$receipt_output_sha" =~ ^[0-9a-f]{64}$ && "$receipt_payload_sha" =~ ^[0-9a-f]{64}$ &&
        "$(property_from "$receipt_path" revanced_tree_sha256)" =~ ^[0-9a-f]{64}$ &&
        "$(property_from "$receipt_path" iris_tree_sha256)" =~ ^[0-9a-f]{64}$ &&
        "$(property_from "$receipt_path" output_bytes)" =~ ^[0-9]+$ ]] || \
        fail "apply receipt digests or size are not canonical"
    [[ "$receipt_output_sha" == "$(property unsigned_output_sha256)" ]] || \
        fail "apply receipt unsigned output mismatch"
    [[ "$receipt_payload_sha" == "$(property canonical_payload_sha256)" ]] || \
        fail "apply receipt canonical payload mismatch"
    if [[ "$requested_phase" == "unsigned" || "$requested_phase" == "pre_sign" ]]; then
        [[ "$receipt_output_sha" == "$(property output_sha256)" ]] || \
            fail "unsigned phase output is not the applied artifact"
        [[ "$(property_from "$receipt_path" output_bytes)" == "$(property output_bytes)" ]] || \
            fail "apply receipt output size mismatch"
    fi
}

validate_parent_chain() {
    local phase="$1"
    local expected_parent parent_path parent_hash parent_phase
    if [[ "$phase" == "unsigned" ]]; then
        for key in parent_provenance_path parent_provenance_sha256 parent_artifact_sha256 parent_payload_sha256; do
            [[ "$(property "$key")" == "none" ]] || fail "unsigned phase unexpectedly has parent evidence"
        done
        return
    fi
    case "$phase" in
        pre_sign) expected_parent="unsigned" ;;
        post_sign) expected_parent="pre_sign" ;;
        final) expected_parent="post_sign" ;;
        *) fail "unsupported parent phase" ;;
    esac
    parent_path="$(property parent_provenance_path)"
    parent_hash="$(property parent_provenance_sha256)"
    [[ -f "$parent_path" && ! -L "$parent_path" ]] || fail "parent provenance is not a regular file"
    hash_matches "$parent_path" "$parent_hash" || fail "parent_provenance_sha256 mismatch"
    validate_property_file "$parent_path" "${required_keys[@]}" || fail "parent $validation_error"
    require_file_properties "$parent_path" "${required_keys[@]}" || fail "parent $validation_error"
    parent_phase="$(property_from "$parent_path" evidence_phase)"
    [[ "$parent_phase" == "$expected_parent" ]] || fail "parent provenance phase mismatch"
    [[ "$(property_from "$parent_path" format_version)" == "4" ]] || fail "parent provenance format mismatch"
    [[ "$(property_from "$parent_path" lineage_id)" == "$(property lineage_id)" ]] || \
        fail "parent lineage mismatch"
    [[ "$(property_from "$parent_path" output_sha256)" == "$(property parent_artifact_sha256)" ]] || \
        fail "parent artifact hash mismatch"
    [[ "$(property_from "$parent_path" canonical_payload_sha256)" == "$(property parent_payload_sha256)" ]] || \
        fail "parent payload hash mismatch"
    [[ "$(property_from "$parent_path" apply_receipt_sha256)" == "$(property apply_receipt_sha256)" &&
        "$(property_from "$parent_path" apply_receipt_path)" == "$(property apply_receipt_path)" ]] || \
        fail "parent apply receipt changed"
    [[ "$(property_from "$parent_path" unsigned_output_sha256)" == "$(property unsigned_output_sha256)" ]] || \
        fail "parent unsigned output lineage changed"
    [[ "$(property_from "$parent_path" dependency_lane)" == "$(property dependency_lane)" ]] || \
        fail "parent dependency lane changed"
    [[ "$(property parent_payload_sha256)" == "$(property canonical_payload_sha256)" ]] || \
        fail "canonical payload changed across phases"
    case "$phase" in
        pre_sign)
            [[ "$(property parent_artifact_sha256)" == "$(property output_sha256)" ]] || \
                fail "pre-sign output does not match unsigned parent"
            ;;
        post_sign)
            [[ "$(property parent_artifact_sha256)" == "$(property unsigned_output_sha256)" ]] || \
                fail "post-sign parent is not the applied unsigned artifact"
            ;;
        final)
            [[ "$(property parent_artifact_sha256)" == "$(property output_sha256)" ]] || \
                fail "final signed output differs from post-sign output"
            ;;
    esac
}

validate_smoke_receipt() {
    local path
    if [[ "$requested_phase" != "final" ]]; then
        [[ "$(property smoke_receipt_path)" == "none" && "$(property smoke_receipt_sha256)" == "none" ]] || \
            fail "non-final phase unexpectedly has smoke evidence"
        return
    fi
    path="$(property smoke_receipt_path)"
    [[ -f "$path" && ! -L "$path" ]] || fail "smoke receipt is not a regular file"
    hash_matches "$path" "$(property smoke_receipt_sha256)" || fail "smoke_receipt_sha256 mismatch"
    validate_property_file "$path" "${smoke_receipt_keys[@]}" || fail "$validation_error"
    require_file_properties "$path" "${smoke_receipt_keys[@]}" || fail "$validation_error"
    [[ "$(property_from "$path" format_version)" == "1" &&
        "$(property_from "$path" lineage_id)" == "$(property lineage_id)" &&
        "$(property_from "$path" signed_output_sha256)" == "$(property output_sha256)" &&
        "$(property_from "$path" performance_receipt_sha256)" == \
            "$(property performance_receipt_sha256)" &&
        "$(property_from "$path" device_smoke)" == "passed" ]] || fail "smoke receipt binding mismatch"
}

validate_performance_binding() {
    local path smoke_path smoke_performance_sha
    if [[ "$requested_phase" != "final" ]]; then
        [[ "$(property performance_receipt_path)" == "none" &&
            "$(property performance_receipt_sha256)" == "none" ]] ||
            fail "non-final phase unexpectedly has performance evidence"
        return
    fi
    path="$(property performance_receipt_path)"
    [[ -f "$path" && ! -L "$path" ]] || fail "performance receipt is not a regular file"
    hash_matches "$path" "$(property performance_receipt_sha256)" ||
        fail "performance_receipt_sha256 mismatch"
    smoke_path="$(property smoke_receipt_path)"
    smoke_performance_sha="$(property_from "$smoke_path" performance_receipt_sha256)"
    [[ "$smoke_performance_sha" == "$(property performance_receipt_sha256)" ]] ||
        fail "smoke receipt performance binding mismatch"
}

validate_performance_receipt() {
    validate_performance_binding
    [[ "$requested_phase" == "final" ]] || return
    "$script_dir/verify-read-receipt-v2-performance-receipt.sh" \
        "$(property performance_receipt_path)" \
        "$(property lineage_id)" \
        "$(property output_sha256)" >/dev/null ||
        fail "performance receipt verification failed"
}

self_test_phase_file() {
    local path="$1"
    local phase="$2"
    local artifact="$3"
    local readiness="$4"
    local signature="$5"
    local signer="$6"
    local build_gate="$7"
    local signed_gate="$8"
    local device_gate="$9"
    local iris_gate="${10}"
    cat >"$path" <<EOF
evidence_phase=$phase
artifact_status=$artifact
release_readiness=$readiness
output_signature_status=$signature
output_signer_sha256=$signer
patches_build_android=$build_gate
signed_final_verification=$signed_gate
android_device_smoke=$device_gate
iris_integration=$iris_gate
EOF
}

self_test_provenance_file() {
    local path="$1"
    local phase="$2"
    local lineage="$3"
    local output_sha="$4"
    local payload_sha="$5"
    local receipt_path="$6"
    local receipt_sha="$7"
    local unsigned_sha="$8"
    local parent_path="$9"
    local parent_sha="${10}"
    local parent_artifact="${11}"
    local parent_payload="${12}"
    local smoke_path="${13:-none}"
    local smoke_sha="${14:-none}"
    local dependency_lane="${15:-pinned_offline}"
    local performance_path="${16:-none}"
    local performance_sha="${17:-none}"
    local key value
    : >"$path"
    for key in "${required_keys[@]}"; do
        value="x"
        case "$key" in
            format_version) value="4" ;;
            evidence_phase) value="$phase" ;;
            lineage_id) value="$lineage" ;;
            dependency_lane) value="$dependency_lane" ;;
            output_sha256) value="$output_sha" ;;
            output_bytes) value="3" ;;
            canonical_payload_sha256) value="$payload_sha" ;;
            extension_mpe_sha256) value="$(printf '8%.0s' {1..64})" ;;
            revanced_head) value="$(printf '9%.0s' {1..40})" ;;
            revanced_tracked_diff_sha256) value="$(printf 'a%.0s' {1..64})" ;;
            revanced_untracked_file_manifest_sha256) value="$(printf 'b%.0s' {1..64})" ;;
            revanced_status_paths_sha256) value="$(printf 'c%.0s' {1..64})" ;;
            iris_task1_head) value="$(printf 'd%.0s' {1..40})" ;;
            iris_tracked_diff_sha256) value="$(printf 'e%.0s' {1..64})" ;;
            iris_untracked_file_manifest_sha256) value="$(printf 'f%.0s' {1..64})" ;;
            iris_status_paths_sha256) value="$(printf '0%.0s' {1..64})" ;;
            apply_receipt_path) value="$receipt_path" ;;
            apply_receipt_sha256) value="$receipt_sha" ;;
            unsigned_output_sha256) value="$unsigned_sha" ;;
            parent_provenance_path) value="$parent_path" ;;
            parent_provenance_sha256) value="$parent_sha" ;;
            parent_artifact_sha256) value="$parent_artifact" ;;
            parent_payload_sha256) value="$parent_payload" ;;
            smoke_receipt_path) value="$smoke_path" ;;
            smoke_receipt_sha256) value="$smoke_sha" ;;
            performance_receipt_path) value="$performance_path" ;;
            performance_receipt_sha256) value="$performance_sha" ;;
        esac
        printf '%s=%s\n' "$key" "$value" >>"$path"
    done
}

run_self_test() {
    local case_file artifact artifact_identity expected signer badging_report
    local unsigned_parent pre_sign post_sign final stale switched_parent lineage unsigned_sha payload_sha payload_apk
    local smoke_receipt stale_smoke performance_receipt stale_performance
    local apply_receipt switched_receipt receipt_provenance
    local missing_lane_receipt invalid_lane_receipt lane_tampered_provenance lane_tampered_parent
    tmp_dir="$(mktemp -d)"
    trap 'rm -rf -- "$tmp_dir"' EXIT
    case_file="$tmp_dir/phase.properties"
    self_test_phase_file "$case_file" unsigned unsigned_evidence pending_prerequisites \
        unsigned_does_not_verify none pending pending pending pending
    provenance="$case_file"
    validate_phase unsigned || fail "self-test rejected valid unsigned evidence"

    self_test_phase_file "$case_file" pre_sign pre_sign_ready pending_signature_and_device_smoke \
        unsigned_does_not_verify none passed pending pending passed
    validate_phase pre_sign || fail "self-test rejected valid pre-sign evidence"

    self_test_phase_file "$case_file" pre_sign pre_sign_ready pending_signature_and_device_smoke \
        unsigned_does_not_verify none pending pending pending passed
    if validate_phase pre_sign; then
        fail "self-test accepted pre-sign without patches build"
    fi

    self_test_phase_file "$case_file" post_sign signed_candidate pending_device_smoke \
        verified "$(printf 'a%.0s' {1..64})" passed passed pending passed
    validate_phase post_sign || fail "self-test rejected valid post-sign evidence"
    validate_signer_pin post_sign "$(printf 'a%.0s' {1..64})" || \
        fail "self-test rejected the exact external signer pin"
    if validate_signer_pin post_sign "$(printf 'e%.0s' {1..64})"; then
        fail "self-test accepted a mismatched external signer pin"
    fi

    self_test_phase_file "$case_file" post_sign signed_candidate pending_device_smoke \
        verified "$(printf 'a%.0s' {1..64})" passed pending pending passed
    if validate_phase post_sign; then
        fail "self-test accepted post-sign without signed verification"
    fi

    self_test_phase_file "$case_file" final release_ready release_ready \
        verified "$(printf 'b%.0s' {1..64})" passed passed passed passed
    validate_phase final || fail "self-test rejected valid final evidence"

    self_test_phase_file "$case_file" final release_ready release_ready \
        verified "$(printf 'b%.0s' {1..64})" passed passed pending passed
    if validate_phase final; then
        fail "self-test accepted final without device smoke"
    fi

    self_test_phase_file "$case_file" unsigned unsigned_evidence pending_prerequisites \
        unsigned_does_not_verify none not_run pending pending pending
    if validate_phase unsigned; then
        fail "self-test accepted not_run"
    fi

    self_test_phase_file "$case_file" unsigned unsigned_evidence pending_prerequisites \
        unsigned_does_not_verify none pending pending pending pending
    printf 'evidence_phase=unsigned\n' >>"$case_file"
    if validate_property_file "$case_file"; then
        fail "self-test accepted a duplicate field"
    fi

    artifact="$tmp_dir/artifact.bin"
    printf 'verified artifact\n' >"$artifact"
    expected="$(sha256_file "$artifact")"
    artifact_identity="$(stable_file_identity "$artifact")"
    hash_matches "$artifact" "$expected" || fail "self-test rejected an exact artifact hash"
    printf 'tamper\n' >>"$artifact"
    if hash_matches "$artifact" "$expected"; then
        fail "self-test accepted artifact tamper"
    fi
    [[ "$(stable_file_identity "$artifact")" != "$artifact_identity" ]] || \
        fail "self-test accepted stable identity tamper"
    ln -s "$artifact" "$tmp_dir/artifact-link.bin"
    if stable_file_identity "$tmp_dir/artifact-link.bin" >/dev/null; then
        fail "self-test accepted a symlink artifact"
    fi

    signer="$(printf 'c%.0s' {1..64})"
    printf 'Signer #1 certificate SHA-256 digest: %s\n' "$signer" >"$tmp_dir/signer.txt"
    signer_report_matches "$tmp_dir/signer.txt" "$signer" || \
        fail "self-test rejected an exact signer"
    if signer_report_matches "$tmp_dir/signer.txt" "$(printf 'd%.0s' {1..64})"; then
        fail "self-test accepted signer tamper"
    fi
    printf 'Signer #2 certificate SHA-256 digest: %s\n' "$signer" >>"$tmp_dir/signer.txt"
    if signer_report_matches "$tmp_dir/signer.txt" "$signer"; then
        fail "self-test accepted multiple signers"
    fi

    badging_report="$tmp_dir/badging.txt"
    printf "package: name='com.kakao.talk' versionCode='29260600' versionName='26.6.0' compileSdkVersion='36'\n" \
        >"$badging_report"
    badging_report_matches "$badging_report" com.kakao.talk 26.6.0 29260600 || \
        fail "self-test rejected exact APK metadata"
    if badging_report_matches "$badging_report" com.kakao.talk.changed 26.6.0 29260600; then
        fail "self-test accepted package metadata tamper"
    fi
    if badging_report_matches "$badging_report" com.kakao.talk 26.6.1 29260600; then
        fail "self-test accepted version name metadata tamper"
    fi
    if badging_report_matches "$badging_report" com.kakao.talk 26.6.0 29260601; then
        fail "self-test accepted version code metadata tamper"
    fi
    cat "$badging_report" >>"$badging_report.duplicate"
    cat "$badging_report" >>"$badging_report.duplicate"
    if badging_report_matches "$badging_report.duplicate" com.kakao.talk 26.6.0 29260600; then
        fail "self-test accepted duplicate APK metadata"
    fi

    command -v jar >/dev/null 2>&1 || fail "jar is unavailable for canonical payload self-test"
    mkdir -p "$tmp_dir/payload"
    printf 'payload-a' >"$tmp_dir/payload/classes.dex"
    payload_apk="$tmp_dir/payload.apk"
    jar --create --file "$payload_apk" -C "$tmp_dir/payload" classes.dex
    [[ "$(canonical_payload_sha256 "$payload_apk")" == \
        "4728ade9214feb8c2faa379adec81310623c2fe6f3cdcd48731b765a6bec418b" ]] || \
        fail "self-test rejected canonical APK payload digest"
    printf 'payload-b' >"$tmp_dir/payload/classes.dex"
    jar --create --file "$tmp_dir/payload-tampered.apk" -C "$tmp_dir/payload" classes.dex
    [[ "$(canonical_payload_sha256 "$tmp_dir/payload-tampered.apk")" != \
        "4728ade9214feb8c2faa379adec81310623c2fe6f3cdcd48731b765a6bec418b" ]] || \
        fail "self-test accepted canonical APK payload tamper"

    lineage="$(printf '1%.0s' {1..64})"
    unsigned_sha="$(printf '2%.0s' {1..64})"
    payload_sha="$(printf '3%.0s' {1..64})"
    stale="$tmp_dir/stale.properties"
    apply_receipt="$tmp_dir/apply-receipt.properties"
    receipt_provenance="$tmp_dir/receipt-provenance.properties"
    self_test_provenance_file "$receipt_provenance" unsigned "$lineage" "$unsigned_sha" "$payload_sha" \
        "$apply_receipt" "$(printf '4%.0s' {1..64})" "$unsigned_sha" none none none none
    provenance="$receipt_provenance"
    cat >"$apply_receipt" <<EOF
format_version=1
lineage_id=$lineage
artifact_kind=unsigned
dependency_lane=pinned_offline
input_sha256=$PINNED_INPUT_SHA256
extension_mpe_sha256=$(property extension_mpe_sha256)
revanced_head=$(property revanced_head)
revanced_tree_sha256=$(tree_evidence_sha256 revanced)
iris_head=$(property iris_task1_head)
iris_tree_sha256=$(tree_evidence_sha256 iris)
output_sha256=$unsigned_sha
output_payload_sha256=$payload_sha
output_bytes=3
EOF
    chmod 400 "$apply_receipt"
    self_test_provenance_file "$receipt_provenance" unsigned "$lineage" "$unsigned_sha" "$payload_sha" \
        "$apply_receipt" "$(sha256_file "$apply_receipt")" "$unsigned_sha" none none none none
    provenance="$receipt_provenance"
    requested_phase=unsigned
    validate_apply_receipt

    missing_lane_receipt="$tmp_dir/missing-lane-apply-receipt.properties"
    sed '/^dependency_lane=/d' "$apply_receipt" >"$missing_lane_receipt"
    chmod 400 "$missing_lane_receipt"
    self_test_provenance_file "$stale" unsigned "$lineage" "$unsigned_sha" "$payload_sha" \
        "$missing_lane_receipt" "$(sha256_file "$missing_lane_receipt")" "$unsigned_sha" none none none none
    if ( provenance="$stale"; validate_apply_receipt ) 2>/dev/null; then
        fail "self-test accepted apply receipt without dependency lane"
    fi

    invalid_lane_receipt="$tmp_dir/invalid-lane-apply-receipt.properties"
    sed 's/^dependency_lane=pinned_offline$/dependency_lane=unverified/' \
        "$apply_receipt" >"$invalid_lane_receipt"
    chmod 400 "$invalid_lane_receipt"
    self_test_provenance_file "$stale" unsigned "$lineage" "$unsigned_sha" "$payload_sha" \
        "$invalid_lane_receipt" "$(sha256_file "$invalid_lane_receipt")" "$unsigned_sha" none none none none \
        none none unverified
    if ( provenance="$stale"; validate_apply_receipt ) 2>/dev/null; then
        fail "self-test accepted unsupported dependency lane"
    fi

    lane_tampered_provenance="$tmp_dir/lane-tampered-provenance.properties"
    self_test_provenance_file "$lane_tampered_provenance" unsigned "$lineage" "$unsigned_sha" "$payload_sha" \
        "$apply_receipt" "$(sha256_file "$apply_receipt")" "$unsigned_sha" none none none none \
        none none release
    if ( provenance="$lane_tampered_provenance"; validate_apply_receipt ) 2>/dev/null; then
        fail "self-test accepted apply receipt dependency lane tamper"
    fi

    self_test_provenance_file "$stale" unsigned "$lineage" "$(printf '5%.0s' {1..64})" "$payload_sha" \
        "$apply_receipt" "$(sha256_file "$apply_receipt")" "$(printf '5%.0s' {1..64})" none none none none
    if ( provenance="$stale"; validate_apply_receipt ) 2>/dev/null; then
        fail "self-test accepted switched apply artifact"
    fi

    switched_receipt="$tmp_dir/switched-apply-receipt.properties"
    sed "s/lineage_id=$lineage/lineage_id=$(printf '7%.0s' {1..64})/" "$apply_receipt" >"$switched_receipt"
    chmod 400 "$switched_receipt"
    self_test_provenance_file "$stale" unsigned "$lineage" "$unsigned_sha" "$payload_sha" \
        "$switched_receipt" "$(sha256_file "$switched_receipt")" "$unsigned_sha" none none none none
    if ( provenance="$stale"; validate_apply_receipt ) 2>/dev/null; then
        fail "self-test accepted switched apply receipt"
    fi

    unsigned_parent="$tmp_dir/unsigned-parent.properties"
    self_test_provenance_file "$unsigned_parent" unsigned "$lineage" "$unsigned_sha" "$payload_sha" \
        /receipt "$(printf '4%.0s' {1..64})" "$unsigned_sha" none none none none
    pre_sign="$tmp_dir/pre-sign.properties"
    self_test_provenance_file "$pre_sign" pre_sign "$lineage" "$unsigned_sha" "$payload_sha" \
        /receipt "$(printf '4%.0s' {1..64})" "$unsigned_sha" "$unsigned_parent" \
        "$(sha256_file "$unsigned_parent")" "$unsigned_sha" "$payload_sha"
    provenance="$pre_sign"
    validate_parent_chain pre_sign

    lane_tampered_parent="$tmp_dir/lane-tampered-parent.properties"
    self_test_provenance_file "$lane_tampered_parent" pre_sign "$lineage" "$unsigned_sha" "$payload_sha" \
        /receipt "$(printf '4%.0s' {1..64})" "$unsigned_sha" "$unsigned_parent" \
        "$(sha256_file "$unsigned_parent")" "$unsigned_sha" "$payload_sha" none none release
    if ( provenance="$lane_tampered_parent"; validate_parent_chain pre_sign ) 2>/dev/null; then
        fail "self-test accepted parent dependency lane tamper"
    fi

    self_test_provenance_file "$stale" pre_sign "$lineage" "$(printf '5%.0s' {1..64})" "$payload_sha" \
        /receipt "$(printf '4%.0s' {1..64})" "$unsigned_sha" "$unsigned_parent" \
        "$(sha256_file "$unsigned_parent")" "$unsigned_sha" "$payload_sha"
    if ( provenance="$stale"; validate_parent_chain pre_sign ) 2>/dev/null; then
        fail "self-test accepted stale pre-sign artifact substitution"
    fi

    switched_parent="$tmp_dir/switched-parent.properties"
    cp -- "$unsigned_parent" "$switched_parent"
    self_test_provenance_file "$stale" pre_sign "$lineage" "$unsigned_sha" "$payload_sha" \
        /receipt "$(printf '4%.0s' {1..64})" "$unsigned_sha" "$switched_parent" \
        "$(sha256_file "$switched_parent")" "$unsigned_sha" "$payload_sha"
    printf 'tamper\n' >>"$switched_parent"
    if ( provenance="$stale"; validate_parent_chain pre_sign ) 2>/dev/null; then
        fail "self-test accepted switched parent provenance"
    fi

    post_sign="$tmp_dir/post-sign.properties"
    self_test_provenance_file "$post_sign" post_sign "$lineage" "$(printf '6%.0s' {1..64})" "$payload_sha" \
        /receipt "$(printf '4%.0s' {1..64})" "$unsigned_sha" "$pre_sign" \
        "$(sha256_file "$pre_sign")" "$unsigned_sha" "$payload_sha"
    provenance="$post_sign"
    validate_parent_chain post_sign

    final="$tmp_dir/final.properties"
    smoke_receipt="$tmp_dir/device-smoke.properties"
    performance_receipt="$tmp_dir/performance-receipt.properties"
    printf 'bound performance receipt\n' >"$performance_receipt"
    performance_sha="$(sha256_file "$performance_receipt")"
    cat >"$smoke_receipt" <<EOF
format_version=1
lineage_id=$lineage
signed_output_sha256=$(printf '6%.0s' {1..64})
performance_receipt_sha256=$performance_sha
device_smoke=passed
completed_at=2026-07-19T00:00:00+09:00
EOF
    self_test_provenance_file "$final" final "$lineage" "$(printf '6%.0s' {1..64})" "$payload_sha" \
        /receipt "$(printf '4%.0s' {1..64})" "$unsigned_sha" "$post_sign" \
        "$(sha256_file "$post_sign")" "$(printf '6%.0s' {1..64})" "$payload_sha" \
        "$smoke_receipt" "$(sha256_file "$smoke_receipt")" pinned_offline \
        "$performance_receipt" "$performance_sha"
    provenance="$final"
    validate_parent_chain final
    requested_phase=final
    validate_smoke_receipt
    validate_performance_binding

    stale_smoke="$tmp_dir/stale-smoke.properties"
    sed "s/signed_output_sha256=$(printf '6%.0s' {1..64})/signed_output_sha256=$(printf '7%.0s' {1..64})/" \
        "$smoke_receipt" >"$stale_smoke"
    self_test_provenance_file "$stale" final "$lineage" "$(printf '6%.0s' {1..64})" "$payload_sha" \
        /receipt "$(printf '4%.0s' {1..64})" "$unsigned_sha" "$post_sign" \
        "$(sha256_file "$post_sign")" "$(printf '6%.0s' {1..64})" "$payload_sha" \
        "$stale_smoke" "$(sha256_file "$stale_smoke")" pinned_offline \
        "$performance_receipt" "$performance_sha"
    if ( provenance="$stale"; validate_smoke_receipt ) 2>/dev/null; then
        fail "self-test accepted smoke receipt for a different signed artifact"
    fi

    stale_performance="$tmp_dir/stale-performance.properties"
    cp -- "$performance_receipt" "$stale_performance"
    self_test_provenance_file "$stale" final "$lineage" "$(printf '6%.0s' {1..64})" "$payload_sha" \
        /receipt "$(printf '4%.0s' {1..64})" "$unsigned_sha" "$post_sign" \
        "$(sha256_file "$post_sign")" "$(printf '6%.0s' {1..64})" "$payload_sha" \
        "$smoke_receipt" "$(sha256_file "$smoke_receipt")" pinned_offline \
        "$stale_performance" "$performance_sha"
    printf 'tamper\n' >>"$stale_performance"
    if ( provenance="$stale"; validate_performance_binding ) 2>/dev/null; then
        fail "self-test accepted performance receipt tamper"
    fi

    echo "RRV2-PROVENANCE-SELF-TEST passed: phases=4 phasePrerequisiteReject=3 notRunReject=1 duplicateReject=1 artifactTamperReject=1 identityTamperReject=1 symlinkReject=1 signerTamperReject=1 signerMultiplicityReject=1 metadataTamperReject=3 metadataDuplicateReject=1 payloadPositive=1 payloadTamperReject=1 applyReceiptPositive=1 applyArtifactSwitchReject=1 applyReceiptSwitchReject=1 dependencyLaneMissingReject=1 dependencyLaneInvalidReject=1 dependencyLaneTamperReject=1 parentDependencyLaneTamperReject=1 lineagePositive=3 staleReject=1 parentSwitchReject=1 smokePositive=1 smokeSwitchReject=1 performancePositive=1 performanceTamperReject=1"
}

if (( $# == 1 )) && [[ "$1" == "--self-test" ]]; then
    run_self_test
    exit 0
fi

if (( $# < 3 || $# > 4 )); then
    echo "usage: verify-read-receipt-v2-provenance.sh <provenance.properties> <iris-worktree> <unsigned|pre_sign|post_sign|final> [expected-signer-sha256]" >&2
    exit 2
fi

provenance="$1"
iris_root="$2"
requested_phase="$3"
expected_signer="${4:-}"
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
patches_dir="$(cd -- "$script_dir/../../.." && pwd)"
revanced_root="$(cd -- "$patches_dir/.." && pwd)"
tmp_dir="$(mktemp -d)"
trap 'rm -rf -- "$tmp_dir"' EXIT

[[ -f "$provenance" && ! -L "$provenance" ]] || fail "properties file is missing or is a symlink"
[[ -d "$iris_root/.git" || -f "$iris_root/.git" ]] || fail "Iris worktree is not a Git checkout"
validate_property_file "$provenance" || fail "$validation_error"
require_properties || fail "$validation_error"
validate_phase "$requested_phase" || fail "$validation_error"
validate_signer_pin "$requested_phase" "$expected_signer" || fail "$validation_error"
[[ "$(property format_version)" == "4" ]] || fail "unsupported format_version"
[[ "$(property lineage_id)" =~ ^[0-9a-f]{64}$ ]] || fail "lineage_id is not canonical"
[[ "$(property dependency_lane)" == "release" || "$(property dependency_lane)" == "pinned_offline" ]] || \
    fail "dependency_lane is unsupported"
[[ "$(property unsigned_output_sha256)" =~ ^[0-9a-f]{64}$ ]] || \
    fail "unsigned_output_sha256 is not canonical"
[[ "$(property input_sha256)" == "$PINNED_INPUT_SHA256" ]] || fail "input_sha256 is not the pinned exact input"
[[ "$(property capture_hooks)" == "1" ]] || fail "capture hook count is not exact"
[[ "$(property accessors)" == "0" ]] || fail "accessor count is not exact"
[[ "$(property native_abis)" == "2" ]] || fail "native ABI count is not exact"
[[ "$(property existing_show_message_read_receipts_patch_reapplied)" == "false" ]] || \
    fail "existing ShowMessageReadReceipts patch was reapplied"
[[ "$(property staged_hook_boundary)" == "immediate_after_successful_watermark_update" ]] || \
    fail "staged hook boundary is not exact"
[[ "$(property cap011_dex_gate)" == "passed" ]] || fail "CAP-011 DEX gate is not recorded as passed"
[[ "$(property transfer_256_host_gate)" == "passed" ]] || fail "256-event host gate is not recorded as passed"
[[ "$(property source_epoch_boundary_gate)" == "passed" ]] || fail "source epoch gate is not recorded as passed"
[[ "$(property durable_property_gate)" == "passed" ]] || fail "durable property gate is not recorded as passed"
[[ "$(property wal_cap_fail_closed_gate)" == "passed" ]] || \
    fail "WAL fail-closed gate is not recorded as passed"
[[ "$(property unsigned_wrong_signer_rejection_gate)" == "passed" ]] || \
    fail "wrong-signer rejection gate is not recorded as passed"
[[ "$(property boot_id_security_review)" == "passed_no_p0_p1_p2" ]] || \
    fail "boot ID security review result is not exact"
[[ "$(property final_delta_review)" == "passed_no_p0_p1_p2_after_p1_fixes" ]] || \
    fail "final delta review result is not exact"

assert_file_hash() {
    local path_key="$1"
    local hash_key="$2"
    local path
    path="$(property "$path_key")"
    [[ -f "$path" && ! -L "$path" ]] || fail "$path_key is not a regular non-symlink file"
    hash_matches "$path" "$(property "$hash_key")" || fail "$hash_key mismatch"
}

assert_file_hash input_path input_sha256
assert_file_hash output_path output_sha256
assert_file_hash extension_mpe_path extension_mpe_sha256
[[ "$(stat -c '%s' -- "$(property output_path)")" == "$(property output_bytes)" ]] || \
    fail "output_bytes mismatch"

command -v aapt2 >/dev/null 2>&1 || fail "aapt2 is unavailable"
command -v apksigner >/dev/null 2>&1 || fail "apksigner is unavailable"
command -v zipinfo >/dev/null 2>&1 || fail "zipinfo is unavailable"
command -v unzip >/dev/null 2>&1 || fail "unzip is unavailable"
input_identity_before="$(stable_file_identity "$(property input_path)")" || fail "input identity is unstable"
output_identity_before="$(stable_file_identity "$(property output_path)")" || fail "output identity is unstable"
extension_identity_before="$(stable_file_identity "$(property extension_mpe_path)")" || \
    fail "extension identity is unstable"
receipt_identity_before="$(stable_file_identity "$(property apply_receipt_path)")" || \
    fail "apply receipt identity is unstable"
provenance_identity_before="$(stable_file_identity "$provenance")" || fail "provenance identity is unstable"

actual_payload_sha256="$(canonical_payload_sha256 "$(property output_path)")" || \
    fail "canonical APK payload hashing failed"
[[ "$actual_payload_sha256" == "$(property canonical_payload_sha256)" ]] || \
    fail "canonical_payload_sha256 mismatch"
validate_apply_receipt
validate_parent_chain "$requested_phase"
validate_smoke_receipt
validate_performance_receipt
parent_identity_before="none"
if [[ "$requested_phase" != "unsigned" ]]; then
    parent_identity_before="$(stable_file_identity "$(property parent_provenance_path)")" || \
        fail "parent provenance identity is unstable"
fi
smoke_identity_before="none"
performance_identity_before="none"
if [[ "$requested_phase" == "final" ]]; then
    smoke_identity_before="$(stable_file_identity "$(property smoke_receipt_path)")" || \
        fail "smoke receipt identity is unstable"
    performance_identity_before="$(stable_file_identity "$(property performance_receipt_path)")" || \
        fail "performance receipt identity is unstable"
fi
verify_apk_metadata "$(property input_path)" input
verify_apk_metadata "$(property output_path)" output

input_signer="$(property input_signer_sha256)"
[[ "$input_signer" =~ ^[0-9a-f]{64}$ ]] || fail "input_signer_sha256 is not canonical"
input_signature_report="$tmp_dir/input-signature.txt"
apksigner verify --verbose --print-certs "$(property input_path)" >"$input_signature_report" 2>&1 || \
    fail "input APK signature verification failed"
signer_report_matches "$input_signature_report" "$input_signer" || fail "input APK signer mismatch"

signature_report="$tmp_dir/output-signature.txt"
if [[ "$requested_phase" == "unsigned" || "$requested_phase" == "pre_sign" ]]; then
    if apksigner verify --verbose "$(property output_path)" >"$signature_report" 2>&1; then
        fail "unsigned output APK has a verified signer"
    else
        signature_status=$?
    fi
    [[ "$signature_status" == "1" ]] || fail "apksigner verification did not complete normally"
    grep -Fxq "DOES NOT VERIFY" "$signature_report" || fail "unsigned APK signature result is not conclusive"
else
    apksigner verify --verbose --print-certs "$(property output_path)" >"$signature_report" 2>&1 || \
        fail "signed APK verification failed"
    signer_report_matches "$signature_report" "$expected_signer" || \
        fail "signed APK signer mismatch"
fi

git_digest() {
    local root="$1"
    local kind="$2"
    local manifest="$tmp_dir/${kind//[^a-z0-9]/_}.manifest"
    case "$kind" in
        tracked)
            git -C "$root" diff HEAD --binary --no-ext-diff -- . | sha256sum | awk '{ print $1 }'
            ;;
        status)
            git -C "$root" status --porcelain=v1 -z --untracked-files=all | sha256sum | awk '{ print $1 }'
            ;;
        untracked)
            : >"$manifest"
            while IFS= read -r -d '' path; do
                printf '%s  %s\n' "$(sha256_file "$root/$path")" "$path" >>"$manifest"
            done < <(git -C "$root" ls-files --others --exclude-standard -z | LC_ALL=C sort -z)
            sha256_file "$manifest"
            ;;
        *)
            fail "unknown Git digest kind"
            ;;
    esac
}

assert_git_state() {
    local root="$1"
    local head_key="$2"
    local upstream_ref="$3"
    local upstream_key="$4"
    local prefix="$5"
    [[ "$(git -C "$root" rev-parse HEAD)" == "$(property "$head_key")" ]] || fail "$head_key mismatch"
    [[ "$(git -C "$root" rev-parse "$upstream_ref")" == "$(property "$upstream_key")" ]] || \
        fail "$upstream_key mismatch"
    [[ "$(git_digest "$root" tracked)" == "$(property "${prefix}_tracked_diff_sha256")" ]] || \
        fail "${prefix}_tracked_diff_sha256 mismatch"
    [[ "$(git_digest "$root" untracked)" == "$(property "${prefix}_untracked_file_manifest_sha256")" ]] || \
        fail "${prefix}_untracked_file_manifest_sha256 mismatch"
    [[ "$(git_digest "$root" status)" == "$(property "${prefix}_status_paths_sha256")" ]] || \
        fail "${prefix}_status_paths_sha256 mismatch"
}

[[ "$(property iris_worktree_path)" == "$(cd -- "$iris_root" && pwd)" ]] || fail "iris_worktree_path mismatch"
assert_git_state "$revanced_root" revanced_head refs/remotes/origin/dev revanced_origin_dev revanced
assert_git_state "$iris_root" iris_task1_head refs/remotes/origin/main iris_origin_main iris

[[ "$(stable_file_identity "$(property input_path)")" == "$input_identity_before" ]] || \
    fail "input artifact changed during provenance verification"
[[ "$(stable_file_identity "$(property output_path)")" == "$output_identity_before" ]] || \
    fail "output artifact changed during provenance verification"
[[ "$(stable_file_identity "$(property extension_mpe_path)")" == "$extension_identity_before" ]] || \
    fail "extension artifact changed during provenance verification"
[[ "$(stable_file_identity "$(property apply_receipt_path)")" == "$receipt_identity_before" ]] || \
    fail "apply receipt changed during provenance verification"
[[ "$(stable_file_identity "$provenance")" == "$provenance_identity_before" ]] || \
    fail "provenance file changed during verification"
if [[ "$requested_phase" != "unsigned" ]]; then
    [[ "$(stable_file_identity "$(property parent_provenance_path)")" == "$parent_identity_before" ]] || \
        fail "parent provenance changed during verification"
fi
if [[ "$requested_phase" == "final" ]]; then
    [[ "$(stable_file_identity "$(property smoke_receipt_path)")" == "$smoke_identity_before" ]] || \
        fail "smoke receipt changed during verification"
    [[ "$(stable_file_identity "$(property performance_receipt_path)")" == \
        "$performance_identity_before" ]] || fail "performance receipt changed during verification"
fi

release_ready=0
[[ "$requested_phase" == "final" ]] && release_ready=1
echo "RRV2-PROVENANCE-002 provenance passed: phase=$requested_phase releaseReady=$release_ready artifacts=3 gitTrees=2 duplicateKeys=0"
