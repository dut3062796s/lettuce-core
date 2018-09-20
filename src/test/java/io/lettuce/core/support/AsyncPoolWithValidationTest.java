/*
 * Copyright 2017-2018 the original author or authors.
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
package io.lettuce.core.support;

import static io.lettuce.core.internal.Futures.failed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.junit.jupiter.MockitoExtension;

import io.lettuce.core.RedisException;

/**
 * @author Mark Paluch
 */
@ExtendWith(MockitoExtension.class)
public class AsyncPoolWithValidationTest {

    @Mock
    AsyncObjectFactory<String> factory;

    @BeforeEach
    public void before() throws Exception {
        when(factory.destroy(any())).thenReturn(CompletableFuture.completedFuture(null));
    }

    private void mockCreation() {

        AtomicInteger counter = new AtomicInteger();
        when(factory.create()).then(invocation -> CompletableFuture.completedFuture("" + counter.incrementAndGet()));
    }

    @Test
    public void objectCreationShouldFail() {

        when(factory.create()).thenReturn(failed(new RedisException("foo")));

        BoundedAsyncPool<String> pool = new BoundedAsyncPool<>(factory, BoundedPoolConfig.create());

        CompletableFuture<String> acquire = pool.acquire();

        assertThat(pool.getIdle()).isZero();
        assertThat(pool.getObjectCount()).isZero();
        assertThat(pool.getCreationInProgress()).isZero();

        assertThat(acquire).isCompletedExceptionally();
    }

    @Test
    public void objectCreationFinishesAfterShutdown() {

        CompletableFuture<String> progress = new CompletableFuture<>();

        when(factory.create()).thenReturn(progress);
        when(factory.destroy(any())).thenReturn(CompletableFuture.completedFuture(null));

        BoundedAsyncPool<String> pool = new BoundedAsyncPool<>(factory, BoundedPoolConfig.create());

        CompletableFuture<String> acquire = pool.acquire();

        assertThat(pool.getIdle()).isZero();
        assertThat(pool.getObjectCount()).isZero();
        assertThat(pool.getCreationInProgress()).isEqualTo(1);

        pool.close();

        assertThat(acquire.isDone()).isFalse();
        assertThat(pool.getIdle()).isZero();
        assertThat(pool.getObjectCount()).isZero();
        assertThat(pool.getCreationInProgress()).isEqualTo(1);
        verify(factory, never()).destroy("foo");

        progress.complete("foo");

        assertThat(pool.getIdle()).isZero();
        assertThat(pool.getObjectCount()).isZero();
        assertThat(pool.getCreationInProgress()).isZero();

        verify(factory).destroy("foo");
    }

    @Test
    public void objectCreationCanceled() {

        CompletableFuture<String> progress = new CompletableFuture<>();

        when(factory.create()).thenReturn(progress);

        BoundedAsyncPool<String> pool = new BoundedAsyncPool<>(factory, BoundedPoolConfig.create());

        CompletableFuture<String> acquire = pool.acquire();

        acquire.cancel(true);
        progress.complete("foo");

        assertThat(pool.getIdle()).isEqualTo(1);
        assertThat(pool.getObjectCount()).isEqualTo(1);
        assertThat(pool.getCreationInProgress()).isZero();

        verify(factory, never()).destroy(anyString());
    }

    @Test
    public void shouldCreateObjectWithTestOnBorrowFailExceptionally() {

        mockCreation();
        when(factory.validate(any())).thenReturn(failed(new RedisException("foo")));

        BoundedAsyncPool<String> pool = new BoundedAsyncPool<>(factory, BoundedPoolConfig.builder().testOnCreate().build());

        CompletableFuture<String> acquire = pool.acquire();

        assertThat(pool.getIdle()).isZero();
        assertThat(pool.getObjectCount()).isZero();
        assertThat(pool.getCreationInProgress()).isZero();

        assertThat(acquire).isCompletedExceptionally();
    }

    @Test
    public void shouldCreateObjectWithTestOnBorrowSuccess() {

        mockCreation();
        when(factory.validate(any())).thenReturn(CompletableFuture.completedFuture(true));

        BoundedAsyncPool<String> pool = new BoundedAsyncPool<>(factory, BoundedPoolConfig.builder().testOnCreate().build());

        CompletableFuture<String> acquire = pool.acquire();

        assertThat(pool.getIdle()).isZero();
        assertThat(pool.getObjectCount()).isEqualTo(1);
        assertThat(pool.getCreationInProgress()).isZero();

        assertThat(acquire).isCompletedWithValue("1");
    }

    @Test
    public void shouldCreateObjectWithTestOnBorrowFailState() {

        mockCreation();
        when(factory.validate(any())).thenReturn(CompletableFuture.completedFuture(false));

        BoundedAsyncPool<String> pool = new BoundedAsyncPool<>(factory, BoundedPoolConfig.builder().testOnCreate().build());

        CompletableFuture<String> acquire = pool.acquire();

        assertThat(pool.getIdle()).isZero();
        assertThat(pool.getObjectCount()).isZero();
        assertThat(pool.getCreationInProgress()).isZero();

        assertThat(acquire).isCompletedExceptionally();
    }

    @Test
    public void shouldCreateFailedObjectWithTestOnBorrowFail() {

        when(factory.create()).thenReturn(failed(new RedisException("foo")));

        BoundedAsyncPool<String> pool = new BoundedAsyncPool<>(factory, BoundedPoolConfig.builder().testOnCreate().build());

        CompletableFuture<String> acquire = pool.acquire();

        assertThat(pool.getIdle()).isZero();
        assertThat(pool.getObjectCount()).isZero();
        assertThat(pool.getCreationInProgress()).isZero();

        assertThat(acquire).isCompletedExceptionally();
    }

