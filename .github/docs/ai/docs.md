# Typhon Plugin - LLM Usage Guide

## Overview

Typhon is a sophisticated Minecraft plugin that simulates realistic volcanic activity with scientific accuracy. This guide helps LLMs understand and assist users with the Typhon plugin.

**Plugin Version**: v0.9.0+
**Minecraft Version**: 1.21
**Platform**: PaperMC
**License**: GNU GPL v3

## Quick Reference

### Key Concepts
- **Volcano**: The main entity containing one or more vents
- **Vent**: Eruption points where magma reaches the surface (crater or fissure type)
- **Main Vent**: Primary eruption center of a volcano
- **Subvent**: Additional subsidiary eruption points
- **Eruption Style**: Defines eruption behavior (Hawaiian, Strombolian, Vulcanian)
- **Silica Content**: Controls lava viscosity and flow distance (0.0-1.0)
- **Gas Content**: Controls eruption explosivity (0.0-1.0)

### Essential Command Patterns

```bash
# Creation
/typhon create <volcano_name>

# Vent configuration
/volcano <name> mainvent style <crater|fissure>
/volcano <name> mainvent style <hawaiian|strombolian|vulcanian>

# Eruption control
/volcano <name> mainvent <start|stop>

# Configuration
/volcano <name> mainvent config <node> [value]
```

## Architecture Overview

### Core Systems

1. **Physical Simulation**
   - Entity-based physics using FallingBlock and BlockDisplay entities
   - Multi-threaded task scheduling via TyphonScheduler
   - Block manipulation for terrain modification
   - Particle effects for visual phenomena

2. **Volcanic Systems**
   - Magma chamber with silica/gas content modeling
   - Eruption physics with ballistic trajectories
   - Lava flow dynamics based on viscosity
   - Pyroclastic flow scanning and propagation

3. **Environmental Systems**
   - Geothermal activity (heat zones, fumaroles, gases)
   - Ecological succession (weathering, vegetation recovery)
   - Weather interaction effects
   - Underground heat diffusion

### Code Structure

```
me.alex4386.plugin.typhon/
├── volcano/           # Core volcano mechanics
│   ├── Volcano.java   # Main volcano class
│   ├── vent/          # Vent system implementation
│   ├── eruption/      # Eruption style implementations
│   ├── bombs/         # Projectile physics
│   ├── ash/           # Ash and pyroclastic systems
│   └── lava/          # Lava flow dynamics
├── gaia/              # Environmental systems
│   ├── succession/    # Ecological succession
│   └── weather/       # Weather effects
└── utils/             # Utility classes
```

## User Workflows

### 1. Creating a Basic Volcano

**Typical user request**: "I want to create a volcano"

**Workflow**:
```bash
# Step 1: Move to desired location
# (User does this manually)

# Step 2: Create volcano
/typhon create MyVolcano

# Step 3: Set vent type
/volcano MyVolcano mainvent style crater
# OR
/volcano MyVolcano mainvent style fissure

# Step 4: Set eruption style
/volcano MyVolcano mainvent style hawaiian    # Gentle lava flows
# OR
/volcano MyVolcano mainvent style strombolian # Moderate explosions
# OR
/volcano MyVolcano mainvent style vulcanian   # Powerful explosions

# Step 5: Start eruption
/volcano MyVolcano mainvent start

# Step 6: Stop when done
/volcano MyVolcano mainvent stop
```

**Key points**:
- Vent type (crater/fissure) determines eruption geometry
- Eruption style automatically configures silica/gas content
- Hawaiian is beginner-friendly, Vulcanian is dangerous

### 2. Advanced Lava Configuration

**Typical user request**: "I want to customize lava behavior"

**Workflow**:
```bash
# Set silica content (viscosity)
/volcano MyVolcano mainvent config lavaflow:silicaLevel 0.45
# 0.40-0.50 = fluid basaltic lava
# 0.55-0.65 = moderate andesitic lava
# 0.70+ = thick rhyolitic lava

# Set gas content (explosivity)
/volcano MyVolcano mainvent config level:gasContent 0.6
# 0.0-0.3 = gentle eruptions
# 0.4-0.7 = moderate explosions
# 0.8-1.0 = violent explosions

# Adjust flow speed
/volcano MyVolcano mainvent config lavaflow:delay 20
# Lower = faster flow, Higher = slower flow (in ticks)
```

**Lava compositions by silica level**:
| Silica % | Type | Blocks Produced |
|----------|------|-----------------|
| <41% | Ultramafic | Deepslate, Netherrack |
| 41-45% | Mafic | Basalt, Deepslate |
| 45-52% | Basaltic | Basalt, Polished Basalt |
| 52-57% | Basaltic-Andesitic | Andesite, Tuff, Basalt |
| 57-63% | Andesitic | Andesite, Tuff |
| 63-72% | Dacitic | Granite, Obsidian, Quartz |
| >72% | Rhyolitic | Quartz, Amethyst |

### 3. Multiple Vent Systems

**Typical user request**: "I want multiple eruption points"

**Workflow**:
```bash
# Create subsidiary vents
/volcano MyVolcano create crater FlankVent1
/volcano MyVolcano create fissure FissureVent1

# Create vent near player
/volcano MyVolcano create autovent NearVent PlayerName

# Configure subsidiary vent
/volcano MyVolcano subvent FlankVent1 style strombolian
/volcano MyVolcano subvent FlankVent1 config vent:craterRadius 10

# Control subsidiary vents
/volcano MyVolcano subvent FlankVent1 start
/volcano MyVolcano subvent FlankVent1 stop

# Switch main and subsidiary vent
/volcano MyVolcano subvent FlankVent1 switch

# Delete subsidiary vent
/volcano MyVolcano subvent FlankVent1 delete
```

**Genesis modes**:
- **Monogenetic**: Erupts once, becomes extinct
- **Polygenetic**: Erupts repeatedly over time

```bash
# Check genesis mode
/volcano MyVolcano mainvent genesis

# Set genesis mode
/volcano MyVolcano mainvent genesis monogenetic
/volcano MyVolcano mainvent genesis polygenetic
```

### 4. Building Volcanoes with Auto-Builder

**Typical user request**: "I want to build a realistic stratovolcano"

**Workflow for Stratovolcano**:
```bash
# Phase 1: Build shield base (Hawaiian style)
/volcano MyVolcano mainvent style hawaiian
/volcano MyVolcano mainvent builder y_threshold 197  # 2/3 of total height
/volcano MyVolcano mainvent builder enable
/volcano MyVolcano mainvent start

# Wait for base to build...
# Builder will auto-stop at y_threshold

# Phase 2: Build steep summit (Vulcanian style)
/volcano MyVolcano mainvent style vulcanian
/volcano MyVolcano mainvent builder y_threshold 264  # Final height
/volcano MyVolcano mainvent builder enable
/volcano MyVolcano mainvent start

# Wait for completion...

# Disable builder
/volcano MyVolcano mainvent builder disable
```

