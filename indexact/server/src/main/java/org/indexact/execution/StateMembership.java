package org.indexact.execution;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.PrimitiveIterator;
import java.util.NoSuchElementException;
import java.util.stream.IntStream;
import org.apache.lucene.util.FixedBitSet;
import org.indexact.index.Snapshot;

/** Immutable density-adaptive Lucene-document membership partitioned by leaf. */
public final class StateMembership {
    public enum Representation {
        DENSE,
        SPARSE
    }

    private sealed interface LeafMembership permits DenseLeaf, SparseLeaf, FullLeaf {
        int cardinality();

        boolean contains(int localDocument);

        int[] documents();

        PrimitiveIterator.OfInt iterator();

        Representation representation();
    }

    /** Deletion-free CORPUS: immutable range, no per-session array or bitmap build. */
    private record FullLeaf(int cardinality) implements LeafMembership {
        @Override
        public boolean contains(int document) {
            return document >= 0 && document < cardinality;
        }

        @Override
        public int[] documents() {
            return IntStream.range(0, cardinality).toArray();
        }

        @Override
        public PrimitiveIterator.OfInt iterator() {
            return IntStream.range(0, cardinality).iterator();
        }

        @Override
        public Representation representation() {
            return Representation.DENSE;
        }
    }

    private record DenseLeaf(FixedBitSet bits, int cardinality) implements LeafMembership {
        @Override
        public PrimitiveIterator.OfInt iterator() {
            return new PrimitiveIterator.OfInt() {
                private int remaining = cardinality;
                private int from;

                public boolean hasNext() { return remaining > 0; }

                public int nextInt() {
                    if (!hasNext()) { throw new NoSuchElementException(); }
                    int next = bits.nextSetBit(from);
                    from = next + 1;
                    remaining--;
                    return next;
                }
            };
        }

        @Override
        public boolean contains(int localDocument) {
            return localDocument >= 0 && localDocument < bits.length() && bits.get(localDocument);
        }

        @Override
        public int[] documents() {
            int[] result = new int[cardinality];
            int cursor = 0;
            for (int document = 0; document < bits.length(); document++) {
                if (bits.get(document)) {
                    result[cursor++] = document;
                }
            }
            return result;
        }

        @Override
        public Representation representation() {
            return Representation.DENSE;
        }
    }

    private record SparseLeaf(int[] sortedDocuments) implements LeafMembership {
        SparseLeaf {
            sortedDocuments = sortedDocuments.clone();
        }

        @Override
        public PrimitiveIterator.OfInt iterator() {
            return Arrays.stream(sortedDocuments).iterator();
        }

        @Override
        public int cardinality() {
            return sortedDocuments.length;
        }

        @Override
        public boolean contains(int localDocument) {
            return Arrays.binarySearch(sortedDocuments, localDocument) >= 0;
        }

        @Override
        public int[] documents() {
            return sortedDocuments.clone();
        }

        @Override
        public Representation representation() {
            return Representation.SPARSE;
        }
    }

    private final Snapshot snapshot;
    private final List<LeafMembership> leaves;
    private final long cardinality;
    private final java.util.concurrent.atomic.LongAdder docKeyMaterializations =
            new java.util.concurrent.atomic.LongAdder();

