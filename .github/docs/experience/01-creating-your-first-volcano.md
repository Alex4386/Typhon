[< Return to Typhon Docs](/DOCS.md)

# Creating Your First Volcano

Welcome to Typhon! This guide will help you create your first volcano and start your first eruption.

## What is Typhon?

Typhon is a volcano simulation plugin that brings realistic volcanic activity to Minecraft. You'll experience:
- **Geothermal phenomena** - heat, steam, and volcanic gases
- **Explosive eruptions** - volcanic bombs, ash plumes, and pyroclastic flows
- **Dynamic landscapes** - volcanoes that build, collapse, and evolve over time
- **Real threats** - heat damage, toxic gases, and destructive lava flows

Let's get started!

## Step 1: Create a Volcano

Stand where you want your volcano's main vent to be located and run:

```
/typhon create <volcano_name>
```

For example:
```
/typhon create MyFirstVolcano
```

![Creating a volcano](placeholder-create-volcano.png)

This creates a volcano with a main vent at your current location.

## Step 2: Choose Your Eruption Style

Typhon supports three main eruption styles. Each has different behavior and threats:

### Hawaiian - Gentle Lava Flows
```
/volcano MyFirstVolcano mainvent style hawaiian
```

**What to expect:**
- Fluid lava flows that travel far
- Gentle lava fountains
- Builds broad, gently sloping shield volcanoes
- **Threat level:** Low - mainly lava flow hazard

![Hawaiian eruption](placeholder-hawaiian.png)

### Strombolian - Moderate Explosions
```
/volcano MyFirstVolcano mainvent style strombolian
```

**What to expect:**
- Regular explosive pulses
- Volcanic bombs ejected into the air
- Moderate lava flows
- Builds steeper cinder cones
- **Threat level:** Medium - bombs and lava

![Strombolian eruption](placeholder-strombolian.png)

### Vulcanian - Powerful Explosions
```
/volcano MyFirstVolcano mainvent style vulcanian
```

**What to expect:**
- Powerful explosive eruptions
- Large volcanic bombs
- Significant ash clouds
- Pyroclastic flows
- Builds steep volcanic cones
- **Threat level:** High - bombs, ash, pyroclastic flows

![Vulcanian eruption](placeholder-vulcanian.png)

## Step 3: Start Your First Eruption

Ready to see your volcano in action?

```
/volcano MyFirstVolcano mainvent start
```

![Eruption starting](placeholder-eruption-start.png)

Your volcano will begin erupting! Watch from a safe distance.

## Step 4: Stop the Eruption

When you're ready to stop:

```
/volcano MyFirstVolcano mainvent stop
```

## Quick Reference Commands

```
# Create volcano
/typhon create <volcano_name>

# Set eruption style
/volcano <volcano_name> mainvent style <hawaiian|strombolian|vulcanian>

# Start eruption
/volcano <volcano_name> mainvent start

# Stop eruption
/volcano <volcano_name> mainvent stop

# Check volcano status
/volcano <volcano_name> status
```

## What's Next?

Now that you've created your first volcano, learn about:
- **[Before Eruption](02-before-eruption.md)** - Geothermal activity and warning signs
- **[During Eruption](03-during-eruption.md)** - The threats and hazards you'll face
- **[After Eruption](04-post-eruption.md)** - How landscapes recover

## Safety Tips

⚠️ **Keep your distance!** Volcanoes are dangerous:
- Volcanic bombs can destroy structures
- Pyroclastic flows are instantly lethal
- Lava flows will burn everything in their path
- Volcanic gases can poison you
- Heat zones can damage you over time

Start with Hawaiian style to learn the basics safely, then progress to more dangerous eruption styles!