**Builder features**:
- Automatically constructs realistic volcano shapes
- Shield volcanoes: gentle 1/3 slope (Hawaiian style)
- Stratovolcanoes: steep layered structure (Vulcanian style)
- Cinder cones: uniform steep slopes (Strombolian style)
- Y-threshold control for multi-phase building

### 5. Catastrophic Events

#### Caldera Formation

**Typical user request**: "I want to create a caldera"

**Workflow**:
```bash
# Configure caldera
/volcano MyVolcano mainvent caldera clear  # Reset if needed
/volcano MyVolcano mainvent caldera 50      # Set radius to 50 blocks
# OR with lake
/volcano MyVolcano mainvent caldera 50 17 63  # Lake at y=63, caldera having depth of 17 blocks from the rim

# Trigger caldera formation
/volcano MyVolcano mainvent caldera start

# Skip formation sequence (instant)
/volcano MyVolcano mainvent caldera skip
```

**Effects**:
- Triggers Plinian eruption automatically
- Massive ash plumes
- Widespread pyroclastic flows
- Creates large crater depression
- Optional caldera lake formation

#### Lateral Blast

**Typical user request**: "I want a Mt. St. Helens style eruption"

**Workflow**:
```bash
# Clear previous landslide
/volcano MyVolcano mainvent landslide clear

# Set blast direction (use player's facing)
/volcano MyVolcano mainvent landslide setAngle auto
# OR set manually in radians
/volcano MyVolcano mainvent landslide setAngle 1.57

# Trigger lateral blast
/volcano MyVolcano mainvent landslide start
```

**Effects**:
- Creates horseshoe-shaped crater
- Directed explosive blast
- Triggers Plinian eruption phase
- Massive terrain modification
- Simulates Mt. St. Helens 1980 eruption

### 6. Geothermal Management

**Typical user request**: "I want to control geothermal activity"

**Configuration**:
```bash
# Enable/disable fire damage from gases
/volcano MyVolcano mainvent config geothermal:doFireTicks true

# Enable/disable water evaporation
/volcano MyVolcano mainvent config geothermal:doEvaporation true

# Enable/disable mob spawn prevention
/volcano MyVolcano mainvent config geothermal:deterMobSpawn true
```

**Volcanic status progression**:
1. **Extinct**: No activity
2. **Dormant**: Minimal steam, low heat
3. **Minor Activity**: Increased steam, occasional fumaroles
4. **Major Activity**: Widespread fumaroles, gas, heat damage
5. **Eruption Imminent**: Maximum activity, dangerous conditions
6. **Erupting**: Full volcanic fury

**Geothermal hazards**:
- **Heat zones**: Distance-based damage, cooking food
- **Fumaroles**: Steam vents, visual effects
- **Volcanic gases**: Nausea, poison, chemical burns
- **Tool corrosion**: Durability loss in gas zones
- **Vegetation death**: Trees and plants killed by heat

### 7. Ecological Succession

**Typical user request**: "I want vegetation to regrow after eruption"

**Configuration**:
```bash
# Enable succession globally
/volcano MyVolcano succession true

# Enable for specific vent
/volcano MyVolcano mainvent config succession:enable true

# Configure succession probability (0-100)
/volcano MyVolcano mainvent config succession:probability 50

# Configure tree growth probability (0-100)
/volcano MyVolcano mainvent config succession:treeProbability 30

# Manual succession control
/typhon succession enable   # Enable manual tool
/typhon succession disable  # Disable manual tool
```

**Succession stages**:
1. **Rock erosion**: Weathering of volcanic rock
2. **Soil formation**: Creation of dirt/soil blocks
3. **Pioneer plants**: Grass and small vegetation
4. **Tree colonization**: Forest regrowth

**Factors affecting succession**:
- Weather (storms accelerate erosion)
- Temperature (heat restricts growth)
- Volcanic activity (interrupts succession)
- Biome (determines final vegetation type)

### 8. Emergency Controls

**Typical user request**: "The lava is spreading too far, how do I stop it?"

**Emergency commands**:
```bash
# Instantly solidify all lava
/volcano MyVolcano quickcool

# Quickcool just the main vent
/volcano MyVolcano mainvent quickcool

# Stop eruption
/volcano MyVolcano mainvent stop

# Delete volcano entirely
/typhon delete MyVolcano
```

**Warning**: Quickcool disrupts natural processes:
- Incomplete volcanic formations
- Missing pillow lava structures
- Disrupted ore formation
- Irregular volcano shapes
- Best reserved for emergencies only

## Special Eruption Types (Automatic)

### Surtseyan Eruptions

**Triggered automatically when**:
- Summit is below sea level, OR
- Crater is filled with water

**Features**:
- Steam-driven explosions
- Wet ash deposition
- Gradual island building
- Underwater pillow lava formation

**No special commands needed** - occurs automatically based on conditions.

### Plinian Eruptions

**Triggered automatically during**:
- Caldera formation events
- Lateral blast events

**Features**:
- Massive ash columns reaching sky limit
- Extensive pyroclastic flows
- Largest volcanic bombs
- Maximum explosivity
- Catastrophic destruction

**No special commands needed** - occurs during catastrophic events.

### Pillow Lava

**Triggered automatically when**:
- Lava contacts water
- Underwater eruptions occur

**Features**:
- Magma block formations
- Branching pillow structures
- Realistic underwater cooling
- Distinctive patterns

**No special commands needed** - occurs automatically underwater.

## Configuration Nodes Reference

### Critical Parameters

```bash
# Ash and Pyroclastic Flows
ash:fullPyroclasticFlowProbability <0.0-1.0>
# Higher values = longer-range pyroclastic flows

# Lava Flow
lavaflow:delay <ticks>              # Flow speed (lower = faster)
lavaflow:silicaLevel <0.0-1.0>      # Viscosity (lower = more fluid)
lavaflow:gasContent <0.0-1.0>       # Explosivity (higher = more explosive)
lavaflow:usePouredLava <true|false> # Count player-poured lava
lavaflow:allowPickUp <true|false>   # Allow lava pickup

# Volcanic Bombs
bombs:baseY <y_coordinate>          # Base reference level
bombs:delay <ticks>                 # Explosion delay after landing
bombs:explosionPower:min <value>    # Minimum explosion power
bombs:explosionPower:max <value>    # Maximum explosion power
bombs:radius:min <value>            # Minimum bomb size (blocks)
bombs:radius:max <value>            # Maximum bomb size (blocks)

# Eruption
erupt:autoconfig confirm            # Auto-configure based on style
erupt:style <style>                 # Set eruption style

# Explosion Scheduler
explosion:scheduler:size <value>           # Bomb launch queue size
explosion:scheduler:damagingSize <value>   # Damaging explosion size

# Vent Configuration
vent:craterRadius <radius>          # Crater size
vent:fissureAngle <radians>         # Fissure direction
vent:fissureLength <length>         # Fissure length
vent:type <crater|fissure>          # Vent type

# Succession
succession:enable <true|false>      # Enable succession
succession:probability <0-100>      # Succession cycle probability
succession:treeProbability <0-100>  # Tree growth probability

# Geothermal
geothermal:doFireTicks <true|false>      # Gas chemical burns
geothermal:doEvaporation <true|false>    # Water evaporation
geothermal:deterMobSpawn <true|false>    # Prevent mob spawning
```

