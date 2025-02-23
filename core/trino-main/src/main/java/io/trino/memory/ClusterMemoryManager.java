/*
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
package io.trino.memory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Streams;
import com.google.common.io.Closer;
import io.airlift.http.client.HttpClient;
import io.airlift.json.JsonCodec;
import io.airlift.log.Logger;
import io.airlift.units.DataSize;
import io.airlift.units.Duration;
import io.trino.execution.LocationFactory;
import io.trino.execution.QueryExecution;
import io.trino.execution.QueryIdGenerator;
import io.trino.execution.scheduler.NodeSchedulerConfig;
import io.trino.memory.LowMemoryKiller.QueryMemoryInfo;
import io.trino.metadata.InternalNode;
import io.trino.metadata.InternalNodeManager;
import io.trino.server.BasicQueryInfo;
import io.trino.server.ServerConfig;
import io.trino.spi.QueryId;
import io.trino.spi.TrinoException;
import io.trino.spi.memory.ClusterMemoryPoolManager;
import io.trino.spi.memory.MemoryPoolId;
import io.trino.spi.memory.MemoryPoolInfo;
import org.weakref.jmx.JmxException;
import org.weakref.jmx.MBeanExporter;
import org.weakref.jmx.Managed;

import javax.annotation.PreDestroy;
import javax.annotation.concurrent.GuardedBy;
import javax.inject.Inject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;
import static com.google.common.base.Verify.verifyNotNull;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.MoreCollectors.toOptional;
import static com.google.common.collect.Sets.difference;
import static io.airlift.units.DataSize.succinctBytes;
import static io.airlift.units.Duration.nanosSince;
import static io.trino.ExceededMemoryLimitException.exceededGlobalTotalLimit;
import static io.trino.ExceededMemoryLimitException.exceededGlobalUserLimit;
import static io.trino.SystemSessionProperties.RESOURCE_OVERCOMMIT;
import static io.trino.SystemSessionProperties.getQueryMaxMemory;
import static io.trino.SystemSessionProperties.getQueryMaxTotalMemory;
import static io.trino.SystemSessionProperties.resourceOvercommit;
import static io.trino.memory.LocalMemoryManager.GENERAL_POOL;
import static io.trino.memory.LocalMemoryManager.RESERVED_POOL;
import static io.trino.metadata.NodeState.ACTIVE;
import static io.trino.metadata.NodeState.SHUTTING_DOWN;
import static io.trino.spi.StandardErrorCode.CLUSTER_OUT_OF_MEMORY;
import static java.lang.Math.min;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

public class ClusterMemoryManager
        implements ClusterMemoryPoolManager
{
    private static final Logger log = Logger.get(ClusterMemoryManager.class);

    private final ExecutorService listenerExecutor = Executors.newSingleThreadExecutor();
    private final ClusterMemoryLeakDetector memoryLeakDetector = new ClusterMemoryLeakDetector();
    private final InternalNodeManager nodeManager;
    private final LocationFactory locationFactory;
    private final HttpClient httpClient;
    private final MBeanExporter exporter;
    private final JsonCodec<MemoryInfo> memoryInfoCodec;
    private final JsonCodec<MemoryPoolAssignmentsRequest> assignmentsRequestJsonCodec;
    private final DataSize maxQueryMemory;
    private final DataSize maxQueryTotalMemory;
    private final LowMemoryKiller lowMemoryKiller;
    private final Duration killOnOutOfMemoryDelay;
    private final String coordinatorId;
    private final AtomicLong totalAvailableProcessors = new AtomicLong();
    private final AtomicLong memoryPoolAssignmentsVersion = new AtomicLong();
    private final AtomicLong clusterUserMemoryReservation = new AtomicLong();
    private final AtomicLong clusterTotalMemoryReservation = new AtomicLong();
    private final AtomicLong clusterMemoryBytes = new AtomicLong();
    private final AtomicLong queriesKilledDueToOutOfMemory = new AtomicLong();
    private final boolean isWorkScheduledOnCoordinator;

    @GuardedBy("this")
    private final Map<String, RemoteNodeMemory> nodes = new HashMap<>();

    @GuardedBy("this")
    private final Map<MemoryPoolId, List<Consumer<MemoryPoolInfo>>> changeListeners = new HashMap<>();

    @GuardedBy("this")
    private final Map<MemoryPoolId, ClusterMemoryPool> pools;

    @GuardedBy("this")
    private long lastTimeNotOutOfMemory = System.nanoTime();

    @GuardedBy("this")
    private QueryId lastKilledQuery;

    @Inject
    public ClusterMemoryManager(
            @ForMemoryManager HttpClient httpClient,
            InternalNodeManager nodeManager,
            LocationFactory locationFactory,
            MBeanExporter exporter,
            JsonCodec<MemoryInfo> memoryInfoCodec,
            JsonCodec<MemoryPoolAssignmentsRequest> assignmentsRequestJsonCodec,
            QueryIdGenerator queryIdGenerator,
            LowMemoryKiller lowMemoryKiller,
            ServerConfig serverConfig,
            MemoryManagerConfig config,
            NodeMemoryConfig nodeMemoryConfig,
            NodeSchedulerConfig schedulerConfig)
    {
        requireNonNull(config, "config is null");
        requireNonNull(nodeMemoryConfig, "nodeMemoryConfig is null");
        requireNonNull(serverConfig, "serverConfig is null");
        requireNonNull(schedulerConfig, "schedulerConfig is null");
        checkState(serverConfig.isCoordinator(), "ClusterMemoryManager must not be bound on worker");

        this.nodeManager = requireNonNull(nodeManager, "nodeManager is null");
        this.locationFactory = requireNonNull(locationFactory, "locationFactory is null");
        this.httpClient = requireNonNull(httpClient, "httpClient is null");
        this.exporter = requireNonNull(exporter, "exporter is null");
        this.memoryInfoCodec = requireNonNull(memoryInfoCodec, "memoryInfoCodec is null");
        this.assignmentsRequestJsonCodec = requireNonNull(assignmentsRequestJsonCodec, "assignmentsRequestJsonCodec is null");
        this.lowMemoryKiller = requireNonNull(lowMemoryKiller, "lowMemoryKiller is null");
        this.maxQueryMemory = config.getMaxQueryMemory();
        this.maxQueryTotalMemory = config.getMaxQueryTotalMemory();
        this.coordinatorId = queryIdGenerator.getCoordinatorId();
        this.killOnOutOfMemoryDelay = config.getKillOnOutOfMemoryDelay();
        this.isWorkScheduledOnCoordinator = schedulerConfig.isIncludeCoordinator();

        verify(maxQueryMemory.toBytes() <= maxQueryTotalMemory.toBytes(),
                "maxQueryMemory cannot be greater than maxQueryTotalMemory");

        this.pools = createClusterMemoryPools(!nodeMemoryConfig.isReservedPoolDisabled());
    }

    private Map<MemoryPoolId, ClusterMemoryPool> createClusterMemoryPools(boolean reservedPoolEnabled)
    {
        Set<MemoryPoolId> memoryPools = new HashSet<>();
        memoryPools.add(GENERAL_POOL);
        if (reservedPoolEnabled) {
            memoryPools.add(RESERVED_POOL);
        }

        ImmutableMap.Builder<MemoryPoolId, ClusterMemoryPool> builder = ImmutableMap.builder();
        for (MemoryPoolId poolId : memoryPools) {
            ClusterMemoryPool pool = new ClusterMemoryPool(poolId);
            builder.put(poolId, pool);
            try {
                exporter.exportWithGeneratedName(pool, ClusterMemoryPool.class, poolId.toString());
            }
            catch (JmxException e) {
                log.error(e, "Error exporting memory pool %s", poolId);
            }
        }
        return builder.build();
    }

    @Override
    public synchronized void addChangeListener(MemoryPoolId poolId, Consumer<MemoryPoolInfo> listener)
    {
        verify(memoryPoolExists(poolId), "Memory pool does not exist: %s", poolId);
        changeListeners.computeIfAbsent(poolId, id -> new ArrayList<>()).add(listener);
    }

    public synchronized boolean memoryPoolExists(MemoryPoolId poolId)
    {
        return pools.containsKey(poolId);
    }

    public synchronized void process(Iterable<QueryExecution> runningQueries, Supplier<List<BasicQueryInfo>> allQueryInfoSupplier)
    {
        // TODO revocable memory reservations can also leak and may need to be detected in the future
        // We are only concerned about the leaks in general pool.
        memoryLeakDetector.checkForMemoryLeaks(allQueryInfoSupplier, pools.get(GENERAL_POOL).getQueryMemoryReservations());

        boolean outOfMemory = isClusterOutOfMemory();
        if (!outOfMemory) {
            lastTimeNotOutOfMemory = System.nanoTime();
        }

        boolean queryKilled = false;
        long totalUserMemoryBytes = 0L;
        long totalMemoryBytes = 0L;
        for (QueryExecution query : runningQueries) {
            boolean resourceOvercommit = resourceOvercommit(query.getSession());
            long userMemoryReservation = query.getUserMemoryReservation().toBytes();
            long totalMemoryReservation = query.getTotalMemoryReservation().toBytes();

            if (resourceOvercommit && outOfMemory) {
                // If a query has requested resource overcommit, only kill it if the cluster has run out of memory
                DataSize memory = succinctBytes(getQueryMemoryReservation(query));
                query.fail(new TrinoException(CLUSTER_OUT_OF_MEMORY,
                        format("The cluster is out of memory and %s=true, so this query was killed. It was using %s of memory", RESOURCE_OVERCOMMIT, memory)));
                queryKilled = true;
            }

            if (!resourceOvercommit) {
                long userMemoryLimit = min(maxQueryMemory.toBytes(), getQueryMaxMemory(query.getSession()).toBytes());
                if (userMemoryReservation > userMemoryLimit) {
                    query.fail(exceededGlobalUserLimit(succinctBytes(userMemoryLimit)));
                    queryKilled = true;
                }

                long totalMemoryLimit = min(maxQueryTotalMemory.toBytes(), getQueryMaxTotalMemory(query.getSession()).toBytes());
                if (totalMemoryReservation > totalMemoryLimit) {
                    query.fail(exceededGlobalTotalLimit(succinctBytes(totalMemoryLimit)));
                    queryKilled = true;
                }
            }

            totalUserMemoryBytes += userMemoryReservation;
            totalMemoryBytes += totalMemoryReservation;
        }

        clusterUserMemoryReservation.set(totalUserMemoryBytes);
        clusterTotalMemoryReservation.set(totalMemoryBytes);

        if (!(lowMemoryKiller instanceof NoneLowMemoryKiller) &&
                outOfMemory &&
                !queryKilled &&
                nanosSince(lastTimeNotOutOfMemory).compareTo(killOnOutOfMemoryDelay) > 0) {
            if (isLastKilledQueryGone()) {
                callOomKiller(runningQueries);
            }
            else {
                log.debug("Last killed query is still not gone: %s", lastKilledQuery);
            }
        }

        Map<MemoryPoolId, Integer> countByPool = new HashMap<>();
        for (QueryExecution query : runningQueries) {
            MemoryPoolId id = query.getMemoryPool().getId();
            countByPool.put(id, countByPool.getOrDefault(id, 0) + 1);
        }

        updatePools(countByPool);

        MemoryPoolAssignmentsRequest assignmentsRequest;
        if (pools.containsKey(RESERVED_POOL)) {
            assignmentsRequest = updateAssignments(runningQueries);
        }
        else {
            // If reserved pool is not enabled, we don't create a MemoryPoolAssignmentsRequest that puts all the queries
            // in the general pool (as they already are). In this case we create an effectively NOOP MemoryPoolAssignmentsRequest.
            // Once the reserved pool is removed we should get rid of the logic of putting queries into reserved pool including
            // this piece of code.
            assignmentsRequest = new MemoryPoolAssignmentsRequest(coordinatorId, Long.MIN_VALUE, ImmutableList.of());
        }
        updateNodes(assignmentsRequest);
    }

    private synchronized void callOomKiller(Iterable<QueryExecution> runningQueries)
    {
        List<QueryMemoryInfo> queryMemoryInfoList = Streams.stream(runningQueries)
                .map(this::createQueryMemoryInfo)
                .collect(toImmutableList());
        List<MemoryInfo> nodeMemoryInfos = nodes.values().stream()
                .map(RemoteNodeMemory::getInfo)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(toImmutableList());
        Optional<QueryId> chosenQueryId = lowMemoryKiller.chooseQueryToKill(queryMemoryInfoList, nodeMemoryInfos);
        if (chosenQueryId.isPresent()) {
            log.debug("Low memory killer chose %s", chosenQueryId.get());
            Optional<QueryExecution> chosenQuery = Streams.stream(runningQueries).filter(query -> chosenQueryId.get().equals(query.getQueryId())).collect(toOptional());
            if (chosenQuery.isPresent()) {
                // See comments in  isLastKilledQueryGone for why chosenQuery might be absent.
                chosenQuery.get().fail(new TrinoException(CLUSTER_OUT_OF_MEMORY, "Query killed because the cluster is out of memory. Please try again in a few minutes."));
                queriesKilledDueToOutOfMemory.incrementAndGet();
                lastKilledQuery = chosenQueryId.get();
                logQueryKill(chosenQueryId.get(), nodeMemoryInfos);
            }
        }
    }

    @GuardedBy("this")
    private boolean isLastKilledQueryGone()
    {
        if (lastKilledQuery == null) {
            return true;
        }

        // If the lastKilledQuery is marked as leaked by the ClusterMemoryLeakDetector we consider the lastKilledQuery as gone,
        // so that the ClusterMemoryManager can continue to make progress even if there are leaks.
        // Even if the weak references to the leaked queries are GCed in the ClusterMemoryLeakDetector, it will mark the same queries
        // as leaked in its next run, and eventually the ClusterMemoryManager will make progress.
        if (memoryLeakDetector.wasQueryPossiblyLeaked(lastKilledQuery)) {
            lastKilledQuery = null;
            return true;
        }

        // pools fields is updated based on nodes field.
        // Therefore, if the query is gone from pools field, it should also be gone from nodes field.
        // However, since nodes can updated asynchronously, it has the potential of coming back after being gone.
        // Therefore, even if the query appears to be gone here, it might be back when one inspects nodes later.
        return !pools.get(GENERAL_POOL)
                .getQueryMemoryReservations()
                .containsKey(lastKilledQuery);
    }

    private void logQueryKill(QueryId killedQueryId, List<MemoryInfo> nodes)
    {
        if (!log.isInfoEnabled()) {
            return;
        }
        StringBuilder nodeDescription = new StringBuilder();
        nodeDescription.append("Query Kill Decision: Killed ").append(killedQueryId).append("\n");
        for (MemoryInfo node : nodes) {
            MemoryPoolInfo memoryPoolInfo = node.getPools().get(GENERAL_POOL);
            if (memoryPoolInfo == null) {
                continue;
            }
            nodeDescription.append("Query Kill Scenario: ");
            nodeDescription.append("MaxBytes ").append(memoryPoolInfo.getMaxBytes()).append(' ');
            nodeDescription.append("FreeBytes ").append(memoryPoolInfo.getFreeBytes() + memoryPoolInfo.getReservedRevocableBytes()).append(' ');
            nodeDescription.append("Queries ");
            Joiner.on(",").withKeyValueSeparator("=").appendTo(nodeDescription, memoryPoolInfo.getQueryMemoryReservations());
            nodeDescription.append('\n');
        }
        log.info("%s", nodeDescription);
    }

    @VisibleForTesting
    synchronized Map<MemoryPoolId, ClusterMemoryPool> getPools()
    {
        return ImmutableMap.copyOf(pools);
    }

    public synchronized Map<MemoryPoolId, MemoryPoolInfo> getMemoryPoolInfo()
    {
        ImmutableMap.Builder<MemoryPoolId, MemoryPoolInfo> builder = new ImmutableMap.Builder<>();
        pools.forEach((poolId, memoryPool) -> builder.put(poolId, memoryPool.getInfo()));
        return builder.build();
    }

    private synchronized boolean isClusterOutOfMemory()
    {
        ClusterMemoryPool reservedPool = pools.get(RESERVED_POOL);
        ClusterMemoryPool generalPool = pools.get(GENERAL_POOL);
        if (reservedPool == null) {
            return generalPool.getBlockedNodes() > 0;
        }
        return reservedPool.getAssignedQueries() > 0 && generalPool.getBlockedNodes() > 0;
    }

    // TODO once the reserved pool is removed we can remove this method. We can also update
    // RemoteNodeMemory as we don't need to POST anything.
    private synchronized MemoryPoolAssignmentsRequest updateAssignments(Iterable<QueryExecution> queries)
    {
        ClusterMemoryPool reservedPool = verifyNotNull(pools.get(RESERVED_POOL), "reservedPool is null");
        ClusterMemoryPool generalPool = verifyNotNull(pools.get(GENERAL_POOL), "generalPool is null");
        long version = memoryPoolAssignmentsVersion.incrementAndGet();
        // Check that all previous assignments have propagated to the visible nodes. This doesn't account for temporary network issues,
        // and is more of a safety check than a guarantee
        if (allAssignmentsHavePropagated(queries)) {
            if (reservedPool.getAssignedQueries() == 0 && generalPool.getBlockedNodes() > 0) {
                QueryExecution biggestQuery = null;
                long maxMemory = -1;
                for (QueryExecution queryExecution : queries) {
                    if (resourceOvercommit(queryExecution.getSession())) {
                        // Don't promote queries that requested resource overcommit to the reserved pool,
                        // since their memory usage is unbounded.
                        continue;
                    }

                    long bytesUsed = getQueryMemoryReservation(queryExecution);
                    if (bytesUsed > maxMemory) {
                        biggestQuery = queryExecution;
                        maxMemory = bytesUsed;
                    }
                }
                if (biggestQuery != null) {
                    log.info("Moving query %s to the reserved pool", biggestQuery.getQueryId());
                    biggestQuery.setMemoryPool(new VersionedMemoryPoolId(RESERVED_POOL, version));
                }
            }
        }

        ImmutableList.Builder<MemoryPoolAssignment> assignments = ImmutableList.builder();
        for (QueryExecution queryExecution : queries) {
            assignments.add(new MemoryPoolAssignment(queryExecution.getQueryId(), queryExecution.getMemoryPool().getId()));
        }
        return new MemoryPoolAssignmentsRequest(coordinatorId, version, assignments.build());
    }

    private QueryMemoryInfo createQueryMemoryInfo(QueryExecution query)
    {
        return new QueryMemoryInfo(query.getQueryId(), query.getMemoryPool().getId(), query.getTotalMemoryReservation().toBytes());
    }

    private long getQueryMemoryReservation(QueryExecution query)
    {
        return query.getTotalMemoryReservation().toBytes();
    }

    private synchronized boolean allAssignmentsHavePropagated(Iterable<QueryExecution> queries)
    {
        if (nodes.isEmpty()) {
            // Assignments can't have propagated, if there are no visible nodes.
            return false;
        }
        long newestAssignment = ImmutableList.copyOf(queries).stream()
                .map(QueryExecution::getMemoryPool)
                .mapToLong(VersionedMemoryPoolId::getVersion)
                .min()
                .orElse(-1);

        long mostOutOfDateNode = nodes.values().stream()
                .mapToLong(RemoteNodeMemory::getCurrentAssignmentVersion)
                .min()
                .orElse(Long.MAX_VALUE);

        return newestAssignment <= mostOutOfDateNode;
    }

    private synchronized void updateNodes(MemoryPoolAssignmentsRequest assignments)
    {
        ImmutableSet.Builder<InternalNode> builder = ImmutableSet.builder();
        Set<InternalNode> aliveNodes = builder
                .addAll(nodeManager.getNodes(ACTIVE))
                .addAll(nodeManager.getNodes(SHUTTING_DOWN))
                .build();

        ImmutableSet<String> aliveNodeIds = aliveNodes.stream()
                .map(InternalNode::getNodeIdentifier)
                .collect(toImmutableSet());

        // Remove nodes that don't exist anymore
        // Make a copy to materialize the set difference
        Set<String> deadNodes = ImmutableSet.copyOf(difference(nodes.keySet(), aliveNodeIds));
        nodes.keySet().removeAll(deadNodes);

        // Add new nodes
        for (InternalNode node : aliveNodes) {
            if (!nodes.containsKey(node.getNodeIdentifier())) {
                nodes.put(node.getNodeIdentifier(), new RemoteNodeMemory(node, httpClient, memoryInfoCodec, assignmentsRequestJsonCodec, locationFactory.createMemoryInfoLocation(node)));
            }
        }

        // If work isn't scheduled on the coordinator (the current node) there is no point
        // in polling or updating (when moving queries to the reserved pool) its memory pools
        if (!isWorkScheduledOnCoordinator) {
            nodes.remove(nodeManager.getCurrentNode().getNodeIdentifier());
        }

        // Schedule refresh
        for (RemoteNodeMemory node : nodes.values()) {
            node.asyncRefresh(assignments);
        }
    }

    private synchronized void updatePools(Map<MemoryPoolId, Integer> queryCounts)
    {
        // Update view of cluster memory and pools
        List<MemoryInfo> nodeMemoryInfos = nodes.values().stream()
                .map(RemoteNodeMemory::getInfo)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(toImmutableList());

        long totalProcessors = nodeMemoryInfos.stream()
                .mapToLong(MemoryInfo::getAvailableProcessors)
                .sum();
        totalAvailableProcessors.set(totalProcessors);

        long totalClusterMemory = nodeMemoryInfos.stream()
                .map(MemoryInfo::getTotalNodeMemory)
                .mapToLong(DataSize::toBytes)
                .sum();
        clusterMemoryBytes.set(totalClusterMemory);

        for (ClusterMemoryPool pool : pools.values()) {
            pool.update(nodeMemoryInfos, queryCounts.getOrDefault(pool.getId(), 0));
            if (changeListeners.containsKey(pool.getId())) {
                MemoryPoolInfo info = pool.getInfo();
                for (Consumer<MemoryPoolInfo> listener : changeListeners.get(pool.getId())) {
                    listenerExecutor.execute(() -> listener.accept(info));
                }
            }
        }
    }

    public synchronized Map<String, Optional<MemoryInfo>> getWorkerMemoryInfo()
    {
        Map<String, Optional<MemoryInfo>> memoryInfo = new HashMap<>();
        for (Entry<String, RemoteNodeMemory> entry : nodes.entrySet()) {
            // workerId is of the form "node_identifier [node_host]"
            String workerId = entry.getKey() + " [" + entry.getValue().getNode().getHost() + "]";
            memoryInfo.put(workerId, entry.getValue().getInfo());
        }
        return memoryInfo;
    }

    @PreDestroy
    public synchronized void destroy()
            throws IOException
    {
        try (Closer closer = Closer.create()) {
            for (ClusterMemoryPool pool : pools.values()) {
                closer.register(() -> exporter.unexportWithGeneratedName(ClusterMemoryPool.class, pool.getId().toString()));
            }
            closer.register(listenerExecutor::shutdownNow);
        }
    }

    @Managed
    public long getTotalAvailableProcessors()
    {
        return totalAvailableProcessors.get();
    }

    @Managed
    public int getNumberOfLeakedQueries()
    {
        return memoryLeakDetector.getNumberOfLeakedQueries();
    }

    @Managed
    public long getClusterUserMemoryReservation()
    {
        return clusterUserMemoryReservation.get();
    }

    @Managed
    public long getClusterTotalMemoryReservation()
    {
        return clusterTotalMemoryReservation.get();
    }

    @Managed
    public long getClusterMemoryBytes()
    {
        return clusterMemoryBytes.get();
    }

    @Managed
    public long getQueriesKilledDueToOutOfMemory()
    {
        return queriesKilledDueToOutOfMemory.get();
    }
}
