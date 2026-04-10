# Standalone Grain Model

This folder contains the current standalone experimental grain model used for look development before app-kernel integration.

Contents:
- `film_grain_preview.cs`: standalone preview engine
- `render_test_outputs.ps1`: local render wrapper
- `atlas_cache/atlas_v4_s128_seed13572468.bin`: baked 5-profile carrier atlas cache

Current behavior:
- `full` resolution uses `100%` grain strength
- `half` resolution uses about `66%` grain strength and outputs at half image dimensions
- `small` resolution uses about `33%` grain strength and outputs at quarter image dimensions

Status:
- This is the current tuned standalone model.
- It is not yet ported into the live app kernel.
