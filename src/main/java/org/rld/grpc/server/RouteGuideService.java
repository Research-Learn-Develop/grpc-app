package org.rld.grpc.server;

import io.grpc.stub.StreamObserver;
import org.rld.grpc.routeguide.*;
import org.rld.grpc.util.RouteGuideUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.lang.Math.*;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

public class RouteGuideService extends RouteGuideGrpc.RouteGuideImplBase {

    private static final Logger logger = Logger.getLogger(RouteGuideService.class.getName());

    private final Collection<Feature> features;
    private final ConcurrentMap<Point, List<RouteNote>> routeNotes =
            new ConcurrentHashMap<Point, List<RouteNote>>();

    public RouteGuideService(Collection<Feature> features) {
        this.features = features;
    }

    @Override
    public void getFeature(Point request, StreamObserver<Feature> responseObserver) {
        logger.info("Request GetFeature Received");
        responseObserver.onNext(checkFeature(request));
        responseObserver.onCompleted();
    }

    private Feature checkFeature(Point location) {
        for (Feature feature : features) {
            if (feature.getLocation().getLatitude() == location.getLatitude()
                    && feature.getLocation().getLongitude() == location.getLongitude()) {
                return feature;
            }
        }
        return Feature.newBuilder().setName("").setLocation(location).build();
    }


    @Override
    public void listFeatures(Rectangle request, StreamObserver<Feature> responseObserver) {
        logger.info("Request ListFeatures Received");
        int left = min(request.getLow().getLongitude(), request.getHigh().getLongitude());
        int right = max(request.getLow().getLongitude(), request.getHigh().getLongitude());
        int top = max(request.getLow().getLatitude(), request.getHigh().getLatitude());
        int bottom = min(request.getLow().getLatitude(), request.getHigh().getLatitude());
        for (Feature feature : features) {
            if (!RouteGuideUtil.exists(feature)) {
                continue;
            }
            int lat = feature.getLocation().getLatitude();
            int lon = feature.getLocation().getLongitude();
            if (lon >= left && lon <= right && lat >= bottom && lat <= top) {
                responseObserver.onNext(feature);
            }
        }
        responseObserver.onCompleted();
    }


    @Override
    public StreamObserver<Point> recordRoute(final StreamObserver<RouteSummary> responseObserver) {
        logger.info("Request recordRoute Received");
        return new StreamObserver<Point>() {
            int pointCount;
            int featureCount;
            int distance;
            Point previous;
            final long startTime = System.nanoTime();

            @Override
            public void onNext(Point point) {
                logger.info("onNext recordRoute");
                pointCount++;
                if (RouteGuideUtil.exists(checkFeature(point))) {
                    featureCount++;
                }
                // For each point after the first, add the incremental distance from the previous point
                // to the total distance value.
                if (previous != null) {
                    distance += calcDistance(previous, point);
                }
                previous = point;
            }

            @Override
            public void onError(Throwable throwable) {
                logger.log(Level.WARNING, "Encountered error in recordRoute", throwable);
            }

            @Override
            public void onCompleted() {
                long seconds = NANOSECONDS.toSeconds(System.nanoTime() - startTime);
                responseObserver.onNext(RouteSummary.newBuilder().setPointCount(pointCount)
                        .setFeaturesCount(featureCount).setDistance(distance)
                        .setElapsedTime((int) seconds).build());
                responseObserver.onCompleted();
            }
        };

    }

    private static int calcDistance(Point start, Point end) {
        int r = 6371000; // earth radius in meters
        double lat1 = toRadians(RouteGuideUtil.getLatitude(start));
        double lat2 = toRadians(RouteGuideUtil.getLatitude(end));
        double lon1 = toRadians(RouteGuideUtil.getLongitude(start));
        double lon2 = toRadians(RouteGuideUtil.getLongitude(end));
        double deltaLat = lat2 - lat1;
        double deltaLon = lon2 - lon1;

        double a = sin(deltaLat / 2) * sin(deltaLat / 2)
                + cos(lat1) * cos(lat2) * sin(deltaLon / 2) * sin(deltaLon / 2);
        double c = 2 * atan2(sqrt(a), sqrt(1 - a));

        return (int) (r * c);
    }


    //Bi-Directional streaming RPC
    @Override
    public StreamObserver<RouteNote> routeChat(final StreamObserver<RouteNote> responseObserver) {
        logger.info("Request routeChat Received");
        return new StreamObserver<RouteNote>() {
            @Override
            public void onNext(RouteNote routeNote) {
                List<RouteNote> notes = getOrCreateNotes(routeNote.getLocation());

                // Respond with all previous notes at this location.
                for (RouteNote prevNote : notes.toArray(new RouteNote[0])) {
                    responseObserver.onNext(prevNote);
                }

                // Now add the new note to the list
                notes.add(routeNote);
            }

            @Override
            public void onError(Throwable throwable) {
                logger.log(Level.WARNING, "Encountered error in routeChat", throwable);
            }

            @Override
            public void onCompleted() {
                responseObserver.onCompleted();
            }
        };
    }

    /**
     * Get the notes list for the given location. If missing, create it.
     */
    private List<RouteNote> getOrCreateNotes(Point location) {
        List<RouteNote> notes = Collections.synchronizedList(new ArrayList<RouteNote>());
        List<RouteNote> prevNotes = routeNotes.putIfAbsent(location, notes);
        return prevNotes != null ? prevNotes : notes;
    }


}
