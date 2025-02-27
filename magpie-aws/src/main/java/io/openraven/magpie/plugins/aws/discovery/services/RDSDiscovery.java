/*
 * Copyright 2021 Open Raven Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package io.openraven.magpie.plugins.aws.discovery.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.openraven.magpie.api.Emitter;
import io.openraven.magpie.api.MagpieAwsResource;
import io.openraven.magpie.api.Session;
import io.openraven.magpie.data.aws.rds.RDSInstance;
import io.openraven.magpie.data.aws.rds.RDSSnapshot;
import io.openraven.magpie.data.aws.rds.RDSProxy;
import io.openraven.magpie.plugins.aws.discovery.AWSUtils;
import io.openraven.magpie.plugins.aws.discovery.Conversions;
import io.openraven.magpie.plugins.aws.discovery.DiscoveryExceptions;
import io.openraven.magpie.plugins.aws.discovery.MagpieAWSClientCreator;
import io.openraven.magpie.plugins.aws.discovery.VersionedMagpieEnvelopeProvider;
import org.javatuples.Pair;
import org.slf4j.Logger;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkServiceException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.model.Datapoint;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricStatisticsResponse;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DBInstance;
import software.amazon.awssdk.services.rds.model.*;
import software.amazon.awssdk.services.rds.model.DescribeDbSnapshotsRequest;
import software.amazon.awssdk.services.rds.model.ListTagsForResourceRequest;
import software.amazon.awssdk.services.rds.model.Tag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static io.openraven.magpie.plugins.aws.discovery.AWSUtils.getAwsResponse;

public class RDSDiscovery implements AWSDiscovery {

  private static final String SERVICE = "rds";

  @Override
  public String service() {
    return SERVICE;
  }

  @Override
  public List<Region> getSupportedRegions() {
    return RdsClient.serviceMetadata().regions();
  }

  @Override
  public void discover(ObjectMapper mapper, Session session, Region region, Emitter emitter, Logger logger, String account, MagpieAWSClientCreator clientCreator) {

    try (final var client = clientCreator.apply(RdsClient.builder()).build()) {
      discoverDbProxy(mapper, session, region, emitter, account, client);
      discoverDbSnapshot(mapper, session, region, emitter, account, client);
      discoverDbInstances(mapper, session, region, emitter, logger, account, client, clientCreator);
      discoverDbAuoraClusters(mapper, session, region, emitter, logger, account, client, clientCreator);
    }
  }

  private void discoverDbProxy(ObjectMapper mapper, Session session, Region region, Emitter emitter, String account, RdsClient client) {
    final String RESOURCE_TYPE = RDSProxy.RESOURCE_TYPE;

    try {
      client.describeDBProxiesPaginator(DescribeDbProxiesRequest.builder().build()).dbProxies().stream()
        .forEach(dbProxy -> {
          var data = new MagpieAwsResource.MagpieAwsResourceBuilder(mapper, dbProxy.dbProxyArn())
            .withResourceName(dbProxy.dbProxyName())
            .withResourceId(dbProxy.dbProxyArn())
            .withResourceType(RESOURCE_TYPE)
            .withConfiguration(mapper.valueToTree(dbProxy.toBuilder()))
            .withCreatedIso(dbProxy.createdDate())
            .withAccountId(account)
            .withAwsRegion(region.toString())
            .build();


          emitter.emit(VersionedMagpieEnvelopeProvider.create(session, List.of(fullService() + ":dbProxy"), data.toJsonNode()));
        });
    } catch (SdkServiceException | SdkClientException ex) {
      DiscoveryExceptions.onDiscoveryException(RESOURCE_TYPE, null, region, ex);
    }
  }

  private void discoverDbSnapshot(ObjectMapper mapper, Session session, Region region, Emitter emitter, String account, RdsClient client) {
    final String RESOURCE_TYPE = RDSSnapshot.RESOURCE_TYPE;

    try {
      client.describeDBSnapshots(DescribeDbSnapshotsRequest.builder().includeShared(true).includePublic(false).build()).dbSnapshots()
        .forEach(snapshot -> {
          var data = new MagpieAwsResource.MagpieAwsResourceBuilder(mapper, snapshot.dbSnapshotArn())
            .withResourceName(snapshot.dbSnapshotIdentifier())
            .withResourceId(snapshot.dbSnapshotArn())
            .withResourceType(RESOURCE_TYPE)
            .withConfiguration(mapper.valueToTree(snapshot.toBuilder()))
            .withCreatedIso(snapshot.instanceCreateTime())
            .withAccountId(account)
            .withAwsRegion(region.toString())
            .build();

          emitter.emit(VersionedMagpieEnvelopeProvider.create(session, List.of(fullService() + ":dbSnapshot"), data.toJsonNode()));
        });
    } catch (SdkServiceException | SdkClientException ex) {
      DiscoveryExceptions.onDiscoveryException(RESOURCE_TYPE, null, region, ex);
    }
  }

  private void discoverDbInstances(ObjectMapper mapper, Session session, Region region, Emitter emitter, Logger logger, String account, RdsClient client, MagpieAWSClientCreator clientCreator) {
    final String RESOURCE_TYPE = RDSInstance.RESOURCE_TYPE;
    try {
      client.describeDBInstancesPaginator().dbInstances().stream()
        .forEach(db -> {
          if (db.dbClusterIdentifier() == null) {
            var data = new MagpieAwsResource.MagpieAwsResourceBuilder(mapper, db.dbInstanceArn())
              .withResourceName(db.dbInstanceIdentifier())
              .withResourceId(db.dbInstanceArn())
              .withResourceType(RESOURCE_TYPE)
              .withConfiguration(mapper.valueToTree(db.toBuilder()))
              .withCreatedIso(db.instanceCreateTime())
              .withAccountId(account)
              .withAwsRegion(region.toString())
              .build();

            if (db.instanceCreateTime() == null) {
              logger.warn("DBInstance has NULL CreateTime: dbInstanceArn=\"{}\"", db.dbInstanceArn());
            }

            discoverTags(client, db, data, mapper);
            discoverInstanceDbSnapshots(client, db, data);
            discoverInstanceSize(db, data, logger, clientCreator);
            discoverInstanceDbProxies(client, db, data);

            discoverCloudWatchInstanceUsageMetrics(client, db, data, logger, clientCreator);

            discoverBackupJobs(db.dbInstanceArn(), region, data, clientCreator, logger);

            emitter.emit(VersionedMagpieEnvelopeProvider.create(session, List.of(fullService() + ":dbInstance"), data.toJsonNode()));
          }
        });
    } catch (SdkServiceException | SdkClientException ex) {
      DiscoveryExceptions.onDiscoveryException(RESOURCE_TYPE, null, region, ex);
    }
  }

  private void discoverDbAuoraClusters(ObjectMapper mapper, Session session, Region region, Emitter emitter, Logger logger, String account, RdsClient client, MagpieAWSClientCreator clientCreator) {
    final String RESOURCE_TYPE = RDSInstance.RESOURCE_TYPE;
    try {
      client.describeDBClustersPaginator().dbClusters().stream()
        .forEach(cluster -> {
          var data = new MagpieAwsResource.MagpieAwsResourceBuilder(mapper, cluster.dbClusterArn())
            .withResourceName(cluster.dbClusterIdentifier())
            .withResourceId(cluster.dbClusterArn())
            .withResourceType(RESOURCE_TYPE)
            .withConfiguration(mapper.valueToTree(cluster.toBuilder()))
            .withCreatedIso(cluster.clusterCreateTime())
            .withAccountId(account)
            .withAwsRegion(region.toString())
            .build();

          if (cluster.clusterCreateTime() == null) {
            logger.warn("DBCluster has NULL CreateTime: dbClusterArn=\"{}\"", cluster.dbClusterArn());
          }

          discoverTags(client, cluster, data, mapper);
          discoverDbClusterInstances(client, cluster, data);
          discoverDbClusterSnapshots(client, cluster, data);
          discoverClusterSize(cluster, data, logger, clientCreator);

          discoverBackupJobs(cluster.dbClusterArn(), region, data, clientCreator, logger);

          discoverCloudWatchClusterUsageMetrics(client, cluster, data, logger, clientCreator);

          emitter.emit(VersionedMagpieEnvelopeProvider.create(session, List.of(fullService() + ":dbInstance"), data.toJsonNode()));
        });
    } catch (SdkServiceException | SdkClientException ex) {
      DiscoveryExceptions.onDiscoveryException(RESOURCE_TYPE, null, region, ex);
    }
  }

  private void discoverTags(RdsClient client, DBInstance resource, MagpieAwsResource data, ObjectMapper mapper) {
    getAwsResponse(
      () -> client.listTagsForResource(ListTagsForResourceRequest.builder().resourceName(resource.dbInstanceArn()).build()),
      (resp) -> {
        JsonNode tagsNode = mapper.convertValue(resp.tagList().stream()
          .collect(Collectors.toMap(Tag::key, Tag::value)), JsonNode.class);
        AWSUtils.update(data.tags, tagsNode);
      },
      (noresp) -> AWSUtils.update(data.tags, noresp)
    );
  }

  private void discoverTags(RdsClient client, DBCluster resource, MagpieAwsResource data, ObjectMapper mapper) {
    getAwsResponse(
      () -> client.listTagsForResource(ListTagsForResourceRequest.builder().resourceName(resource.dbClusterArn()).build()),
      (resp) -> {
        JsonNode tagsNode = mapper.convertValue(resp.tagList().stream()
          .collect(Collectors.toMap(Tag::key, Tag::value)), JsonNode.class);
        AWSUtils.update(data.tags, tagsNode);
      },
      (noresp) -> AWSUtils.update(data.tags, noresp)
    );
  }

  private void discoverDbClusterInstances(RdsClient client, DBCluster resource, MagpieAwsResource data) {
    Filter filter = Filter.builder().name("db-cluster-id").values(resource.dbClusterArn()).build();
    getAwsResponse(
      () -> client.describeDBInstances(DescribeDbInstancesRequest.builder()
        .filters(filter)
        .build()),
      (resp) -> AWSUtils.update(data.supplementaryConfiguration, resp),
      (noresp) -> AWSUtils.update(data.supplementaryConfiguration, noresp)
    );
  }

  private void discoverInstanceDbProxies(RdsClient client, DBInstance resource, MagpieAwsResource data){
    getAwsResponse(
      () -> client.describeDBProxies(DescribeDbProxiesRequest.builder().dbProxyName(resource.dbInstanceIdentifier()).build()),
      (resp) -> AWSUtils.update(data.supplementaryConfiguration, resp),
      (noresp) -> AWSUtils.update(data.supplementaryConfiguration, noresp)
    );
  }

  private void discoverInstanceDbSnapshots(RdsClient client, DBInstance resource, MagpieAwsResource data) {
    final String keyname = "dbSnapshot";
    getAwsResponse(
      () -> client.describeDBSnapshots(DescribeDbSnapshotsRequest.builder()
        .dbInstanceIdentifier(resource.dbInstanceIdentifier())
        .includePublic(false)
        .includeShared(true)
        .build()),
      (resp) -> AWSUtils.update(data.supplementaryConfiguration, Map.of(keyname, resp)),
      (noresp) -> AWSUtils.update(data.supplementaryConfiguration, Map.of(keyname, noresp))
    );
  }

  private void discoverDbClusterSnapshots(RdsClient client, DBCluster resource, MagpieAwsResource data) {
    final String keyname = "dbSnapshot";
    getAwsResponse(
      () -> client.describeDBClusterSnapshots(DescribeDbClusterSnapshotsRequest.builder()
        .dbClusterIdentifier(resource.dbClusterIdentifier())
        .includePublic(false)
        .includeShared(true)
        .build()),
      (resp) -> AWSUtils.update(data.supplementaryConfiguration, Map.of(keyname, resp)),
      (noresp) -> AWSUtils.update(data.supplementaryConfiguration, Map.of(keyname, noresp))
    );
  }

  private void discoverInstanceSize(DBInstance resource, MagpieAwsResource data, Logger logger, MagpieAWSClientCreator clientCreator) {
    // get the DB engine and call the relevant function (as although RDS uses same client, the metrics available are different)
    String engine = resource.engine();
    if (engine != null) {
      if ("docdb".equalsIgnoreCase(engine)) {
        // although DocDB uses RDS client, it's metrics are subtly different, so get metrics via setDocDBSize
        setDocDBSize(resource, data, logger, clientCreator);
      } else {
        setRDSSize(resource, data, logger, clientCreator);
      }
    } else {
      logger.warn("{} RDS instance is missing engine property", resource.dbInstanceIdentifier());
    }
  }

  private void discoverClusterSize(DBCluster resource, MagpieAwsResource data, Logger logger, MagpieAWSClientCreator clientCreator) {
    setAuroraDBSize(resource, data, logger, clientCreator);
  }

  private void setRDSSize(DBInstance resource, MagpieAwsResource data, Logger logger, MagpieAWSClientCreator clientCreator) {
    try {
      List<Dimension> dimensions = new ArrayList<>();
      dimensions.add(Dimension.builder().name("DBInstanceIdentifier").value(resource.dbInstanceIdentifier()).build());
      Pair<Long, GetMetricStatisticsResponse> freeStorageSpace =
        AWSUtils.getCloudwatchMetricMinimum(data.awsRegion, "AWS/RDS", "FreeStorageSpace", dimensions, clientCreator);

      if (freeStorageSpace.getValue0() != null) {
        AWSUtils.update(data.supplementaryConfiguration, Map.of("size", Map.of("FreeStorageSpace", freeStorageSpace.getValue0())));

        // pull the relevant node(s) from the payload object. See https://boto3.amazonaws.com/v1/documentation/api/latest/reference/services/rds.html
        long freeStorageCapacity = freeStorageSpace.getValue0();
        long storageCapacity = resource.allocatedStorage();

        data.sizeInBytes = Conversions.GibToBytes(storageCapacity) - freeStorageCapacity;
        data.maxSizeInBytes = Conversions.GibToBytes(storageCapacity);
      } else {
        logger.warn("{} RDS instance is missing size metrics", resource.dbInstanceIdentifier());
      }
    } catch (Exception se) {
      logger.warn("{} RDS instance is missing size metrics, with error {}", resource.dbInstanceIdentifier(), se.getMessage());
    }
  }

  private void setDocDBSize(DBInstance resource, MagpieAwsResource data, Logger logger, MagpieAWSClientCreator clientCreator) {
    try {
      List<Dimension> dimensions = new ArrayList<>();
      dimensions.add(Dimension.builder().name("DBClusterIdentifier").value(resource.dbInstanceIdentifier()).build());
      Pair<Long, GetMetricStatisticsResponse> volumeBytesUsed =
        AWSUtils.getCloudwatchMetricMaximum(data.awsRegion, "AWS/DocDB", "VolumeBytesUsed", dimensions, clientCreator);

      if (volumeBytesUsed.getValue0() != null) {
        AWSUtils.update(data.supplementaryConfiguration, Map.of("size", Map.of("VolumeBytesUsed", volumeBytesUsed.getValue0())));

        data.sizeInBytes = volumeBytesUsed.getValue0();
        data.maxSizeInBytes = Conversions.GibToBytes(resource.allocatedStorage());
      }
    } catch (Exception se) {
      logger.warn("{} RDS instance is missing size metrics, with error {}", resource.dbInstanceArn(), se.getMessage());
    }
  }

  private void setAuroraDBSize(DBCluster resource, MagpieAwsResource data, Logger logger, MagpieAWSClientCreator clientCreator) {
    try {
      List<Dimension> dimensions = new ArrayList<>();
      dimensions.add(Dimension.builder().name("DBClusterIdentifier").value(resource.dbClusterIdentifier()).build());
      Pair<Long, GetMetricStatisticsResponse> volumeBytesUsed =
        AWSUtils.getCloudwatchMetricMaximum(data.awsRegion, "AWS/RDS", "VolumeBytesUsed", dimensions, clientCreator);

      if (volumeBytesUsed.getValue0() != null) {
        AWSUtils.update(data.supplementaryConfiguration, Map.of("size", Map.of("VolumeBytesUsed", volumeBytesUsed.getValue0())));

        data.sizeInBytes = volumeBytesUsed.getValue0();
        data.maxSizeInBytes = Conversions.GibToBytes(resource.allocatedStorage());

      }
    } catch (Exception se) {
      logger.warn("{} RDS cluster is missing size metrics, with error {}", resource.dbClusterArn(), se.getMessage());
    }
  }

  private Map<String, Double> formatDataMapAvg(List<Datapoint> map) {
    Map<String, Double> datapointMetrics = new HashMap<>();
    for (Datapoint dp : map) {
      datapointMetrics.put(dp.timestamp().toString(), dp.average());
    }
    return datapointMetrics;
  }

  private Map<String, Double> formatDataMapSum(List<Datapoint> map) {
    Map<String, Double> datapointMetrics = new HashMap<>();
    for (Datapoint dp : map) {
      datapointMetrics.put(dp.timestamp().toString(), dp.sum());
    }
    return datapointMetrics;
  }

  private Map<String, Object> getRDSCloudWatchMetrics(String identifier, String engine, MagpieAwsResource data, Logger logger, MagpieAWSClientCreator clientCreator) {
    String readMetric;
    String writeMetric;
    Map<String, Object> requestMetrics = new HashMap<>();
    List<Dimension> dimensions = new ArrayList<>();

    if ("aurora-mysql".equalsIgnoreCase(engine)) {
      dimensions.add(Dimension.builder().name("DBClusterIdentifier").value(identifier).build());
      readMetric = "VolumeReadIOPs";
      writeMetric = "VolumeWriteIOPs";
    } else {
      dimensions.add(Dimension.builder().name("DBInstanceIdentifier").value(identifier).build());
      readMetric = "ReadIOPS";
      writeMetric = "WriteIOPS";
    }

    List<Datapoint> connections =
      AWSUtils.getCloudwatchMetricStaleDataSum(data.awsRegion, "AWS/RDS", "DatabaseConnections", dimensions, clientCreator);
    requestMetrics.put("DatabaseConnections", formatDataMapSum(connections));

    List<Datapoint> writeIOPS =
      AWSUtils.getCloudwatchMetricStaleDataAvg(data.awsRegion, "AWS/RDS", writeMetric, dimensions, clientCreator);
    requestMetrics.put(writeMetric, formatDataMapAvg(writeIOPS));

    List<Datapoint> readIOPS =
      AWSUtils.getCloudwatchMetricStaleDataAvg(data.awsRegion, "AWS/RDS", readMetric, dimensions, clientCreator);
    requestMetrics.put(readMetric, formatDataMapAvg(readIOPS));

    return requestMetrics;

  }

  private void discoverCloudWatchClusterUsageMetrics(RdsClient client, DBCluster resource, MagpieAwsResource data, Logger logger, MagpieAWSClientCreator clientCreator) {
    try {
      Map<String, Object> allMetrics = new HashMap<>();
      if ("aurora-mysql".equalsIgnoreCase(resource.engine())) {
        Map<String, Object> clusterMetrics = getRDSCloudWatchMetrics(resource.dbClusterIdentifier(), resource.engine(), data, logger, clientCreator);
        allMetrics.put(resource.dbClusterIdentifier() + ":cluster", clusterMetrics);
        AWSUtils.update(data.supplementaryConfiguration, Map.of("staleDataMetrics", allMetrics));
      } else {
        Filter filter = Filter.builder().name("db-cluster-id").values(resource.dbClusterArn()).build();
        DescribeDbInstancesResponse dbInstances = client.describeDBInstances(DescribeDbInstancesRequest.builder().filters(filter).build());
        for (DBInstance db : dbInstances.dbInstances()) {
          Map<String, Object> instanceMetrics = getRDSCloudWatchMetrics(db.dbInstanceIdentifier(), db.engine(), data, logger, clientCreator);
          allMetrics.put(db.dbInstanceIdentifier() + ":instance", instanceMetrics);
        }
        AWSUtils.update(data.supplementaryConfiguration, Map.of("staleDataMetrics", allMetrics));
      }
    } catch (Exception se) {
      logger.warn("{} RDS cluster is missing stale data metrics, with error {}", resource.dbClusterArn(), se.getMessage());
    }
  }

  private void discoverCloudWatchInstanceUsageMetrics(RdsClient client, DBInstance db, MagpieAwsResource data, Logger logger, MagpieAWSClientCreator clientCreator) {
    try {
      Map<String, Object> allMetrics = new HashMap<>();

      Map<String, Object> instanceMetrics = getRDSCloudWatchMetrics(db.dbInstanceIdentifier(), db.engine(), data, logger, clientCreator);
      allMetrics.put(db.dbInstanceIdentifier() + ":instance", instanceMetrics);

      AWSUtils.update(data.supplementaryConfiguration, Map.of("staleDataMetrics", allMetrics));

    } catch (Exception se) {
      logger.warn("{} RDS cluster is missing metrics, with error {}", db.dbInstanceArn(), se.getMessage());
    }
  }

}
