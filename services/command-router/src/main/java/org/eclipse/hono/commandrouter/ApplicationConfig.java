/*******************************************************************************
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
 *******************************************************************************/

package org.eclipse.hono.commandrouter;

import java.util.Optional;

import org.eclipse.hono.adapter.client.registry.DeviceRegistrationClient;
import org.eclipse.hono.adapter.client.registry.amqp.ProtonBasedDeviceRegistrationClient;
import org.eclipse.hono.cache.CacheProvider;
import org.eclipse.hono.client.CommandTargetMapper;
import org.eclipse.hono.client.HonoConnection;
import org.eclipse.hono.client.RequestResponseClientConfigProperties;
import org.eclipse.hono.client.SendMessageSampler;
import org.eclipse.hono.commandrouter.impl.CommandRouterServiceImpl;
import org.eclipse.hono.commandrouter.impl.amqp.ProtonBasedCommandConsumerFactoryImpl;
import org.eclipse.hono.config.ApplicationConfigProperties;
import org.eclipse.hono.config.AuthenticatingClientConfigProperties;
import org.eclipse.hono.config.ClientConfigProperties;
import org.eclipse.hono.config.ServerConfig;
import org.eclipse.hono.config.ServiceConfigProperties;
import org.eclipse.hono.config.VertxProperties;
import org.eclipse.hono.deviceconnection.infinispan.client.BasicCache;
import org.eclipse.hono.deviceconnection.infinispan.client.CacheBasedDeviceConnectionInfo;
import org.eclipse.hono.deviceconnection.infinispan.client.CommonCacheConfig;
import org.eclipse.hono.service.HealthCheckServer;
import org.eclipse.hono.service.VertxBasedHealthCheckServer;
import org.eclipse.hono.service.amqp.AmqpEndpoint;
import org.eclipse.hono.service.cache.SpringCacheProvider;
import org.eclipse.hono.service.commandrouter.CommandRouterService;
import org.eclipse.hono.service.commandrouter.DelegatingCommandRouterAmqpEndpoint;
import org.eclipse.hono.service.deviceconnection.DelegatingDeviceConnectionAmqpEndpoint;
import org.eclipse.hono.service.deviceconnection.DeviceConnectionService;
import org.eclipse.hono.service.metric.MetricsTags;
import org.eclipse.hono.util.CommandConstants;
import org.eclipse.hono.util.Constants;
import org.eclipse.hono.util.RegistrationConstants;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ObjectFactoryCreatingFactoryBean;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Scope;

import com.github.benmanes.caffeine.cache.Caffeine;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentracing.Tracer;
import io.opentracing.contrib.tracerresolver.TracerResolver;
import io.opentracing.noop.NoopTracerFactory;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;

/**
 * Spring Boot configuration for the Command Router service.
 *
 */
@Configuration
public class ApplicationConfig {

    /**
     * Profile for using a embedded (vs remote) cache.
     */
    public static final String PROFILE_EMBEDDED_CACHE = "embedded-cache";
    /**
     * Profile for enabling an AMQP endpoint implementing the Device Connection API.
     */
    public static final String PROFILE_ENABLE_DEVICE_CONNECTION_ENDPOINT = "enable-device-connection-endpoint";

    private static final String BEAN_NAME_AMQP_SERVER = "amqpServer";
    private static final String COMPONENT_NAME = "Command Router";

    /**
     * Exposes an OpenTracing {@code Tracer} as a Spring Bean.
     * <p>
     * The Tracer will be resolved by means of a Java service lookup.
     * If no tracer can be resolved this way, the {@code NoopTracer} is
     * returned.
     *
     * @return The tracer.
     */
    @Bean
    public Tracer getTracer() {
        return Optional.ofNullable(TracerResolver.resolveTracer())
                .orElse(NoopTracerFactory.create());
    }

    /**
     * Exposes a Vert.x instance as a Spring bean.
     * <p>
     * This method creates new Vert.x default options and invokes
     * {@link VertxProperties#configureVertx(VertxOptions)} on the object returned
     * by {@link #vertxProperties()}.
     *
     * @return The Vert.x instance.
     */
    @Bean
    public Vertx vertx() {
        return Vertx.vertx(vertxProperties().configureVertx(new VertxOptions()));
    }

    /**
     * Exposes configuration properties for vert.x.
     *
     * @return The properties.
     */
    @ConfigurationProperties("hono.vertx")
    @Bean
    public VertxProperties vertxProperties() {
        return new VertxProperties();
    }

    /**
     * Exposes properties for configuring the application properties as a Spring bean.
     *
     * @return The application configuration properties.
     */
    @Bean
    @ConfigurationProperties(prefix = "hono.app")
    public ApplicationConfigProperties applicationConfigProperties() {
        return new ApplicationConfigProperties();
    }

