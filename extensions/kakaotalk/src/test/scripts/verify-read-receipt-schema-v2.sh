#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
schema="$script_dir/../resources/read-receipt-v2/read_receipt_schema_v2.sql"
sqlite3_bin=${SQLITE3_BIN:-/opt/android-sdk/platform-tools/sqlite3}
database=$(mktemp "${TMPDIR:-/tmp}/read-receipt-schema-v2.XXXXXX.db")
trap 'unlink "$database"' EXIT

if [[ ! -x "$sqlite3_bin" ]]; then
    echo "sqlite3 unavailable: $sqlite3_bin" >&2
    exit 1
fi

awk '{print $0 ";"}' "$schema" | "$sqlite3_bin" "$database" >/dev/null

sql() {
    "$sqlite3_bin" "$database" "PRAGMA foreign_keys=ON; CREATE TEMP TABLE foreign_keys_guard (enabled INTEGER CHECK (enabled = 1)); INSERT INTO foreign_keys_guard SELECT foreign_keys FROM pragma_foreign_keys; $1"
}

object_names=(
    read_receipt_stream_state
    read_receipt_batches
    read_receipt_outbox
    read_receipt_loss_segments
    read_receipt_transfer_lease
    read_receipt_diagnostic_counters
    idx_read_receipt_batches_delivery
    idx_read_receipt_outbox_batch
    idx_read_receipt_loss_segments_batch
)
mapfile -t fixture_ddl < <(sed -n '8,16p' "$schema")
for index in "${!object_names[@]}"; do
    persisted=$(sql "SELECT sql FROM sqlite_schema WHERE name = '${object_names[$index]}'")
    if [[ "$persisted" != "${fixture_ddl[$index]}" ]]; then
        echo "persisted SQL mismatch: ${object_names[$index]}" >&2
        exit 1
    fi
done

expected_autoindexes=$'sqlite_autoindex_read_receipt_batches_1\nsqlite_autoindex_read_receipt_loss_segments_1\nsqlite_autoindex_read_receipt_loss_segments_2\nsqlite_autoindex_read_receipt_outbox_1\nsqlite_autoindex_read_receipt_outbox_2\nsqlite_autoindex_read_receipt_stream_state_1\nsqlite_autoindex_read_receipt_transfer_lease_1\nsqlite_autoindex_read_receipt_transfer_lease_2'
actual_autoindexes=$(sql "SELECT name FROM sqlite_schema WHERE type = 'index' AND sql IS NULL ORDER BY CAST(name AS BLOB)")
if [[ "$actual_autoindexes" != "$expected_autoindexes" ]]; then
    echo "autoindex catalog mismatch" >&2
    exit 1
fi

expected_catalog=$'index|idx_read_receipt_batches_delivery|read_receipt_batches|0\nindex|idx_read_receipt_loss_segments_batch|read_receipt_loss_segments|0\nindex|idx_read_receipt_outbox_batch|read_receipt_outbox|0\nindex|sqlite_autoindex_read_receipt_batches_1|read_receipt_batches|1\nindex|sqlite_autoindex_read_receipt_loss_segments_1|read_receipt_loss_segments|1\nindex|sqlite_autoindex_read_receipt_loss_segments_2|read_receipt_loss_segments|1\nindex|sqlite_autoindex_read_receipt_outbox_1|read_receipt_outbox|1\nindex|sqlite_autoindex_read_receipt_outbox_2|read_receipt_outbox|1\nindex|sqlite_autoindex_read_receipt_stream_state_1|read_receipt_stream_state|1\nindex|sqlite_autoindex_read_receipt_transfer_lease_1|read_receipt_transfer_lease|1\nindex|sqlite_autoindex_read_receipt_transfer_lease_2|read_receipt_transfer_lease|1\ntable|read_receipt_batches|read_receipt_batches|0\ntable|read_receipt_diagnostic_counters|read_receipt_diagnostic_counters|0\ntable|read_receipt_loss_segments|read_receipt_loss_segments|0\ntable|read_receipt_outbox|read_receipt_outbox|0\ntable|read_receipt_stream_state|read_receipt_stream_state|0\ntable|read_receipt_transfer_lease|read_receipt_transfer_lease|0'
actual_catalog=$(sql "SELECT type || '|' || name || '|' || tbl_name || '|' || (sql IS NULL) FROM sqlite_schema ORDER BY CAST(type AS BLOB), CAST(name AS BLOB), CAST(tbl_name AS BLOB)")
if [[ "$actual_catalog" != "$expected_catalog" ]]; then
    echo "schema object catalog mismatch" >&2
    exit 1
