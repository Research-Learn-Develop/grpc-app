package org.rld.grpc.server;

import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.rld.grpc.routeguide.Feature;
import org.rld.grpc.util.RouteGuideUtil;

import java.io.IOException;
import java.net.URL;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class RouteGuideServer {

    private static final Logger logger = Logger.getLogger(RouteGuideServer.class.getName());

    private final int port;
    private final Server server;

    public RouteGuideServer(int port) throws IOException {
        this(port, RouteGuideUtil.getDefaultFeaturesFile());
    }

    /** Create a RouteGuide server listening on {@code port} using {@code featureFile} database. */
    public RouteGuideServer(int port, URL featureFile) throws IOException {
        this(Grpc.newServerBuilderForPort(port, InsecureServerCredentials.create()),
                port, RouteGuideUtil.parseFeatures(featureFile));
    }

    /** Create a RouteGuide server using serverBuilder as a base and features as data. */
    public RouteGuideServer(ServerBuilder<?> serverBuilder, int port, Collection<Feature> features) {
        this.port = port;
        server = serverBuilder.addService(new RouteGuideService(features))
                .build();
    }


    public void start() throws IOException {

        logger.log(Level.INFO,"Starting the RouteGuide GRPC server on port:" + port);
        server.start();
        logger.info("Server started, listening on " + port);

        Runtime.getRuntime().addShutdownHook(new Thread(){
            @Override
            public void run(){
                // Use stderr here since the logger may have been reset by its JVM shutdown hook.
                System.err.println("*** shutting down gRPC server since JVM is shutting down");
                try {
                    RouteGuideServer.this.stop();
                } catch (InterruptedException e) {
                    e.printStackTrace(System.err);
                }
                System.err.println("*** RouteGuide GRPC server is shut down");
            }
        });
    }

    /** Stop serving requests and shutdown resources. */
    public void stop() throws InterruptedException {
        if(server != null){
            server.shutdown().awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    /**
     * Await termination on the main thread since the grpc library uses daemon threads.
     */
    private void blockUntilShutdown() throws InterruptedException {
        if (server != null) {
            server.awaitTermination();
        }
    }

    /**
     * Main method.  This comment makes the linter happy.
     */
    public static void main(String args[]) throws Exception {
        RouteGuideServer routeGuideServer = new RouteGuideServer(8980);
        routeGuideServer.start();
        routeGuideServer.blockUntilShutdown();
    }


}
