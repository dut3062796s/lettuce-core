/*
 * Copyright 2011-2018 the original author or authors.
 *
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
 */
package io.lettuce.core.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assume.assumeTrue;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.Ignore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.lettuce.KeysAndValues;
import io.lettuce.RedisConditions;
import io.lettuce.core.*;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.async.RedisAdvancedClusterAsyncCommands;
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands;
import io.lettuce.core.cluster.api.reactive.RedisAdvancedClusterReactiveCommands;
import io.lettuce.core.cluster.api.reactive.RedisClusterReactiveCommands;
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;
import io.lettuce.core.cluster.models.partitions.Partitions;
import io.lettuce.core.cluster.models.partitions.RedisClusterNode;

/**
 * @author Mark Paluch
 */
@SuppressWarnings("rawtypes")
public class AdvancedClusterClientTest extends AbstractClusterTest {

    public static final String KEY_ON_NODE_1 = "a";
    public static final String KEY_ON_NODE_2 = "b";

    private RedisAdvancedClusterAsyncCommands<String, String> commands;
    private RedisAdvancedClusterCommands<String, String> syncCommands;
    private StatefulRedisClusterConnection<String, String> clusterConnection;

    @BeforeEach
    public void before() {
        clusterClient.reloadPartitions();
        clusterConnection = clusterClient.connect();
        commands = clusterConnection.async();
        syncCommands = clusterConnection.sync();
    }

    @AfterEach
    public void after() {
        commands.getStatefulConnection().close();
    }

    @Test
    public void nodeConnections() throws Exception {

        assertThat(clusterClient.getPartitions()).hasSize(4);

        for (RedisClusterNode redisClusterNode : clusterClient.getPartitions()) {
            RedisClusterAsyncCommands<String, String> nodeConnection = commands.getConnection(redisClusterNode.getNodeId());

            String myid = nodeConnection.clusterMyId().get();
            assertThat(myid).isEqualTo(redisClusterNode.getNodeId());
        }
    }

    @Test
    public void unknownNodeId() {

        assertThatThrownBy(() -> commands.getConnection("unknown")).isInstanceOf(RedisException.class);
    }

    @Test
    public void invalidHost() {
        assertThatThrownBy(() -> commands.getConnection("invalid-host", -1)).isInstanceOf(RedisException.class);
    }

    @Test
    public void partitions() {

        Partitions partitions = commands.getStatefulConnection().getPartitions();
        assertThat(partitions).hasSize(4);
    }

    @Test
    public void differentConnections() {

        for (RedisClusterNode redisClusterNode : clusterClient.getPartitions()) {
            RedisClusterAsyncCommands<String, String> nodeId = commands.getConnection(redisClusterNode.getNodeId());
            RedisClusterAsyncCommands<String, String> hostAndPort = commands.getConnection(redisClusterNode.getUri().getHost(),
                    redisClusterNode.getUri().getPort());

            assertThat(nodeId).isNotSameAs(hostAndPort);
        }

        StatefulRedisClusterConnection<String, String> statefulConnection = commands.getStatefulConnection();
        for (RedisClusterNode redisClusterNode : clusterClient.getPartitions()) {

            StatefulRedisConnection<String, String> nodeId = statefulConnection.getConnection(redisClusterNode.getNodeId());
            StatefulRedisConnection<String, String> hostAndPort = statefulConnection.getConnection(redisClusterNode.getUri()
                    .getHost(), redisClusterNode.getUri().getPort());

            assertThat(nodeId).isNotSameAs(hostAndPort);
        }

        RedisAdvancedClusterCommands<String, String> sync = statefulConnection.sync();
        for (RedisClusterNode redisClusterNode : clusterClient.getPartitions()) {

            RedisClusterCommands<String, String> nodeId = sync.getConnection(redisClusterNode.getNodeId());
            RedisClusterCommands<String, String> hostAndPort = sync.getConnection(redisClusterNode.getUri().getHost(),
                    redisClusterNode.getUri().getPort());

            assertThat(nodeId).isNotSameAs(hostAndPort);
        }

        RedisAdvancedClusterReactiveCommands<String, String> rx = statefulConnection.reactive();
        for (RedisClusterNode redisClusterNode : clusterClient.getPartitions()) {

            RedisClusterReactiveCommands<String, String> nodeId = rx.getConnection(redisClusterNode.getNodeId());
            RedisClusterReactiveCommands<String, String> hostAndPort = rx.getConnection(redisClusterNode.getUri().getHost(),
                    redisClusterNode.getUri().getPort());

            assertThat(nodeId).isNotSameAs(hostAndPort);
        }
    }