fi

expect_rejected() {
    local name=$1
    local statement=$2
    if sql "$statement" >/dev/null 2>&1; then
        echo "expected rejection: $name" >&2
        exit 1
    fi
}

expect_rejected_check() {
    local name=$1
    local expression=$2
    local statement=$3
    local error
    if error=$(sql "$statement" 2>&1); then
        echo "expected CHECK rejection: $name" >&2
        exit 1
    fi
    if [[ "$error" != *"CHECK constraint failed: $expression"* ]]; then
        echo "unexpected rejection class: $name; expected CHECK: $expression" >&2
        exit 1
    fi
}

expect_accepted() {
    local name=$1
    local statement=$2
    if ! sql "$statement" >/dev/null; then
        echo "expected acceptance: $name" >&2
        exit 1
    fi
}

installation="X'00000000000000000000000000000001'"
epoch="X'00000000000000000000000000000002'"
batch="X'00000000000000000000000000000003'"
session="X'00000000000000000000000000000004'"
event="X'00000000000000000000000000000005'"
loss="X'00000000000000000000000000000006'"
lease="X'00000000000000000000000000000007'"
digest="X'0000000000000000000000000000000000000000000000000000000000000008'"
other_installation="X'00000000000000000000000000000011'"
other_epoch="X'00000000000000000000000000000012'"
other_batch="X'00000000000000000000000000000013'"
other_session="X'00000000000000000000000000000014'"
other_event="X'00000000000000000000000000000015'"
other_loss="X'00000000000000000000000000000016'"
other_lease="X'00000000000000000000000000000017'"
other_digest="X'0000000000000000000000000000000000000000000000000000000000000018'"

