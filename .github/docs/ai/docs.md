# Typhon Plugin Feature Documentation

## Basic Commands
```
# Create new volcano
/typhon create <volcano_name>              # Create at current location

# Basic vent setup
/volcano <name> mainvent style <vent_type>     # crater|fissure
/volcano <name> mainvent style <eruption_style> # hawaiian|strombolian|vulcanian

# Basic controls
/volcano <name> mainvent start             # Start eruption
/volcano <name> mainvent stop              # Stop eruption
```

## Visual Effects

### Pyroclastic Flows
- Uses BlockDisplay entities for flow visualization
- Dynamic scaling and terrain following
- LAVA particles for heat visualization
- Burns entities and destroys vegetation
- Deposits tuff blocks in realistic patterns

### Ash Plumes
- Expanding BlockDisplay columns
- Progressive scaling during ascent
- Multiple layers for volume
- Realistic ash (tuff) deposition
- Integrated particle effects

### Volcanic Bombs
- Implemented using FallingBlock entities
- Size varies by eruption intensity (1-5 blocks)
- Creates impact craters on landing
- Breaks fragile blocks in path
- Oozes lava on impact with ground

### Pillow Lava
- Forms when lava meets water
- Uses MAGMA_BLOCK for visualization
- Triggers during underwater eruptions
- Creates realistic underwater formations

### Geothermal Features
- Activity-based behavior:
  - **Dormant**:
    * Minimal steam in crater
    * Low cooking chance
    * No damage effects
  - **Minor Activity**:
    * Increased crater steam
    * Occasional flank fumaroles
    * Low gas concentration
  - **Major Activity**:
    * Widespread fumaroles
    * High gas concentration
    * Tree/plant death
    * Enhanced heat effects
  - **Eruption Imminent/Erupting**:
    * Maximum activity
    * Crater filled with steam
    * Dangerous gas levels
    * Widespread heat damage
- Environmental effects:
  - Kills trees and vegetation
  - Dries out grass blocks
  - Creates steam vents
  - Heats surrounding blocks
- Gas implementation:
  - Chemical burns on entities
  - Nausea effect on players
  - Poison damage system
  - Tool corrosion mechanic
- Heat mechanics:
  - Distance-based intensity
  - Cooking system for food
  - Entity damage scaling
  - Underground temperature gradients

## Major Systems

### Eruption Styles
```
# Basic style controls
/volcano <name> mainvent style <style>    # hawaiian|strombolian|vulcanian
/volcano <name> mainvent start            # Start eruption
/volcano <name> mainvent stop             # Stop eruption

# Fine-tune eruption behavior
/volcano <name> mainvent config bombs:explosionPower:min <value>  # Min bomb power
/volcano <name> mainvent config bombs:explosionPower:max <value>  # Max bomb power
/volcano <name> mainvent config bombs:radius:min <value>          # Min bomb size
/volcano <name> mainvent config bombs:radius:max <value>          # Max bomb size
/volcano <name> mainvent config explosion:scheduler:size <value>  # Bomb launch queue
/volcano <name> mainvent config ash:fullPyroclasticFlowProbability <value>  # Flow range

# Auto-configure based on style
/volcano <name> mainvent config erupt:autoconfig confirm
```
#### Configurable Styles
1. **Hawaiian**:
   - High lava fountains
   - Extensive lava flows
   - Shield volcano formation
   - Rootless cone formation at height
   - Low explosivity

2. **Strombolian**:
   - Regular explosive pulses
   - Volcanic bomb ejection
   - Cinder cone building
   - Moderate intensity
   - Scoria formation

3. **Vulcanian**:
   - Major explosions
   - Significant ash production
   - Pyroclastic flows
   - Mixed lava-explosive activity
   - High intensity bombing

#### Automatic Triggers
1. **Surtseyan** (Water Interaction):
   - Triggers underwater/water-contact
   - Activates when:
     * Summit below sea level
     * Crater water-filled
   - Special steam explosions
   - Unique bomb behavior

2. **Plinian** (Extreme Events):
   - Triggers during:
     * Caldera formation
     * Lateral blast events
   - Maximum explosivity
   - Massive ash columns
   - Extensive pyroclastic flows
   - Largest bomb sizes

### Lava Composition System
```
# Basic composition
/volcano <name> mainvent config lavaflow:silicaLevel <0.0-1.0>  # Silica content
/volcano <name> mainvent config level:gasContent <0.0-1.0>      # Gas content

# Flow behavior
/volcano <name> mainvent config lavaflow:delay <ticks>          # Flow speed
```
- Realistic silica content variation (41-90%):
  - Ultramafic (<41%): Deepslate/Netherrack
  - Mafic (41-45%): Basalt/Deepslate
  - Basaltic (45-52%): Pure basalt
  - Basaltic-Andesitic (52-57%): Andesite/Basalt mix
  - Andesitic (57-63%): Andesite/Tuff
  - Dacitic (63-72%): Granite/Obsidian/Quartz
  - Rhyolitic (>72%): Quartz/Amethyst
- Affects viscosity and flow behavior
- Dynamic ore formation system
- Temperature-based crystallization