    @Test
    public void msetRegular() {

        Map<String, String> mset = Collections.singletonMap(key, value);

        String result = syncCommands.mset(mset);

        assertThat(result).isEqualTo("OK");
        assertThat(syncCommands.get(key)).isEqualTo(value);
    }

    @Test
    public void msetCrossSlot() {

        Map<String, String> mset = prepareMset();

        String result = syncCommands.mset(mset);

        assertThat(result).isEqualTo("OK");

        for (String mykey : mset.keySet()) {
            String s1 = syncCommands.get(mykey);
            assertThat(s1).isEqualTo("value-" + mykey);
        }
    }

    @Test
    public void msetnxCrossSlot() throws Exception {

        Map<String, String> mset = prepareMset();

        String key = mset.keySet().iterator().next();
        Map<String, String> submap = Collections.singletonMap(key, mset.get(key));

        assertThat(commands.msetnx(submap).get()).isTrue();
        assertThat(commands.msetnx(mset).get()).isFalse();

        for (String mykey : mset.keySet()) {
            String s1 = commands.get(mykey).get();
            assertThat(s1).isEqualTo("value-" + mykey);
        }
    }

    @Test
    public void mgetRegular() {

        msetRegular();
        List<KeyValue<String, String>> result = syncCommands.mget(key);

        assertThat(result).hasSize(1);
    }

    @Test
    public void mgetCrossSlot() {

        msetCrossSlot();
        List<String> keys = new ArrayList<>();
        List<KeyValue<String, String>> expectation = new ArrayList<>();
        for (char c = 'a'; c < 'z'; c++) {
            String key = new String(new char[] { c, c, c });
            keys.add(key);
            expectation.add(kv(key, "value-" + key));
        }

        List<KeyValue<String, String>> result = syncCommands.mget(keys.toArray(new String[keys.size()]));

        assertThat(result).hasSize(keys.size());
        assertThat(result).isEqualTo(expectation);
    }

    @Test
    public void delRegular() throws Exception {

        assumeTrue(RedisConditions.of(syncCommands.getStatefulConnection()).hasCommand("UNLINK"));

        msetRegular();
        Long result = syncCommands.unlink(key);

        assertThat(result).isEqualTo(1);
        assertThat(commands.get(key).get()).isNull();
    }

    @Test
    public void delCrossSlot() {

        List<String> keys = prepareKeys();

        Long result = syncCommands.del(keys.toArray(new String[keys.size()]));

        assertThat(result).isEqualTo(25);

        for (String mykey : keys) {
            String s1 = syncCommands.get(mykey);
            assertThat(s1).isNull();
        }
    }

    @Test
    public void unlinkRegular() {

        assumeTrue(RedisConditions.of(syncCommands.getStatefulConnection()).hasCommand("UNLINK"));

        msetRegular();
        Long result = syncCommands.unlink(key);

        assertThat(result).isEqualTo(1);
        assertThat(syncCommands.get(key)).isNull();
    }

    @Test
    public void unlinkCrossSlot() {

        assumeTrue(RedisConditions.of(syncCommands.getStatefulConnection()).hasCommand("UNLINK"));

        List<String> keys = prepareKeys();

        Long result = syncCommands.unlink(keys.toArray(new String[keys.size()]));

        assertThat(result).isEqualTo(25);

        for (String mykey : keys) {
            String s1 = syncCommands.get(mykey);
            assertThat(s1).isNull();
        }
    }