state_columns="singleton,installation_id,stream_epoch,next_sequence,last_acked_sequence,highest_issued_sequence,confirmed_dropped_event_count,confirmed_dropped_batch_count,uncertain_outcome_event_count,uncertain_outcome_batch_count,capture_drop_event_count,capture_drop_batch_count,stream_status,poison_category,created_at_ms,updated_at_ms"
expect_rejected state_sequence_fraction "INSERT INTO read_receipt_stream_state ($state_columns) VALUES (1,$installation,$epoch,1.5,0,0,0,0,0,0,0,0,'active',NULL,0,0)"
expect_rejected state_counter_fraction "INSERT INTO read_receipt_stream_state ($state_columns) VALUES (1,$installation,$epoch,1,0,0,0.5,0,0,0,0,0,'active',NULL,0,0)"
expect_rejected state_timestamp_fraction "INSERT INTO read_receipt_stream_state ($state_columns) VALUES (1,$installation,$epoch,1,0,0,0,0,0,0,0,0,'active',NULL,0.5,0)"
expect_rejected state_negative_sequence "INSERT INTO read_receipt_stream_state ($state_columns) VALUES (1,$installation,$epoch,1,-1,0,0,0,0,0,0,0,'active',NULL,0,0)"
expect_rejected state_negative_counter "INSERT INTO read_receipt_stream_state ($state_columns) VALUES (1,$installation,$epoch,1,0,0,-1,0,0,0,0,0,'active',NULL,0,0)"
expect_rejected state_negative_timestamp "INSERT INTO read_receipt_stream_state ($state_columns) VALUES (1,$installation,$epoch,1,0,0,0,0,0,0,0,0,'active',NULL,-1,0)"
expect_rejected state_invalid_status "INSERT INTO read_receipt_stream_state ($state_columns) VALUES (1,$installation,$epoch,1,0,0,0,0,0,0,0,0,'unknown',NULL,0,0)"
expect_rejected state_active_with_poison "INSERT INTO read_receipt_stream_state ($state_columns) VALUES (1,$installation,$epoch,1,0,0,0,0,0,0,0,0,'active','sequence_hole',0,0)"
expect_rejected state_poisoned_without_category "INSERT INTO read_receipt_stream_state ($state_columns) VALUES (1,$installation,$epoch,1,0,0,0,0,0,0,0,0,'poisoned',NULL,0,0)"
expect_rejected state_invalid_poison_category "INSERT INTO read_receipt_stream_state ($state_columns) VALUES (1,$installation,$epoch,1,0,0,0,0,0,0,0,0,'poisoned','unknown',0,0)"
expect_rejected state_acked_above_highest "INSERT INTO read_receipt_stream_state ($state_columns) VALUES (1,$installation,$epoch,2,1,0,0,0,0,0,0,0,'active',NULL,0,0)"
expect_rejected state_highest_not_below_next "INSERT INTO read_receipt_stream_state ($state_columns) VALUES (1,$installation,$epoch,1,0,1,0,0,0,0,0,0,'active',NULL,0,0)"
expect_rejected state_short_installation "INSERT INTO read_receipt_stream_state ($state_columns) VALUES (1,zeroblob(15),$epoch,1,0,0,0,0,0,0,0,0,'active',NULL,0,0)"
expect_rejected state_short_epoch "INSERT INTO read_receipt_stream_state ($state_columns) VALUES (1,$installation,zeroblob(15),1,0,0,0,0,0,0,0,0,'active',NULL,0,0)"
expect_accepted valid_state "INSERT INTO read_receipt_stream_state ($state_columns) VALUES (1,$installation,$epoch,2,0,1,0,0,0,0,0,0,'active',NULL,0,0)"
expect_rejected second_stream_singleton "INSERT INTO read_receipt_stream_state ($state_columns) VALUES (1,$other_installation,$other_epoch,1,0,0,0,0,0,0,0,0,'active',NULL,0,0)"

