[<  Return to Typhon Docs](/DOCS.md)  
[<< Return to Volcano Docs](./index.md)  

# Ecological Succession: Life Returns to Volcanic Landscapes

In nature, the rebirth of landscapes after volcanic devastation is one of Earth's most remarkable recovery stories. From Mount St. Helens' returning forest to Hawaii's lava fields gradually transforming into lush terrain, the process of life reclaiming volcanic wastelands follows predictable patterns studied by ecologists worldwide.

Typhon faithfully recreates this natural recovery process, allowing you to witness years of ecological change in a fraction of the time.

![Succession in progress](/.github/docs/volcano/assets/succession.png)

## Nature's Recovery Blueprint

When a barren volcanic landscape begins its journey back to a thriving ecosystem, it follows a pattern called ecological succession:

### Weathering and Erosion: Breaking Down the Rock

In nature, the first step in recovery involves physical and chemical weathering:
- Wind, rain, and temperature fluctuations crack volcanic rocks
- Storms accelerate the erosion process
- Chemical reactions alter volcanic minerals

In Typhon, this process is simulated through:
- Gradual softening of volcanic landforms
- Accelerated erosion during rainfall and storms
- Formation of soil-like surfaces that can support life

### Primary Succession: Pioneers on the Frontier

In the real world, primary succession begins when "pioneer species" colonize barren volcanic rock:
- Lichens and mosses are often first, creating initial soil
- Small, hardy plants with wind-dispersed seeds follow
- Each generation of plants dies and contributes to building soil

Typhon recreates this through:
- Grass blocks gradually appearing on weathered volcanic materials
- Initial vegetation appearing in patterns similar to natural primary succession
- Soil formation that builds upon previous generations of plants

### Secondary Succession: The Return of Complexity

As soil depth and quality improve in nature, more complex communities develop:
- Shrubs and small trees replace the initial colonizers
- Larger tree species eventually establish
- Animal life returns as habitat becomes available

In Typhon, this manifests as:
- Transition from grass to more diverse vegetation
- Tree growth beginning in areas where succession has progressed further
- Return to a more natural-looking landscape over time

## The Volcano's Heartbeat Controls Recovery

Just as in the natural world, Typhon's succession processes are influenced by volcanic activity:

- **Active Volcanoes Resist Recovery**: The areas near Kilauea's active vents remain barren despite being surrounded by rainforest. Similarly, in Typhon, areas with high [heat values](./status.md) and intense [geothermal activity](./geothermal.md) resist succession.

- **Recovery Pause During Activity**: When real volcanoes enter periods of heightened activity, recovery processes are interrupted. Typhon simulates this by halting succession during `major activity` or `eruption imminent/erupting` states.

- **Zoned Recovery**: In nature, recovery progresses from the edges inward, with areas farthest from vents recovering first. Typhon recreates this pattern, with succession advancing faster at greater distances from the volcano's heat sources.

## Managing Succession in Your World

### Global Volcano Succession Controls

Enable or disable succession processes for an entire volcano:
```
/volcano <volcano_name> succession <true|false>
```

### Individual Vent Succession Controls

> [!NOTE]  
> These commands refer to the main vent. For subsidiary vents, please refer to the [Eruption Vents](./vents.md#commands) documentation.

Control succession around specific vents:
```
/volcano <volcano_name> mainvent config succession:enable <true|false>
```

### Manual Succession: Terraforming with Nature's Patterns

You can manually apply succession to specific areas, mimicking the transformative work of ecologists helping to recover Mount St. Helens and other volcanic sites:

1. Activate the succession tool:
   ```
   /typhon succession enable
   ```

2. Use the provided `WOODEN_SHOVEL` by right-clicking on volcanic terrain to trigger the succession process at that location.

3. When finished, deactivate the tool:
   ```
   /typhon succession disable
   ```

By incorporating ecological succession, Typhon doesn't just simulate the destructive power of volcanoes - it also recreates nature's remarkable ability to heal and transform even the most devastated landscapes.

