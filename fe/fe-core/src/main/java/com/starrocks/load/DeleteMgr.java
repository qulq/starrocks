// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/main/java/org/apache/doris/load/DeleteHandler.java

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.starrocks.load;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.reflect.TypeToken;
import com.google.gson.annotations.SerializedName;
import com.starrocks.analysis.BinaryPredicate;
import com.starrocks.analysis.BinaryType;
import com.starrocks.analysis.DateLiteral;
import com.starrocks.analysis.DecimalLiteral;
import com.starrocks.analysis.InPredicate;
import com.starrocks.analysis.IsNullPredicate;
import com.starrocks.analysis.LiteralExpr;
import com.starrocks.analysis.NullLiteral;
import com.starrocks.analysis.Predicate;
import com.starrocks.analysis.SlotRef;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.KeysType;
import com.starrocks.catalog.MaterializedIndex;
import com.starrocks.catalog.MaterializedIndexMeta;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.Partition;
import com.starrocks.catalog.PhysicalPartition;
import com.starrocks.catalog.PrimitiveType;
import com.starrocks.catalog.Table;
import com.starrocks.catalog.Type;
import com.starrocks.common.AnalysisException;
import com.starrocks.common.Config;
import com.starrocks.common.DdlException;
import com.starrocks.common.ErrorCode;
import com.starrocks.common.ErrorReport;
import com.starrocks.common.FeConstants;
import com.starrocks.common.Pair;
import com.starrocks.common.io.Writable;
import com.starrocks.common.util.ListComparator;
import com.starrocks.common.util.TimeUtils;
import com.starrocks.common.util.UUIDUtil;
import com.starrocks.common.util.concurrent.lock.LockType;
import com.starrocks.common.util.concurrent.lock.Locker;
import com.starrocks.lake.delete.LakeDeleteJob;
import com.starrocks.memory.MemoryTrackable;
import com.starrocks.persist.ImageWriter;
import com.starrocks.persist.metablock.SRMetaBlockEOFException;
import com.starrocks.persist.metablock.SRMetaBlockException;
import com.starrocks.persist.metablock.SRMetaBlockID;
import com.starrocks.persist.metablock.SRMetaBlockReader;
import com.starrocks.persist.metablock.SRMetaBlockWriter;
import com.starrocks.qe.ConnectContext;
import com.starrocks.qe.QueryState;
import com.starrocks.qe.QueryStateException;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.server.WarehouseManager;
import com.starrocks.service.FrontendOptions;
import com.starrocks.sql.StatementPlanner;
import com.starrocks.sql.analyzer.Analyzer;
import com.starrocks.sql.analyzer.DeleteAnalyzer;
import com.starrocks.sql.ast.DeleteStmt;
import com.starrocks.sql.ast.StatementBase;
import com.starrocks.sql.optimizer.Utils;
import com.starrocks.sql.optimizer.operator.physical.PhysicalOlapScanOperator;
import com.starrocks.sql.parser.SqlParser;
import com.starrocks.sql.plan.ExecPlan;
import com.starrocks.transaction.RunningTxnExceedException;
import com.starrocks.transaction.TransactionState;
import com.starrocks.transaction.TransactionState.TxnCoordinator;
import com.starrocks.transaction.TransactionState.TxnSourceType;
import com.starrocks.warehouse.cngroup.ComputeResource;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.ListUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

public class DeleteMgr implements Writable, MemoryTrackable {
    private static final Logger LOG = LogManager.getLogger(DeleteMgr.class);

    // TransactionId -> DeleteJob
    private final Map<Long, DeleteJob> idToDeleteJob;

    // Db -> DeleteInfo list
    @SerializedName(value = "dbToDeleteInfos")
    private final Map<Long, List<MultiDeleteInfo>> dbToDeleteInfos;

    // this lock is protect List<MultiDeleteInfo> add / remove dbToDeleteInfos is use newConcurrentMap
    // so it does not need to protect, although removeOldDeleteInfo only be called in one thread
    // but other thread may call deleteInfoList.add(deleteInfo) so deleteInfoList is not thread safe.
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Set<Long> killJobSet;

    public DeleteMgr() {
        idToDeleteJob = Maps.newConcurrentMap();
        dbToDeleteInfos = Maps.newConcurrentMap();
        killJobSet = Sets.newConcurrentHashSet();
    }

    public void killJob(long jobId) {
        killJobSet.add(jobId);
    }

