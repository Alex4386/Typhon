package me.alex4386.plugin.typhon;

import java.io.IOException;
import java.io.InputStream;
import java.util.function.Consumer;

import javax.imageio.ImageIO;

import org.bukkit.World;

import com.flowpowered.math.vector.Vector2i;
import com.flowpowered.math.vector.Vector3d;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.markers.Marker;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.POIMarker;
import me.alex4386.plugin.typhon.volcano.Volcano;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVentStatus;

public class TyphonBlueMapUtils {
  public static String eruptingImgUrl = null, dormantImgUrl = null;
  public static Vector2i iconSize = new Vector2i(18, 18);

  public static BlueMapAPI getBlueMapAPI() {
    return TyphonPlugin.blueMap;
  }

  public static boolean getBlueMapAvailable() {
    return getBlueMapAPI() != null;
  }

  public static void loadImages() {
    loadImages(getBlueMapAPI());
  }

  public static void loadImages(BlueMapAPI api) {
    try {
      InputStream eruptingImg = TyphonPlugin.plugin.getResource("volcano_erupting.png");
      if (eruptingImg != null) {
        eruptingImgUrl = api.getWebApp().createImage(ImageIO.read(eruptingImg), "typhon/vol_erupt");
      }
    } catch(IOException e) {
      
    }

    try {
      InputStream dormantImg = TyphonPlugin.plugin.getResource("volcano_erupting.png");
      if (dormantImg != null) {
        dormantImgUrl = api.getWebApp().createImage(ImageIO.read(dormantImg), "typhon/vol_dormant");
      }
    } catch(IOException e) {
      
    }
  }

  public static String getEruptingImgUrl() {
    if (eruptingImgUrl == null) loadImages();
    return eruptingImgUrl;
  }

  public static String getDormantImgUrl() {
    if (dormantImgUrl == null) loadImages();
    return dormantImgUrl;
  }

  public static String getVolcanoMarkerSetID(Volcano volcano) {
    return "typhon:vol:"+volcano.name;
  }

  public static String getVolcanoVentMarkerID(VolcanoVent vent) {
    return getVolcanoMarkerSetID(vent.volcano)+":"+vent.getName();
  }

  public static void addVolcanoOnMap(Volcano volcano) {
    runOnMap(volcano, map -> {
      MarkerSet volcanoSet = getVolcanoMarkers(volcano);

      map.getMarkerSets().put(getVolcanoMarkerSetID(volcano), volcanoSet);
    });
  }

  public static void removeVolcanoFromMap(Volcano volcano) {
    runOnMap(volcano, map -> {
      map.getMarkerSets().remove(getVolcanoMarkerSetID(volcano));
    });
  }

  public static MarkerSet getVolcanoMarkers(Volcano volcano) {
    final MarkerSet volcanoMarkerSet = MarkerSet.builder().label(volcano.name+" volcano").build();

    for (VolcanoVent vent: volcano.manager.getVents()) {
      addVolcanoVentToMarkerSet(volcanoMarkerSet, vent);
    }

    return volcanoMarkerSet;
  }


  public static String getIconURLByStatus(VolcanoVent vent) {
    return getIconURLByStatus(vent.getStatus());
  }

  public static String getIconURLByStatus(VolcanoVentStatus status) {
    if (status.getScaleFactor() >= VolcanoVentStatus.ERUPTING.getScaleFactor()) {
      return getEruptingImgUrl();
    } else {
      return getDormantImgUrl();
    }
  }

  public static POIMarker getVolcanoVentMarker(VolcanoVent vent) {
    Vector3d v3d = Vector3d.from(vent.location.getX(), vent.location.getY(), vent.location.getZ());

    POIMarker ventMarker = POIMarker.toBuilder()
      .label(vent.isMainVent() ? vent.volcano.name+" Volcano" : vent.name+" ("+vent.volcano.name+")")
      .icon(getIconURLByStatus(vent), iconSize)
      .position(v3d)
      .build();

    return ventMarker;
  }

  public static void addVolcanoVentToMarkerSet(MarkerSet set, VolcanoVent vent) {
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

  public static void updateVolcanoVentIcon(VolcanoVent vent) {
    runOnVolcanoVentMarker(vent, marker -> {
      marker.setIcon(getIconURLByStatus(vent), iconSize);
    });
  }

  public static void runOnMap(World bukkitWorld, Consumer<? super BlueMapMap> run) {
    getBlueMapAPI().getWorld(bukkitWorld.getUID()).ifPresent(world -> world.getMaps().forEach(run));
  }

  public static void runOnMap(Volcano volcano, Consumer<? super BlueMapMap> run) {
    runOnMap(volcano.location.getWorld(), run);
  }
  
  public static void runOnMap(VolcanoVent vent, Consumer<? super BlueMapMap> run) {
    runOnMap(vent.location.getWorld(), run);
  }

}
