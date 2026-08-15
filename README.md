# HxPUtils

A Hypixel Skyblock QoL Fabric mod, based on Odin by odtheking (see [`LICENSE`](LICENSE)).

## Requirements

- Minecraft 26.1.2, Fabric Loader ≥ 0.19.3
- [Fabric API](https://modrinth.com/mod/fabric-api)
- [Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin)
- Java 25

## Features

### 🌿 Garden
- **Greenhouse Timer** — shows a timer for greenhouse growth stages and tracks time away from the Garden.
- **Phantom Leaf Solver** — triangulates the exact Phantom Leaf position in the Garden.
- **Pest ESP** — highlights garden pests through walls.

### 🏝️ Skyblock
- **Auto Fish** — reels in and recasts automatically when a fish bites.
- **Auto Loadout** — equips a loadout by number via `/hxp loadout <n>`.
- **Term AC** — automatically left-click spams while holding right click with a Terminator bow.
- **Terminator** — detects the ultimate enchantment on a Terminator bow.

### ⚔️ Dungeon
- **Auto Close Chest** — instantly closes dungeon secret chests instead of opening their inventory screen.
- **Star Mob ESP** — highlights starred dungeon mobs through walls.

### 🎨 Render
- **Custom ESP** — highlights entities (and optionally particles) matching a name/type list through walls.
- **Zoom** — hold a keybind to reduce FOV, scroll to adjust; optional Smooth Mode (eased zoom transition + smoothed camera turning) and zoom-depth-scaled mouse sensitivity.
- **Borderless Fullscreen** — replaces Minecraft's exclusive fullscreen with a borderless window (F11).

### 💬 General
- **Chat Filter** — hides chat messages matching user-defined patterns (regex supported).
- **Compact Chat** — compacts repeated chat messages into a single counted line.
- **Copy Chat** — copy chat messages by clicking on them.
- **Auto Dialogue** — automatically continues dialogues with NPCs.
- **Mod ID Hider** — hides your mod list and client brand from servers (on by default).
- **Name Changer** — replaces your own name with another (in a configurable color) wherever it shows up in chat, the tab list, and your floating name tag. Purely client-side - only you see the replacement.
- **Smooth World Loading** — skips the terrain loading screen when joining servers or switching dimensions.

### 🖱️ Commands
- `/hxp` — opens the Click GUI.
- `/hxp help` — lists every command.
- `/hxp recipe <item>` — looks up an item's crafting recipe.
- `/hxp craftflip` — scans the Bazaar for profitable craft flips.
- `/hxp fish [stop]` — toggles Auto Fish.
- `/hxp loadout <n>` — equips loadout n via /loadout.
- `/hxp garden reset` — resets the Phantom Leaf Solver.
- `/hxp wip` — toggles WIP modules in the Click GUI.
