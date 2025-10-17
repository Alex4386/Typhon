[<  Return to Typhon Docs](/DOCS.md)  
[<< Return to Volcano Docs](./index.md)  

# Caldera Formation

Calderas are massive volcanic depressions formed when a volcano empties its magma chamber during an eruption and the unsupported summit collapses inward.

## Formation Process

Calderas form through this sequence:
1. Volcano builds up through repeated eruptions
2. Magma chamber partially empties during a massive eruption
3. Without support, the summit collapses into the emptied chamber
4. The resulting depression can be many times wider than the original crater

Typhon simulates this geological process.

## Caldera Lakes

Many calderas fill with water over time, breaching local water tables to form lakes.

Typhon allows you to configure whether your caldera will form a lake.

## Creating a Caldera

> [!NOTE]  
> These commands refer to the main vent. For subsidiary vents, please refer to the [Eruption Vents](./vents.md#commands) documentation.

### Setting Up the Caldera Formation

1. If your volcano has undergone caldera formation before, reset it first:  
   `/volcano <volcano_name> mainvent caldera clear`

2. Configure your caldera's dimensions:  
   `/volcano <volcano_name> mainvent caldera <radius> <?depth> <?waterLevel>`

   Parameters:
   - `<radius>`: The radius of the caldera's rim (in blocks)
   - `<depth>` (optional): How deep the caldera will be. If omitted, Typhon calculates a natural depth based on the radius
   - `<waterLevel>` (optional): The Y-coordinate for water if you want a caldera lake. If omitted, Typhon will randomly decide whether to create a lake. Set this below the lowest rim point to prevent lake formation.

3. Begin the cataclysmic event:  
   `/volcano <volcano_name> mainvent caldera start`

### Formation Sequence

Once started, the caldera formation process includes:

- Automatic switch to [`plinian` eruption](./eruption.md#plinian-eruptions), the most violent eruption type
- Massive [volcanic bombs](bombs.md) ejected far beyond the volcano's cone
- Extensive [pyroclastic flows](ash.md#pyroclastic-flows) racing down the mountainsides until the caldera collapse completes
- Summit gradually collapsing inward as underlying support disappears

### Monitoring Your Caldera's Progress

You can check the status of your caldera formation at any time:
`/volcano <volcano_name> mainvent caldera`

### Emergency Controls

If you need to complete the caldera formation immediately (perhaps to reduce server load):
`/volcano <volcano_name> mainvent caldera skip`

This will finalize the caldera structure without generating additional volcanic bombs or pyroclastic flows.

## Caldera Aftermath

After formation, your caldera will permanently transform your volcano:

- The summit area will be replaced by a wide, steep-walled depression
- If a water level was specified (or randomly selected), a beautiful caldera lake will form
- Inside of the caldera will have more intensive geothermal activity, just like the inside of the crater
