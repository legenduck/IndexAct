package org.indexact.protocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.regex.Pattern;
import org.indexact.analysis.ContractAnalyzer;
import org.indexact.execution.Bm25Parameters;
import org.indexact.execution.RankOperations;
import org.indexact.execution.RankedState;
import org.indexact.execution.SetState;
import org.indexact.execution.StateOperations;
import org.indexact.execution.StateValue;
import org.indexact.expression.Term;
import org.indexact.index.Manifest;
import org.indexact.index.Snapshot;
import org.indexact.protocol.RequestCodec.AllDocuments;
import org.indexact.protocol.RequestCodec.AroundRegion;
import org.indexact.protocol.RequestCodec.BinarySetData;
import org.indexact.protocol.RequestCodec.CountData;
import org.indexact.protocol.RequestCodec.CountDocsData;
import org.indexact.protocol.RequestCodec.DocumentRegion;
import org.indexact.protocol.RequestCodec.FilterData;
import org.indexact.protocol.RequestCodec.PageDocuments;
import org.indexact.protocol.RequestCodec.PreparedOperation;
import org.indexact.protocol.RequestCodec.RangeRegion;
import org.indexact.protocol.RequestCodec.RankData;
import org.indexact.protocol.RequestCodec.ReadData;
import org.indexact.protocol.RequestCodec.RestrictData;
import org.indexact.protocol.RequestCodec.StateRef;
import org.indexact.protocol.RequestCodec.TopKData;
import org.indexact.protocol.json.CompactWireJson;
import org.indexact.protocol.json.CompactWireJson.ResponseTooLargeException;
import org.indexact.protocol.json.JsonParseException;
import org.indexact.read.AllSelector;
import org.indexact.read.Evidence;
import org.indexact.read.FirstSelector;
import org.indexact.read.NthSelector;
import org.indexact.read.PageSelection;
import org.indexact.read.ReadBudgetExceeded;
import org.indexact.read.ReadEngine;
import org.indexact.read.ReadResult;
import org.indexact.read.ReadSemanticException;
import org.indexact.read.ReadSuccess;
import org.indexact.read.StateDocumentSelection;
import org.indexact.session.IdGenerator;
import org.indexact.session.SecureIdGenerator;
import org.indexact.session.SessionManager;
import org.indexact.session.SessionRecord;
import org.indexact.session.SnapshotManager;
import org.indexact.state.CanonicalLineageNode;
import org.indexact.state.LineageGraph;
import org.indexact.state.StateMetadata;
import org.indexact.state.StateRecord;

/** Thread-safe byte-exact in-process IndexAct service boundary. */
public final class ProtocolService {
    private static final Pattern HEX32 = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");

    private final ServiceLimits limits;
    private final SnapshotManager snapshots;
    private final SessionManager sessions = new SessionManager();
    private final IdGenerator ids;
    private final Bm25Parameters bm25;

    public ProtocolService(Collection<Snapshot> snapshots) {
        this(snapshots, ServiceLimits.defaults(), new SecureIdGenerator());
    }

    public ProtocolService(Collection<Snapshot> snapshots, Bm25Parameters bm25) {
        this(snapshots, ServiceLimits.defaults(), new SecureIdGenerator(), bm25);
    }

    public ProtocolService(
            Collection<Snapshot> snapshots, ServiceLimits limits, IdGenerator ids) {
        this(snapshots, limits, ids, Bm25Parameters.WIKIPEDIA_18);
    }

    public ProtocolService(
            Collection<Snapshot> snapshots, ServiceLimits limits, IdGenerator ids,
            Bm25Parameters bm25) {
        this.limits = java.util.Objects.requireNonNull(limits, "limits");
        this.snapshots = new SnapshotManager(java.util.Objects.requireNonNull(snapshots, "snapshots"));
        this.ids = java.util.Objects.requireNonNull(ids, "ids");
        this.bm25 = java.util.Objects.requireNonNull(bm25, "bm25");
        validateResponseProfile();
    }

    public void registerSnapshot(Snapshot snapshot) {
        snapshots.register(snapshot);
    }

    public ServiceLimits serviceLimits() {
        return limits;
    }

    public List<String> activeSessionIds() {
        return sessions.activeSessionIds();
    }

    /** Process one identity-encoded request body and return the exact CompactWireJSON body. */
    public byte[] handle(byte[] body) {
        if (body == null) {
            throw new NullPointerException("body");
        }
        if ((long) body.length > limits.maxRequestBytes()) {
            return ResultCodec.error(
                    RequestCodec.limit("max_request_bytes", limits.maxRequestBytes(), body.length), limits);
        }
        try {
            return dispatch(RequestCodec.decodeOuter(body));
        } catch (JsonParseException error) {
            return ResultCodec.error(
                    RequestCodec.failure(ErrorCode.MALFORMED_REQUEST, "malformed request"), limits);
        } catch (ProtocolFailure error) {
            return ResultCodec.error(error, limits);
        } catch (CancellationException error) {
            // The execution has already unwound without publishing its staged session. Clear the
            // carrier interrupt so the HTTP adapter can finish the bounded diagnostic response.
            Thread.interrupted();
            return ResultCodec.error(
                    RequestCodec.failure(ErrorCode.INTERNAL_ERROR, "request execution cancelled"),
                    limits);
        } catch (Exception error) {
            return ResultCodec.error(
                    RequestCodec.failure(ErrorCode.INTERNAL_ERROR, "unexpected server failure"), limits);
        }
    }

