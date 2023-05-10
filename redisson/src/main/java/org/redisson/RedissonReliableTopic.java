/**
 * Copyright (c) 2013-2022 Nikita Koksharov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.redisson;

import io.netty.util.Timeout;
import org.redisson.api.RFuture;
import org.redisson.api.RReliableTopic;
import org.redisson.api.StreamMessageId;
import org.redisson.api.listener.MessageListener;
import org.redisson.client.codec.Codec;
import org.redisson.client.codec.StringCodec;
import org.redisson.client.protocol.RedisCommands;
import org.redisson.codec.CompositeCodec;
import org.redisson.command.CommandAsyncExecutor;
import org.redisson.misc.CompletableFutureWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 *
 * @author Nikita Koksharov
 *
 */
public class RedissonReliableTopic extends RedissonExpirable implements RReliableTopic {

    private static final Logger log = LoggerFactory.getLogger(RedissonReliableTopic.class);

    private static class Entry {

        private final Class<?> type;
        private final MessageListener<?> listener;

        Entry(Class<?> type, MessageListener<?> listener) {
            this.type = type;
            this.listener = listener;
        }

        public Class<?> getType() {
            return type;
        }

        public MessageListener<?> getListener() {
            return listener;
        }
    }

    private final Map<String, Entry> listeners = new ConcurrentHashMap<>();
    private final AtomicReference<String> subscriberId = new AtomicReference<>();
    private volatile RFuture<Map<StreamMessageId, Map<String, Object>>> readFuture;
    private volatile Timeout timeoutTask;

    public RedissonReliableTopic(Codec codec, CommandAsyncExecutor commandExecutor, String name) {
        super(codec, commandExecutor, name);
    }

    public RedissonReliableTopic(CommandAsyncExecutor commandExecutor, String name) {
        super(commandExecutor, name);
    }

    private String getTimeout() {
        return suffixName(getRawName(), "timeout");
    }

    @Override
    public long publish(Object message) {
        return get(publishAsync(message));
    }

    @Override
    public <M> String addListener(Class<M> type, MessageListener<M> listener) {
        return get(addListenerAsync(type, listener));
    }

    @Override
    public void removeListener(String... listenerIds) {
        get(removeListenerAsync(listenerIds));
    }

    @Override
    public void removeAllListeners() {
        get(removeAllListenersAsync());
    }

    public RFuture<Void> removeAllListenersAsync() {
        listeners.clear();
        return removeSubscriber();
    }

    @Override
    public long size() {
        return get(sizeAsync());
    }

    public RFuture<Long> sizeAsync() {
        return commandExecutor.readAsync(getRawName(), StringCodec.INSTANCE, RedisCommands.XLEN, getRawName());
    }

    @Override
    public int countListeners() {
        return listeners.size();
    }

    @Override
    public RFuture<Long> publishAsync(Object message) {
        return commandExecutor.evalWriteAsync(getRawName(), StringCodec.INSTANCE, RedisCommands.EVAL_LONG,
                "redis.call('xadd', KEYS[1], '*', 'm', ARGV[1]); "
                    + "local v = redis.call('xinfo', 'groups', KEYS[1]); "
                    + "return #v;",
                Arrays.asList(getRawName()),
                encode(message));
    }

    @Override
    public <M> RFuture<String> addListenerAsync(Class<M> type, MessageListener<M> listener) {
        String id = getServiceManager().generateId();
        listeners.put(id, new Entry(type, listener));

        if (subscriberId.get() != null) {
            return new CompletableFutureWrapper<>(id);
        }

        if (subscriberId.compareAndSet(null, id)) {
            renewExpiration();

            RFuture<Void> addFuture = commandExecutor.evalWriteNoRetryAsync(getRawName(), StringCodec.INSTANCE, RedisCommands.EVAL_VOID,
                              "redis.call('zadd', KEYS[2], ARGV[3], ARGV[2]);" +
                                    "redis.call('xgroup', 'create', KEYS[1], ARGV[2], ARGV[1], 'MKSTREAM'); ",
                    Arrays.asList(getRawName(), getTimeout()),
            StreamMessageId.ALL, id, System.currentTimeMillis() + getServiceManager().getCfg().getReliableTopicWatchdogTimeout());
            CompletionStage<String> f = addFuture.thenApply(r -> {
                poll(id);
                return id;
            });

            return new CompletableFutureWrapper<>(f);
        }

        return new CompletableFutureWrapper<>(id);
    }

