/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hudi

import org.apache.hudi.DataSourceWriteOptions._
import org.apache.hudi.HoodieSparkSqlWriter.StreamingWriteParams
import org.apache.hudi.HoodieStreamingSink.SINK_CHECKPOINT_KEY
import org.apache.hudi.async.{AsyncClusteringService, AsyncCompactService, SparkStreamingAsyncClusteringService, SparkStreamingAsyncCompactService}
import org.apache.hudi.client.SparkRDDWriteClient
import org.apache.hudi.client.common.HoodieSparkEngineContext
import org.apache.hudi.common.model.HoodieCommitMetadata
import org.apache.hudi.common.table.{HoodieTableConfig, HoodieTableMetaClient}
import org.apache.hudi.common.table.marker.MarkerType
import org.apache.hudi.common.table.timeline.HoodieInstant
import org.apache.hudi.common.util.{ClusteringUtils, CommitUtils, CompactionUtils}
import org.apache.hudi.common.util.ValidationUtils.checkArgument
import org.apache.hudi.config.HoodieWriteConfig
import org.apache.hudi.exception.{HoodieCorruptedDataException, HoodieException, TableNotFoundException}
import org.apache.hudi.hadoop.fs.HadoopFSUtils

import org.apache.spark.api.java.JavaSparkContext
import org.apache.spark.sql.{DataFrame, SaveMode, SQLContext}
import org.apache.spark.sql.execution.streaming.{Sink, StreamExecution}
import org.apache.spark.sql.streaming.OutputMode
import org.slf4j.LoggerFactory

import java.lang
import java.util.function.{BiConsumer, Function}

import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

