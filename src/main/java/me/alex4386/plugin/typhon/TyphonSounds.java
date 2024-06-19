package me.alex4386.plugin.typhon;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;

// special thanks to:
// https://github.com/Owen1212055/mc-sound-seeds/blob/main/sound_seeds.json
// for the sound seeds

public enum TyphonSounds {
    // basalt_deltas/basalt_ground1
    DISTANT_EXPLOSION(Sound.AMBIENT_BASALT_DELTAS_ADDITIONS, 8427267301185073000L),

    // add when basalt_deltas/basaltground4's seed is available
    @Deprecated
    LAVA_ERUPTION(Sound.AMBIENT_BASALT_DELTAS_ADDITIONS, 0L),

    // basalt_deltas/debris1
    LAVA_FLOW_FRAGMENTING(Sound.AMBIENT_BASALT_DELTAS_ADDITIONS, -56266925801278970L),

    // basalt_deltas/plode1
    BOMB_LANDING(Sound.AMBIENT_BASALT_DELTAS_ADDITIONS, 957415143359721600L),

    EARTH_CRACKING(Sound.AMBIENT_UNDERWATER_LOOP_ADDITIONS_RARE, 6037455361874583000L)
    ;

    Sound sound;
    long seed;

    TyphonSounds(Sound sound, long seed) {
        this.sound = sound;
        this.seed = seed;
    }

    public void play(Location location, SoundCategory category, float volume, float pitch) {
        if (this == LAVA_ERUPTION) {
            // until it is implemented, do the fallback.
            location.getWorld().playSound(
                    location,
                    Sound.ENTITY_BREEZE_WIND_BURST,
                    SoundCategory.BLOCKS,
                    volume,
                    pitch
            );

            TyphonSounds.DISTANT_EXPLOSION.play(location, category, volume, pitch);
            return;
        }

        try {
            location.getWorld().playSound(location, this.sound, category, volume, pitch, this.seed);
        } catch (Exception e) {
            // pre 1.21 sound system
            location.getWorld().playSound(location, this.sound, category, volume, pitch);
        }
    }
}