    private void poll(String id) {
        readFuture = commandExecutor.readAsync(getRawName(), new CompositeCodec(StringCodec.INSTANCE, codec),
                RedisCommands.XREADGROUP_BLOCKING_SINGLE, "GROUP", id, "consumer", "BLOCK", 0, "STREAMS", getRawName(), ">");
        readFuture.whenComplete((res, ex) -> {
            if (readFuture.isCancelled()) {
                return;
            }
            if (ex != null) {
                if (ex instanceof RedissonShutdownException) {
                    return;
                }

                log.error(ex.getMessage(), ex);

                getServiceManager().newTimeout(task -> {
                    poll(id);
                }, 1, TimeUnit.SECONDS);
                return;
            }

            if (listeners.isEmpty()) {
                return;
            }

            getServiceManager().getExecutor().execute(() -> {
                res.values().forEach(entry -> {
                    Object m = entry.get("m");
                    listeners.values().forEach(e -> {
                        if (e.getType().isInstance(m)) {
                            ((MessageListener<Object>) e.getListener()).onMessage(getRawName(), m);
                        }
                    });
                });
            });

            long time = System.currentTimeMillis();
            RFuture<Boolean> updateFuture = commandExecutor.evalWriteAsync(getRawName(), StringCodec.INSTANCE, RedisCommands.EVAL_BOOLEAN,
                            "local expired = redis.call('zrangebyscore', KEYS[2], 0, tonumber(ARGV[2]) - 1); "
                            + "for i, v in ipairs(expired) do "
                                + "redis.call('xgroup', 'destroy', KEYS[1], v); "
                            + "end; "
                            + "local r = redis.call('zscore', KEYS[2], ARGV[1]); "

                            + "local score = 92233720368547758;"
                            + "local groups = redis.call('xinfo', 'groups', KEYS[1]); " +
                              "for i, v in ipairs(groups) do "
                                 + "local id1, id2 = string.match(v[8], '(.*)%-(.*)'); "
                                 + "score = math.min(tonumber(id1), score); "
                            + "end; " +
                              "score = tostring(score) .. '-0';"
                            + "local range = redis.call('xrange', KEYS[1], score, '+'); "
                            + "if #range == 0 or (#range == 1 and range[1][1] == score) then "
                                + "redis.call('xtrim', KEYS[1], 'maxlen', 0); "
                            + "else "
                                + "redis.call('xtrim', KEYS[1], 'maxlen', #range); "
                            + "end;"
                            + "return r ~= false; ",
                    Arrays.asList(getRawName(), getTimeout()),
                    id, time);
            updateFuture.whenComplete((re, exc) -> {
                if (exc != null) {
                    if (exc instanceof RedissonShutdownException) {
                        return;
                    }
                    log.error("Unable to update subscriber status", exc);
                    return;
                }

                if (!re || listeners.isEmpty()) {
                    return;
                }

                poll(id);
            });

        });
    }

    @Override
    public RFuture<Boolean> deleteAsync() {
        return deleteAsync(getRawName(), getTimeout());
    }

    @Override
    public RFuture<Long> sizeInMemoryAsync() {
        return super.sizeInMemoryAsync(Arrays.asList(getRawName(), getTimeout()));
    }

    @Override
    public RFuture<Boolean> expireAsync(long timeToLive, TimeUnit timeUnit, String param, String... keys) {
        return super.expireAsync(timeToLive, timeUnit, param, getRawName(), getTimeout());
    }

    @Override
    protected RFuture<Boolean> expireAtAsync(long timestamp, String param, String... keys) {
        return super.expireAtAsync(timestamp, param, getRawName(), getTimeout());
    }

    @Override
    public RFuture<Boolean> clearExpireAsync() {
        return clearExpireAsync(getRawName(), getTimeout());
    }

    @Override
    public RFuture<Void> removeListenerAsync(String... listenerIds) {
        listeners.keySet().removeAll(Arrays.asList(listenerIds));

        if (listeners.isEmpty()) {
            return removeSubscriber();
        }
        return new CompletableFutureWrapper<>((Void) null);
    }

    private RFuture<Void> removeSubscriber() {
        readFuture.cancel(false);
        timeoutTask.cancel();

        String id = subscriberId.getAndSet(null);
        return commandExecutor.evalWriteAsync(getRawName(), StringCodec.INSTANCE, RedisCommands.EVAL_VOID,
                "redis.call('xgroup', 'destroy', KEYS[1], ARGV[1]); "
                      + "redis.call('zrem', KEYS[2], ARGV[1]); ",
                Arrays.asList(getRawName(), getTimeout()),
                id);
    }

    @Override
    public int countSubscribers() {
        return get(countSubscribersAsync());
    }

    @Override
    public RFuture<Integer> countSubscribersAsync() {
        return commandExecutor.evalReadAsync(getRawName(), StringCodec.INSTANCE, RedisCommands.EVAL_INTEGER,
                        "local v = redis.call('xinfo', 'groups', KEYS[1]); " +
                              "return #v;",
                Arrays.asList(getRawName()));
    }

    private void renewExpiration() {
        timeoutTask = getServiceManager().newTimeout(t -> {
            RFuture<Boolean> future = commandExecutor.evalWriteAsync(getRawName(), StringCodec.INSTANCE, RedisCommands.EVAL_BOOLEAN,
                  "if redis.call('zscore', KEYS[1], ARGV[2]) == false then "
                         + "return 0; "
                      + "end; "
                      + "redis.call('zadd', KEYS[1], ARGV[1], ARGV[2]); "
                      + "return 1; ",
                Arrays.asList(getTimeout()),
                System.currentTimeMillis() + getServiceManager().getCfg().getReliableTopicWatchdogTimeout(), subscriberId.get());
            future.whenComplete((res, e) -> {
                if (e != null) {
                    log.error("Can't update reliable topic {} expiration time", getRawName(), e);
                    return;
                }

                if (res) {
                    // reschedule itself
                    renewExpiration();
                }
            });
        }, getServiceManager().getCfg().getReliableTopicWatchdogTimeout() / 3, TimeUnit.MILLISECONDS);
    }


}
