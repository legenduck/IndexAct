package org.indexact.execution;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.SimpleCollector;
import org.apache.lucene.search.TermQuery;
import org.indexact.analysis.ContractAnalyzer;
import org.indexact.expression.And;
import org.indexact.expression.AnyOf;
import org.indexact.expression.LexicalExpression;
import org.indexact.expression.Near;
import org.indexact.expression.Not;
import org.indexact.expression.Or;
import org.indexact.expression.Phrase;
import org.indexact.expression.TextCondition;
import org.indexact.index.FieldSchema;
import org.indexact.index.Snapshot;

/** Exact immutable state execution over Lucene queries and per-leaf membership. */
public final class StateOperations implements AutoCloseable {
    private final Snapshot snapshot;
    private final ContractAnalyzer queryAnalyzer;
    private long lastCollectedDocuments;
    private long lastOpenedOccurrencePostings;

    public StateOperations(Snapshot snapshot) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.queryAnalyzer = new ContractAnalyzer();
    }

    public SetState corpus() {
        return SetState.fromMembership(StateMembership.all(snapshot));
    }

    public SetState filter(SetState state, TextCondition condition) throws IOException {
        StateMembership candidates = membership(state);
        Objects.requireNonNull(condition, "condition");
        validateTerms(condition);
        Query query;
        try {
            query = candidateQuery(condition);
        } catch (org.apache.lucene.search.IndexSearcher.TooManyClauses engineLimit) {
            // Engine clause limits are not public semantics; exact post-validation remains complete.
            query = new MatchAllDocsQuery();
        }
        ArrayList<Integer> candidateDocuments = new ArrayList<>();
        long[] collected = {0};
        snapshot.searcher().search(query, new SimpleCollector() {
            private int leafOrdinal;
            private int documentBase;

            @Override
            protected void doSetNextReader(LeafReaderContext context) {
                leafOrdinal = context.ord;
                documentBase = context.docBase;
            }

            @Override
            public void collect(int localDocument) throws IOException {
                if (Thread.currentThread().isInterrupted()) {
                    throw new java.io.InterruptedIOException(
                            "candidate collection interrupted");
                }
                if (!candidates.contains(leafOrdinal, localDocument)) {
                    return;
                }
                collected[0]++;
                candidateDocuments.add(documentBase + localDocument);
            }

            @Override
            public ScoreMode scoreMode() {
                return ScoreMode.COMPLETE_NO_SCORES;
            }
        });
        lastCollectedDocuments = collected[0];
        candidateDocuments.sort(Integer::compareTo);

        ArrayList<Integer> accepted = new ArrayList<>();
        if (condition instanceof org.indexact.expression.Term) {
            // The candidate TermQuery is already the complete exact TERM denotation.
            accepted.addAll(candidateDocuments);
            lastOpenedOccurrencePostings = 0;
        } else {
            Map<String, String> analyzedTerms = new HashMap<>();
            Map<String, Snapshot.TermPositionCursor> cursors = new HashMap<>();
            OccurrenceEvaluator.TermPositionSource occurrences =
                    (internalDocument, ignoredDocKey, analyzedToken) -> {
                        Snapshot.TermPositionCursor cursor = cursors.get(analyzedToken);
                        if (cursor == null) {
                            cursor = snapshot.newTermPositionCursor(analyzedToken);
                            cursors.put(analyzedToken, cursor);
                        }
                        return cursor.positionsAt(internalDocument);
                    };
            for (int globalDocument : candidateDocuments) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new java.io.InterruptedIOException(
                            "condition evaluation interrupted");
                }
                String docKey = snapshot.docKeyForInternalDocument(globalDocument);
                if (new ConditionEvaluator(new OccurrenceEvaluator(
                                snapshot,
                                docKey,
                                globalDocument,
                                queryAnalyzer,
                                analyzedTerms,
                                occurrences))
                        .satisfies(condition)) {
                    accepted.add(globalDocument);
                }
            }
            lastOpenedOccurrencePostings = cursors.size();
        }
        return SetState.fromMembership(StateMembership.fromGlobalDocuments(
                snapshot, accepted.stream().mapToInt(Integer::intValue).toArray()));
    }

    /** Diagnostic proving positive filters are postings-driven rather than corpus scans. */
    public long lastCollectedDocuments() {
        return lastCollectedDocuments;
    }

    /** Diagnostic proving each analyzed term opens at most one positional cursor per operation. */
    public long lastOpenedOccurrencePostings() {
        return lastOpenedOccurrencePostings;
    }

    public SetState intersect(SetState left, SetState right) {
        return SetState.fromMembership(membership(left).intersect(membership(right)));
    }

    public SetState union(SetState left, SetState right) {
        return SetState.fromMembership(membership(left).union(membership(right)));
    }

    public SetState difference(SetState left, SetState right) {
        return SetState.fromMembership(membership(left).difference(membership(right)));
    }

    public long count(SetState state) {
        return membership(state).cardinality();
    }

    public long countDocs(SetState state, TextCondition condition) throws IOException {
        return filter(state, condition).cardinality();
    }

    StateMembership membership(SetState state) {
        Objects.requireNonNull(state, "state");
        if (!snapshot.snapshotId().equals(state.snapshotId())) {
            throw new IllegalArgumentException("state belongs to another snapshot");
        }
        if (state.hasPhysicalMembership()) {
            return state.physicalMembership();
        }
        return StateMembership.fromDocKeys(snapshot, state.members());
    }

    private Query candidateQuery(TextCondition condition) {
        record Frame(TextCondition condition, boolean expanded) {}
        java.util.IdentityHashMap<TextCondition, Query> built = new java.util.IdentityHashMap<>();
        java.util.ArrayDeque<Frame> pending = new java.util.ArrayDeque<>();
        pending.push(new Frame(condition, false));
        while (!pending.isEmpty()) {
            Frame frame = pending.pop();
            TextCondition current = frame.condition();
            if (built.containsKey(current)) {
                continue;
            }
            List<? extends TextCondition> children = conditionChildren(current);
            if (!frame.expanded()) {
                pending.push(new Frame(current, true));
                for (int index = children.size() - 1; index >= 0; index--) {
                    pending.push(new Frame(children.get(index), false));
                }
                continue;
            }
            Query query;
            if (current instanceof org.indexact.expression.Term term) {
                query = new TermQuery(new Term(
                        FieldSchema.BODY, queryAnalyzer.analyzeTerm(term.surface()).text()));
            } else if (current instanceof Not) {
                query = new MatchAllDocsQuery();
            } else {
                BooleanQuery.Builder builder = new BooleanQuery.Builder();
                boolean constrained = false;
                if (current instanceof Or or
                        && or.children().stream().anyMatch(Not.class::isInstance)) {
                    query = new MatchAllDocsQuery();
                    built.put(current, query);
                    continue;
                }
                BooleanClause.Occur occurrence = current instanceof Or || current instanceof AnyOf
                        ? BooleanClause.Occur.SHOULD
                        : BooleanClause.Occur.FILTER;
                for (TextCondition child : children) {
                    if (current instanceof And && child instanceof Not) {
                        continue;
                    }
                    builder.add(built.get(child), occurrence);
                    constrained = true;
                }
                if (occurrence == BooleanClause.Occur.SHOULD) {
                    builder.setMinimumNumberShouldMatch(1);
                }
                query = constrained ? builder.build() : new MatchAllDocsQuery();
            }
            built.put(current, query);
        }
        return built.get(condition);
    }

    private static List<? extends TextCondition> conditionChildren(TextCondition condition) {
        return switch (condition) {
            case org.indexact.expression.Term ignored -> List.of();
            case AnyOf any -> any.children();
            case Phrase phrase -> phrase.children();
            case Near near -> near.children();
            case And and -> and.children();
            case Or or -> or.children();
            case Not not -> List.of(not.child());
            case LexicalExpression ignored -> throw new AssertionError();
        };
    }

    private Query lexicalCandidateQuery(LexicalExpression expression) {
        return switch (expression) {
            case org.indexact.expression.Term term -> new TermQuery(new Term(
                    FieldSchema.BODY, queryAnalyzer.analyzeTerm(term.surface()).text()));
            case AnyOf anyOf -> lexicalDisjunction(anyOf.children());
            case Phrase phrase -> lexicalConjunction(phrase.children());
            case Near near -> lexicalConjunction(near.children());
        };
    }

    private Query conjunction(List<? extends TextCondition> children) {
        BooleanQuery.Builder query = new BooleanQuery.Builder();
        boolean constrained = false;
        for (TextCondition child : children) {
            if (child instanceof Not) {
                continue;
            }
            query.add(candidateQuery(child), BooleanClause.Occur.FILTER);
            constrained = true;
        }
        return constrained ? query.build() : new MatchAllDocsQuery();
    }

    private Query disjunction(List<? extends TextCondition> children) {
        if (children.stream().anyMatch(Not.class::isInstance)) {
            return new MatchAllDocsQuery();
        }
        BooleanQuery.Builder query = new BooleanQuery.Builder();
        children.forEach(child -> query.add(candidateQuery(child), BooleanClause.Occur.SHOULD));
        query.setMinimumNumberShouldMatch(1);
        return query.build();
    }

    private Query lexicalConjunction(List<LexicalExpression> children) {
        BooleanQuery.Builder query = new BooleanQuery.Builder();
        children.forEach(child -> query.add(
                lexicalCandidateQuery(child), BooleanClause.Occur.FILTER));
        return query.build();
    }

    private Query lexicalDisjunction(List<LexicalExpression> children) {
        BooleanQuery.Builder query = new BooleanQuery.Builder();
        children.forEach(child -> query.add(
                lexicalCandidateQuery(child), BooleanClause.Occur.SHOULD));
        query.setMinimumNumberShouldMatch(1);
        return query.build();
    }

    private void validateTerms(TextCondition condition) {
        java.util.ArrayDeque<TextCondition> pending = new java.util.ArrayDeque<>();
        pending.push(condition);
        while (!pending.isEmpty()) {
            TextCondition current = pending.pop();
            if (current instanceof org.indexact.expression.Term term) {
                queryAnalyzer.analyzeTerm(term.surface());
                continue;
            }
            List<? extends TextCondition> children = conditionChildren(current);
            for (int index = children.size() - 1; index >= 0; index--) {
                pending.push(children.get(index));
            }
        }
    }

    @Override
    public void close() {
        queryAnalyzer.close();
    }
}
