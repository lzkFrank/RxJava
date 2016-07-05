/**
 * Copyright 2016 Netflix, Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See
 * the License for the specific language governing permissions and limitations under the License.
 */

package io.reactivex.internal.operators.flowable;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.*;

import org.reactivestreams.*;

import io.reactivex.exceptions.MissingBackpressureException;
import io.reactivex.functions.Function;
import io.reactivex.internal.functions.Objects;
import io.reactivex.internal.fuseable.QueueSubscription;
import io.reactivex.internal.queue.SpscArrayQueue;
import io.reactivex.internal.subscriptions.*;
import io.reactivex.internal.util.*;
import io.reactivex.plugins.RxJavaPlugins;

public final class FlowableFlattenIterable<T, R> extends FlowableSource<T, R> {

    final Function<? super T, ? extends Iterable<? extends R>> mapper;

    final int prefetch;

    public FlowableFlattenIterable(Publisher<T> source,
            Function<? super T, ? extends Iterable<? extends R>> mapper, int prefetch) {
        super(source);
        if (prefetch <= 0) {
            throw new IllegalArgumentException("prefetch > 0 required but it was " + prefetch);
        }
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.prefetch = prefetch;
    }

    @SuppressWarnings("unchecked")
    @Override
    public void subscribeActual(Subscriber<? super R> s) {
        if (source instanceof Callable) {
            T v;

            try {
                v = ((Callable<T>)source).call();
            } catch (Throwable ex) {
                Exceptions.throwIfFatal(ex);
                EmptySubscription.error(ex, s);
                return;
            }

            if (v == null) {
                EmptySubscription.complete(s);
                return;
            }

            Iterator<? extends R> it;

            try {
                Iterable<? extends R> iter = mapper.apply(v);

                it = iter.iterator();
            } catch (Throwable ex) {
                Exceptions.throwIfFatal(ex);
                EmptySubscription.error(ex, s);
                return;
            }

            FlowableFromIterable.subscribe(s, it);

            return;
        }
        source.subscribe(new FlattenIterableSubscriber<T, R>(s, mapper, prefetch));
    }