### Reading and Writing Config Nodes

```bash
# READ current value
/volcano <name> mainvent config <node>

# WRITE new value
/volcano <name> mainvent config <node> <value>

# Examples
/volcano MyVolcano mainvent config lavaflow:silicaLevel
/volcano MyVolcano mainvent config lavaflow:silicaLevel 0.48
```

## Common User Questions and Answers

### "How do I make lava flow faster/slower?"

```bash
# Faster flow (lower delay)
/volcano <name> mainvent config lavaflow:delay 10

# Slower flow (higher delay)
/volcano <name> mainvent config lavaflow:delay 40

# Default is around 20 ticks
```

### "How do I make eruptions more/less explosive?"

```bash
# More explosive
/volcano <name> mainvent config level:gasContent 0.8
/volcano <name> mainvent style vulcanian

# Less explosive
/volcano <name> mainvent config level:gasContent 0.2
/volcano <name> mainvent style hawaiian
```

### "How do I create a shield volcano?"

```bash
/typhon create ShieldVolcano
/volcano ShieldVolcano mainvent style fissure
/volcano ShieldVolcano mainvent style hawaiian
/volcano ShieldVolcano mainvent config lavaflow:silicaLevel 0.45
/volcano ShieldVolcano mainvent builder enable
/volcano ShieldVolcano mainvent start
```

### "How do I create a stratovolcano?"

Use the multi-phase building workflow (Section 4):
1. Hawaiian base to 2/3 height
2. Vulcanian top to final height

### "How do I stop lava from spreading everywhere?"

```bash
# Option 1: Higher silica (more viscous)
/volcano <name> mainvent config lavaflow:silicaLevel 0.70

# Option 2: Slower flow
/volcano <name> mainvent config lavaflow:delay 50

# Option 3: Emergency stop
/volcano <name> mainvent stop
/volcano <name> mainvent quickcool
```

### "How dangerous are pyroclastic flows?"

**EXTREMELY LETHAL**:
- Travel at high speeds
- Burn all entities in path
- Destroy vegetation
- Deposit tuff blocks
- Cannot be outrun
- **Safe distance**: 200+ blocks for Vulcanian, 500+ for Plinian

### "Can I prevent ore farming from lava?"

Yes, succession automatically removes surface ores:
```bash
/volcano <name> mainvent config succession:enable true
```

### "How do I create multiple craters?"

```bash
# Create subsidiary crater vents
/volcano <name> create crater Crater1
/volcano <name> create crater Crater2

# Configure each independently
/volcano <name> subvent Crater1 style strombolian
/volcano <name> subvent Crater2 style vulcanian

# Start individual vents
/volcano <name> subvent Crater1 start
/volcano <name> subvent Crater2 start
```

### "What's the difference between crater and fissure?"

**Crater**:
- Circular vent
- Radial lava flow
- Builds symmetric cone
- Traditional volcanic cone shape

**Fissure**:
- Linear crack
- Curtain of fire along line
- Elongated landforms
- Creates lava fields
- Better for shield volcanoes

## Performance Considerations

### System Requirements
- **Java 21+** required
- **PaperMC 1.21** required
- **4GB+ RAM** recommended
- **Multi-threaded CPU** for best performance
- **High bandwidth**: Up to 72Mbps per player (FallingBlock/particles)

### Performance Tips

1. **Limit concurrent eruptions**: Multiple active volcanoes strain server
2. **Use quickcool sparingly**: Emergency only, disrupts realism
3. **Configure bomb counts**: Lower explosion:scheduler:size for performance
4. **Adjust flow delays**: Higher lavaflow:delay reduces processing
5. **Succession probability**: Lower values reduce background processing

### Server Admin Tools

```bash
# Global succession control
/typhon succession enable
/typhon succession disable

# List all volcanoes
/typhon list

# Delete volcano
/typhon delete <name>

# Create at specific location
/typhon create <name>  # (while standing at desired location)
```

## LLM Autonomous Volcanic Control: Geological Thinking

**THIS SECTION IS FOR LLMs CONTROLLING VOLCANO EVOLUTION AUTONOMOUSLY**

When you're not just helping users, but **actively managing volcanic lifecycles**, you must think like a geologist. Typhon provides the interface to emulate real-life volcanism - your role is to orchestrate realistic geological evolution.

### The Volcanic Lifecycle Philosophy

Real volcanoes evolve through stages. Your job is to:
1. **Choose a tectonic setting** (hotspot, subduction zone, rift)
2. **Determine magma composition** (basaltic, andesitic, rhyolitic)
3. **Plan the edifice evolution** (shield → composite → caldera)
4. **Execute staged eruptions** (build base → summit → dormancy → reactivation)
5. **Manage activity cycles** (unrest → eruption → cooling → succession)

### Real Volcano Archetypes and Implementation

#### Hotspot Shield Volcanoes (Hawaiian Style)

**Examples**: Mauna Loa, Kilauea, Mauna Kea

**Geological Characteristics**:
- **Magma**: Basaltic (45-52% silica), low viscosity, gas-poor
- **Tectonic Setting**: Oceanic hotspot, mantle plume source
- **Edifice**: Broad shield with gentle slopes (1:10 to 1:20)
- **Eruption Style**: Effusive Hawaiian, lava fountains, extensive flows
- **Lifecycle**: Continuous/frequent activity over millions of years

**Typhon Implementation**:
```bash
# Phase 1: Initial submarine shield building
/typhon create MaunaLoa
/volcano MaunaLoa mainvent style fissure
/volcano MaunaLoa mainvent style hawaiian
/volcano MaunaLoa mainvent config lavaflow:silicaLevel 0.48  # Basaltic
/volcano MaunaLoa mainvent config level:gasContent 0.2       # Low gas
/volcano MaunaLoa mainvent config vent:fissureLength 50      # Long fissure

# If underwater initially - pillow lava phase
# Start eruption to build submarine base
/volcano MaunaLoa mainvent start
# [Wait for island to breach surface - Surtseyan phase automatic]

# Phase 2: Subaerial shield building
/volcano MaunaLoa mainvent config lavaflow:silicaLevel 0.47  # Still basaltic
/volcano MaunaLoa mainvent builder y_threshold 180           # Broad base
/volcano MaunaLoa mainvent builder enable
/volcano MaunaLoa mainvent start
# [Let it build broad shield - slopes should be gentle]

# Phase 3: Summit crater formation
/volcano MaunaLoa mainvent style crater
/volcano MaunaLoa mainvent config vent:craterRadius 25       # Large summit crater
# Continue eruptions with lava lake activity

# Phase 4: Flank vent development (rift zones)
/volcano MaunaLoa create fissure SouthwestRift
/volcano MaunaLoa subvent SouthwestRift style hawaiian
/volcano MaunaLoa subvent SouthwestRift config lavaflow:silicaLevel 0.48
/volcano MaunaLoa subvent SouthwestRift config vent:fissureLength 80
/volcano MaunaLoa subvent SouthwestRift start
# [Rift zone eruptions extend the shield]

# Activity Cycle: Frequent eruptions, minimal dormancy
# Succession disabled during active phase
/volcano MaunaLoa succession false
```