    protected List<String> prepareKeys() {
        msetCrossSlot();
        List<String> keys = new ArrayList<>();
        for (char c = 'a'; c < 'z'; c++) {
            String key = new String(new char[] { c, c, c });
            keys.add(key);
        }
        return keys;
    }

    @Test
    public void clientSetname() {

        String name = "test-cluster-client";

        assertThat(clusterClient.getPartitions().size()).isGreaterThan(0);

        syncCommands.clientSetname(name);

        for (RedisClusterNode redisClusterNode : clusterClient.getPartitions()) {
            RedisClusterCommands<String, String> nodeConnection = commands.getStatefulConnection().sync()
                    .getConnection(redisClusterNode.getNodeId());
            assertThat(nodeConnection.clientList()).contains(name);
        }

        assertThat(syncCommands.clientGetname()).isEqualTo(name);
    }

    @Test
    public void clientSetnameRunOnError() {
        assertThatThrownBy(() -> syncCommands.clientSetname("not allowed")).isInstanceOf(RedisCommandExecutionException.class);
    }

    @Test
    public void dbSize() {

        writeKeysToTwoNodes();

        RedisClusterCommands<String, String> nodeConnection1 = clusterConnection.getConnection(host, port1).sync();
        RedisClusterCommands<String, String> nodeConnection2 = clusterConnection.getConnection(host, port2).sync();

        assertThat(nodeConnection1.dbsize()).isEqualTo(1);
        assertThat(nodeConnection2.dbsize()).isEqualTo(1);

        Long dbsize = syncCommands.dbsize();
        assertThat(dbsize).isEqualTo(2);
    }

    @Test
    public void flushall() {

        writeKeysToTwoNodes();

        assertThat(syncCommands.flushall()).isEqualTo("OK");

        Long dbsize = syncCommands.dbsize();
        assertThat(dbsize).isEqualTo(0);
    }

    @Test
    public void flushdb() {

        writeKeysToTwoNodes();

        assertThat(syncCommands.flushdb()).isEqualTo("OK");

        Long dbsize = syncCommands.dbsize();
        assertThat(dbsize).isEqualTo(0);
    }

    @Test
    public void keys() {

        writeKeysToTwoNodes();

        assertThat(syncCommands.keys("*")).contains(KEY_ON_NODE_1, KEY_ON_NODE_2);
    }

    @Test
    public void keysStreaming() {

        writeKeysToTwoNodes();
        ListStreamingAdapter<String> result = new ListStreamingAdapter<>();

        assertThat(syncCommands.keys(result, "*")).isEqualTo(2);
        assertThat(result.getList()).contains(KEY_ON_NODE_1, KEY_ON_NODE_2);
    }

    @Test
    public void randomKey() {

        writeKeysToTwoNodes();

        assertThat(syncCommands.randomkey()).isIn(KEY_ON_NODE_1, KEY_ON_NODE_2);
    }

    @Test
    public void scriptFlush() {
        assertThat(syncCommands.scriptFlush()).isEqualTo("OK");
    }

    @Test
    public void scriptKill() {
        assertThat(syncCommands.scriptKill()).isEqualTo("OK");
    }

    @Test
    public void scriptLoad() {

        assertThat(syncCommands.scriptFlush()).isEqualTo("OK");

        String script = "return true";

        String sha = LettuceStrings.digest(script.getBytes());
        assertThat(syncCommands.scriptExists(sha)).contains(false);

        String returnedSha = syncCommands.scriptLoad(script);

        assertThat(returnedSha).isEqualTo(sha);
        assertThat(syncCommands.scriptExists(sha)).contains(true);
    }