batch_columns="stream_epoch,batch_id,first_sequence,last_sequence,event_count,persisted_at_ms,persisted_elapsed_ms,capture_session_id,capture_boot_id,source_epoch_token,metadata_digest,created_at_ms"
expect_rejected batch_range_fraction "INSERT INTO read_receipt_batches ($batch_columns) VALUES ($epoch,$batch,1.5,1.5,1,0,0,$session,NULL,NULL,$digest,0)"
expect_rejected batch_count_fraction "INSERT INTO read_receipt_batches ($batch_columns) VALUES ($epoch,$batch,1,1.5,1.5,0,0,$session,NULL,NULL,$digest,0)"
expect_rejected batch_clock_fraction "INSERT INTO read_receipt_batches ($batch_columns) VALUES ($epoch,$batch,1,1,1,0.5,0,$session,NULL,NULL,$digest,0)"
expect_rejected batch_timestamp_fraction "INSERT INTO read_receipt_batches ($batch_columns) VALUES ($epoch,$batch,1,1,1,0,0,$session,NULL,NULL,$digest,0.5)"
expect_rejected batch_negative_sequence "INSERT INTO read_receipt_batches ($batch_columns) VALUES ($epoch,$batch,-1,-1,1,0,0,$session,NULL,NULL,$digest,0)"
expect_rejected batch_negative_clock "INSERT INTO read_receipt_batches ($batch_columns) VALUES ($epoch,$batch,1,1,1,-1,0,$session,NULL,NULL,$digest,0)"
expect_rejected batch_negative_timestamp "INSERT INTO read_receipt_batches ($batch_columns) VALUES ($epoch,$batch,1,1,1,0,0,$session,NULL,NULL,$digest,-1)"
expect_rejected batch_reversed_range "INSERT INTO read_receipt_batches ($batch_columns) VALUES ($epoch,$batch,2,1,1,0,0,$session,NULL,NULL,$digest,0)"
expect_rejected batch_count_mismatch "INSERT INTO read_receipt_batches ($batch_columns) VALUES ($epoch,$batch,1,1,2,0,0,$session,NULL,NULL,$digest,0)"
expect_rejected_check batch_short_epoch "typeof(stream_epoch) = 'blob' AND length(stream_epoch) = 16" "INSERT INTO read_receipt_batches ($batch_columns) VALUES (zeroblob(15),$batch,1,1,1,0,0,$session,NULL,NULL,$digest,0)"
expect_rejected batch_short_id "INSERT INTO read_receipt_batches ($batch_columns) VALUES ($epoch,zeroblob(15),1,1,1,0,0,$session,NULL,NULL,$digest,0)"
expect_rejected batch_short_session "INSERT INTO read_receipt_batches ($batch_columns) VALUES ($epoch,$batch,1,1,1,0,0,zeroblob(15),NULL,NULL,$digest,0)"
expect_rejected batch_short_boot "INSERT INTO read_receipt_batches ($batch_columns) VALUES ($epoch,$batch,1,1,1,0,0,$session,zeroblob(15),NULL,$digest,0)"
expect_rejected batch_short_source "INSERT INTO read_receipt_batches ($batch_columns) VALUES ($epoch,$batch,1,1,1,0,0,$session,NULL,zeroblob(15),$digest,0)"
expect_rejected batch_short_digest "INSERT INTO read_receipt_batches ($batch_columns) VALUES ($epoch,$batch,1,1,1,0,0,$session,NULL,NULL,zeroblob(31),0)"
expect_rejected orphan_batch "INSERT INTO read_receipt_batches ($batch_columns) VALUES ($other_epoch,$other_batch,1,1,1,0,0,$other_session,NULL,NULL,$other_digest,0)"
expect_accepted valid_batch "INSERT INTO read_receipt_batches ($batch_columns) VALUES ($epoch,$batch,1,1,1,0,0,$session,NULL,NULL,$digest,0)"

outbox_columns="stream_epoch,sequence,event_id,batch_id,chat_id,user_id,watermark"
expect_rejected outbox_sequence_fraction "INSERT INTO read_receipt_outbox ($outbox_columns) VALUES ($epoch,1.5,$event,$batch,0,0,0)"
expect_rejected outbox_chat_fraction "INSERT INTO read_receipt_outbox ($outbox_columns) VALUES ($epoch,1,$event,$batch,0.5,0,0)"
expect_rejected outbox_user_fraction "INSERT INTO read_receipt_outbox ($outbox_columns) VALUES ($epoch,1,$event,$batch,0,0.5,0)"
expect_rejected outbox_watermark_fraction "INSERT INTO read_receipt_outbox ($outbox_columns) VALUES ($epoch,1,$event,$batch,0,0,0.5)"
expect_rejected outbox_negative_sequence "INSERT INTO read_receipt_outbox ($outbox_columns) VALUES ($epoch,-1,$event,$batch,0,0,0)"
expect_rejected outbox_zero_sequence "INSERT INTO read_receipt_outbox ($outbox_columns) VALUES ($epoch,0,$event,$batch,0,0,0)"
expect_rejected_check outbox_short_epoch "typeof(stream_epoch) = 'blob' AND length(stream_epoch) = 16" "INSERT INTO read_receipt_outbox ($outbox_columns) VALUES (zeroblob(15),1,$event,$batch,0,0,0)"
expect_rejected outbox_short_event "INSERT INTO read_receipt_outbox ($outbox_columns) VALUES ($epoch,1,zeroblob(15),$batch,0,0,0)"
expect_rejected_check outbox_short_batch "typeof(batch_id) = 'blob' AND length(batch_id) = 16" "INSERT INTO read_receipt_outbox ($outbox_columns) VALUES ($epoch,1,$event,zeroblob(15),0,0,0)"
expect_rejected orphan_outbox_event "INSERT INTO read_receipt_outbox ($outbox_columns) VALUES ($epoch,2,$other_event,$other_batch,0,0,0)"
expect_accepted negative_signed_identifiers "INSERT INTO read_receipt_outbox ($outbox_columns) VALUES ($epoch,1,$event,$batch,-9223372036854775808,-1,-2)"

