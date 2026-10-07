package org.indexact.index;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable snapshot manifest values required by the approved contracts. */
public record Manifest(
        String snapshotId,
        String corpusVersion,
        String analyzerContractVersion,
        String scoringContractVersion,
        String coordinateContractVersion,
        String unicodeVersion,
        int uax15Revision,
        int uax29Revision,
        String emissionProfile,
        String normalization,
        String caseMapping,
        String stemming,
        String stopwords,
        String positionScheme,
        String luceneVersion,
        long documentCount,
        long sumLenTokens,
        double avgdl,
        int maxDocKeyUtf8BytesObserved,
        int maxAnalyzedTokenUtf8BytesObserved,
        boolean perSegmentDocKeySort,
        Map<String, String> indexChecksums) {
    private static final Pattern SNAPSHOT_ID = Pattern.compile("snap-[0-9a-f]{64}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public Manifest {
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(corpusVersion, "corpusVersion");
        Objects.requireNonNull(analyzerContractVersion, "analyzerContractVersion");
        Objects.requireNonNull(scoringContractVersion, "scoringContractVersion");
        Objects.requireNonNull(coordinateContractVersion, "coordinateContractVersion");
        Objects.requireNonNull(unicodeVersion, "unicodeVersion");
        Objects.requireNonNull(emissionProfile, "emissionProfile");
        Objects.requireNonNull(normalization, "normalization");
        Objects.requireNonNull(caseMapping, "caseMapping");
        Objects.requireNonNull(stemming, "stemming");
        Objects.requireNonNull(stopwords, "stopwords");
        Objects.requireNonNull(positionScheme, "positionScheme");
        Objects.requireNonNull(luceneVersion, "luceneVersion");
        Objects.requireNonNull(indexChecksums, "indexChecksums");
        indexChecksums = Map.copyOf(indexChecksums);
        if (!SNAPSHOT_ID.matcher(snapshotId).matches()) {
            throw new IllegalArgumentException("invalid snapshot ID in manifest");
        }
        if (documentCount < 0 || sumLenTokens < 0 || maxDocKeyUtf8BytesObserved < 0
                || maxAnalyzedTokenUtf8BytesObserved < 0) {
            throw new IllegalArgumentException("manifest counts must be non-negative");
        }
        if (documentCount > SnapshotBuilder.MAX_SAFE_INTEGER
                || sumLenTokens > SnapshotBuilder.MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException("manifest counts must be I-JSON safe");
        }
        if (!Double.isFinite(avgdl) || avgdl < 0.0) {
            throw new IllegalArgumentException("manifest avgdl must be finite and non-negative");
        }
        for (Map.Entry<String, String> checksum : indexChecksums.entrySet()) {
            String name = Objects.requireNonNull(checksum.getKey(), "checksum file name");
            String hash = Objects.requireNonNull(checksum.getValue(), "checksum hash");
            if (name.isEmpty()
                    || name.equals(".")
                    || name.equals("..")
                    || name.indexOf('/') >= 0
                    || name.indexOf('\\') >= 0
                    || !SHA256.matcher(hash).matches()) {
                throw new IllegalArgumentException("invalid index checksum entry");
            }
        }
    }

    public Manifest withIndexChecksums(Map<String, String> checksums) {
        return new Manifest(
                snapshotId,
                corpusVersion,
                analyzerContractVersion,
                scoringContractVersion,
                coordinateContractVersion,
                unicodeVersion,
                uax15Revision,
                uax29Revision,
                emissionProfile,
                normalization,
                caseMapping,
                stemming,
                stopwords,
                positionScheme,
                luceneVersion,
                documentCount,
                sumLenTokens,
                avgdl,
                maxDocKeyUtf8BytesObserved,
                maxAnalyzedTokenUtf8BytesObserved,
                perSegmentDocKeySort,
                checksums);
    }
}