    public boolean removeKillJob(long jobid) {
        return killJobSet.remove(jobid);
    }

    public enum CancelType {
        METADATA_MISSING,
        TIMEOUT,
        COMMIT_FAIL,
        UNKNOWN,
        USER
    }

    public void process(DeleteStmt stmt) throws DdlException, QueryStateException {
        String dbName = stmt.getTableName().getDb();
        String tableName = stmt.getTableName().getTbl();

        Database db = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb(dbName);
        if (db == null) {
            throw new DdlException("Db does not exist. name: " + dbName);
        }

        Table table = GlobalStateMgr.getCurrentState().getLocalMetastore().getTable(db.getFullName(), tableName);
        if (table == null) {
            throw new DdlException("Table does not exist. name: " + tableName);
        }

        DeleteJob deleteJob = null;
        try {
            List<Partition> partitions = Lists.newArrayList();
            Locker locker = new Locker();
            locker.lockDatabase(db.getId(), LockType.READ);
            try {
                if (!table.isOlapOrCloudNativeTable()) {
                    throw new DdlException("Delete is not supported on " + table.getType() + " table");
                }

                List<Predicate> conditions = DeleteAnalyzer.replaceParameterInExpr(stmt.getDeleteConditions());
                deleteJob = createJob(stmt, conditions, db, (OlapTable) table, partitions);
                if (deleteJob == null) {
                    return;
                }

            } catch (Throwable t) {
                LOG.warn("error occurred during delete process", t);
                // if transaction has been begun, need to abort it
                long transactionId = deleteJob == null ? -1 : deleteJob.getTransactionId();
                if (GlobalStateMgr.getCurrentState().getGlobalTransactionMgr()
                        .getTransactionState(db.getId(), transactionId) != null) {
                    cancelJob(deleteJob, CancelType.UNKNOWN, t.getMessage());
                }
                throw new DdlException(t.getMessage(), t);
            } finally {
                locker.unLockDatabase(db.getId(), LockType.READ);
            }

            deleteJob.run(stmt, db, table, partitions);
        } catch (QueryStateException e) {
            // If delete success, it will throw QueryStateException(QueryState.MysqlStateType.OK, sb.toString()).
            if (e.getQueryState().getStateType() == QueryState.MysqlStateType.OK) {
                // trigger after a delete job finished
                GlobalStateMgr.getCurrentState().getOperationListenerBus().onDeleteJobTransactionFinish(db, table);
            }
            throw e;
        } finally {
            if (!FeConstants.runningUnitTest) {
                clearJob(deleteJob);
            }
        }
    }

    private DeleteJob createJob(DeleteStmt stmt, List<Predicate> conditions, Database db, OlapTable olapTable,
                                List<Partition> partitions)
            throws DdlException, AnalysisException, RunningTxnExceedException {
        // check table state
        if (olapTable.getState() != OlapTable.OlapTableState.NORMAL) {
            throw new DdlException("Table's state is not normal: " + olapTable.getName());
        }

        // get partitions
        List<String> partitionNames = stmt.getPartitionNamesList();
        Preconditions.checkState(partitionNames != null);
        boolean noPartitionSpecified = partitionNames.isEmpty();
        if (noPartitionSpecified) {
            partitionNames = partitionPruneForDelete(stmt, olapTable);

            if (partitionNames.isEmpty()) {
                LOG.info("The delete statement [{}] prunes all partitions", stmt.getOrigStmt().originStmt);
                return null;
            }
        }

        Map<Long, Short> partitionReplicaNum = Maps.newHashMap();
        for (String partitionName : partitionNames) {
            Partition partition = olapTable.getPartition(partitionName);
            if (partition == null) {
                throw new DdlException("Partition does not exist. name: " + partitionName);
            }
            partitions.add(partition);
            short replicationNum = olapTable.getPartitionInfo().getReplicationNum(partition.getId());
            for (PhysicalPartition physicalPartition : partition.getSubPartitions()) {
                partitionReplicaNum.put(physicalPartition.getId(), replicationNum);
            }
        }

        // check conditions
        List<String> deleteConditions = Lists.newArrayList();
        boolean hasValidCondition = checkDelete(olapTable, partitions, conditions, deleteConditions);
        if (!hasValidCondition) {
            return null;
        }

        // generate label
        String label = "delete_" + UUIDUtil.genUUID().toString();
        long jobId = GlobalStateMgr.getCurrentState().getNextId();
        stmt.setJobId(jobId);

        ComputeResource computeResource = WarehouseManager.DEFAULT_RESOURCE;
        if (ConnectContext.get() != null) {
            computeResource = ConnectContext.get().getCurrentComputeResource();
        }

        // begin txn here and generate txn id
        long transactionId = GlobalStateMgr.getCurrentState().getGlobalTransactionMgr().beginTransaction(db.getId(),
                Lists.newArrayList(olapTable.getId()), label, null,
                new TxnCoordinator(TxnSourceType.FE, FrontendOptions.getLocalHostAddress()),
                TransactionState.LoadJobSourceType.DELETE, jobId, Config.stream_load_default_timeout_second,
                computeResource);

        MultiDeleteInfo deleteInfo =
                new MultiDeleteInfo(db.getId(), olapTable.getId(), olapTable.getName(), deleteConditions);
        deleteInfo.setPartitions(noPartitionSpecified,
                partitions.stream().map(Partition::getId).collect(Collectors.toList()), partitionNames);
        DeleteJob deleteJob = null;

        if (olapTable.isCloudNativeTable()) {
            deleteJob = new LakeDeleteJob(jobId, transactionId, label, deleteInfo, computeResource);
        } else {
            deleteJob = new OlapDeleteJob(jobId, transactionId, label, partitionReplicaNum, deleteInfo);
        }
        deleteJob.setDeleteConditions(conditions);
        idToDeleteJob.put(deleteJob.getTransactionId(), deleteJob);

        // add transaction callback
        GlobalStateMgr.getCurrentState().getGlobalTransactionMgr().getCallbackFactory().addCallback(deleteJob);

        return deleteJob;
    }