loss_columns="stream_epoch,loss_receipt_id,first_sequence,last_sequence,dropped_event_count,dropped_batch_count,batch_id,reason,persisted_at_ms,persisted_elapsed_ms,capture_session_id,capture_boot_id,source_epoch_token,created_at_ms,content_digest"
expect_rejected loss_range_fraction "INSERT INTO read_receipt_loss_segments ($loss_columns) VALUES ($epoch,$loss,2.5,2.5,1,0,NULL,'bounded_prune',NULL,NULL,NULL,NULL,NULL,0,$digest)"
expect_rejected loss_count_fraction "INSERT INTO read_receipt_loss_segments ($loss_columns) VALUES ($epoch,$loss,2,2,1,0.5,NULL,'bounded_prune',NULL,NULL,NULL,NULL,NULL,0,$digest)"
expect_rejected loss_nullable_clock_fraction "INSERT INTO read_receipt_loss_segments ($loss_columns) VALUES ($epoch,$loss,2,2,1,0,NULL,'bounded_prune',0.5,NULL,NULL,NULL,NULL,0,$digest)"
expect_rejected loss_timestamp_fraction "INSERT INTO read_receipt_loss_segments ($loss_columns) VALUES ($epoch,$loss,2,2,1,0,NULL,'bounded_prune',NULL,NULL,NULL,NULL,NULL,0.5,$digest)"
expect_rejected loss_negative_sequence "INSERT INTO read_receipt_loss_segments ($loss_columns) VALUES ($epoch,$loss,-1,-1,1,0,NULL,'bounded_prune',NULL,NULL,NULL,NULL,NULL,0,$digest)"
expect_rejected loss_negative_count "INSERT INTO read_receipt_loss_segments ($loss_columns) VALUES ($epoch,$loss,2,2,1,-1,NULL,'bounded_prune',NULL,NULL,NULL,NULL,NULL,0,$digest)"
expect_rejected loss_negative_clock "INSERT INTO read_receipt_loss_segments ($loss_columns) VALUES ($epoch,$loss,2,2,1,0,NULL,'bounded_prune',-1,NULL,NULL,NULL,NULL,0,$digest)"
expect_rejected loss_negative_timestamp "INSERT INTO read_receipt_loss_segments ($loss_columns) VALUES ($epoch,$loss,2,2,1,0,NULL,'bounded_prune',NULL,NULL,NULL,NULL,NULL,-1,$digest)"
expect_rejected loss_reversed_range "INSERT INTO read_receipt_loss_segments ($loss_columns) VALUES ($epoch,$loss,3,2,1,0,NULL,'bounded_prune',NULL,NULL,NULL,NULL,NULL,0,$digest)"
expect_rejected loss_count_mismatch "INSERT INTO read_receipt_loss_segments ($loss_columns) VALUES ($epoch,$loss,2,2,2,0,NULL,'bounded_prune',NULL,NULL,NULL,NULL,NULL,0,$digest)"
expect_rejected loss_invalid_reason "INSERT INTO read_receipt_loss_segments ($loss_columns) VALUES ($epoch,$loss,2,2,1,0,NULL,'unknown',NULL,NULL,NULL,NULL,NULL,0,$digest)"
expect_rejected_check loss_short_epoch "typeof(stream_epoch) = 'blob' AND length(stream_epoch) = 16" "INSERT INTO read_receipt_loss_segments ($loss_columns) VALUES (zeroblob(15),$loss,2,2,1,0,NULL,'bounded_prune',NULL,NULL,NULL,NULL,NULL,0,$digest)"
expect_rejected loss_short_id "INSERT INTO read_receipt_loss_segments ($loss_columns) VALUES ($epoch,zeroblob(15),2,2,1,0,NULL,'bounded_prune',NULL,NULL,NULL,NULL,NULL,0,$digest)"
expect_rejected_check loss_short_batch "batch_id IS NULL OR (typeof(batch_id) = 'blob' AND length(batch_id) = 16)" "INSERT INTO read_receipt_loss_segments ($loss_columns) VALUES ($epoch,$loss,2,2,1,0,zeroblob(15),'bounded_prune',NULL,NULL,NULL,NULL,NULL,0,$digest)"
expect_rejected loss_short_session "INSERT INTO read_receipt_loss_segments ($loss_columns) VALUES ($epoch,$loss,2,2,1,0,NULL,'bounded_prune',NULL,NULL,zeroblob(15),NULL,NULL,0,$digest)"
expect_rejected loss_short_boot "INSERT INTO read_receipt_loss_segments ($loss_columns) VALUES ($epoch,$loss,2,2,1,0,NULL,'bounded_prune',NULL,NULL,NULL,zeroblob(15),NULL,0,$digest)"
expect_rejected loss_short_source "INSERT INTO read_receipt_loss_segments ($loss_columns) VALUES ($epoch,$loss,2,2,1,0,NULL,'bounded_prune',NULL,NULL,NULL,NULL,zeroblob(15),0,$digest)"
expect_rejected loss_short_digest "INSERT INTO read_receipt_loss_segments ($loss_columns) VALUES ($epoch,$loss,2,2,1,0,NULL,'bounded_prune',NULL,NULL,NULL,NULL,NULL,0,zeroblob(31))"
expect_rejected orphan_loss_batch "INSERT INTO read_receipt_loss_segments ($loss_columns) VALUES ($epoch,$other_loss,2,2,1,0,$other_batch,'bounded_prune',NULL,NULL,NULL,NULL,NULL,0,$digest)"