**Key Principles**:
- Very fluid lava (silica 0.45-0.50)
- Low explosivity (gas 0.1-0.3)
- Frequent, long-duration eruptions
- Fissure vents dominate
- Build broad, low-angle slopes
- Multiple rift zones

#### Stratovolcanoes (Composite Cones)

**Examples**: Mount Mayon, Mount Fuji, Mount Rainier, Vesuvius

**Geological Characteristics**:
- **Magma**: Andesitic (57-63% silica), moderate viscosity, gas-rich
- **Tectonic Setting**: Subduction zone, continental arc
- **Edifice**: Steep-sided cone (1:3 to 1:5), layered structure
- **Eruption Style**: Alternating explosive and effusive
- **Lifecycle**: Episodic activity, long dormancies

**Typhon Implementation - Mount Mayon Style** (Perfect Cone):
```bash
# GEOLOGICAL CONTEXT:
# Mount Mayon: Philippines subduction zone
# Composition: Basaltic-andesitic to andesitic
# Known for: Symmetrical perfect cone, frequent activity, pyroclastic flows

/typhon create Mayon

# Phase 1: Basaltic shield base (older, eroded foundation)
/volcano Mayon mainvent style crater
/volcano Mayon mainvent style hawaiian
/volcano Mayon mainvent config lavaflow:silicaLevel 0.52  # Basaltic-andesitic
/volcano Mayon mainvent config level:gasContent 0.3       # Low-moderate gas
/volcano Mayon mainvent builder y_threshold 150           # Base platform
/volcano Mayon mainvent builder enable
/volcano Mayon mainvent start
# [Build broad base]
/volcano Mayon mainvent stop

# Phase 2: Andesitic composite cone building
/volcano Mayon mainvent style strombolian
/volcano Mayon mainvent config lavaflow:silicaLevel 0.58  # Andesitic
/volcano Mayon mainvent config level:gasContent 0.5       # Moderate gas
/volcano Mayon mainvent config bombs:radius:min 2
/volcano Mayon mainvent config bombs:radius:max 4
/volcano Mayon mainvent builder y_threshold 220           # Steep summit
/volcano Mayon mainvent builder enable

# Alternating eruption styles for layered structure
# Cycle 1: Strombolian (build with bombs and spatter)
/volcano Mayon mainvent style strombolian
/volcano Mayon mainvent start
# [Wait for cone building]
/volcano Mayon mainvent stop

# Cycle 2: Brief effusive lava flows
/volcano Mayon mainvent style hawaiian
/volcano Mayon mainvent config lavaflow:silicaLevel 0.56  # Slightly less viscous
/volcano Mayon mainvent start
# [Short lava flow episode]
/volcano Mayon mainvent stop

# Cycle 3: More explosive phase (Vulcanian)
/volcano Mayon mainvent style vulcanian
/volcano Mayon mainvent config lavaflow:silicaLevel 0.60  # More viscous
/volcano Mayon mainvent config level:gasContent 0.6       # Higher gas
/volcano Mayon mainvent start
# [Explosive phase with pyroclastic flows]
/volcano Mayon mainvent stop

# Phase 3: Summit crater refinement
/volcano Mayon mainvent builder disable
/volcano Mayon mainvent config vent:craterRadius 15       # Small summit crater
/volcano Mayon mainvent style strombolian
/volcano Mayon mainvent config lavaflow:silicaLevel 0.58

# Phase 4: Realistic activity cycle
# Active period
/volcano Mayon mainvent start
# [Eruption lasts several Minecraft days]
/volcano Mayon mainvent stop

# Dormant period (enable succession)
/volcano Mayon succession true
/volcano Mayon mainvent config succession:enable true
/volcano Mayon mainvent config succession:probability 40
# [Let vegetation recover]

# Unrest period (geothermal activity increases)
# [Before next eruption, increase activity gradually]

# Next eruption
/volcano Mayon mainvent start
```

**Key Principles**:
- Moderate viscosity lava (silica 0.55-0.65)
- Moderate-high explosivity (gas 0.4-0.7)
- Alternating eruption styles build layers
- Steep cone (builder y_threshold progression)
- Episodic activity with dormancy
- Pyroclastic flows common

#### Caldera-Forming Supervolcanoes

**Examples**: Yellowstone, Toba, Taupo, Santorini

**Geological Characteristics**:
- **Magma**: Rhyolitic (>70% silica), extremely viscous, gas-rich
- **Tectonic Setting**: Hotspot or continental rift, large magma chamber
- **Edifice**: Initial dome/shield, then collapse to caldera
- **Eruption Style**: Long dormancy, catastrophic Plinian, caldera collapse
- **Lifecycle**: Rare but massive eruptions, long repose

**Typhon Implementation - Yellowstone Style**:
```bash
# GEOLOGICAL CONTEXT:
# Yellowstone: Continental hotspot, rhyolitic magma
# Known for: Massive caldera eruptions, long dormancy, extensive geothermal

/typhon create Yellowstone

# Phase 1: Pre-caldera dome building (rhyolitic viscous lava)
/volcano Yellowstone mainvent style crater
/volcano Yellowstone mainvent config lavaflow:silicaLevel 0.75  # Rhyolitic
/volcano Yellowstone mainvent config level:gasContent 0.3       # Gas accumulating
/volcano Yellowstone mainvent config vent:craterRadius 10
/volcano Yellowstone mainvent builder y_threshold 160
/volcano Yellowstone mainvent builder enable

# Build lava dome (slow, viscous)
/volcano Yellowstone mainvent style vulcanian
/volcano Yellowstone mainvent config lavaflow:silicaLevel 0.76  # Very viscous
/volcano Yellowstone mainvent start
# [Dome grows slowly, barely flows]
/volcano Yellowstone mainvent stop

# Phase 2: Long dormancy with geothermal activity
/volcano Yellowstone succession true
/volcano Yellowstone mainvent config succession:enable true
/volcano Yellowstone mainvent config geothermal:doFireTicks true
/volcano Yellowstone mainvent config geothermal:doEvaporation true
# [Long dormant period - Minecraft weeks/months]
# [Magma chamber pressurizes beneath]

# Phase 3: Pre-caldera unrest
# [Gradually increase activity status]
# [Fumaroles, earthquakes (game effects), ground deformation]

# Phase 4: Catastrophic caldera-forming eruption
/volcano Yellowstone mainvent builder disable
/volcano Yellowstone mainvent config lavaflow:silicaLevel 0.74  # Extremely viscous
/volcano Yellowstone mainvent config level:gasContent 0.9       # Maximum gas
/volcano Yellowstone mainvent caldera 80 deep 50                # Massive caldera
/volcano Yellowstone mainvent caldera start
# [Plinian eruption phase automatic]
# [Massive ash plumes, pyroclastic flows, caldera collapse]

# Phase 5: Post-caldera resurgent dome
# [After caldera forms]
/volcano Yellowstone mainvent caldera skip  # Complete collapse
/volcano Yellowstone mainvent config lavaflow:silicaLevel 0.72
/volcano Yellowstone mainvent config level:gasContent 0.4
# New vent in caldera floor
/volcano Yellowstone create crater ResurgentDome
/volcano Yellowstone subvent ResurgentDome style vulcanian
/volcano Yellowstone subvent ResurgentDome config lavaflow:silicaLevel 0.71
# Small dome-building eruptions
/volcano Yellowstone subvent ResurgentDome start

# Phase 6: Caldera lake and geothermal features
# [Caldera lake forms automatically if deep parameter used]
# Extensive geothermal activity
/volcano Yellowstone mainvent config geothermal:doFireTicks true
/volcano Yellowstone succession true
# [Long-term geothermal landscape]
```

