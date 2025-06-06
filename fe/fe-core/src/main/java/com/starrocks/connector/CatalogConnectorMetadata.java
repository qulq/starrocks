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

package com.starrocks.connector;

import com.google.common.collect.ImmutableList;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.IcebergTable;
import com.starrocks.catalog.PartitionKey;
import com.starrocks.catalog.Table;
import com.starrocks.common.AlreadyExistsException;
import com.starrocks.common.AnalysisException;
import com.starrocks.common.DdlException;
import com.starrocks.common.MetaNotFoundException;
import com.starrocks.common.StarRocksException;
import com.starrocks.common.profile.Tracers;
import com.starrocks.connector.informationschema.InformationSchemaMetadata;
import com.starrocks.connector.metadata.MetadataTable;
import com.starrocks.connector.metadata.MetadataTableType;
import com.starrocks.connector.metadata.TableMetaMetadata;
import com.starrocks.credential.CloudConfiguration;
import com.starrocks.qe.ConnectContext;
import com.starrocks.sql.ast.AddPartitionClause;
import com.starrocks.sql.ast.AlterMaterializedViewStmt;
import com.starrocks.sql.ast.AlterTableCommentClause;
import com.starrocks.sql.ast.AlterTableStmt;
import com.starrocks.sql.ast.AlterViewStmt;
import com.starrocks.sql.ast.CancelRefreshMaterializedViewStmt;
import com.starrocks.sql.ast.CreateMaterializedViewStatement;
import com.starrocks.sql.ast.CreateMaterializedViewStmt;
import com.starrocks.sql.ast.CreateTableLikeStmt;
import com.starrocks.sql.ast.CreateTableStmt;
import com.starrocks.sql.ast.CreateViewStmt;
import com.starrocks.sql.ast.DropMaterializedViewStmt;
import com.starrocks.sql.ast.DropPartitionClause;
import com.starrocks.sql.ast.DropTableStmt;
import com.starrocks.sql.ast.PartitionRenameClause;
import com.starrocks.sql.ast.RefreshMaterializedViewStatement;
import com.starrocks.sql.ast.TableRenameClause;
import com.starrocks.sql.ast.TruncateTableStmt;
import com.starrocks.sql.optimizer.OptimizerContext;
import com.starrocks.sql.optimizer.operator.scalar.ColumnRefOperator;
import com.starrocks.sql.optimizer.operator.scalar.ScalarOperator;
import com.starrocks.sql.optimizer.statistics.Statistics;
import com.starrocks.thrift.TSinkCommitInfo;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.FileContent;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;
import static com.starrocks.catalog.system.information.InfoSchemaDb.isInfoSchemaDb;
import static java.util.Objects.requireNonNull;

// CatalogConnectorMetadata provides a uniform interface to provide normal tables and information schema tables.
// The database name/id is used to route request to specific metadata.
public class CatalogConnectorMetadata implements ConnectorMetadata {
    private final ConnectorMetadata normal;
    private final ConnectorMetadata informationSchema;
    private final ConnectorMetadata tableMetadata;

    public CatalogConnectorMetadata(ConnectorMetadata normal,
                                    ConnectorMetadata informationSchema,
                                    ConnectorMetadata tableMetadata) {
        requireNonNull(normal, "metadata is null");
        requireNonNull(informationSchema, "infoSchemaDb is null");
        checkArgument(informationSchema instanceof InformationSchemaMetadata);
        this.normal = normal;
        this.informationSchema = informationSchema;
        this.tableMetadata = tableMetadata;
    }

    private ConnectorMetadata metadataOfTable(String tableName) {
        // Paimon system table shares the same pattern, ignore it here
        if (getTableType() != Table.TableType.PAIMON && TableMetaMetadata.isMetadataTable(tableName)) {
            return tableMetadata;
        }
        return null;
    }

    private ConnectorMetadata metadataOfTable(Table table) {
        if (table instanceof MetadataTable) {
            return tableMetadata;
        }

        return null;
    }

    private ConnectorMetadata metadataOfDb(String dBName) {
        if (isInfoSchemaDb(dBName)) {
            return informationSchema;
        }
        return normal;
    }