lease_columns="singleton,stream_epoch,lease_id,first_sequence,last_sequence,previous_sequence,lease_digest,state,nack_category,expected_cursor,confirmed_dropped_event_count,confirmed_dropped_batch_count,uncertain_outcome_event_count,uncertain_outcome_batch_count,capture_drop_event_count,capture_drop_batch_count,attempt_count,created_at_ms,updated_at_ms"
expect_rejected lease_range_fraction "INSERT INTO read_receipt_transfer_lease ($lease_columns) VALUES (1,$epoch,$lease,1,1.5,0,$digest,'active',NULL,NULL,0,0,0,0,0,0,0,0,0)"
expect_rejected lease_counter_fraction "INSERT INTO read_receipt_transfer_lease ($lease_columns) VALUES (1,$epoch,$lease,1,1,0,$digest,'active',NULL,NULL,0.5,0,0,0,0,0,0,0,0)"
expect_rejected lease_attempt_fraction "INSERT INTO read_receipt_transfer_lease ($lease_columns) VALUES (1,$epoch,$lease,1,1,0,$digest,'active',NULL,NULL,0,0,0,0,0,0,0.5,0,0)"
expect_rejected lease_timestamp_fraction "INSERT INTO read_receipt_transfer_lease ($lease_columns) VALUES (1,$epoch,$lease,1,1,0,$digest,'active',NULL,NULL,0,0,0,0,0,0,0,0.5,0)"
expect_rejected lease_negative_sequence "INSERT INTO read_receipt_transfer_lease ($lease_columns) VALUES (1,$epoch,$lease,-1,-1,-2,$digest,'active',NULL,NULL,0,0,0,0,0,0,0,0,0)"
expect_rejected lease_negative_counter "INSERT INTO read_receipt_transfer_lease ($lease_columns) VALUES (1,$epoch,$lease,1,1,0,$digest,'active',NULL,NULL,-1,0,0,0,0,0,0,0,0)"
expect_rejected lease_negative_cursor "INSERT INTO read_receipt_transfer_lease ($lease_columns) VALUES (1,$epoch,$lease,1,1,0,$digest,'poisoned','sequence_hole',-1,0,0,0,0,0,0,0,0,0)"
expect_rejected lease_negative_attempt "INSERT INTO read_receipt_transfer_lease ($lease_columns) VALUES (1,$epoch,$lease,1,1,0,$digest,'active',NULL,NULL,0,0,0,0,0,0,-1,0,0)"
expect_rejected lease_negative_timestamp "INSERT INTO read_receipt_transfer_lease ($lease_columns) VALUES (1,$epoch,$lease,1,1,0,$digest,'active',NULL,NULL,0,0,0,0,0,0,0,-1,0)"
expect_rejected lease_reversed_range "INSERT INTO read_receipt_transfer_lease ($lease_columns) VALUES (1,$epoch,$lease,2,1,1,$digest,'active',NULL,NULL,0,0,0,0,0,0,0,0,0)"
expect_rejected lease_previous_mismatch "INSERT INTO read_receipt_transfer_lease ($lease_columns) VALUES (1,$epoch,$lease,1,1,1,$digest,'active',NULL,NULL,0,0,0,0,0,0,0,0,0)"
expect_rejected lease_invalid_state "INSERT INTO read_receipt_transfer_lease ($lease_columns) VALUES (1,$epoch,$lease,1,1,0,$digest,'unknown',NULL,NULL,0,0,0,0,0,0,0,0,0)"
expect_rejected lease_invalid_category "INSERT INTO read_receipt_transfer_lease ($lease_columns) VALUES (1,$epoch,$lease,1,1,0,$digest,'poisoned','unknown',NULL,0,0,0,0,0,0,0,0,0)"
expect_rejected_check lease_short_epoch "typeof(stream_epoch) = 'blob' AND length(stream_epoch) = 16" "INSERT INTO read_receipt_transfer_lease ($lease_columns) VALUES (1,zeroblob(15),$lease,1,1,0,$digest,'active',NULL,NULL,0,0,0,0,0,0,0,0,0)"
expect_rejected lease_short_id "INSERT INTO read_receipt_transfer_lease ($lease_columns) VALUES (1,$epoch,zeroblob(15),1,1,0,$digest,'active',NULL,NULL,0,0,0,0,0,0,0,0,0)"
expect_rejected lease_short_digest "INSERT INTO read_receipt_transfer_lease ($lease_columns) VALUES (1,$epoch,$lease,1,1,0,zeroblob(31),'active',NULL,NULL,0,0,0,0,0,0,0,0,0)"
expect_rejected orphan_lease "INSERT INTO read_receipt_transfer_lease ($lease_columns) VALUES (1,$other_epoch,$other_lease,1,1,0,$other_digest,'active',NULL,NULL,0,0,0,0,0,0,0,0,0)"
expect_rejected active_category "INSERT INTO read_receipt_transfer_lease ($lease_columns) VALUES (1,$epoch,$lease,1,1,0,$digest,'active','sequence_hole',NULL,0,0,0,0,0,0,0,0,0)"
expect_rejected active_cursor "INSERT INTO read_receipt_transfer_lease ($lease_columns) VALUES (1,$epoch,$lease,1,1,0,$digest,'active',NULL,0,0,0,0,0,0,0,0,0,0)"
expect_rejected poisoned_missing_category "INSERT INTO read_receipt_transfer_lease ($lease_columns) VALUES (1,$epoch,$lease,1,1,0,$digest,'poisoned',NULL,NULL,0,0,0,0,0,0,0,0,0)"
expect_rejected poisoned_fractional_cursor "INSERT INTO read_receipt_transfer_lease ($lease_columns) VALUES (1,$epoch,$lease,1,1,0,$digest,'poisoned','sequence_hole',0.5,0,0,0,0,0,0,0,0,0)"
expect_accepted valid_lease "INSERT INTO read_receipt_transfer_lease ($lease_columns) VALUES (1,$epoch,$lease,1,1,0,$digest,'active',NULL,NULL,0,0,0,0,0,0,0,0,0)"
expect_rejected second_lease_singleton "INSERT INTO read_receipt_transfer_lease ($lease_columns) VALUES (1,$epoch,$other_lease,2,2,1,$other_digest,'active',NULL,NULL,0,0,0,0,0,0,0,0,0)"
expect_accepted clear_valid_lease "DELETE FROM read_receipt_transfer_lease"