    /**
     * Gets properties for configuring the Command Router service's AMQP 1.0 endpoint.
     *
     * @return The properties.
     */
    @Qualifier(Constants.QUALIFIER_AMQP)
    @Bean
    @ConfigurationProperties(prefix = "hono.command-router.amqp")
    public ServiceConfigProperties amqpServerProperties() {
        return new ServiceConfigProperties();
    }

    /**
     * Exposes the AMQP based Command Router server as a Spring Bean.
     *
     * @return The service.
     */
    @Bean(name = BEAN_NAME_AMQP_SERVER)
    @Scope("prototype")
    public CommandRouterAmqpServer amqpServer() {
        return new CommandRouterAmqpServer();
    }

    /**
     * Exposes a factory for creating Command Router service instances.
     *
     * @return The factory bean.
     */
    @Bean
    public ObjectFactoryCreatingFactoryBean amqpServerFactory() {
        final ObjectFactoryCreatingFactoryBean factory = new ObjectFactoryCreatingFactoryBean();
        factory.setTargetBeanName(BEAN_NAME_AMQP_SERVER);
        return factory;
    }

    /**
     * Creates a new instance of an AMQP 1.0 protocol handler for Hono's <em>Command Router</em> API.
     *
     * @param service The service instance to delegate to.
     * @return The handler.
     */
    @Bean
    @Scope("prototype")
    public AmqpEndpoint commandRouterAmqpEndpoint(final CommandRouterService service) {
        return new DelegatingCommandRouterAmqpEndpoint<>(vertx(), service);
    }

    /**
     * Gets properties for configuring the Command Router service.
     *
     * @return The properties.
     */
    @Bean
    @ConfigurationProperties(prefix = "hono.command-router.svc")
    public CommandRouterServiceConfigProperties commandRouterServiceConfigProperties() {
        return new CommandRouterServiceConfigProperties();
    }

    /**
     * Exposes a Command Router service as a Spring bean.
     *
     * @return The service implementation.
     */
    @Bean
    public CommandRouterServiceImpl commandRouterService() {
        return new CommandRouterServiceImpl();
    }

    /**
     * Exposes a client to access device connection data as a Spring bean.
     *
     * @param cache The cache storing the device connection data.
     * @param tracer The tracer instance.
     * @return The service implementation.
     */
    @Bean
    public CacheBasedDeviceConnectionInfo deviceConnectionInfo(final BasicCache<String, String> cache,
            final Tracer tracer) {
        return new CacheBasedDeviceConnectionInfo(cache, tracer);
    }

    /**
     * Gets properties for configuring the common cache aspects used for storing device connection data.
     *
     * @return The properties.
     */
    @Bean
    @ConfigurationProperties(prefix = "hono.device-connection.common")
    public CommonCacheConfig deviceConnectionInfoCommonCacheConfig() {
        return new CommonCacheConfig();
    }

    /**
     * Exposes configuration properties for the command consumer factory.
     *
     * @return The Properties.
     */
    @Qualifier(CommandConstants.COMMAND_ENDPOINT)
    @ConfigurationProperties(prefix = "hono.command")
    @Bean
    public ClientConfigProperties commandConsumerFactoryConfig() {
        final ClientConfigProperties config = new ClientConfigProperties();
        setConfigServerRoleIfUnknown(config, "Command & Control");
        setDefaultConfigNameIfNotSet(config);
        return config;
    }

    /**
     * Exposes the connection used for receiving upstream commands as a Spring bean.
     *
     * @return The connection.
     */
    @Bean
    @Scope("prototype")
    public HonoConnection commandConsumerConnection() {
        return HonoConnection.newConnection(vertx(), commandConsumerFactoryConfig());
    }

    /**
     * Exposes a factory for creating clients for receiving upstream commands
     * via the AMQP Messaging Network.
     *
     * @param config The component's configuration properties.
     * @return The factory.
     */
    @Bean
    @Scope("prototype")
    public CommandConsumerFactory commandConsumerFactory(final CommandRouterServiceConfigProperties config) {
        return new ProtonBasedCommandConsumerFactoryImpl(
                commandConsumerConnection(),
                SendMessageSampler.Factory.noop(),
                config);
    }

    /**
     * Exposes the component for mapping an incoming command to the gateway (if applicable)
     * and protocol adapter instance that can handle it.
     *
     * @return The newly created mapper instance.
     */
    @Bean
    @Scope("prototype")
    public CommandTargetMapper commandTargetMapper() {
        return CommandTargetMapper.create(getTracer());
    }

