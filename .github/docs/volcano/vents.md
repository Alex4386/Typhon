[<  Return to Typhon Docs](/DOCS.md)  
[<< Return to Volcano Docs](./index.md)  

# Volcanic Vents: The Earth's Fiery Gateways

In nature, volcanic eruptions don't always occur from a single, neat crater at the summit. From Iceland's dramatic fissure eruptions that create curtains of fire to complex stratovolcanoes like Mount Etna with multiple active vents, volcanic systems often feature various "gateways" where magma reaches the surface.

Typhon faithfully recreates these diverse volcanic vent systems, allowing you to build volcanic landscapes as complex and dynamic as their real-world counterparts.

![Volcano system with multiple vents, with some of them erupting](/.github/docs/volcano/assets/multi-vents.png)

## Types of Volcanic Vents

Just as in nature, Typhon supports multiple vent types that produce different eruption patterns:

### Crater Vents: The Classic Volcano

**In Nature**: The classic volcanic crater is the most familiar vent type - a roughly circular opening at the summit of a volcanic cone. Mount Fuji, Mount Vesuvius, and Kilauea's Halema'uma'u crater exemplify this common vent type.

**In Typhon**:
- Crater vents erupt from a central point
- Lava fountains and flows radiate from the central vent
- The volcano builds a symmetric cone with repeated eruptions
- The crater's size influences eruption characteristics

To set a vent to crater type:  
`/volcano <volcano_name> mainvent style crater`  

Configure crater size:  
`/volcano <volcano_name> mainvent config vent:craterRadius <radius>`

### Fissure Vents: The Curtain of Fire

**In Nature**: Fissure eruptions occur along linear cracks in the earth, often producing spectacular "curtains of fire" as lava erupts along a line. Iceland's Laki fissure (1783) and the ongoing Holuhraun eruption demonstrate this dramatic eruption type.

**In Typhon**:
- Lava emerges along a linear crack rather than from a point
- Multiple lava fountains line up along the fissure
- The resulting landforms tend to be elongated rather than conical
- Ideal for creating features like lava fields and shield volcanoes

To set a vent to fissure type:  
`/volcano <volcano_name> mainvent style fissure`  

Configure fissure orientation:  
`/volcano <volcano_name> mainvent config vent:fissureAngle auto`  
(This sets the fissure angle based on the direction you're facing)

Configure fissure length:  
`/volcano <volcano_name> mainvent config vent:fissureLength <length>`

## Multi-Vent Volcanic Systems

Real volcanic complexes often feature multiple active vents. Italy's Mount Etna has several summit craters and numerous flank vents. The Canary Islands' Cumbre Vieja eruption in 2021 developed multiple vents along a fissure system.

Typhon allows you to recreate these complex volcanic systems:

### Main Vent: The Primary Hub

When you create a volcano, a main vent is automatically established. This represents the primary eruption center, typically at the summit of the volcanic edifice.

### Creating Subsidiary Vents

Just as Mount Etna develops new vents during eruption sequences, you can create additional vents for your volcano:

Create a crater-type subsidiary vent:  
`/volcano <volcano_name> create crater <subvent_name>`

Create a fissure-type subsidiary vent:  
`/volcano <volcano_name> create fissure <subvent_name>`

Create a subsidiary vent near a player:  
`/volcano <volcano_name> create autovent <subvent_name> <player_name>`

### Monogenetic vs. Polygenetic Vents

In volcanology, vents are classified based on their eruption frequency:

**Monogenetic Vents**: In nature, these erupt only once and become extinct. The cinder cones of Mexico's Michoacán-Guanajuato volcanic field are monogenetic, each representing a single eruptive event.

**Polygenetic Vents**: These erupt repeatedly over time. Mount Vesuvius and Mount Fuji are classic polygenetic volcanoes, with the same vent system active for thousands of years.

Typhon allows you to specify either behavior:

Check a vent's current genesis mode:  
`/volcano <volcano_name> mainvent genesis`

Set a vent's genesis mode:  
`/volcano <volcano_name> mainvent genesis <mode>`  
Where `<mode>` is either `monogenetic` or `polygenetic`

## Managing Your Volcanic Vent System

### Vent Control Commands

> [!NOTE]  
> The following examples use `mainvent`. For subsidiary vents, replace `mainvent` with `subvent <subvent_name>`:  
> `/volcano <volcano_name> subvent <subvent_name> <command> <args>`

Start or stop an eruption:  
`/volcano <volcano_name> mainvent <start|stop>`

Find the highest point of a vent:  
`/volcano <volcano_name> mainvent summit`

Configure vent parameters:  
`/volcano <volcano_name> mainvent config <parameter> <value>`  
See [Config Nodes](./config_nodes.md) for detailed configuration options.

### Vent Management Commands

Switch the main vent with a subsidiary vent:  
`/volcano <volcano_name> subvent <subvent_name> switch`

Delete a subsidiary vent:  
`/volcano <volcano_name> subvent <subvent_name> delete`

Access builder options for a vent:  
`/volcano <volcano_name> mainvent builder <options>`  
See [Builder](./builder.md) for more information on volcano construction.