for category in stream_poisoned sequence_hole range_mismatch lease_mismatch digest_mismatch conflicting_replay batch_conflict counter_regression stream_rollback unknown_epoch; do
    expect_accepted "poisoned_${category}_null_cursor" "INSERT INTO read_receipt_transfer_lease ($lease_columns) VALUES (1,$epoch,$lease,1,1,0,$digest,'poisoned','$category',NULL,0,0,0,0,0,0,0,0,0); DELETE FROM read_receipt_transfer_lease"
    expect_accepted "poisoned_${category}_integer_cursor" "INSERT INTO read_receipt_transfer_lease ($lease_columns) VALUES (1,$epoch,$lease,1,1,0,$digest,'poisoned','$category',0,0,0,0,0,0,0,0,0,0); DELETE FROM read_receipt_transfer_lease"
done

diagnostic_columns="category,occurrence_count,event_count,batch_count,last_observed_at_ms"
expect_rejected diagnostic_count_fraction "INSERT INTO read_receipt_diagnostic_counters ($diagnostic_columns) VALUES ('owner_mismatch',1.5,0,0,0)"
expect_rejected diagnostic_timestamp_fraction "INSERT INTO read_receipt_diagnostic_counters ($diagnostic_columns) VALUES ('owner_mismatch',1,0,0,0.5)"
expect_rejected diagnostic_invalid_category "INSERT INTO read_receipt_diagnostic_counters ($diagnostic_columns) VALUES ('unknown',0,0,0,NULL)"
expect_rejected diagnostic_negative_occurrence "INSERT INTO read_receipt_diagnostic_counters ($diagnostic_columns) VALUES ('owner_mismatch',-1,0,0,NULL)"
expect_rejected diagnostic_negative_event "INSERT INTO read_receipt_diagnostic_counters ($diagnostic_columns) VALUES ('owner_mismatch',0,-1,0,NULL)"
expect_rejected diagnostic_negative_batch "INSERT INTO read_receipt_diagnostic_counters ($diagnostic_columns) VALUES ('owner_mismatch',0,0,-1,NULL)"
expect_rejected diagnostic_zero_with_timestamp "INSERT INTO read_receipt_diagnostic_counters ($diagnostic_columns) VALUES ('owner_mismatch',0,0,0,0)"
expect_rejected diagnostic_positive_without_timestamp "INSERT INTO read_receipt_diagnostic_counters ($diagnostic_columns) VALUES ('owner_mismatch',1,0,0,NULL)"

metadata=$(sql "SELECT application_id || '|' || user_version || '|' || (SELECT journal_mode FROM pragma_journal_mode) || '|' || (SELECT foreign_keys FROM pragma_foreign_keys) || '|' || (SELECT count(*) FROM sqlite_schema) || '|' || (SELECT count(*) FROM pragma_foreign_key_check) FROM pragma_application_id CROSS JOIN pragma_user_version")
if [[ "$metadata" != "1230131762|2|delete|1|17|0" ]]; then
    echo "unexpected schema metadata: $metadata" >&2
    exit 1
fi

sql "CREATE TABLE android_metadata (locale TEXT)"
android_metadata=$(sql "SELECT type || '|' || name || '|' || tbl_name || '|' || sql FROM sqlite_schema WHERE name = 'android_metadata'")
if [[ "$android_metadata" != "table|android_metadata|android_metadata|CREATE TABLE android_metadata (locale TEXT)" ]]; then
    echo "android metadata catalog mismatch" >&2
    exit 1
fi

echo "DDL-001 host verification passed: application_id=1230131762 user_version=2 journal_mode=delete foreign_keys=1 objects=17 android_metadata=exact foreign_key_check=0"
