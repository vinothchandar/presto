/*
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
package com.facebook.presto.server;

import com.facebook.airlift.configuration.AbstractConfigurationAwareModule;
import com.facebook.airlift.discovery.server.EmbeddedDiscoveryModule;
import com.facebook.presto.dispatcher.NoOpQueryManager;
import com.facebook.presto.execution.QueryIdGenerator;
import com.facebook.presto.execution.QueryInfo;
import com.facebook.presto.execution.QueryManager;
import com.facebook.presto.execution.QueryPreparer;
import com.facebook.presto.execution.resourceGroups.NoOpResourceGroupManager;
import com.facebook.presto.execution.resourceGroups.ResourceGroupManager;
import com.facebook.presto.failureDetector.FailureDetectorModule;
import com.facebook.presto.resourcemanager.ForResourceManager;
import com.facebook.presto.resourcemanager.ResourceManagerClusterStateProvider;
import com.facebook.presto.resourcemanager.ResourceManagerConfig;
import com.facebook.presto.resourcemanager.ResourceManagerServer;
import com.facebook.presto.transaction.NoOpTransactionManager;
import com.facebook.presto.transaction.TransactionManager;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.inject.Binder;
import com.google.inject.Provides;
import com.google.inject.Scopes;

import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.inject.Singleton;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;

import static com.facebook.airlift.concurrent.Threads.daemonThreadsNamed;
import static com.facebook.airlift.configuration.ConditionalModule.installModuleIf;
import static com.facebook.airlift.discovery.client.DiscoveryBinder.discoveryBinder;
import static com.facebook.airlift.http.client.HttpClientBinder.httpClientBinder;
import static com.facebook.airlift.jaxrs.JaxrsBinder.jaxrsBinder;
import static com.facebook.airlift.json.JsonCodecBinder.jsonCodecBinder;
import static com.facebook.airlift.json.smile.SmileCodecBinder.smileCodecBinder;
import static com.facebook.drift.server.guice.DriftServerBinder.driftServerBinder;
import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;
import static java.util.concurrent.TimeUnit.SECONDS;

public class ResourceManagerModule
        extends AbstractConfigurationAwareModule
{
    @Override
    protected void setup(Binder binder)
    {
        // discovery server
        install(installModuleIf(EmbeddedDiscoveryConfig.class, EmbeddedDiscoveryConfig::isEnabled, new EmbeddedDiscoveryModule()));

        // presto coordinator announcement
        discoveryBinder(binder).bindHttpAnnouncement("presto-resource-manager");

        // statement resource
        jsonCodecBinder(binder).bindJsonCodec(QueryInfo.class);

        // resource for serving static content
        jaxrsBinder(binder).bind(WebUiResource.class);

        // failure detector
        binder.install(new FailureDetectorModule());
        jaxrsBinder(binder).bind(NodeResource.class);
        jaxrsBinder(binder).bind(WorkerResource.class);
        httpClientBinder(binder).bindHttpClient("workerInfo", ForWorkerInfo.class);

        // TODO: decouple query-level configuration that is not needed for Resource Manager
        binder.bind(QueryManager.class).to(NoOpQueryManager.class).in(Scopes.SINGLETON);
        jaxrsBinder(binder).bind(ResourceGroupStateInfoResource.class);
        binder.bind(QueryIdGenerator.class).in(Scopes.SINGLETON);
        binder.bind(QueryPreparer.class).in(Scopes.SINGLETON);
        binder.bind(SessionSupplier.class).to(QuerySessionSupplier.class).in(Scopes.SINGLETON);

        binder.bind(ResourceGroupManager.class).to(NoOpResourceGroupManager.class);

        jsonCodecBinder(binder).bindJsonCodec(QueryInfo.class);
        smileCodecBinder(binder).bindSmileCodec(QueryInfo.class);
        jsonCodecBinder(binder).bindJsonCodec(BasicQueryInfo.class);
        smileCodecBinder(binder).bindSmileCodec(BasicQueryInfo.class);

        binder.bind(TransactionManager.class).to(NoOpTransactionManager.class);

        binder.bind(ResourceManagerClusterStateProvider.class).in(Scopes.SINGLETON);
        driftServerBinder(binder).bindService(ResourceManagerServer.class);

        // cleanup
        binder.bind(ExecutorCleanup.class).in(Scopes.SINGLETON);
    }

    @Provides
    @Singleton
    public static ResourceGroupManager<?> getResourceGroupManager(@SuppressWarnings("rawtypes") ResourceGroupManager manager)
    {
        return manager;
    }

    @Provides
    @Singleton
    @ForResourceManager
    public static ListeningExecutorService createResourceManagerExecutor(ResourceManagerConfig config)
    {
        ExecutorService executor = new ThreadPoolExecutor(
                1,
                config.getResourceManagerExecutorThreads(),
                60,
                SECONDS,
                new LinkedBlockingQueue<>(),
                daemonThreadsNamed("resource-manager-executor-%s"));
        return listeningDecorator(executor);
    }

    public static class ExecutorCleanup
    {
        private ListeningExecutorService resourceManagerExecutor;

        @Inject
        public ExecutorCleanup(@ForResourceManager ListeningExecutorService resourceManagerExecutor)
        {
            this.resourceManagerExecutor = resourceManagerExecutor;
        }

        @PreDestroy
        public void shutdown()
        {
            resourceManagerExecutor.shutdownNow();
        }
    }
}
