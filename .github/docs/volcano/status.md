[<  Return to Typhon Docs](/DOCS.md)  
[<< Return to Volcano Docs](./index.md)  

# Vent Status
Each vent can have different status. The status of the vent will determine the behavior of the vent.

## Status
The vent can have the following status:

| Extinct | Dormant |                              Minor Activity                               |                    Major Activity / Eruption Imminent                     | Erupting |
|:-------:|:-------:|:-------------------------------------------------------------------------:|:-------------------------------------------------------------------------:|:--------:|
| ![Extinct](/src/main/resources/icons/[footage]/extinct.svg) | ![Dormant](/src/main/resources/icons/[footage]/dormant.svg) | ![Minor Activity](/src/main/resources/icons/[footage]/minor-activity.svg) | ![Major Activity](/src/main/resources/icons/[footage]/major-activity.svg) | ![Erupting](/src/main/resources/icons/[footage]/erupting.svg) |

> [!TIP]  
> The status of the vent is mostly used for the geothermal activities. For more information about the geothermal activities, please refer to the [Geothermal Activities](./geothermal.md) documentation.

Each was derived from real-life volcanic alert levels:  
- **EXTINCT:** `VolcanoAutoStart` will not trigger the eruption, The volcano has no `heat` value for geothermal activities.
  - The volcano will not have any geothermal activities.
  - The primary succession will trigger rapidly in all places, including the vent.

- **DORMANT:** The volcano shows minor heat signatures time-to-time, but it doesn't happen all the time.
  - The volcano will have minor geothermal activities only inside the vent.
  - The primary succession will trigger rapidly in all places, except the vent.

- **MINOR_ACTIVITY:** The volcano shows minor heat signatures such as fumarole near the summit.
  - The volcano have sustained geothermal activities near the vent.
  - The volcano have minor geothermal activities near the summit.
  - The volcano have little to no geothermal activities on the cone/flanks.
  - The primary succession will still trigger, but interrupted by the geothermal activities.
  - Geothermal activities near the vent can "burn" nearby entities.
  - The vent will have a chance to emit volcanic gases.

- **MAJOR_ACTIVITY:** The volcano shows sustained geothermal activities and heat signatures near the summit. stable heat signatures from the volcano flanks are also visible.
  - The crater is filled with geothermal activities in the crater.
  - Water inside the crater will be evaporated.
  - The volcano have sustained geothermal activities in all places.
  - The primary succession will be interrupted by the geothermal activities.
  - Geothermal activities near the vent can "burn" nearby entities.
  - Geothermal activities killing trees and plants.
  - The vent has sustained fumarole output of volcanic gases.

- **ERUPTION_IMMINENT/ERUPTING:** Volcano is actively erupting and lava, bomb or ashes are spewing out from the vent or it is imminent to erupt.
  - The crater is filled with geothermal activities in the crater.
  - Water inside the crater will be evaporated.
  - The volcano have sustained geothermal activities in all places.


## Commands

### Check the Highest Status of the Volcano
You can check the highest status of the volcano by running the following command:
```
/volcano <volcano_name> status
```

### Check the Status of the Vent
You can check the status of the vent by running the following command:

> [!NOTE]  
> For simplicity of the documentation, the following commands will use commands for `mainvent`. For subvent, Please refer to the [Eruption Vents](./vents.md#commands) documentation.

```
/volcano <volcano_name> mainvent status
```

### Set the Status of the Vent
You can set the status of the vent by running the following command:
```
/volcano <volcano_name> mainvent status <status>
```
`<status>` can be one of the following: `extinct`, `dormant`, `minor_activity`, `major_activity`, `eruption_imminent`, `erupting`
