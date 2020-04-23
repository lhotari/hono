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

import org.eclipse.hono.adapter.http.HttpProtocolAdapterProperties;
import org.eclipse.hono.client.RequestResponseClientConfigProperties;
import org.eclipse.hono.config.ApplicationConfigProperties;
import org.eclipse.hono.config.ServerConfig;
import org.eclipse.hono.config.VertxProperties;
import org.eclipse.hono.service.resourcelimits.PrometheusBasedResourceLimitChecksConfig;

import io.micronaut.context.annotation.ConfigurationBuilder;
import io.micronaut.context.annotation.ConfigurationProperties;

/**
 * Configuration properties for the HTTP adapter.
 *
 */
@ConfigurationProperties("hono")
public class HonoConfig {

    @ConfigurationBuilder(configurationPrefix = "vertx")
    VertxProperties vertxProperties = new VertxProperties();

    @ConfigurationBuilder(configurationPrefix = "health-check")
    ServerConfig healthCheckConfigProperties = new ServerConfig();

    @ConfigurationBuilder(configurationPrefix = "app")
    ApplicationConfigProperties applicationConfigProperties = new ApplicationConfigProperties();

    @ConfigurationBuilder(configurationPrefix = "resource-limits.prometheus-based")
    PrometheusBasedResourceLimitChecksConfig resourceLimitChecksConfig = new PrometheusBasedResourceLimitChecksConfig();

    @ConfigurationBuilder(configurationPrefix = "http")
    HttpProtocolAdapterProperties adapterConfig = new HttpProtocolAdapterProperties();

    @ConfigurationBuilder(configurationPrefix = "command")
    RequestResponseClientConfigProperties commandConsumerConfigProperties = new RequestResponseClientConfigProperties();

    @ConfigurationBuilder(configurationPrefix = "credentials")
    RequestResponseClientConfigProperties credentialsClientConfigProperties = new RequestResponseClientConfigProperties();

    @ConfigurationBuilder(configurationPrefix = "device-connection")
    RequestResponseClientConfigProperties deviceConnectionServiceClientConfigProperties = new RequestResponseClientConfigProperties();

    @ConfigurationBuilder(configurationPrefix = "messaging")
    RequestResponseClientConfigProperties downstreamSenderConfigProperties = new RequestResponseClientConfigProperties();

    @ConfigurationBuilder(configurationPrefix = "registration")
    RequestResponseClientConfigProperties registrationClientConfigProperties = new RequestResponseClientConfigProperties();

    @ConfigurationBuilder(configurationPrefix = "tenant")
    RequestResponseClientConfigProperties tenantClientConfigProperties = new RequestResponseClientConfigProperties();
}