    @Test
    @Ignore("Run me manually, I will shutdown all your cluster nodes so you need to restart the Redis Cluster after this test")
    public void shutdown() {
        syncCommands.shutdown(true);
    }

    @Test
    public void testSync() {

        RedisAdvancedClusterCommands<String, String> sync = commands.getStatefulConnection().sync();
        sync.set(key, value);
        assertThat(sync.get(key)).isEqualTo(value);

        RedisClusterCommands<String, String> node2Connection = sync.getConnection(host, port2);
        assertThat(node2Connection.get(key)).isEqualTo(value);

        assertThat(sync.getStatefulConnection()).isSameAs(commands.getStatefulConnection());
    }

    @Test
    public void routeCommandTonoAddrPartition() {

        RedisAdvancedClusterCommands<String, String> sync = clusterClient.connect().sync();
        try {

            Partitions partitions = clusterClient.getPartitions();
            for (RedisClusterNode partition : partitions) {
                partition.setUri(RedisURI.create("redis://non.existent.host:1234"));
            }

            sync.set("A", "value");// 6373
        } catch (Exception e) {
            assertThat(e).isInstanceOf(RedisException.class).hasMessageContaining("Unable to connect to");
        } finally {
            clusterClient.getPartitions().clear();
            clusterClient.reloadPartitions();
        }
        sync.getStatefulConnection().close();
    }

    @Test
    public void routeCommandToForbiddenHostOnRedirect() {

        RedisAdvancedClusterCommands<String, String> sync = clusterClient.connect().sync();
        try {

            Partitions partitions = clusterClient.getPartitions();
            for (RedisClusterNode partition : partitions) {
                partition.setSlots(Collections.singletonList(0));
                if (partition.getUri().getPort() == 7380) {
                    partition.setSlots(Collections.singletonList(6373));
                } else {
                    partition.setUri(RedisURI.create("redis://non.existent.host:1234"));
                }
            }

            partitions.updateCache();

            sync.set("A", "value");// 6373
        } catch (Exception e) {
            assertThat(e).isInstanceOf(RedisException.class).hasMessageContaining("not allowed");
        } finally {
            clusterClient.getPartitions().clear();
            clusterClient.reloadPartitions();
        }
        sync.getStatefulConnection().close();
    }

    @Test
    public void getConnectionToNotAClusterMemberForbidden() {

        StatefulRedisClusterConnection<String, String> sync = clusterClient.connect();
        try {
            sync.getConnection(TestSettings.host(), TestSettings.port());
        } catch (RedisException e) {
            assertThat(e).hasRootCauseExactlyInstanceOf(IllegalArgumentException.class);
        }
        sync.close();
    }

    @Test
    public void getConnectionToNotAClusterMemberAllowed() {

        clusterClient.setOptions(ClusterClientOptions.builder().validateClusterNodeMembership(false).build());
        StatefulRedisClusterConnection<String, String> connection = clusterClient.connect();
        connection.getConnection(TestSettings.host(), TestSettings.port());
        connection.close();
    }

    @Test
    public void pipelining() throws Exception {

        RedisAdvancedClusterCommands<String, String> verificationConnection = clusterClient.connect().sync();

        // preheat the first connection
        commands.get(key(0)).get();

        int iterations = 1000;
        commands.setAutoFlushCommands(false);
        List<RedisFuture<?>> futures = new ArrayList<>();
        for (int i = 0; i < iterations; i++) {
            futures.add(commands.set(key(i), value(i)));
        }

        for (int i = 0; i < iterations; i++) {
            assertThat(verificationConnection.get(key(i))).as("Key " + key(i) + " must be null").isNull();
        }

        commands.flushCommands();
        boolean result = LettuceFutures.awaitAll(5, TimeUnit.SECONDS, futures.toArray(new RedisFuture[futures.size()]));
        assertThat(result).isTrue();

        for (int i = 0; i < iterations; i++) {
            assertThat(verificationConnection.get(key(i))).as("Key " + key(i) + " must be " + value(i)).isEqualTo(value(i));
        }

        verificationConnection.getStatefulConnection().close();
    }

