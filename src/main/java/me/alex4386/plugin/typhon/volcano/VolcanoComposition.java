package me.alex4386.plugin.typhon.volcano;

import org.bukkit.Material;

import me.alex4386.plugin.typhon.TyphonUtils;

import java.util.Random;

public class VolcanoComposition {

    public static Material getBombRock(double silicateLevel) {
        Random random = new Random();

        if (silicateLevel < 0.57) {
            double s = random.nextDouble();

            // bunch of scoria
            if (s < (1.0 / 3.0)) {
                // black scoria
                return Material.BLACKSTONE;
            } else if (s < (2.0 / 3.0)) {
                // gray scoria
                return Material.COBBLED_DEEPSLATE;
            } else {
                // purple-red scoria
                if (Math.random() < 0.0001) {
                    return Material.NETHER_QUARTZ_ORE;
                } else {
                    return Material.NETHERRACK;
                }
            }
        } else {
            if (silicateLevel < 0.7) {
                return Material.TUFF;
            } else if (silicateLevel < 0.90) {
                double s = random.nextDouble();
                if (s > 0.2) {
                    return Material.TUFF;
                } else if (s > 0.1) {
                    return Material.AMETHYST_BLOCK;
                } else {
                    if (Math.random() < 0.9) {
                        return Material.NETHER_QUARTZ_ORE;
                    } else {
                        return Material.QUARTZ_BLOCK;
                    }
                }
            } else {
                if (Math.random() < 0.9) {
                    return Material.NETHER_QUARTZ_ORE;
                } else {
                    return Material.QUARTZ_BLOCK;
                }
            }
        }
    }

    public static Material getExtrusiveRock(double silicateLevel) {
        Random random = new Random();

        if (silicateLevel < 0.41) {
            return Material.DEEPSLATE;
        } else if (silicateLevel < 0.45) {
            double ratio = (silicateLevel - 0.45) / (0.45 - 0.41);

            double s = random.nextDouble();
            if (s > ratio) {
                return Material.DEEPSLATE;
            } else {
                if (random.nextBoolean()) return Material.POLISHED_BASALT;
                return Material.BASALT;
            }
        } else if (silicateLevel < 0.53) {
            if (random.nextBoolean()) return Material.POLISHED_BASALT;
            return Material.BASALT;
        } else if (silicateLevel < 0.57) {
            double ratio = (silicateLevel - 0.53) / (0.57 - 0.53);

            double s = random.nextDouble();
            if (s > ratio) {
                if (random.nextBoolean()) return Material.POLISHED_BASALT;
                return Material.BASALT;
            } else {
                return getExtrusiveRock(0.64);
            }
        } else if (silicateLevel < 0.65) {
            if (Math.random() < 0.3) return Material.TUFF;
            return Material.ANDESITE;
        } else if (silicateLevel < 0.69) {
            double ratio = (silicateLevel - 0.65) / (0.69 - 0.65);

            double s = random.nextDouble();
            if (s > ratio) {
                return getExtrusiveRock(0.64);
            } else {
                if (random.nextDouble() < 0.01 * ratio) return Material.QUARTZ_BLOCK;
                if (random.nextDouble() < 0.5 * ratio) { return (random.nextDouble() < 0.9) ? Material.OBSIDIAN : Material.CRYING_OBSIDIAN; }
                if (random.nextDouble() < 0.1 * ratio) return Material.GRANITE;
                return Material.STONE;
            }
        } else if (silicateLevel < 0.77) {
            double ratio = (silicateLevel - 0.77) / (0.83 - 0.77);

            double s = random.nextDouble();
            if (s > ratio) {
                if (random.nextDouble() < 0.02) return Material.QUARTZ_BLOCK;
                if (random.nextBoolean()) return Material.OBSIDIAN;
                return Material.STONE;
            } else {
                if (random.nextDouble() < 0.5 * ratio) return Material.AMETHYST_BLOCK;
                return Material.GRANITE;
            }
        } else if (silicateLevel < 0.90) {
            double ratio = (silicateLevel - 0.77) / (0.90 - 0.77);

            double s = random.nextDouble();
            if (s > ratio && silicateLevel < 0.83) {
                if (random.nextDouble() < 0.5 * ratio) return Material.AMETHYST_BLOCK;
                return Material.GRANITE;
            } else {
                s = random.nextDouble();
                double silicateRatio = (silicateLevel - 0.83) / (0.90 - 0.83);
                if (s > silicateRatio) {
                    return Material.AMETHYST_BLOCK;
                } else {
                    return Material.QUARTZ_BLOCK;
                }
            }
        } else {
            return Material.QUARTZ_BLOCK;
        }
    }


