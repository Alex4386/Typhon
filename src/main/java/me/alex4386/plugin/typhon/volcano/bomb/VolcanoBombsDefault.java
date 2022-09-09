package me.alex4386.plugin.typhon.volcano.bomb;

import org.json.simple.JSONObject;

public class VolcanoBombsDefault {
    public static float minBombPower = 2.0f;
    public static float maxBombPower = 4.0f;

    public static int minBombRadius = 1;
    public static int maxBombRadius = 3;

    public static int bombDelay = 20;

    public static void importConfig(JSONObject configData) {
        VolcanoBombs bombs = new VolcanoBombs(null);
        bombs.importConfig(configData);

        minBombPower = bombs.minBombPower;
        maxBombPower = bombs.maxBombPower;
        minBombRadius = bombs.minBombRadius;
        maxBombRadius = bombs.maxBombRadius;
        bombDelay = bombs.bombDelay;

        bombs = null;
    }

    public static JSONObject exportConfig() {
        VolcanoBombs bombs = new VolcanoBombs(null);

        bombs.minBombPower = minBombPower;
        bombs.maxBombPower = maxBombPower;
        bombs.minBombRadius = minBombRadius;
        bombs.maxBombRadius = maxBombRadius;

        bombs.bombDelay = bombDelay;

        return bombs.exportConfig();
    }
}
