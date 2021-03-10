/*-
 * -\-\-
 * bigtable-autoscaler
 * --
 * Copyright (C) 2018 Spotify AB
 * --
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * -/-/-
 */

package com.spotify.autoscaler.api;

import static com.google.api.client.util.Preconditions.checkNotNull;

import com.google.gson.Gson;
import com.spotify.autoscaler.api.type.BigtableAutoscaler;
import com.spotify.autoscaler.api.type.ReconcileRequest;
import com.spotify.autoscaler.api.type.ReconcileResponse;
import com.spotify.autoscaler.db.BigtableCluster;
import com.spotify.autoscaler.db.BigtableClusterBuilder;
import com.spotify.autoscaler.db.Database;
import io.kubernetes.client.openapi.JSON;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/reconcile")
public class DeclarativeAutoscalerController implements Endpoint {

  // Use the GSON provided by the k8s java client to deal with k8s objects, since
  // it implements several typeadapter to decode k8s object correctly.
  private static final Gson gson = new JSON().getGson();

  private static final Logger LOGGER =
      LoggerFactory.getLogger(DeclarativeAutoscalerController.class);

  private final Database database;

  @Inject
  public DeclarativeAutoscalerController(final Database database) {
    this.database = checkNotNull(database);
  }

  @POST
  public String reconcile(String body) {
    // parse request
    final ReconcileRequest request = gson.fromJson(body, ReconcileRequest.class);
    final BigtableAutoscaler config = request.getForObject();
    final ReconcileResponse.EnsureResources.ForObject forObj =
        new ReconcileResponse.EnsureResources.ForObject(
            config.getApiVersion(),
            config.getKind(),
            config.getMetadata().getNamespace(),
            config.getMetadata().getName());

    final String projectId = config.getMetadata().getNamespace();
    final String instanceId = config.getSpec().getInstanceId();

    final Set<BigtableCluster> clustersDesiredState =
        getClustersDesiredState(
            projectId, instanceId, config.getSpec().getCluster(), request.getDeletionInProgress());

    // reconcile
    clustersDesiredState.forEach(database::reconcileBigtableCluster);
    deleteAbsentClusters(projectId, instanceId, clustersDesiredState);

    return reconcileResponse(projectId, instanceId, clustersDesiredState, forObj);
  }

  private Set<BigtableCluster> getClustersDesiredState(
      final String projectId,
      final String instanceId,
      final BigtableAutoscaler.Spec.Cluster[] arraySpecClusters,
      final Boolean isDeletionInProgress) {
    final Set<BigtableAutoscaler.Spec.Cluster> setSpecClusters =
        isDeletionInProgress
            ? Collections.emptySet()
            : new HashSet<>(Arrays.asList(arraySpecClusters));
    return setSpecClusters
        .stream()
        .map(cluster -> getBigtableCluster(projectId, instanceId, cluster))
        .collect(Collectors.toSet());
  }

  private BigtableCluster getBigtableCluster(
      final String projectId,
      final String instanceId,
      final BigtableAutoscaler.Spec.Cluster cluster) {
    return new BigtableClusterBuilder()
        .projectId(projectId)
        .instanceId(instanceId)
        .clusterId(cluster.getClusterId())
        .minNodes(cluster.getMinNodes())
        .maxNodes(cluster.getMaxNodes())
        .cpuTarget(cluster.getCpuTarget())
        .enabled(true)
        .build();
  }

  private void deleteAbsentClusters(
      final String projectId,
      final String instanceId,
      final Set<BigtableCluster> clustersDesiredState) {
    final int deletedClustersCount =
        database.deleteBigtableClustersExcept(
            projectId, instanceId, getClusterIds(clustersDesiredState));
    if (deletedClustersCount > 0) {
      LOGGER.info(
          "Project {}, instance {}: {} clusters deleted",
          projectId,
          instanceId,
          deletedClustersCount);
    }
  }

  private Set<String> getClusterIds(final Set<BigtableCluster> clustersDesiredState) {
    return clustersDesiredState
        .stream()
        .map(BigtableCluster::clusterId)
        .collect(Collectors.toSet());
  }

  private String reconcileResponse(
      final String projectId,
      final String instanceId,
      final Set<BigtableCluster> clustersDesiredState,
      final ReconcileResponse.EnsureResources.ForObject forObj) {
    final List<BigtableCluster> clustersFromDB =
        database.getBigtableClusters(projectId, instanceId);
    if (clustersDesiredState.size() != clustersFromDB.size()) {
      throw new IllegalStateException(
          String.format(
              "Desired state had %d clusters but DB has %d clusters after reconciliation!",
              clustersDesiredState.size(), clustersFromDB.size()));
    }
    forObj.status(new ReconcileResponse.Status(clustersFromDB));
    forObj.deleted(clustersDesiredState.isEmpty());
    return gson.toJson(
        new ReconcileResponse().ensure(new ReconcileResponse.EnsureResources().forObject(forObj)),
        ReconcileResponse.class);
  }
}