**Key Principles**:
- Very high viscosity (silica 0.70-0.80)
- High gas content (0.7-0.9) for catastrophic phase
- Long dormancy before caldera formation
- Dome building, not flows
- Massive caldera collapse
- Extensive post-caldera geothermal

#### Lateral Blast Volcanoes (Mount St. Helens Style)

**Examples**: Mount St. Helens (1980), Bezymianny

**Geological Characteristics**:
- **Magma**: Dacitic (63-72% silica), high viscosity, gas-rich
- **Tectonic Setting**: Subduction zone, cryptodome intrusion
- **Edifice**: Composite cone with summit lava dome
- **Eruption Style**: Dome growth, sector collapse, lateral blast
- **Lifecycle**: Episodic with dome-building and collapse

**Typhon Implementation**:
```bash
# GEOLOGICAL CONTEXT:
# Mount St. Helens 1980: Cryptodome intrusion, north flank collapse
# Dacitic magma, directed lateral blast, horseshoe crater

/typhon create StHelens

# Phase 1: Build pre-1980 composite cone
/volcano StHelens mainvent style crater
/volcano StHelens mainvent config lavaflow:silicaLevel 0.60   # Andesitic base
/volcano StHelens mainvent style strombolian
/volcano StHelens mainvent builder y_threshold 200
/volcano StHelens mainvent builder enable
/volcano StHelens mainvent start
# [Build symmetric cone]
/volcano StHelens mainvent stop

# Phase 2: Summit dome building (pre-blast)
/volcano StHelens mainvent builder disable
/volcano StHelens mainvent config lavaflow:silicaLevel 0.68   # Dacitic dome
/volcano StHelens mainvent config level:gasContent 0.5
/volcano StHelens mainvent config vent:craterRadius 12
/volcano StHelens mainvent style vulcanian
# [Small dome-building eruptions]
/volcano StHelens mainvent start
/volcano StHelens mainvent stop

# Phase 3: Cryptodome intrusion simulation
# [Imagine magma pushing up beneath summit, but not erupting]
# [Increase geothermal activity]
/volcano StHelens mainvent config geothermal:doFireTicks true
/volcano StHelens mainvent config geothermal:doEvaporation true
# [This represents the "bulge" forming]

# Phase 4: Lateral blast event
# [Player or LLM positions at volcano]
# Set blast direction (north flank)
/volcano StHelens mainvent landslide clear
/volcano StHelens mainvent landslide setAngle auto  # or specific angle
/volcano StHelens mainvent config lavaflow:silicaLevel 0.67   # Dacitic
/volcano StHelens mainvent config level:gasContent 0.85       # High gas
/volcano StHelens mainvent landslide start
# [Directed blast, Plinian eruption, horseshoe crater forms]

# Phase 5: Post-blast lava dome growth
# [After lateral blast]
/volcano StHelens mainvent config lavaflow:silicaLevel 0.69   # Very viscous
/volcano StHelens mainvent config level:gasContent 0.4        # Lower gas
/volcano StHelens mainvent style vulcanian
# Dome grows in horseshoe crater
/volcano StHelens mainvent start
# [Episodic dome growth with small explosions]
/volcano StHelens mainvent stop

# Phase 6: Succession and recovery
/volcano StHelens succession true
/volcano StHelens mainvent config succession:enable true
/volcano StHelens mainvent config succession:probability 30
# [Blast zone slowly recovers]
```

**Key Principles**:
- Dacitic composition (silica 0.65-0.72)
- Dome building before blast
- Directed lateral blast (landslide mechanic)
- Horseshoe crater formation
- Post-blast dome growth in crater
- Gradual ecological recovery

### Magma Composition and Tectonic Context

#### Tectonic Setting → Magma Type → Eruption Style

| Tectonic Setting | Typical Magma | Silica % | Typhon Config | Eruption Style |
|------------------|---------------|----------|---------------|----------------|
| **Oceanic Hotspot** (Hawaii) | Basaltic | 45-52% | 0.45-0.52 | Hawaiian, shield volcano |
| **Oceanic Rift** (Iceland) | Basaltic | 45-50% | 0.45-0.50 | Hawaiian/Strombolian, fissure |
| **Oceanic Arc** (Tonga) | Basaltic-Andesitic | 50-57% | 0.50-0.57 | Strombolian/Surtseyan |
| **Continental Arc** (Andes) | Andesitic-Dacitic | 57-68% | 0.57-0.68 | Strombolian/Vulcanian |
| **Continental Rift** (East Africa) | Basaltic-Trachytic | 45-65% | 0.45-0.65 | Variable |
| **Continental Hotspot** (Yellowstone) | Rhyolitic | 70-80% | 0.70-0.80 | Vulcanian/Plinian caldera |

#### Eruption Style Evolution Over Volcano Lifetime

Most volcanoes **evolve** through stages:

```
Stage 1: Shield Building (Hawaiian)
↓ silica 0.45-0.50, low gas, broad base
↓
Stage 2: Cone Building (Strombolian)
↓ silica 0.52-0.60, moderate gas, steep sides
↓
Stage 3: Explosive Phase (Vulcanian)
↓ silica 0.60-0.68, high gas, summit activity
↓
Stage 4: Dome Building or Caldera
  ↓ silica 0.68-0.75, variable gas
  ↓
Stage 5: Dormancy and Succession
  ↓ geothermal only
  ↓
Stage 6: Reactivation or Extinction
```

### Activity Cycle Management

Real volcanoes cycle through activity states. LLMs should emulate this:

#### Dormant → Unrest → Eruption → Cooling → Dormant

