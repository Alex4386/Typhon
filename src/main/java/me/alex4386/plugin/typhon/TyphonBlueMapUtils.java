package me.alex4386.plugin.typhon;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;
import java.util.function.Consumer;

import javax.imageio.ImageIO;

import de.bluecolored.bluemap.api.AssetStorage;
import de.bluecolored.bluemap.api.RenderManager;
import org.bukkit.Chunk;
import org.bukkit.World;

import com.flowpowered.math.vector.Vector2i;
import com.flowpowered.math.vector.Vector3d;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.markers.Marker;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.POIMarker;
import me.alex4386.plugin.typhon.volcano.Volcano;
import me.alex4386.plugin.typhon.volcano.log.VolcanoLogClass;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVentStatus;

public class TyphonBlueMapUtils {
  public static boolean isInitialized = false;
  public static boolean bluemapFailNotified = false;
  public static String eruptingImgUrl = null, dormantImgUrl = null;

  public static Map<Volcano, Long> lastRerender = new HashMap<>();
  public static long rerenderExpire = 1000 * 30;

  public static BlueMapAPI getBlueMapAPI() {
    if (TyphonPlugin.blueMap == null) {
      if (!isInitialized) {
        try {
          TyphonPlugin.blueMap = BlueMapAPI.getInstance().get();

          TyphonPlugin.logger.log(VolcanoLogClass.BLUE_MAP, "Bluemap Detected. Integrating...");
          initialize();
        } catch(NoClassDefFoundError | NoSuchElementException e) {
          if (!bluemapFailNotified) {
            TyphonPlugin.logger.error(VolcanoLogClass.BLUE_MAP, "Failed to integrate with Bluemap! " + e.getLocalizedMessage());
            bluemapFailNotified = true;
          }

          return null;
        }
      }
    }
    return TyphonPlugin.blueMap;
  }

  public static void updateChunks(World world, Set<Chunk> chunks) {
    if (!getBlueMapAvailable()) return;

    // make it a Collection of Vector2i.
    Vector2i[] chunkPositions = chunks.stream().filter(chunk -> chunk.getWorld().equals(world)).map(chunk -> new Vector2i(chunk.getX(), chunk.getZ())).toArray(Vector2i[]::new);

    runOnMap(world,
            map -> {
      BlueMapAPI api = getBlueMapAPI();
      if (api != null) {
        RenderManager man = api.getRenderManager();
        if (!map.isFrozen()) {
          man.scheduleMapUpdateTask(map, List.of(chunkPositions), true);
          if (!man.isRunning()) man.start();
        }
      }
    });
  }

  public static boolean reRenderVolcano(Volcano volcano) {
    if (!getBlueMapAvailable()) return false;
    if (lastRerender.containsKey(volcano)) {
        if (System.currentTimeMillis() - lastRerender.get(volcano) < rerenderExpire) return false;
    }

    Set<Chunk> chunks = new HashSet<>();

    for (VolcanoVent vent: volcano.manager.getVents()) {
      // radius
      double radius = vent.longestFlowLength;

      // get the chunks of the volcano from the center of the vent.
      chunks.addAll(TyphonUtils.getChunksInRadius(vent.location.getChunk(), radius));
    }

    updateChunks(volcano.location.getWorld(), chunks);
    return true;
  }

  public static void initialize() {
    isInitialized = true;
    loadTyphonVolcanoes();
  }

  public static void loadTyphonVolcanoes() {
    TyphonPlugin.logger.log(VolcanoLogClass.BLUE_MAP, "Integrating volcanoes to Bluemap");

    for (Map.Entry<String, Volcano> volcanoEntry : TyphonPlugin.listVolcanoes.entrySet()) {
      Volcano volcano = volcanoEntry.getValue();

      TyphonBlueMapUtils.addVolcanoOnMap(volcano);
    }
  }

  public static boolean getBlueMapAvailable() {
    return getBlueMapAPI() != null;
  }

  public static boolean checkIfAssetExists(World world, String id) {
    try {
      AssetStorage storage = getBlueMapAPI().getMap(world.getName()).get().getAssetStorage();
      return storage.assetExists(id);
    } catch (IOException e) {
      return false;
    }
  }

  public static String uploadAndGetURLIfAssetNotExist(World world, String id, byte[] data) {
    AssetStorage storage = getBlueMapAPI().getMap(world.getName()).get().getAssetStorage();
    if (checkIfAssetExists(world, id)) return storage.getAssetUrl(id);

    try {
      OutputStream out = storage.writeAsset(id);
      out.write(data);
      out.flush();
      out.close();

      return storage.getAssetUrl(id);
    } catch (IOException e) {
      return null;
    }
  }

