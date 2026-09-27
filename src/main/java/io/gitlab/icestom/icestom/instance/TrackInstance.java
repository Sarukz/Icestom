package io.gitlab.icestom.icestom.instance;

import io.github.openboatutils.protocol.OBUPacket;
import io.github.openboatutils.protocol.channels.OBUContextPacket;
import io.github.openboatutils.protocol.channels.OBUSettingsPacket;
import io.gitlab.icestom.icestom.IceStom;
import io.gitlab.icestom.icestom.entity.Boat;
import io.gitlab.icestom.icestom.entity.IceStomPlayer;
import io.gitlab.icestom.icestom.timetrial.lap.TimedLap;
import io.gitlab.icestom.icestom.track.Track;
import io.gitlab.icestom.icestom.track.colliders.CrossCollider;
import io.gitlab.icestom.icestom.track.colliders.InsideCollider;
import io.gitlab.icestom.icestom.track.library.TrackLibrary;
import io.gitlab.icestom.icestom.util.BatchQueue;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.text.Component;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.Player;
import net.minestom.server.event.instance.InstanceRegisterEvent;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.event.player.PlayerPacketEvent;
import net.minestom.server.instance.LightingChunk;
import net.minestom.server.network.packet.client.play.ClientVehicleMovePacket;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static io.gitlab.icestom.icestom.openboatutils.OpenBoatUtilsManager.writePacket;
import static io.gitlab.icestom.icestom.util.DisplayEntityConverter.*;

@SuppressWarnings("UnstableApiUsage")
public abstract class TrackInstance extends BoatInstance implements SpawnLocation {

    private static final Logger log = LoggerFactory.getLogger(TrackInstance.class);

    protected final Track track;
    private final TrackLibrary.Ticket ticket;

    private final BatchQueue<TickLocation> movementBatchQueue = new BatchQueue<>();
    private final Map<UUID, Vec> lastTickPositions = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> ticks = new ConcurrentHashMap<>();

    private final Set<String> subscribedRegions = new HashSet<>();
    private final Set<String> subscribedTriggers = new HashSet<>();

    private Set<InsideCollider> watchingRegions = Set.of();
    private Set<CrossCollider> watchingTriggers = Set.of();

    protected TrackInstance(TrackLibrary.Ticket ticket, Track track) {
        super(UUID.randomUUID(), track.getMapContainer());

        this.ticket = ticket;
        this.track = track;

        setChunkSupplier(LightingChunk::new);

        eventNode().addListener(PlayerBlockBreakEvent.class, event -> event.setCancelled(true));
        eventNode().addListener(PlayerBlockPlaceEvent.class, event -> event.setCancelled(true));

        eventNode().addListener(InstanceRegisterEvent.class, event -> {
            track.getMapContainer().onNewDisplayEntity(nbt -> {
                String id = nbt.getString("id");

                Entity entity = switch (id) {
                    case "minecraft:block_display" -> loadBlockDisplay(nbt);
                    case "minecraft:item_display" -> loadItemDisplay(nbt);
                    case "minecraft:text_display" -> loadTextDisplay(nbt);
                    default -> null;
                };

                if (entity == null) return;

                ListBinaryTag pos = nbt.getList("Pos");

                double x = pos.getDouble(0);
                double y = pos.getDouble(1);
                double z = pos.getDouble(2);

                entity.setInstance(this, new Pos(x, y, z));
            });
        });

        eventNode().addListener(PlayerPacketEvent.class, event -> {
            final Player player = event.getPlayer();

            if (!shouldTrackPlayer(player)) return;
            if(!(player.getVehicle() instanceof Boat)) return;
            if (!(event.getPacket() instanceof ClientVehicleMovePacket(Pos position, boolean onGround))) return;

            final UUID uuid = player.getUuid();

            int tick = ticks.merge(uuid, 1, Integer::sum);

            log.info("{} {}", player.getUsername(), tick);

            movementBatchQueue.add(new TickLocation(
                    player.getUuid(),
                    tick,
                    position.asVec(),
                    position.yaw()
            ));
        });
    }

