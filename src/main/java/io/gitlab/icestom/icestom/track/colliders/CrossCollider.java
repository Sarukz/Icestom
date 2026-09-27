package io.gitlab.icestom.icestom.track.colliders;

import io.gitlab.icestom.icestom.instance.TrackInstance;
import net.minestom.server.coordinate.Vec;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public interface CrossCollider {
    default Map<TrackInstance.TickLocation, Long> detectCrosses(List<Vec> before, SequencedCollection<TrackInstance.TickLocation> now) {
        Map<TrackInstance.TickLocation, Long> deltas = new HashMap<>();

        int index = 0;
        for (TrackInstance.TickLocation b : now) {
            deltas.put(b, null);

            @Nullable Vec a = before.get(index++);
            @Nullable Long tick_delta = detectCross(a, b);

            if (tick_delta != null) {
                deltas.put(b, tick_delta);
            }
        }

        return deltas;
    }

    @Nullable Long detectCross(Vec before, TrackInstance.TickLocation now);
}