    /**
     * Exposes configuration properties for accessing the registration service as a Spring bean.
     *
     * @return The properties.
     */
    @Qualifier(RegistrationConstants.REGISTRATION_ENDPOINT)
    @ConfigurationProperties(prefix = "hono.registration")
    @Bean
    public RequestResponseClientConfigProperties registrationClientConfig() {
        final RequestResponseClientConfigProperties config = new RequestResponseClientConfigProperties();
        setConfigServerRoleIfUnknown(config, "Device Registration");
        setDefaultConfigNameIfNotSet(config);
        return config;
    }

    /**
     * Exposes a client for accessing the <em>Device Registration</em> API as a Spring bean.
     *
     * @param config The component's configuration properties.
     * @return The client.
     */
    @Bean
    @Qualifier(RegistrationConstants.REGISTRATION_ENDPOINT)
    @Scope("prototype")
    public DeviceRegistrationClient registrationClient(final CommandRouterServiceConfigProperties config) {

        return new ProtonBasedDeviceRegistrationClient(
                registrationServiceConnection(),
                SendMessageSampler.Factory.noop(),
                config,
                registrationCacheProvider());
    }

    /**
     * Exposes the connection used for accessing the registration service as a Spring bean.
     *
     * @return The connection.
     */
    @Bean
    @Qualifier(RegistrationConstants.REGISTRATION_ENDPOINT)
    @Scope("prototype")
    public HonoConnection registrationServiceConnection() {
        return HonoConnection.newConnection(vertx(), registrationClientConfig());
    }

    /**
     * Exposes the provider for caches as a Spring bean.
     *
     * @return The provider instance.
     */
    @Bean
    @Qualifier(RegistrationConstants.REGISTRATION_ENDPOINT)
    @Scope("prototype")
    public CacheProvider registrationCacheProvider() {
        return newCaffeineCache(registrationClientConfig());
    }

    /**
     * Create a new cache provider based on Caffeine and Spring Cache.
     *
     * @param config The configuration to use as base for this cache.
     * @return A new cache provider or {@code null} if no cache should be used.
     */
    private static CacheProvider newCaffeineCache(final RequestResponseClientConfigProperties config) {
        return newCaffeineCache(config.getResponseCacheMinSize(), config.getResponseCacheMaxSize());
    }

    /**
     * Create a new cache provider based on Caffeine and Spring Cache.
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

        final CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setAllowNullValues(false);
        manager.setCaffeine(caffeine);

        return new SpringCacheProvider(manager);
    }

    private static void setConfigServerRoleIfUnknown(final AuthenticatingClientConfigProperties config,
            final String serverRole) {
        if (config.getServerRole().equals(AuthenticatingClientConfigProperties.SERVER_ROLE_UNKNOWN)) {
            config.setServerRole(serverRole);
        }
    }

    private void setDefaultConfigNameIfNotSet(final ClientConfigProperties config) {
        if (config.getName() == null) {
            config.setName(COMPONENT_NAME);
        }
    }

    /**
     * Customizer for meter registry.
     *
     * @return The new meter registry customizer.
     */
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> commonTags() {
        return r -> r.config().commonTags(MetricsTags.forService(Constants.SERVICE_NAME_COMMAND_ROUTER));
    }

    /**
     * Gets properties for configuring the service's health check endpoint.
     *
     * @return The properties.
     */
    @Bean
    @ConfigurationProperties(prefix = "hono.health-check")
    public ServerConfig healthCheckProperties() {
        return new ServerConfig();
    }

    /**
     * Exposes the health check server as a Spring bean.
     *
     * @return The health check server.
     */
    @Bean
    public HealthCheckServer healthCheckServer() {
        return new VertxBasedHealthCheckServer(vertx(), healthCheckProperties());
    }


    // ---- Optional beans letting the Command Router component also implement the Device Connection API (e.g. for integration tests) ----

    /**
     * Creates a new instance of an AMQP 1.0 protocol handler for Hono's <em>Device Connection</em> API.
     *
     * @param service The service instance to delegate to.
     * @return The handler.
     */
    @Bean
    @Scope("prototype")
    @Profile(PROFILE_ENABLE_DEVICE_CONNECTION_ENDPOINT)
    public AmqpEndpoint deviceConnectionAmqpEndpoint(final DeviceConnectionService service) {
        return new DelegatingDeviceConnectionAmqpEndpoint<>(vertx(), service);
    }

    /**
     * Exposes a Device Connection service as a Spring bean.
     *
     * @param deviceConnectionInfo The Device Connection info repository.
     * @return The service implementation.
     */
    @Bean
    @Profile(PROFILE_ENABLE_DEVICE_CONNECTION_ENDPOINT)
    public CacheBasedDeviceConnectionService deviceConnectionService(final CacheBasedDeviceConnectionInfo deviceConnectionInfo) {
        return new CacheBasedDeviceConnectionService(deviceConnectionInfo);
    }
}
