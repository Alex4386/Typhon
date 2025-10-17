[<  Return to Typhon Docs](/DOCS.md)  
[<< Return to Volcano Docs](./index.md)  

# Volcanic Vents

Volcanic eruptions don't always occur from a single crater at the summit. Volcanic systems often feature various "gateways" where magma reaches the surface, including fissures and multiple active vents.

Typhon simulates these diverse volcanic vent systems.

<img src="/.github/docs/volcano/assets/multi-vents.png" align="center" width="400" />
Just as the image shows, Typhon can implement multiple vents with "own" type (fissure, crater). each erupting on its own.

## Types of Volcanic Vents

Typhon supports multiple vent types that produce different eruption patterns:

### Crater Vents

Circular openings at the summit of a volcanic cone.

**Features**:
- Crater vents erupt from a central point
- Lava fountains and flows radiate from the central vent
- Builds symmetric cone with repeated eruptions
- Crater size influences eruption characteristics

**Configuration**:
```
/volcano <volcano_name> mainvent style crater
/volcano <volcano_name> mainvent config vent:craterRadius <radius>
```

### Fissure Vents

Linear cracks that produce "curtains of fire" as lava erupts along a line.

**Features**:
- Lava emerges along a linear crack rather than from a point
- Multiple lava fountains line up along the fissure
- Resulting landforms are elongated rather than conical
- Ideal for creating lava fields and shield volcanoes

**Configuration**:
```
/volcano <volcano_name> mainvent style fissure
/volcano <volcano_name> mainvent config vent:fissureAngle auto
/volcano <volcano_name> mainvent config vent:fissureLength <length>
```

## Multi-Vent Volcanic Systems

Volcanic complexes often feature multiple active vents, including summit craters and flank vents along fissure systems.

Typhon supports complex multi-vent volcanic systems:

### Main Vent

When you create a volcano, a main vent is automatically established as the primary eruption center, typically at the summit.

### Creating Subsidiary Vents

Create additional vents for your volcano:

Create a crater-type subsidiary vent:  
`/volcano <volcano_name> create crater <subvent_name>`

Create a fissure-type subsidiary vent:  
`/volcano <volcano_name> create fissure <subvent_name>`

Create a subsidiary vent near a player:  
`/volcano <volcano_name> create autovent <subvent_name> <player_name>`

### Monogenetic vs. Polygenetic Vents

Vents are classified based on their eruption frequency:

**Monogenetic Vents**: Erupt only once and become extinct, representing a single eruptive event.

**Polygenetic Vents**: Erupt repeatedly over time, with the same vent system remaining active for extended periods.

Configure vent behavior:

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


