/*
 * Copyright (C) 2020 ActiveJ LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.activej.async.function;

import io.activej.async.process.AsyncExecutor;
import io.activej.async.process.AsyncExecutors;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

public final class AsyncConsumers {

	@Contract(pure = true)
	public static <T> @NotNull AsyncConsumer<T> buffer(@NotNull AsyncConsumer<T> actual) {
		return buffer(1, Integer.MAX_VALUE, actual);
	}

	@Contract(pure = true)
	public static <T> @NotNull AsyncConsumer<T> buffer(int maxParallelCalls, int maxBufferedCalls, @NotNull AsyncConsumer<T> asyncConsumer) {
		return ofExecutor(AsyncExecutors.buffered(maxParallelCalls, maxBufferedCalls), asyncConsumer);
	}

	@Contract(pure = true)
	public static <T> @NotNull AsyncConsumer<T> ofExecutor(@NotNull AsyncExecutor asyncExecutor, @NotNull AsyncConsumer<T> consumer) {
		return t -> asyncExecutor.execute(() -> consumer.accept(t));
	}
}
