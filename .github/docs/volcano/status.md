[<  Return to Typhon Docs](/DOCS.md)  
[<< Return to Volcano Docs](./index.md)  

# Volcanic Status

Volcanoes exist in a spectrum of activity states that indicate their internal pressure and potential for eruption.

Typhon simulates this spectrum of volcanic activity.

## Volcanic Activity States

Each vent can exist in one of five distinct states:

| Extinct | Dormant |  Minor Activity  |  Major Activity  | Erupting |
|:-------:|:-------:|:----------------:|:----------------:|:--------:|
| ![Extinct](/src/main/resources/icons/[footage]/extinct.svg) | ![Dormant](/src/main/resources/icons/[footage]/dormant.svg) | ![Minor Activity](/src/main/resources/icons/[footage]/minor-activity.svg) | ![Major Activity](/src/main/resources/icons/[footage]/major-activity.svg) | ![Erupting](/src/main/resources/icons/[footage]/erupting.svg) |

### Extinct
<img src="/.github/docs/volcano/assets/status/extinct.png" alt="Extinct volcano Image" width="300" height="200"/>

Volcanoes with no magmatic system capable of future eruptions.

**Characteristics**:
- No heat signatures or geothermal activity
- Complete ecological [succession](./succession.md) throughout
- Automatic eruption triggers have no effect
- Plants and trees grow freely, even within crater

### Dormant
<img src="/.github/docs/volcano/assets/status/dormant.png" alt="Dormant volcano Image" width="300" height="200"/>

Volcanoes that maintain the potential to erupt, with active magma chambers but no current activity.

**Characteristics**:
- Occasional mild heat signatures in crater
- Minor geothermal features intermittently
- [Ecological succession](./succession.md) progresses except within crater
- Faint steam wisps occasionally from crater

### Minor Activity
<img src="/.github/docs/volcano/assets/status/minor-activity.png" alt="Minor Activity volcano Image" width="300" height="200"/>

Consistent, low-level signs of volcanic unrest with elevated activity.

**Characteristics**:
- Sustained [geothermal activity](./geothermal.md) in and near crater
- Occasional [volcanic gases](./geothermal.md#volcanic-gases) emission
- Minor heat signatures on upper flanks
- Ecological succession slowed but not halted
- More numerous and consistent steam vents
- Heat damage near vents
- Upper flank vegetation shows stress signs

### Major Activity
<img src="/.github/docs/volcano/assets/status/major-activity.png" alt="Major Activity volcano Image" width="300" height="200"/>

Intense signs of unrest indicating potential eruption is near.

**Characteristics**:
- Intense [geothermal activity](./geothermal.md) throughout edifice
- Rapid water evaporation in crater
- Upper flank vegetation dies from volcanic gases
- Lower flank vegetation shows stress signs
- Sustained fumarole activity with significant gas, steam, heat
- Significantly increased fumarole activity
- Widespread burning hotspots
- [Ecological succession](./succession.md) halted

### Eruption Imminent
<img src="/.github/docs/volcano/assets/status/eruption-imminent.png" alt="Eruption Imminent volcano Image" width="300" height="200"/>

Volcano on the verge of erupting with magma about to breach the surface. (In Typhon, this is internal state as having eruption's state without lava/bombs/ash actually emerging yet.)

**Characteristics**:
- All `Major Activity` effects present
- Maximal [geothermal activity](./geothermal.md) throughout
- Crater filled with intense heat, activity, and volcanic gas
- Heat damage throughout volcanic area
- Complete cessation of ecological processes
- Even more fumarole activity
- Incandescent glow may be visible in crater

### Erupting
<img src="/.github/docs/volcano/assets/status/erupting.png" alt="Eruption Imminent volcano Image" width="300" height="200"/>

Active eruption with magma, gas, and ash discharge.

**Characteristics**:
- All `Eruption Imminent` effects present
- Eruption in progress
- [Lava](./lava.md), [volcanic bombs](./bombs.md), and [ash](./ash.md) emerge from vent (depending on style)
- Intense geothermal activity and heat signatures throughout


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