  public static String getEruptingImgUrl(World world) {
    AssetStorage storage = getBlueMapAPI().getMap(world.getName()).get().getAssetStorage();
    String id = "typhon/erupting.png";

    try {
      if (checkIfAssetExists(world, id)) return storage.getAssetUrl(id);

      InputStream eruptingImg = TyphonPlugin.plugin.getResource("icons/erupting.png");
      byte[] data = eruptingImg.readAllBytes();

      return uploadAndGetURLIfAssetNotExist(world, id, data);
    } catch (IOException e) {
      return null;
    }
  }

  public static String getDormantImgUrl(World world) {
    AssetStorage storage = getBlueMapAPI().getMap(world.getName()).get().getAssetStorage();
    String id = "typhon/dormant.png";

    try {
      if (checkIfAssetExists(world, id)) return storage.getAssetUrl(id);

      InputStream eruptingImg = TyphonPlugin.plugin.getResource("icons/dormant.png");
      byte[] data = eruptingImg.readAllBytes();

      return uploadAndGetURLIfAssetNotExist(world, id, data);
    } catch (IOException e) {
      return null;
    }
  }

  public static String getMinorActivityImgUrl(World world) {
    AssetStorage storage = getBlueMapAPI().getMap(world.getName()).get().getAssetStorage();
    String id = "typhon/minor-activity.png";

    try {
      if (checkIfAssetExists(world, id)) return storage.getAssetUrl(id);

      InputStream eruptingImg = TyphonPlugin.plugin.getResource("icons/minor-activity.png");
      byte[] data = eruptingImg.readAllBytes();

      return uploadAndGetURLIfAssetNotExist(world, id, data);
    } catch (IOException e) {
      return null;
    }
  }

  public static String getMajorActivityImgUrl(World world) {
    AssetStorage storage = getBlueMapAPI().getMap(world.getName()).get().getAssetStorage();
    String id = "typhon/major-activity.png";

    try {
      if (checkIfAssetExists(world, id)) return storage.getAssetUrl(id);

      InputStream eruptingImg = TyphonPlugin.plugin.getResource("icons/major-activity.png");
      byte[] data = eruptingImg.readAllBytes();

      return uploadAndGetURLIfAssetNotExist(world, id, data);
    } catch (IOException e) {
      return null;
    }
  }

  public static String getExtinctImgUrl(World world) {
    AssetStorage storage = getBlueMapAPI().getMap(world.getName()).get().getAssetStorage();
    String id = "typhon/extinct.png";

    try {
      if (checkIfAssetExists(world, id)) return storage.getAssetUrl(id);

      InputStream eruptingImg = TyphonPlugin.plugin.getResource("icons/extinct.png");
      byte[] data = eruptingImg.readAllBytes();

      return uploadAndGetURLIfAssetNotExist(world, id, data);
    } catch (IOException e) {
      return null;
    }
  }

  public static String getVolcanoMarkerSetID(Volcano volcano) {
    return "Volcano "+volcano.name;
  }

  public static String getVolcanoVentMarkerID(VolcanoVent vent) {
    return vent.getName();
  }

  public static void addVolcanoOnMap(Volcano volcano) {
    if (!TyphonBlueMapUtils.getBlueMapAvailable()) return;
    runOnMap(volcano, map -> {
      TyphonPlugin.logger.log(VolcanoLogClass.BLUE_MAP, "Adding volcano "+volcano.name+" on map.");
      MarkerSet volcanoSet = getVolcanoMarkers(volcano);

      map.getMarkerSets().put(getVolcanoMarkerSetID(volcano), volcanoSet);
    });
  }

  public static void removeVolcanoFromMap(Volcano volcano) {
    if (!TyphonBlueMapUtils.getBlueMapAvailable()) return;
    runOnMap(volcano, map -> {
      TyphonPlugin.logger.log(VolcanoLogClass.BLUE_MAP, "Removing volcano "+volcano.name+" on map.");
      map.getMarkerSets().remove(getVolcanoMarkerSetID(volcano));
    });
  }

  public static MarkerSet getVolcanoMarkers(Volcano volcano) {
    if (!TyphonBlueMapUtils.getBlueMapAvailable()) return null;
    final MarkerSet volcanoMarkerSet = MarkerSet.builder().label(volcano.name+" volcano").build();

    for (VolcanoVent vent: volcano.manager.getVents()) {
      addVolcanoVentToMarkerSet(volcanoMarkerSet, vent);
    }

    return volcanoMarkerSet;
  }

