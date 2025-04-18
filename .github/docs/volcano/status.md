[<  Return to Typhon Docs](/DOCS.md)  
[<< Return to Volcano Docs](./index.md)  

# Volcanic Status: The Pulse of the Earth

Just as medical doctors monitor a patient's vital signs, volcanologists closely watch volcanic "vital signs" to assess activity levels. From the dormant slumber of Washington's Mount Baker to the near-constant eruptions of Italy's Stromboli, volcanoes exist in a spectrum of states that indicate their internal pressure and potential for eruption.

Typhon faithfully recreates this spectrum of volcanic activity, allowing you to experience the full lifecycle of volcanoes.

## The Volcanic Activity Spectrum

Each vent in Typhon can exist in one of five distinct states, mirroring the monitoring systems used by real volcano observatories worldwide:

| Extinct | Dormant |  Minor Activity  |  Major Activity  | Erupting |
|:-------:|:-------:|:----------------:|:----------------:|:--------:|
| ![Extinct](/src/main/resources/icons/[footage]/extinct.svg) | ![Dormant](/src/main/resources/icons/[footage]/dormant.svg) | ![Minor Activity](/src/main/resources/icons/[footage]/minor-activity.svg) | ![Major Activity](/src/main/resources/icons/[footage]/major-activity.svg) | ![Erupting](/src/main/resources/icons/[footage]/erupting.svg) |

### Extinct: The Silent Mountain
<img src="/.github/docs/volcano/assets/status/extinct.png" alt="Extinct volcano Image" width="300" height="200"/>
**In Nature**: Extinct volcanoes haven't erupted in over 10,000 years and have no magmatic system capable of future eruptions.

**In Typhon**:
- No heat signatures or geothermal activity
- Complete ecological [succession](./succession.md) occurs throughout the volcano
- Automatic eruption triggers will not affect the volcano
- Plants and trees grow freely, even within the crater

### Dormant: The Sleeping Giant
<img src="/.github/docs/volcano/assets/status/dormant.png" alt="Dormant volcano Image" width="300" height="200"/>
**In Nature**: Dormant volcanoes haven't erupted in historical time but maintain the potential to awaken., such as Volcanoes still having active Magma Chambers to support future eruptions.

**In Typhon**:
- Occasional mild heat signatures appear in the crater
- Minor geothermal features manifest intermittently
- [Ecological succession](./succession.md) progresses everywhere except within the crater
- Faint wisps of steam might occasionally rise from the crater

### Minor Activity: The Restless Slumber
<img src="/.github/docs/volcano/assets/status/minor-activity.png" alt="Minor Activity volcano Image" width="300" height="200"/>

**In Nature**: Volcanoes showing minor activity have consistent, low-level signs of unrest.  
The volcano not only have active magma chambers, but also show elevated activities like gas emissions, fumaroles, and minor seismic activity.

**In Typhon**:
- Sustained [geothermal activity](./geothermal.md) in and near the crater
- Occasional emission of [volcanic gases](./geothermal.md#volcanic-gases)
- Minor heat signatures detectable on the upper flanks
- Ecological succession slowed but not halted by volcanic influence
- Steam vents become more numerous and consistent
- Entities too close to vents may experience heat damage
- Vegetations near the upper flanks may show signs of stress from heat and gases

### Major Activity: The Awakening
<img src="/.github/docs/volcano/assets/status/major-activity.png" alt="Major Activity volcano Image" width="300" height="200"/>

**In Nature**: Volcanoes in major activity show even more intense signs of unrest.
They may have increased seismic activity, ground deformation, and gas emissions, but even more intense, indicating a potential eruption might be near.

**In Typhon**:
- Intense [geothermal activity](./geothermal.md) throughout the volcanic edifice
- Water in the crater rapidly evaporates
- Plants and trees near the volcano begin to die from volcanic gases in upper flanks
- Vegetations in the lower flanks may show signs of stress from heat and gases
- Sustained fumarole activity emits significant volumes of gas, steam, and heat.
- Amount of fumarole activity increases significantly
- Hotspots capable of burning entities appear widely around the volcano
- [Ecological succession](./succession.md) halted by volcanic conditions

### Eruption Imminent: Nature's Fury just at the throat
<img src="/.github/docs/volcano/assets/status/eruption-imminent.png" alt="Eruption Imminent volcano Image" width="300" height="200"/>

**In Nature**: The volcano is on the verge of erupting, with Magma rising to just waiting for breaching the surface.
The volcanic activity is at its peak, with intense seismic activity, ground deformation, and gas emissions.

**In Typhon**:
- All of the effects of `Major Activity` are present
- Maximal [geothermal activity](./geothermal.md) throughout the volcano
- Crater filled with intense heat and activity, and volcanic gas
- Heat damage to entities throughout the volcanic area
- Complete cessation of ecological processes
- Amount of fumarole activity increases even more
- Incadescent glow may visible in the crater

### Erupting: The Volcano's Fury Unleashed
<img src="/.github/docs/volcano/assets/status/erupting.png" alt="Eruption Imminent volcano Image" width="300" height="200"/>

**In Nature**: Erupting volcanoes are in the throes of an eruption, with lava flows, ash clouds, and pyroclastic flows.
The volcano is actively discharging magma, gas, and ash into the atmosphere.

**In Typhon**:
- All of the effects of `Eruption Imminent` are present
- The eruption is now in progress
- Depending on eruption style: [lava](./lava.md), [volcanic bombs](./bombs.md), and [ash](./ash.md) emerge from the vent
- Intense geothermal activity and heat signatures throughout the volcano


## Monitoring Your Volcano

### Check the Overall Volcano Status

To determine the highest activity level among all vents in your volcano:
```
/volcano <volcano_name> status
```

### Check Individual Vent Status

> [!NOTE]  
> These commands refer to the main vent. For subsidiary vents, please refer to the [Eruption Vents](./vents.md#commands) documentation.

To check the status of a specific vent:
```
/volcano <volcano_name> mainvent status
```

### Manually Change Vent Status

You can manually set the status of a vent:
```
/volcano <volcano_name> mainvent status <status>
```

Where `<status>` is one of: `extinct`, `dormant`, `minor_activity`, `major_activity`, `eruption_imminent`, or `erupting`

## The Living Volcano

In Typhon, a volcano's status isn't just a label - it fundamentally affects how the volcano interacts with its environment:

- Higher activity levels increase [geothermal phenomena](./geothermal.md) like fumaroles
- The status influences how quickly [ecological succession](./succession.md) reclaims volcanic terrain
- Animal and plant life respond realistically to changing volcanic conditions
- Water bodies near the volcano are affected by heat levels
- The landscape gradually transforms based on the volcano's status

By implementing this spectrum of volcanic states, Typhon creates volcanoes that feel alive - changing, breathing, and evolving over time, just like their real-world counterparts.
