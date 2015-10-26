package org.copperengine.core.persistent.cassandra;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang.NullArgumentException;
import org.copperengine.core.ProcessingState;
import org.copperengine.core.WaitMode;
import org.copperengine.core.monitoring.RuntimeStatisticsCollector;
import org.copperengine.core.persistent.SerializedWorkflow;
import org.copperengine.core.persistent.hybrid.HybridDBStorageAccessor;
import org.copperengine.core.persistent.hybrid.Storage;
import org.copperengine.core.persistent.hybrid.WorkflowInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.datastax.driver.core.ConsistencyLevel;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.ResultSetFuture;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

public class CassandraStorage implements Storage {

    private static final Logger logger = LoggerFactory.getLogger(CassandraStorage.class);

    private static final String TABLE_PLACEHOLDER = "<table>";
    private static final String DEFAULT_TABLE_NAME = "COP_WORKFLOW_INSTANCE";
    private static final String CQL_UPD_WORKFLOW_INSTANCE_NOT_WAITING = "UPDATE <table> SET PPOOL_ID=?, PRIO=?, CREATION_TS=?, DATA=?, OBJECT_STATE=?, STATE=? WHERE ID=?";
    private static final String CQL_UPD_WORKFLOW_INSTANCE_WAITING = "UPDATE <table> SET PPOOL_ID=?, PRIO=?, CREATION_TS=?, DATA=?, OBJECT_STATE=?, WAIT_MODE=?, TIMEOUT=?, RESPONSE_MAP_JSON=?, STATE=? WHERE ID=?";
    private static final String CQL_UPD_WORKFLOW_INSTANCE_STATE = "UPDATE <table> SET STATE=? WHERE ID=?";
    private static final String CQL_UPD_WORKFLOW_INSTANCE_STATE_AND_RESPONSE_MAP = "UPDATE <table> SET STATE=?, RESPONSE_MAP_JSON=?  WHERE ID=?";
    private static final String CQL_DEL_WORKFLOW_INSTANCE_WAITING = "DELETE FROM <table> WHERE ID=?";
    private static final String CQL_SEL_WORKFLOW_INSTANCE_WAITING = "SELECT * FROM <table> WHERE ID=?";
    private static final String CQL_SEL_ALL_WORKFLOW_INSTANCES = "SELECT ID, PPOOL_ID, PRIO, WAIT_MODE, RESPONSE_MAP_JSON, STATE, TIMEOUT FROM <table>";
    private static final String CQL_INS_EARLY_RESPONSE = "INSERT INTO COP_EARLY_RESPONSE (CORRELATION_ID, RESPONSE) VALUES (?,?) USING TTL ?";
    private static final String CQL_DEL_EARLY_RESPONSE = "DELETE FROM COP_EARLY_RESPONSE WHERE CORRELATION_ID=?";
    private static final String CQL_SEL_EARLY_RESPONSE = "SELECT RESPONSE FROM COP_EARLY_RESPONSE WHERE CORRELATION_ID=?";

    private final Executor executor;
    private final Session session;
    private final Map<String, PreparedStatement> preparedStatements = new HashMap<>();
    private final JsonMapper jsonMapper = new JsonMapperImpl();
    private final ConsistencyLevel consistencyLevel;
    private final RuntimeStatisticsCollector runtimeStatisticsCollector;

    private int ttlEarlyResponseSeconds = 1 * 24 * 60 * 60; // one day

    public CassandraStorage(final CassandraSessionManager sessionManager, final Executor executor, final RuntimeStatisticsCollector runtimeStatisticsCollector) {
        this(sessionManager, executor, runtimeStatisticsCollector, ConsistencyLevel.LOCAL_QUORUM);

    }

    public CassandraStorage(final CassandraSessionManager sessionManager, final Executor executor, final RuntimeStatisticsCollector runtimeStatisticsCollector, final ConsistencyLevel consistencyLevel) {
        if (sessionManager == null)
            throw new NullArgumentException("sessionManager");

        if (consistencyLevel == null)
            throw new NullArgumentException("consistencyLevel");

        if (executor == null)
            throw new NullArgumentException("executor");

        if (runtimeStatisticsCollector == null)
            throw new NullArgumentException("runtimeStatisticsCollector");

        this.executor = executor;
        this.consistencyLevel = consistencyLevel;
        this.session = sessionManager.getSession();
        this.runtimeStatisticsCollector = runtimeStatisticsCollector;

        prepare(CQL_UPD_WORKFLOW_INSTANCE_NOT_WAITING);
        prepare(CQL_UPD_WORKFLOW_INSTANCE_WAITING);
        prepare(CQL_DEL_WORKFLOW_INSTANCE_WAITING);
        prepare(CQL_SEL_WORKFLOW_INSTANCE_WAITING);
        prepare(CQL_SEL_ALL_WORKFLOW_INSTANCES);
        prepare(CQL_UPD_WORKFLOW_INSTANCE_STATE);
        prepare(CQL_INS_EARLY_RESPONSE);
        prepare(CQL_DEL_EARLY_RESPONSE);
        prepare(CQL_SEL_EARLY_RESPONSE);
        prepare(CQL_UPD_WORKFLOW_INSTANCE_STATE_AND_RESPONSE_MAP);
    }