    @Override
    public @NonNull String getDimensionName() {
        return getInstanceContainer().getDimensionName();
    }

    public long getPlayerTick(UUID player) {
        return ticks.getOrDefault(player, 0);
    }

    @Override
    public void tick(long time) {
        super.tick(time);

        if (!movementBatchQueue.isEmpty()) {
            List<TickLocation> movements = movementBatchQueue.drain();

            handleMovements(movements);
        }
    }

    @Override
    public void resetPlayer(Player player) {
        SpawnLocation.super.resetPlayer(player);

        if (!track.getOpenBoatUtilsPackets().isEmpty()) {
            if (((IceStomPlayer) player).getOpenBoatUtilsVersion() == null) {
                player.sendMessage(Component.translatable("message.timetrial.requires_open_boat_utils"));
                drop(player);
                IceStom.getInstance().getSpawnInstance().consume(player);
            } else {
                try {
                    List<OBUSettingsPacket> packets = new ArrayList<>();
                    packets.add(new OBUSettingsPacket.Reset());
                    packets.addAll(track.getOpenBoatUtilsPackets());

                    OBUPacket compound = new OBUSettingsPacket.Compound(new OBUSettingsPacket.CompoundPayload(packets));

                    player.sendPacket(writePacket(compound));
                } catch (IOException _) {}
            }
        }
    }

    @Override
    public void drop(Player player) {
        removeBoat(player);
        try {
            player.sendPacket(writePacket(new OBUContextPacket.Reset()));
        } catch (IOException _) {}
    }

    protected abstract void handleMovements(List<TickLocation> movements);
    protected abstract boolean shouldTrackPlayer(Player player);

    public TrackLibrary.Ticket getTicket() {
        return ticket;
    }

    public Track getTrack() {
        return track;
    }

    private void updateWatchedRegions() {
        Map<InsideCollider, Set<String>> colliders = new HashMap<>(track.getRegions());

        colliders.entrySet().removeIf(entry -> {
            for (String tag: entry.getValue()) {
                if (subscribedRegions.contains(tag)) return false;
            }

            return true;
        });

        watchingRegions = colliders.keySet();
    }

    private void updateWatchedTriggers() {
        Map<CrossCollider, Set<String>> colliders = new HashMap<>(track.getTriggers());

        colliders.entrySet().removeIf(entry -> {
            for (String tag: entry.getValue()) {
                if (subscribedTriggers.contains(tag)) return false;
            }

            return true;
        });

        watchingTriggers = colliders.keySet();
    }

    public void subscribeRegionId(String id) {
        this.subscribedRegions.add(id);
        updateWatchedRegions();
    }

    public boolean unsubscribeRegionId(String id) {
        boolean removed = this.subscribedRegions.remove(id);
        updateWatchedRegions();
        return removed;
    }

    public void subscribeTriggerId(String id) {
        this.subscribedTriggers.add(id);
        updateWatchedTriggers();
    }

    public boolean unsubscribeTriggerId(String id) {
        boolean removed = this.subscribedTriggers.remove(id);
        updateWatchedRegions();
        return removed;
    }

    public static void tickResetRegions(
            TrackInstance instance,
            Player player,
            Set<String> playerRegions,
            Map<String, Long> playerTriggers,
            TimedLap lap
    ) {
        boolean hitResetRegion = playerRegions != null && playerRegions.contains("icestom.reset");
        boolean hitResetTrigger = playerTriggers != null && playerTriggers.containsKey("icestom.reset");

        if (hitResetRegion || hitResetTrigger) {
            Track track = instance.getTrack();
            Pos reset_point = track.getLocations().getOrDefault("icestom.reset_" + lap.getLastReachedCheckpoint(), track.getSpawnLocation());

            instance.createBoat(player, reset_point);
        }
    }

    public record TickLocation(
            UUID player,
            long tick,
            Vec pos,
            float yaw
    ) {}
}
