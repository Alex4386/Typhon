[<  Return to Typhon Docs](/DOCS.md)  
[<< Return to Volcano Docs](./index.md)  

# Typhon Vent Config Nodes
<p align="right">Last Updated: <b>v0.9.0</b></p>

> [!NOTE]  
> For simplicity of the documentation, the following commands will use commands for `mainvent`. For subvent, Please refer to the [Eruption Vents](./vents.md#commands) documentation.

> [!NOTE]
> You can get and set the config nodes by running the following command:
> - **READ** : `/volcano <name> mainvent config <node>` to get current node value.
> - **WRITE**: `/volcano <name> mainvent config <node> <value>` to write the value.

## Volcanic Ash
| **Parameter**         | **Description**                                                                                                   |
|-----------------------|-------------------------------------------------------------------------------------------------------------------|
| **ash:fullPyroclasticFlowProbability** | Sets the probability for the pyroclastic flow to extend far away from the vent. |

## Lavaflow
| **Parameter**             | **Description**                                                                                                                                                  |
|---------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **lavaflow:delay**        | Sets the delay between lava flow iterations in ticks. A higher delay will cause the lava to flow slower, while a lower delay will cause the lava to flow faster. |
| **lavaflow:flowed**       | Sets the amount of time that the lava has been flowing.                                                                                                          |
| **lavaflow:silicateLevel**| Refer to [Silica Content](lava.md#silica-content)                                                                                                                |
| **lavaflow:gasContent**   | Refer to [Gas Content](lava.md#gas-content).                                                                                                                     |
| **lavaflow:usePouredLava** | Whether the player's lava poured from the lava bucket should be considered as volcanic lava (default: `true`)                                                    |
| **lavaflow:allowPickUp** | Allow players to pickup the volcanic lava (default: `false`) |                                                                                                    |                                                                                                                      
| **lavaflow:usePouredLava** | Whether the lava from player's bucket should be considered as volcanic lava which should be cooled (default: `true`) |

## Bombs
| **Parameter**                | **Description**                                               |
|------------------------------|---------------------------------------------------------------|
| **bombs:baseY**              | Sets the Y coordinate of the base of the vent. Refer to [baseY](eruption.md#baseY-of-the-vent) for more information. |
| **bombs:delay**              | Sets the delay between bomb explosions after landing in ticks. |
| **bombs:explosionPower:min** | Sets the minimum explosion power of volcanic bombs.           |
| **bombs:explosionPower:max** | Sets the maximum explosion power of volcanic bombs.           |
| **bombs:radius:min**         | Sets the minimum radius of volcanic bombs.                     |
| **bombs:radius:max**         | Sets the maximum radius of volcanic bombs.                     |

## Erupt

| **Parameter**        | **Description**                                                                                                   |
|----------------------|-------------------------------------------------------------------------------------------------------------------|
| **erupt:autoconfig** | Automatically configures the volcano based on its current eruption style. Refer to [Eruption Style](eruption.md#eruption-style) for more information. |
| **erupt:style**      | Sets the eruption style of the volcano. Refer to [Eruption Style](eruption.md#eruption-style) for more information. |

## Explosion

| **Parameter**                       | **Description**                                                                                              |
|-------------------------------------|--------------------------------------------------------------------------------------------------------------|
| **explosion:bombs:min**             | Sets the minimum number of explosion power of Volcanic bombs.                                              |
| **explosion:bombs:max**             | Sets the maximum number of explosion power of Volcanic bombs.                                              |
| **explosion:scheduler:size**        | Sets the size of volcanic bomb launch queue.                                                                 |
| **explosion:scheduler:damagingSize**| Sets the size of the damaging explosion size launched from summit via scheduler.                             |

## Succession
| **Parameter**         | **Description**                                                                                                   |
|-----------------------|-------------------------------------------------------------------------------------------------------------------|
| **succession:enable** | Enables the succession of this vent.                                                                            |
| **succession:probability** | Sets the probability of each succession cycle. (0-100)                                                              |
| **succession:treeProbability** | Sets the probability of succession cycle can generate tree. (0-100)                                                        |


## Vent
| **Parameter**         | **Description**                                                                                                   |
|-----------------------|-------------------------------------------------------------------------------------------------------------------|
| **vent:craterRadius** | Sets the radius of the volcano's crater.                                                                          |
| **vent:fissureAngle** | Sets the angle of the volcano's fissure. (in radians)                                                             |
| **vent:fissureLength**| Sets the length of the volcano's fissure.                                                                         |
| **vent:type**         | Sets the vent type of the volcano. See [Vent Type](vents.md#vent-type) for more information.                   |

## Geothermal
| **Parameter** | **Description** |
|---------------|-----------------|
| **geothermal:doFireTicks** | Whether volcanic gas can do chemical/fire burns on players and entities |
| **geothermal:doEvaporation** | Whether high eruptive state can cause water to boil off and blocks to change to "evaporated" equivalents |
| **geothermal:deterMobSpawn** | Whether high eruptive state can prevent mob spawn depending on heat emitted by the volcano (based on "heat" value) |

