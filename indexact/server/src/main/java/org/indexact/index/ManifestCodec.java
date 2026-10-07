package org.indexact.index;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.indexact.analysis.UnicodeScalar;
import org.indexact.protocol.json.CompactWireJson;
import org.indexact.protocol.json.JsonNumber;
import org.indexact.protocol.json.JsonParseException;
import org.indexact.protocol.json.StrictJsonParser;

/** Strict, deterministic external snapshot-manifest JSON codec. */
public final class ManifestCodec {
    /** A structurally readable manifest whose declared numeric domains cannot be served. */
    public static final class IncompatibleManifestException extends IOException {
        IncompatibleManifestException(String message) {
            super(message);
        }
    }

    private static final List<String> FIELD_ORDER = List.of(
            "snapshot_id",
            "corpus_version",
            "analyzer_contract_version",
            "scoring_contract_version",
            "coordinate_contract_version",
            "unicode_version",
            "uax15_revision",
            "uax29_revision",
            "emission_profile",
            "normalization",
            "case_mapping",
            "stemming",
            "stopwords",
            "position_scheme",
            "lucene_version",
            "N",
            "sum_len_tokens",
            "avgdl",
            "max_doc_key_utf8_bytes_observed",
            "max_analyzed_token_utf8_bytes_observed",
            "per_segment_doc_key_sort",
            "index_checksums");
    private static final Set<String> FIELDS = Set.copyOf(FIELD_ORDER);

    private ManifestCodec() {}

    public static byte[] encode(Manifest manifest) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("snapshot_id", manifest.snapshotId());
        value.put("corpus_version", manifest.corpusVersion());
        value.put("analyzer_contract_version", manifest.analyzerContractVersion());
        value.put("scoring_contract_version", manifest.scoringContractVersion());
        value.put("coordinate_contract_version", manifest.coordinateContractVersion());
        value.put("unicode_version", manifest.unicodeVersion());
        value.put("uax15_revision", manifest.uax15Revision());
        value.put("uax29_revision", manifest.uax29Revision());
        value.put("emission_profile", manifest.emissionProfile());
        value.put("normalization", manifest.normalization());
        value.put("case_mapping", manifest.caseMapping());
        value.put("stemming", manifest.stemming());
        value.put("stopwords", manifest.stopwords());
        value.put("position_scheme", manifest.positionScheme());
        value.put("lucene_version", manifest.luceneVersion());
        value.put("N", manifest.documentCount());
        value.put("sum_len_tokens", manifest.sumLenTokens());
        value.put("avgdl", manifest.avgdl());
        value.put("max_doc_key_utf8_bytes_observed", manifest.maxDocKeyUtf8BytesObserved());
        value.put(
                "max_analyzed_token_utf8_bytes_observed",
                manifest.maxAnalyzedTokenUtf8BytesObserved());
        value.put("per_segment_doc_key_sort", manifest.perSegmentDocKeySort());
        value.put(
                "index_checksums",
                new CompactWireJson.SortedObject(manifest.indexChecksums()));
        return CompactWireJson.encode(value);
    }

    public static Manifest decode(byte[] utf8) throws IOException {
        final Object parsed;
        try {
            parsed = StrictJsonParser.parse(utf8);
        } catch (JsonParseException error) {
            throw new IOException("manifest is not strict UTF-8 JSON", error);
        }
        if (!(parsed instanceof Map<?, ?> raw)) {
            throw new IOException("manifest must be a JSON object");
        }
        Map<String, Object> object = stringObject(raw, "manifest");
        if (!object.keySet().equals(FIELDS)) {
            throw new IOException("manifest has missing or unknown fields");
        }
        try {
            return new Manifest(
                    string(object, "snapshot_id"),
                    string(object, "corpus_version"),
                    string(object, "analyzer_contract_version"),
                    string(object, "scoring_contract_version"),
                    string(object, "coordinate_contract_version"),
                    string(object, "unicode_version"),
                    integer(object, "uax15_revision"),
                    integer(object, "uax29_revision"),
                    string(object, "emission_profile"),
                    string(object, "normalization"),
                    string(object, "case_mapping"),
                    string(object, "stemming"),
                    string(object, "stopwords"),
                    string(object, "position_scheme"),
                    string(object, "lucene_version"),
                    safeLong(object, "N"),
                    safeLong(object, "sum_len_tokens"),
                    finiteNonNegativeDouble(object, "avgdl"),
                    integer(object, "max_doc_key_utf8_bytes_observed"),
                    integer(object, "max_analyzed_token_utf8_bytes_observed"),
                    bool(object, "per_segment_doc_key_sort"),
                    checksums(object.get("index_checksums")));
        } catch (IllegalArgumentException | ArithmeticException error) {
            throw new IOException("manifest contains an invalid value", error);
        }
    }

    private static Map<String, String> checksums(Object value) throws IOException {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IOException("index_checksums must be an object");
        }
        Map<String, Object> object = stringObject(raw, "index_checksums");
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        object.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(SnapshotBuilder.CODE_POINT_ORDER))
                .forEach(entry -> {
                    if (!(entry.getValue() instanceof String hash)) {
                        throw new IllegalArgumentException("checksum must be a string");
                    }
                    result.put(entry.getKey(), hash);
                });
        return Map.copyOf(result);
    }

    private static Map<String, Object> stringObject(Map<?, ?> raw, String label)
            throws IOException {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IOException(label + " property name is not a string");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static String string(Map<String, Object> object, String name) throws IOException {
        if (!(object.get(name) instanceof String value)) {
            throw new IOException(name + " must be a string");
        }
        UnicodeScalar.toCodePoints(value);
        return value;
    }

    private static JsonNumber number(Map<String, Object> object, String name) throws IOException {
        if (!(object.get(name) instanceof JsonNumber value)) {
            throw new IOException(name + " must be a JSON number");
        }
        return value;
    }

    private static long safeLong(Map<String, Object> object, String name) throws IOException {
        JsonNumber value = number(object, name);
        BigInteger exact = value.exactInteger();
        if (exact.signum() < 0
                || exact.compareTo(BigInteger.valueOf(SnapshotBuilder.MAX_SAFE_INTEGER)) > 0) {
            throw new IncompatibleManifestException(
                    name + " is outside the public integer domain");
        }
        return exact.longValueExact();
    }

    private static int integer(Map<String, Object> object, String name) throws IOException {
        long value = safeLong(object, name);
        if (value > Integer.MAX_VALUE) {
            throw new IncompatibleManifestException(name + " exceeds the JVM representation");
        }
        return (int) value;
    }

    private static double finiteNonNegativeDouble(
            Map<String, Object> object, String name) throws IOException {
        JsonNumber number = number(object, name);
        if (number.exactNegativeNonzero()) {
            throw new IncompatibleManifestException(name + " must be non-negative");
        }
        double value = number.binary64();
        if (!Double.isFinite(value)) {
            throw new IncompatibleManifestException(name + " must be finite binary64");
        }
        return value == 0.0 ? 0.0 : value;
    }

    private static boolean bool(Map<String, Object> object, String name) throws IOException {
        if (!(object.get(name) instanceof Boolean value)) {
            throw new IOException(name + " must be boolean");
        }
        return value;
    }

    public static String utf8(Manifest manifest) {
        return new String(encode(manifest), StandardCharsets.UTF_8);
    }
}