### Lateral Blast
```
# Setup and trigger blast
/volcano <name> mainvent landslide clear
/volcano <name> mainvent landslide setAngle <radians>    # Set direction
/volcano <name> mainvent landslide setAngle auto         # Use player's direction
/volcano <name> mainvent landslide start                 # Begin blast
```
- Simulates Mt. St. Helens-style collapse
- Direction can be manually set or auto-calculated
- Creates horseshoe-shaped crater
- Triggers plinian eruption sequence
- Massive terrain modification

### Caldera Formation
```
# Setup and trigger caldera
/volcano <name> mainvent caldera clear                   # Reset caldera
/volcano <name> mainvent caldera <radius> [deep] [oceanY]
/volcano <name> mainvent caldera start                   # Begin formation
/volcano <name> mainvent caldera skip                    # Skip formation sequence
```
- Configurable radius and depth
- Optional caldera lake formation
- Triggers plinian eruption phase
- Complex terrain modification
- Automatic rim calculation

### Succession System
```
# Control succession
/volcano <name> succession <true|false>                  # Global control
/volcano <name> mainvent config succession:enable <true|false>  # Per-vent
/typhon succession enable                               # Manual tool enable
/typhon succession disable                              # Manual tool disable

# Fine-tune succession
/volcano <name> mainvent config succession:probability <0-100>      # Cycle chance
/volcano <name> mainvent config succession:treeProbability <0-100>  # Tree growth chance
```
- Implements primary and secondary succession
- Weather-influenced erosion speed
- Temperature-dependent growth
- Progressive stages:
  1. Rock erosion
  2. Soil formation
  3. Initial plant colonization
  4. Tree growth
- Interrupted by volcanic activity
- Heat-based growth restrictions
- Storm acceleration of processes
- Automatic biome restoration

### Multiple Vent System
```
# Create vents
/volcano <name> create crater <vent_name>      # Create crater vent
/volcano <name> create fissure <vent_name>     # Create fissure vent
/volcano <name> create autovent <vent_name>    # Auto-place vent

# Manage vents
/volcano <name> subvent <vent_name> switch     # Switch main/sub vent
/volcano <name> subvent <vent_name> delete     # Remove vent
/volcano <name> subvent <vent_name> start|stop # Control subvent

# Configure vent properties
/volcano <name> mainvent config vent:craterRadius <radius>   # Set crater size
/volcano <name> mainvent config vent:fissureAngle <radians>  # Set fissure direction
/volcano <name> mainvent config vent:fissureLength <length>  # Set fissure length
/volcano <name> mainvent config vent:type <type>            # crater|fissure

# Set eruption lifecycle
/volcano <name> mainvent genesis               # Check current mode
/volcano <name> mainvent genesis <mode>        # monogenetic|polygenetic
```
- Supports diverse vent types:
  - Crater: Traditional circular vent
  - Fissure: Linear crack eruptions
  - Autovent: Automatic placement
- Vent characteristics:
  - Independent eruption styles
  - Configurable dimensions
  - Individual activity cycles
- Genesis modes:
  - Monogenetic: Single eruption cycle
  - Polygenetic: Multiple eruption cycles
- Dynamic interaction:
  - Vent switching capability
  - Coordinated activity
  - Summit tracking
  - Automatic subsidiary formation

### Underground System
- Heat diffusion through rocks
- Magma conduit simulation
- Underground steam vents
- Deep geothermal activity
- Metamorphic rock formation
- Ore generation in hot zones
- Temperature gradient effects

### Automatic Builder
```
# Builder controls
/volcano <name> mainvent builder y_threshold <height>    # Set height limit
/volcano <name> mainvent builder enable                  # Enable builder
/volcano <name> mainvent builder disable                # Disable builder

# Example: Create stratovolcano (base then top)
# 1. Hawaiian base (y=197):
/volcano <name> mainvent style hawaiian
/volcano <name> mainvent builder y_threshold 197
/volcano <name> mainvent builder enable
/volcano <name> mainvent start

# 2. Vulcanian top (y=264):
/volcano <name> mainvent style vulcanian
/volcano <name> mainvent builder y_threshold 264
/volcano <name> mainvent builder enable
/volcano <name> mainvent start
```
- Implements realistic volcano construction:
  - **Shield Base Creation**:
    * Uses Hawaiian style for fluid base
    * 1/3 slope ratio for basaltic flows
    * Automatic height calculation (2/3 of total)
    * Progressive layer building
  - **Stratovolcano Formation**:
    * Vulcanian style for steep top
    * Alternating layers of material
    * Proper slope calculations
    * Height threshold monitoring
- Building mechanics:
  - Y-level threshold control
  - Automatic eruption termination
  - Layer composition variation
  - Natural terrain blending
- Volcano types supported:
  - Shield volcanoes (gentle slopes)
  - Stratovolcanoes (steep, layered)
  - Cinder cones (steep, uniform)
  - Composite formations
- Integration features:
  - Works with multiple vent systems
  - Coordinates with lava composition
  - Adapts to eruption styles
  - Maintains realistic proportions
- Smart height management:
  - Base/summit ratio calculation
  - Automatic slope adjustment
  - Progress monitoring
  - Layer transition control

Each feature is designed to work together, creating a comprehensive and realistic volcanic system. The implementation focuses on scientific accuracy while working within Minecraft's limitations, resulting in a dynamic and interactive volcanic environment.

