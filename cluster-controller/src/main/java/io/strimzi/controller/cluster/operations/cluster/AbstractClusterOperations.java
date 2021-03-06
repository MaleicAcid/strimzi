/*
 * Copyright 2017-2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.controller.cluster.operations.cluster;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.strimzi.controller.cluster.ClusterController;
import io.strimzi.controller.cluster.operations.resource.ConfigMapOperations;
import io.strimzi.controller.cluster.resources.AbstractCluster;
import io.strimzi.controller.cluster.resources.ClusterDiffResult;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.shareddata.Lock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>Abstract cluster creation, update, read, delection, etc, for a generic cluster type {@code C}.
 * This class applies the "template method" pattern, first obtaining the desired cluster configuration
 * ({@link CompositeOperation#getCluster(String, String)}),
 * then creating resources to match ({@link CompositeOperation#composite(String, ClusterOperation)}.</p>
 *
 * <p>This class manages a per-cluster-type and per-cluster locking strategy so only one operation per cluster
 * can proceed at once.</p>
 * @param <C> The type of Kubernetes client
 * @param <R> The type of resource from which the cluster state can be recovered
 */
public abstract class AbstractClusterOperations<C extends AbstractCluster,
        R extends HasMetadata> {

    private static final Logger log = LoggerFactory.getLogger(AbstractClusterOperations.class.getName());

    protected static final String OP_CREATE = "create";
    protected static final String OP_DELETE = "delete";
    protected static final String OP_UPDATE = "update";

    protected static final int LOCK_TIMEOUT = 60000;

    protected final Vertx vertx;
    protected final boolean isOpenShift;
    protected final String clusterDescription;
    protected final ConfigMapOperations configMapOperations;

    /**
     * @param vertx The Vertx instance
     * @param isOpenShift True iff running on OpenShift
     * @param clusterDescription A description of the cluster, for logging. This is a high level description and different from
     *                           the {@code clusterType} passed to {@link #getLockName(String, String, String)}
     *                           and {@link #execute(String, String, String, String, CompositeOperation, Handler)}
     */
    protected AbstractClusterOperations(Vertx vertx, boolean isOpenShift, String clusterDescription,
                                        ConfigMapOperations configMapOperations) {
        this.vertx = vertx;
        this.isOpenShift = isOpenShift;
        this.clusterDescription = clusterDescription;
        this.configMapOperations = configMapOperations;
    }

    /**
     * Gets the name of the lock to be used for operating on the given {@code clusterType}, {@code namespace} and
     * cluster {@code name}
     * @param clusterType
     * @param namespace
     * @param name
     */
    protected final String getLockName(String clusterType, String namespace, String name) {
        return "lock::" + clusterType + "::" + namespace + "::" + name;
    }

    /**
     * Represents the desired state of a cluster, possibly including
     * how it differs from the current state.
     * @param <C> The type of cluster.
     */
    protected static class ClusterOperation<C extends AbstractCluster> {
        private final C cluster;
        private final ClusterDiffResult diff;

        /**
         * @param cluster A cluster representing the target state (i.e.
         *                a cluster obtained from a cluster ConfigMap).
         * @param diff The diff if this is an update, otherwise null.
         */
        public ClusterOperation(C cluster, ClusterDiffResult diff) {
            this.cluster = cluster;
            this.diff = diff;
        }

        public C cluster() {
            return cluster;
        }

        public ClusterDiffResult diff() {
            return diff;
        }

    }

    /**
     * An operation in the resources which make up a cluster to make it conform to
     * a particular {@linkplain ClusterOperation desired state}.
     * @param <C> The type of cluster.
     */
    protected interface CompositeOperation<C extends AbstractCluster> {
        /**
         * Create the resources in Kubernetes according to the given {@code cluster},
         * returning a composite future for when the overall operation is done
         */
        Future<?> composite(String namespace, ClusterOperation<C> operation);

        /**
         * Get the desired Cluster instance (by getting the corresponding ConfigMap and
         * creating the appropriate {@link AbstractCluster} subclass from it.
         */
        ClusterOperation<C> getCluster(String namespace, String name);
    }

    /**
     * <p>Execute the resource operations necessary to make a cluster conform to a particular desired state.</p>
     *
     * <p>The desired cluster state is obtained from {@link CompositeOperation#getCluster(String, String)} and the
     * resource operations are executed via {@link CompositeOperation#composite(String, ClusterOperation)}.</p>
     *
     * @param clusterType The type of cluster
     * @param operationType The kind of operation
     * @param namespace The namespace containing the cluster.
     * @param name The name of the cluster
     * @param compositeOperation The operation to execute
     * @param handler A completion handler
     * @param <C> The type of cluster.
     */
    protected final <C extends AbstractCluster> void execute(String clusterType, String operationType, String namespace, String name, CompositeOperation<C> compositeOperation, Handler<AsyncResult<Void>> handler) {
        final String lockName = getLockName(clusterType, namespace, name);
        vertx.sharedData().getLockWithTimeout(lockName, LOCK_TIMEOUT, res -> {
            if (res.succeeded()) {
                Lock lock = res.result();

                ClusterOperation<C> clusterOp;
                try {
                    clusterOp = compositeOperation.getCluster(namespace, name);
                    log.info("{} {} cluster {} in namespace {}", operationType, clusterType, clusterOp.cluster().getName(), namespace);
                } catch (Throwable ex) {
                    log.error("Error while getting required {} cluster state for {} operation", clusterType, operationType, ex);
                    handler.handle(Future.failedFuture("getCluster error"));
                    lock.release();
                    return;
                }
                Future<?> composite = compositeOperation.composite(namespace, clusterOp);

                composite.setHandler(ar -> {
                    if (ar.succeeded()) {
                        log.info("{} cluster {} in namespace {}: successful {}", clusterType, clusterOp.cluster().getName(), namespace, operationType);
                        handler.handle(Future.succeededFuture());
                        lock.release();
                    } else {
                        log.error("{} cluster {} in namespace {}: failed to {}", clusterType, clusterOp.cluster().getName(), namespace, operationType);
                        handler.handle(Future.failedFuture("Failed to execute cluster operation"));
                        lock.release();
                    }
                });
            } else {
                log.error("Failed to acquire lock to {} {} cluster {}", operationType, clusterType, lockName);
                handler.handle(Future.failedFuture("Failed to acquire lock to " + operationType + " " + clusterType + " cluster"));
            }
        });
    }

    /**
     * Subclasses implement this method to create the cluster. The implementation usually just has to call
     * {@link #execute(String, String, String, String, CompositeOperation, Handler)} with appropriate arguments.
     * @param namespace The namespace containing the cluster.
     * @param name The name of the cluster.
     * @param handler Completion handler
     */
    protected abstract void create(String namespace, String name, Handler<AsyncResult<Void>> handler);

    public void create(String namespace, String name)   {
        log.info("Adding {} cluster {}", clusterDescription, name);

        create(namespace, name, res -> {
            if (res.succeeded()) {
                log.info("{} cluster added {}", clusterDescription, name);
            } else {
                log.error("Failed to add {} cluster {}.", clusterDescription, name);
            }
        });
    }

    /**
     * Subclasses implement this method to delete the cluster. The implementation usually just has to call
     * {@link #execute(String, String, String, String, CompositeOperation, Handler)} with appropriate arguments.
     * @param namespace The namespace containing the cluster.
     * @param name The name of the cluster.
     * @param handler Completion handler
     */
    protected abstract void delete(String namespace, String name, Handler<AsyncResult<Void>> handler);

    public void delete(String namespace, String name)   {
        log.info("Deleting {} cluster {} in namespace {}", clusterDescription, name, namespace);
        delete(namespace, name, res -> {
            if (res.succeeded()) {
                log.info("{} cluster deleted {} in namespace {}", clusterDescription, name, namespace);
            } else {
                log.error("Failed to delete {} cluster {} in namespace {}", clusterDescription, name, namespace);
            }
        });
    }

    /**
     * Subclasses implement this method to update the cluster. The implementation usually just has to call
     * {@link #execute(String, String, String, String, CompositeOperation, Handler)} with appropriate arguments.
     * @param namespace The namespace containing the cluster.
     * @param name The name of the cluster.
     * @param handler Completion handler
     */
    protected abstract void update(String namespace, String name, Handler<AsyncResult<Void>> handler);

    public void update(String namespace, String name)   {

        log.info("Checking for updates in {} cluster {}", clusterDescription, name);
        update(namespace, name, res2 -> {
            if (res2.succeeded()) {
                log.info("{} cluster updated {}", clusterDescription, name);
            } else {
                log.error("Failed to update {} cluster {}.", clusterDescription, name);
            }
        });
    }

    /**
     * The name of the given {@code resource}, as read from its metadata.
     * @param resource The resource
     */
    protected String name(HasMetadata resource) {
        return resource.getMetadata().getName();
    }

    /**
     * The name of the given {@code resource}, as read from its {@code strimzi.io/cluster} label.
     * @param resource The resource
     */
    protected String nameFromLabels(R resource) {
        return resource.getMetadata().getLabels().get(ClusterController.STRIMZI_CLUSTER_LABEL);
    }

    /**
     * Reconcile cluster resources in the given namespace having the given labels.
     * Reconciliation works by getting the cluster ConfigMaps in the given namespace with the given labels and
     * comparing with the corresponding {@linkplain #getResources(String, Map) resource}.
     * <ul>
     * <li>A cluster will be {@linkplain #create(String, String) created} for all ConfigMaps without same-named resources</li>
     * <li>A cluster will be {@linkplain #delete(String, String) deleted} for all resources without same-named ConfigMaps</li>
     * <li>A cluster will be {@linkplain #update(String, String) updated} if it has a cluster ConfigMap and a resource with the same name.</li>
     * </ul>
     * @param namespace The namespace
     * @param labels The labels
     */
    public abstract void reconcile(String namespace, Map<String, String> labels);

    /**
     * This is provided for subclasses to help them implement the public {@link #reconcile(String, Map)}.
     * It obtains a list of ConfigMaps (the desired state of the cluster)
     * and a list of other resources (of type {@link R}) (the actual state of the cluster) and determines,
     * by comparing their names which clusters need to be created, updated or deleted.
     * @param namespace The namespace
     * @param labels The labels to use to select the ConfigMap and other resources to be compared.
     * @param type The {@code strimzi.io/type} of the ConfigMaps and other resources
     */
    protected void reconcile(String namespace, Map<String, String> labels, String type) {
        log.info("Reconciling {} clusters ...", clusterDescription);

        Map<String, String> kafkaLabels = new HashMap<>(labels);
        kafkaLabels.put(ClusterController.STRIMZI_TYPE_LABEL, type);

        List<ConfigMap> cms = configMapOperations.list(namespace, kafkaLabels);
        List<R> resources = getResources(namespace, kafkaLabels);

        Set<String> cmsNames = cms.stream().map(cm -> cm.getMetadata().getName()).collect(Collectors.toSet());
        Set<String> resourceNames = resources.stream().map(cm -> cm.getMetadata().getLabels().get(ClusterController.STRIMZI_CLUSTER_LABEL)).collect(Collectors.toSet());

        List<ConfigMap> addList = cms.stream().filter(cm -> !resourceNames.contains(cm.getMetadata().getName())).collect(Collectors.toList());
        List<ConfigMap> updateList = cms.stream().filter(cm -> resourceNames.contains(cm.getMetadata().getName())).collect(Collectors.toList());
        List<R> deletionList = resources.stream().filter(dep -> !cmsNames.contains(dep.getMetadata().getLabels().get(ClusterController.STRIMZI_CLUSTER_LABEL))).collect(Collectors.toList());

        add(namespace, addList);
        delete(namespace, deletionList);
        update(namespace, updateList);
    }

    /**
     * Gets the resources in the given namespace and with the given labels
     * from which an AbstractCluster representing the current state of the cluster can be obtained.
     * @param namespace The namespace
     * @param kafkaLabels The labels
     * @return The matching resources.
     */
    protected abstract List<R> getResources(String namespace, Map<String, String> kafkaLabels);

    protected final void add(String namespace, List<ConfigMap> add)   {
        for (ConfigMap cm : add) {
            log.info("Reconciliation: {} cluster {} should be added", clusterDescription, cm.getMetadata().getName());
            create(namespace, name(cm));
        }
    }

    protected final void update(String namespace, List<ConfigMap> update)   {
        for (ConfigMap cm : update) {
            log.info("Reconciliation: {} cluster {} should be checked for updates", clusterDescription, cm.getMetadata().getName());
            update(namespace, name(cm));
        }
    }

    protected final void delete(String namespace, List<R> delete)   {
        for (R resource : delete) {
            log.info("Reconciliation: {} cluster {} should be deleted", clusterDescription, resource.getMetadata().getName());
            delete(namespace, nameFromLabels(resource));
        }
    }

}
