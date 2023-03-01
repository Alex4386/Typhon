package me.alex4386.plugin.typhon.volcano.utils;

import java.util.Random;

public class VolcanoNamer {

    public static String[] vowels = {"a", "e", "i", "o", "u"};

    // use plosives and fricatives, or liquids to get "magmatic" feel
    public static String[] consonants = {
            "k", "t", "g", "b", "r", "d", "n", "m", "z", "v", "s", "l", "h", "j", "f"
    };


    // Define the rules for generating names
    public static int MIN_SYLLABLES = 3;
    public static int MAX_SYLLABLES = 5;

    public static int MIN_LENGTH = MIN_SYLLABLES * 2 - 1; // minimum length to satisfy alternating rule
    public static int MAX_LENGTH = MAX_SYLLABLES * 2 - 1; // maximum length for 5 syllables

    public static boolean startByVowels = true; // always start with a vowel sound
    public static boolean alternatingVC = true; // alternate between vowel and consonant sounds

    // Random number generator for choosing sounds
    public static Random random = new Random();

    public static String generate() {
        int syllables = random.nextInt(MAX_SYLLABLES - MIN_SYLLABLES + 1) + MIN_SYLLABLES;
        int length = syllables * 2 - 1;
        StringBuilder nameBuilder = new StringBuilder(length);
        boolean isVowel = startByVowels;
        for (int i = 0; i < syllables; i++) {
            if (alternatingVC) {
                // Alternate between vowel and consonant sounds
                String sound = isVowel ? getRandomVowel() : getRandomConsonant();
                nameBuilder.append(sound);
                isVowel = !isVowel;
            } else {
                // Choose sounds randomly
                String sound = randomSound();
                nameBuilder.append(sound);
            }
        }
        return nameBuilder.toString().substring(0, length).toUpperCase();
    }

    private static String getRandomVowel() {
        return vowels[random.nextInt(vowels.length)];
    }

    private static String getRandomConsonant() {
        return consonants[random.nextInt(consonants.length)];
    }

    private static String randomSound() {
        boolean isVowel = random.nextBoolean();
        return isVowel ? getRandomVowel() : getRandomConsonant();
    }
}
