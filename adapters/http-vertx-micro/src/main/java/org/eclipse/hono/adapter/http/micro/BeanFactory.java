/**
 * Copyright (c) 2020 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */


package org.eclipse.hono.adapter.http.micro;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import javax.inject.Singleton;

import org.eclipse.hono.adapter.http.HttpAdapterMetrics;
import org.eclipse.hono.adapter.http.HttpProtocolAdapterProperties;
import org.eclipse.hono.adapter.http.MicrometerBasedHttpAdapterMetrics;
import org.eclipse.hono.adapter.http.impl.VertxBasedHttpProtocolAdapter;
import org.eclipse.hono.cache.BasicExpiringValue;
import org.eclipse.hono.cache.CacheProvider;
import org.eclipse.hono.cache.ExpiringValue;
import org.eclipse.hono.cache.ExpiringValueCache;
import org.eclipse.hono.client.CommandTargetMapper;
import org.eclipse.hono.client.CredentialsClientFactory;
import org.eclipse.hono.client.DeviceConnectionClientFactory;
import org.eclipse.hono.client.DownstreamSenderFactory;
import org.eclipse.hono.client.HonoConnection;
import org.eclipse.hono.client.ProtocolAdapterCommandConsumerFactory;
import org.eclipse.hono.client.RegistrationClientFactory;
import org.eclipse.hono.client.TenantClientFactory;
import org.eclipse.hono.service.HealthCheckServer;
import org.eclipse.hono.service.VertxBasedHealthCheckServer;
import org.eclipse.hono.service.metric.Metrics;
import org.eclipse.hono.service.resourcelimits.PrometheusBasedResourceLimitChecks;
import org.eclipse.hono.service.resourcelimits.PrometheusBasedResourceLimitChecksConfig;
import org.eclipse.hono.service.resourcelimits.ResourceLimitChecks;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.lang.Nullable;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Prototype;
import io.micronaut.context.annotation.Requires;
import io.opentracing.Tracer;
import io.opentracing.contrib.tracerresolver.TracerResolver;
import io.opentracing.noop.NoopTracerFactory;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;

/**
 * A factory for the objects making up the HTTP adapter.
 *
 */
@Factory
class BeanFactory {

    /**
     * Create a new cache provider based on Caffeine.
     *
     * @param minCacheSize The minimum size of the cache.
     * @param maxCacheSize the maximum size of the cache.
     * @return A new cache provider or {@code null} if no cache should be used.
     */
    private static CacheProvider newCaffeineCache(final int minCacheSize, final long maxCacheSize) {

        if (maxCacheSize <= 0) {
            return null;
        }

        final Caffeine<Object, Object> caffeine = Caffeine.newBuilder()
                .initialCapacity(minCacheSize)
                .maximumSize(Math.max(minCacheSize, maxCacheSize));

        return new CacheProvider() {
            private Map<String, ExpiringValueCache<Object, Object>> caches = new HashMap<>();

            @Override
            public ExpiringValueCache<Object, Object> getCache(final String cacheName) {

                return caches.computeIfAbsent(cacheName, name -> new CaffeineBasedExpiringValueCache<>(caffeine.build()));
            }
        };
    }

    @Singleton
    MicrometerBasedHttpAdapterMetrics metrics(final Vertx vertx, final MeterRegistry registry) {
        return new MicrometerBasedHttpAdapterMetrics(registry, vertx);
    }

    @Singleton
    Tracer tracer() {
        return Optional.ofNullable(TracerResolver.resolveTracer())
                .orElse(NoopTracerFactory.create());
    }

    /**
     * Exposes a Vert.x instance as a bean.
     * <p>
     * This method creates new Vert.x default options and invokes
     * {@link VertxProperties#configureVertx(VertxOptions)} on the
     * given properties object.
     *
     * @return The Vert.x instance.
     */
    @Singleton
    Vertx vertx(final HonoConfig config) {
        return Vertx.vertx(config.vertxProperties.configureVertx(new VertxOptions()));
    }

    @Prototype
    ProtocolAdapterCommandConsumerFactory commandConsumerFactory(final Vertx vertx, final HonoConfig config) {
        return ProtocolAdapterCommandConsumerFactory.create(HonoConnection.newConnection(vertx, config.commandConsumerConfigProperties));
    }

    @Prototype
    CommandTargetMapper commandTargetMapper(final Tracer tracer) {
        return CommandTargetMapper.create(tracer);
    }

