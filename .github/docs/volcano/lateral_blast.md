[<  Return to Typhon Docs](/DOCS.md)  
[<< Return to Volcano Docs](./index.md)  

# Lateral Blast

Lateral blasts are rare volcanic events where eruptions explode sideways rather than upward, devastating large areas and creating distinctive horseshoe-shaped craters.

Typhon simulates this dramatic volcanic phenomenon.

<img src="https://github.com/user-attachments/assets/d8e8e172-92c4-462f-bca7-e6e87274724a" width="400" />

## Formation Process

Lateral blasts typically occur when:
1. Magma intrudes into a volcano's flank, creating a bulge
2. This destabilizes the volcano's structure
3. The bulging section causes a massive landslide
4. The sudden removal "uncorks" the pressurized magma system
5. The eruption explodes horizontally through the collapsed section

The result is a devastating directional blast that creates a distinctive horseshoe-shaped crater and significantly reduces volcano elevation.

## Creating a Lateral Blast

Configure and trigger a lateral blast to permanently transform your volcano.

> [!NOTE]  
> These commands refer to the main vent. For subsidiary vents, please refer to the [Eruption Vents](./vents.md#commands) documentation.

### Setting Up the Lateral Blast

1. If your volcano has experienced a lateral blast before, reset it:  
   `/volcano <volcano_name> mainvent landslide clear`

2. Set the blast direction:  
   `/volcano <volcano_name> mainvent landslide setAngle <radians>`
   
   - `<radians>`: The direction of the blast in radians
   - For a more intuitive approach, you can set the direction based on where you're looking:
     `/volcano <volcano_name> mainvent landslide setAngle auto`

3. Trigger the cataclysmic event:  
   `/volcano <volcano_name> mainvent landslide start`

### Sequence of Events

Once triggered, the lateral blast creates:

- Automatic switch to [`plinian` eruption](./eruption.md#plinian-eruptions), simulating tremendous energy release
- Massive volcanic bombs ejected in the blast direction
- Extensive pyroclastic flows racing down to the blast direction
- Formation of horseshoe-shaped crater opening in the blast direction

## Geological Aftermath

The lateral blast permanently transforms the volcano:

- Symmetrical cone becomes asymmetrical with distinctive open side
- Significant reduction in volcano height
- Geothermal activity intensifies on the blasted range
- Horseshoe-shaped crater remains as evidence
- Blast zone extends outward in explosion direction





