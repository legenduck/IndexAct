package org.indexact.protocol;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.indexact.analysis.ContractAnalyzer;
import org.indexact.analysis.UnicodeScalar;
import org.indexact.expression.And;
import org.indexact.expression.AnyOf;
import org.indexact.expression.AstMetrics;
import org.indexact.expression.Combine;
import org.indexact.expression.Expression;
import org.indexact.expression.LexicalExpression;
import org.indexact.expression.Near;
import org.indexact.expression.Not;
import org.indexact.expression.Or;
import org.indexact.expression.Phrase;
import org.indexact.expression.ScoringExpression;
import org.indexact.expression.Term;
import org.indexact.expression.TextCondition;
import org.indexact.expression.Weight;
import org.indexact.expression.WeightedTerm;
import org.indexact.protocol.json.JsonNumber;
import org.indexact.protocol.json.JsonParseException;
import org.indexact.protocol.json.StrictJsonParser;

/** Strict two-phase decoder for IndexAct requests. */
public final class RequestCodec {
    private static final Pattern PROTOCOL_VERSION = Pattern.compile("[1-9][0-9]*\\.[0-9]+");
    private static final Pattern SNAPSHOT_ID = Pattern.compile("snap-[0-9a-f]{64}");
    private static final Pattern SESSION_ID = Pattern.compile("sess-[0-9a-f]{32}");
    private static final Pattern STATE_HANDLE = Pattern.compile("(?:CORPUS|[sr][1-9][0-9]{0,15})");
    private static final Pattern READ_CURSOR = Pattern.compile("cur-[0-9a-f]{64}");
    private static final Pattern STATE_CURSOR = Pattern.compile("stc-[0-9a-f]{64}");
    private static final Pattern LINEAGE_ID = Pattern.compile("lin-[0-9a-f]{64}");
    private static final Pattern BINDING = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");
    private static final Set<String> TOP_OPS = Set.of(
            "OPEN_SESSION", "CLOSE_SESSION", "EXECUTE", "LIST_STATES",
            "GET_LINEAGE_NODE", "RELEASE_STATE");
    private static final Set<String> STATE_CREATING = Set.of(
            "FILTER", "INTERSECT", "UNION", "DIFFERENCE", "RANK", "TOPK",
            "RESTRICT", "AS_SET");
    private static final Set<String> BATCH_OPS = Set.of(
            "FILTER", "INTERSECT", "UNION", "DIFFERENCE", "COUNT", "COUNT_DOCS",
            "RANK", "TOPK", "RESTRICT", "AS_SET", "READ");

    public sealed interface OuterRequest
            permits OpenSession, CloseSession, Execute, ListStates, GetLineageNode, ReleaseState {}

    public record OpenSession(String protocolVersion, Object rawSnapshotId) implements OuterRequest {}

    public record CloseSession(String sessionId) implements OuterRequest {}

    public record Execute(String sessionId, Object rawOperations) implements OuterRequest {}

    public record ListStates(String sessionId, Object rawLimit, String after) implements OuterRequest {}

    public record GetLineageNode(String sessionId, String lineageId) implements OuterRequest {}

    public record ReleaseState(String sessionId, String handle) implements OuterRequest {}

    public record StateRef(String kind, String value) {}

    public sealed interface RegionSpec permits DocumentRegion, RangeRegion, AroundRegion {}

    public record DocumentRegion() implements RegionSpec {}

    public record RangeRegion(long start, long end) implements RegionSpec {}

    public enum OccurrenceSelectorKind { FIRST, NTH, ALL }

    public record OccurrenceSelector(OccurrenceSelectorKind kind, long nth) {}

    public record AroundRegion(
            LexicalExpression anchor, OccurrenceSelector selector, long before, long after)
            implements RegionSpec {}

    public record ReadBudget(long maxOutputCodepoints, long maxEvidenceCount) {}

    public sealed interface DocumentSelector permits AllDocuments, PageDocuments {}

    public record AllDocuments() implements DocumentSelector {}

    public record PageDocuments(long limit, String after) implements DocumentSelector {}

    public sealed interface OperationData
            permits FilterData, BinarySetData, CountData, CountDocsData, RankData, TopKData,
                    RestrictData, AsSetData, ReadData {}

    public record FilterData(StateRef target, TextCondition condition) implements OperationData {}

    public record BinarySetData(StateRef left, StateRef right) implements OperationData {}

    public record CountData(StateRef state) implements OperationData {}

    public record CountDocsData(StateRef state, TextCondition condition) implements OperationData {}

    public record RankData(StateRef target, ScoringExpression scoring) implements OperationData {}

    public record TopKData(StateRef target, long k) implements OperationData {}

    public record RestrictData(StateRef ranked, StateRef allowed) implements OperationData {}

    public record AsSetData(StateRef target) implements OperationData {}

