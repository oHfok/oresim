# OreSim by krwawiec

Maintained by krwawiec ([oHfok](https://github.com/oHfok)). A client-side Fabric mod for **Minecraft 26.1.2** that predicts where ores
generated from the world seed, shows them as boxes through walls, and can
drive [Baritone](https://github.com/cabaletta/baritone) to mine them for you.
It works behind server anti-xray, because the positions come from the seed,
not from what the server sends.

It started as a fork of "OreSim by gumark"
([gumark/fabric-oresim](https://github.com/gumark/fabric-oresim)), itself a
standalone port of the OreSim module of
[meteor-rejects](https://github.com/AntiCope/meteor-rejects). What it adds is
listed under [Changes in this fork](#changes-in-this-fork).

> Using x-ray or automated mining on a server you don't run may break its
> rules and get you banned. Check first.

## Features

- **Seed-based ore overlay**: coal, iron, gold, redstone, diamond, lapis,
  copper, emerald, nether quartz, nether gold and ancient debris, drawn through
  walls within 5 chunks. No x-ray resource pack, no Meteor Client needed.
- **Measured accuracy**: about 99% of predicted diamonds are real behind
  anti-xray, and 100% in the nether ([details](#prediction-accuracy)).
- **Auto-mining with Baritone** (optional): mines the ores you choose, follows
  veins, mines visible ores the prediction missed, picks up the drops, and
  tunnels onward when nothing is left in range.
- **Remembers what's done**: mined ores and predictions proven wrong disappear
  from the overlay and are saved per server, seed and dimension.

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) 0.19+ for Minecraft 26.1.2.
2. Put these in your `mods` folder:
   - the OreSim jar from [Releases](../../releases)
   - [Fabric API](https://modrinth.com/mod/fabric-api) for 26.1.2
   - *(optional, for auto-mining)* `baritone-api-fabric-1.18.0.jar` from
     [Baritone v1.18.0](https://github.com/cabaletta/baritone/releases/tag/v1.18.0)
3. Java 25 or newer.

Works alongside Meteor Client.

## Usage

### Overlay

| Action | How |
| --- | --- |
| Toggle the overlay | **X** key (rebindable in Controls) |
| Open the menu | **O** key or `/oresim` |
| Set the world seed (multiplayer) | `/setoresimseed <seed>` (remembered per server) |
| Show mined/disproven ores again | `/oresim clearmined` |

In singleplayer the seed is read from the world. Seeds follow vanilla rules:
numeric text is a literal seed, other text is hashed like the world-creation
screen does.

The menu chooses which ores are shown (with *Show all* / *Hide all*) and how
they're drawn; everything is saved in `config/oresim-settings.json`:

| Setting | Options |
| --- | --- |
| Style | **Veins**: each vein as one shape, outer edges and faces only (default). **Boxes**: a see-through box per block. **Outlines**: the edges of every block. |
| Fill | Off, Low, Medium, High (see-through) |
| Lines | Thin, Normal, Thick |
| Range | 16, 32 or 48 blocks around you, or all (about 80); the miner still uses everything |

### Auto-mining (needs Baritone)

| Action | How |
| --- | --- |
| Mine specific ores | `/oresim mine diamond debris` (tab-completes; spaces or commas) |
| Mine whatever the overlay shows | `/oresim mine` |
| Stop | `/oresim stop` or Baritone's `#stop` |

Ore names: `coal`, `iron`, `gold`, `redstone`, `diamond`, `lapis`, `copper`,
`emerald`, `quartz`, `debris` (also `ancient_debris`, `netherite`).

Tip: go to the right depth first (around Y -58 for diamonds), face the way you
want to go, then start. What the miner does:

- Gives Baritone the nearest predicted ores as goals, so it digs to ores
  anti-xray hides (Baritone's own `#mine` only finds ores the client can see).
- Mines ores you can actually see (exposed to air, within 10 blocks) as well,
  and prefers them. Digging into a vein exposes the rest of it, so veins get
  finished even where the prediction only caught part of one.
- Breaks an ore on the spot when it's within reach and in line of sight, like
  `#mine`, but only with a hotbar tool that gets the drop (iron pickaxe or
  better for diamonds), never the block underfoot or an ore touching lava.
- Picks up the drops of the ores it mines (within 12 blocks) before moving on.
- When nothing is left in range, tunnels onward in the direction you faced at
  the Y level you started at; turns 90° if blocked, stops after four tries.

### Mined and wrong predictions

A predicted ore that turns into air (mined by you, the miner or anyone else) is
marked as mined. A predicted ore that gets exposed to air but still isn't that
ore 2 seconds later is marked as wrong: anti-xray never disguises exposed
blocks, so the client sees the truth there. Both stop being shown or mined,
and are saved in `config/oresim-mined.json`.

## Prediction accuracy

The simulation replays vanilla ore generation against the blocks the client
sees. Measured with the [accuracy tester](#accuracy-tester) on three seeds, 289
chunks each, client behind anti-xray:

| | Original port | This fork |
|---|---|---|
| Diamonds: predicted that are real / real that are predicted | 98.1–98.5% / 98.4–99.3% | 98.7–99.7% / 98.7–99.9% |
| All ores: predicted that are real / real that are predicted | 79–83% / 74–79% | 96.5–99.5% / 89.6–99.4% |
| Nether (gold, quartz, debris) | 99.7–99.9% / 100% | 100% / 100% |

What it corrects for, compared with the original port:

- **Surface height** (the big one): vanilla only places a vein where the
  terrain reaches it, measured without water and before trees exist. The
  original used a heightmap that counts water and leaves, which derailed coal,
  copper, lapis and iron near the surface (coal ~60% → 90–98%, copper
  50–72% → 97–100%).
- **Overlapping veins**: an ore placed earlier can't be replaced, in vanilla's
  feature order, even where anti-xray shows it as stone.
- **Neighbouring chunks**: a chunk is only simulated once the chunks its veins
  reach into are loaded.
- **Mined ores**: still count as the ore they were, so re-simulating a mined
  area doesn't derail the rest of the vein.
- **Biome lookup**: the same block-position lookup vanilla uses.

Known limits: stone dug away by *other* players can't be told apart from a
natural cave, so a vein crossing someone else's tunnel may be predicted wrong
(dense strip mines cost about 1–2% of diamonds). Iron and copper also
generate in large noise-based veins that aren't simulated, which lowers their
recall on some seeds. Chunks generated by older Minecraft versions, or with
world-generation datapacks, won't match.

## Building

Requires JDK 25.

```bash
./gradlew build -Pmc=26.1.2 -Pfapi=0.155.3+26.1.2
```

The jar is written to `build/libs/oresim-by-krwawiec-<version>-mc26.1.2.jar`.

It also builds for 26.2 and 26.3 (`-Pmc=26.2 -Pfapi=0.161.0+26.2`,
`-Pmc=26.3 -Pfapi=0.161.0+26.3`), but only 26.1.2 has been tested in game.
Auto-mining is compiled against Baritone 1.18.0 (26.1); with other Minecraft
versions use the matching Baritone release, untested.

### Accuracy tester

`src/tester` is a separate development-only mod (never in the release jar). It
starts a real dedicated server, generates a world, runs the mod's own
simulation against what a client would see, and compares with the real ores:

```bash
./gradlew runAccuracyTest -Pmc=26.1.2 -Pfapi=0.155.3+26.1.2 -Pseed=12345
# optional: -Pradius=8 (chunks around 0,0)  -Pdimensions=overworld,nether
```

The report goes to `run-tester/oresim-accuracy-<seed>.md`: precision and recall
for the original simulation, the current one, the current one with each
correction switched off, with and without anti-xray, and with strip mines. A
run accepts the Minecraft EULA in `run-tester/eula.txt` for that local test
server and keeps one world per seed there (about 20 MB each).

## Changes in this fork

Compared with gumark/fabric-oresim 1.1.0 (full list in [CHANGELOG.md](CHANGELOG.md)):

- Fixed two crashes on Minecraft 26.1.2 (at startup, and when turning the
  overlay on).
- Prediction accuracy fixes listed above, and the accuracy tester.
- Auto-mining with Baritone.
- Mined and disproven ores are remembered and hidden.

## Credits

- [gumark/fabric-oresim](https://github.com/gumark/fabric-oresim) ("OreSim by
  gumark"): the standalone Fabric port this mod builds on.
- [meteor-rejects](https://github.com/AntiCope/meteor-rejects): the original
  OreSim module and its port of vanilla's ore generation.
- [Baritone](https://github.com/cabaletta/baritone) (LGPL-3.0): pathing and
  mining. Not bundled; see [libs/README.md](libs/README.md).

## License

GPL-3.0-only, see [LICENSE](LICENSE). A derivative work of gumark/fabric-oresim
and meteor-rejects, both GPL-3.0.