    /** Bounded transport-layer response after the received body has proven over-cap. */
    public byte[] requestTooLarge(long observedLowerBound) {
        return ResultCodec.error(
                RequestCodec.limit(
                        "max_request_bytes", limits.maxRequestBytes(), observedLowerBound), limits);
    }

    /** Bounded response for an invalid HTTP method or content encoding. */
    public byte[] malformedTransportRequest(String message) {
        return ResultCodec.error(
                RequestCodec.failure(ErrorCode.MALFORMED_REQUEST, message), limits);
    }

    private byte[] dispatch(RequestCodec.OuterRequest request) throws Exception {
        return switch (request) {
            case RequestCodec.OpenSession open -> open(open);
            case RequestCodec.CloseSession close -> close(close.sessionId());
            case RequestCodec.Execute execute -> withSession(
                    execute.sessionId(), session -> execute(session, execute.rawOperations()));
            case RequestCodec.ListStates list -> withSession(
                    list.sessionId(), session -> listStates(session, list));
            case RequestCodec.GetLineageNode lineage -> withSession(
                    lineage.sessionId(), session -> lineageNode(session, lineage.lineageId()));
            case RequestCodec.ReleaseState release -> withSession(
                    release.sessionId(), session -> release(session, release.handle()));
        };
    }

    @FunctionalInterface
    private interface SessionAction {
        byte[] apply(SessionRecord session) throws Exception;
    }

    private byte[] withSession(String sessionId, SessionAction action) throws Exception {
        SessionRecord session = sessions.get(sessionId);
        if (session == null) {
            throw RequestCodec.failure(
                    ErrorCode.SESSION_NOT_FOUND, "unknown session", Map.of("session_id", sessionId));
        }
        try {
            session.requestLock().lockInterruptibly();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new CancellationException("request cancelled while waiting for session");
        }
        try {
            if (!sessions.isCurrent(sessionId, session)) {
                throw RequestCodec.failure(
                        ErrorCode.SESSION_NOT_FOUND, "unknown session", Map.of("session_id", sessionId));
            }
            return action.apply(session);
        } finally {
            session.requestLock().unlock();
        }
    }

    private byte[] open(RequestCodec.OpenSession request) throws Exception {
        if (!request.protocolVersion().equals("3.4")) {
            throw RequestCodec.failure(
                    ErrorCode.UNSUPPORTED_PROTOCOL_VERSION,
                    "unsupported protocol version",
                    Map.of("protocol_version", request.protocolVersion()));
        }
        String snapshotId = RequestCodec.parseSnapshotId(request.rawSnapshotId());
        Snapshot snapshot = snapshots.get(snapshotId);
        if (snapshot == null) {
            throw RequestCodec.failure(
                    ErrorCode.SNAPSHOT_NOT_FOUND,
                    "unknown snapshot", Map.of("snapshot_id", snapshotId));
        }
        requireCompatible(snapshot);
        synchronized (sessions) {
            String sessionId = mint("sess-", 16, HEX32, sessions::contains);
            CanonicalLineageNode rootLineage = new CanonicalLineageNode("CORPUS", List.of(), Map.of());
            if (rootLineage.lineageNodeBytes() > limits.maxLineageNodeBytes()
                    || limits.maxRetainedLineageNodes() < 1
                    || rootLineage.lineageNodeBytes() > limits.maxRetainedLineageBytes()) {
                throw RequestCodec.failure(
                        ErrorCode.INTERNAL_ERROR, "service profile cannot admit CORPUS lineage");
            }
            SetState corpus;
            try (StateOperations operations = new StateOperations(snapshot)) {
                corpus = operations.corpus();
            }
            StateMetadata metadata = new StateMetadata(
                    "CORPUS", "set", snapshot.snapshotId(), corpus.cardinality(),
                    rootLineage.id(), "CORPUS");
            StateRecord record = new StateRecord(
                    "istate-" + sessionId.substring(5) + "-0",
                    metadata,
                    corpus,
                    0,
                    List.of(),
                    () -> {
                        try (StateOperations operations = new StateOperations(snapshot)) {
                            return operations.corpus();
                        }
                    });
            SessionRecord session = new SessionRecord(
                    sessionId, "3.4", snapshot, limits, record, rootLineage);
            LinkedHashMap<String, Object> response = new LinkedHashMap<>();
            response.put("protocol_version", "3.4");
            response.put("session_id", sessionId);
            response.put("corpus", metadata.toWire());
            response.put("service_limits", limits.toWire());
            byte[] encoded;
            try {
                encoded = ResultCodec.encode(response, limits);
            } catch (ResponseTooLargeException overCap) {
                return responseCapError();
            }
            if (!sessions.publishIfAbsent(session)) {
                throw new IllegalStateException("identifier collision after session admission");
            }
            return encoded;
        }
    }

    private byte[] close(String sessionId) throws Exception {
        LinkedHashMap<String, Object> response = new LinkedHashMap<>();
        response.put("closed", true);
        byte[] encoded = ResultCodec.encode(response, limits);
        SessionRecord session = sessions.get(sessionId);
        if (session == null) {
            return encoded;
        }
        session.requestLock().lock();
        try {
            sessions.removeIfCurrent(sessionId, session);
        } finally {
            session.requestLock().unlock();
        }
        return encoded;
    }