    public record ReadData(
            boolean stateTarget,
            StateRef state,
            String docKey,
            DocumentSelector documents,
            RegionSpec region,
            ReadBudget budget)
            implements OperationData {}

    public record PreparedOperation(
            String op, String bind, OperationData data, List<Expression> expressions) {
        public PreparedOperation {
            expressions = List.copyOf(expressions);
        }
    }

    public record PreflightResult(
            List<PreparedOperation> operations, ProtocolFailure failure, Integer failureIndex) {
        public PreflightResult {
            operations = List.copyOf(operations);
        }
    }

    private RequestCodec() {}

    /** Decode only the version-stable outer shape and outer identifier scalars. */
    public static OuterRequest decodeOuter(byte[] body) throws JsonParseException, ProtocolFailure {
        Object parsed = StrictJsonParser.parse(body);
        Map<String, Object> request = object(parsed, false, "top-level request");
        if (!request.containsKey("op")) {
            throw failure(ErrorCode.MALFORMED_REQUEST, "top-level request is missing op");
        }
        Object rawOp = request.get("op");
        if (!(rawOp instanceof String op) || !TOP_OPS.contains(op)) {
            throw failure(ErrorCode.INVALID_ARGUMENT, "unknown top-level operation");
        }
        return switch (op) {
            case "OPEN_SESSION" -> {
                exactKeys(request, false, Set.of("op", "protocol_version", "snapshot_id"), Set.of());
                String version = identifier(request.get("protocol_version"), "ProtocolVersion", PROTOCOL_VERSION);
                yield new OpenSession(version, request.get("snapshot_id"));
            }
            case "CLOSE_SESSION" -> {
                exactKeys(request, false, Set.of("op", "session_id"), Set.of());
                yield new CloseSession(identifier(request.get("session_id"), "SessionId", SESSION_ID));
            }
            case "EXECUTE" -> {
                exactKeys(request, false, Set.of("op", "session_id", "ops"), Set.of());
                yield new Execute(
                        identifier(request.get("session_id"), "SessionId", SESSION_ID),
                        request.get("ops"));
            }
            case "LIST_STATES" -> {
                exactKeys(request, false, Set.of("op", "session_id", "limit"), Set.of("after"));
                String after = request.containsKey("after")
                        ? identifier(request.get("after"), "StateListCursor", STATE_CURSOR)
                        : null;
                yield new ListStates(
                        identifier(request.get("session_id"), "SessionId", SESSION_ID),
                        request.get("limit"), after);
            }
            case "GET_LINEAGE_NODE" -> {
                exactKeys(request, false, Set.of("op", "session_id", "lineage_id"), Set.of());
                yield new GetLineageNode(
                        identifier(request.get("session_id"), "SessionId", SESSION_ID),
                        identifier(request.get("lineage_id"), "LineageId", LINEAGE_ID));
            }
            case "RELEASE_STATE" -> {
                exactKeys(request, false, Set.of("op", "session_id", "handle"), Set.of());
                yield new ReleaseState(
                        identifier(request.get("session_id"), "SessionId", SESSION_ID),
                        stateHandle(request.get("handle")));
            }
            default -> throw new AssertionError(op);
        };
    }

    public static long parseListLimit(Object raw, ServiceLimits limits) throws ProtocolFailure {
        long value = integer(raw, "LIST_STATES limit", true);
        if (value > limits.maxPageLimit()) {
            throw limit("max_page_limit", limits.maxPageLimit(), value);
        }
        return value;
    }

    static String parseSnapshotId(Object raw) throws ProtocolFailure {
        return identifier(raw, "SnapshotId", SNAPSHOT_ID);
    }

