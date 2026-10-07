package org.indexact.protocol;

import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable deployment capability profile negotiated at OPEN_SESSION. */
public record ServiceLimits(
        long maxRequestBytes,
        long maxResponseBytes,
        long maxBatchOperations,
        long maxAstNodes,
        long maxAstDepth,
        long maxGapChoiceBranches,
        long maxPageLimit,
        long maxActiveNonrootStates,
        long maxDocKeyUtf8Bytes,
        long maxLineageStringUtf8Bytes,
        long maxAnalyzedTermUtf8Bytes,
        long maxReadSelectedDocs,
        long maxReadOutputCodepoints,
        long maxReadEvidenceCount,
        long maxLineageNodeBytes,
        long maxRetainedLineageNodes,
        long maxRetainedLineageBytes,
        long maxLineageDepth) {
    public static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    public ServiceLimits {
        long[] values = {
            maxRequestBytes,
            maxResponseBytes,
            maxBatchOperations,
            maxAstNodes,
            maxAstDepth,
            maxGapChoiceBranches,
            maxPageLimit,
            maxActiveNonrootStates,
            maxDocKeyUtf8Bytes,
            maxLineageStringUtf8Bytes,
            maxAnalyzedTermUtf8Bytes,
            maxReadSelectedDocs,
            maxLineageNodeBytes,
            maxRetainedLineageNodes,
            maxRetainedLineageBytes,
            maxLineageDepth
        };
        for (long value : values) {
            requirePositive(value);
        }
        requireNonNegative(maxReadOutputCodepoints);
        requireNonNegative(maxReadEvidenceCount);
    }

    public static ServiceLimits defaults() {
        return new ServiceLimits(
                1_048_576,
                4_194_304,
                128,
                4096,
                64,
                65_536,
                1000,
                10_000,
                4096,
                16_384,
                32_766,
                10_000,
                1_000_000,
                10_000,
                65_536,
                100_000,
                67_108_864,
                4096);
    }

    public Map<String, Object> toWire() {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("max_request_bytes", maxRequestBytes);
        result.put("max_response_bytes", maxResponseBytes);
        result.put("max_batch_operations", maxBatchOperations);
        result.put("max_ast_nodes", maxAstNodes);
        result.put("max_ast_depth", maxAstDepth);
        result.put("max_gap_choice_branches", maxGapChoiceBranches);
        result.put("max_page_limit", maxPageLimit);
        result.put("max_active_nonroot_states", maxActiveNonrootStates);
        result.put("max_doc_key_utf8_bytes", maxDocKeyUtf8Bytes);
        result.put("max_lineage_string_utf8_bytes", maxLineageStringUtf8Bytes);
        result.put("max_analyzed_term_utf8_bytes", maxAnalyzedTermUtf8Bytes);
        result.put("max_read_selected_docs", maxReadSelectedDocs);
        result.put("max_read_output_codepoints", maxReadOutputCodepoints);
        result.put("max_read_evidence_count", maxReadEvidenceCount);
        result.put("max_lineage_node_bytes", maxLineageNodeBytes);
        result.put("max_retained_lineage_nodes", maxRetainedLineageNodes);
        result.put("max_retained_lineage_bytes", maxRetainedLineageBytes);
        result.put("max_lineage_depth", maxLineageDepth);
        return result;
    }

    private static void requirePositive(long value) {
        if (value < 1 || value > MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException("positive ServiceLimit is outside I-JSON domain");
        }
    }

    private static void requireNonNegative(long value) {
        if (value < 0 || value > MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException("ServiceLimit is outside I-JSON domain");
        }
    }
}