    public void setTtlEarlyResponseSeconds(int ttlEarlyResponseSeconds) {
        if (ttlEarlyResponseSeconds <= 0)
            throw new IllegalArgumentException();
        this.ttlEarlyResponseSeconds = ttlEarlyResponseSeconds;
    }

    @Override
    public void safeWorkflowInstance(final WorkflowInstance cw) throws Exception {
        logger.debug("safeWorkflow({})", cw);
        if (cw.cid2ResponseMap == null || cw.cid2ResponseMap.isEmpty()) {
            final PreparedStatement pstmt = preparedStatements.get(CQL_UPD_WORKFLOW_INSTANCE_NOT_WAITING);
            final long startTS = System.nanoTime();
            session.execute(pstmt.bind(cw.ppoolId, cw.prio, cw.creationTS, cw.serializedWorkflow.getData(), cw.serializedWorkflow.getObjectState(), cw.state.name(), cw.id));
            runtimeStatisticsCollector.submit("wfi.update.nowait", 1, System.nanoTime() - startTS, TimeUnit.NANOSECONDS);
        }
        else {
            final PreparedStatement pstmt = preparedStatements.get(CQL_UPD_WORKFLOW_INSTANCE_WAITING);
            final String responseMapJson = jsonMapper.toJSON(cw.cid2ResponseMap);
            final long startTS = System.nanoTime();
            session.execute(pstmt.bind(cw.ppoolId, cw.prio, cw.creationTS, cw.serializedWorkflow.getData(), cw.serializedWorkflow.getObjectState(), cw.waitMode.name(), cw.timeout, responseMapJson, cw.state.name(), cw.id));
            runtimeStatisticsCollector.submit("wfi.update.wait", 1, System.nanoTime() - startTS, TimeUnit.NANOSECONDS);
        }
    }

    @Override
    public ListenableFuture<Void> deleteWorkflowInstance(String wfId) throws Exception {
        logger.debug("deleteWorkflowInstance({})", wfId);
        final PreparedStatement pstmt = preparedStatements.get(CQL_DEL_WORKFLOW_INSTANCE_WAITING);
        final long startTS = System.nanoTime();
        final ResultSetFuture rsf = session.executeAsync(pstmt.bind(wfId));
        return createSettableFuture(rsf, "wfi.delete", startTS);
    }

    private SettableFuture<Void> createSettableFuture(final ResultSetFuture rsf, final String mpId, final long startTsNanos) {
        final SettableFuture<Void> rv = SettableFuture.create();
        rsf.addListener(new Runnable() {
            @Override
            public void run() {
                try {
                    runtimeStatisticsCollector.submit(mpId, 1, System.nanoTime() - startTsNanos, TimeUnit.NANOSECONDS);
                    rsf.get();
                    rv.set(null);
                } catch (InterruptedException e) {
                    rv.setException(e);
                } catch (ExecutionException e) {
                    rv.setException(e.getCause());
                }

            }
        }, executor);
        return rv;
    }

    @Override
    public WorkflowInstance readCassandraWorkflow(String wfId) throws Exception {
        logger.debug("readCassandraWorkflow({})", wfId);
        final PreparedStatement pstmt = preparedStatements.get(CQL_SEL_WORKFLOW_INSTANCE_WAITING);
        final long startTS = System.nanoTime();
        ResultSet rs = session.execute(pstmt.bind(wfId));
        Row row = rs.one();
        if (row == null) {
            logger.warn("No workflow instance with id {} found", wfId);
            return null;
        }
        final WorkflowInstance cw = new WorkflowInstance();
        cw.id = wfId;
        cw.ppoolId = row.getString("PPOOL_ID");
        cw.prio = row.getInt("PRIO");
        cw.creationTS = row.getDate("CREATION_TS");
        cw.timeout = row.getDate("TIMEOUT");
        cw.waitMode = toWaitMode(row.getString("WAIT_MODE"));
        cw.serializedWorkflow = new SerializedWorkflow();
        cw.serializedWorkflow.setData(row.getString("DATA"));
        cw.serializedWorkflow.setObjectState(row.getString("OBJECT_STATE"));
        cw.cid2ResponseMap = toResponseMap(row.getString("RESPONSE_MAP_JSON"));
        cw.state = ProcessingState.valueOf(row.getString("STATE"));
        runtimeStatisticsCollector.submit("wfi.read", 1, System.nanoTime() - startTS, TimeUnit.NANOSECONDS);
        return cw;
    }

    @Override
    public ListenableFuture<Void> safeEarlyResponse(String correlationId, String serializedResponse) throws Exception {
        logger.debug("safeEarlyResponse({})", correlationId);
        final long startTS = System.nanoTime();
        final ResultSetFuture rsf = session.executeAsync(preparedStatements.get(CQL_INS_EARLY_RESPONSE).bind(correlationId, serializedResponse, ttlEarlyResponseSeconds));
        return createSettableFuture(rsf, "ear.insert", startTS);
    }

