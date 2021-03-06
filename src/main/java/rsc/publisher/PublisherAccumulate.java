package rsc.publisher;

import java.util.Objects;
import java.util.function.BiFunction;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import rsc.documentation.Operator;
import rsc.documentation.OperatorType;
import rsc.flow.Loopback;
import rsc.flow.Producer;
import rsc.flow.Receiver;
import rsc.state.Completable;
import rsc.util.ExceptionHelper;
import rsc.util.SubscriptionHelper;
import rsc.util.UnsignalledExceptions;

/**
 * Accumulates the source values with an accumulator function and
 * returns the intermediate results of this function.
 * <p>
 * Unlike {@link PublisherScan}, this operator doesn't take an initial value
 * but treats the first source value as initial value.
 * <br>
 * The accumulation works as follows:
 * <pre><code>
 * result[0] = accumulator(source[0], source[1])
 * result[1] = accumulator(result[0], source[2])
 * result[2] = accumulator(result[1], source[3])
 * ...
 * </code></pre>
 *
 * @param <T> the input and accumulated value type
 */
@Operator(traits = OperatorType.TRANSFORMATION, aliases = {"accumulate", "scan"})
public final class PublisherAccumulate<T> extends PublisherSource<T, T> {

    final BiFunction<T, ? super T, T> accumulator;

    public PublisherAccumulate(Publisher<? extends T> source, BiFunction<T, ? super T, T> accumulator) {
        super(source);
        this.accumulator = Objects.requireNonNull(accumulator, "accumulator");
    }

    @Override
    public void subscribe(Subscriber<? super T> s) {
        source.subscribe(new PublisherAccumulateSubscriber<>(s, accumulator));
    }

    static final class PublisherAccumulateSubscriber<T> implements Subscriber<T>, Receiver, Producer, Completable,
                                                                   Loopback, Subscription {
        final Subscriber<? super T> actual;

        final BiFunction<T, ? super T, T> accumulator;

        Subscription s;

        T value;

        boolean done;

        public PublisherAccumulateSubscriber(Subscriber<? super T> actual, BiFunction<T, ? super T, T> accumulator) {
            this.actual = actual;
            this.accumulator = accumulator;
        }

        @Override
        public void onSubscribe(Subscription s) {
            if (SubscriptionHelper.validate(this.s, s)) {
                this.s = s;

                actual.onSubscribe(this);
            }
        }

        @Override
        public void onNext(T t) {
            if (done) {
                UnsignalledExceptions.onNextDropped(t);
                return;
            }

            T v = value;

            if (v != null) {
                try {
                    t = accumulator.apply(v, t);
                } catch (Throwable e) {
                    s.cancel();
                    ExceptionHelper.throwIfFatal(e);
                    onError(ExceptionHelper.unwrap(e));
                    return;
                }
                if (t == null) {
                    s.cancel();

                    onError(new NullPointerException("The accumulator returned a null value"));
                    return;
                }
            }
            value = t;
            actual.onNext(t);
        }

        @Override
        public void onError(Throwable t) {
            if (done) {
                UnsignalledExceptions.onErrorDropped(t);
                return;
            }
            done = true;
            actual.onError(t);
        }

        @Override
        public void onComplete() {
            if (done) {
                return;
            }
            done = true;
            actual.onComplete();
        }

        @Override
        public boolean isStarted() {
            return s != null && !done;
        }

        @Override
        public boolean isTerminated() {
            return done;
        }

        @Override
        public Object downstream() {
            return actual;
        }

        @Override
        public Object connectedInput() {
            return accumulator;
        }

        @Override
        public Object connectedOutput() {
            return value;
        }

        @Override
        public Object upstream() {
            return s;
        }
        
        @Override
        public void request(long n) {
            s.request(n);
        }
        
        @Override
        public void cancel() {
            s.cancel();
        }
    }
}
