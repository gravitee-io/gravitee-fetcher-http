/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.fetcher.http;

import io.gravitee.common.http.HttpStatusCode;
import io.gravitee.fetcher.api.Fetcher;
import io.gravitee.fetcher.api.FetcherException;
import io.gravitee.fetcher.http.vertx.VertxCompletableFuture;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.util.concurrent.CompletableFuture;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author Nicolas GERAUD (nicolas <AT> graviteesource.com)
 * @author GraviteeSource Team
 */
public class HttpFetcher implements Fetcher {

    private static final Logger logger = LoggerFactory.getLogger(HttpFetcher.class);

    private static final String HTTPS_SCHEME = "https";

    private HttpFetcherConfiguration httpFetcherConfiguration;

    @Autowired
    private Vertx vertx;

    private static final int GLOBAL_TIMEOUT = 10_000;

    public HttpFetcher(HttpFetcherConfiguration httpFetcherConfiguration) {
        this.httpFetcherConfiguration = httpFetcherConfiguration;
    }

    @Override
    public InputStream fetch() throws FetcherException {
        try {
            Buffer buffer = fetchContent().join();
            if (buffer == null) {
                throw new FetcherException("Unable to fetch Http content '" + httpFetcherConfiguration.getUrl() + "': no content", null);
            }
            return new ByteArrayInputStream(buffer.getBytes());
        } catch (Exception ex) {
            throw new FetcherException("Unable to fetch Http content (" + ex.getMessage() + ")", ex);
        }
    }

    private CompletableFuture<Buffer> fetchContent() {
        CompletableFuture<Buffer> future = new VertxCompletableFuture<>(vertx);

        URI requestUri = URI.create(httpFetcherConfiguration.getUrl());
        boolean ssl = HTTPS_SCHEME.equalsIgnoreCase(requestUri.getScheme());

        final HttpClientOptions options = new HttpClientOptions()
                .setSsl(ssl)
                .setTrustAll(true)
                .setMaxPoolSize(1)
                .setKeepAlive(false)
                .setTcpKeepAlive(false)
                .setConnectTimeout(GLOBAL_TIMEOUT);

        final HttpClient httpClient = vertx.createHttpClient(options);

        final int port = requestUri.getPort() != -1 ? requestUri.getPort() :
                (HTTPS_SCHEME.equals(requestUri.getScheme()) ? 443 : 80);

        try {
            HttpClientRequest request = httpClient.request(
                    HttpMethod.GET,
                    port,
                    requestUri.getHost(),
                    requestUri.toString()
            );

            request.setTimeout(GLOBAL_TIMEOUT);

            request.handler(response -> {
                if (response.statusCode() == HttpStatusCode.OK_200) {
                    response.bodyHandler(buffer -> {
                        future.complete(buffer);

                        // Close client
                        httpClient.close();
                    });
                } else {
                    future.complete(null);
                }
            });

            request.exceptionHandler(event -> {
                try {
                    future.completeExceptionally(event);

                    // Close client
                    httpClient.close();
                } catch (IllegalStateException ise) {
                    // Do not take care about exception when closing client
                }
            });

            request.end();
        } catch (Exception ex) {
            logger.error("Unable to fetch content using HTTP", ex);
            future.completeExceptionally(ex);
        }

        return future;
    }

    public void setVertx(Vertx vertx) {
        this.vertx = vertx;
    }
}
