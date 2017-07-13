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

import com.github.tomakehurst.wiremock.junit.WireMockRule;
import io.gravitee.fetcher.api.FetcherException;
import io.vertx.core.Vertx;
import org.junit.Rule;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * @author Nicolas GERAUD (nicolas <AT> graviteesource.com)
 * @author GraviteeSource Team
 */
public class HttpFetcherTest {

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(wireMockConfig().dynamicPort());

    @Test
    public void shouldGetExistingFile() throws Exception {
        stubFor(get(urlEqualTo("/resource/to/fetch"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("Gravitee.io is awesome!")));

        HttpFetcherConfiguration httpFetcherConfiguration = new HttpFetcherConfiguration();
        httpFetcherConfiguration.setUrl("http://localhost:" + wireMockRule.port() + "/resource/to/fetch");
        HttpFetcher httpFetcher = new HttpFetcher(httpFetcherConfiguration);
        httpFetcher.setVertx(Vertx.vertx());
        InputStream is = httpFetcher.fetch();
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
        stubFor(get(urlEqualTo("/resource/to/fetch"))
                .willReturn(aResponse()
                        .withStatus(404)));
        HttpFetcherConfiguration httpFetcherConfiguration = new HttpFetcherConfiguration();
        httpFetcherConfiguration.setUrl("http://localhost:" + wireMockRule.port() + "/resource/to/fetch");
        HttpFetcher httpFetcher = new HttpFetcher(httpFetcherConfiguration);
        httpFetcher.setVertx(Vertx.vertx());
        InputStream is = null;
        try {
            is = httpFetcher.fetch();
            fail("should not happen");
        } catch (FetcherException fetcherException) {
            assertThat(fetcherException.getMessage()).contains("Unable to fetch");
            assertThat(is).isNull();
        }
    }
}
