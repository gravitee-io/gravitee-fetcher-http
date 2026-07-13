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

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.gravitee.fetcher.api.FetcherException;
import io.vertx.core.Vertx;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * @author Nicolas GERAUD (nicolas <AT> graviteesource.com)
 * @author GraviteeSource Team
 */
class HttpFetcherTest {

    @RegisterExtension
    static WireMockExtension wiremock = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private Vertx vertx;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
    }

    @AfterEach
    void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    public void shouldGetExistingFile() throws Exception {
        wiremock.stubFor(get(urlEqualTo("/resource/to/fetch")).willReturn(aResponse().withStatus(200).withBody("Gravitee.io is awesome!")));

        HttpFetcherConfiguration httpFetcherConfiguration = new HttpFetcherConfiguration();
        httpFetcherConfiguration.setUrl(wiremock.baseUrl() + "/resource/to/fetch");
        HttpFetcher httpFetcher = new HttpFetcher(httpFetcherConfiguration);
        ReflectionTestUtils.setField(httpFetcher, "httpClientTimeout", 10_000);
        httpFetcher.setVertx(vertx);
        InputStream is = httpFetcher.fetch().getContent();
        assertThat(is).isNotNull();
        BufferedReader br = new BufferedReader(new InputStreamReader(is));
        String line;
        String content = "";
        while ((line = br.readLine()) != null) {
            content += line;
            assertThat(line).isNotNull();
        }
        br.close();
        assertThat(content).contains("awesome");
    }

    @Test
    public void shouldGetInexistingFile() throws Exception {
        wiremock.stubFor(get(urlEqualTo("/resource/to/fetch")).willReturn(aResponse().withStatus(404)));
        HttpFetcherConfiguration httpFetcherConfiguration = new HttpFetcherConfiguration();
        httpFetcherConfiguration.setUrl(wiremock.baseUrl() + "/resource/to/fetch");
        HttpFetcher httpFetcher = new HttpFetcher(httpFetcherConfiguration);
        httpFetcher.setVertx(vertx);
        InputStream is = null;
        try {
            is = httpFetcher.fetch().getContent();
            fail("should not happen");
        } catch (FetcherException fetcherException) {
            assertThat(fetcherException.getMessage()).contains("Unable to fetch");
            assertThat(is).isNull();
        }
    }

    @Test
    void should_fail_with_status_details_when_response_is_not_200() {
        wiremock.stubFor(get(urlEqualTo("/resource/to/fetch")).willReturn(aResponse().withStatus(500)));

        HttpFetcher httpFetcher = fetcher(10_000);

        assertThatThrownBy(httpFetcher::fetch).isInstanceOf(FetcherException.class).hasMessageContaining("Status code: 500");
    }

    @Test
    void should_expose_original_cause_instead_of_async_wrapper_when_fetch_fails() {
        wiremock.stubFor(get(urlEqualTo("/resource/to/fetch")).willReturn(aResponse().withStatus(404)));

        HttpFetcher httpFetcher = fetcher(10_000);

        assertThatThrownBy(httpFetcher::fetch)
            .isInstanceOf(FetcherException.class)
            .hasCauseInstanceOf(FetcherException.class)
            .cause()
            .isNotInstanceOf(ExecutionException.class);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void should_fail_fast_when_connection_is_closed_while_reading_response() {
        wiremock.stubFor(get(urlEqualTo("/resource/to/fetch")).willReturn(aResponse().withFault(Fault.RANDOM_DATA_THEN_CLOSE)));

        HttpFetcher httpFetcher = fetcher(10_000);

        assertThatThrownBy(httpFetcher::fetch).isInstanceOf(FetcherException.class);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void should_fail_when_connection_stalls_while_reading_body() {
        wiremock.stubFor(
            get(urlEqualTo("/resource/to/fetch")).willReturn(
                aResponse().withStatus(200).withBody("Gravitee.io is awesome!").withChunkedDribbleDelay(20, 20_000)
            )
        );

        HttpFetcher httpFetcher = fetcher(500);

        assertThatThrownBy(httpFetcher::fetch).isInstanceOf(FetcherException.class);
    }

    private HttpFetcher fetcher(int timeoutMs) {
        HttpFetcherConfiguration configuration = new HttpFetcherConfiguration();
        configuration.setUrl(wiremock.baseUrl() + "/resource/to/fetch");
        HttpFetcher httpFetcher = new HttpFetcher(configuration);
        ReflectionTestUtils.setField(httpFetcher, "httpClientTimeout", timeoutMs);
        httpFetcher.setVertx(vertx);
        return httpFetcher;
    }
}
