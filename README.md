# HxPUtils

A Hypixel Skyblock QoL Fabric mod, based on Odin by odtheking (see [`LICENSE`](LICENSE)).

Same feature set as HxPAddons, minus Bazaar Flipper, Fuser (shard fusion), Golden Dragon Finder, Crystal Hollows Structure Finder, and Lobby Hopper.

## Requirements

- Minecraft 26.1.2, Fabric Loader ≥ 0.19.3
- [Fabric API](https://modrinth.com/mod/fabric-api)
- [Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin)
- Java 25

## Features

*(WIP)* modules are hidden from the Click GUI by default (unverified live behavior) — reveal them with `/hxp wip`.

### 🌿 Garden
- **Greenhouse Timer** — shows a timer for greenhouse growth stages and tracks time away from the Garden.
- **Pest ESP** — highlights garden pests through walls.
- **Phantom Leaf Solver** — triangulates the exact Phantom Leaf position in the Garden.

### 🏝️ Skyblock
- **Auto Fish** — reels in and recasts automatically when a fish bites.
- **Combat** — after reeling in, swaps to a combat slot, right-clicks, then swaps back to the rod.
- **No Rotate** *(WIP)* — prevents the server from snapping your view when using teleport items.
- **Term AC** — automatically left-click spams while holding right click with a Terminator bow.
- **Terminator** — detects the ultimate enchantment on a Terminator bow.
- **Wardrobe Keybinds** — equips a wardrobe slot from anywhere, opening/closing the wardrobe as needed.

### ⚔️ Dungeon
- **Dungeon Map** — a live map of the dungeon: rooms, doors, teammates, and secrets progress.
- **Auto Close Chest** — instantly closes dungeon secret chests instead of opening their inventory screen.
- **Puzzle Triggerbot** — solves supported puzzles and clicks the correct answer as soon as you hover it.
- **Secret Triggerbot** — automatically interacts with secrets (chests, levers, wither essence) as soon as you look at them.
- **Star Mob ESP** — highlights starred dungeon mobs through walls.
- **Terminal Solver** *(WIP)* — renders the solution for floor 7 terminals.
- **Terminal Triggerbot** *(WIP)* — middle-clicks a terminal slot as soon as it's known to be correct.

### 🎨 Render
- **Custom ESP** — highlights entities (and optionally particles) matching a name/type list through walls.
- **Borderless Fullscreen** — replaces Minecraft's exclusive fullscreen with a borderless window (F11).

### 💬 General
- **Chat Filter** — hides chat messages matching user-defined patterns (regex supported).
- **Compact Chat** — compacts repeated chat messages into a single counted line.
- **Copy Chat** — copy chat messages by clicking on them.
- **Auto Dialogue** — automatically continues dialogues with NPCs.
- **Mod ID Hider** — hides your mod list and client brand from servers (on by default).
- **Smooth World Loading** — skips the terrain loading screen when joining servers or switching dimensions.

### 🖱️ Commands
- `/hxp` — opens the Click GUI.
- `/hxp help` — lists every command.
- `/hxp recipe <item>` — looks up an item's crafting recipe.
- `/hxp craftflip` — scans the Bazaar for profitable craft flips.
- `/hxp fish [stop]` — toggles Auto Fish.
- `/hxp garden reset` — resets the Phantom Leaf Solver.
- `/hxp wip` — toggles WIP modules in the Click GUI.

---

*This list is kept in sync with the mod's actual features — if it's in the Click GUI, it's here.*
