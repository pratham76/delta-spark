/*
 * Copyright (2021) The Delta Lake Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.delta.commands

// scalastyle:off import.ordering.noEmptyLine
import java.util.concurrent.TimeUnit

import org.apache.spark.sql.delta.skipping.clustering.ClusteredTableUtils
import org.apache.spark.sql.delta._
import org.apache.spark.sql.delta.DeltaColumnMapping.filterColumnMappingProperties
import org.apache.spark.sql.delta.actions.{Action, Metadata, Protocol, TableFeatureProtocolUtils}
import org.apache.spark.sql.delta.actions.DomainMetadata
import org.apache.spark.sql.delta.commands.DMLUtils.TaggedCommitData
import org.apache.spark.sql.delta.coordinatedcommits.{CatalogOwnedTableUtils, CoordinatedCommitsUtils}
import org.apache.spark.sql.delta.hooks.{HudiConverterHook, IcebergConverterHook}
import org.apache.spark.sql.delta.logging.DeltaLogKeys
import org.apache.spark.sql.delta.metering.DeltaLogging
import org.apache.spark.sql.delta.schema.SchemaUtils
import org.apache.spark.sql.delta.sources.DeltaSQLConf
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}

import org.apache.spark.SparkContext
import org.apache.spark.internal.MDC
import org.apache.spark.sql._
import org.apache.spark.sql.catalyst.catalog.{CatalogTable, CatalogTableType}
import org.apache.spark.sql.catalyst.expressions.Attribute
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.execution.command.{LeafRunnableCommand, RunnableCommand}
import org.apache.spark.sql.execution.metric.SQLMetric
import org.apache.spark.sql.execution.metric.SQLMetrics.createMetric
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types.StructType
import org.apache.spark.util.Utils

/**
 * Single entry point for all write or declaration operations for Delta tables accessed through
 * the table name.
 *
 * @param table `CatalogTable` object representing the table to create
 * @param existingTableOpt The existing table for the same identifier if exists
 * @param mode The save mode when writing data. Relevant when the query is empty or set to Ignore
 *             with `CREATE TABLE IF NOT EXISTS`.
 * @param query The query to commit into the Delta table if it exist. This can come from
 *                - CTAS
 *                - saveAsTable
 * @param operation The table creation mode
 * @param tableByPath Whether the table is identified by path
 * @param output SQL output of the command
 * @param protocol This is used to create a table with specific protocol version
 * @param createTableFunc If specified, call this function to create the table, instead of
 *                        Spark `SessionCatalog#createTable` which is backed by Hive Metastore.
 */
