[<  Return to Typhon Docs](/DOCS.md)  
[<< Return to Volcano Docs](./index.md)  

# Volcanic Ash

Volcanic ash is pulverized rock, minerals, and volcanic glass violently ejected during explosive eruptions. It represents one of the most dangerous volcanic phenomena.

Typhon simulates ash clouds using Minecraft's `BlockDisplay` entities to create realistic visual and environmental effects.

<img alt="pyroclastic flows" src="https://github.com/user-attachments/assets/bed69bbf-0d8b-4830-858a-33b94d8e9af1" width="400" />

## Pyroclastic Flows

Pyroclastic flows are among the deadliest volcanic hazards - scalding avalanches of gas, ash, and rock that can travel at extreme speeds and reach temperatures of 1,000°C.

In Typhon, pyroclastic flows are simulated as fast-moving clouds of ash (shown as large `BlockDisplay` entities) cascading down the volcano's slopes during major explosive eruptions., in this cloud the following occurs:

**Features**:
- **Devastating Movement**: Flows race down the volcano's flanks, following valleys and topography
- **Heat Damage**: Superheated clouds ignite anything flammable in their path
- **Ash Deposition**: Flows leave behind layers of tuff (volcanic ash deposits)
- **Carbonization**: Trees caught in the flow are converted to coal blocks
- **Lethal to Players**: Players caught in these flows instantly perish due to extreme heat


## Ash Plumes

Ash plumes can rise miles into the atmosphere during major eruptions. These columns are not just visually spectacular - they impact weather and can collapse to form pyroclastic flows.

**Features**:
- **Towering Columns**: Plumes rise dramatically above the volcano, visible from great distances
- **Thermal Properties**: Superheated ash burns entities caught within the column
- **Realistic Dispersal**: Ash spreads outward at high altitudes, creating mushroom cloud appearance
- **Gradual Settling**: Fine ash particles slowly descend from the sky after major eruptions
- **Lethal to Players**: Going in to the ash plume results in instant death due to extreme heat
