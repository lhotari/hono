package org.eclipse.hono.adapter.http.micro;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.StreamSupport;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Inject;

import org.eclipse.hono.adapter.http.impl.VertxBasedHttpProtocolAdapter;
import org.eclipse.hono.client.CredentialsClientFactory;
import org.eclipse.hono.service.HealthCheckServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Context;
import io.micronaut.runtime.Micronaut;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;

@Context
public class Application {

    private static final Logger LOG = LoggerFactory.getLogger(Application.class);

    @Inject
    Optional<HealthCheckServer> healthCheckServer;

    @PostConstruct
    void start(final ApplicationContext ctx, final HonoConfig config, final Vertx vertx) {
        LOG.info("deploying {} HTTP adapter instances ...", config.applicationConfigProperties.getMaxInstances());
        final CompletableFuture<Void> startup = new CompletableFuture<>();
        final Promise<String> deploymentTracker = Promise.promise();
        vertx.deployVerticle(
                () -> ctx.getBean(VertxBasedHttpProtocolAdapter.class),
                new DeploymentOptions().setInstances(config.applicationConfigProperties.getMaxInstances()),
                deploymentTracker);
        deploymentTracker.future()
            .compose(s -> healthCheckServer.map(server -> server.start())
                        .orElse(Future.succeededFuture()))
            .onSuccess(ok -> startup.complete(null))
            .onFailure(t -> startup.completeExceptionally(t));
        startup.join();
    }

    @PreDestroy
    public void shutdown(final Vertx vertx) {
        LOG.info("shutting down HTTP adapter");
        final CompletableFuture<Void> shutdown = new CompletableFuture<>();
        healthCheckServer.map(server -> server.stop())
            .orElse(Future.succeededFuture())
            .onComplete(ok -> {
                vertx.close(attempt -> {
                    if (attempt.succeeded()) {
                        shutdown.complete(null);
                    } else {
                        shutdown.completeExceptionally(attempt.cause());
                    }
                });
            });
        shutdown.join();
    }

    public static void main(String[] args) {
        final ApplicationContext ctx = Micronaut.run(Application.class);
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                ctx.stop();
            }
        });
    }
}