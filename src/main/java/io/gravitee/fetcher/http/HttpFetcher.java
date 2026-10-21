/*
 * Copyright © 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.fetcher.http;

import io.gravitee.common.http.HttpHeaders;
import io.gravitee.common.http.HttpStatusCode;
import io.gravitee.common.utils.UUID;
import io.gravitee.fetcher.api.Fetcher;
import io.gravitee.fetcher.api.FetcherConfiguration;
import io.gravitee.fetcher.api.FetcherException;
import io.gravitee.fetcher.api.Resource;
import io.gravitee.node.api.Node;
import io.gravitee.node.api.utils.NodeUtils;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.*;
import io.vertx.core.net.ProxyOptions;
import io.vertx.core.net.ProxyType;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import lombok.CustomLog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.support.CronExpression;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author Nicolas GERAUD (nicolas.geraud at graviteesource.com)
 * @author GraviteeSource Team
 */
@CustomLog
public class HttpFetcher implements Fetcher {

    private static final String HTTPS_SCHEME = "https";

    /**
     * Vert.x holds on to every client it creates until the Vert.x instance itself is closed, so a client per fetch
     * would accumulate for as long as the node runs. A fetcher is instantiated per fetch, hence a static cache keyed
     * by everything that shapes the client options.
     */
    private static final Map<ClientKey, HttpClient> CLIENTS = new ConcurrentHashMap<>();

    // Shared by every fetch of the node: a single connection would serialise them.
    private static final int MAX_CONNECTIONS = 20;

    private final HttpFetcherConfiguration httpFetcherConfiguration;

    @Value("${httpClient.timeout:10000}")
    private int httpClientTimeout;

    @Value("${httpClient.proxy.type:HTTP}")
    private String httpClientProxyType;

    @Value("${httpClient.proxy.http.host:#{systemProperties['http.proxyHost'] ?: 'localhost'}}")
    private String httpClientProxyHttpHost;

    @Value("${httpClient.proxy.http.port:#{systemProperties['http.proxyPort'] ?: 3128}}")
    private int httpClientProxyHttpPort;

    @Value("${httpClient.proxy.http.username:#{null}}")
    private String httpClientProxyHttpUsername;

    @Value("${httpClient.proxy.http.password:#{null}}")
    private String httpClientProxyHttpPassword;

    @Value("${httpClient.proxy.https.host:#{systemProperties['https.proxyHost'] ?: 'localhost'}}")
    private String httpClientProxyHttpsHost;

    @Value("${httpClient.proxy.https.port:#{systemProperties['https.proxyPort'] ?: 3128}}")
    private int httpClientProxyHttpsPort;

    @Value("${httpClient.proxy.https.username:#{null}}")
    private String httpClientProxyHttpsUsername;

    @Value("${httpClient.proxy.https.password:#{null}}")
    private String httpClientProxyHttpsPassword;

    @Autowired
    private Vertx vertx;

    @Autowired
    private Node node;

    public HttpFetcher(HttpFetcherConfiguration httpFetcherConfiguration) {
        this.httpFetcherConfiguration = httpFetcherConfiguration;
    }

    @Override
    public Resource fetch() throws FetcherException {
        if (
            httpFetcherConfiguration.isAutoFetch() &&
            (httpFetcherConfiguration.getFetchCron() == null || httpFetcherConfiguration.getFetchCron().isEmpty())
        ) {
            throw new FetcherException("Some required configuration attributes are missing.", null);
        }

        if (httpFetcherConfiguration.isAutoFetch() && httpFetcherConfiguration.getFetchCron() != null) {
            try {
                CronExpression.parse(httpFetcherConfiguration.getFetchCron());
            } catch (IllegalArgumentException e) {
                throw new FetcherException("Cron expression is invalid", e);
            }
        }

        try {
            Buffer buffer = fetchContent().get();
            if (buffer == null) {
                throw new FetcherException("Unable to fetch Http content '" + httpFetcherConfiguration.getUrl() + "': no content", null);
            }
            final Resource resource = new Resource();
            resource.setContent(new ByteArrayInputStream(buffer.getBytes()));
            return resource;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.error("Fetch of Http content '{}' has been interrupted", httpFetcherConfiguration.getUrl(), ie);
            throw new FetcherException("Unable to fetch Http content (" + ie.getMessage() + ")", ie);
        } catch (Exception ex) {
            Throwable cause = ex instanceof ExecutionException && ex.getCause() != null ? ex.getCause() : ex;
            log.error(cause.getMessage(), cause);
            throw new FetcherException("Unable to fetch Http content (" + cause.getMessage() + ")", cause);
        }
    }