    private byte[] execute(SessionRecord session, Object rawOperations) {
        RequestCodec.PreflightResult preflight;
        try (ContractAnalyzer analyzer = new ContractAnalyzer()) {
            preflight = RequestCodec.preflight(rawOperations, session.limits(), analyzer);
        }
        if (preflight.failure() != null) {
            if (preflight.failureIndex() == null) {
                return ResultCodec.error(preflight.failure(), limits);
            }
            return encodeStaticTrace(preflight.failure(), preflight.failureIndex());
        }

        SessionRecord staged = session.stage();
        Map<String, String> bindings = new HashMap<>();
        ArrayList<Map<String, Object>> completed = new ArrayList<>();
        ProtocolFailure dynamicFailure = null;
        Integer dynamicIndex = null;
        for (int index = 0; index < preflight.operations().size(); index++) {
            PreparedOperation operation = preflight.operations().get(index);
            SessionRecord operationStage = staged.stage();
            Map<String, String> operationBindings = new HashMap<>(bindings);
            try {
                Object result = executeOperation(operationStage, operation, operationBindings);
                LinkedHashMap<String, Object> completedItem = new LinkedHashMap<>();
                completedItem.put("index", index);
                completedItem.put("result", result);
                completed.add(completedItem);
                staged = operationStage;
                bindings = operationBindings;
            } catch (ProtocolFailure error) {
                dynamicFailure = error;
                dynamicIndex = index;
                break;
            } catch (java.io.InterruptedIOException error) {
                Thread.currentThread().interrupt();
                throw new CancellationException("request execution interrupted");
            } catch (CancellationException error) {
                throw error;
            } catch (Exception error) {
                dynamicFailure = RequestCodec.failure(
                        ErrorCode.INTERNAL_ERROR, "unexpected backend failure");
                dynamicIndex = index;
                break;
            }
        }
        LinkedHashMap<String, Object> trace = new LinkedHashMap<>();
        trace.put("completed", completed);
        if (dynamicFailure != null) {
            trace.put("failed_operation_index", dynamicIndex);
            ProtocolFailure bounded = ResultCodec.bounded(dynamicFailure, limits);
            trace.put("error", ResultCodec.errorValue(bounded).get("error"));
        }
        final byte[] encoded;
        try {
            encoded = ResultCodec.encode(trace, limits);
        } catch (ResponseTooLargeException overCap) {
            return responseCapError();
        }
        session.publish(staged);
        return encoded;
    }

    private byte[] encodeStaticTrace(ProtocolFailure failure, int index) {
        LinkedHashMap<String, Object> trace = new LinkedHashMap<>();
        trace.put("completed", List.of());
        trace.put("failed_operation_index", index);
        ProtocolFailure bounded = ResultCodec.bounded(failure, limits);
        trace.put("error", ResultCodec.errorValue(bounded).get("error"));
        try {
            return ResultCodec.encode(trace, limits);
        } catch (ResponseTooLargeException overCap) {
            return responseCapError();
        }
    }