    @VisibleForTesting
    public List<String> extractPartitionNamesByCondition(DeleteStmt stmt, OlapTable olapTable) throws DdlException {
        return partitionPruneForDelete(stmt, olapTable);
    }

    /**
     * Construct a fake sql then leverage the optimizer to prune partitions
     *
     * @return pruned partitions with delete conditions
     */
    private List<String> partitionPruneForDelete(DeleteStmt stmt, OlapTable table) {
        String tableName = stmt.getTableName().toSql();
        String predicate = stmt.getWherePredicate().toSql();
        String fakeSql = String.format("SELECT * FROM %s WHERE %s", tableName, predicate);
        PhysicalOlapScanOperator physicalOlapScanOperator;
        ConnectContext currentSession = ConnectContext.get();
        try {
            // Bypass the privilege check, as current user may have only the DELETE privilege but not SELECT
            currentSession.setBypassAuthorizerCheck(true);
            List<StatementBase> parse = SqlParser.parse(fakeSql, currentSession.getSessionVariable());
            StatementBase selectStmt = parse.get(0);
            Analyzer.analyze(selectStmt, ConnectContext.get());
            ExecPlan plan = StatementPlanner.plan(selectStmt, ConnectContext.get());
            List<PhysicalOlapScanOperator> physicalOlapScanOperators =
                    Utils.extractPhysicalOlapScanOperator(plan.getPhysicalPlan());
            // it's supposed to be empty set
            if (CollectionUtils.isEmpty(physicalOlapScanOperators)) {
                return Lists.newArrayList();
            }
            physicalOlapScanOperator = physicalOlapScanOperators.get(0);
        } catch (Exception e) {
            LOG.warn("failed to do partition pruning for delete {}", stmt.toString(), e);
            return Lists.newArrayList(table.getVisiblePartitionNames());
        } finally {
            currentSession.setBypassAuthorizerCheck(false);
        }
        List<Long> selectedPartitionId = physicalOlapScanOperator.getSelectedPartitionId();
        return ListUtils.emptyIfNull(selectedPartitionId)
                .stream()
                .map(x -> table.getPartition(x).getName())
                .collect(Collectors.toList());
    }

    /**
     * This method should always be called in the end of the delete process to clean the job.
     * Better put it in finally block.
     *
     * @param job
     */
    public void clearJob(DeleteJob job) {
        if (job == null) {
            return;
        }

        long signature = job.getTransactionId();
        idToDeleteJob.remove(signature);
        job.clear();

        // NOT remove callback from GlobalTransactionMgr's callback factory here.
        // the callback will be removed after transaction is aborted of visible.
    }