    /** Complete static batch preflight. No state or binding lookup occurs here. */
    public static PreflightResult preflight(
            Object rawOperations, ServiceLimits limits, ContractAnalyzer analyzer) {
        if (!(rawOperations instanceof List<?> rawList)) {
            return new PreflightResult(
                    List.of(), failure(ErrorCode.MALFORMED_REQUEST, "ops must be an array"), null);
        }
        if ((long) rawList.size() > limits.maxBatchOperations()) {
            return new PreflightResult(
                    List.of(),
                    limit("max_batch_operations", limits.maxBatchOperations(), rawList.size()),
                    Math.toIntExact(limits.maxBatchOperations()));
        }
        ArrayList<PreparedOperation> result = new ArrayList<>();
        Set<String> bindings = new java.util.HashSet<>();
        long nodeTotal = 0;
        BigInteger branchTotal = BigInteger.ZERO;
        for (int index = 0; index < rawList.size(); index++) {
            try {
                // Owner ruling ISSUE-001: parseOperation includes request-declared
                // PAGE/READ limit checks, before any dynamic state/cursor resolution.
                PreparedOperation operation = parseOperation(rawList.get(index), limits);
                if (operation.bind() != null && !bindings.add(operation.bind())) {
                    throw failure(
                            ErrorCode.MALFORMED_OPERATION,
                            "duplicate binding",
                            Map.of("binding", operation.bind()));
                }
                result.add(operation);
                for (Expression expression : operation.expressions()) {
                    AstMetrics.Metrics metrics = AstMetrics.measure(expression);
                    try {
                        nodeTotal = Math.addExact(nodeTotal, metrics.nodeCount());
                    } catch (ArithmeticException overflow) {
                        nodeTotal = Long.MAX_VALUE;
                    }
                    branchTotal = branchTotal.add(metrics.gapChoiceBranchCost());
                    if (metrics.depth() > limits.maxAstDepth()) {
                        throw limit("max_ast_depth", limits.maxAstDepth(), metrics.depth());
                    }
                    if (nodeTotal > limits.maxAstNodes()) {
                        throw limit("max_ast_nodes", limits.maxAstNodes(), nodeTotal);
                    }
                    if (branchTotal.compareTo(BigInteger.valueOf(limits.maxGapChoiceBranches())) > 0) {
                        throw limit(
                                "max_gap_choice_branches",
                                limits.maxGapChoiceBranches(),
                                branchTotal.min(BigInteger.valueOf(ServiceLimits.MAX_SAFE_INTEGER)).longValue());
                    }
                    // READ anchor analysis is state/cursor-precedence-sensitive and therefore
                    // occurs dynamically after those references have been resolved.
                    if (!operation.op().equals("READ")) {
                        validateAnalyzedTerms(expression, limits, analyzer);
                    }
                }
            } catch (ProtocolFailure error) {
                return new PreflightResult(List.of(), error, index);
            } catch (IllegalArgumentException error) {
                return new PreflightResult(
                        List.of(), failure(ErrorCode.INVALID_ARGUMENT, boundedMessage(error)), index);
            }
        }
        return new PreflightResult(result, null, null);
    }

    private static PreparedOperation parseOperation(Object raw, ServiceLimits limits)
            throws ProtocolFailure {
        Map<String, Object> value = object(raw, true, "batch operation");
        if (!value.containsKey("op")) {
            throw failure(ErrorCode.MALFORMED_OPERATION, "batch operation is missing op");
        }
        Object rawOp = value.get("op");
        if (!(rawOp instanceof String op) || !BATCH_OPS.contains(op)) {
            throw failure(ErrorCode.MALFORMED_OPERATION, "unknown nested operation");
        }
        Set<String> required = switch (op) {
            case "FILTER" -> Set.of("op", "target", "condition");
            case "INTERSECT", "UNION", "DIFFERENCE" -> Set.of("op", "left", "right");
            case "COUNT" -> Set.of("op", "state");
            case "COUNT_DOCS" -> Set.of("op", "state", "condition");
            case "RANK" -> Set.of("op", "target", "scoring");
            case "TOPK" -> Set.of("op", "target", "k");
            case "RESTRICT" -> Set.of("op", "ranked", "allowed");
            case "AS_SET" -> Set.of("op", "target");
            case "READ" -> Set.of("op", "target", "region", "budget");
            default -> throw new AssertionError(op);
        };
        Set<String> optional = op.equals("READ")
                ? Set.of("documents")
                : STATE_CREATING.contains(op) ? Set.of("bind") : Set.of();
        exactKeys(value, true, required, optional);
        String bind = value.containsKey("bind") ? binding(value.get("bind")) : null;
        return switch (op) {
            case "FILTER" -> {
                TextCondition condition = parseCondition(value.get("condition"), limits);
                yield prepared(op, bind, new FilterData(stateRef(value.get("target")), condition), condition);
            }
            case "INTERSECT", "UNION", "DIFFERENCE" -> prepared(
                    op, bind,
                    new BinarySetData(stateRef(value.get("left")), stateRef(value.get("right"))));
            case "COUNT" -> prepared(op, null, new CountData(stateRef(value.get("state"))));
            case "COUNT_DOCS" -> {
                TextCondition condition = parseCondition(value.get("condition"), limits);
                yield prepared(op, null, new CountDocsData(stateRef(value.get("state")), condition), condition);
            }
            case "RANK" -> {
                ScoringExpression scoring = parseScoring(value.get("scoring"), limits);
                yield prepared(op, bind, new RankData(stateRef(value.get("target")), scoring), scoring);
            }
            case "TOPK" -> prepared(
                    op, bind,
                    new TopKData(stateRef(value.get("target")), integer(value.get("k"), "TOPK k", false)));
            case "RESTRICT" -> prepared(
                    op, bind,
                    new RestrictData(stateRef(value.get("ranked")), stateRef(value.get("allowed"))));
            case "AS_SET" -> prepared(op, bind, new AsSetData(stateRef(value.get("target"))));
            case "READ" -> parseRead(value, limits);
            default -> throw new AssertionError(op);
        };
    }

