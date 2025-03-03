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

package org.apache.dolphinscheduler.plugin.registry;

import static com.google.common.truth.Truth.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.apache.dolphinscheduler.registry.api.ConnectionState;
import org.apache.dolphinscheduler.registry.api.Event;
import org.apache.dolphinscheduler.registry.api.Registry;
import org.apache.dolphinscheduler.registry.api.RegistryException;
import org.apache.dolphinscheduler.registry.api.SubscribeListener;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import lombok.SneakyThrows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public abstract class RegistryTestCase<R extends Registry> {

    protected R registry;

    @BeforeEach
    public void setupRegistry() {
        registry = createRegistry();
    }

    @SneakyThrows
    @AfterEach
    public void tearDownRegistry() {
        try (R registry = this.registry) {
        }
    }

    @Test
    public void testIsConnected() {
        registry.start();
        assertThat(registry.isConnected()).isTrue();
    }

    @Test
    public void testConnectUntilTimeout() {
        registry.start();
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> registry.connectUntilTimeout(Duration.ofSeconds(3)));

    }

    @SneakyThrows
    @Test
    public void testSubscribe() {
        registry.start();

        final AtomicBoolean subscribeAdded = new AtomicBoolean(false);
        final AtomicBoolean subscribeRemoved = new AtomicBoolean(false);
        final AtomicBoolean subscribeUpdated = new AtomicBoolean(false);

        final SubscribeListener subscribeListener = new SubscribeListener() {

            @Override
            public void notify(Event event) {
                if (event.getType() == Event.Type.ADD) {
                    subscribeAdded.compareAndSet(false, true);
                }
                if (event.getType() == Event.Type.REMOVE) {
                    subscribeRemoved.compareAndSet(false, true);
                }
                if (event.getType() == Event.Type.UPDATE) {
                    subscribeUpdated.compareAndSet(false, true);
                }
            }

            @Override
            public SubscribeScope getSubscribeScope() {
                return SubscribeScope.PATH_ONLY;
            }
        };
        String key = "/nodes/master" + System.nanoTime();
        registry.subscribe(key, subscribeListener);
        registry.put(key, String.valueOf(System.nanoTime()), true);
        // Sleep 3 seconds here since in mysql jdbc registry
        // If multiple event occurs in a refresh time, only the last event will be triggered
        Thread.sleep(3000);
        registry.put(key, String.valueOf(System.nanoTime()), true);
        Thread.sleep(3000);
        registry.delete(key);

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> {
                    Assertions.assertTrue(subscribeAdded.get());
                    Assertions.assertTrue(subscribeUpdated.get());
                    Assertions.assertTrue(subscribeRemoved.get());
                });
    }

    @SneakyThrows
    @Test
    public void testAddConnectionStateListener() {

        AtomicReference<ConnectionState> connectionState = new AtomicReference<>();
        registry.addConnectionStateListener(connectionState::set);

        assertThat(connectionState.get()).isNull();
        registry.start();

        await().atMost(Duration.ofSeconds(2))
                .until(() -> ConnectionState.CONNECTED == connectionState.get());

    }

    @Test
    public void testGet() {
        registry.start();
        String key = "/nodes/master" + System.nanoTime();
        String value = "127.0.0.1:8080";
        assertThrows(RegistryException.class, () -> registry.get(key));
        registry.put(key, value, true);
        assertThat(registry.get(key)).isEqualTo(value);
    }

    @Test
    public void testPut() {
        registry.start();
        String key = "/nodes/master" + System.nanoTime();
        String value = "127.0.0.1:8080";
        registry.put(key, value, true);
        assertThat(registry.get(key)).isEqualTo(value);

        // Update the value
        registry.put(key, "123", true);
        assertThat(registry.get(key)).isEqualTo("123");
    }

    @Test
    public void testDelete() {
        registry.start();
        String key = "/nodes/master" + System.nanoTime();
        String value = "127.0.0.1:8080";
        // Delete a non-existent key
        registry.delete(key);

        registry.put(key, value, true);
        assertThat(registry.get(key)).isEqualTo(value);
        registry.delete(key);
        assertThat(registry.exists(key)).isFalse();

    }

    @Test
    public void testChildren() {
        registry.start();
        String master1 = "/nodes/children/childGroup1/127.0.0.1:8080";
        String master2 = "/nodes/children/childGroup1/127.0.0.2:8080";
        String value = "123";
        registry.put(master1, value, true);
        registry.put(master2, value, true);
        assertThat(registry.children("/nodes/children")).containsExactly("childGroup1");
        assertThat(registry.children("/nodes/children/childGroup1")).containsExactlyElementsIn(
                Arrays.asList("127.0.0.1:8080", "127.0.0.2:8080"));
    }

    @Test
    public void testExists() {
        registry.start();
        String key = "/nodes/master" + System.nanoTime();
        String value = "123";
        assertThat(registry.exists(key)).isFalse();
        registry.put(key, value, true);
        assertThat(registry.exists(key)).isTrue();

    }

    @SneakyThrows
    @Test
    public void testAcquireLock() {
        registry.start();
        String lockKey = "/lock" + System.nanoTime();

        // 1. Acquire the lock at the main thread
        assertThat(registry.acquireLock(lockKey)).isTrue();
        // Acquire the lock at the main thread again
        // It should acquire success
        assertThat(registry.acquireLock(lockKey)).isTrue();

        // Acquire the lock at another thread
        // It should acquire failed
        CompletableFuture<Boolean> acquireResult = CompletableFuture.supplyAsync(() -> registry.acquireLock(lockKey));
        assertThrows(TimeoutException.class, () -> acquireResult.get(3000, TimeUnit.MILLISECONDS));

    }

    @SneakyThrows
    @Test
    public void testAcquireLock_withTimeout() {
        registry.start();
        String lockKey = "/lock" + System.nanoTime();
        // 1. Acquire the lock in the main thread
        assertThat(registry.acquireLock(lockKey, 3000)).isTrue();

        // Acquire the lock in the main thread
        // It should acquire success
        assertThat(registry.acquireLock(lockKey, 3000)).isTrue();

        // Acquire the lock at another thread
        // It should acquire failed
        CompletableFuture<Boolean> acquireResult =
                CompletableFuture.supplyAsync(() -> registry.acquireLock(lockKey, 3000));
        assertThat(acquireResult.get()).isFalse();

    }

    @SneakyThrows
    @Test
    public void testReleaseLock() {
        registry.start();
        String lockKey = "/lock" + System.nanoTime();
        // 1. Acquire the lock in the main thread
        assertThat(registry.acquireLock(lockKey, 3000)).isTrue();

        // Acquire the lock at another thread
        // It should acquire failed
        CompletableFuture<Boolean> acquireResult =
                CompletableFuture.supplyAsync(() -> registry.acquireLock(lockKey, 3000));
        assertThat(acquireResult.get()).isFalse();

        // 2. Release the lock in the main thread
        assertThat(registry.releaseLock(lockKey)).isTrue();

        // Acquire the lock at another thread
        // It should acquire success
        acquireResult = CompletableFuture.supplyAsync(() -> registry.acquireLock(lockKey, 3000));
        assertThat(acquireResult.get()).isTrue();
    }

    public abstract R createRegistry();

}