class HoodieStreamingSink(sqlContext: SQLContext,
                          options: Map[String, String],
                          partitionColumns: Seq[String],
                          outputMode: OutputMode)
  extends Sink
    with Serializable {
  @volatile private var latestCommittedBatchId = -1L

  private val log = LoggerFactory.getLogger(classOf[HoodieStreamingSink])

  private val tablePath = options.get("path")
  if (tablePath.isEmpty || tablePath.get == null) {
    throw new HoodieException(s"'path'  must be specified.")
  }
  private var metaClient: Option[HoodieTableMetaClient] = {
    try {
      Some(HoodieTableMetaClient.builder()
        .setConf(HadoopFSUtils.getStorageConfWithCopy(sqlContext.sparkContext.hadoopConfiguration))
        .setBasePath(tablePath.get)
        .build())
    } catch {
      case _: TableNotFoundException =>
        log.warn("Ignore TableNotFoundException as it is first microbatch.")
        Option.empty
    }
  }
  private val retryCnt = options.getOrElse(STREAMING_RETRY_CNT.key,
    STREAMING_RETRY_CNT.defaultValue).toInt
  private val retryIntervalMs = options.getOrElse(STREAMING_RETRY_INTERVAL_MS.key,
    STREAMING_RETRY_INTERVAL_MS.defaultValue).toLong
  private val ignoreFailedBatch = options.getOrElse(STREAMING_IGNORE_FAILED_BATCH.key,
    STREAMING_IGNORE_FAILED_BATCH.defaultValue).toBoolean
  private val disableCompaction = options.getOrElse(STREAMING_DISABLE_COMPACTION.key,
    STREAMING_DISABLE_COMPACTION.defaultValue).toBoolean

  private var isAsyncCompactorServiceShutdownAbnormally = false
  private var isAsyncClusteringServiceShutdownAbnormally = false

  private val mode =
    if (outputMode == OutputMode.Append()) {
      SaveMode.Append
    } else {
      SaveMode.Overwrite
    }

  private var asyncCompactorService: AsyncCompactService = _
  private var asyncClusteringService: AsyncClusteringService = _
  private var writeClient: Option[SparkRDDWriteClient[_]] = Option.empty
  private var hoodieTableConfig: Option[HoodieTableConfig] = Option.empty

  override def addBatch(batchId: Long, data: DataFrame): Unit = this.synchronized {
    if (isAsyncCompactorServiceShutdownAbnormally) {
      throw new IllegalStateException("Async Compactor shutdown unexpectedly")
    }
    if (isAsyncClusteringServiceShutdownAbnormally) {
      log.error("Async clustering service shutdown unexpectedly")
      throw new IllegalStateException("Async clustering service shutdown unexpectedly")
    }

    val queryId = sqlContext.sparkContext.getLocalProperty(StreamExecution.QUERY_ID_KEY)
    checkArgument(queryId != null, "queryId is null")
    if (metaClient.isDefined && canSkipBatch(batchId, options.getOrElse(OPERATION.key, UPSERT_OPERATION_OPT_VAL))) {
      log.warn(s"Skipping already completed batch $batchId in query $queryId")
      // scalastyle:off return
      return
      // scalastyle:on return
    }


    // Override to use direct markers. In Structured streaming, timeline server is closed after
    // first micro-batch and subsequent micro-batches do not have timeline server running.
    // Thus, we can't use timeline-server-based markers.
    var updatedOptions = options.updated(HoodieWriteConfig.MARKERS_TYPE.key(), MarkerType.DIRECT.name())
    // we need auto adjustment enabled for streaming sink since async table services are feasible within the same JVM.
    updatedOptions = updatedOptions.updated(HoodieWriteConfig.AUTO_ADJUST_LOCK_CONFIGS.key, "true")
    updatedOptions = updatedOptions.updated(HoodieSparkSqlWriter.SPARK_STREAMING_BATCH_ID, batchId.toString)
    if (!options.contains(HoodieWriteConfig.EMBEDDED_TIMELINE_SERVER_ENABLE.key())) {
      // if user does not explicitly override, we are disabling timeline server for streaming sink.
      // refer to HUDI-3636 for more details
      updatedOptions = updatedOptions.updated(HoodieWriteConfig.EMBEDDED_TIMELINE_SERVER_ENABLE.key(), " false")
    }

    retry(retryCnt, retryIntervalMs)(
      Try(
        HoodieSparkSqlWriter.write(
          sqlContext, mode, updatedOptions, data, Some(StreamingWriteParams(hoodieTableConfig,
            if (disableCompaction) None else Some(triggerAsyncCompactor), Some(triggerAsyncClustering),
            extraPreCommitFn = Some(new BiConsumer[HoodieTableMetaClient, HoodieCommitMetadata] {
              override def accept(metaClient: HoodieTableMetaClient, newCommitMetadata: HoodieCommitMetadata): Unit = {
                val identifier = options.getOrElse(STREAMING_CHECKPOINT_IDENTIFIER.key(), STREAMING_CHECKPOINT_IDENTIFIER.defaultValue())
                newCommitMetadata.addMetadata(SINK_CHECKPOINT_KEY, CommitUtils.getCheckpointValueAsString(identifier, String.valueOf(batchId)))
              }
            }))), writeClient)
      )
      match {
        case Success((true, commitOps, compactionInstantOps, clusteringInstant, client, tableConfig)) =>
          log.info(s"Micro batch id=$batchId succeeded"
            + (commitOps.isPresent match {
            case true => s" for commit=${commitOps.get()}"
            case _ => s" with no new commits"
          }))
          log.info(s"Current value of latestCommittedBatchId: $latestCommittedBatchId. Setting latestCommittedBatchId to batchId $batchId.")
          latestCommittedBatchId = batchId
          writeClient = Some(client)
          hoodieTableConfig = Some(tableConfig)
          if (client != null) {
            metaClient = Some(HoodieTableMetaClient.builder()
              .setConf(HadoopFSUtils.getStorageConfWithCopy(sqlContext.sparkContext.hadoopConfiguration))
              .setBasePath(client.getConfig.getBasePath)
              .build())
          }
          if (compactionInstantOps.isPresent) {
            asyncCompactorService.enqueuePendingAsyncServiceInstant(compactionInstantOps.get())
          }
          if (clusteringInstant.isPresent) {
            asyncClusteringService.enqueuePendingAsyncServiceInstant(clusteringInstant.get())
          }
          Success((true, commitOps, compactionInstantOps))
        case Failure(e) =>
          // clean up persist rdds in the write process
          data.sparkSession.sparkContext.getPersistentRDDs
            .foreach {
              case (_, rdd) =>
                try {
                  rdd.unpersist()
                } catch {
                  case t: Exception => log.warn("Got excepting trying to unpersist rdd", t)
                }
            }
          log.error(s"Micro batch id=$batchId threw following exception: ", e)
          if (ignoreFailedBatch) {
            log.warn(s"Ignore the exception and move on streaming as per " +
              s"${STREAMING_IGNORE_FAILED_BATCH.key} configuration")
            Success((true, None, None))
          } else {
            if (retryCnt > 1) log.info(s"Retrying the failed micro batch id=$batchId ...")
            Failure(e)
          }
        case Success((false, commitOps, _, _, _, _)) =>
          log.error(s"Micro batch id=$batchId ended up with errors"
            + (commitOps.isPresent match {
            case true => s" for commit=${commitOps.get()}"
            case _ => s""
          }))
          if (ignoreFailedBatch) {
            log.info(s"Ignore the errors and move on streaming as per " +
              s"${STREAMING_IGNORE_FAILED_BATCH.key} configuration")
            Success((true, None, None))
          } else {
            if (retryCnt > 1) log.warn(s"Retrying the failed micro batch id=$batchId ...")
            Failure(new HoodieCorruptedDataException(s"Micro batch id=$batchId ended up with errors"))
          }
      }
    )
    match {
      case Failure(e) =>
        if (!ignoreFailedBatch) {
          log.error(s"Micro batch id=$batchId threw following expections," +
            s"aborting streaming app to avoid data loss: ", e)
          // spark sometimes hangs upon exceptions and keep on hold of the executors
          // this is to force exit upon errors / exceptions and release all executors
          // will require redeployment / supervise mode to restart the streaming
          reset(true)
          System.exit(1)
        }
      case Success(_) =>
        log.info(s"Micro batch id=$batchId succeeded")
    }
  }

  override def toString: String = s"HoodieStreamingSink[${options("path")}]"

  @annotation.tailrec
  private def retry[T](n: Int, waitInMillis: Long)(fn: => Try[T]): Try[T] = {
    fn match {
      case x: Success[T] =>
        x
      case _ if n > 1 =>
        Thread.sleep(waitInMillis)
        retry(n - 1, waitInMillis * 2)(fn)
      case f =>
        reset(false)
        f
    }
  }

  protected def triggerAsyncCompactor(client: SparkRDDWriteClient[_]): Unit = {
    if (null == asyncCompactorService) {
      log.info("Triggering Async compaction !!")
      asyncCompactorService = new SparkStreamingAsyncCompactService(new HoodieSparkEngineContext(new JavaSparkContext(sqlContext.sparkContext)),
        client)
      asyncCompactorService.start(new Function[java.lang.Boolean, java.lang.Boolean] {
        override def apply(errored: lang.Boolean): lang.Boolean = {
          log.info(s"Async Compactor shutdown. Errored ? $errored")
          isAsyncCompactorServiceShutdownAbnormally = errored
          reset(false)
          log.info("Done resetting write client.")
          true
        }
      })

      // Add Shutdown Hook
      Runtime.getRuntime.addShutdownHook(new Thread(new Runnable {
        override def run(): Unit = reset(true)
      }))

      // First time, scan .hoodie folder and get all pending compactions
      val metaClient = HoodieTableMetaClient.builder()
        .setConf(HadoopFSUtils.getStorageConfWithCopy(sqlContext.sparkContext.hadoopConfiguration))
        .setBasePath(client.getConfig.getBasePath).build()
      val pendingInstants: java.util.List[HoodieInstant] =
        CompactionUtils.getPendingCompactionInstantTimes(metaClient)
      pendingInstants.asScala.foreach((h: HoodieInstant) => asyncCompactorService.enqueuePendingAsyncServiceInstant(h.requestedTime()))
    }
  }

  protected def triggerAsyncClustering(client: SparkRDDWriteClient[_]): Unit = {
    if (null == asyncClusteringService) {
      log.info("Triggering async clustering!")
      asyncClusteringService = new SparkStreamingAsyncClusteringService(new HoodieSparkEngineContext(new JavaSparkContext(sqlContext.sparkContext)),
        client)
      asyncClusteringService.start(new Function[java.lang.Boolean, java.lang.Boolean] {
        override def apply(errored: lang.Boolean): lang.Boolean = {
          log.info(s"Async clustering service shutdown. Errored ? $errored")
          isAsyncClusteringServiceShutdownAbnormally = errored
          reset(false)
          true
        }
      })

      // Add Shutdown Hook
      Runtime.getRuntime.addShutdownHook(new Thread(new Runnable {
        override def run(): Unit = reset(true)
      }))

      // First time, scan .hoodie folder and get all pending clustering instants
      val metaClient = HoodieTableMetaClient.builder()
        .setConf(HadoopFSUtils.getStorageConfWithCopy(sqlContext.sparkContext.hadoopConfiguration))
        .setBasePath(client.getConfig.getBasePath).build()
      val pendingInstants: java.util.List[HoodieInstant] = ClusteringUtils.getPendingClusteringInstantTimes(metaClient)
      pendingInstants.asScala.foreach((h: HoodieInstant) => asyncClusteringService.enqueuePendingAsyncServiceInstant(h.requestedTime()))
    }
  }

  private def reset(force: Boolean): Unit = this.synchronized {
    if (asyncCompactorService != null) {
      asyncCompactorService.shutdown(force)
      asyncCompactorService = null
    }

    if (asyncClusteringService != null) {
      asyncClusteringService.shutdown(force)
      asyncClusteringService = null
    }

    if (writeClient.isDefined) {
      writeClient.get.close()
      writeClient = Option.empty
    }
  }

  private def canSkipBatch(incomingBatchId: Long, operationType: String): Boolean = {
    if (!DELETE_OPERATION_OPT_VAL.equals(operationType)) {
      val identifier = options.getOrElse(STREAMING_CHECKPOINT_IDENTIFIER.key(), STREAMING_CHECKPOINT_IDENTIFIER.defaultValue())
      // get the latest checkpoint from the commit metadata to check if the microbatch has already been processed or not
      val lastCheckpoint = CommitUtils.getValidCheckpointForCurrentWriter(
        metaClient.get.getActiveTimeline.getWriteTimeline, SINK_CHECKPOINT_KEY, identifier)
      if (lastCheckpoint.isPresent) {
        latestCommittedBatchId = lastCheckpoint.get().toLong
      }
      latestCommittedBatchId >= incomingBatchId
    } else {
      // In case of DELETE_OPERATION_OPT_VAL the incoming batch id is sentinel value (-1)
      false
    }
  }
}

object HoodieStreamingSink {

  // This constant serves as the checkpoint key for streaming sink so that each microbatch is processed exactly-once.
  val SINK_CHECKPOINT_KEY = "_hudi_streaming_sink_checkpoint"
}