    @Prototype
    CredentialsClientFactory credentialsClientFactory(final Vertx vertx, final HonoConfig config) {
        return CredentialsClientFactory.create(
                HonoConnection.newConnection(vertx, config.credentialsClientConfigProperties),
                newCaffeineCache(config.credentialsClientConfigProperties.getResponseCacheMinSize(), config.credentialsClientConfigProperties.getResponseCacheMaxSize()));
    }

    @Requires(property = "hono.device-connection.host")
    @Prototype
    DeviceConnectionClientFactory deviceConnectionClientFactory(final Vertx vertx, final HonoConfig config) {
        return DeviceConnectionClientFactory.create(HonoConnection.newConnection(vertx, config.deviceConnectionServiceClientConfigProperties));
    }

    @Prototype
    DownstreamSenderFactory downstreamSenderFactory(final Vertx vertx, final HonoConfig config) {
        return DownstreamSenderFactory.create(HonoConnection.newConnection(vertx, config.downstreamSenderConfigProperties));
    }

    @Singleton
    HealthCheckServer healthCheckServer(final Vertx vertx, final HonoConfig config) {
        return new VertxBasedHealthCheckServer(vertx, config.healthCheckConfigProperties);
    }

    @Prototype
    RegistrationClientFactory registrationClientFactory(final Vertx vertx, final HonoConfig config) {
        return RegistrationClientFactory.create(
                HonoConnection.newConnection(vertx, config.registrationClientConfigProperties),
                newCaffeineCache(config.registrationClientConfigProperties.getResponseCacheMinSize(), config.registrationClientConfigProperties.getResponseCacheMaxSize()));
    }

    @Prototype
    TenantClientFactory tenantClientFactory(final Vertx vertx, final HonoConfig config) {
        return TenantClientFactory.create(
                HonoConnection.newConnection(vertx, config.tenantClientConfigProperties),
                newCaffeineCache(config.tenantClientConfigProperties.getResponseCacheMinSize(), config.tenantClientConfigProperties.getResponseCacheMaxSize()));
    }

    /**
     * Creates resource limit checks that use Prometheus metrics data.
     * 
     * @return The checks.
     */
    @Requires(property = "hono.resource-limits.prometheus-based.host")
    @Prototype
    public ResourceLimitChecks resourceLimitChecks(
            final PrometheusBasedResourceLimitChecksConfig config,
            final Vertx vertx,
            final Tracer tracer) {

        final WebClientOptions webClientOptions = new WebClientOptions();
        webClientOptions.setDefaultHost(config.getHost());
        webClientOptions.setDefaultPort(config.getPort());
        webClientOptions.setTrustOptions(config.getTrustOptions());
        webClientOptions.setKeyCertOptions(config.getKeyCertOptions());
        webClientOptions.setSsl(config.isTlsEnabled());
        return new PrometheusBasedResourceLimitChecks(
                WebClient.create(vertx, webClientOptions),
                config,
                newCaffeineCache(config.getCacheMinSize(), config.getCacheMaxSize()),
                tracer);
    }

    @Prototype
    VertxBasedHttpProtocolAdapter adapter(
            final HonoConfig config,
            final ProtocolAdapterCommandConsumerFactory commandConsumerFactory,
            final CommandTargetMapper commandTargetMapper,
            final CredentialsClientFactory credentialsClientFactory,
            final DeviceConnectionClientFactory deviceConnectionClientFactory,
            final DownstreamSenderFactory downstreamSenderFactory,
            final Optional<HealthCheckServer> healthCheckServer,
            final HttpAdapterMetrics metrics,
            final RegistrationClientFactory registrationClientFactory,
            final Optional<ResourceLimitChecks> resourceLimitChecks,
            final TenantClientFactory tenantClientFactory,
            final Tracer tracer) {

        final VertxBasedHttpProtocolAdapter adapter = new VertxBasedHttpProtocolAdapter();
        adapter.setCommandConsumerFactory(commandConsumerFactory);
        adapter.setCommandTargetMapper(commandTargetMapper);
        adapter.setConfig(config.adapterConfig);
        adapter.setCredentialsClientFactory(credentialsClientFactory);
        adapter.setDeviceConnectionClientFactory(deviceConnectionClientFactory);
        adapter.setDownstreamSenderFactory(downstreamSenderFactory);
        healthCheckServer.ifPresent(s -> adapter.setHealthCheckServer(s));
        adapter.setMetrics(metrics);
        adapter.setRegistrationClientFactory(registrationClientFactory);
        resourceLimitChecks.ifPresent(checks -> adapter.setResourceLimitChecks(checks));
        adapter.setTenantClientFactory(tenantClientFactory);
        adapter.setTracer(tracer);
        return adapter;
    }
}
