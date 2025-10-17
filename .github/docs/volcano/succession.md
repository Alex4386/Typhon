[<  Return to Typhon Docs](/DOCS.md)  
[<< Return to Volcano Docs](./index.md)  

# Ecological Succession

Ecological succession is the process of landscapes recovering after volcanic devastation, where life gradually reclaims volcanic wastelands through predictable patterns.

Typhon simulates this natural recovery process.

<img src="/.github/docs/volcano/assets/succession.png" width="400" />

## Recovery Stages

Volcanic landscapes recover through ecological succession stages:

### Primary Succession

Pioneer species colonize barren volcanic rock:
- Lichens and mosses create initial soil
- Hardy plants with wind-dispersed seeds follow
- Each plant generation contributes to soil building

Typhon simulates this gradual recovery by some of the volcanic rocks are turning into cobbled variants. and then into grass blocks over time.

**Implementation**:
- Grass blocks gradually appearing on weathered materials
- Initial vegetation in natural succession patterns
- Soil formation building upon previous generations

### Secondary Succession

More complex communities develop as soil improves:
- Shrubs and small trees replace initial colonizers
- Larger tree species establish
- Animal life returns as habitat develops

**Implementation**:
- Transition from grass to diverse vegetation
- Tree growth in progressed succession areas
- Return to natural-looking landscape over time

## Volcanic Activity Impact

Succession processes are influenced by volcanic activity:

- **Active Zones Resist Recovery**: Areas with high [heat values](./status.md) and intense [geothermal activity](./geothermal.md) resist succession

- **Activity Pauses Recovery**: Succession halts during `major activity` or `eruption imminent/erupting` states

- **Zoned Recovery**: Succession advances faster at greater distances from heat sources, progressing from edges inward

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

### Manual Succession

Manually apply succession to specific areas:

1. Activate the succession tool:
   ```
   /typhon succession enable
   ```

2. Use the provided `WOODEN_SHOVEL` by right-clicking on volcanic terrain to trigger succession at that location.

3. When finished, deactivate the tool:
   ```
   /typhon succession disable
   ```