    static final class FlattenIterableSubscriber<T, R>
    extends BasicIntQueueSubscription<R>
    implements Subscriber<T> {

        /** */
        private static final long serialVersionUID = -3096000382929934955L;

        final Subscriber<? super R> actual;

        final Function<? super T, ? extends Iterable<? extends R>> mapper;

        final int prefetch;

        final int limit;

        final AtomicLong requested;

        Subscription s;

        Queue<T> queue;

        volatile boolean done;

        volatile boolean cancelled;

        final AtomicReference<Throwable> error;

        Iterator<? extends R> current;

        int consumed;

        int fusionMode;

        public FlattenIterableSubscriber(Subscriber<? super R> actual,
                Function<? super T, ? extends Iterable<? extends R>> mapper, int prefetch) {
            this.actual = actual;
            this.mapper = mapper;
            this.prefetch = prefetch;
            this.limit = prefetch - (prefetch >> 2);
            this.error = new AtomicReference<Throwable>();
            this.requested = new AtomicLong();
        }

        @Override
        public void onSubscribe(Subscription s) {
            if (SubscriptionHelper.validate(this.s, s)) {
                this.s = s;

                if (s instanceof QueueSubscription) {
                    @SuppressWarnings("unchecked")
                    QueueSubscription<T> qs = (QueueSubscription<T>) s;

                    int m = qs.requestFusion(ANY);

                    if (m == SYNC) {
                        fusionMode = m;
                        this.queue = qs;
                        done = true;

                        actual.onSubscribe(this);

                        return;
                    }
                    if (m == ASYNC) {
                        fusionMode = m;
                        this.queue = qs;

                        actual.onSubscribe(this);

                        s.request(prefetch);
                        return;
                    }
                }

                queue = new SpscArrayQueue<T>(prefetch);

                actual.onSubscribe(this);

                s.request(prefetch);
            }
        }

        @Override
        public void onNext(T t) {
            if (fusionMode != ASYNC && !queue.offer(t)) {
                onError(new MissingBackpressureException("Queue is full?!"));
                return;
            }
            drain();
        }

        @Override
        public void onError(Throwable t) {
            if (Exceptions.addThrowable(error, t)) {
                done = true;
                drain();
            } else {
                RxJavaPlugins.onError(t);
            }
        }

        @Override
        public void onComplete() {
            done = true;
            drain();
        }

        @Override
        public void request(long n) {
            if (SubscriptionHelper.validate(n)) {
                BackpressureHelper.add(requested, n);
                drain();
            }
        }

        @Override
        public void cancel() {
            if (!cancelled) {
                cancelled = true;

                if (getAndIncrement() == 0) {
                    queue.clear();
                }
            }
        }

        void drain() {
            if (getAndIncrement() != 0) {
                return;
            }

            final Subscriber<? super R> a = actual;
            final Queue<T> q = queue;
            final boolean replenish = fusionMode != SYNC;

            int missed = 1;

            Iterator<? extends R> it = current;

            for (;;) {

                if (it == null) {

                    boolean d = done;

                    T t;

                    t = q.poll();

                    boolean empty = t == null;

                    if (checkTerminated(d, empty, a, q)) {
                        return;
                    }

                    if (t != null) {
                        Iterable<? extends R> iterable;

                        boolean b;

                        try {
                            iterable = mapper.apply(t);

                            it = iterable.iterator();

                            b = it.hasNext();
                        } catch (Throwable ex) {
                            Exceptions.throwIfFatal(ex);
                            onError(ex);
                            it = null;
                            continue;
                        }

                        if (!b) {
                            it = null;
                            consumedOne(replenish);
                            continue;
                        }

                        current = it;
                    }
                }

                if (it != null) {
                    long r = requested.get();
                    long e = 0L;

                    while (e != r) {
                        if (checkTerminated(done, false, a, q)) {
                            return;
                        }

                        R v;

                        try {
                            v = it.next();
                        } catch (Throwable ex) {
                            Exceptions.throwIfFatal(ex);
                            onError(ex);
                            continue;
                        }

                        a.onNext(v);

                        if (checkTerminated(done, false, a, q)) {
                            return;
                        }

                        e++;

                        boolean b;

                        try {
                            b = it.hasNext();
                        } catch (Throwable ex) {
                            Exceptions.throwIfFatal(ex);
                            onError(ex);
                            continue;
                        }

                        if (!b) {
                            consumedOne(replenish);
                            it = null;
                            current = null;
                            break;
                        }
                    }

                    if (e == r) {
                        boolean d = done;
                        boolean empty;

                        try {
                            empty = q.isEmpty() && it == null;
                        } catch (Throwable ex) {
                            Exceptions.throwIfFatal(ex);
                            onError(ex);
                            empty = true;
                        }

                        if (checkTerminated(d, empty, a, q)) {
                            return;
                        }
                    }

                    if (e != 0L) {
                        if (r != Long.MAX_VALUE) {
                            requested.addAndGet(-e);
                        }
                    }

                    if (it == null) {
                        continue;
                    }
                }

                missed = addAndGet(-missed);
                if (missed == 0) {
                    break;
                }
            }
        }

        void consumedOne(boolean enabled) {
            if (enabled) {
                int c = consumed + 1;
                if (c == limit) {
                    consumed = 0;
                    s.request(c);
                } else {
                    consumed = c;
                }
            }
        }

        boolean checkTerminated(boolean d, boolean empty, Subscriber<?> a, Queue<?> q) {
            if (cancelled) {
                current = null;
                q.clear();
                return true;
            }
            if (d) {
                Throwable ex = error.get();
                if (ex != null) {
                    ex = Exceptions.terminate(error);

                    current = null;
                    q.clear();

                    a.onError(ex);
                    return true;
                } else
                    if (empty) {
                        a.onComplete();
                        return true;
                    }
            }
            return false;
        }

        @Override
        public void clear() {
            current = null;
            queue.clear();
        }

        @Override
        public boolean isEmpty() {
            Iterator<? extends R> it = current;
            if (it != null) {
                return it.hasNext();
            }
            return queue.isEmpty(); // estimate
        }

        @Override
        public R poll() {
            Iterator<? extends R> it = current;
            for (;;) {
                if (it == null) {
                    T v = queue.poll();
                    if (v == null) {
                        return null;
                    }

                    it = mapper.apply(v).iterator();

                    if (!it.hasNext()) {
                        continue;
                    }
                    current = it;
                }

                R r = it.next();

                if (!it.hasNext()) {
                    current = null;
                }

                return r;
            }
        }

        @Override
        public int requestFusion(int requestedMode) {
            if ((requestedMode & SYNC) != 0 && fusionMode == SYNC) {
                return SYNC;
            }
            return NONE;
        }
    }
}