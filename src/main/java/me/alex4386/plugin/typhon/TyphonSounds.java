package me.alex4386.plugin.typhon;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;

// special thanks to:
// https://github.com/Owen1212055/mc-sound-seeds/blob/main/sound_seeds.json
// for the sound seeds

public enum TyphonSounds {

    // https://minecraft.wiki/w/Ambience#Basalt_Deltas_ambience
    // https://minecraft.wiki/w/Ambience#Nether_Wastes_ambience

    // basalt_deltas/basalt_ground1
    DISTANT_EXPLOSION(Sound.AMBIENT_BASALT_DELTAS_ADDITIONS, -8189996362953806000L),

    // nether_wastes/mood4
    EARTH_CRUMBLING(Sound.AMBIENT_BASALT_DELTAS_MOOD, 5824310034203387000L),

    // nether_wastes/ground3
    LAVA_THROAT_PLUMBING(Sound.AMBIENT_NETHER_WASTES_ADDITIONS, -6092451252487690000L),

    // nether_wastes/ground4
    LAVA_THROAT_PLUMBING_2(Sound.AMBIENT_NETHER_WASTES_ADDITIONS, 8017002997411952000L),

    // basalt_deltas/debris1
    LAVA_FLOW_FRAGMENTING(Sound.AMBIENT_BASALT_DELTAS_ADDITIONS, -4954164536332790000L),

    // nether_wastes/ground1
    LAVA_FLOW_FRAGMENTING_BIG(Sound.AMBIENT_NETHER_WASTES_ADDITIONS, -8315353685128669000L),

    // nether_wastes/addition4
    LAVA_DEGASSING(Sound.AMBIENT_NETHER_WASTES_ADDITIONS, -8058227235025528000L),

    // basalt_deltas/plode1
    STROMBOLIAN_ERUPTION(Sound.AMBIENT_BASALT_DELTAS_ADDITIONS, 3671146418610134500L),

    // nether_wastes/mood1
    VULCANIAN_ERUPTION(Sound.AMBIENT_BASALT_DELTAS_MOOD, -2906677560844330500L),

    // underwater/additions/earth_crack
    EARTH_CRACKING(Sound.AMBIENT_UNDERWATER_LOOP_ADDITIONS_RARE, -1149439178961691000L),

    // nether_wastes/mood5
    ASH_PLUME(Sound.AMBIENT_NETHER_WASTES_MOOD, -2893570119582769000L),
    ;

    Sound sound;
    long seed;

    TyphonSounds(Sound sound, long seed) {
        this.sound = sound;
        this.seed = seed;
    }

    public void play(Location location, SoundCategory category, float volume, float pitch) {
        try {
            location.getWorld().playSound(location, this.sound, category, volume, pitch, this.seed);
        } catch (Exception e) {
            // pre 1.21 sound system
            location.getWorld().playSound(location, this.sound, category, volume, pitch);
        }
    }

    public static TyphonSounds getRandomLavaThroat() {
        if (Math.random() < 0.5) {
            return LAVA_THROAT_PLUMBING;
        } else {
            return LAVA_THROAT_PLUMBING_2;
        }
    }

    public static TyphonSounds getRandomLavaFragmenting() {
        if (Math.random() < 0.7) {
            return LAVA_FLOW_FRAGMENTING;
        } else {
            return LAVA_FLOW_FRAGMENTING_BIG;
        }
    }
}