case class CreateDeltaTableCommand(
    override val table: CatalogTable,
    override val existingTableOpt: Option[CatalogTable],
    override val mode: SaveMode,
    query: Option[LogicalPlan],
    override val operation: TableCreationModes.CreationMode = TableCreationModes.Create,
    override val tableByPath: Boolean = false,
    override val output: Seq[Attribute] = Nil,
    protocol: Option[Protocol] = None,
    createTableFunc: Option[CatalogTable => Unit] = None)
  extends LeafRunnableCommand
  with DeltaCommand
  with DeltaLogging
  with CreateDeltaTableLike {

  @transient
  private lazy val sc: SparkContext = SparkContext.getOrCreate()

  override lazy val metrics = Map[String, SQLMetric](
    "numCopiedFiles" -> createMetric(sc, "number of files copied"),
    "copiedFilesSize" -> createMetric(sc, "size of files copied"),
    "executionTimeMs" -> createMetric(sc, "time taken to execute the entire operation"),
    "numRemovedBytes" -> createMetric(sc, "number of bytes removed"),
    "removedFilesSize" -> createMetric(sc, "size of files removed"),
    "sourceTableSize" -> createMetric(sc, "size of source table"),
    "numOutputRows" -> createMetric(sc, "number of output rows"),
    "numParts" -> createMetric(sc, "number of partitions"),
    "numFiles" -> createMetric(sc, "number of written files"),
    "sourceNumOfFiles" -> createMetric(sc, "number of files in source table"),
    "numRemovedFiles" -> createMetric(sc, "number of files removed."),
    "numOutputBytes" -> createMetric(sc, "number of output bytes"),
    "taskCommitTime" -> createMetric(sc, "task commit time"),
    "jobCommitTime" -> createMetric(sc, "job commit time"),
    "numOfSyncedTransactions" -> createMetric(sc, "number of synced transactions")
  )

  override def run(sparkSession: SparkSession): Seq[Row] = {

    assert(table.tableType != CatalogTableType.VIEW)
    assert(table.identifier.database.isDefined, "Database should've been fixed at analysis")
    // There is a subtle race condition here, where the table can be created by someone else
    // while this command is running. Nothing we can do about that though :(
    val tableExistsInCatalog = existingTableOpt.isDefined
    if (mode == SaveMode.Ignore && tableExistsInCatalog) {
      // Early exit on ignore
      return Nil
    } else if (mode == SaveMode.ErrorIfExists && tableExistsInCatalog) {
      throw DeltaErrors.tableAlreadyExists(table)
    }
    // This check should be relaxed once the UC client supports creating tables,
    // It gets bypassed in UTs to allow tests that use InMemoryCommitCoordinator to create tables
    val tableFeatures = TableFeatureProtocolUtils.
      getSupportedFeaturesFromTableConfigs(table.properties)
    if (!Utils.isTesting && (tableFeatures.contains(CatalogOwnedTableFeature) ||
      CatalogOwnedTableUtils.defaultCatalogOwnedEnabled(spark = sparkSession))) {
      throw DeltaErrors.deltaCannotCreateCatalogOwnedTable()
    }

    val tableWithLocation = getCatalogTableWithLocation(sparkSession)

    val tableLocation = getDeltaTablePath(tableWithLocation)
    // To be safe, here we only extract file system options from table storage properties, to create
    // the DeltaLog.
    val fileSystemOptions = table.storage.properties.filter { case (k, _) =>
      DeltaTableUtils.validDeltaTableHadoopPrefixes.exists(k.startsWith)
    }
    val deltaLog = DeltaLog.forTable(sparkSession, tableLocation, fileSystemOptions)
    CoordinatedCommitsUtils.validateConfigurationsForCreateDeltaTableCommand(
      sparkSession, deltaLog.tableExists, query, tableWithLocation.properties)
    CatalogOwnedTableUtils.validatePropertiesForCreateDeltaTableCommand(
      sparkSession, deltaLog.tableExists, query, tableWithLocation.properties)

    recordDeltaOperation(deltaLog, "delta.ddl.createTable") {
      val result = handleCommit(sparkSession, deltaLog, tableWithLocation)
      sendDriverMetrics(sparkSession, metrics)
      result
    }
  }

  /**
   * Handles the transaction logic for the command. Returns the operation metrics in case of CLONE.
   */
  private def handleCommit(
      sparkSession: SparkSession,
      deltaLog: DeltaLog,
      tableWithLocation: CatalogTable): Seq[Row] = {
    val tableExistsInCatalog = existingTableOpt.isDefined
    val hadoopConf = deltaLog.newDeltaHadoopConf()
    val tableLocation = getDeltaTablePath(tableWithLocation)
    val fs = tableLocation.getFileSystem(hadoopConf)

    def checkPathEmpty(txn: OptimisticTransaction): Unit = {
      // Verify the table does not exist.
      if (mode == SaveMode.Ignore || mode == SaveMode.ErrorIfExists) {
        // We should have returned earlier in Ignore and ErrorIfExists mode if the table
        // is already registered in the catalog.
        assert(!tableExistsInCatalog)
        // Verify that the data path does not contain any data.
        // We may have failed a previous write. The retry should still succeed even if we have
        // garbage data
        if (txn.readVersion > -1 || !fs.exists(deltaLog.logPath)) {
          assertPathEmpty(hadoopConf, tableWithLocation)
        }
      }
    }

    var txn = startTxnForTableCreation(sparkSession, deltaLog, tableWithLocation)

    OptimisticTransaction.withActive(txn) {
      val result = query match {
        // CLONE handled separately from other CREATE TABLE syntax
        case Some(cmd: CloneTableCommand) =>
          checkPathEmpty(txn)
          cmd.handleClone(
            sparkSession,
            txn,
            targetDeltaLog = deltaLog,
            commandMetrics = Some(metrics))
        case Some(deltaWriter: WriteIntoDeltaLike) =>
          checkPathEmpty(txn)
          txn = handleCreateTableAsSelect(
            sparkSession, txn, deltaLog, deltaWriter, tableWithLocation)
          Nil
        case Some(query) =>
          checkPathEmpty(txn)
          require(!query.isInstanceOf[RunnableCommand])
          // When using V1 APIs, the `query` plan is not yet optimized, therefore, it is safe
          // to once again go through analysis
          val data = DataFrameUtils.ofRows(sparkSession, query)
          val options = new DeltaOptions(table.storage.properties, sparkSession.sessionState.conf)
          val deltaWriter = WriteIntoDelta(
            deltaLog = deltaLog,
            mode = mode,
            options,
            partitionColumns = table.partitionColumnNames,
            configuration = tableWithLocation.properties + ("comment" -> table.comment.orNull),
            data = data,
            Some(tableWithLocation))
          txn = handleCreateTableAsSelect(
            sparkSession, txn, deltaLog, deltaWriter, tableWithLocation)
          Nil
        case _ =>
          handleCreateTable(sparkSession, txn, tableWithLocation, fs, hadoopConf)
          Nil
      }

      runPostCommitUpdates(sparkSession, txn, deltaLog, tableWithLocation)

      result
    }
  }

  /**
   * Runs updates post table creation commit, such as updating the catalog
   * with relevant information.
   */
  private def runPostCommitUpdates(
      sparkSession: SparkSession,
      txnUsedForCommit: OptimisticTransaction,
      deltaLog: DeltaLog,
      tableWithLocation: CatalogTable): Unit = {
    // Note that someone may have dropped and recreated the table in a separate location in the
    // meantime... Unfortunately we can't do anything there at the moment, because Hive sucks.
    logInfo(log"Table is path-based table: ${MDC(DeltaLogKeys.IS_PATH_TABLE, tableByPath)}. " +
      log"Update catalog with mode: ${MDC(DeltaLogKeys.OPERATION, operation)}")
    val opStartTs = TimeUnit.NANOSECONDS.toMillis(txnUsedForCommit.txnStartTimeNs)
    val postCommitSnapshot = deltaLog.update(
      checkIfUpdatedSinceTs = Some(opStartTs),
      catalogTableOpt = Some(tableWithLocation))
    val didNotChangeMetadata = txnUsedForCommit.metadata == txnUsedForCommit.snapshot.metadata
    updateCatalog(
      sparkSession,
      tableWithLocation,
      postCommitSnapshot,
      query,
      didNotChangeMetadata,
      createTableFunc)


    if (UniversalFormat.icebergEnabled(postCommitSnapshot.metadata) &&
        !txnUsedForCommit.containsPostCommitHook(IcebergConverterHook)) {
      deltaLog.icebergConverter.convertSnapshot(postCommitSnapshot, tableWithLocation)
    }

    if (UniversalFormat.hudiEnabled(postCommitSnapshot.metadata) &&
        !txnUsedForCommit.containsPostCommitHook(HudiConverterHook)) {
      deltaLog.hudiConverter.convertSnapshot(postCommitSnapshot, tableWithLocation)
    }
  }

  /**
   * Handles the transaction logic for CTAS-like statements, i.e.:
   * CREATE TABLE AS SELECT
   * CREATE OR REPLACE TABLE AS SELECT
   * .saveAsTable in DataframeWriter API
   *
   * @return the txn used to make Delta commit
   */
  private def handleCreateTableAsSelect(
      sparkSession: SparkSession,
      txn: OptimisticTransaction,
      deltaLog: DeltaLog,
      deltaWriter: WriteIntoDeltaLike,
      tableWithLocation: CatalogTable): OptimisticTransaction = {
    val isManagedTable = tableWithLocation.tableType == CatalogTableType.MANAGED
    val options = new DeltaOptions(table.storage.properties, sparkSession.sessionState.conf)

    // Execute write command for `deltaWriter` by
    //   - replacing the metadata new target table for DataFrameWriterV2 writer if it is a
    //     REPLACE or CREATE_OR_REPLACE command,
    //   - running the write procedure of DataFrameWriter command and returning the
    //     new created actions,
    //   - returning the Delta Operation type of this DataFrameWriter
    def doDeltaWrite(
        deltaWriter: WriteIntoDeltaLike,
        schema: StructType): (TaggedCommitData[Action], DeltaOperations.Operation) = {
      // In the V2 Writer, methods like "replace" and "createOrReplace" implicitly mean that
      // the metadata should be changed. This wasn't the behavior for DataFrameWriterV1.
      if (!isV1Writer) {
        replaceMetadataIfNecessary(
          txn,
          tableWithLocation,
          options,
          sparkSession,
          schema)
      }
      var taggedCommitData = deltaWriter.writeAndReturnCommitData(
        txn,
        sparkSession,
        ClusteredTableUtils.getClusterBySpecOptional(table),
        // Pass this option to the writer so that it can differentiate between an INSERT and a
        // REPLACE command. This is needed because the writer is shared between the two commands.
        // But some options, such as dynamic partition overwrite, are only valid for INSERT.
        // Only allow createOrReplace command which is not a V1 writer.
        // saveAsTable() command uses this same code path and is marked as a V1 writer.
        // We do not want saveAsTable() to be treated as a REPLACE command wrt dynamic partition
        // overwrite.
        isTableReplace = isReplace && !isV1Writer
      )
      // The 'deltaWriter' initialized the schema. Remove 'EXISTS_DEFAULT' metadata keys because
      // they are not required on tables created by CTAS.
      txn.removeExistsDefaultFromSchema()
      // Metadata updates for creating table (with any writer) and replacing table
      // (only with V1 writer) will be handled inside WriteIntoDelta.
      // For createOrReplace operation, metadata updates are handled here if the table already
      // exists (replacing table), otherwise it is handled inside WriteIntoDelta (creating table).
      if (!isV1Writer && isReplace && txn.readVersion > -1L) {
        val newDomainMetadata = Seq.empty[DomainMetadata] ++
          ClusteredTableUtils.getDomainMetadataFromTransaction(
            ClusteredTableUtils.getClusterBySpecOptional(table), txn)
        // Ensure to remove any domain metadata for REPLACE TABLE.
        val newActions = taggedCommitData.actions ++
          DomainMetadataUtils.handleDomainMetadataForReplaceTable(
            txn.snapshot.domainMetadata, newDomainMetadata)
        taggedCommitData = taggedCommitData.copy(actions = newActions)
      }
      val op = getOperation(txn.metadata, isManagedTable, Some(options),
        clusterBy = ClusteredTableUtils.getLogicalClusteringColumnNames(
          txn, taggedCommitData.actions)
      )
      (taggedCommitData, op)
    }
    val updatedConfiguration = UniversalFormat.enforceDependenciesInConfiguration(
      sparkSession,
      deltaWriter.configuration,
      txn.snapshot
    )
    val updatedWriter = deltaWriter.withNewWriterConfiguration(updatedConfiguration)
    var txnToReturn = txn
    // We are either appending/overwriting with saveAsTable or creating a new table with CTAS
    if (!hasBeenExecuted(txn, sparkSession, Some(options))) {
      val (taggedCommitData, op) = doDeltaWrite(updatedWriter, updatedWriter.data.schema.asNullable)
      txn.commit(taggedCommitData.actions, op, tags = taggedCommitData.stringTags)
    }
    txnToReturn
  }

  /**
   * Handles the transaction logic for CREATE OR REPLACE TABLE statement
   * without the AS [CLONE, SELECT] clause.
   */
  private def handleCreateTable(
      sparkSession: SparkSession,
      txn: OptimisticTransaction,
      tableWithLocation: CatalogTable,
      fs: FileSystem,
      hadoopConf: Configuration): Unit = {

    val isManagedTable = tableWithLocation.tableType == CatalogTableType.MANAGED
    val tableLocation = getDeltaTablePath(tableWithLocation)
    val tableExistsInCatalog = existingTableOpt.isDefined
    val options = new DeltaOptions(table.storage.properties, sparkSession.sessionState.conf)

    def createActionsForNewTableOrVerify(): Seq[Action] = {
      if (isManagedTable) {
        // When creating a managed table, the table path should not exist or is empty, or
        // users would be surprised to see the data, or see the data directory being dropped
        // after the table is dropped.
        assertPathEmpty(hadoopConf, tableWithLocation)
      }

      // However, if we allow creating an empty schema table and indeed the table is new, we
      // would need to make sure txn.readVersion <= 0 so we are either:
      // 1) Creating a new empty schema table (version = -1) or
      // 2) Restoring an existing empty schema table at version 0. An empty schema table should
      //    not have versions > 0 because it must be written with schema changes after initial
      //    creation.
      val emptySchemaTableFlag = sparkSession.sessionState.conf
        .getConf(DeltaSQLConf.DELTA_ALLOW_CREATE_EMPTY_SCHEMA_TABLE)
      val allowRestoringExistingEmptySchemaTable =
        emptySchemaTableFlag && txn.metadata.schema.isEmpty && txn.readVersion == 0
      val allowCreatingNewEmptySchemaTable =
        emptySchemaTableFlag && tableWithLocation.schema.isEmpty && txn.readVersion == -1

      // This is either a new table, or, we never defined the schema of the table. While it is
      // unexpected that `txn.metadata.schema` to be empty when txn.readVersion >= 0, we still
      // guard against it, in case of checkpoint corruption bugs.
      val noExistingMetadata = txn.readVersion == -1 || txn.metadata.schema.isEmpty
      if (noExistingMetadata && !allowRestoringExistingEmptySchemaTable) {
        assertTableSchemaDefined(
          fs, tableLocation, tableWithLocation, sparkSession,
          allowCreatingNewEmptySchemaTable
        )
        assertPathEmpty(hadoopConf, tableWithLocation)
        // This is a user provided schema.
        // Doesn't come from a query, Follow nullability invariants.
        var newMetadata =
          getProvidedMetadata(tableWithLocation, table.schema.json)
        newMetadata = newMetadata.copy(configuration =
          UniversalFormat.enforceDependenciesInConfiguration(
            sparkSession,
            newMetadata.configuration,
            txn.snapshot
          ))

        txn.updateMetadataForNewTable(newMetadata)
        // Remove 'EXISTS_DEFAULT' because it is not required for tables created with CREATE TABLE.
        txn.removeExistsDefaultFromSchema()
        protocol.foreach { protocol =>
          // For commands like CREATE LIKE, the `protocol` here may contain table features
          // from source table. It will override the `newProtocol` being created in the above
          // `txn.updateMetadataForNewTable`.
          // In order to enable [[CatalogOwnedTableFeature]] for target table w/ default
          // spark configuration of CatalogOwned enabled, we need to manually append
          // [[CatalogOwnedTableFeature]] here to the existing source table protocol.
          val finalizedProtocol = if (CatalogOwnedTableUtils.defaultCatalogOwnedEnabled(
              spark = sparkSession)) {
            val minCatalogOwnedProtocol = Protocol(
              CatalogOwnedTableFeature.minReaderVersion,
              CatalogOwnedTableFeature.minWriterVersion).withFeature(CatalogOwnedTableFeature)
            protocol.merge(minCatalogOwnedProtocol)
          } else {
            protocol
          }
          txn.updateProtocol(finalizedProtocol)
        }
        ClusteredTableUtils.getDomainMetadataFromTransaction(
          ClusteredTableUtils.getClusterBySpecOptional(table), txn).toSeq
      } else {
        verifyTableMetadata(sparkSession, txn, tableWithLocation)
        Nil
      }
    }

    // We are defining a table using the Create or Replace Table statements.
    val actionsToCommit = operation match {
      case TableCreationModes.Create =>
        require(!tableExistsInCatalog, "Can't recreate a table when it exists")
        createActionsForNewTableOrVerify()

      case TableCreationModes.CreateOrReplace if !tableExistsInCatalog =>
        // If the table doesn't exist, CREATE OR REPLACE must provide a schema
        if (tableWithLocation.schema.isEmpty) {
          throw DeltaErrors.schemaNotProvidedException
        }
        createActionsForNewTableOrVerify()
      case _ =>
        // When the operation is a REPLACE or CREATE OR REPLACE, then the schema shouldn't be
        // empty, since we'll use the entry to replace the schema
        if (tableWithLocation.schema.isEmpty) {
          throw DeltaErrors.schemaNotProvidedException
        }
        // This can happen if someone deleted files from the filesystem but
        // the table still exists in the catalog.
        if (txn.readVersion == -1 && tableExistsInCatalog) {
          throw DeltaErrors.metadataAbsentForExistingCatalogTable(
            tableWithLocation.identifier.toString, txn.deltaLog.logPath.toString)
        }
        // We need to replace
        replaceMetadataIfNecessary(
          txn,
          tableWithLocation,
          options,
          sparkSession,
          tableWithLocation.schema)
        // Remove 'EXISTS_DEFAULT' because it is not required for tables created with REPLACE TABLE.
        txn.removeExistsDefaultFromSchema()
        // Truncate the table
        val operationTimestamp = System.currentTimeMillis()
        var actionsToCommit = Seq.empty[Action]
        val removes = txn.filterFiles().map(_.removeWithTimestamp(operationTimestamp))
        actionsToCommit = removes ++
          DomainMetadataUtils.handleDomainMetadataForReplaceTable(
            txn.snapshot.domainMetadata,
            ClusteredTableUtils.getDomainMetadataFromTransaction(
              ClusteredTableUtils.getClusterBySpecOptional(table), txn).toSeq)
        actionsToCommit
    }

    val changedMetadata = txn.metadata != txn.snapshot.metadata
    val changedProtocol = txn.protocol != txn.snapshot.protocol
    if (actionsToCommit.nonEmpty || changedMetadata || changedProtocol) {
      val op = getOperation(txn.metadata, isManagedTable, None,
        clusterBy = ClusteredTableUtils.getLogicalClusteringColumnNames(
          txn, actionsToCommit)
      )
      txn.commit(actionsToCommit, op)
    }
  }

  private def getProvidedMetadata(table: CatalogTable, schemaString: String): Metadata = {
    Metadata(
      description = table.comment.orNull,
      schemaString = schemaString,
      partitionColumns = table.partitionColumnNames,
      // Filter out ephemeral clustering columns config because we don't want to persist
      // it in delta log. This will be persisted in CatalogTable's table properties instead.
      configuration = ClusteredTableUtils.removeClusteringColumnsProperty(table.properties),
      createdTime = Some(System.currentTimeMillis()))
  }

  private def assertPathEmpty(
      hadoopConf: Configuration,
      tableWithLocation: CatalogTable): Unit = {
    val path = getDeltaTablePath(tableWithLocation)
    val fs = path.getFileSystem(hadoopConf)
    // Verify that the table location associated with CREATE TABLE doesn't have any data. Note that
    // we intentionally diverge from this behavior w.r.t regular datasource tables (that silently
    // overwrite any previous data)
    if (fs.exists(path) && fs.listStatus(path).nonEmpty) {
      throw DeltaErrors.createTableWithNonEmptyLocation(
        tableWithLocation.identifier.toString,
        path.toString)
    }
  }

  private def assertTableSchemaDefined(
      fs: FileSystem,
      path: Path,
      table: CatalogTable,
      sparkSession: SparkSession,
      allowEmptyTableSchema: Boolean): Unit = {
    // Users did not specify the schema. We expect the schema exists in Delta.
    if (table.schema.isEmpty) {
      if (table.tableType == CatalogTableType.EXTERNAL) {
        if (fs.exists(path) && fs.listStatus(path).nonEmpty) {
          throw DeltaErrors.createExternalTableWithoutLogException(
            path, table.identifier.quotedString, sparkSession)
        } else {
          if (allowEmptyTableSchema) return
          throw DeltaErrors.createExternalTableWithoutSchemaException(
            path, table.identifier.quotedString, sparkSession)
        }
      } else {
        if (allowEmptyTableSchema) return
        throw DeltaErrors.createManagedTableWithoutSchemaException(
          table.identifier.quotedString, sparkSession)
      }
    }
  }


  /**
   * When creating an external table in a location where some table already existed, we make sure
   * that the specified table properties match the existing table properties. Since Coordinated
   * Commits is not designed to be overridden, we should not error out if the command omits these
   * properties. If the existing table has Coordinated Commits enabled, we also do not error out if
   * the command omits the ICT properties, which are the dependencies for Coordinated Commits.
   */
  private def filterCoordinatedCommitsProperties(
      existingProperties: Map[String, String],
      tableProperties: Map[String, String]): Map[String, String] = {
    var filteredExistingProperties = existingProperties
    val overridingCCConfs = CoordinatedCommitsUtils.getExplicitCCConfigurations(tableProperties)
    val existingCCConfs = CoordinatedCommitsUtils.getExplicitCCConfigurations(existingProperties)
    if (existingCCConfs.nonEmpty && overridingCCConfs.isEmpty) {
      filteredExistingProperties --= CoordinatedCommitsUtils.TABLE_PROPERTY_KEYS
      val overridingICTConfs = CoordinatedCommitsUtils.getExplicitICTConfigurations(tableProperties)
      val existingICTConfs = CoordinatedCommitsUtils.getExplicitICTConfigurations(
        existingProperties)
      if (existingICTConfs.nonEmpty && overridingICTConfs.isEmpty) {
        filteredExistingProperties --= CoordinatedCommitsUtils.ICT_TABLE_PROPERTY_KEYS
      }
    }
    filteredExistingProperties
  }

  /**
   * Verify against our transaction metadata that the user specified the right metadata for the
   * table.
   */
  private def verifyTableMetadata(
      sparkSession: SparkSession,
      txn: OptimisticTransaction,
      tableDesc: CatalogTable): Unit = {
    val existingMetadata = txn.metadata
    val path = getDeltaTablePath(tableDesc)

    // The delta log already exists. If they give any configuration, we'll make sure it all matches.
    // Otherwise we'll just go with the metadata already present in the log.
    // The schema compatibility checks will be made in `WriteIntoDelta` for CreateTable
    // with a query
    if (txn.readVersion > -1) {
      if (tableDesc.schema.nonEmpty) {
        // We check exact alignment on create table if everything is provided
        // However, if in column mapping mode, we can safely ignore the related metadata fields in
        // existing metadata because new table desc will not have related metadata assigned yet
        val differences = SchemaUtils.reportDifferences(
          DeltaTableUtils.removeInternalDeltaMetadata(sparkSession, existingMetadata.schema),
          tableDesc.schema)
        if (differences.nonEmpty) {
          throw DeltaErrors.createTableWithDifferentSchemaException(
            path, tableDesc.schema, existingMetadata.schema, differences)
        }

        // If schema is specified, we must make sure the partitioning matches, even the partitioning
        // is not specified.
        if (tableDesc.partitionColumnNames != existingMetadata.partitionColumns) {
          throw DeltaErrors.createTableWithDifferentPartitioningException(
            path, tableDesc.partitionColumnNames, existingMetadata.partitionColumns)
        }
        // If schema is specified, we must make sure the clustering column matches (includes when
        // clustering is not specified).
        val specifiedClusterBySpec = ClusteredTableUtils.getClusterBySpecOptional(tableDesc)
        val existingClusterBySpec = ClusteredTableUtils.getClusterBySpecOptional(txn.snapshot)
        if (specifiedClusterBySpec != existingClusterBySpec) {
          throw DeltaErrors.createTableWithDifferentClusteringException(
            path,
            specifiedClusterBySpec,
            existingClusterBySpec)
        }
      }

      if (tableDesc.properties.nonEmpty) {
        // When comparing properties of the existing table and the new table, remove some
        // internal column mapping properties for the sake of comparison.
        var filteredTableProperties = filterColumnMappingProperties(
          tableDesc.properties)
        // We also need to remove any protocol-related properties as we're filtering these
        // from the metadata so they won't be present in the table properties.
        filteredTableProperties =
          Protocol.filterProtocolPropsFromTableProps(filteredTableProperties)
        var filteredExistingProperties = filterColumnMappingProperties(
          existingMetadata.configuration)
        // Clustered table has internal table properties in Metadata configurations and they are
        // never configured by the user so remove them before validation.
        if (ClusteredTableUtils.isSupported(txn.protocol)) {
          filteredExistingProperties =
            ClusteredTableUtils.removeInternalTableProperties(filteredExistingProperties) ++
              // Validate clustering columns in CatalogTable.PROP_CLUSTERING_COLUMNS
              // are matched.
              ClusteredTableUtils.getClusteringColumnsAsProperty(txn.snapshot)
          // Note that clustering columns are already stored in the key
          // CatalogTable.PROP_CLUSTERING_COLUMNS.
          filteredTableProperties =
            ClusteredTableUtils.removeInternalTableProperties(filteredTableProperties)
        }
        filteredExistingProperties =
          filterCoordinatedCommitsProperties(filteredExistingProperties, filteredTableProperties)
        if (filteredTableProperties != filteredExistingProperties) {
          throw DeltaErrors.createTableWithDifferentPropertiesException(
            path, filteredTableProperties, filteredExistingProperties)
        }
        // If column mapping properties are present in both configs, verify they're the same value.
        if (!DeltaColumnMapping.verifyInternalProperties(
            tableDesc.properties, existingMetadata.configuration)) {
          throw DeltaErrors.createTableWithDifferentPropertiesException(
            path, tableDesc.properties, existingMetadata.configuration)
        }
      }
    }
  }

  /**
   * Based on the table creation operation, and parameters, we can resolve to different operations.
   * A lot of this is needed for legacy reasons in Databricks Runtime.
   * @param metadata The table metadata, which we are creating or replacing
   * @param isManagedTable Whether we are creating or replacing a managed table
   * @param options Write options, if this was a CTAS/RTAS
   */
  private def getOperation(
      metadata: Metadata,
      isManagedTable: Boolean,
      options: Option[DeltaOptions],
      clusterBy: Option[Seq[String]]
  ): DeltaOperations.Operation = operation match {
    // This is legacy saveAsTable behavior in Databricks Runtime
    case TableCreationModes.Create if existingTableOpt.isDefined &&
      query.isDefined && options.nonEmpty =>
      DeltaOperations.Write(mode, Option(table.partitionColumnNames), options.get.replaceWhere,
        options.flatMap(_.userMetadata)
      )

    // DataSourceV2 table creation
    // CREATE TABLE (non-DataFrameWriter API) doesn't have options syntax
    // (userMetadata uses SQLConf in this case)
    case TableCreationModes.Create =>
      DeltaOperations.CreateTable(
        metadata, isManagedTable, query.isDefined, clusterBy = clusterBy
      )

    // DataSourceV2 table replace
    // REPLACE TABLE (non-DataFrameWriter API) doesn't have options syntax
    // (userMetadata uses SQLConf in this case)
    case TableCreationModes.Replace =>
      DeltaOperations.ReplaceTable(
        metadata, isManagedTable, orCreate = false, query.isDefined, clusterBy = clusterBy
      )

    // Legacy saveAsTable with Overwrite mode
    case TableCreationModes.CreateOrReplace if options.exists(_.replaceWhere.isDefined) =>
      DeltaOperations.Write(mode, Option(table.partitionColumnNames), options.get.replaceWhere,
        options.flatMap(_.userMetadata)
      )

    // New DataSourceV2 saveAsTable with overwrite mode behavior
    case TableCreationModes.CreateOrReplace =>
      DeltaOperations.ReplaceTable(metadata, isManagedTable, orCreate = true, query.isDefined,
        options.flatMap(_.userMetadata), clusterBy = clusterBy
      )
  }

  private def getDeltaTablePath(table: CatalogTable): Path = {
    new Path(table.location)
  }

  /**
   * With DataFrameWriterV2, methods like `replace()` or `createOrReplace()` mean that the
   * metadata of the table should be replaced. If overwriteSchema=false is provided with these
   * methods, then we will verify that the metadata match exactly.
   */
  private def replaceMetadataIfNecessary(
      txn: OptimisticTransaction,
      tableDesc: CatalogTable,
      options: DeltaOptions,
      sparkSession: SparkSession,
      schema: StructType): Unit = {
    // If a user explicitly specifies not to overwrite the schema, during a replace, we should
    // tell them that it's not supported
    val dontOverwriteSchema = options.options.contains(DeltaOptions.OVERWRITE_SCHEMA_OPTION) &&
      !options.canOverwriteSchema
    if (isReplace && dontOverwriteSchema) {
      throw DeltaErrors.illegalUsageException(DeltaOptions.OVERWRITE_SCHEMA_OPTION, "replacing")
    }
    if (txn.readVersion > -1L && isReplace && !dontOverwriteSchema) {
      // When a table already exists, and we're using the DataFrameWriterV2 API to replace
      // or createOrReplace a table, we blindly overwrite the metadata.
      var newMetadata = getProvidedMetadata(table, schema.json)
      val updatedConfig = UniversalFormat.enforceDependenciesInConfiguration(
        sparkSession,
        newMetadata.configuration,
        txn.snapshot)
      newMetadata = newMetadata.copy(configuration = updatedConfig)
      txn.updateMetadataForNewTableInReplace(newMetadata)
    }
  }

  /** Returns true if the current operation could be replacing a table. */
  private def isReplace: Boolean = {
    operation == TableCreationModes.CreateOrReplace ||
      operation == TableCreationModes.Replace
  }

  /** Returns the transaction that should be used for the CREATE/REPLACE commit. */
  private def startTxnForTableCreation(
      sparkSession: SparkSession,
      deltaLog: DeltaLog,
      tableWithLocation: CatalogTable,
      snapshotOpt: Option[Snapshot] = None): OptimisticTransaction = {
    val txn = deltaLog.startTransaction(None, snapshotOpt)
    validatePrerequisitesForClusteredTable(txn.snapshot.protocol, txn.deltaLog)

    // During CREATE (not REPLACE/overwrites), we synchronously run conversion
    //  (if Uniform is enabled) so we always remove the post commit hook here.
    if (!isReplace) {
      txn.unregisterPostCommitHooksWhere(hook => hook.name == IcebergConverterHook.name)
      txn.unregisterPostCommitHooksWhere(hook => hook.name == HudiConverterHook.name)
    }
    txn
  }

  /**
   * Validate pre-requisites for clustered tables for CREATE/REPLACE operations.
   * @param protocol Protocol used for validations. This protocol should
   *                 be used during the CREATE/REPLACE commit.
   * @param deltaLog Delta log used for logging purposes.
   */
  private def validatePrerequisitesForClusteredTable(
      protocol: Protocol,
      deltaLog: DeltaLog): Unit = {
    // Validate a clustered table is not replaced by a partitioned table.
    if (table.partitionColumnNames.nonEmpty &&
      ClusteredTableUtils.isSupported(protocol)) {
      throw DeltaErrors.replacingClusteredTableWithPartitionedTableNotAllowed()
    }
  }
}

// isCreate is true for Create and CreateOrReplace modes. It is false for Replace mode.
object TableCreationModes {
  sealed trait CreationMode {
    def mode: SaveMode
    def isCreate: Boolean = true
  }

  case object Create extends CreationMode {
    override def mode: SaveMode = SaveMode.ErrorIfExists
  }

  case object CreateOrReplace extends CreationMode {
    override def mode: SaveMode = SaveMode.Overwrite
  }

  case object Replace extends CreationMode {
    override def mode: SaveMode = SaveMode.Overwrite
    override def isCreate: Boolean = false
  }
}
