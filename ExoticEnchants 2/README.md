# ExoticEnchants (Paper 1.21.11)

Globally unique exotic enchantments: normally impossible enchantment combinations that exist **once on the
whole server**, are won through hourly rolls, can be **taken by other players** on a Great Reroll, bought in a
rotating shop, and removed for money. Java + Bedrock (Geyser) friendly: there is no anvil/sign/book input,
only chat commands and a click-only chest shop.

## Requirements
- Paper **1.21.11**, Java **21**
- Internet access on first start (Paper downloads the SQLite driver listed in `plugin.yml`)

### Dependencies for the shop and enchantment removal (money features)
Two plugins are needed, and **Vault alone is not enough**:
1. **Vault** - a bridge between plugins. It holds no money and has no balance commands.
2. **An economy plugin that registers with Vault**, for example **EssentialsX** (install `EssentialsX` and enable
   its economy, which is on by default) or CMI. This is the part that actually stores player balances.

Without them everything else works (rolls, steals, books, registry, effects); only `/exotic shop` and
`/exotic remove` are disabled and tell the player exactly what is missing. The plugin connects automatically as soon as
an economy registers, even if it loads after ExoticEnchants. `/exotic admin economy` shows the live status.

## Install
1. Put `ExoticEnchants-1.0.0.jar` in the server's `plugins` folder.
2. Start the server. Check the console for `ExoticEnchants enabled`.
3. Edit `plugins/ExoticEnchants/config.yml` (and `rarity.yml`), then `/exotic admin reload`.

## How to build the jar

### Option A - GitHub (no installs)
1. Create a free GitHub repository and upload this whole folder (including the hidden `.github` folder).
2. Open the **Actions** tab, run **Build ExoticEnchants** (it also runs on every push).
3. When it finishes, open the run and download the **ExoticEnchants-jar** artifact. It also runs the tests.
4. If the build fails, open the failed step and copy the error text (see "If it does not compile").

### Option B - your computer
1. Install **JDK 21** (e.g. Temurin) and **Gradle 8.x** (or open the folder in IntelliJ IDEA, which sets this up).
2. In the project folder run:

       gradle build

3. The jar is at `build/libs/ExoticEnchants-1.0.0.jar`.
4. Run only the logic tests with `gradle coreTest`.

### If it does not compile
Version 1.0.0 compiled on the real Paper API (GitHub Actions). Version 1.0.1 adds new code that has so far only been
compiled against hand-written stand-ins of the Paper API, so the first real build may show a small API mismatch. The
new API calls are: `ServiceRegisterEvent` (EconomyListener), `EntityBlockFormEvent`, `Levelled`, `Material.FROSTED_ICE`
(ExoticEffectsListener), `EntityShootBowEvent#getBow`, `Entity#getFallDistance`. Paste the compiler error to get a fix.
The logic tests (`gradle coreTest logicTest`, 27 + 34 checks) run as part of `gradle build`.

## Commands
| Command | What it does |
|---|---|
| `/exotic` | Your exotics, time to next roll and shop rotation |
| `/exotic apply` | Exotic Book in **off-hand**, item in **main hand** |
| `/exotic shop` | Open the book shop (click a book to buy) |
| `/exotic remove` then `/exotic remove confirm` | Remove the exotic on the held item for money (normal enchants stay) |
| `/exotic top` | Leaderboard from the registry |
| `/exotic inspect <player>` | A player's registered exotics |
| `/exotic rolls` | Time until the next hourly roll |
| `/exotic admin inspect <player>` | Same, with registration ids and status |
| `/exotic admin unregister <id>` | Release a registration |
| `/exotic admin transfer <id> <player>` | Change the registered owner |
| `/exotic admin givebook <player> <enchant> <level>` | Give a book |
| `/exotic admin rollnow` | Run a roll round now |
| `/exotic admin reload` | Reload config and rarity.yml |
| `/exotic admin audit` | Disputed/pending counts, anomalies, invariant check |
| `/exotic admin economy` | Shows whether Vault and an economy provider are connected, and what is missing |
| `/exotic admin effects` | Lists the custom effects the plugin implements |

Permissions: `exoticenchants.use` (default true), `exoticenchants.admin` (default op).