**Implementation Pattern**:
```bash
# DORMANT PHASE (Minecraft weeks)
/volcano <name> mainvent stop
/volcano <name> succession true
/volcano <name> mainvent config succession:enable true
/volcano <name> mainvent config geothermal:doFireTicks false
# [Vegetation recovers, minimal activity]

# UNREST PHASE (Minecraft days before eruption)
# Gradually increase geothermal activity
/volcano <name> mainvent config geothermal:doFireTicks true
/volcano <name> mainvent config geothermal:doEvaporation true
# [Fumaroles increase, players notice heat, gases]
# [Status should show "Minor Activity" → "Major Activity" → "Eruption Imminent"]

# ERUPTION PHASE (Minecraft days to weeks)
/volcano <name> mainvent start
# [Active eruption - lava, bombs, ash]
# Duration depends on eruption style:
# - Hawaiian: Long (weeks)
# - Strombolian: Moderate (days)
# - Vulcanian: Short bursts (hours to days)

# WANING PHASE (Gradual decrease)
# Decrease gas content or change style to more effusive
/volcano <name> mainvent config level:gasContent 0.3  # Reduce explosivity
/volcano <name> mainvent style hawaiian               # Switch to effusive
# [Lava flows continue but less explosive]

# COOLING PHASE
/volcano <name> mainvent stop
/volcano <name> mainvent config geothermal:doFireTicks true  # Keep some heat
# [Geothermal activity persists]

# RETURN TO DORMANCY
/volcano <name> mainvent config geothermal:doFireTicks false
/volcano <name> succession true
# [Cycle repeats]
```

### Multi-Vent Volcanic Complexes

Real volcanic systems often have multiple vents:

#### Rift Zone Implementation (Hawaiian Style)
```bash
# Main summit vent
/typhon create Kilauea
/volcano Kilauea mainvent style crater
/volcano Kilauea mainvent config vent:craterRadius 30  # Large summit crater

# East Rift Zone fissures
/volcano Kilauea create fissure EastRift1
/volcano Kilauea subvent EastRift1 style hawaiian
/volcano Kilauea subvent EastRift1 config vent:fissureAngle auto
/volcano Kilauea subvent EastRift1 config vent:fissureLength 60

/volcano Kilauea create fissure EastRift2
/volcano Kilauea subvent EastRift2 style hawaiian
/volcano Kilauea subvent EastRift2 config vent:fissureLength 80

# Southwest Rift Zone
/volcano Kilauea create fissure SouthwestRift
/volcano Kilauea subvent SouthwestRift style hawaiian
/volcano Kilauea subvent SouthwestRift config vent:fissureLength 50

# Activity pattern: Summit lava lake + episodic rift eruptions
/volcano Kilauea mainvent start                    # Summit active
/volcano Kilauea subvent EastRift1 start          # Rift eruption
# [Lava flows from both sources]
/volcano Kilauea subvent EastRift1 stop           # Rift ends
# [Summit continues]
```

#### Parasitic Cone Implementation (Stratovolcano)
```bash
# Main composite cone
/typhon create Etna
/volcano Etna mainvent style crater
/volcano Etna mainvent style strombolian

# Summit craters
/volcano Etna create crater NortheastCrater
/volcano Etna create crater Voragine
/volcano Etna create crater BoccaNuova

# Flank parasitic cones (monogenetic)
/volcano Etna create crater FlankCone1
/volcano Etna subvent FlankCone1 genesis monogenetic  # Erupts once
/volcano Etna subvent FlankCone1 style strombolian
/volcano Etna subvent FlankCone1 start
# [Builds small cone on flank, then goes extinct]
```

### Realistic Eruption Sequencing

LLMs should sequence eruptions realistically:

#### Example: Building a Mature Stratovolcano Over Time

```bash
# YEAR 1-5: Initial shield base
/typhon create SantoriniBuild
/volcano SantoriniBuild mainvent style fissure
/volcano SantoriniBuild mainvent style hawaiian
/volcano SantoriniBuild mainvent config lavaflow:silicaLevel 0.50
/volcano SantoriniBuild mainvent builder y_threshold 140
/volcano SantoriniBuild mainvent builder enable
/volcano SantoriniBuild mainvent start
# [Let build for Minecraft equivalent of "years"]
/volcano SantoriniBuild mainvent stop

# YEAR 5-10: Transition to cone building
/volcano SantoriniBuild succession true  # Some recovery
# [Dormancy]
/volcano SantoriniBuild mainvent style crater
/volcano SantoriniBuild mainvent style strombolian
/volcano SantoriniBuild mainvent config lavaflow:silicaLevel 0.56
/volcano SantoriniBuild mainvent config level:gasContent 0.5
/volcano SantoriniBuild mainvent builder y_threshold 180
/volcano SantoriniBuild mainvent builder enable
/volcano SantoriniBuild mainvent start
# [Build cone layers]
/volcano SantoriniBuild mainvent stop

# YEAR 10-15: Summit construction
/volcano SantoriniBuild succession true
# [Dormancy and recovery]
/volcano SantoriniBuild mainvent config lavaflow:silicaLevel 0.60
/volcano SantoriniBuild mainvent style vulcanian
/volcano SantoriniBuild mainvent builder y_threshold 210
/volcano SantoriniBuild mainvent start
# [Steep summit]
/volcano SantoriniBuild mainvent stop

# YEAR 15-20: Mature activity cycles
/volcano SantoriniBuild mainvent builder disable
# Cycle through eruptions and dormancy
# Eruption 1
/volcano SantoriniBuild mainvent start
# [Days of activity]
/volcano SantoriniBuild mainvent stop
# [Months of dormancy with succession]
/volcano SantoriniBuild succession true
# Eruption 2
/volcano SantoriniBuild mainvent config level:gasContent 0.7  # More explosive
/volcano SantoriniBuild mainvent start
/volcano SantoriniBuild mainvent stop

# YEAR 20+: Caldera-forming climax
/volcano SantoriniBuild mainvent config lavaflow:silicaLevel 0.70  # Rhyolitic
/volcano SantoriniBuild mainvent config level:gasContent 0.9
/volcano SantoriniBuild mainvent caldera 60 deep 55
/volcano SantoriniBuild mainvent caldera start
# [Catastrophic Plinian eruption, caldera collapse]
# [Caldera lake forms - becomes like real Santorini]
```

### Decision Trees for Autonomous Control

#### When to Progress Volcano Stage?

```
IF summit_height >= target_base_height AND style == "hawaiian" THEN
  → Transition to Strombolian cone building
  → Increase silica content by 0.05-0.10
  → Increase gas content

IF summit_height >= target_cone_height AND style == "strombolian" THEN
  → Transition to Vulcanian summit activity
  → Increase silica content by 0.05
  → Increase gas content
  → Reduce crater radius (steeper)

IF eruption_count >= threshold AND magma_chamber_large THEN
  → Plan caldera formation
  → Increase silica content to rhyolitic
  → Set high gas content

IF flank_stress_high THEN
  → Create parasitic vent
  → OR trigger lateral blast
```

#### When to Cycle Activity?

```
IF eruption_duration >= style_typical_duration THEN
  → Stop eruption
  → Enable succession
  → Enter dormancy

IF dormancy_duration >= repose_interval THEN
  → Increase geothermal activity
  → Enter unrest phase
  → Plan next eruption

IF unrest_duration >= buildup_time THEN
  → Start eruption
  → Execute planned eruption style
```

### Monitoring and Adapting

LLMs should monitor volcano state:

