[<  Return to Typhon Docs](/DOCS.md)  
[<< Return to Volcano Docs](./index.md)  

# Lateral Blast: When Mountains Explode Sideways

The most dramatic volcanic event of the 20th century occurred on May 18, 1980, when Mount St. Helens in Washington State didn't just erupt upward - it exploded sideways. This lateral blast devastated 230 square miles of forest in minutes, traveling at speeds up to 300 mph (480 km/h). Similar events have occurred at Mount Bezymianny (1956) and Mount Bandai (1888), creating some of Earth's most distinctive volcanic landscapes.

Typhon brings this rare but spectacular volcanic phenomenon to Minecraft.

![Image](https://github.com/user-attachments/assets/d8e8e172-92c4-462f-bca7-e6e87274724a)

## The Science Behind Lateral Blasts

In nature, lateral blasts typically occur when:
1. Magma intrudes into a volcano's flank, creating a bulge
2. This destabilizes the volcano's structure
3. The weight of the bulging section causes a massive landslide
4. The sudden removal of this material "uncorks" the pressurized magma system
5. The eruption explodes horizontally through the collapsed section

The result is a devastating directional blast that can travel for miles, flatten forests, and create a distinctive horseshoe-shaped crater - as seen dramatically at Mount St. Helens, which lost 1,300 feet (400 meters) of elevation in seconds.

## Creating a Lateral Blast in Typhon

Typhon allows you to recreate this extraordinary geological event, transforming your volcano's appearance permanently.

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

### Witnessing Nature's Fury

Once triggered, the lateral blast creates a spectacular sequence of events:

- The volcano automatically switches to a [`plinian` eruption](./eruption.md#plinian-eruptions-historys-most-catastrophic-volcanic-events), simulating the tremendous energy release
- Massive volcanic bombs are ejected in the direction of the blast
- The side of the volcano collapses dramatically, similar to the Mount St. Helens landslide
- A horseshoe-shaped crater forms, opening in the direction of the blast
- The landscape in the blast zone is devastated

## Geological Aftermath

The lateral blast permanently transforms your volcano:

- The once symmetrical cone becomes asymmetrical with a distinctive open side
- The height of the volcano is significantly reduced
- A horseshoe-shaped crater remains as evidence of the catastrophic event
- The blast zone extends outward in the direction of the explosion

This recreates the distinctive morphology seen at real-world volcanoes that have experienced lateral blasts.

Typhon's lateral blast feature allows you to witness and recreate this extraordinary type of volcanic event in your Minecraft world, providing both spectacle and education about one of nature's most dramatic geological processes.