## How it works
- **Uniqueness key** = enchantment + level + item type. Diamond Sword Power V and Diamond Axe Power V are different.
  One exotic per item. Normal enchantments are never overwritten.
- **Exotic pool**: built from the server's own enchantment list. `NON_VANILLA_ONLY` (default) means enchants that
  cannot normally be on that item, or that conflict with an enchant already on it. `ANY` makes every enchant exotic.
- **Hourly roll**: persisted timer, warnings at 5 and 1 minute. Participants are online, non-AFK players holding an
  eligible item. One 65% / 35% decision per hour: **Normal** (claim a free exotic) or **Great Reroll**.
- **Great Reroll**: each player has `steal-bias` (default 0.7) chance to take an exotic from another **online**
  player holding the same item type, unless the owner is protected (3 h after acquiring) or the per-round steal cap
  is reached. Otherwise they roll normally. Only one transfer per exotic per round.
- **Source of truth is the database.** An item carries a "claim ticket" in its data; it only counts while the
  registry has an ACTIVE row for it. When an old owner's item is no longer registered (after a steal) the exotic is
  stripped from it automatically the next time it is seen (join, pickup, container open, or the periodic scan).
  The thief's registration never waits for the old item to be reachable.
- **Crash safety**: claims and transfers are written as PENDING, applied to the item, then confirmed. After a crash,
  an interrupted claim is cancelled and an interrupted transfer goes back to the previous owner. SQLite has
  unique indexes so the database itself refuses a second live registration for a key or item.
- **Duplicates**: if the same item id is found twice, both copies lose the exotic and the registration is marked
  DISPUTED for admins (`/exotic admin audit`).
- **Vanilla paths**: exotic items cannot be anvil sacrifices or go in a grindstone (use `/exotic remove`).
  A netherite upgrade migrates the registration to the new item type, or is cancelled if that exotic already exists.
- **AFK**: no horizontal movement of at least 1 block for `afk.minutes`. Camera turns and jumping in place do not count.
- **Books**: unique, single use (used ids are stored), registered only when applied. Buying never bypasses uniqueness.

## Configuration (config.yml)
| Option | Default | Meaning |
|---|---|---|
| rolls.interval-minutes | 60 | Time between rolls |
| rolls.normal-chance | 0.65 | Normal vs Great Reroll |
| rolls.announce | true | Broadcast rewards |
| great-reroll.steal-bias | 0.7 | Chance a Great Reroll tries to take an owned exotic |
| great-reroll.allow-steal-from-offline | false | Offline owners are never targeted when false |
| great-reroll.protection-hours | 3 | New owners cannot be stolen from for this long |
| great-reroll.max-steals-per-round | 3 | Cap on transfers per round |
| afk.minutes | 10 | Idle time before AFK |
| afk.ignore-passive-movement | true | Vehicles do not count as moving |
| exotics.pool-mode | NON_VANILLA_ONLY | or ANY |
| exotics.eligible-items | swords, tools, armor, bow... | Exact names or `_SUFFIX` |
| exotics.protect-dropped-items | true | Dropped exotics are invulnerable and never despawn |
| exotics.scan-interval-seconds | 3 | Inventory validation interval (restart to change) |
| rarity-weights.* | 20 each | Tier weights |
| shop.* | see file | Enabled, rotation hours, slots, stock, prices |
| removal-cost.* | see file | Price to remove an exotic, by rarity |

`rarity.yml` is generated automatically with a rarity for every enchantment and level (spread evenly, not tied to
level) and extended when new enchantments appear.

## API for other plugins (auction house, trading, crates)
`com.exoticenchants.api.ExoticAPI`: `isExotic(ItemStack)`, `randomBook(Rarity, Material)` (empty = give your
alternative reward), `ownershipChanged(ItemStack, Player newOwner)` (call after a successful handover).
Without calling it, ownership still follows the item when the new holder picks it up or is scanned.

## Exotic effects on unusual items (new in 1.0.1)
Vanilla only activates an enchantment on the item types it was designed for, so before 1.0.1 a Frost Walker sword or a
Density bow carried the enchantment but did nothing. Now every combination is one of:
- **native** - vanilla already makes it work (the item normally accepts the enchantment, or it works in any slot:
  Mending, Unbreaking, Vanishing Curse);
