package com.assessment.service;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.stream.IntStream;

@Component
public class KeyedLockRegistry {

    private static final int STRIPE_COUNT = 256;

    private final List<ReentrantLock> stripes = IntStream.range(0, STRIPE_COUNT)
            .mapToObj(index -> new ReentrantLock())
            .toList();

    public <T> T withLocks(Collection<String> keys, Supplier<T> action) {
        List<ReentrantLock> acquired = keys.stream()
                .map(this::stripeFor)
                .distinct()
                .sorted()
                .map(stripes::get)
                .toList();

        for (ReentrantLock lock : acquired) {
            lock.lock();
        }
        try {
            return action.get();
        } finally {
            for (int index = acquired.size() - 1; index >= 0; index--) {
                acquired.get(index).unlock();
            }
        }
    }

    private int stripeFor(String key) {
        return (key.hashCode() & Integer.MAX_VALUE) % STRIPE_COUNT;
    }
}
