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

import io.gravitee.fetcher.api.Fetcher;
import io.gravitee.fetcher.api.FetcherException;
import org.asynchttpclient.*;

import java.io.*;

/**
 * @author Nicolas GERAUD (nicolas <AT> graviteesource.com)
 * @author GraviteeSource Team
 */
public class HttpFetcher implements Fetcher {

    private HttpFetcherConfiguration httpFetcherConfiguration;
    private AsyncHttpClient asyncHttpClient;
    private static final int GLOBAL_TIMEOUT = 10_000;

    public HttpFetcher(HttpFetcherConfiguration httpFetcherConfiguration) {
        this.httpFetcherConfiguration = httpFetcherConfiguration;
        this.asyncHttpClient = new DefaultAsyncHttpClient(
                new DefaultAsyncHttpClientConfig.Builder()
                        .setConnectTimeout(GLOBAL_TIMEOUT)
                        .setReadTimeout(GLOBAL_TIMEOUT)
                        .setRequestTimeout(GLOBAL_TIMEOUT)
                        .setMaxConnections(10)
                        .setMaxConnectionsPerHost(5)
                        .setAcceptAnyCertificate(true)
                        .build());
    }

    @Override
    public InputStream fetch() throws FetcherException {
        try {
            Response response = this.asyncHttpClient
                    .prepareGet(httpFetcherConfiguration.getUrl())
                    .execute().get();

            if (response.getStatusCode() != 200) {
                throw new FetcherException("Unable to fetch '" + httpFetcherConfiguration.getUrl() + "'. Status code: " + response.getStatusCode(), null);
            }

            return response.getResponseBodyAsStream();
        } catch (Exception e) {
            throw new FetcherException("Unable to fetch '" + httpFetcherConfiguration.getUrl() + "'", e);
        }
    }
}
