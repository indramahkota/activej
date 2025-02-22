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

package io.activej.fs.http;

import io.activej.bytebuf.ByteBuf;
import io.activej.common.Checks;
import io.activej.common.Utils;
import io.activej.common.exception.MalformedDataException;
import io.activej.csp.consumer.ChannelConsumer;
import io.activej.csp.process.transformer.ChannelConsumerTransformer;
import io.activej.csp.queue.ChannelZeroBuffer;
import io.activej.csp.supplier.ChannelSupplier;
import io.activej.csp.supplier.ChannelSuppliers;
import io.activej.fs.FileMetadata;
import io.activej.fs.IFileSystem;
import io.activej.fs.util.RemoteFileSystemUtils;
import io.activej.http.*;
import io.activej.promise.Promise;
import io.activej.promise.SettablePromise;
import io.activej.reactor.AbstractReactive;
import io.activej.reactor.Reactive;
import io.activej.reactor.Reactor;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;

import static io.activej.fs.http.FileSystemCommand.*;
import static io.activej.fs.json.JsonCodecs.*;
import static io.activej.json.JsonCodecs.*;
import static io.activej.json.JsonUtils.fromJsonBytes;
import static io.activej.json.JsonUtils.toJsonBytes;

/**
 * A client to the remote server with {@link FileSystemServlet}.
 * This client can be used to connect to publicly available servers.
 * <p>
 * Inherits all the limitations of {@link IFileSystem} implementation located on server.
 */
