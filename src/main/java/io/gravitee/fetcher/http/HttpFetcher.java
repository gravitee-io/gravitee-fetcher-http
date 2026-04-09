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
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.*;
import io.vertx.core.net.ProxyOptions;
import io.vertx.core.net.ProxyType;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
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
        } catch (Exception ex) {
            throw new FetcherException("Unable to fetch Http content (" + ex.getMessage() + ")", ex);
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

        final HttpClientOptions options = new HttpClientOptions()
            .setSsl(ssl)
            .setTrustAll(true)
            .setKeepAlive(false)
            .setTcpKeepAlive(false)
            .setConnectTimeout(httpClientTimeout);

        final PoolOptions poolOptions = new PoolOptions().setHttp1MaxSize(1);

        if (httpFetcherConfiguration.isUseSystemProxy()) {
            ProxyOptions proxyOptions = new ProxyOptions();
            proxyOptions.setType(ProxyType.valueOf(httpClientProxyType));
            if (HTTPS_SCHEME.equals(requestUri.getScheme())) {
                proxyOptions.setHost(httpClientProxyHttpsHost);
                proxyOptions.setPort(httpClientProxyHttpsPort);
                proxyOptions.setUsername(httpClientProxyHttpsUsername);
                proxyOptions.setPassword(httpClientProxyHttpsPassword);
            } else {
                proxyOptions.setHost(httpClientProxyHttpHost);
                proxyOptions.setPort(httpClientProxyHttpPort);
                proxyOptions.setUsername(httpClientProxyHttpUsername);
                proxyOptions.setPassword(httpClientProxyHttpPassword);
            }
            options.setProxyOptions(proxyOptions);
        }

        final HttpClient httpClient = vertx.createHttpClient(options, poolOptions);
        // Ensure the HTTP client is closed exactly once when the promise completes, regardless of success or failure
        promise.future().onComplete(ar -> httpClient.close());

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
                .onFailure(promise::fail)
                .onSuccess(request -> {
                    request.exceptionHandler(throwable -> {
                        try {
                            promise.fail(throwable);
                        } catch (IllegalStateException ise) {
                            // Promise already completed
                        }
                    });

                    request
                        .send()
                        .onFailure(promise::fail)
                        .onSuccess(response -> {
                            if (response.statusCode() == HttpStatusCode.OK_200) {
                                response.bodyHandler(promise::complete);
                            } else {
                                promise.complete(null);
                            }
                        });
                });
        } catch (Exception ex) {
            log.error("Unable to fetch content using HTTP", ex);
            promise.fail(ex);
        }

        return promise.future().toCompletionStage().toCompletableFuture();
    }

    public void setVertx(Vertx vertx) {
        this.vertx = vertx;
    }
}
