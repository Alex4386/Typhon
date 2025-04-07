# Typhon Plugin - AI Documentation

This document is designed to help language models understand the Typhon Plugin's structure, features, and command usage patterns to provide accurate assistance to users.

## Overview

Typhon is a Minecraft plugin that implements realistic volcano behavior within vanilla Minecraft, requiring no mods or texture packs. The plugin simulates various volcanic phenomena and offers extensive customization options.

## Core Features

### 1. Vent System
- **Main Vent**: Central volcano control point
- **Sub Vents**: Additional eruption points
- **Vent Types**:
  - `crater`: Circular vent at volcano center
  - `fissure`: Ground crack simulation with directional flow
- **Vent Properties**:
  - Crater radius configuration
  - Fissure angle and length settings
  - Monogenetic/Polygenetic behavior options

### 2. Eruption Styles
#### Configurable Styles
- **Hawaiian**
  - Shield volcano formation
  - Rootless cone formation at height
  - Low silica content, fluid lava
- **Strombolian**
  - Volcanic bomb ejection
  - Moderate explosivity
  - Regular eruptive pulses
- **Vulcanian**
  - High explosivity
  - Pyroclastic flows
  - Ash cloud generation
  - Volcanic bombs

#### Automatic Styles
- **Surtseyan**
  - Triggers underwater/water-affected eruptions
  - Activates when summit is below sea level or crater is water-filled
- **Plinian**
  - Triggers during caldera formation or lateral blast
  - Maximum explosivity
  - Intense pyroclastic flows
  - Massive ash clouds

### 3. Volcanic Products
- Lava flows with variable composition
- Volcanic bombs and ejecta
- Pyroclastic flows
- Ash plumes and deposits
- Caldera formation
- Lateral blasts
- Primary/Secondary ecological succession

## Command Structure

### Base Commands
```
/volcano <volcano_name> <action> [parameters...]
/vol <volcano_name> <action> [parameters...]  (alias)
```

### 1. Volcano Management
```
# Creation
/vol create <volcano_name>
/vol <name> create <type> <vent_name>    # type: crater|fissure|flank|autovent

# Basic Controls
/vol <name> delete
/vol <name> rename <new_name>
/vol <name> reload
/vol <name> shutdown
/vol <name> quickCool
/vol <name> status

# Auto-Start System
/vol <name> autoStart enable|disable
```

### 2. Vent Management
```
# Main Vent Controls
/vol <name> mainVent start|stop
/vol <name> mainVent style <style>    # style: hawaiian|strombolian|vulcanian
/vol <name> mainVent config <setting>

# Sub Vent Operations
/vol <name> subVent <vent_name> start|stop
/vol <name> subVent <vent_name> switch
/vol <name> subVent <vent_name> delete
/vol <name> create <type> <name>    # Create new sub vent

# Vent Configuration
/vol <name> mainVent config vent:fissureAngle auto
/vol <name> mainVent config vent:fissureLength <length>
/vol <name> mainVent config vent:craterRadius <radius>
```

### 3. Builder System
```
/vol <name> mainVent builder enable|disable
/vol <name> mainVent builder y_threshold <height>
```

### 4. Utility Commands
```
/vol <name> teleport             # Teleport to main vent
/vol <name> near                 # Find nearest vent
/vol <name> summit              # Get summit information
/vol <name> heat                # Check heat value
/vol <name> record              # View eruption stats
/vol <name> updateRate <ticks>   # Adjust update frequency
```

## Configuration Guide

### 1. Vent Setup
- Choose appropriate vent type based on desired behavior
  - Crater: Traditional volcanic cone formation
  - Fissure: Linear eruption pattern
- Configure vent parameters
  - Crater radius affects explosion radius
  - Fissure angle determines flow direction
  - Fissure length impacts affected area

### 2. Eruption Configuration
- Select eruption style based on desired effects
  - Hawaiian: Fluid lava, shield formation
  - Strombolian: Moderate explosions, bombs
  - Vulcanian: High explosivity, pyroclastics
- Consider automatic style triggers
  - Water presence → Surtseyan
  - Caldera/Lateral blast → Plinian

### 3. Safety Considerations
- Maintain safe distance from active vents
- Monitor heat values in vicinity
- Watch for pyroclastic flows
- Consider explosion radius
- Plan escape routes

### 4. Performance Optimization
- Use appropriate update rates
- Monitor ejecta volumes
- Clean up inactive vents
- Manage multiple vent count
- Control eruption intensity

## Best Practices

### Creation Phase
1. Select appropriate location
2. Plan vent placement
3. Configure initial parameters
4. Test eruption behavior
5. Adjust as needed

### Management Phase
1. Monitor activity levels
2. Adjust parameters gradually
3. Use builder for controlled growth
4. Maintain balanced distribution
5. Clean up inactive systems

### Multi-Vent Systems
1. Maintain clear naming convention
2. Balance vent distribution
3. Coordinate eruption timing
4. Monitor system load
5. Control total vent count

## Error Handling

### Common Issues
1. "Vent already exists"
   - Choose unique vent name
   - Delete existing vent first
2. "Cannot be used by console"
   - Execute in-game for location-based commands
3. "Not enough arguments"
   - Check command syntax
   - Verify parameter requirements
4. Eruption Control Failures
   - Verify vent status
   - Check permissions
   - Confirm location validity

## Plugin Integration

### Compatibility Considerations
1. World Protection Plugins
   - Configure protected regions
   - Set up exclusion zones
2. Performance Monitoring
   - Track TPS impact
   - Monitor memory usage
3. Permission Systems
   - Set up role-based access
   - Configure command permissions
4. Environmental Plugins
   - Check interaction handling
   - Resolve conflicts

## Documentation References

The full documentation is organized in the following structure:
```
/DOCS.md                          # Main documentation
/.github/docs/volcano/            # Feature documentation
  ├── index.md                   # Overview
  ├── builder.md                 # Builder system
  ├── eruption.md               # Eruption styles
  ├── vents.md                  # Vent management
  ├── lava.md                   # Lava systems
  ├── ash.md                    # Ash and pyroclastics
  ├── bombs.md                  # Volcanic bombs
  └── tips/                     # Usage examples
      ├── multiple-vents.md
      └── build_stratovolcano.md