package org.indexact.state;

import java.util.LinkedHashMap;
import java.util.Map;

/** Exact compact public metadata for one active state. */
public record StateMetadata(
        String handle,
        String stateType,
        String snapshotId,
        long cardinality,
        String lineageId,
        String createdBy) {
    public Map<String, Object> toWire() {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("handle", handle);
        result.put("state_type", stateType);
        result.put("snapshot_id", snapshotId);
        result.put("cardinality", cardinality);
        result.put("lineage_id", lineageId);
        result.put("created_by", createdBy);
        return result;
    }
}