```bash
# Check summit location
/volcano <name> mainvent summit

# Check current configuration
/volcano <name> mainvent config lavaflow:silicaLevel
/volcano <name> mainvent config level:gasContent
/volcano <name> mainvent config erupt:style

# Check genesis mode
/volcano <name> mainvent genesis

# Adapt based on observations
# If lava spreading too far:
/volcano <name> mainvent config lavaflow:silicaLevel 0.65  # Increase viscosity

# If eruption too gentle:
/volcano <name> mainvent config level:gasContent 0.7  # Increase explosivity
```

### Example: Fully Autonomous Volcano Evolution Script

**Scenario**: Build a realistic stratovolcano from birth to maturity

```bash
# ========================================
# AUTONOMOUS STRATOVOLCANO LIFECYCLE
# Example: "Mount Typhon" - Andesitic Composite Cone
# ========================================

# STAGE 1: BIRTH - Submarine Shield (if underwater)
/typhon create MountTyphon
/volcano MountTyphon mainvent style fissure
/volcano MountTyphon mainvent style hawaiian
/volcano MountTyphon mainvent config lavaflow:silicaLevel 0.48  # Basaltic
/volcano MountTyphon mainvent config level:gasContent 0.2
/volcano MountTyphon mainvent config vent:fissureLength 40
/volcano MountTyphon mainvent config succession:enable false
# [IF underwater: Pillow lava phase, Surtseyan phase automatic]
/volcano MountTyphon mainvent start
# [Monitor: When summit breaches sea level, proceed]
/volcano MountTyphon mainvent stop

# STAGE 2: SHIELD BASE CONSTRUCTION
/volcano MountTyphon mainvent style crater
/volcano MountTyphon mainvent config vent:craterRadius 20
/volcano MountTyphon mainvent config lavaflow:silicaLevel 0.50  # Still basaltic
/volcano MountTyphon mainvent builder y_threshold 145           # 1/3 of final height
/volcano MountTyphon mainvent builder enable
/volcano MountTyphon mainvent start
# [AUTO-STOP when y_threshold reached]

# DORMANCY PERIOD 1 (Let succession occur)
/volcano MountTyphon succession true
/volcano MountTyphon mainvent config succession:probability 50
/volcano MountTyphon mainvent config succession:treeProbability 40
# [Wait: Minecraft days/weeks - vegetation grows]

# STAGE 3: TRANSITION - Basaltic-Andesitic Cone
/volcano MountTyphon mainvent style strombolian
/volcano MountTyphon mainvent config lavaflow:silicaLevel 0.55  # Transition magma
/volcano MountTyphon mainvent config level:gasContent 0.4
/volcano MountTyphon mainvent config bombs:radius:min 2
/volcano MountTyphon mainvent config bombs:radius:max 4
/volcano MountTyphon mainvent builder y_threshold 185           # 2/3 height
/volcano MountTyphon mainvent builder enable
/volcano MountTyphon succession false  # Stop succession during active phase
/volcano MountTyphon mainvent start
# [AUTO-STOP when y_threshold reached]

# DORMANCY PERIOD 2
/volcano MountTyphon succession true
# [Wait: Recovery period]

# STAGE 4: SUMMIT - Andesitic Explosive Phase
/volcano MountTyphon mainvent style vulcanian
/volcano MountTyphon mainvent config lavaflow:silicaLevel 0.60  # Andesitic
/volcano MountTyphon mainvent config level:gasContent 0.6
/volcano MountTyphon mainvent config bombs:radius:min 3
/volcano MountTyphon mainvent config bombs:radius:max 5
/volcano MountTyphon mainvent config ash:fullPyroclasticFlowProbability 0.3
/volcano MountTyphon mainvent builder y_threshold 220           # Final height
/volcano MountTyphon mainvent builder enable
/volcano MountTyphon succession false
/volcano MountTyphon mainvent start
# [AUTO-STOP when final height reached]

# STAGE 5: MATURE VOLCANO - Activity Cycles
/volcano MountTyphon mainvent builder disable
/volcano MountTyphon mainvent config vent:craterRadius 15  # Refine summit crater

# CYCLE 1: Explosive eruption
/volcano MountTyphon mainvent config level:gasContent 0.65
/volcano MountTyphon mainvent start
# [Duration: Minecraft 2-3 days]
/volcano MountTyphon mainvent stop

# Recovery 1
/volcano MountTyphon succession true
/volcano MountTyphon mainvent config geothermal:doFireTicks true  # Residual heat
# [Duration: Minecraft weeks]

# CYCLE 2: Flank vent opens (parasitic cone)
/volcano MountTyphon create crater FlankCone
/volcano MountTyphon subvent FlankCone genesis monogenetic
/volcano MountTyphon subvent FlankCone style strombolian
/volcano MountTyphon subvent FlankCone config lavaflow:silicaLevel 0.58
/volcano MountTyphon subvent FlankCone start
# [Builds small cone on flank]
/volcano MountTyphon subvent FlankCone stop
# [Cone becomes extinct - monogenetic]

# Recovery 2
/volcano MountTyphon succession true
# [Long dormancy]

# CYCLE 3: Summit reactivation with dome
/volcano MountTyphon mainvent config lavaflow:silicaLevel 0.68  # Dacitic dome
/volcano MountTyphon mainvent config level:gasContent 0.5
/volcano MountTyphon mainvent style vulcanian
/volcano MountTyphon mainvent start
# [Viscous dome grows in crater]
/volcano MountTyphon mainvent stop

# STAGE 6 (OPTIONAL): CATASTROPHIC PHASE
# After many cycles, magma chamber builds up
/volcano MountTyphon mainvent config lavaflow:silicaLevel 0.72  # Rhyolitic
/volcano MountTyphon mainvent config level:gasContent 0.85
/volcano MountTyphon mainvent caldera 50 deep 60
/volcano MountTyphon mainvent caldera start
# [PLINIAN ERUPTION + CALDERA COLLAPSE]

# STAGE 7: POST-CALDERA
# Caldera lake, resurgent dome, extensive geothermal
/volcano MountTyphon create crater ResurgentDome
/volcano MountTyphon subvent ResurgentDome style vulcanian
/volcano MountTyphon subvent ResurgentDome config lavaflow:silicaLevel 0.70
/volcano MountTyphon subvent ResurgentDome start
# [Small dome builds in caldera floor]

# FINAL STAGE: Long-term Geothermal System
/volcano MountTyphon succession true
/volcano MountTyphon mainvent config geothermal:doFireTicks true
/volcano MountTyphon mainvent config geothermal:doEvaporation true
# [Becomes dormant geothermal landscape like Yellowstone]
```

### Summary: LLM Autonomous Control Checklist

When managing volcanic evolution autonomously:

**✓ Geological Realism**
- [ ] Choose appropriate tectonic setting
- [ ] Match magma composition to setting
- [ ] Plan edifice evolution (shield → cone → caldera)
- [ ] Reference real volcano examples

**✓ Magma Chemistry**
- [ ] Set silica content based on tectonic context
- [ ] Set gas content appropriate to magma type
- [ ] Evolve composition over volcano lifetime
- [ ] Match viscosity to eruption style