  public static void addVolcanoVentOnMap(VolcanoVent vent) {
    if (!TyphonBlueMapUtils.getBlueMapAvailable()) return;
    runOnMap(vent, map -> {
      TyphonPlugin.logger.log(VolcanoLogClass.BLUE_MAP, "Adding vent "+vent.getName()+" of "+vent.volcano.name+" on map.");

      // get vent set
      String markerSetID = getVolcanoMarkerSetID(vent.volcano);
      MarkerSet set = map.getMarkerSets().get(markerSetID);
      if (set != null) {
        set.getMarkers().put(getVolcanoVentMarkerID(vent), getVolcanoVentMarker(vent));
      }
    });
  }


  public static String getIconURLByStatus(VolcanoVent vent) {
    return getIconURLByStatus(vent.location.getWorld(), vent.getStatus());
  }

  public static String getIconURLByStatus(World world, VolcanoVentStatus status) {
    if (status == VolcanoVentStatus.ERUPTING) {
      return getEruptingImgUrl(world);
    } else if (status == VolcanoVentStatus.MAJOR_ACTIVITY || status == VolcanoVentStatus.ERUPTION_IMMINENT) {
      return getMajorActivityImgUrl(world);
    } else if (status == VolcanoVentStatus.MINOR_ACTIVITY) {
      return getMinorActivityImgUrl(world);
    } else if (status == VolcanoVentStatus.DORMANT) {
      return getDormantImgUrl(world);
    } else {
      return getExtinctImgUrl(world);
    }
  }

  public static POIMarker getVolcanoVentMarker(VolcanoVent vent) {
    if (!TyphonBlueMapUtils.getBlueMapAvailable()) return null;
    Vector3d v3d = Vector3d.from(vent.location.getX(), vent.getSummitBlock().getY(), vent.location.getZ());

    POIMarker ventMarker = POIMarker.builder()
      .label(vent.isMainVent() ? vent.volcano.name+" Volcano" : vent.name+" ("+vent.volcano.name+")")
      .icon(getIconURLByStatus(vent), TyphonBlueMapUtils.getIconSize())
      .position(v3d)
      .build();

    return ventMarker;
  }

  public static void updateVolcanoVentMarkerHeight(VolcanoVent vent) {
    TyphonBlueMapUtils.runOnVolcanoVentMarker(vent, marker -> {
      marker.setPosition(new Vector3d(vent.location.getX(), vent.getSummitBlock().getY(), vent.location.getZ()));
    });
  }

  public static void addVolcanoVentToMarkerSet(MarkerSet set, VolcanoVent vent) {
    if (!TyphonBlueMapUtils.getBlueMapAvailable()) return;
    TyphonPlugin.logger.log(VolcanoLogClass.BLUE_MAP, "Adding vent "+vent.getName()+" of "+vent.volcano.name+" on map.");
    String markerId = getVolcanoVentMarkerID(vent);

    set.getMarkers().put(markerId, getVolcanoVentMarker(vent));
  }

  public static void runOnVolcanoVentMarker(VolcanoVent vent, Consumer<? super POIMarker> run) {
    runOnMap(vent, map -> {
      String markerSetID = getVolcanoMarkerSetID(vent.volcano);
      MarkerSet set = map.getMarkerSets().get(markerSetID);
      if (set != null) {
        Marker marker = set.getMarkers().get(getVolcanoVentMarkerID(vent));
        if (marker instanceof POIMarker) {
          POIMarker realMarker = (POIMarker) marker;
          run.accept(realMarker);
        }
      }
    });
  }

  public static void removeVolcanoVentMarker(VolcanoVent vent) {
    runOnVolcanoVentMarker(vent, marker -> {
      runOnMap(vent, map -> {
        String markerSetID = getVolcanoMarkerSetID(vent.volcano);
        MarkerSet set = map.getMarkerSets().get(markerSetID);
        if (set != null) {
          set.getMarkers().remove(getVolcanoVentMarkerID(vent));
        }
      });
    });
  }

  public static Vector2i getIconSize() {
    return new Vector2i(18, 18);
  }

  public static void updateVolcanoVentIcon(VolcanoVent vent) {
    runOnVolcanoVentMarker(vent, marker -> {
      marker.setIcon(getIconURLByStatus(vent), TyphonBlueMapUtils.getIconSize());
    });
  }

  public static void runOnMap(World bukkitWorld, Consumer<? super BlueMapMap> run) {
    if (!getBlueMapAvailable()) return;
    getBlueMapAPI().getWorld(bukkitWorld.getUID()).ifPresent(world -> world.getMaps().forEach(run));
  }

  public static void runOnMap(Volcano volcano, Consumer<? super BlueMapMap> run) {
    runOnMap(volcano.location.getWorld(), run);
  }
  
  public static void runOnMap(VolcanoVent vent, Consumer<? super BlueMapMap> run) {
    runOnMap(vent.location.getWorld(), run);
  }

}