    public void recordFinishedJob(DeleteJob job) {
        if (job != null) {
            long dbId = job.getDeleteInfo().getDbId();
            LOG.info("record finished deleteJob, transactionId {}, dbId {}",
                    job.getTransactionId(), dbId);
            dbToDeleteInfos.putIfAbsent(dbId, Lists.newArrayList());
            List<MultiDeleteInfo> deleteInfoList = dbToDeleteInfos.get(dbId);
            lock.writeLock().lock();
            try {
                deleteInfoList.add(job.getDeleteInfo());
            } finally {
                lock.writeLock().unlock();
            }
        }
    }

    /**
     * abort delete job
     * return true when successfully abort.
     * return true when some unknown error happened, just ignore it.
     * return false when the job is already committed
     *
     * @param job
     * @param cancelType
     * @param reason
     * @return
     */
    public boolean cancelJob(DeleteJob job, CancelType cancelType, String reason) {
        if (job == null) {
            LOG.warn("cancel a null job, cancelType: {}, reason: {}", cancelType.name(), reason);
            return true;
        }
        return job.cancel(cancelType, reason);
    }

    public DeleteJob getDeleteJob(long transactionId) {
        return idToDeleteJob.get(transactionId);
    }

    private SlotRef getSlotRef(Predicate condition) {
        if (condition instanceof BinaryPredicate || condition instanceof IsNullPredicate ||
                condition instanceof InPredicate) {
            return (SlotRef) condition.getChild(0);
        }
        return null;
    }