    private static PreparedOperation parseRead(Map<String, Object> value, ServiceLimits limits)
            throws ProtocolFailure {
        Map<String, Object> target = tagged(
                value.get("target"), Set.of("state", "doc_key"), "READ target");
        RegionSpec region = parseRegion(value.get("region"), limits);
        ReadBudget budget = parseBudget(value.get("budget"), limits);
        ArrayList<Expression> expressions = new ArrayList<>();
        if (region instanceof AroundRegion around) {
            expressions.add(around.anchor());
        }
        if (target.containsKey("state")) {
            if (!value.containsKey("documents")) {
                throw failure(ErrorCode.MALFORMED_OPERATION, "state READ requires documents");
            }
            if (!value.keySet().equals(Set.of("op", "target", "documents", "region", "budget"))) {
                throw failure(ErrorCode.MALFORMED_OPERATION, "READ has missing or unknown fields");
            }
            return new PreparedOperation(
                    "READ", null,
                    new ReadData(
                            true, stateRef(target.get("state")), null,
                            parseDocuments(value.get("documents"), limits), region, budget),
                    expressions);
        }
        if (value.containsKey("documents")) {
            throw failure(ErrorCode.MALFORMED_OPERATION, "DocKey READ forbids documents");
        }
        String docKey = scalarString(target.get("doc_key"), "DocKey");
        long bytes = docKey.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > limits.maxDocKeyUtf8Bytes()) {
            throw limit("max_doc_key_utf8_bytes", limits.maxDocKeyUtf8Bytes(), bytes);
        }
        return new PreparedOperation(
                "READ", null,
                new ReadData(false, null, docKey, null, region, budget), expressions);
    }

    private static PreparedOperation prepared(
            String op, String bind, OperationData data, Expression... expressions) {
        return new PreparedOperation(op, bind, data, List.of(expressions));
    }

    private static StateRef stateRef(Object raw) throws ProtocolFailure {
        Map<String, Object> value = tagged(raw, Set.of("handle", "binding"), "state reference");
        if (value.containsKey("handle")) {
            return new StateRef("handle", stateHandle(value.get("handle")));
        }
        return new StateRef("binding", binding(value.get("binding")));
    }

    private static LexicalExpression parseLexical(Object raw, ServiceLimits limits)
            throws ProtocolFailure {
        return (LexicalExpression) parseTextExpression(raw, limits, true);
    }

    private static TextCondition parseCondition(Object raw, ServiceLimits limits)
            throws ProtocolFailure {
        return parseTextExpression(raw, limits, false);
    }

    private record TextParseFrame(
            Object raw,
            boolean lexicalOnly,
            long depth,
            boolean expanded,
            String tag,
            Object metadata,
            int childCount) {}

    private record NearParameters(boolean ordered, long maxGaps) {}

    private static TextCondition parseTextExpression(
            Object raw, ServiceLimits limits, boolean lexicalOnly) throws ProtocolFailure {
        java.util.ArrayDeque<TextParseFrame> work = new java.util.ArrayDeque<>();
        ArrayList<TextCondition> built = new ArrayList<>();
        work.push(new TextParseFrame(raw, lexicalOnly, 1, false, null, null, 0));
        try {
            while (!work.isEmpty()) {
                TextParseFrame frame = work.pop();
                if (frame.expanded()) {
                    int start = built.size() - frame.childCount();
                    List<TextCondition> children = List.copyOf(built.subList(start, built.size()));
                    built.subList(start, built.size()).clear();
                    TextCondition value = switch (frame.tag()) {
                        case "term" -> new Term((String) frame.metadata());
                        case "any_of" -> new AnyOf(children.stream()
                                .map(LexicalExpression.class::cast).toList());
                        case "phrase" -> new Phrase(children.stream()
                                .map(LexicalExpression.class::cast).toList());
                        case "near" -> {
                            NearParameters parameters = (NearParameters) frame.metadata();
                            yield new Near(
                                    children.stream().map(LexicalExpression.class::cast).toList(),
                                    parameters.ordered(), parameters.maxGaps());
                        }
                        case "and" -> new And(children);
                        case "or" -> new Or(children);
                        case "not" -> new Not(children.getFirst());
                        default -> throw new AssertionError(frame.tag());
                    };
                    built.add(value);
                    continue;
                }
                if (!frame.lexicalOnly()
                        && frame.raw() instanceof Map<?, ?> rawMap
                        && (rawMap.containsKey("and")
                                || rawMap.containsKey("or")
                                || rawMap.containsKey("not"))) {
                    Map<String, Object> value = tagged(
                            frame.raw(), Set.of("and", "or", "not"), "condition");
                    String tag = value.containsKey("and")
                            ? "and" : value.containsKey("or") ? "or" : "not";
                    List<?> children;
                    if (tag.equals("not")) {
                        children = java.util.Collections.singletonList(value.get("not"));
                    } else {
                        children = array(value.get(tag), tag.toUpperCase() + " children");
                        if (children.size() < 2) {
                            throw failure(
                                    ErrorCode.MALFORMED_OPERATION,
                                    tag.toUpperCase() + " requires at least two children");
                        }
                    }
                    work.push(new TextParseFrame(
                            frame.raw(), false, frame.depth(), true, tag, null, children.size()));
                    for (int index = children.size() - 1; index >= 0; index--) {
                        work.push(new TextParseFrame(
                                children.get(index), false, frame.depth() + 1,
                                false, null, null, 0));
                    }
                    continue;
                }

                Map<String, Object> value = tagged(
                        frame.raw(), Set.of("term", "any_of", "phrase", "near"),
                        "lexical expression");
                String tag = value.containsKey("term")
                        ? "term"
                        : value.containsKey("any_of")
                                ? "any_of" : value.containsKey("phrase") ? "phrase" : "near";
                if (tag.equals("term")) {
                    String surface = lineageString(value.get("term"), limits, "TERM surface");
                    work.push(new TextParseFrame(
                            frame.raw(), true, frame.depth(), true, tag, surface, 0));
                    continue;
                }
                List<?> children;
                Object metadata = null;
                if (tag.equals("near")) {
                    Map<String, Object> near = object(value.get("near"), true, "NEAR body");
                    exactKeys(near, true, Set.of("children", "ordered", "max_gaps"), Set.of());
                    children = array(near.get("children"), "NEAR children");
                    if (!(near.get("ordered") instanceof Boolean ordered)) {
                        throw failure(ErrorCode.INVALID_ARGUMENT, "NEAR ordered must be boolean");
                    }
                    metadata = new NearParameters(
                            ordered, integer(near.get("max_gaps"), "NEAR max_gaps", false));
                } else {
                    children = array(value.get(tag), tag.toUpperCase() + " children");
                }
                if (children.size() < 2) {
                    throw failure(
                            ErrorCode.MALFORMED_OPERATION,
                            tag.toUpperCase() + " requires at least two children");
                }
                work.push(new TextParseFrame(
                        frame.raw(), true, frame.depth(), true, tag, metadata, children.size()));
                for (int index = children.size() - 1; index >= 0; index--) {
                    work.push(new TextParseFrame(
                            children.get(index), true, frame.depth() + 1,
                            false, null, null, 0));
                }
            }
        } catch (IllegalArgumentException error) {
            throw failure(ErrorCode.INVALID_ARGUMENT, boundedMessage(error));
        }
        if (built.size() != 1) {
            throw new AssertionError("expression parser did not produce one root");
        }
        return built.getFirst();
    }

    private static ScoringExpression parseScoring(Object raw, ServiceLimits limits)
            throws ProtocolFailure {
        Map<String, Object> value = tagged(raw, Set.of("term", "combine", "weight"), "scoring expression");
        try {
            if (value.containsKey("term")) {
                return new Term(lineageString(value.get("term"), limits, "TERM surface"));
            }
            if (value.containsKey("combine")) {
                List<?> children = array(value.get("combine"), "COMBINE children");
                if (children.isEmpty()) {
                    throw failure(ErrorCode.MALFORMED_OPERATION, "COMBINE requires at least one TERM");
                }
                List<Term> terms = new ArrayList<>();
                for (Object child : children) {
                    Map<String, Object> term = object(child, true, "COMBINE TERM");
                    exactKeys(term, true, Set.of("term"), Set.of());
                    terms.add(new Term(lineageString(term.get("term"), limits, "TERM surface")));
                }
                return new Combine(terms);
            }
            List<?> atoms = array(value.get("weight"), "WEIGHT atoms");
            if (atoms.isEmpty()) {
                throw failure(ErrorCode.MALFORMED_OPERATION, "WEIGHT requires at least one pair");
            }
            List<WeightedTerm> parsed = new ArrayList<>();
            for (Object rawAtom : atoms) {
                Map<String, Object> atom = object(rawAtom, true, "WEIGHT atom");
                exactKeys(atom, true, Set.of("weight", "term"), Set.of());
                parsed.add(new WeightedTerm(
                        nonNegativeBinary64(atom.get("weight"), "weight"),
                        new Term(lineageString(atom.get("term"), limits, "TERM surface"))));
            }
            return new Weight(parsed);
        } catch (IllegalArgumentException error) {
            throw failure(ErrorCode.INVALID_ARGUMENT, boundedMessage(error));
        }
    }

    private static RegionSpec parseRegion(Object raw, ServiceLimits limits) throws ProtocolFailure {
        Map<String, Object> value = tagged(raw, Set.of("document", "range", "around"), "region");
        if (value.containsKey("document")) {
            exactKeys(object(value.get("document"), true, "DOCUMENT"), true, Set.of(), Set.of());
            return new DocumentRegion();
        }
        if (value.containsKey("range")) {
            Map<String, Object> range = object(value.get("range"), true, "RANGE");
            exactKeys(range, true, Set.of("start", "end"), Set.of());
            long start = integer(range.get("start"), "RANGE start", false);
            long end = integer(range.get("end"), "RANGE end", false);
            return new RangeRegion(start, end);
        }
        Map<String, Object> around = object(value.get("around"), true, "AROUND");
        exactKeys(around, true, Set.of("anchor", "selector", "before", "after"), Set.of());
        Map<String, Object> selector = tagged(
                around.get("selector"), Set.of("first", "nth", "all"), "AROUND selector");
        OccurrenceSelector parsedSelector;
        if (selector.containsKey("first")) {
            exactKeys(object(selector.get("first"), true, "FIRST"), true, Set.of(), Set.of());
            parsedSelector = new OccurrenceSelector(OccurrenceSelectorKind.FIRST, 1);
        } else if (selector.containsKey("all")) {
            exactKeys(object(selector.get("all"), true, "ALL"), true, Set.of(), Set.of());
            parsedSelector = new OccurrenceSelector(OccurrenceSelectorKind.ALL, 0);
        } else {
            parsedSelector = new OccurrenceSelector(
                    OccurrenceSelectorKind.NTH,
                    integer(selector.get("nth"), "AROUND NTH", true));
        }
        return new AroundRegion(
                parseLexical(around.get("anchor"), limits),
                parsedSelector,
                integer(around.get("before"), "AROUND before", false),
                integer(around.get("after"), "AROUND after", false));
    }

    private static DocumentSelector parseDocuments(Object raw, ServiceLimits limits)
            throws ProtocolFailure {
        Map<String, Object> value = tagged(raw, Set.of("all_documents", "page"), "document selector");
        if (value.containsKey("all_documents")) {
            exactKeys(object(value.get("all_documents"), true, "ALL_DOCUMENTS"), true, Set.of(), Set.of());
            return new AllDocuments();
        }
        Map<String, Object> page = object(value.get("page"), true, "PAGE");
        exactKeys(page, true, Set.of("limit"), Set.of("after"));
        long limit = integer(page.get("limit"), "PAGE limit", true);
        if (limit > limits.maxPageLimit()) {
            throw limit("max_page_limit", limits.maxPageLimit(), limit);
        }
        String after = page.containsKey("after")
                ? identifier(page.get("after"), "ReadCursor", READ_CURSOR)
                : null;
        return new PageDocuments(limit, after);
    }

    private static ReadBudget parseBudget(Object raw, ServiceLimits limits) throws ProtocolFailure {
        Map<String, Object> value = object(raw, true, "ReadBudget");
        exactKeys(
                value, true,
                Set.of("max_output_codepoints", "max_evidence_count"), Set.of());
        long output = integer(value.get("max_output_codepoints"), "ReadBudget max_output_codepoints", false);
        long evidence = integer(value.get("max_evidence_count"), "ReadBudget max_evidence_count", false);
        if (output > limits.maxReadOutputCodepoints()) {
            throw limit("max_read_output_codepoints", limits.maxReadOutputCodepoints(), output);
        }
        if (evidence > limits.maxReadEvidenceCount()) {
            throw limit("max_read_evidence_count", limits.maxReadEvidenceCount(), evidence);
        }
        return new ReadBudget(output, evidence);
    }

    static void validateAnalyzedTerms(
            Expression expression, ServiceLimits limits, ContractAnalyzer analyzer)
            throws ProtocolFailure {
        for (Term term : terms(expression)) {
            final ContractAnalyzer.Token token;
            try {
                token = analyzer.analyzeTerm(term.surface());
            } catch (IllegalArgumentException error) {
                throw failure(ErrorCode.INVALID_ARGUMENT, boundedMessage(error));
            }
            if (token.utf8Length() > limits.maxAnalyzedTermUtf8Bytes()) {
                throw limit(
                        "max_analyzed_term_utf8_bytes",
                        limits.maxAnalyzedTermUtf8Bytes(), token.utf8Length());
            }
        }
    }

    private static List<Term> terms(Expression expression) {
        ArrayList<Term> result = new ArrayList<>();
        java.util.ArrayDeque<Expression> pending = new java.util.ArrayDeque<>();
        pending.push(expression);
        while (!pending.isEmpty()) {
            Expression current = pending.pop();
            if (current instanceof Term term) {
                result.add(term);
                continue;
            }
            List<? extends Expression> children = switch (current) {
                case AnyOf any -> any.children();
                case Phrase phrase -> phrase.children();
                case Near near -> near.children();
                case And and -> and.children();
                case Or or -> or.children();
                case Not not -> List.of(not.child());
                case Combine combine -> combine.terms();
                case Weight weight -> weight.atoms().stream().map(WeightedTerm::term).toList();
                case Term ignored -> throw new AssertionError();
            };
            for (int index = children.size() - 1; index >= 0; index--) {
                pending.push(children.get(index));
            }
        }
        return List.copyOf(result);
    }

    public static Map<String, Object> lexicalWire(LexicalExpression expression) {
        return textWire(expression);
    }

    public static Map<String, Object> conditionWire(TextCondition expression) {
        return textWire(expression);
    }

    private record WireFrame(TextCondition expression, boolean expanded) {}

    private static Map<String, Object> textWire(TextCondition expression) {
        java.util.ArrayDeque<WireFrame> work = new java.util.ArrayDeque<>();
        ArrayList<Map<String, Object>> built = new ArrayList<>();
        work.push(new WireFrame(expression, false));
        while (!work.isEmpty()) {
            WireFrame frame = work.pop();
            List<? extends TextCondition> children = textChildren(frame.expression());
            if (!frame.expanded()) {
                work.push(new WireFrame(frame.expression(), true));
                for (int index = children.size() - 1; index >= 0; index--) {
                    work.push(new WireFrame(children.get(index), false));
                }
                continue;
            }
            int start = built.size() - children.size();
            List<Map<String, Object>> childValues = List.copyOf(
                    built.subList(start, built.size()));
            built.subList(start, built.size()).clear();
            LinkedHashMap<String, Object> value = new LinkedHashMap<>();
            switch (frame.expression()) {
                case Term term -> value.put("term", term.surface());
                case AnyOf ignored -> value.put("any_of", childValues);
                case Phrase ignored -> value.put("phrase", childValues);
                case Near near -> {
                    LinkedHashMap<String, Object> body = new LinkedHashMap<>();
                    body.put("children", childValues);
                    body.put("ordered", near.ordered());
                    body.put("max_gaps", near.maxGaps());
                    value.put("near", body);
                }
                case And ignored -> value.put("and", childValues);
                case Or ignored -> value.put("or", childValues);
                case Not ignored -> value.put("not", childValues.getFirst());
                case LexicalExpression ignored -> throw new AssertionError();
            }
            built.add(value);
        }
        if (built.size() != 1) {
            throw new AssertionError("expression wire conversion did not produce one root");
        }
        return built.getFirst();
    }

    private static List<? extends TextCondition> textChildren(TextCondition expression) {
        return switch (expression) {
            case Term ignored -> List.of();
            case AnyOf any -> any.children();
            case Phrase phrase -> phrase.children();
            case Near near -> near.children();
            case And and -> and.children();
            case Or or -> or.children();
            case Not not -> List.of(not.child());
            case LexicalExpression ignored -> throw new AssertionError();
        };
    }

    public static Map<String, Object> scoringWire(ScoringExpression expression) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        switch (expression) {
            case Term term -> result.put("term", term.surface());
            case Combine combine -> result.put(
                    "combine", combine.terms().stream().map(term -> Map.of("term", term.surface())).toList());
            case Weight weight -> {
                List<Map<String, Object>> atoms = new ArrayList<>();
                for (WeightedTerm atom : weight.atoms()) {
                    LinkedHashMap<String, Object> item = new LinkedHashMap<>();
                    item.put("weight", atom.weight());
                    item.put("term", atom.term().surface());
                    atoms.add(item);
                }
                result.put("weight", atoms);
            }
        }
        return result;
    }

    private static Map<String, Object> tagged(Object raw, Set<String> tags, String label)
            throws ProtocolFailure {
        Map<String, Object> value = object(raw, true, label);
        if (value.size() != 1 || !tags.contains(value.keySet().iterator().next())) {
            throw failure(
                    ErrorCode.MALFORMED_OPERATION,
                    label + " must contain exactly one recognized tag");
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object raw, boolean nested, String label)
            throws ProtocolFailure {
        if (!(raw instanceof Map<?, ?> map)) {
            throw failure(
                    nested ? ErrorCode.MALFORMED_OPERATION : ErrorCode.MALFORMED_REQUEST,
                    label + " must be an object");
        }
        return (Map<String, Object>) map;
    }

    private static List<?> array(Object raw, String label) throws ProtocolFailure {
        if (!(raw instanceof List<?> list)) {
            throw failure(ErrorCode.MALFORMED_OPERATION, label + " must be an array");
        }
        return list;
    }

    private static void exactKeys(
            Map<String, Object> value, boolean nested, Set<String> required, Set<String> optional)
            throws ProtocolFailure {
        if (!value.keySet().containsAll(required)
                || !union(required, optional).containsAll(value.keySet())) {
            throw failure(
                    nested ? ErrorCode.MALFORMED_OPERATION : ErrorCode.MALFORMED_REQUEST,
                    "object has missing or unknown fields");
        }
    }

    private static Set<String> union(Set<String> left, Set<String> right) {
        java.util.HashSet<String> result = new java.util.HashSet<>(left);
        result.addAll(right);
        return result;
    }

    private static long integer(Object raw, String label, boolean positive) throws ProtocolFailure {
        if (!(raw instanceof JsonNumber number) || !number.isIntegerSyntax()) {
            throw failure(ErrorCode.INVALID_ARGUMENT, label + " must be a JSON integer");
        }
        final BigInteger exact;
        try {
            exact = number.exactInteger();
        } catch (ArithmeticException error) {
            throw failure(ErrorCode.INVALID_ARGUMENT, label + " must be a JSON integer");
        }
        BigInteger lower = positive ? BigInteger.ONE : BigInteger.ZERO;
        if (exact.compareTo(lower) < 0
                || exact.compareTo(BigInteger.valueOf(ServiceLimits.MAX_SAFE_INTEGER)) > 0) {
            throw failure(ErrorCode.INVALID_ARGUMENT, label + " is outside its numeric domain");
        }
        return exact.longValueExact();
    }

    private static double nonNegativeBinary64(Object raw, String label) throws ProtocolFailure {
        if (!(raw instanceof JsonNumber number)) {
            throw failure(ErrorCode.INVALID_ARGUMENT, label + " must be a JSON number");
        }
        final BigDecimal exact;
        try {
            exact = number.exactDecimal();
        } catch (NumberFormatException error) {
            throw failure(ErrorCode.INVALID_ARGUMENT, label + " is not a decimal number");
        }
        if (exact.signum() < 0) {
            throw failure(ErrorCode.INVALID_ARGUMENT, label + " must be non-negative");
        }
        double admitted = exact.doubleValue();
        if (!Double.isFinite(admitted)) {
            throw failure(ErrorCode.INVALID_ARGUMENT, label + " converts to a non-finite binary64");
        }
        return admitted == 0.0 ? 0.0 : admitted;
    }

    private static String stateHandle(Object raw) throws ProtocolFailure {
        String handle = identifier(raw, "StateHandle", STATE_HANDLE);
        if (!handle.equals("CORPUS")) {
            BigInteger ordinal = new BigInteger(handle.substring(1));
            if (ordinal.compareTo(BigInteger.valueOf(ServiceLimits.MAX_SAFE_INTEGER)) > 0) {
                throw failure(ErrorCode.INVALID_ARGUMENT, "StateHandle ordinal exceeds numeric domain");
            }
        }
        return handle;
    }

    private static String binding(Object raw) throws ProtocolFailure {
        String value = scalarString(raw, "BindingName");
        if (!BINDING.matcher(value).matches() || value.equals("CORPUS")) {
            throw failure(ErrorCode.INVALID_ARGUMENT, "invalid BindingName");
        }
        return value;
    }

    private static String lineageString(Object raw, ServiceLimits limits, String label)
            throws ProtocolFailure {
        String value = scalarString(raw, label);
        for (int codePoint : UnicodeScalar.toCodePoints(value)) {
            if (codePoint >= 0xFDD0 && codePoint <= 0xFDEF
                    || codePoint <= 0x10FFFF && (codePoint & 0xFFFF) >= 0xFFFE) {
                throw failure(ErrorCode.INVALID_ARGUMENT, label + " is not a LineageString");
            }
        }
        long byteLength = value.getBytes(StandardCharsets.UTF_8).length;
        if (byteLength > limits.maxLineageStringUtf8Bytes()) {
            throw limit("max_lineage_string_utf8_bytes", limits.maxLineageStringUtf8Bytes(), byteLength);
        }
        return value;
    }

    private static String identifier(Object raw, String label, Pattern pattern)
            throws ProtocolFailure {
        String value = scalarString(raw, label);
        if (!pattern.matcher(value).matches()) {
            throw failure(ErrorCode.INVALID_ARGUMENT, "invalid " + label);
        }
        return value;
    }

    private static String scalarString(Object raw, String label) throws ProtocolFailure {
        if (!(raw instanceof String value)) {
            throw failure(ErrorCode.INVALID_ARGUMENT, label + " must be a string");
        }
        try {
            UnicodeScalar.toCodePoints(value);
        } catch (IllegalArgumentException error) {
            throw failure(ErrorCode.INVALID_ARGUMENT, label + " must contain Unicode scalar values");
        }
        return value;
    }

    static ProtocolFailure limit(String name, long limit, long observed) {
        LinkedHashMap<String, Object> details = new LinkedHashMap<>();
        details.put("limit_name", name);
        details.put("limit", limit);
        details.put("observed_or_requested", observed);
        return failure(
                ErrorCode.RESOURCE_LIMIT_EXCEEDED,
                "request exceeds a published service limit", details);
    }

    static ProtocolFailure failure(ErrorCode code, String message) {
        return new ProtocolFailure(code, message);
    }

    static ProtocolFailure failure(ErrorCode code, String message, Map<String, Object> details) {
        return new ProtocolFailure(code, message, details);
    }

    private static String boundedMessage(Exception error) {
        return error.getMessage() == null ? "invalid argument" : error.getMessage();
    }
}