- **custom** - implemented by this plugin (below);
- **no effect** - never rolled, sold or applied (`exotic-effects.require-working-effect: true`, the default), so you
  can never buy an exotic that silently does nothing. Set it to `false` for the old behaviour.

| Enchantment | On | Custom effect |
|---|---|---|
| Frost Walker | any non-boots item | Water around you freezes while the item is held (radius 2 + level, melts after 6 s) |
| Density | bow, crossbow | Arrows shot while falling deal bonus damage: level x 0.25 x blocks fallen, capped at 20 |
| Sharpness | bow, crossbow | Arrows deal +0.5 x level + 0.5 damage |
| Smite / Bane of Arthropods | bow, crossbow | Arrows deal +2.5 x level against undead / arthropods |
| Fire Aspect | bow, crossbow | Arrows set the target on fire (4 s per level) |
| Protection, Fire/Blast/Projectile Protection, Feather Falling | any non-armor item | Damage reduction while held in either hand (vanilla 4% per protection point, capped) |

Every number is configurable under `exotic-effects` in `config.yml`. Effects only work while the item is a **valid
registered** exotic, so a stolen item stops working immediately. On every start the plugin writes
`plugins/ExoticEnchants/effects-audit.txt`: for **every** enchantment on your server, where it is native, where the plugin
implements it, and where it has no effect. It uses your server's own enchantment rules, so it is always accurate.
Exotics that conflict on their proper item (e.g. Sharpness + Smite on a sword, Protection + Blast on a chestplate)
keep vanilla behaviour.

**Not verified in-game:** the formulas and the rules are unit tested, but the event handlers (freezing water, arrow
damage, damage reduction) have not yet been run on a real server. See the testing checklist.

## Changelog
### 1.0.1
- Shop: precise errors for "Vault missing" vs "no economy plugin connected"; connects automatically when an economy
  registers late; purchase flow rebuilt on a tested routine (checks before charging, one charge, stock restored and
  money refunded on any failure, double-click guard, refund failure reported to console and audit log).
- New `/exotic admin economy` and `/exotic admin effects`.
- Exotic effects: Frost Walker, Density, bow weapon enchants and held protection implemented (see above); combinations
  with no working effect are no longer offered; `effects-audit.txt` generated.
- Crash recovery bug fixed (an interrupted claim could revive an old owner).
### 1.0.0
- First release.

## Known limitations (read before launch)
- Not compiled against the real Paper API by the author; see "If it does not compile".
- Database writes happen on the main thread (synchronous, tiny). Fine for 10-20 players.
- Exotic items inside chests/ender chests are only validated when the container is opened, and shulker boxes in
  player inventories are checked, but shulkers in the world are not.
- An exotic item destroyed by the void/cactus etc. is not automatically released: use `/exotic admin unregister <id>`.
- The registered owner is "whoever last held it". Trades that move the item without a pickup are caught by the scan.
- Anvil merges of an exotic item with normal enchanted books are allowed (the exotic stays).
- Shop books can fail to apply if that exotic already exists on the item type; the book is kept, nothing is lost.

## Testing checklist
1. Start the server, `/exotic admin rollnow` with two accounts holding swords (set `afk` low or keep moving).
2. `/exotic admin givebook <you> power 5`, book in off-hand, sword in main hand, `/exotic apply`.
3. Give the same book to a second player and apply to another sword: it must be refused.
4. Set `steal-bias: 1.0`, `protection-hours: 0`, run `rollnow` a few times: ownership should move and the
   loser's item must lose the exotic within ~3 seconds.
5. Drop the exotic item, die with it, put it in a chest and a shulker, restart the server: it must remain valid.
6. Try the vanilla grindstone and anvil with an exotic item.
7. `/exotic admin audit` must say the registry invariants are OK.
8. Test every command and the shop as a Bedrock player through Geyser.
9. Shop: with Vault + an economy installed, buy a book (balance drops once), try with too little money (no charge),
   try with a full inventory (no charge). Remove the economy plugin and check `/exotic shop` explains what is missing.
10. Effects: give yourself `/exotic admin givebook <you> frost_walker 2`, apply it to a sword (main hand, sword) and walk
    over water holding it; do the same with `density` on a bow and shoot while falling (jump from a height) at a mob;
    `protection` on a sword and take fall/fire damage. Compare against a plain sword.