    @Test
    public void shouldTestObjectOnBorrowSuccessfully() {

        mockCreation();
        when(factory.validate(any())).thenReturn(CompletableFuture.completedFuture(true));

        BoundedAsyncPool<String> pool = new BoundedAsyncPool<>(factory, BoundedPoolConfig.builder().testOnAcquire().build());

        pool.release(pool.acquire().join());

        assertThat(pool.getIdle()).isEqualTo(1);
        assertThat(pool.getObjectCount()).isEqualTo(1);
        assertThat(pool.getCreationInProgress()).isZero();

        CompletableFuture<String> acquire = pool.acquire();

        assertThat(acquire).isCompletedWithValue("1");
    }

    @Test
    public void shouldTestObjectOnBorrowFailState() {

        mockCreation();
        when(factory.validate(any())).thenReturn(failed(new RedisException("foo")));

        BoundedAsyncPool<String> pool = new BoundedAsyncPool<>(factory, BoundedPoolConfig.builder().testOnAcquire().build());

        pool.release(pool.acquire().join());

        assertThat(pool.getIdle()).isEqualTo(1);
        assertThat(pool.getObjectCount()).isEqualTo(1);
        assertThat(pool.getCreationInProgress()).isZero();

        CompletableFuture<String> acquire = pool.acquire();

        assertThat(acquire).isCompletedWithValue("2");

        assertThat(pool.getIdle()).isEqualTo(0);
        assertThat(pool.getObjectCount()).isEqualTo(1);
        assertThat(pool.getCreationInProgress()).isZero();
    }

    @Test
    public void shouldTestObjectOnBorrowFailExceptionally() {

        mockCreation();
        when(factory.validate(any())).thenReturn(failed(new RedisException("foo")));

        BoundedAsyncPool<String> pool = new BoundedAsyncPool<>(factory, BoundedPoolConfig.builder().testOnAcquire().build());

        pool.release(pool.acquire().join());

        assertThat(pool.getIdle()).isEqualTo(1);
        assertThat(pool.getObjectCount()).isEqualTo(1);
        assertThat(pool.getCreationInProgress()).isZero();

        CompletableFuture<String> acquire = pool.acquire();

        assertThat(acquire).isCompletedWithValue("2");

        assertThat(pool.getIdle()).isEqualTo(0);
        assertThat(pool.getObjectCount()).isEqualTo(1);
        assertThat(pool.getCreationInProgress()).isZero();
    }

    @Test
    public void shouldTestObjectOnReturnSuccessfully() {

        mockCreation();
        when(factory.validate(any())).thenReturn(CompletableFuture.completedFuture(true));

        BoundedAsyncPool<String> pool = new BoundedAsyncPool<>(factory, BoundedPoolConfig.builder().testOnRelease().build());

        CompletableFuture<Void> release = pool.release(pool.acquire().join());

        assertThat(pool.getIdle()).isEqualTo(1);
        assertThat(pool.getObjectCount()).isEqualTo(1);
        assertThat(pool.getCreationInProgress()).isZero();

        CompletableFuture<String> acquire = pool.acquire();

        assertThat(acquire).isCompletedWithValue("1");
    }

    @Test
    public void shouldTestObjectOnReturnFailState() {

        mockCreation();
        when(factory.validate(any())).thenReturn(failed(new RedisException("foo")));

        BoundedAsyncPool<String> pool = new BoundedAsyncPool<>(factory, BoundedPoolConfig.builder().testOnRelease().build());

        CompletableFuture<Void> release = pool.release(pool.acquire().join());

        assertThat(pool.getIdle()).isZero();
        assertThat(pool.getObjectCount()).isZero();
        assertThat(pool.getCreationInProgress()).isZero();

        assertThat(release).isCompletedWithValue(null);
    }

    @Test
    public void shouldTestObjectOnReturnFailExceptionally() {

        mockCreation();
        when(factory.validate(any())).thenReturn(failed(new RedisException("foo")));

        BoundedAsyncPool<String> pool = new BoundedAsyncPool<>(factory, BoundedPoolConfig.builder().testOnRelease().build());

        CompletableFuture<Void> release = pool.release(pool.acquire().join());

        assertThat(pool.getIdle()).isZero();
        assertThat(pool.getObjectCount()).isZero();
        assertThat(pool.getCreationInProgress()).isZero();

        assertThat(release).isCompletedWithValue(null);
    }

    @Test
    public void shouldRefillIdleObjects() {

        mockCreation();

        BoundedAsyncPool<String> pool = new BoundedAsyncPool<>(factory, BoundedPoolConfig.builder().maxTotal(20).minIdle(5)
                .build());

        assertThat(pool.getIdle()).isEqualTo(5);

        pool.acquire();

        assertThat(pool.getIdle()).isEqualTo(5);
        assertThat(pool.getObjectCount()).isEqualTo(6);

        verify(factory, times(6)).create();
    }

    @Test
    public void shouldDisposeIdleObjects() {

        mockCreation();

        BoundedAsyncPool<String> pool = new BoundedAsyncPool<>(factory, BoundedPoolConfig.builder().maxTotal(20).maxIdle(5)
                .minIdle(5).build());

        assertThat(pool.getIdle()).isEqualTo(5);

        String object = pool.acquire().join();
        pool.release(object);

        assertThat(pool.getIdle()).isEqualTo(5);

        verify(factory).destroy(object);
    }
}