    // ====== legacy ======
    // The following is left for 
    // reference purposes

    @Deprecated
    public static Material getIntrusiveRock(double silicateLevel) {
        Random random = new Random();

        if (silicateLevel < 0.45) {
            return Material.BLACKSTONE;
        } else if (silicateLevel < 0.53) {
            return Material.BASALT;
        } else if (silicateLevel < 0.57) {
            double ratio = (silicateLevel - 0.53) / (0.57 - 0.53);

            double s = random.nextDouble();
            if (s > ratio) {
                return Material.BASALT;
            } else {
                return Material.DIORITE;
            }
        } else if (silicateLevel < 0.65) {
            return Material.DIORITE;
        } else if (silicateLevel < 0.69) {
            double ratio = (silicateLevel - 0.65) / (0.69 - 0.65);

            double s = random.nextDouble();
            if (s > ratio) {
                return Material.DIORITE;
            } else {
                return Material.GRANITE;
            }
        } else if (silicateLevel < 0.77) {
            return Material.GRANITE;
        } else if (silicateLevel < 0.83) {
            double ratio = (silicateLevel - 0.77) / (0.83 - 0.77);

            double s = random.nextDouble();
            if (s > ratio) {
                return Material.GRANITE;
            } else {
                return Material.AMETHYST_BLOCK;
            }
        } else if (silicateLevel < 0.90) {
            double ratio = (silicateLevel - 0.77) / (0.90 - 0.77);
            double ratioAme = (silicateLevel - 0.83) / (0.90 - 0.83);

            double s = random.nextDouble();
            if (s > ratio) {
                return Material.GRANITE;
            } else {
                if (random.nextDouble() > ratioAme) {
                    return Material.AMETHYST_BLOCK;
                } else {
                    return Material.QUARTZ_BLOCK;
                }
            }
        } else {
            return Material.QUARTZ_BLOCK;
        }
    }

    /*
    public static Material[] getExtrusiveRocks(double silicateLevel) {
        if (silicateLevel < 0.45) {
            return new Material[] {Material.DEEPSLATE, Material.BASALT, Material.POLISHED_BASALT};
        } else if (silicateLevel < 0.53) {
            return new Material[] {
                Material.BASALT, Material.SMOOTH_BASALT, Material.POLISHED_BASALT
            };
        } else if (silicateLevel < 0.57) {
            return new Material[] {
                Material.ANDESITE,
                Material.POLISHED_ANDESITE,
                Material.BASALT,
                Material.SMOOTH_BASALT,
                Material.POLISHED_BASALT
            };
        } else if (silicateLevel < 0.63) {
            return new Material[] {Material.ANDESITE, Material.POLISHED_ANDESITE};
        } else if (silicateLevel < 0.69) {
            return new Material[] {
                Material.ANDESITE,
                Material.POLISHED_ANDESITE,
                Material.OBSIDIAN,
                Material.CRYING_OBSIDIAN,
                Material.TUFF
            };
        } else if (silicateLevel < 0.77) {
            return new Material[] {Material.OBSIDIAN, Material.CRYING_OBSIDIAN, Material.TUFF};
        } else if (silicateLevel < 0.83) {
            return new Material[] {
                Material.OBSIDIAN, Material.CRYING_OBSIDIAN, Material.TUFF, Material.QUARTZ
            };
        } else {
            return new Material[] {Material.QUARTZ};
        }
    }
    */

    public static boolean isVolcanicRock(Material material) {
        String materialName = TyphonUtils.toLowerCaseDumbEdition(material.name());

        return (
                material == Material.STONE ||
                        material == Material.DEEPSLATE ||
			material == Material.COBBLED_DEEPSLATE ||
			material == Material.NETHERRACK ||
                        material == Material.DIORITE ||
                        material == Material.ANDESITE ||
                        materialName.contains("ore") ||
                        material == Material.MAGMA_BLOCK ||
                        material == Material.OBSIDIAN ||
                        materialName.contains("basalt") ||
                        material == Material.ANCIENT_DEBRIS ||
                        material == Material.TUFF
        );
    }

}