    @Override
    public String readEarlyResponse(String correlationId) throws Exception {
        logger.debug("readEarlyResponse({})", correlationId);
        final long startTS = System.nanoTime();
        final ResultSet rs = session.execute(preparedStatements.get(CQL_SEL_EARLY_RESPONSE).bind(correlationId));
        Row row = rs.one();
        runtimeStatisticsCollector.submit("ear.read", 1, System.nanoTime() - startTS, TimeUnit.NANOSECONDS);
        if (row != null) {
            logger.debug("early response with correlationId {} found!", correlationId);
            return row.getString("RESPONSE");
        }
        return null;
    }

    @Override
    public ListenableFuture<Void> deleteEarlyResponse(String correlationId) throws Exception {
        logger.debug("deleteEarlyResponse({})", correlationId);
        final long startTS = System.nanoTime();
        final ResultSetFuture rsf = session.executeAsync(preparedStatements.get(CQL_DEL_EARLY_RESPONSE).bind(correlationId));
        return createSettableFuture(rsf, "ear.delete", startTS);
    }

    @Override
    public void initialize(HybridDBStorageAccessor internalStorageAccessor) throws Exception {
        final long startTS = System.currentTimeMillis();
        final List<String> missingResponseCorrelationIds = new ArrayList<String>();
        final ResultSet rs = session.execute(preparedStatements.get(CQL_SEL_ALL_WORKFLOW_INSTANCES).bind().setFetchSize(2000));
        long counter = 0;
        Row row;
        while ((row = rs.one()) != null) {
            counter++;
            final String wfId = row.getString("ID");
            final String ppoolId = row.getString("PPOOL_ID");
            final int prio = row.getInt("PRIO");
            final WaitMode waitMode = toWaitMode(row.getString("WAIT_MODE"));
            final Map<String, String> responseMap = toResponseMap(row.getString("RESPONSE_MAP_JSON"));
            final ProcessingState state = ProcessingState.valueOf(row.getString("STATE"));
            final Date timeout = row.getDate("TIMEOUT");
            final boolean timeoutOccured = timeout != null && timeout.getTime() <= System.currentTimeMillis();

            if (state == ProcessingState.ERROR) {
                continue;
            }

            if (state == ProcessingState.ENQUEUED) {
                internalStorageAccessor.enqueue(wfId, ppoolId, prio);
                continue;
            }

            if (responseMap != null) {
                missingResponseCorrelationIds.clear();
                int numberOfAvailableResponses = 0;
                for (Entry<String, String> e : responseMap.entrySet()) {
                    final String correlationId = e.getKey();
                    final String response = e.getValue();
                    internalStorageAccessor.registerCorrelationId(correlationId, wfId);
                    if (response != null) {
                        numberOfAvailableResponses++;
                    }
                    else {
                        missingResponseCorrelationIds.add(correlationId);
                    }
                }
                boolean modified = false;
                if (!missingResponseCorrelationIds.isEmpty()) {
                    // check for early responses
                    for (String cid : missingResponseCorrelationIds) {
                        String earlyResponse = readEarlyResponse(cid);
                        if (earlyResponse != null) {
                            responseMap.put(cid, earlyResponse);
                            numberOfAvailableResponses++;
                            modified = true;
                        }
                    }
                }
                if (modified || timeoutOccured) {
                    ProcessingState newState = (timeoutOccured || numberOfAvailableResponses == responseMap.size() || (numberOfAvailableResponses == 1 && waitMode == WaitMode.FIRST)) ? ProcessingState.ENQUEUED : ProcessingState.WAITING;
                    String responseMapJson = jsonMapper.toJSON(responseMap);
                    session.execute(preparedStatements.get(CQL_UPD_WORKFLOW_INSTANCE_STATE_AND_RESPONSE_MAP).bind(newState.name(), responseMapJson, wfId));
                    if (newState == ProcessingState.ENQUEUED) {
                        internalStorageAccessor.enqueue(wfId, ppoolId, prio);
                    }
                }

            }
        }
        logger.debug("Read {} rows in {} msec", counter, System.currentTimeMillis() - startTS);
    }

    @Override
    public void updateWorkflowInstanceState(String wfId, ProcessingState state) throws Exception {
        logger.debug("updateWorkflowInstanceState({}, {})", wfId, state);
        long startTS = System.nanoTime();
        session.execute(preparedStatements.get(CQL_UPD_WORKFLOW_INSTANCE_STATE).bind(state.name(), wfId));
        runtimeStatisticsCollector.submit("wfi.update.state", 1, System.nanoTime() - startTS, TimeUnit.NANOSECONDS);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> toResponseMap(String v) {
        return v == null ? null : jsonMapper.fromJSON(v, HashMap.class);
    }

    private WaitMode toWaitMode(String v) {
        return v == null ? null : WaitMode.valueOf(v);
    }

    private void prepare(String cql) {
        String replaced = cql.replace(TABLE_PLACEHOLDER, DEFAULT_TABLE_NAME);
        logger.info("Preparing cql stmt {}", replaced);
        PreparedStatement pstmt = session.prepare(replaced);
        pstmt.setConsistencyLevel(consistencyLevel);
        preparedStatements.put(cql, pstmt);
    }
}