    @Override
    public Table.TableType getTableType() {
        return normal.getTableType();
    }

    @Override
    public List<String> listDbNames(ConnectContext context) {
        return ImmutableList.<String>builder()
                .addAll(this.normal.listDbNames(context))
                .addAll(this.informationSchema.listDbNames(context))
                .build();
    }

    @Override
    public List<String> listTableNames(ConnectContext context, String dbName) {
        ConnectorMetadata metadata = metadataOfDb(dbName);
        return metadata.listTableNames(context, dbName);
    }

    @Override
    public List<String> listPartitionNames(String databaseName, String tableName, ConnectorMetadatRequestContext requestContext) {
        return normal.listPartitionNames(databaseName, tableName, requestContext);
    }

    @Override
    public List<String> listPartitionNamesByValue(String databaseName, String tableName,
                                                  List<Optional<String>> partitionValues) {
        return normal.listPartitionNamesByValue(databaseName, tableName, partitionValues);
    }

    @Override
    public Table getTable(ConnectContext context, String dbName, String tblName) {
        ConnectorMetadata metadata = metadataOfTable(tblName);
        if (metadata == null) {
            metadata = metadataOfDb(dbName);
        }

        return metadata.getTable(context, dbName, tblName);
    }

    @Override
    public TableVersionRange getTableVersionRange(String dbName, Table table,
                                                  Optional<ConnectorTableVersion> startVersion,
                                                  Optional<ConnectorTableVersion> endVersion) {
        ConnectorMetadata metadata = metadataOfTable(table);
        if (metadata == null) {
            metadata = metadataOfDb(dbName);
        }

        return metadata.getTableVersionRange(dbName, table, startVersion, endVersion);
    }

    @Override
    public boolean tableExists(ConnectContext context, String dbName, String tblName) {
        ConnectorMetadata metadata = metadataOfDb(dbName);
        return metadata.tableExists(context, dbName, tblName);
    }

    @Override
    public List<RemoteFileInfo> getRemoteFiles(Table table, GetRemoteFilesParams params) {
        return normal.getRemoteFiles(table, params);
    }

    @Override
    public RemoteFileInfoSource getRemoteFilesAsync(Table table, GetRemoteFilesParams params) {
        return normal.getRemoteFilesAsync(table, params);
    }

    @Override
    public boolean prepareMetadata(MetaPreparationItem item, Tracers tracers, ConnectContext connectContext) {
        return normal.prepareMetadata(item, tracers, connectContext);
    }

    @Override
    public List<PartitionInfo> getRemotePartitions(Table table, List<String> partitionNames) {
        return normal.getRemotePartitions(table, partitionNames);
    }

    @Override
    public SerializedMetaSpec getSerializedMetaSpec(String dbName, String tableName,
                                                    long snapshotId, String serializedPredicate, MetadataTableType type) {
        return normal.getSerializedMetaSpec(dbName, tableName, snapshotId, serializedPredicate, type);
    }

    @Override
    public List<PartitionInfo> getPartitions(Table table, List<String> partitionNames) {
        return normal.getPartitions(table, partitionNames);
    }

    @Override
    public Statistics getTableStatistics(OptimizerContext session, Table table, Map<ColumnRefOperator, Column> columns,
                                         List<PartitionKey> partitionKeys, ScalarOperator predicate, long limit,
                                         TableVersionRange version) {
        return normal.getTableStatistics(session, table, columns, partitionKeys, predicate, limit, version);
    }

    @Override
    public Set<DeleteFile> getDeleteFiles(IcebergTable table, Long snapshotId, ScalarOperator predicate, FileContent content) {
        return normal.getDeleteFiles(table, snapshotId, predicate, content);
    }

    @Override
    public void clear() {
        normal.clear();
    }

    @Override
    public void refreshTable(String srDbName, Table table, List<String> partitionNames, boolean onlyCachedPartitions) {
        normal.refreshTable(srDbName, table, partitionNames, onlyCachedPartitions);
    }

    @Override
    public boolean dbExists(ConnectContext context, String dbName) {
        ConnectorMetadata metadata = metadataOfDb(dbName);
        return metadata.dbExists(context, dbName);
    }