    /**
     * @param table
     * @param partitions
     * @param conditions
     * @param deleteConditions
     * @return return false means no need to push delete condition to BE
     * return ture means it should go through the following procedure
     * @throws DdlException
     */
    private boolean checkDelete(OlapTable table, List<Partition> partitions, List<Predicate> conditions,
                                List<String> deleteConditions)
            throws DdlException {

        // check partition state
        for (Partition partition : partitions) {
            Partition.PartitionState state = partition.getState();
            if (state != Partition.PartitionState.NORMAL) {
                // ErrorReport.reportDdlException(ErrorCode.ERR_BAD_PARTITION_STATE, partition.getName(), state.name());
                throw new DdlException("Partition[" + partition.getName() + "]' state is not NORMAL: " + state.name());
            }
        }

        // primary key table do not support delete sql statement yet
        if (table.getKeysType() == KeysType.PRIMARY_KEYS) {
            throw new DdlException("primary key tablet do not support delete statement yet");
        }

        // check condition column is key column and condition value
        Map<String, Column> nameToColumn = Maps.newTreeMap(String.CASE_INSENSITIVE_ORDER);
        for (Column column : table.getBaseSchema()) {
            nameToColumn.put(column.getName(), column);
        }
        for (Predicate condition : conditions) {
            SlotRef slotRef = getSlotRef(condition);
            if (slotRef == null) {
                throw new DdlException("unsupported delete condition:" + condition);
            }
            String columnName = slotRef.getColumnName();
            if (!nameToColumn.containsKey(columnName)) {
                ErrorReport.reportDdlException(ErrorCode.ERR_BAD_FIELD_ERROR, columnName, table.getName());
            }

            Column column = nameToColumn.get(columnName);
            // Due to rounding errors, most floating-point numbers end up being slightly imprecise,
            // it also means that numbers expected to be equal often differ slightly, so we do not allow compare with
            // floating-point numbers, floating-point number not allowed in where clause
            if (!column.isKey() && table.getKeysType() != KeysType.DUP_KEYS
                    || column.getPrimitiveType().isFloatingPointType()) {
                // ErrorReport.reportDdlException(ErrorCode.ERR_NOT_KEY_COLUMN, columnName);
                throw new DdlException("Column[" + columnName + "] is not key column or storage model " +
                        "is not duplicate or column type is float or double.");
            }

            if (column.getType().isComplexType()) {
                throw new DdlException(
                        "unsupported delete condition on Array/Map/Struct type column[" + columnName + "]");
            }

            if (condition instanceof BinaryPredicate) {
                try {
                    BinaryPredicate binaryPredicate = (BinaryPredicate) condition;
                    if (binaryPredicate.getOp() == BinaryType.EQ_FOR_NULL) {
                        throw new DdlException("Delete condition does not support null-safe equal currently.");
                    }
                    // delete a = null means delete nothing
                    if (binaryPredicate.getChild(1) instanceof NullLiteral) {
                        return false;
                    }
                    updatePredicate(binaryPredicate, column, 1);
                } catch (AnalysisException e) {
                    // ErrorReport.reportDdlException(ErrorCode.ERR_INVALID_VALUE, value);
                    throw new DdlException("Invalid value for column " + columnName + ": " + e.getMessage());
                }
            } else if (condition instanceof InPredicate) {
                String value = null;
                try {
                    InPredicate inPredicate = (InPredicate) condition;
                    // delete a in (null) means delete nothing
                    inPredicate.removeNullChild();
                    int inElementNum = inPredicate.getInElementNum();
                    if (inElementNum == 0) {
                        return false;
                    }
                    for (int i = 1; i <= inElementNum; i++) {
                        updatePredicate(inPredicate, column, i);
                    }
                } catch (AnalysisException e) {
                    throw new DdlException("Invalid column value[" + value + "] for column " + columnName);
                }
            }

            // set schema column name
            slotRef.setColumnName(column.getName());
        }
        // check materialized index.
        // only need check the first partition because each partition has same materialized view
        Map<Long, List<Column>> indexIdToSchema = table.getIndexIdToSchema();
        PhysicalPartition partition = partitions.get(0).getDefaultPhysicalPartition();
        for (MaterializedIndex index : partition.getMaterializedIndices(MaterializedIndex.IndexExtState.VISIBLE)) {
            if (table.getBaseIndexId() == index.getId()) {
                continue;
            }
            // check table has condition column
            Map<String, Column> indexColNameToColumn = Maps.newTreeMap(String.CASE_INSENSITIVE_ORDER);
            for (Column column : indexIdToSchema.get(index.getId())) {
                indexColNameToColumn.put(column.getName(), column);
            }
            String indexName = table.getIndexNameById(index.getId());
            for (Predicate condition : conditions) {
                String columnName = getSlotRef(condition).getColumnName();
                Column column = indexColNameToColumn.get(columnName);
                if (column == null) {
                    ErrorReport
                            .reportDdlException(ErrorCode.ERR_BAD_FIELD_ERROR, columnName, "index[" + indexName + "]");
                }
                MaterializedIndexMeta indexMeta = table.getIndexIdToMeta().get(index.getId());
                if (indexMeta.getKeysType() != KeysType.DUP_KEYS && !column.isKey()) {
                    throw new DdlException("Column[" + columnName + "] is not key column in index[" + indexName + "]");
                }
            }
        }

        // save delete conditions
        for (Predicate condition : conditions) {
            if (condition instanceof BinaryPredicate) {
                BinaryPredicate binaryPredicate = (BinaryPredicate) condition;
                SlotRef slotRef = (SlotRef) binaryPredicate.getChild(0);
                String columnName = slotRef.getColumnName();
                StringBuilder sb = new StringBuilder();
                sb.append(columnName).append(" ").append(binaryPredicate.getOp().name()).append(" \"")
                        .append(((LiteralExpr) binaryPredicate.getChild(1)).getStringValue()).append("\"");
                deleteConditions.add(sb.toString());
            } else if (condition instanceof IsNullPredicate) {
                IsNullPredicate isNullPredicate = (IsNullPredicate) condition;
                SlotRef slotRef = (SlotRef) isNullPredicate.getChild(0);
                String columnName = slotRef.getColumnName();
                StringBuilder sb = new StringBuilder();
                sb.append(columnName);
                if (isNullPredicate.isNotNull()) {
                    sb.append(" IS NOT NULL");
                } else {
                    sb.append(" IS NULL");
                }
                deleteConditions.add(sb.toString());
            } else if (condition instanceof InPredicate) {
                InPredicate inPredicate = (InPredicate) condition;
                SlotRef slotRef = (SlotRef) inPredicate.getChild(0);
                String columnName = slotRef.getColumnName();
                StringBuilder strBuilder = new StringBuilder();
                String notStr = inPredicate.isNotIn() ? "NOT " : "";
                strBuilder.append(columnName).append(" ").append(notStr).append("IN (");
                int inElementNum = inPredicate.getInElementNum();
                for (int i = 1; i <= inElementNum; ++i) {
                    strBuilder.append(inPredicate.getChild(i).toSql());
                    strBuilder.append((i != inPredicate.getInElementNum()) ? ", " : "");
                }
                strBuilder.append(")");
                deleteConditions.add(strBuilder.toString());
            }
        }
        return true;
    }

