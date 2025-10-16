[<  Return to Typhon Docs](/DOCS.md)  
[<< Return to Volcano Docs](./index.md)  

# Caldera: The Collapsed Giant

In nature, some of Earth's most spectacular geological features are calderas - massive volcanic depressions formed when a volcano empties its magma chamber during an eruption and the unsupported summit collapses inward. From Yellowstone's 30-mile-wide super-caldera to Crater Lake's perfect blue waters in Oregon, calderas represent the most dramatic transformations a volcano can undergo.

## The Science of Caldera Formation

In the real world, calderas form through this sequence:
1. A volcano builds up through repeated eruptions
2. The magma chamber beneath partially empties during a massive eruption
3. Without support, the volcano's summit collapses into the partially emptied chamber
4. The resulting depression can be many times wider than the original summit crater

Typhon faithfully recreates this dramatic geological process, allowing you to witness a catastrophic transformation that would take thousands of years in the natural world.

## Caldera Lakes: Nature's Aftermath from violent disaster

Many real-world calderas fill with water over time, creating some of largest depressions on Earth, breaching local water tables, thus forming lakes.

With Typhon, you can choose whether your caldera will become a lake, creating a unique focal point for your Minecraft world.

## Creating Your Own Caldera Event

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

### Witnessing the Cataclysm

Once started, the caldera formation process is a spectacular event to witness:

- The volcano automatically switches to a [`plinian` eruption](./eruption.md#plinian-eruptions-historys-most-catastrophic-volcanic-events), the most violent type in nature
- Massive [volcanic bombs](bombs.md) are ejected far beyond the volcano's cone
- Extensive [pyroclastic flows](ash.md#pyroclastic-flows) race down the mountainsides
- The summit gradually collapses inward as the underlying support disappears

This simulation mirrors the catastrophic sequence of real caldera-forming eruptions like Krakatoa (1883) or Mount Tambora (1815), events that affected global climate and altered landscapes permanently.

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
- The volcano's eruption behavior may change, as happens in nature after such events
- The landscape surrounding the volcano will bear the scars of the cataclysmic eruption