    @Override
    public void createDb(ConnectContext context, String dbName, Map<String, String> properties)
            throws DdlException, AlreadyExistsException {
        normal.createDb(context, dbName, properties);
    }

    @Override
    public void dropDb(ConnectContext context, String dbName, boolean isForceDrop) throws DdlException, MetaNotFoundException {
        normal.dropDb(context, dbName, isForceDrop);
    }

    @Override
    public Database getDb(ConnectContext context, String name) {
        ConnectorMetadata metadata = metadataOfDb(name);
        return metadata.getDb(context, name);
    }

    @Override
    public boolean createTable(ConnectContext context, CreateTableStmt stmt) throws DdlException {
        return normal.createTable(context, stmt);
    }

    @Override
    public void dropTable(ConnectContext context, DropTableStmt stmt) throws DdlException {
        normal.dropTable(context, stmt);
    }

    @Override
    public void finishSink(String dbName, String table, List<TSinkCommitInfo> commitInfos, String branch) {
        normal.finishSink(dbName, table, commitInfos, branch);
    }

    @Override
    public void abortSink(String dbName, String table, List<TSinkCommitInfo> commitInfos) {
        normal.abortSink(dbName, table, commitInfos);
    }

    @Override
    public void alterTable(ConnectContext context, AlterTableStmt stmt) throws StarRocksException {
        normal.alterTable(context, stmt);
    }

    @Override
    public void renameTable(Database db, Table table, TableRenameClause tableRenameClause) throws DdlException {
        normal.renameTable(db, table, tableRenameClause);
    }

    @Override
    public void alterTableComment(Database db, Table table, AlterTableCommentClause clause) {
        normal.alterTableComment(db, table, clause);
    }

    @Override
    public void truncateTable(TruncateTableStmt truncateTableStmt, ConnectContext context) throws DdlException {
        normal.truncateTable(truncateTableStmt, context);
    }

    @Override
    public void createTableLike(CreateTableLikeStmt stmt) throws DdlException {
        normal.createTableLike(stmt);
    }

    @Override
    public void addPartitions(ConnectContext ctx, Database db, String tableName, AddPartitionClause addPartitionClause)
            throws DdlException {
        normal.addPartitions(ctx, db, tableName, addPartitionClause);
    }

    @Override
    public void dropPartition(Database db, Table table, DropPartitionClause clause) throws DdlException {
        normal.dropPartition(db, table, clause);
    }

    @Override
    public void renamePartition(Database db, Table table, PartitionRenameClause renameClause) throws DdlException {
        normal.renamePartition(db, table, renameClause);
    }

    @Override
    public void createMaterializedView(CreateMaterializedViewStmt stmt) throws AnalysisException, DdlException {
        normal.createMaterializedView(stmt);
    }

    @Override
    public void createMaterializedView(CreateMaterializedViewStatement statement) throws DdlException {
        normal.createMaterializedView(statement);
    }

    @Override
    public void dropMaterializedView(DropMaterializedViewStmt stmt) throws DdlException, MetaNotFoundException {
        normal.dropMaterializedView(stmt);
    }

    @Override
    public void alterMaterializedView(AlterMaterializedViewStmt stmt)
            throws DdlException, MetaNotFoundException, AnalysisException {
        normal.alterMaterializedView(stmt);
    }

    @Override
    public String refreshMaterializedView(RefreshMaterializedViewStatement refreshMaterializedViewStatement)
            throws DdlException, MetaNotFoundException {
        return normal.refreshMaterializedView(refreshMaterializedViewStatement);
    }

    @Override
    public void cancelRefreshMaterializedView(
            CancelRefreshMaterializedViewStmt stmt) throws DdlException, MetaNotFoundException {
        normal.cancelRefreshMaterializedView(stmt);
    }

    @Override
    public void createView(ConnectContext context, CreateViewStmt stmt) throws DdlException {
        normal.createView(context, stmt);
    }

    @Override
    public void alterView(ConnectContext context, AlterViewStmt stmt) throws StarRocksException {
        normal.alterView(context, stmt);
    }

    @Override
    public CloudConfiguration getCloudConfiguration() {
        return normal.getCloudConfiguration();
    }
}
