package org.rld.grpc.client;

import io.grpc.*;
import io.grpc.stub.StreamObserver;
import org.rld.grpc.routeguide.*;
import org.rld.grpc.util.RouteGuideUtil;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class RouteGuideClient {
    private static final Logger logger = Logger.getLogger(RouteGuideClient.class.getName());
    private final RouteGuideGrpc.RouteGuideBlockingStub blockingStub;
    private final RouteGuideGrpc.RouteGuideStub asyncStub;

    private final Random random = new Random();


    /**
     * Construct client for accessing RouteGuide server using the existing channel.
     */
    public RouteGuideClient(Channel channel) {
        blockingStub = RouteGuideGrpc.newBlockingStub(channel);
        asyncStub = RouteGuideGrpc.newStub(channel);
    }

    /**
     * Blocking unary call example.  Calls getFeature and prints the response.
     */
    public void getFeature(int lat, int lon) {
        info("*** GetFeature: lat={0} lon={1}", lat, lon);

        Point request = Point.newBuilder().setLatitude(lat).setLongitude(lon).build();

        Feature feature;
        try {
            feature = blockingStub.getFeature(request);
        } catch (StatusRuntimeException statusRuntimeException) {
            warning("GetFeature RPC failed: {0}", statusRuntimeException.getStatus());
            return;
        }
        if (RouteGuideUtil.exists(feature)) {
            info("Found feature called \"{0}\" at {1}, {2}",
                    feature.getName(), RouteGuideUtil.getLatitude(feature.getLocation()),
                    RouteGuideUtil.getLongitude(feature.getLocation()));
        } else {
            info("Found no feature at {0}, {1}", RouteGuideUtil.getLatitude(feature.getLocation()),
                    RouteGuideUtil.getLongitude(feature.getLocation()));
        }
    }

    /**
     * Blocking server-streaming example. Calls listFeatures with a rectangle of interest. Prints each
     * response feature as it arrives.
     */
    public void listFeatures(int lowLat, int lowLon, int hiLat, int hiLon) {
        info("*** ListFeature: lowlat:{0} lowlon:{1} hilat:{2} hilon:{3}", lowLat, lowLon, hiLat, hiLon);
        Rectangle request = Rectangle.newBuilder()
                .setLow(Point.newBuilder().setLatitude(lowLat).setLongitude(lowLon).build())
                .setHigh(Point.newBuilder().setLongitude(hiLon).setLatitude(hiLat).build()).build();

        Iterator<Feature> featureList = null;
        try {
            featureList = blockingStub.listFeatures(request);
            for (int i = 0; featureList.hasNext(); i++) {
                Feature feature = featureList.next();
                info("Result #" + i + ": {0}", feature);
            }
        } catch (StatusRuntimeException e) {
            warning("ListFeatures RPC failed: {0}", e.getStatus());
        }
    }

    /**
     * Async client-streaming example. Sends {@code numPoints} randomly chosen points from {@code
     * features} with a variable delay in between. Prints the statistics when they are sent from the
     * server.
     */
    public void recordRoute(List<Feature> featureList, int numPoints) throws InterruptedException {
        info("*** RecordRoute");
        final CountDownLatch finishLatch = new CountDownLatch(1);
        StreamObserver<RouteSummary> responseObserver = new StreamObserver<RouteSummary>() {
            @Override
            public void onNext(RouteSummary routeSummary) {
                info("Finished trip with {0} points. Passed {1} features. " +
                                "Travelled {2} meters. It took {3} seconds.", routeSummary.getPointCount()
                        , routeSummary.getFeaturesCount(), routeSummary.getDistance(), routeSummary.getElapsedTime());
            }

            @Override
            public void onError(Throwable throwable) {
                warning("RecordRoute RPC Failed: {0}", Status.fromThrowable(throwable));
                finishLatch.countDown();
            }

            @Override
            public void onCompleted() {
                info("Finished RecordRoute RPC");
                finishLatch.countDown();
            }
        };

        StreamObserver<Point> requestObserver = asyncStub.recordRoute(responseObserver);
        try {
            // Send numPoints points randomly selected from the features list.
            for (int i = 0; i < numPoints; i++) {
                int index = random.nextInt(featureList.size());
                Point point = featureList.get(index).getLocation();
                info("Visiting point {0}, {1}", RouteGuideUtil.getLatitude(point)
                        , RouteGuideUtil.getLongitude(point));
                requestObserver.onNext(point);

                // Sleep for a bit before sending next Location(Point)
                Thread.sleep(random.nextInt(1000) + 500);
                if (finishLatch.getCount() == 0) {
                    // RPC completed or error occurred before we finished sending.
                    // Sending further requests won't error, but they will just be thrown away.
                    return;
                }
            }
        } catch (RuntimeException e) {
            // Cancel RPC
            requestObserver.onError(e);
            throw e;
        }

        // Mark the end of requests
        requestObserver.onCompleted();

        // Receiving happens asynchronously
        if (!finishLatch.await(1, TimeUnit.MINUTES)) {
            warning("recordRoute can not finish within 1 minutes");
        }
    }

    /**
     * Bi-directional example, which can only be asynchronous. Send some chat messages, and print any
     * chat messages that are sent from the server.
     */
    public CountDownLatch routeChat() {
        info("*** Route Chat");
        final CountDownLatch finishLatch = new CountDownLatch(1);
        StreamObserver<RouteNote> requestObserver = new StreamObserver<RouteNote>() {
            @Override
            public void onNext(RouteNote routeNote) {
                info("Got message '{0}' at {1}, {2}", routeNote.getMessageNote(), routeNote.getLocation().getLatitude(),
                        routeNote.getLocation().getLongitude());
            }

            @Override
            public void onError(Throwable throwable) {
                warning("RouteChat RPC failed: {0}", Status.fromThrowable(throwable));
                finishLatch.countDown();
            }

            @Override
            public void onCompleted() {
                info("RouteChat RPC completed");
                finishLatch.countDown();
            }
        };

        try {
            RouteNote[] requests = {newNote("First Message", 0, 0),
                    newNote("Second Message", 0, 10_000_000),
                    newNote("Third Message", 10_000_000, 0),
                    newNote("Fourth Message", 10_000_000, 10_000_000)};

            for (RouteNote request : requests) {
                info("Sending message: '{0}' at {1} {2}", request.getMessageNote(),
                        request.getLocation().getLatitude(), request.getLocation().getLongitude());
                requestObserver.onNext(request);
            }
        } catch (RuntimeException e) {
            // Cancel RPC
            requestObserver.onError(e);
            throw e;
        }

        // Mark the end of requests
        requestObserver.onCompleted();

        // return the latch while receiving happens asynchronously
        return finishLatch;
    }

    private void info(String msg, Object... params) {
        logger.log(Level.INFO, msg, params);
    }

    private void warning(String msg, Object... params) {
        logger.log(Level.WARNING, msg, params);
    }

    private RouteNote newNote(String message, int lat, int lon) {
        return RouteNote.newBuilder().setMessageNote(message)
                .setLocation(Point.newBuilder().setLatitude(lat).setLongitude(lon).build()).build();
    }

    /** Issues several different requests and then exits. */
    public static void main(String[] args) throws InterruptedException {
        String target = "localhost:8980";
        if (args.length > 0) {
            if ("--help".equals(args[0])) {
                System.err.println("Usage: [target]");
                System.err.println("");
                System.err.println("  target  The server to connect to. Defaults to " + target);
                System.exit(1);
            }
            target = args[0];
        }

        List<Feature> features;
        try {
            features = RouteGuideUtil.parseFeatures(RouteGuideUtil.getDefaultFeaturesFile());
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }


        ManagedChannel channel = Grpc.newChannelBuilder(target, InsecureChannelCredentials.create())
                .build();

        try {
            RouteGuideClient client = new RouteGuideClient(channel);

            // Unary RPC
            // Looking for a valid feature
            client.getFeature(409146138, -746188906);

            // Unary RPC
            // Feature missing.
            client.getFeature(0, 0);

            // Server streaming RPC
            // Looking for features between 40, -75 and 42, -73.
            client.listFeatures(400000000, -750000000, 420000000, -730000000);

            // Client Streaming RPC
            // Record a few randomly selected points from the features file.
            client.recordRoute(features, 10);

            // Send and receive some notes.
            CountDownLatch finishLatch = client.routeChat();

            if (!finishLatch.await(1, TimeUnit.MINUTES)) {
                client.warning("routeChat can not finish within 1 minutes");
            }
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }


}