    private Object executeOperation(
            SessionRecord staged, PreparedOperation operation, Map<String, String> bindings)
            throws Exception {
        List<StateRecord> parents;
        StateValue value;
        switch (operation.op()) {
            case "FILTER" -> {
                FilterData data = (FilterData) operation.data();
                StateRecord parent = resolve(staged, data.target(), bindings);
                requireType(parent, "set");
                parents = List.of(parent);
                value = computeValue(staged.snapshot(), operation, parents);
            }
            case "INTERSECT", "UNION", "DIFFERENCE" -> {
                BinarySetData data = (BinarySetData) operation.data();
                StateRecord left = resolve(staged, data.left(), bindings);
                StateRecord right = resolve(staged, data.right(), bindings);
                requireType(left, "set");
                requireType(right, "set");
                parents = List.of(left, right);
                value = computeValue(staged.snapshot(), operation, parents);
            }
            case "COUNT" -> {
                StateRecord state = resolve(staged, ((CountData) operation.data()).state(), bindings);
                return countResult(state.metadata().cardinality());
            }
            case "COUNT_DOCS" -> {
                CountDocsData data = (CountDocsData) operation.data();
                StateRecord parent = resolve(staged, data.state(), bindings);
                StateValue parentValue = physical(parent);
                SetState membership;
                if (parentValue instanceof SetState set) {
                    membership = set;
                } else {
                    try (RankOperations ranks = new RankOperations(staged.snapshot(), bm25)) {
                        membership = ranks.asSet((RankedState) parentValue);
                    }
                }
                long count;
                try (StateOperations operations = new StateOperations(staged.snapshot())) {
                    count = operations.countDocs(membership, data.condition());
                }
                return countResult(count);
            }
            case "RANK" -> {
                RankData data = (RankData) operation.data();
                StateRecord parent = resolve(staged, data.target(), bindings);
                requireType(parent, "set");
                parents = List.of(parent);
                try {
                    value = computeValue(staged.snapshot(), operation, parents);
                } catch (IllegalArgumentException error) {
                    throw RequestCodec.failure(ErrorCode.INVALID_ARGUMENT, safeMessage(error));
                }
            }
            case "TOPK" -> {
                TopKData data = (TopKData) operation.data();
                StateRecord parent = resolve(staged, data.target(), bindings);
                requireType(parent, "ranked");
                parents = List.of(parent);
                value = computeValue(staged.snapshot(), operation, parents);
            }
            case "RESTRICT" -> {
                RestrictData data = (RestrictData) operation.data();
                StateRecord ranked = resolve(staged, data.ranked(), bindings);
                StateRecord allowed = resolve(staged, data.allowed(), bindings);
                requireType(ranked, "ranked");
                requireType(allowed, "set");
                parents = List.of(ranked, allowed);
                value = computeValue(staged.snapshot(), operation, parents);
            }
            case "AS_SET" -> {
                RequestCodec.AsSetData data = (RequestCodec.AsSetData) operation.data();
                StateRecord parent = resolve(staged, data.target(), bindings);
                requireType(parent, "ranked");
                parents = List.of(parent);
                value = computeValue(staged.snapshot(), operation, parents);
            }
            case "READ" -> {
                return executeRead(staged, (ReadData) operation.data(), bindings);
            }
            default -> throw new AssertionError(operation.op());
        }
        StateRecord created = createState(staged, operation, value, parents);
        if (operation.bind() != null) {
            bindings.put(operation.bind(), created.metadata().handle());
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("state", created.metadata().toWire());
        return result;
    }

    private StateValue computeValue(
            Snapshot snapshot, PreparedOperation operation, List<StateRecord> parents)
            throws Exception {
        return switch (operation.op()) {
            case "FILTER" -> {
                FilterData data = (FilterData) operation.data();
                try (StateOperations operations = new StateOperations(snapshot)) {
                    yield operations.filter((SetState) physical(parents.get(0)), data.condition());
                }
            }
            case "INTERSECT", "UNION", "DIFFERENCE" -> {
                try (StateOperations operations = new StateOperations(snapshot)) {
                    SetState left = (SetState) physical(parents.get(0));
                    SetState right = (SetState) physical(parents.get(1));
                    yield switch (operation.op()) {
                        case "INTERSECT" -> operations.intersect(left, right);
                        case "UNION" -> operations.union(left, right);
                        case "DIFFERENCE" -> operations.difference(left, right);
                        default -> throw new AssertionError();
                    };
                }
            }
            case "RANK" -> {
                RankData data = (RankData) operation.data();
                try (RankOperations operations = new RankOperations(snapshot, bm25)) {
                    yield operations.rank((SetState) physical(parents.get(0)), data.scoring());
                }
            }
            case "TOPK" -> {
                TopKData data = (TopKData) operation.data();
                try (RankOperations operations = new RankOperations(snapshot, bm25)) {
                    yield operations.topK((RankedState) physical(parents.get(0)), data.k());
                }
            }
            case "RESTRICT" -> {
                try (RankOperations operations = new RankOperations(snapshot, bm25)) {
                    yield operations.restrict(
                            (RankedState) physical(parents.get(0)),
                            (SetState) physical(parents.get(1)));
                }
            }
            case "AS_SET" -> {
                try (RankOperations operations = new RankOperations(snapshot, bm25)) {
                    yield operations.asSet((RankedState) physical(parents.get(0)));
                }
            }
            default -> throw new AssertionError("not a state-producing operation");
        };
    }

    private StateRecord createState(
            SessionRecord staged,
            PreparedOperation operation,
            StateValue value,
            List<StateRecord> parents)
            throws ProtocolFailure {
        if ((long) staged.states().size() - 1 >= staged.limits().maxActiveNonrootStates()) {
            throw RequestCodec.limit(
                    "max_active_nonroot_states",
                    staged.limits().maxActiveNonrootStates(), staged.states().size());
        }
        if (staged.handleSequence() >= ServiceLimits.MAX_SAFE_INTEGER) {
            throw RequestCodec.failure(
                    ErrorCode.RESOURCE_LIMIT_EXCEEDED, "StateHandle ordinal exhausted");
        }
        List<String> parentLineage = parents.stream()
                .map(parent -> parent.metadata().lineageId()).toList();
        CanonicalLineageNode node;
        try {
            node = new CanonicalLineageNode(operation.op(), parentLineage, lineageArgs(operation));
        } catch (IllegalArgumentException error) {
            throw RequestCodec.failure(ErrorCode.INVALID_ARGUMENT, safeMessage(error));
        }
        if (node.lineageNodeBytes() > staged.limits().maxLineageNodeBytes()) {
            throw RequestCodec.limit(
                    "max_lineage_node_bytes",
                    staged.limits().maxLineageNodeBytes(), node.lineageNodeBytes());
        }
        Map<String, CanonicalLineageNode> prospective = new HashMap<>(staged.lineageNodes());
        prospective.put(node.id(), node);
        ArrayList<String> roots = staged.states().values().stream()
                .map(record -> record.metadata().lineageId())
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        roots.add(node.id());
        final LineageGraph.Analysis reachability;
        try {
            reachability = LineageGraph.analyze(roots, prospective);
        } catch (ArithmeticException error) {
            throw RequestCodec.failure(
                    ErrorCode.RESOURCE_LIMIT_EXCEEDED, "retained lineage byte count overflow");
        }
        if (reachability.nodeCount() > staged.limits().maxRetainedLineageNodes()) {
            throw RequestCodec.limit(
                    "max_retained_lineage_nodes",
                    staged.limits().maxRetainedLineageNodes(), reachability.nodeCount());
        }
        if (reachability.totalBytes() > staged.limits().maxRetainedLineageBytes()) {
            throw RequestCodec.limit(
                    "max_retained_lineage_bytes",
                    staged.limits().maxRetainedLineageBytes(), reachability.totalBytes());
        }
        if (reachability.maxDepth() > staged.limits().maxLineageDepth()) {
            throw RequestCodec.limit(
                    "max_lineage_depth",
                    staged.limits().maxLineageDepth(), reachability.maxDepth());
        }

        long ordinal = staged.handleSequence() + 1;
        long creation = staged.creationSequence() + 1;
        long internal = staged.internalSequence() + 1;
        boolean set = value instanceof SetState;
        String handle = (set ? "s" : "r") + ordinal;
        StateMetadata metadata = new StateMetadata(
                handle,
                set ? "set" : "ranked",
                staged.snapshot().snapshotId(),
                value.cardinality(),
                node.id(),
                operation.op());
        StateRecord record = new StateRecord(
                "istate-" + staged.sessionId().substring(5) + "-" + internal,
                metadata,
                value,
                creation,
                parents,
                () -> computeValue(staged.snapshot(), operation, parents));
        staged.handleSequence(ordinal);
        staged.creationSequence(creation);
        staged.internalSequence(internal);
        staged.lineageNodes().putAll(prospective);
        staged.states().put(handle, record);
        return record;
    }

    private static Map<String, Object> lineageArgs(PreparedOperation operation) {
        LinkedHashMap<String, Object> args = new LinkedHashMap<>();
        switch (operation.op()) {
            case "FILTER" -> args.put(
                    "condition", RequestCodec.conditionWire(((FilterData) operation.data()).condition()));
            case "RANK" -> args.put(
                    "scoring", RequestCodec.scoringWire(((RankData) operation.data()).scoring()));
            case "TOPK" -> args.put("k", ((TopKData) operation.data()).k());
            case "INTERSECT", "UNION", "DIFFERENCE", "RESTRICT", "AS_SET" -> { }
            default -> throw new AssertionError(operation.op());
        }
        return args;
    }

    private Object executeRead(SessionRecord staged, ReadData data, Map<String, String> bindings)
            throws Exception {
        org.indexact.read.ReadBudget budget = new org.indexact.read.ReadBudget(
                data.budget().maxOutputCodepoints(), data.budget().maxEvidenceCount());
        try (ReadEngine engine = staged.snapshot().newReadEngine()) {
            ReadResult result;
            if (!data.stateTarget()) {
                validateReadAnchor(data, staged);
                // DocKey lookup deliberately precedes the later RANGE relation check.
                try {
                    staged.snapshot().rawCodePointLength(data.docKey());
                } catch (NoSuchElementException error) {
                    throw RequestCodec.failure(
                            ErrorCode.DOCUMENT_NOT_FOUND,
                            "unknown DocKey", Map.of("doc_key", data.docKey()));
                }
                if (data.region() instanceof RangeRegion range && range.start() > range.end()) {
                    throw RequestCodec.failure(
                            ErrorCode.INVALID_ARGUMENT, "RANGE start must not exceed end");
                }
                org.indexact.read.ReadRegion region = readRegion(data.region());
                result = engine.readDocument(data.docKey(), region, budget);
            } else {
                StateRecord record = resolve(staged, data.state(), bindings);
                long pageStart = resolveReadCursor(staged, record, data.documents());
                validateReadAnchor(data, staged);
                if (data.region() instanceof RangeRegion) {
                    throw RequestCodec.failure(
                            ErrorCode.TYPE_MISMATCH,
                            "RANGE is valid only for a direct DocKey target");
                }
                org.indexact.read.ReadRegion region = readRegion(data.region());
                StateValue value = physical(record);
                StateDocumentSelection selection = selectDocuments(
                        staged, record, value, data.documents(), pageStart);
                result = value instanceof SetState set
                        ? engine.readSet(set, selection, region, budget)
                        : engine.readRanked((RankedState) value, selection, region, budget);
            }
            return readResultWire(result);
        } catch (ReadSemanticException error) {
            ErrorCode code = switch (error.reason()) {
                case DOCUMENT_NOT_FOUND -> ErrorCode.DOCUMENT_NOT_FOUND;
                case INVALID_STATE_REF -> ErrorCode.INVALID_STATE_REF;
                case INVALID_ARGUMENT -> ErrorCode.INVALID_ARGUMENT;
                case TYPE_MISMATCH -> ErrorCode.TYPE_MISMATCH;
            };
            throw RequestCodec.failure(code, error.getMessage());
        } catch (NoSuchElementException error) {
            throw RequestCodec.failure(
                    ErrorCode.DOCUMENT_NOT_FOUND, "unknown DocKey", Map.of("doc_key", data.docKey()));
        }
    }

    private StateDocumentSelection selectDocuments(
            SessionRecord staged,
            StateRecord target,
            StateValue value,
            RequestCodec.DocumentSelector selector,
            long pageStart)
            throws ProtocolFailure {
        List<String> traversal = value instanceof SetState set
                ? set.orderedMembers()
                : ((RankedState) value).order();
        if (selector instanceof AllDocuments) {
            if (target.metadata().cardinality() > staged.limits().maxReadSelectedDocs()) {
                throw RequestCodec.limit(
                        "max_read_selected_docs",
                        staged.limits().maxReadSelectedDocs(), target.metadata().cardinality());
            }
            return org.indexact.read.AllDocuments.INSTANCE;
        }
        PageDocuments page = (PageDocuments) selector;
        long start = pageStart;
        long end = Math.min(Math.addExact(start, page.limit()), traversal.size());
        List<String> selected = List.copyOf(traversal.subList(Math.toIntExact(start), Math.toIntExact(end)));
        if ((long) selected.size() > staged.limits().maxReadSelectedDocs()) {
            throw RequestCodec.limit(
                    "max_read_selected_docs",
                    staged.limits().maxReadSelectedDocs(), selected.size());
        }
        String next = end < traversal.size()
                ? mintReadCursor(staged, target.metadata().handle(), end)
                : null;
        return new PageSelection(selected, next);
    }

    /** Cursor validity precedes anchor TERM analysis, region typing, and selection caps. */
    private static long resolveReadCursor(
            SessionRecord staged,
            StateRecord target,
            RequestCodec.DocumentSelector selector)
            throws ProtocolFailure {
        if (selector instanceof AllDocuments) {
            return -1;
        }
        PageDocuments page = (PageDocuments) selector;
        if (page.after() == null) {
            return 0;
        }
        SessionRecord.ReadCursor cursor = staged.readCursors().get(page.after());
        if (cursor == null || !cursor.targetHandle().equals(target.metadata().handle())) {
            throw RequestCodec.failure(ErrorCode.INVALID_ARGUMENT, "invalid ReadCursor");
        }
        return cursor.position();
    }

    private static void validateReadAnchor(ReadData data, SessionRecord staged)
            throws ProtocolFailure {
        if (data.region() instanceof AroundRegion around) {
            try (ContractAnalyzer analyzer = new ContractAnalyzer()) {
                RequestCodec.validateAnalyzedTerms(around.anchor(), staged.limits(), analyzer);
            }
        }
    }

    private org.indexact.read.ReadRegion readRegion(RequestCodec.RegionSpec region) {
        return switch (region) {
            case DocumentRegion ignored -> org.indexact.read.DocumentRegion.INSTANCE;
            case RangeRegion range -> new org.indexact.read.RangeRegion(range.start(), range.end());
            case AroundRegion around -> new org.indexact.read.AroundRegion(
                    around.anchor(),
                    switch (around.selector().kind()) {
                        case FIRST -> FirstSelector.INSTANCE;
                        case NTH -> new NthSelector(around.selector().nth());
                        case ALL -> AllSelector.INSTANCE;
                    },
                    around.before(), around.after());
        };
    }

    private static Map<String, Object> readResultWire(ReadResult result) {
        LinkedHashMap<String, Object> response = new LinkedHashMap<>();
        if (result instanceof ReadSuccess success) {
            response.put("status", "success");
            response.put("target_docs", success.targetDocs());
            response.put("selected_docs", success.selectedDocs());
            response.put("region_docs", success.regionDocs());
            response.put("evidence_count", success.evidenceCount());
            response.put("required_output_codepoints", success.requiredOutputCodePoints());
            if (success.page() != null) {
                response.put("page", pageWire(success.page()));
            }
            response.put("evidence", success.evidence().stream()
                    .map(ProtocolService::evidenceWire).toList());
            return response;
        }
        ReadBudgetExceeded exceeded = (ReadBudgetExceeded) result;
        response.put("status", "budget_exceeded");
        LinkedHashMap<String, Object> budget = new LinkedHashMap<>();
        budget.put("max_output_codepoints", exceeded.budget().maxOutputCodePoints());
        budget.put("max_evidence_count", exceeded.budget().maxEvidenceCount());
        response.put("budget", budget);
        response.put("exceeded_limits", exceeded.exceededLimits().stream()
                .map(limit -> limit.wireName()).toList());
        response.put("target_docs", exceeded.targetDocs());
        response.put("selected_docs", exceeded.selectedDocs());
        response.put("evaluation_complete", exceeded.evaluationComplete());
        if (exceeded.page() != null) {
            response.put("page", pageWire(exceeded.page()));
        }
        response.put(
                "observed_output_codepoints_lower_bound",
                exceeded.observedOutputCodePointsLowerBound());
        response.put("evaluated_docs_count", exceeded.evaluatedDocsCount());
        response.put("region_docs_lower_bound", exceeded.regionDocsLowerBound());
        response.put("evidence_count_lower_bound", exceeded.evidenceCountLowerBound());
        if (exceeded.evaluationComplete()) {
            response.put("required_output_codepoints", exceeded.requiredOutputCodePoints());
            response.put("region_docs", exceeded.regionDocs());
            response.put("evidence_count", exceeded.evidenceCount());
        }
        return response;
    }

    private static Map<String, Object> pageWire(PageSelection page) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("doc_keys", page.docKeys());
        if (page.nextCursor() != null) {
            result.put("next_cursor", page.nextCursor());
        }
        return result;
    }