    @Override
    public FetcherConfiguration getConfiguration() {
        return httpFetcherConfiguration;
    }

    private CompletableFuture<Buffer> fetchContent() {
        Promise<Buffer> promise = Promise.promise();

        URI requestUri = URI.create(httpFetcherConfiguration.getUrl());
        boolean ssl = HTTPS_SCHEME.equalsIgnoreCase(requestUri.getScheme());

        Proxy proxy = null;
        if (httpFetcherConfiguration.isUseSystemProxy()) {
            proxy = HTTPS_SCHEME.equals(requestUri.getScheme())
                ? new Proxy(
                    httpClientProxyType,
                    httpClientProxyHttpsHost,
                    httpClientProxyHttpsPort,
                    httpClientProxyHttpsUsername,
                    httpClientProxyHttpsPassword
                )
                : new Proxy(
                    httpClientProxyType,
                    httpClientProxyHttpHost,
                    httpClientProxyHttpPort,
                    httpClientProxyHttpUsername,
                    httpClientProxyHttpPassword
                );
        }

        final HttpClient httpClient = CLIENTS.computeIfAbsent(
            new ClientKey(vertx, ssl, httpClientTimeout, proxy),
            HttpFetcher::buildHttpClient
        );

        final int port = requestUri.getPort() != -1 ? requestUri.getPort() : (HTTPS_SCHEME.equals(requestUri.getScheme()) ? 443 : 80);

        try {
            String relativeUri = (requestUri.getRawQuery() == null)
                ? requestUri.getRawPath()
                : requestUri.getRawPath() + '?' + requestUri.getRawQuery();

            final RequestOptions reqOptions = new RequestOptions()
                .setMethod(HttpMethod.GET)
                .setPort(port)
                .setHost(requestUri.getHost())
                .setURI(relativeUri)
                .putHeader(HttpHeaders.USER_AGENT, NodeUtils.userAgent(node))
                .putHeader("X-Gravitee-Request-Id", UUID.toString(UUID.random()))
                .setTimeout(httpClientTimeout);

            httpClient
                .request(reqOptions)
                .compose(HttpClientRequest::send)
                .compose(response -> {
                    if (response.statusCode() == HttpStatusCode.OK_200) {
                        return response.body();
                    } else {
                        return Future.failedFuture(
                            new FetcherException(
                                "Unable to fetch '" +
                                    httpFetcherConfiguration.getUrl() +
                                    "'. Status code: " +
                                    response.statusCode() +
                                    ". Message: " +
                                    response.statusMessage(),
                                null
                            )
                        );
                    }
                })
                .onSuccess(promise::complete)
                .onFailure(promise::fail);
        } catch (Exception ex) {
            promise.fail(ex);
        }

        return promise.future().toCompletionStage().toCompletableFuture();
    }

    private static HttpClient buildHttpClient(ClientKey key) {
        final HttpClientOptions options = new HttpClientOptions()
            .setSsl(key.ssl())
            .setTrustAll(true)
            .setKeepAlive(false)
            .setTcpKeepAlive(false)
            .setConnectTimeout(key.timeout())
            .setIdleTimeout(key.timeout())
            .setIdleTimeoutUnit(TimeUnit.MILLISECONDS);

        final PoolOptions poolOptions = new PoolOptions().setHttp1MaxSize(MAX_CONNECTIONS);

        if (key.proxy() != null) {
            options.setProxyOptions(
                new ProxyOptions()
                    .setType(ProxyType.valueOf(key.proxy().type()))
                    .setHost(key.proxy().host())
                    .setPort(key.proxy().port())
                    .setUsername(key.proxy().username())
                    .setPassword(key.proxy().password())
            );
        }

        return key.vertx().createHttpClient(options, poolOptions);
    }

    private record Proxy(String type, String host, int port, String username, String password) {}

    private record ClientKey(Vertx vertx, boolean ssl, int timeout, Proxy proxy) {}

    public void setVertx(Vertx vertx) {
        this.vertx = vertx;
    }
}
