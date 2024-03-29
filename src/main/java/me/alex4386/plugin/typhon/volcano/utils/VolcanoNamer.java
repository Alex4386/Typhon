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
    public static int MAX_SYLLABLES = 7;

    public static boolean startByVowels = true; // always start with a vowel sound
    public static boolean alternatingVC = true; // alternate between vowel and consonant sounds

    // Random number generator for choosing sounds
    public static Random random = new Random();

    public static String generate() {
        int syllables = (int) ((MAX_SYLLABLES - MIN_SYLLABLES) * Math.random()) + MIN_SYLLABLES;
        int length = syllables * 2 - 1;
        StringBuilder nameBuilder = new StringBuilder(length);
        boolean isVowel = Math.random() < 0.3;
        for (int i = 0; i < length; i++) {
            // Alternate between vowel and consonant sounds
            String sound = isVowel ? getRandomVowel() : getRandomConsonant();
            nameBuilder.append(sound);
            isVowel = !isVowel;
        }

        String targetName = nameBuilder.toString();
        return targetName;
    }

    private static String getRandomVowel() {
        return vowels[(int) (Math.random() * vowels.length)];
    }

    private static String getRandomConsonant() {
        return consonants[(int) (Math.random() * vowels.length)];
    }

    private static String randomSound() {
        boolean isVowel = random.nextBoolean();
        return isVowel ? getRandomVowel() : getRandomConsonant();
    }
}
