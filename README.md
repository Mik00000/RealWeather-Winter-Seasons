<p align="center">
  <img src="src/main/resources/icon.png" alt="RealWeather: Winter & Seasons" width="96" />
</p>

# RealWeather: Winter & Seasons (`temp_winter`)

A lightweight, **zero-block / zero-item** real-world weather and winter atmosphere mod for Minecraft (`NeoForge`, `Forge`, `Fabric`).  
Safe to drop into existing worlds and safe to remove at any time without corrupting blocks or saves.

## Supported Versions & Loaders

Download ready-to-play `.jar` builds from the **[Releases](../../releases/latest)** page:

| Minecraft | NeoForge | Forge | Fabric |
| :--- | :---: | :---: | :---: |
| **1.21.1** | `realweather-neoforge-1.21.1-1.0.0.jar` | — | `realweather-fabric-1.21.1-1.0.0.jar` |
| **1.21.4** | `realweather-neoforge-1.21.4-1.0.0.jar` | — | `realweather-fabric-1.21.4-1.0.0.jar` |
| **1.20.1** | — | `realweather-forge-1.20.1-1.0.0.jar` | `realweather-fabric-1.20.1-1.0.0.jar` |

*(Requires no external library mods; Forge 1.20.1 bundles MixinExtras via Jar-in-Jar.)*

## Features

- **Real-World Weather Sync:** Polls **Open-Meteo** (with automatic **wttr.in** fallback) using a city name or dynamic `auto` IP geolocation (`https://ipapi.co/json/` + fallback).
- **3-State Precipitation & Thunderstorms:**
  - `<= 0.0°C`: Snowfall, uneven snowdrifts (up to `3` blocks / 24 layers via smooth 2D noise), and water freezing.
  - `0.0°C .. 3.0°C`: Wet snow (snowflakes in the air without snow layers or ice).
  - `> 3.0°C` (rain or WMO codes `95, 96, 99`): Real rain and thunderstorms.
- **Disables Vanilla Weather Cycle:** Prevents random vanilla storms and sleep-induced weather resets in the Overworld while real weather is active.
- **Interactive Snow & Ice Physics:**
  - **Snow Trampling:** Walking on multi-layer snow compresses it down to 1 layer.
  - **Warm Melting:** Snow layers and sky-exposed ice gradually melt above freezing.
  - **Fragile Ice:** Thin ice over water can crack under sprinting/falling players above `-5°C` (*Frost Walker* protects).
  - **Heat Sources & Machinery Protection:** Vanilla and modded heat sources (`blaze_burner`, `stove`, `campfire`, `furnace`, `light >= 12`) keep a configurable radius clear; rails, tracks, and Create machinery (`belt`, `shaft`, `cogwheel`, `funnel`, `depot`, etc.) are protected from snow buildup (customizable in config).
- **Altitude Cold & Optional IRL Clock:** Cooler temperatures above `y = 120` (`-1°C` per 16 blocks) for mountains and airships; optional `syncRealTime` sun/moon sync.
- **Winter Visuals & Audio:** Frosted grass/foliage/water tints, cold breath particles (`<= 0°C`), tree-shake snow, overhang snow drip, ground blizzard snowflakes, wind gusts, and non-damaging screen frost (`<= -10°C`, disabled indoors, near heat, or with 2+ leather armor pieces).
- **Localization:** English (`en_us`) and Ukrainian (`uk_ua`) included.

## Commands (OP Level 2)

- `/tempwinter status` — Show current location, temperature, wind speed, mode, and freeze/melt state.
- `/tempwinter sync` — Trigger an immediate weather fetch.
- `/tempwinter city <name|auto>` — Switch city and sync immediately.
- `/tempwinter mode <auto|snow|rain|thunder|clear|vanilla> [minutes]` — Override weather (optionally with an auto-expiring timer).
- `/tempwinter vanilla [minutes]` — Temporarily restore vanilla Minecraft weather.

## Mod Compatibility

No hard dependencies. Optional runtime compatibility is built in for:

- **Shaders (`Iris` / `Oculus`):** Automatically yields fog control when a shaderpack (e.g. Complementary) is active (`fogWithShaders = false`).
- **Seasons (`Serene Seasons`, `Ecliptic Seasons`, `Fabric Seasons`):** Default `seasonsBehavior = "SYNC_REAL_DATE"` syncs seasons to the real calendar and hemisphere (`RESPECT_SEASONS` and `OVERRIDE` also available).
- **Temperature Survival (`Cold Sweat`, `Tough As Nails`, `Legendary Survival Overhaul`, `Homeostatic`):** Provides clean base ambient temperature via `Biome.getBaseTemperature()` and TAN's positional modifier, yielding the screen frost overlay.
- **HUD & Tooltips (`Jade`):** Native snow/ice temperature & fragile ice tooltips + `F3` debug weather line.
- **Atmosphere & Weather (`Particle Rain`, `Snow! Real Magic!`, `Immersive Weathering: Renewed`, `AmbientSounds 6`, `Fog`, `WinterParticles`, `Breath.`, `Simple Clouds`, `Weather2`, `The Weather Update`, `LocalizedWeather`):** Automatically yields overlapping fog, particles, wind sounds, snow placement, or storm loops.

## Configuration

- **NeoForge / Forge:** `config/temp_winter-common.toml`
- **Fabric:** `config/temp_winter.json`

## License

[MIT License](LICENSE)
