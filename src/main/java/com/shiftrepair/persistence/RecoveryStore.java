package com.shiftrepair.persistence;

import java.util.function.Function;

/** Loads current state and publishes the result only after an atomic successful commit. */
public interface RecoveryStore {
    record Change<T>(RecoveryState state, T result) { }
    <T> T transact(Function<RecoveryState, Change<T>> operation);
}
