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

package org.eclipse.hono.deviceregistry.jdbc.impl;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.hono.auth.SpringBasedHonoPasswordEncoder;
import org.eclipse.hono.deviceregistry.jdbc.config.DeviceServiceProperties;
import org.eclipse.hono.deviceregistry.jdbc.config.TenantServiceProperties;
import org.eclipse.hono.service.base.jdbc.config.JdbcProperties;
import org.eclipse.hono.service.base.jdbc.store.device.DeviceStores;
import org.eclipse.hono.service.base.jdbc.store.tenant.Stores;
import org.eclipse.hono.service.credentials.CredentialsService;
import org.eclipse.hono.service.management.credentials.CredentialsManagementService;
import org.eclipse.hono.service.management.device.DeviceManagementService;
import org.eclipse.hono.service.management.tenant.TenantManagementService;
import org.eclipse.hono.service.registration.RegistrationService;
import org.eclipse.hono.service.tenant.TenantService;
import org.h2.Driver;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;

import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.noop.NoopSpan;
import io.opentracing.noop.NoopTracerFactory;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import org.testcontainers.containers.MSSQLServerContainer;

@ExtendWith(VertxExtension.class)
abstract class AbstractJdbcRegistryTest {
    enum DatabaseType {
        H2,
        SQLSERVER
    }
    private static final DatabaseType DATABASE_TYPE = DatabaseType.valueOf(System.getProperty(AbstractJdbcRegistryTest.class.getSimpleName() + ".databaseType", "H2").toUpperCase());
    private static final MSSQLServerContainer<?> MSSQL_SERVER_CONTAINER = DATABASE_TYPE == DatabaseType.SQLSERVER ? new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2017-CU12") : null;
    private static final AtomicLong MSSQL_UNIQUE_ID = new AtomicLong(System.currentTimeMillis());

    protected static final Span SPAN = NoopSpan.INSTANCE;

    private static final Tracer TRACER = NoopTracerFactory.create();
    private static final Path EXAMPLE_SQL_BASE = Path.of("..", "base-jdbc", "src", "main", "sql", DATABASE_TYPE.name().toLowerCase());

    private static final Path BASE_DIR = Path.of("target/data").toAbsolutePath();

    protected CredentialsService credentialsAdapter;
    protected CredentialsManagementService credentialsManagement;
    protected RegistrationService registrationAdapter;
    protected DeviceManagementService registrationManagement;

    protected TenantService tenantAdapter;
    protected TenantManagementService tenantManagement;

    @BeforeEach
    void startDevices(final Vertx vertx) throws IOException, SQLException {
        final var jdbc = resolveJdbcProperties();

        try (
                var connection = DriverManager.getConnection(jdbc.getUrl(), jdbc.getUsername(), jdbc.getPassword());
                var script = Files.newBufferedReader(EXAMPLE_SQL_BASE.resolve("create.devices.sql"))
        ) {
            // pre-create database
            RunScript.execute(connection, script);
        }

        final var properties = new DeviceServiceProperties();

        this.credentialsAdapter = new CredentialsServiceImpl(
                DeviceStores.adapterStoreFactory().createTable(vertx, TRACER, jdbc, Optional.empty(), Optional.empty(), Optional.empty()),
                properties
        );
        this.registrationAdapter = new RegistrationServiceImpl(
                DeviceStores.adapterStoreFactory().createTable(vertx, TRACER, jdbc, Optional.empty(), Optional.empty(), Optional.empty())
        );

        this.credentialsManagement = new CredentialsManagementServiceImpl(
                vertx,
                new SpringBasedHonoPasswordEncoder(properties.getMaxBcryptCostfactor()),
                DeviceStores.managementStoreFactory().createTable(vertx, TRACER, jdbc, Optional.empty(), Optional.empty(), Optional.empty()),
                properties
        );
        this.registrationManagement = new DeviceManagementServiceImpl(
                DeviceStores.managementStoreFactory().createTable(vertx, TRACER, jdbc, Optional.empty(), Optional.empty(), Optional.empty()),
                properties
        );

    }

    private JdbcProperties resolveJdbcProperties() {
        final var jdbc = new JdbcProperties();
        switch (DATABASE_TYPE) {
            case H2:
                jdbc.setDriverClass(Driver.class.getName());
                jdbc.setUrl("jdbc:h2:" + BASE_DIR.resolve(UUID.randomUUID().toString()).toAbsolutePath());
                break;
            case SQLSERVER:
                MSSQL_SERVER_CONTAINER.start();
                jdbc.setDriverClass("com.microsoft.sqlserver.jdbc.SQLServerDriver");
                jdbc.setUrl(MSSQL_SERVER_CONTAINER.getJdbcUrl());
                jdbc.setUsername(MSSQL_SERVER_CONTAINER.getUsername());
                jdbc.setPassword(MSSQL_SERVER_CONTAINER.getPassword());
                createNewPerTestSchemaAndUser(jdbc);
                break;
            default:
                throw new UnsupportedOperationException(DATABASE_TYPE.name() + " is not supported.");
        }
        try {
            Class.forName(jdbc.getDriverClass());
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Cannot load JDBC driver class", e);
        }
        return jdbc;
    }

    void createNewPerTestSchemaAndUser(JdbcProperties jdbc) {
        String schemaName = "test" + MSSQL_UNIQUE_ID.incrementAndGet();
        String userName = "user" + MSSQL_UNIQUE_ID.incrementAndGet();
        String sql = "create login " + userName + " with password='" + jdbc.getPassword() + "';\n" +
                "create schema " + schemaName + ";\n" +
                "create user " + userName + " for login " + userName + " with default_schema = " + schemaName + ";\n" +
                "exec sp_addrolemember 'db_owner', '" + userName + "';\n";
        try (
                var connection = DriverManager.getConnection(jdbc.getUrl(), jdbc.getUsername(), jdbc.getPassword());
                var script = new StringReader(sql)
        ) {
            RunScript.execute(connection, script);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        jdbc.setUsername(userName);
    }

    @BeforeEach
    void startTenants(final Vertx vertx) throws IOException, SQLException {
        final var jdbc = resolveJdbcProperties();

        try (
                var connection = DriverManager.getConnection(jdbc.getUrl(), jdbc.getUsername(), jdbc.getPassword());
                var script = Files.newBufferedReader(EXAMPLE_SQL_BASE.resolve("create.tenants.sql"))
        ) {
            // pre-create database
            RunScript.execute(connection, script);
        }

        this.tenantAdapter = new TenantServiceImpl(
                Stores.adapterStore(vertx, TRACER, jdbc),
                new TenantServiceProperties()
        );

        this.tenantManagement = new TenantManagementServiceImpl(
                Stores.managementStore(vertx, TRACER, jdbc)
        );
    }

    public RegistrationService getRegistrationService() {
        return this.registrationAdapter;
    }

    public DeviceManagementService getDeviceManagementService() {
        return this.registrationManagement;
    }

    public TenantService getTenantService() {
        return this.tenantAdapter;
    }

    public TenantManagementService getTenantManagementService() {
        return this.tenantManagement;
    }

    public CredentialsService getCredentialsService() {
        return this.credentialsAdapter;
    }

    public CredentialsManagementService getCredentialsManagementService() {
        return this.credentialsManagement;
    }
}