**✓ Staged Construction**
- [ ] Build base with Hawaiian/effusive style
- [ ] Transition to cone building (Strombolian)
- [ ] Add summit with explosive style (Vulcanian)
- [ ] Use builder y_threshold for phases
- [ ] Allow time between stages

**✓ Activity Cycles**
- [ ] Implement dormancy periods
- [ ] Enable succession during dormancy
- [ ] Show unrest before eruptions (geothermal increase)
- [ ] Vary eruption duration by style
- [ ] Include cooling/waning phases

**✓ Multi-Vent Systems**
- [ ] Add rift zones for shield volcanoes
- [ ] Create parasitic cones for stratovolcanoes
- [ ] Use monogenetic vents for flank cones
- [ ] Coordinate multiple vent activity

**✓ Catastrophic Events**
- [ ] Plan caldera formation for mature systems
- [ ] Use lateral blast for dome collapse scenarios
- [ ] Trigger Plinian eruptions appropriately
- [ ] Manage post-catastrophe landscape

**✓ Monitoring and Adaptation**
- [ ] Check summit height regularly
- [ ] Monitor eruption progress
- [ ] Adjust parameters based on behavior
- [ ] Adapt to unexpected outcomes

**✓ Timescales**
- [ ] Allow sufficient build time for each stage
- [ ] Implement realistic dormancy periods
- [ ] Don't rush volcanic evolution
- [ ] Think in Minecraft "geological time"

## Technical Implementation Details

### Entity Types Used
- **FallingBlock**: Volcanic bombs, realistic trajectories
- **BlockDisplay**: Ash plumes, pyroclastic flows, visual effects
- **Particles**: Steam, heat distortion, gas emissions

### Block Types and Geology

**Lava cooling produces** (based on silica content):
- Basalt (low silica)
- Andesite (medium silica)
- Granite, Obsidian, Quartz (high silica)
- Tuff (pyroclastic deposits)
- Magma blocks (pillow lava underwater)

**Ore formation**: Volcanic lava can generate ore blocks when cooling

### Multithreading Architecture
- `TyphonScheduler.java`: Task scheduling
- `TyphonMultithreading.java`: Thread distribution
- `TyphonQueuedHashMap.java`: Efficient task management

## Safety and Best Practices

### Safe Observation Distances

| Eruption Style | Minimum Safe Distance |
|----------------|----------------------|
| Hawaiian | 50+ blocks |
| Strombolian | 100+ blocks |
| Vulcanian | 200+ blocks |
| Plinian | 500+ blocks |

### Player Safety Tips
1. Set respawn point far from volcano
2. Keep fire resistance potions
3. Build with non-flammable materials
4. Watch for volcanic gas (nausea effect)
5. Never approach during Major Activity or higher
6. Pyroclastic flows are INSTANTLY LETHAL

### Building Considerations
- Build at safe distances
- Use stone/non-flammable materials
- Expect terrain changes
- Plan for ash deposition
- Consider geothermal heat zones

## Documentation Cross-References

### Full Documentation Structure
```
/DOCS.md                                    # Main documentation
/.github/docs/installation.md               # Installation guide
/.github/docs/volcano/index.md              # Volcano features overview
/.github/docs/volcano/eruption.md           # Eruption mechanics
/.github/docs/volcano/lava.md               # Lava dynamics
/.github/docs/volcano/vents.md              # Vent systems
/.github/docs/volcano/bombs.md              # Volcanic bombs
/.github/docs/volcano/ash.md                # Ash and pyroclastic flows
/.github/docs/volcano/caldera.md            # Caldera formation
/.github/docs/volcano/lateral_blast.md      # Lateral blasts
/.github/docs/volcano/succession.md         # Ecological succession
/.github/docs/volcano/geothermal.md         # Geothermal activity
/.github/docs/volcano/builder.md            # Volcano building
/.github/docs/volcano/config_nodes.md       # Configuration reference
/.github/docs/volcano/status.md             # Volcano status
/.github/docs/experience/index.md           # Experience guides
/.github/docs/experience/01-creating-your-first-volcano.md
/.github/docs/experience/02-before-eruption.md
/.github/docs/experience/03-during-eruption.md
/.github/docs/experience/04-post-eruption.md
/.github/docs/volcano/tips/volcano-quickstart.md
/.github/docs/volcano/tips/multiple-vents.md
/.github/docs/volcano/tips/build_stratovolcano.md
```

## Version Information

**Current Version**: v0.9.0+
**Last Documentation Update**: 2025-10-18

### Breaking Changes from Diwaly's Plugin
See [Changes from Diwaly's Volcano Plugin](/.github/docs/changes_from_diwaly.md)

### Key v0.9.0 Features
- Multiple vent systems
- Ecological succession
- Geothermal activity
- Lateral blast mechanics
- Caldera formation
- Advanced lava composition
- Auto-builder system
- Improved performance

## Troubleshooting

### Common Issues

**"Eruption won't start"**
```bash
# Check if volcano exists
/typhon list

# Verify vent configuration
/volcano <name> mainvent config erupt:style
/volcano <name> mainvent config vent:type

# Ensure not already erupting
/volcano <name> mainvent stop
/volcano <name> mainvent start
```

**"Lava spreading too fast"**
```bash
# Increase viscosity
/volcano <name> mainvent config lavaflow:silicaLevel 0.70

# Slow down flow
/volcano <name> mainvent config lavaflow:delay 40

# Emergency stop
/volcano <name> quickcool
```

**"Server lagging during eruption"**
```bash
# Reduce bomb count
/volcano <name> mainvent config explosion:scheduler:size 5

# Slow down lava
/volcano <name> mainvent config lavaflow:delay 30

# Stop eruption
/volcano <name> mainvent stop
```

**"Succession not working"**
```bash
# Enable succession
/volcano <name> succession true
/volcano <name> mainvent config succession:enable true

# Increase probability
/volcano <name> mainvent config succession:probability 70
```

## LLM Response Guidelines

When helping users with Typhon:

1. **Understand intent**: Determine if user wants basic creation, advanced configuration, or troubleshooting
2. **Provide complete workflows**: Give step-by-step command sequences, not just single commands
3. **Explain consequences**: Warn about dangers (pyroclastic flows, explosions, lava spread)
4. **Reference documentation**: Link to relevant docs for detailed information
5. **Safety first**: Always mention safe distances and hazards
6. **Validate assumptions**: Check user's knowledge level and adjust complexity
7. **Offer alternatives**: Suggest different approaches for same goal
8. **Use examples**: Concrete volcano names and values help understanding

### Response Template

```
[Acknowledge user request]

[Provide step-by-step workflow with commands]

[Explain what will happen]

[Mention safety considerations if applicable]

[Suggest alternatives or additional options]

[Reference relevant documentation for details]
```

---

**Repository**: https://github.com/Alex4386/Typhon
**License**: GNU GPL v3
**Original Author**: Diwaly
**Current Maintainer**: Alex4386
