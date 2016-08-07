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
package io.reactivex.internal.operators.observable;

import java.util.concurrent.Callable;

import io.reactivex.*;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.BiConsumer;
import io.reactivex.internal.disposables.*;

public final class ObservableCollect<T, U> extends ObservableWithUpstream<T, U> {
    final Callable<? extends U> initialSupplier;
    final BiConsumer<? super U, ? super T> collector;
    
    public ObservableCollect(ObservableSource<T> source,
            Callable<? extends U> initialSupplier, BiConsumer<? super U, ? super T> collector) {
        super(source);
        this.initialSupplier = initialSupplier;
        this.collector = collector;
    }

    @Override
    protected void subscribeActual(Observer<? super U> t) {
        U u;
        try {
            u = initialSupplier.call();
        } catch (Throwable e) {
            EmptyDisposable.error(e, t);
            return;
        }
        
        if (u == null) {
            EmptyDisposable.error(new NullPointerException("The inital supplier returned a null value"), t);
            return;
        }
        
        source.subscribe(new CollectSubscriber<T, U>(t, u, collector));
        
    }
    
    static final class CollectSubscriber<T, U> implements Observer<T>, Disposable {
        final Observer<? super U> actual;
        final BiConsumer<? super U, ? super T> collector;
        final U u;
        
        Disposable s;
        
        public CollectSubscriber(Observer<? super U> actual, U u, BiConsumer<? super U, ? super T> collector) {
            this.actual = actual;
            this.collector = collector;
            this.u = u;
        }
        
        @Override
        public void onSubscribe(Disposable s) {
            if (DisposableHelper.validate(this.s, s)) {
                this.s = s;
                actual.onSubscribe(this);
            }
        }
        

        @Override
        public void dispose() {
            s.dispose();
        }
        
        @Override
        public boolean isDisposed() {
            return s.isDisposed();
        }

        
        @Override
        public void onNext(T t) {
            try {
                collector.accept(u, t);
            } catch (Throwable e) {
                s.dispose();
                actual.onError(e);
            }
        }
        
        @Override
        public void onError(Throwable t) {
            actual.onError(t);
        }
        
        @Override
        public void onComplete() {
            actual.onNext(u);
            actual.onComplete();
        }
    }
}