public final class HttpClientFileSystem extends AbstractReactive
	implements IFileSystem {

	private final IHttpClient client;
	private final String url;

	private HttpClientFileSystem(Reactor reactor, String url, IHttpClient client) {
		super(reactor);
		this.url = url;
		this.client = client;
	}

	public static HttpClientFileSystem create(Reactor reactor, String url, IHttpClient client) {
		return new HttpClientFileSystem(reactor, url.endsWith("/") ? url : url + '/', client);
	}

	@Override
	public Promise<ChannelConsumer<ByteBuf>> upload(String name) {
		Reactive.checkInReactorThread(this);
		return doUpload(name, null);
	}

	@Override
	public Promise<ChannelConsumer<ByteBuf>> upload(String name, long size) {
		Reactive.checkInReactorThread(this);
		return doUpload(name, size);
	}

	@Override
	public Promise<ChannelConsumer<ByteBuf>> append(String name, long offset) {
		Reactive.checkInReactorThread(this);
		Checks.checkArgument(offset >= 0, "Offset cannot be less than 0");
		UrlBuilder urlBuilder = UrlBuilder.create()
			.withPath(APPEND.path())
			.withPath(name.split("/"));
		if (offset != 0) {
			urlBuilder.withQuery("offset", offset);
		}
		return uploadData(HttpRequest.post(url + urlBuilder), ChannelConsumerTransformer.identity());
	}

	@Override
	public Promise<ChannelSupplier<ByteBuf>> download(String name, long offset, long limit) {
		Reactive.checkInReactorThread(this);
		Checks.checkArgument(offset >= 0 && limit >= 0);
		UrlBuilder urlBuilder = UrlBuilder.create()
			.withPath(DOWNLOAD.path())
			.withPath(name.split("/"));
		if (offset != 0) {
			urlBuilder.withQuery("offset", offset);
		}
		if (limit != Long.MAX_VALUE) {
			urlBuilder.withQuery("limit", limit);
		}
		return client.request(
				HttpRequest.get(url + urlBuilder).build())
			.then(HttpClientFileSystem::checkResponse)
			.map(HttpMessage::takeBodyStream);
	}

	@Override
	public Promise<Map<String, FileMetadata>> list(String glob) {
		Reactive.checkInReactorThread(this);
		return client.request(
				HttpRequest.get(url + UrlBuilder.create()
						.withPath(LIST.path())
						.withQuery("glob", glob))
					.build())
			.then(HttpClientFileSystem::checkResponse)
			.then(response -> response.loadBody())
			.map(body -> fromJsonBytes(ofMap(ofFileMetadata()), body.getArray()));
	}

	@Override
	public Promise<@Nullable FileMetadata> info(String name) {
		Reactive.checkInReactorThread(this);
		return client.request(
				HttpRequest.get(url + UrlBuilder.create().withPath(INFO.path(), name)).build())
			.then(HttpClientFileSystem::checkResponse)
			.then(response -> response.loadBody())
			.map(body -> fromJsonBytes(ofFileMetadata().nullable(), body.getArray()));
	}

	@Override
	public Promise<Map<String, FileMetadata>> infoAll(Set<String> names) {
		Reactive.checkInReactorThread(this);
		return client.request(
				HttpRequest.get(url + UrlBuilder.create()
						.withPath(INFO_ALL.path()))
					.withBody(toJsonBytes(ofSet(ofString()), names))
					.build())
			.then(HttpClientFileSystem::checkResponse)
			.then(response -> response.loadBody())
			.map(body -> fromJsonBytes(ofMap(ofFileMetadata()), body.getArray()));
	}

	@Override
	public Promise<Void> ping() {
		Reactive.checkInReactorThread(this);
		return client.request(
				HttpRequest.get(url + UrlBuilder.create().withPath(PING.path())).build())
			.then(HttpClientFileSystem::checkResponse)
			.toVoid();
	}

	@Override
	public Promise<Void> move(String name, String target) {
		Reactive.checkInReactorThread(this);
		return client.request(
				HttpRequest.post(url + UrlBuilder.create()
						.withPath(MOVE.path())
						.withQuery("name", name)
						.withQuery("target", target))
					.build())
			.then(HttpClientFileSystem::checkResponse)
			.toVoid();
	}

	@Override
	public Promise<Void> moveAll(Map<String, String> sourceToTarget) {
		Reactive.checkInReactorThread(this);
		Checks.checkArgument(Utils.isBijection(sourceToTarget), "Targets must be unique");
		if (sourceToTarget.isEmpty()) return Promise.complete();

		return client.request(
				HttpRequest.post(url + UrlBuilder.create()
						.withPath(MOVE_ALL.path()))
					.withBody(toJsonBytes(ofMap(ofString()), sourceToTarget))
					.build())
			.then(HttpClientFileSystem::checkResponse)
			.toVoid();
	}

	@Override
	public Promise<Void> copy(String name, String target) {
		Reactive.checkInReactorThread(this);
		return client.request(
				HttpRequest.post(url + UrlBuilder.create()
						.withPath(COPY.path())
						.withQuery("name", name)
						.withQuery("target", target))
					.build())
			.then(HttpClientFileSystem::checkResponse)
			.toVoid();
	}

	@Override
	public Promise<Void> copyAll(Map<String, String> sourceToTarget) {
		Reactive.checkInReactorThread(this);
		Checks.checkArgument(Utils.isBijection(sourceToTarget), "Targets must be unique");
		if (sourceToTarget.isEmpty()) return Promise.complete();

		return client.request(
				HttpRequest.post(url + UrlBuilder.create()
						.withPath(COPY_ALL.path()))
					.withBody(toJsonBytes(ofMap(ofString()), sourceToTarget))
					.build())
			.then(HttpClientFileSystem::checkResponse)
			.toVoid();
	}

	@Override
	public Promise<Void> delete(String name) {
		Reactive.checkInReactorThread(this);
		return client.request(
				HttpRequest.builder(HttpMethod.DELETE,
						url + UrlBuilder.create()
							.withPath(DELETE.path())
							.withPath(name.split("/")))
					.build())
			.then(HttpClientFileSystem::checkResponse)
			.toVoid();
	}

	@Override
	public Promise<Void> deleteAll(Set<String> toDelete) {
		Reactive.checkInReactorThread(this);
		return client.request(
				HttpRequest.post(url + UrlBuilder.create().withPath(DELETE_ALL.path()))
					.withBody(toJsonBytes(ofSet(ofString()), toDelete))
					.build())
			.then(HttpClientFileSystem::checkResponse)
			.toVoid();
	}

	private static Promise<HttpResponse> checkResponse(HttpResponse response) throws HttpError {
		return switch (response.getCode()) {
			case 200, 206 -> Promise.of(response);
			case 500 -> response.loadBody()
				.map(body -> {
					try {
						throw fromJsonBytes(ofFileSystemException(), body.getArray());
					} catch (MalformedDataException ignored) {
						throw HttpError.ofCode(500);
					}
				});
			default -> throw HttpError.ofCode(response.getCode());
		};
	}

	private Promise<ChannelConsumer<ByteBuf>> doUpload(String filename, @Nullable Long size) {
		HttpRequest.Builder builder = HttpRequest.post(
			url + UrlBuilder.create()
				.withPath(UPLOAD.path())
				.withPath(filename.split("/")));

		if (size != null) {
			builder.withHeader(HttpHeaders.CONTENT_LENGTH, String.valueOf(size));
		}

		return uploadData(builder, size == null ? ChannelConsumerTransformer.identity() : RemoteFileSystemUtils.ofFixedSize(size));
	}

	private Promise<ChannelConsumer<ByteBuf>> uploadData(HttpRequest.Builder builder, ChannelConsumerTransformer<ByteBuf, ChannelConsumer<ByteBuf>> transformer) {
		SettablePromise<ChannelConsumer<ByteBuf>> channelPromise = new SettablePromise<>();
		SettablePromise<HttpResponse> responsePromise = new SettablePromise<>();
		client.request(builder
				.withBodyStream(ChannelSuppliers.ofPromise(responsePromise
					.map(response -> {
						ChannelZeroBuffer<ByteBuf> buffer = new ChannelZeroBuffer<>();
						ChannelConsumer<ByteBuf> consumer = buffer.getConsumer();
						channelPromise.trySet(consumer
							.transformWith(transformer)
							.withAcknowledgement(ack -> ack.both(response.loadBody()
								.map(body -> fromJsonBytes(ofUploadAcknowledgement(), body.getArray()))
								.whenResult(uploadAck -> {
									if (!uploadAck.isOk()) throw uploadAck.getError();
								})
								.whenException(e -> {
									channelPromise.trySetException(e);
									buffer.closeEx(e);
								}))));
						return buffer.getSupplier();
					})))
				.build())
			.then(HttpClientFileSystem::checkResponse)
			.whenException(channelPromise::trySetException)
			.whenComplete(responsePromise::trySet);

		return channelPromise;
	}

}
