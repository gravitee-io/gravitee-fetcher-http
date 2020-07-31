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

import io.gravitee.fetcher.api.FetcherConfiguration;

/**
 * @author Nicolas GERAUD (nicolas.geraud at graviteesource.com)
 * @author GraviteeSource Team
 */
public class HttpFetcherConfiguration implements FetcherConfiguration {

    private String url;

    private boolean useSystemProxy;

    private String fetchCron;

    private boolean autoFetch = false;

    @Override
    public String getFetchCron() {
        return fetchCron;
    }

    public void setFetchCron(String fetchCron) {
        this.fetchCron = fetchCron;
    }

    @Override
    public boolean isAutoFetch() {
        return autoFetch;
    }

    public void setAutoFetch(boolean autoFetch) {
        this.autoFetch = autoFetch;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public boolean isUseSystemProxy() {
        return useSystemProxy;
    }

    public void setUseSystemProxy(boolean useSystemProxy) {
        this.useSystemProxy = useSystemProxy;
    }
}