    @Test
    public void clusterScan() {

        RedisAdvancedClusterCommands<String, String> sync = commands.getStatefulConnection().sync();
        sync.mset(KeysAndValues.MAP);

        Set<String> allKeys = new HashSet<>();

        KeyScanCursor<String> scanCursor = null;

        do {
            if (scanCursor == null) {
                scanCursor = sync.scan();
            } else {
                scanCursor = sync.scan(scanCursor);
            }
            allKeys.addAll(scanCursor.getKeys());
        } while (!scanCursor.isFinished());

        assertThat(allKeys).containsAll(KeysAndValues.KEYS);

    }

    @Test
    public void clusterScanWithArgs() {

        RedisAdvancedClusterCommands<String, String> sync = commands.getStatefulConnection().sync();
        sync.mset(KeysAndValues.MAP);

        Set<String> allKeys = new HashSet<>();

        KeyScanCursor<String> scanCursor = null;

        do {
            if (scanCursor == null) {
                scanCursor = sync.scan(ScanArgs.Builder.matches("a*"));
            } else {
                scanCursor = sync.scan(scanCursor, ScanArgs.Builder.matches("a*"));
            }
            allKeys.addAll(scanCursor.getKeys());
        } while (!scanCursor.isFinished());

        assertThat(allKeys)
                .containsAll(KeysAndValues.KEYS.stream().filter(k -> k.startsWith("a")).collect(Collectors.toList()));

    }

    @Test
    public void clusterScanStreaming() {

        RedisAdvancedClusterCommands<String, String> sync = commands.getStatefulConnection().sync();
        sync.mset(KeysAndValues.MAP);

        ListStreamingAdapter<String> adapter = new ListStreamingAdapter<>();

        StreamScanCursor scanCursor = null;

        do {
            if (scanCursor == null) {
                scanCursor = sync.scan(adapter);
            } else {
                scanCursor = sync.scan(adapter, scanCursor);
            }
        } while (!scanCursor.isFinished());

        assertThat(adapter.getList()).containsAll(KeysAndValues.KEYS);

    }

    @Test
    public void clusterScanStreamingWithArgs() {

        RedisAdvancedClusterCommands<String, String> sync = commands.getStatefulConnection().sync();
        sync.mset(KeysAndValues.MAP);

        ListStreamingAdapter<String> adapter = new ListStreamingAdapter<>();

        StreamScanCursor scanCursor = null;
        do {
            if (scanCursor == null) {
                scanCursor = sync.scan(adapter, ScanArgs.Builder.matches("a*"));
            } else {
                scanCursor = sync.scan(adapter, scanCursor, ScanArgs.Builder.matches("a*"));
            }
        } while (!scanCursor.isFinished());

        assertThat(adapter.getList()).containsAll(
                KeysAndValues.KEYS.stream().filter(k -> k.startsWith("a")).collect(Collectors.toList()));

    }

    @Test
    public void clusterScanCursorFinished() {
        assertThatThrownBy(() -> syncCommands.scan(ScanCursor.FINISHED)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void clusterScanCursorNotReused() {
        assertThatThrownBy(() -> syncCommands.scan(ScanCursor.of("dummy"))).isInstanceOf(IllegalArgumentException.class);
    }

    protected String value(int i) {
        return value + "-" + i;
    }

    protected String key(int i) {
        return key + "-" + i;
    }

    private void writeKeysToTwoNodes() {
        syncCommands.set(KEY_ON_NODE_1, value);
        syncCommands.set(KEY_ON_NODE_2, value);
    }

    protected Map<String, String> prepareMset() {
        Map<String, String> mset = new HashMap<>();
        for (char c = 'a'; c < 'z'; c++) {
            String key = new String(new char[] { c, c, c });
            mset.put(key, "value-" + key);
        }
        return mset;
    }

}