    // if a bool cond passed to be, be's zone_map cannot handle bool correctly,
    // change it to a tinyint type here;
    // for DateLiteral needs to be converted to the correct format uniformly
    // if datekey type pass will cause delete cond timeout.
    public void updatePredicate(Predicate predicate, Column column, int childNo) throws AnalysisException {
        String value = ((LiteralExpr) predicate.getChild(childNo)).getStringValue();
        if (column.getPrimitiveType() == PrimitiveType.BOOLEAN) {
            if (value.equalsIgnoreCase("true")) {
                predicate.setChild(childNo, LiteralExpr.create("1", Type.TINYINT));
            } else if (value.equalsIgnoreCase("false")) {
                predicate.setChild(childNo, LiteralExpr.create("0", Type.TINYINT));
            }
        } else if (column.getType().isStringType()) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > column.getStrLen()) {
                throw new AnalysisException("String value is too long. value: " + bytes.length
                        + ", column: " + column.getStrLen());
            }
        }

        LiteralExpr result = LiteralExpr.create(value, Objects.requireNonNull(column.getType()));
        if (result instanceof DecimalLiteral) {
            ((DecimalLiteral) result).checkPrecisionAndScale(column.getType(), column.getPrecision(), column.getScale());
        } else if (result instanceof DateLiteral) {
            predicate.setChild(childNo, result);
        }
    }

    // show delete stmt
    public List<List<Comparable>> getDeleteInfosByDb(long dbId) {
        LinkedList<List<Comparable>> infos = new LinkedList<List<Comparable>>();
        Database db = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb(dbId);
        if (db == null) {
            return infos;
        }

        String dbName = db.getFullName();
        List<MultiDeleteInfo> deleteInfos = dbToDeleteInfos.get(dbId);

        lock.readLock().lock();
        try {
            if (deleteInfos == null) {
                return infos;
            }

            for (MultiDeleteInfo deleteInfo : deleteInfos) {
                List<Comparable> info = Lists.newArrayList();
                info.add(deleteInfo.getTableName());
                if (deleteInfo.isNoPartitionSpecified()) {
                    info.add("*");
                } else {
                    if (deleteInfo.getPartitionNames() == null) {
                        info.add("");
                    } else {
                        info.add(Joiner.on(", ").join(deleteInfo.getPartitionNames()));
                    }
                }

                info.add(TimeUtils.longToTimeString(deleteInfo.getCreateTimeMs()));
                String conds = Joiner.on(", ").join(deleteInfo.getDeleteConditions());
                info.add(conds);

                info.add("FINISHED");
                infos.add(info);
            }
        } finally {
            lock.readLock().unlock();
        }
        // sort by createTimeMs
        ListComparator<List<Comparable>> comparator = new ListComparator<>(2);
        infos.sort(comparator);
        return infos;
    }

    public void replayDelete(DeleteInfo deleteInfo, GlobalStateMgr globalStateMgr) {
        // add to deleteInfos
        if (deleteInfo == null) {
            return;
        }
        long dbId = deleteInfo.getDbId();
        LOG.info("replay delete, dbId {}", dbId);
        updateTableDeleteInfo(globalStateMgr, dbId, deleteInfo.getTableId());

        if (isDeleteInfoExpired(deleteInfo, System.currentTimeMillis())) {
            LOG.info("discard expired delete info {}", deleteInfo);
            return;
        }

        dbToDeleteInfos.putIfAbsent(dbId, Lists.newArrayList());
        List<MultiDeleteInfo> deleteInfoList = dbToDeleteInfos.get(dbId);
        lock.writeLock().lock();
        try {
            deleteInfoList.add(MultiDeleteInfo.upgrade(deleteInfo));
        } finally {
            lock.writeLock().unlock();
        }

    }

    public void replayMultiDelete(MultiDeleteInfo deleteInfo, GlobalStateMgr globalStateMgr) {
        // add to deleteInfos
        if (deleteInfo == null) {
            return;
        }

        long dbId = deleteInfo.getDbId();
        LOG.info("replay delete, dbId {}", dbId);
        updateTableDeleteInfo(globalStateMgr, dbId, deleteInfo.getTableId());

        if (isDeleteInfoExpired(deleteInfo, System.currentTimeMillis())) {
            LOG.info("discard expired delete info {}", deleteInfo);
            return;
        }

        dbToDeleteInfos.putIfAbsent(dbId, Lists.newArrayList());
        List<MultiDeleteInfo> deleteInfoList = dbToDeleteInfos.get(dbId);
        lock.writeLock().lock();
        try {
            deleteInfoList.add(deleteInfo);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void updateTableDeleteInfo(GlobalStateMgr globalStateMgr, long dbId, long tableId) {
        Database db = globalStateMgr.getLocalMetastore().getDb(dbId);
        if (db == null) {
            return;
        }
        Table table = GlobalStateMgr.getCurrentState().getLocalMetastore().getTable(db.getId(), tableId);
        if (table == null) {
            return;
        }
        OlapTable olapTable = (OlapTable) table;
        olapTable.setHasDelete();
    }

    private boolean isDeleteInfoExpired(DeleteInfo deleteInfo, long currentTimeMs) {
        return (currentTimeMs - deleteInfo.getCreateTimeMs()) / 1000 > Config.label_keep_max_second;
    }

    private boolean isDeleteInfoExpired(MultiDeleteInfo deleteInfo, long currentTimeMs) {
        return (currentTimeMs - deleteInfo.getCreateTimeMs()) / 1000 > Config.label_keep_max_second;
    }

    public void removeOldDeleteInfo() {
        long currentTimeMs = System.currentTimeMillis();
        Iterator<Entry<Long, List<MultiDeleteInfo>>> logIterator = dbToDeleteInfos.entrySet().iterator();
        while (logIterator.hasNext()) {

            List<MultiDeleteInfo> deleteInfos = logIterator.next().getValue();
            lock.writeLock().lock();
            try {
                deleteInfos.sort((o1, o2) -> Long.signum(o1.getCreateTimeMs() - o2.getCreateTimeMs()));
                int numJobsToRemove = deleteInfos.size() - Config.label_keep_max_num;

                Iterator<MultiDeleteInfo> iterator = deleteInfos.iterator();
                while (iterator.hasNext()) {
                    MultiDeleteInfo deleteInfo = iterator.next();
                    if (isDeleteInfoExpired(deleteInfo, currentTimeMs) || numJobsToRemove > 0) {
                        iterator.remove();
                        --numJobsToRemove;
                    }
                }
                if (deleteInfos.isEmpty()) {
                    logIterator.remove();
                }
            } finally {
                lock.writeLock().unlock();
            }
        }
    }

    public long getDeleteJobCount() {
        return this.idToDeleteJob.size();
    }

    public long getDeleteInfoCount() {
        return dbToDeleteInfos.values().stream().mapToLong(List::size).sum();
    }

    public void save(ImageWriter imageWriter) throws IOException, SRMetaBlockException {
        int numJson = 1 + dbToDeleteInfos.size() * 2;
        SRMetaBlockWriter writer = imageWriter.getBlockWriter(SRMetaBlockID.DELETE_MGR, numJson);

        writer.writeInt(dbToDeleteInfos.size());
        for (Map.Entry<Long, List<MultiDeleteInfo>> deleteInfoEntry : dbToDeleteInfos.entrySet()) {
            writer.writeLong(deleteInfoEntry.getKey());
            writer.writeJson(deleteInfoEntry.getValue());
        }
        writer.close();
    }

    public void load(SRMetaBlockReader reader) throws IOException, SRMetaBlockException, SRMetaBlockEOFException {
        reader.readMap(Long.class, new TypeToken<List<MultiDeleteInfo>>() {}.getType(),
                dbToDeleteInfos::put);
    }

    @Override
    public Map<String, Long> estimateCount() {
        long count = 0;
        for (List<MultiDeleteInfo> value : dbToDeleteInfos.values()) {
            count += value.size();
        }
        return ImmutableMap.of("DeleteInfo", getDeleteInfoCount(),
                "DeleteJob", (long) idToDeleteJob.size());
    }

    @Override
    public List<Pair<List<Object>, Long>> getSamples() {
        List<Object> samples = dbToDeleteInfos.values()
                .stream()
                .filter(infos -> !infos.isEmpty())
                .map(infos -> infos.stream().findAny().get())
                .collect(Collectors.toList());
        long size = dbToDeleteInfos.values().stream().mapToInt(List::size).sum();
        return Lists.newArrayList(Pair.create(samples, size));
    }
}