    private StateMembership(Snapshot snapshot, List<LeafMembership> leaves) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.leaves = List.copyOf(leaves);
        this.cardinality = leaves.stream().mapToLong(LeafMembership::cardinality).sum();
    }

    public static StateMembership all(Snapshot snapshot) {
        List<LeafMembership> leaves = new ArrayList<>(snapshot.leafCount());
        for (int leaf = 0; leaf < snapshot.leafCount(); leaf++) {
            leaves.add(new FullLeaf(snapshot.leafMaxDoc(leaf)));
        }
        return new StateMembership(snapshot, leaves);
    }

    public boolean belongsTo(Snapshot candidate) {
        return snapshot == candidate;
    }

    /** Ascending Lucene IDs, without allocating an array proportional to membership. */
    public PrimitiveIterator.OfInt iterator() {
        return new PrimitiveIterator.OfInt() {
            private int leaf = -1;
            private PrimitiveIterator.OfInt current = IntStream.empty().iterator();

            public boolean hasNext() {
                while (!current.hasNext() && leaf + 1 < leaves.size()) {
                    current = leaves.get(++leaf).iterator();
                }
                return current.hasNext();
            }

            public int nextInt() {
                if (!hasNext()) { throw new NoSuchElementException(); }
                return snapshot.leafDocumentBase(leaf) + current.nextInt();
            }
        };
    }

    public static StateMembership fromDocKeys(Snapshot snapshot, Collection<String> docKeys) {
        Objects.requireNonNull(docKeys, "docKeys");
        final int[] documents;
        try {
            documents = docKeys.stream()
                    .mapToInt(snapshot::internalDocumentId)
                    .sorted()
                    .distinct()
                    .toArray();
        } catch (java.util.NoSuchElementException error) {
            throw new IllegalArgumentException("membership contains an unknown DocKey", error);
        }
        if (documents.length != docKeys.size()) {
            throw new IllegalArgumentException("membership contains duplicate DocKeys");
        }
        return fromGlobalDocuments(snapshot, documents);
    }

    public static StateMembership fromGlobalDocuments(Snapshot snapshot, int[] globalDocuments) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(globalDocuments, "globalDocuments");
        int[][] byLeaf = new int[snapshot.leafCount()][];
        int[] counts = new int[byLeaf.length];
        for (int global : globalDocuments) {
            int leaf = snapshot.leafOrdinal(global);
            counts[leaf]++;
        }
        for (int leaf = 0; leaf < byLeaf.length; leaf++) {
            byLeaf[leaf] = new int[counts[leaf]];
        }
        Arrays.fill(counts, 0);
        for (int global : globalDocuments) {
            int leaf = snapshot.leafOrdinal(global);
            byLeaf[leaf][counts[leaf]++] = global - snapshot.leafDocumentBase(leaf);
        }
        List<LeafMembership> leaves = new ArrayList<>(byLeaf.length);
        for (int leaf = 0; leaf < byLeaf.length; leaf++) {
            int[] local = byLeaf[leaf];
            Arrays.sort(local);
            int unique = 0;
            for (int document : local) {
                if (unique == 0 || local[unique - 1] != document) {
                    local[unique++] = document;
                }
            }
            local = Arrays.copyOf(local, unique);
            leaves.add(buildLeaf(snapshot.leafMaxDoc(leaf), local));
        }
        return new StateMembership(snapshot, leaves);
    }

    public String snapshotId() {
        return snapshot.snapshotId();
    }

    public long cardinality() {
        return cardinality;
    }

    public int leafCount() {
        return leaves.size();
    }

    public Representation representation(int leafOrdinal) {
        return leaves.get(leafOrdinal).representation();
    }

    public boolean contains(int leafOrdinal, int localDocument) {
        return leaves.get(leafOrdinal).contains(localDocument);
    }

    /** Sorted immutable iterator source for one Lucene leaf. */
    public int[] documents(int leafOrdinal) {
        return leaves.get(leafOrdinal).documents();
    }

    public boolean containsGlobal(int globalDocument) {
        int leaf = snapshot.leafOrdinal(globalDocument);
        return contains(leaf, globalDocument - snapshot.leafDocumentBase(leaf));
    }

    public int[] globalDocuments() {
        int[] result = new int[Math.toIntExact(cardinality)];
        int cursor = 0;
        for (int leaf = 0; leaf < leaves.size(); leaf++) {
            int base = snapshot.leafDocumentBase(leaf);
            for (int local : leaves.get(leaf).documents()) {
                result[cursor++] = base + local;
            }
        }
        return result;
    }

    public java.util.Set<String> docKeys() {
        docKeyMaterializations.increment();
        java.util.HashSet<String> result = new java.util.HashSet<>();
        for (int document : globalDocuments()) {
            result.add(snapshot.docKeyForInternalDocument(document));
        }
        return java.util.Set.copyOf(result);
    }

    public long docKeyMaterializationCount() { return docKeyMaterializations.sum(); }

    public List<String> orderedDocKeys() {
        if (cardinality == snapshot.maxDocumentId()) {
            return snapshot.docKeys();
        }
        List<String> result = new ArrayList<>(Math.toIntExact(cardinality));
        PrimitiveIterator.OfInt documents = iterator();
        while (documents.hasNext()) {
            result.add(snapshot.docKeyForInternalDocument(documents.nextInt()));
        }
        result.sort(org.indexact.index.SnapshotBuilder.CODE_POINT_ORDER);
        return List.copyOf(result);
    }

    public StateMembership intersect(StateMembership other) {
        requireCompatible(other);
        return combine(other, 0);
    }

    public StateMembership union(StateMembership other) {
        requireCompatible(other);
        return combine(other, 1);
    }

    public StateMembership difference(StateMembership other) {
        requireCompatible(other);
        return combine(other, 2);
    }

    private StateMembership combine(StateMembership other, int operation) {
        List<LeafMembership> result = new ArrayList<>(leaves.size());
        for (int leaf = 0; leaf < leaves.size(); leaf++) {
            LeafMembership leftLeaf = leaves.get(leaf);
            LeafMembership rightLeaf = other.leaves.get(leaf);
            if (leftLeaf instanceof DenseLeaf leftDense
                    && rightLeaf instanceof DenseLeaf rightDense) {
                FixedBitSet bits = leftDense.bits().clone();
                switch (operation) {
                    case 0 -> bits.and(rightDense.bits());
                    case 1 -> bits.or(rightDense.bits());
                    case 2 -> bits.andNot(rightDense.bits());
                    default -> throw new AssertionError("unknown membership operation");
                }
                int[] documents = new int[Math.toIntExact(bits.cardinality())];
                int cursor = 0;
                for (int document = 0; document < bits.length(); document++) {
                    if (bits.get(document)) {
                        documents[cursor++] = document;
                    }
                }
                result.add(buildLeaf(snapshot.leafMaxDoc(leaf), documents));
            } else {
                result.add(buildLeaf(
                        snapshot.leafMaxDoc(leaf),
                        mergeSorted(leftLeaf.documents(), rightLeaf.documents(), operation)));
            }
        }
        return new StateMembership(snapshot, result);
    }

    private static LeafMembership buildLeaf(int maxDoc, int[] sortedDocuments) {
        if ((long) sortedDocuments.length * Long.SIZE >= maxDoc && maxDoc > 0) {
            FixedBitSet bits = new FixedBitSet(maxDoc);
            for (int document : sortedDocuments) {
                bits.set(document);
            }
            return new DenseLeaf(bits, sortedDocuments.length);
        }
        return new SparseLeaf(sortedDocuments);
    }

    private static int[] mergeSorted(int[] left, int[] right, int operation) {
        int[] output = new int[operation == 1 ? left.length + right.length : left.length];
        int l = 0;
        int r = 0;
        int out = 0;
        while (l < left.length || r < right.length) {
            int comparison = l >= left.length
                    ? 1
                    : r >= right.length ? -1 : Integer.compare(left[l], right[r]);
            if (comparison == 0) {
                if (operation != 2) {
                    output[out++] = left[l];
                }
                l++;
                r++;
            } else if (comparison < 0) {
                if (operation != 0) {
                    output[out++] = left[l];
                }
                l++;
            } else {
                if (operation == 1) {
                    output[out++] = right[r];
                }
                r++;
            }
        }
        return Arrays.copyOf(output, out);
    }

    private void requireCompatible(StateMembership other) {
        Objects.requireNonNull(other, "other");
        if (snapshot != other.snapshot) {
            throw new IllegalArgumentException("memberships belong to different snapshot instances");
        }
    }
}