    private static Map<String, Object> evidenceWire(Evidence evidence) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("snapshot_id", evidence.snapshotId());
        result.put("doc_key", evidence.docKey());
        result.put("start", evidence.start());
        result.put("end", evidence.end());
        result.put("text", evidence.text());
        return result;
    }

    private byte[] listStates(SessionRecord session, RequestCodec.ListStates request)
            throws ProtocolFailure {
        long limit = RequestCodec.parseListLimit(request.rawLimit(), session.limits());
        long afterSequence = -1;
        if (request.after() != null) {
            SessionRecord.StateCursor cursor = session.stateCursors().get(request.after());
            if (cursor == null) {
                throw RequestCodec.failure(ErrorCode.INVALID_ARGUMENT, "invalid StateListCursor");
            }
            afterSequence = cursor.creationSequence();
        }
        final long position = afterSequence;
        List<StateRecord> active = session.states().values().stream()
                .filter(record -> record.creationSequence() > position)
                .sorted(Comparator.comparingLong(StateRecord::creationSequence))
                .toList();
        int count = (int) Math.min(limit, active.size());
        List<StateRecord> page = active.subList(0, count);
        SessionRecord staged = session.stage();
        LinkedHashMap<String, Object> response = new LinkedHashMap<>();
        response.put("states", page.stream().map(record -> record.metadata().toWire()).toList());
        if (active.size() > count) {
            response.put(
                    "next_cursor",
                    mintStateCursor(staged, page.getLast().creationSequence()));
        }
        try {
            byte[] encoded = ResultCodec.encode(response, limits);
            session.publish(staged);
            return encoded;
        } catch (ResponseTooLargeException overCap) {
            return responseCapError();
        }
    }

    private byte[] lineageNode(SessionRecord session, String lineageId) throws ProtocolFailure {
        CanonicalLineageNode node = session.lineageNodes().get(lineageId);
        if (node == null) {
            throw RequestCodec.failure(
                    ErrorCode.LINEAGE_NOT_FOUND,
                    "lineage node is unavailable", Map.of("lineage_id", lineageId));
        }
        try {
            return ResultCodec.encode(node.toWire(), limits);
        } catch (ResponseTooLargeException overCap) {
            return responseCapError();
        }
    }

    private byte[] release(SessionRecord session, String handle) throws ProtocolFailure {
        if (handle.equals("CORPUS")) {
            throw RequestCodec.failure(ErrorCode.INVALID_ARGUMENT, "CORPUS cannot be released");
        }
        SessionRecord staged = session.stage();
        boolean released = staged.states().remove(handle) != null;
        if (released) {
            List<String> cursors = staged.readCursors().entrySet().stream()
                    .filter(entry -> entry.getValue().targetHandle().equals(handle))
                    .map(Map.Entry::getKey).toList();
            for (String cursorToken : cursors) {
                SessionRecord.ReadCursor cursor = staged.readCursors().remove(cursorToken);
                staged.readCursorTokens().remove(readCursorKey(cursor.targetHandle(), cursor.position()));
            }
            List<String> roots = staged.states().values().stream()
                    .map(record -> record.metadata().lineageId()).toList();
            LineageGraph.prune(staged.lineageNodes(), roots);
        }
        LinkedHashMap<String, Object> response = new LinkedHashMap<>();
        response.put("released", released);
        try {
            byte[] encoded = ResultCodec.encode(response, limits);
            session.publish(staged);
            return encoded;
        } catch (ResponseTooLargeException overCap) {
            return responseCapError();
        }
    }

    private static StateRecord resolve(
            SessionRecord session, StateRef reference, Map<String, String> bindings)
            throws ProtocolFailure {
        String handle;
        if (reference.kind().equals("binding")) {
            handle = bindings.get(reference.value());
            if (handle == null) {
                throw RequestCodec.failure(
                        ErrorCode.MALFORMED_OPERATION,
                        "undefined binding", Map.of("binding", reference.value()));
            }
        } else {
            handle = reference.value();
        }
        StateRecord record = session.states().get(handle);
        if (record == null) {
            throw RequestCodec.failure(
                    ErrorCode.INVALID_STATE_REF,
                    "unknown or released state handle", Map.of("handle", handle));
        }
        return record;
    }

    private static void requireType(StateRecord record, String expected) throws ProtocolFailure {
        if (!record.metadata().stateType().equals(expected)) {
            throw RequestCodec.failure(
                    ErrorCode.TYPE_MISMATCH,
                    "operation requires " + (expected.equals("set") ? "SetRef" : "RankedRef"));
        }
    }

    private record MaterializationFrame(StateRecord record, boolean expanded) {}

    private static StateValue physical(StateRecord record) throws Exception {
        if (record.physicalResident()) {
            return record.value();
        }
        Set<StateRecord> active = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<>());
        java.util.ArrayDeque<MaterializationFrame> pending = new java.util.ArrayDeque<>();
        pending.push(new MaterializationFrame(record, false));
        while (!pending.isEmpty()) {
            MaterializationFrame frame = pending.pop();
            StateRecord current = frame.record();
            if (current.physicalResident()) {
                continue;
            }
            if (!frame.expanded()) {
                if (!active.add(current)) {
                    throw new IllegalStateException("state dependency cycle detected");
                }
                pending.push(new MaterializationFrame(current, true));
                List<StateRecord> dependencies = current.dependencies();
                for (int index = dependencies.size() - 1; index >= 0; index--) {
                    StateRecord dependency = dependencies.get(index);
                    if (active.contains(dependency)) {
                        throw new IllegalStateException("state dependency cycle detected");
                    }
                    if (!dependency.physicalResident()) {
                        pending.push(new MaterializationFrame(dependency, false));
                    }
                }
            } else {
                active.remove(current);
                // Its recipe may request parent values, but every dependency is
                // resident now, so those calls have constant call-stack depth.
                current.value();
            }
        }
        return record.value();
    }

    private static Map<String, Object> countResult(long count) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("count", count);
        return result;
    }

    private String mintReadCursor(SessionRecord session, String targetHandle, long position) {
        String key = readCursorKey(targetHandle, position);
        String cached = session.readCursorTokens().get(key);
        if (cached != null) {
            return cached;
        }
        String token = mint("cur-", 32, HEX64, session.readCursors()::containsKey);
        session.readCursors().put(token, new SessionRecord.ReadCursor(targetHandle, position));
        session.readCursorTokens().put(key, token);
        return token;
    }

    private String mintStateCursor(SessionRecord session, long position) {
        String cached = session.stateCursorTokens().get(position);
        if (cached != null) {
            return cached;
        }
        String token = mint("stc-", 32, HEX64, session.stateCursors()::containsKey);
        session.stateCursors().put(token, new SessionRecord.StateCursor(position));
        session.stateCursorTokens().put(position, token);
        return token;
    }

    private static String readCursorKey(String handle, long position) {
        return handle + ":" + position;
    }

    @FunctionalInterface
    private interface Occupied {
        boolean contains(String value);
    }

    private String mint(String prefix, int bytes, Pattern suffix, Occupied occupied) {
        while (true) {
            String generated = ids.hex(bytes);
            if (!suffix.matcher(generated).matches()) {
                throw new IllegalStateException("IdGenerator returned invalid lowercase hex");
            }
            String candidate = prefix + generated;
            if (!occupied.contains(candidate)) {
                return candidate;
            }
        }
    }

    /** Test/operations hook: evicts only physical cache state, never the logical handle. */
    public void evictPhysical(String sessionId, String handle) throws ProtocolFailure {
        evictPhysical(sessionId, handle, false);
    }

    /** Evicts one cache entry, or its complete immutable dependency closure, returning the count. */
    public int evictPhysical(String sessionId, String handle, boolean includeDependencies)
            throws ProtocolFailure {
        SessionRecord session = sessions.get(sessionId);
        if (session == null) {
            throw RequestCodec.failure(ErrorCode.SESSION_NOT_FOUND, "unknown session");
        }
        session.requestLock().lock();
        try {
            StateRecord record = session.states().get(handle);
            if (record == null) {
                throw RequestCodec.failure(ErrorCode.INVALID_STATE_REF, "unknown or released state handle");
            }
            java.util.Set<StateRecord> visited = java.util.Collections.newSetFromMap(
                    new java.util.IdentityHashMap<>());
            java.util.ArrayDeque<StateRecord> pending = new java.util.ArrayDeque<>();
            pending.push(record);
            while (!pending.isEmpty()) {
                StateRecord current = pending.pop();
                if (!visited.add(current)) {
                    continue;
                }
                current.evictPhysical();
                if (includeDependencies) {
                    current.dependencies().forEach(pending::push);
                }
            }
            return visited.size();
        } finally {
            session.requestLock().unlock();
        }
    }

    private void requireCompatible(Snapshot snapshot) throws ProtocolFailure {
        Manifest manifest = snapshot.manifest();
        boolean versions = manifest.analyzerContractVersion().equals(ContractAnalyzer.CONTRACT_VERSION)
                && manifest.scoringContractVersion().equals("SCORING_CONTRACT_v2.2")
                && manifest.coordinateContractVersion().equals("raw-codepoint-coordinates-v3.4")
                && manifest.unicodeVersion().equals("17.0.0")
                && manifest.uax15Revision() == 57
                && manifest.uax29Revision() == 47
                && manifest.luceneVersion().equals("10.5.1");
        if (!versions) {
            throw RequestCodec.failure(
                    ErrorCode.SNAPSHOT_INCOMPATIBLE, "snapshot contract versions are unsupported");
        }
        if (manifest.maxDocKeyUtf8BytesObserved() > limits.maxDocKeyUtf8Bytes()) {
            throw RequestCodec.failure(
                    ErrorCode.SNAPSHOT_INCOMPATIBLE,
                    "snapshot contains an over-limit DocKey",
                    Map.of("limit_name", "max_doc_key_utf8_bytes"));
        }
        if (manifest.maxAnalyzedTokenUtf8BytesObserved() > limits.maxAnalyzedTermUtf8Bytes()) {
            throw RequestCodec.failure(
                    ErrorCode.SNAPSHOT_INCOMPATIBLE,
                    "snapshot contains an over-limit analyzed token",
                    Map.of("limit_name", "max_analyzed_term_utf8_bytes"));
        }
    }

    private void validateResponseProfile() {
        byte[] minimal = ResultCodec.error(
                new ProtocolFailure(
                        ErrorCode.RESOURCE_LIMIT_EXCEEDED, "response exceeds max_response_bytes"),
                limits);
        if ((long) minimal.length > limits.maxResponseBytes()) {
            throw new IllegalArgumentException("max_response_bytes cannot fit the minimal cap error");
        }
        StateMetadata maximalMetadata = new StateMetadata(
                "r" + ServiceLimits.MAX_SAFE_INTEGER,
                "ranked",
                "snap-" + "0".repeat(64),
                ServiceLimits.MAX_SAFE_INTEGER,
                "lin-" + "0".repeat(64),
                "DIFFERENCE");
        LinkedHashMap<String, Object> maximalOpen = new LinkedHashMap<>();
        maximalOpen.put("protocol_version", "3.4");
        maximalOpen.put("session_id", "sess-" + "0".repeat(32));
        LinkedHashMap<String, Object> corpus = new LinkedHashMap<>(maximalMetadata.toWire());
        corpus.put("handle", "CORPUS");
        corpus.put("state_type", "set");
        corpus.put("created_by", "CORPUS");
        maximalOpen.put("corpus", corpus);
        maximalOpen.put("service_limits", limits.toWire());
        if ((long) CompactWireJson.encode(maximalOpen).length > limits.maxResponseBytes()
                || (long) CompactWireJson.encode(maximalMetadata.toWire()).length > limits.maxResponseBytes()
                || limits.maxLineageNodeBytes() > limits.maxResponseBytes()
                || worstPageBytes(limits.maxDocKeyUtf8Bytes()) > limits.maxResponseBytes()) {
            throw new IllegalArgumentException(
                    "max_response_bytes violates the mandatory minimum response profile");
        }
    }

    private static long worstPageBytes(long docKeyBytes) {
        try {
            LinkedHashMap<String, Object> emptyPage = new LinkedHashMap<>();
            emptyPage.put("doc_keys", List.of(""));
            emptyPage.put("next_cursor", "cur-" + "0".repeat(64));
            long fixedBytes = CompactWireJson.encode(emptyPage).length;
            return Math.addExact(Math.multiplyExact(docKeyBytes, 6), fixedBytes);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private byte[] responseCapError() {
        return ResultCodec.error(
                RequestCodec.failure(
                        ErrorCode.RESOURCE_LIMIT_EXCEEDED, "response exceeds max_response_bytes"),
                limits);
    }

    private static String safeMessage(Exception error) {
        return error.getMessage() == null ? "invalid argument" : error.getMessage();
    }
}
