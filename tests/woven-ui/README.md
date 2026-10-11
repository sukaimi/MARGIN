# Notebook UI host checks

Run `python3 tests/woven-ui/check.py` with Pillow. This verifies approved image hashes, launcher geometry, XML/source links, cached texture decode and minimum contrast/target contracts.

The current paper surface uses a cached 6x mean-centered contrast filter at 72/255 alpha; cloth uses 190/255 alpha. MIRROR shader coordinates scale with display density. Cards and controls draw a 3dp lower lip and 1.5dp highlight inside their rounded clips. Original PNG assets are unchanged.

Run `python3 tests/woven-ui/fallback_host.py` with a JDK to compile the production surface against graphics stubs. It checks null/OOM/success decode caching, bounded draw calls and contained edges, not Android rasterization.

Run `python3 tests/woven-ui/texture_visibility.py` for a synthetic pixel comparison. It writes `texture-visibility-proof.png` and numeric contrast/variation metrics. Pillow BOX approximates resource downsampling. These images are host illustrations, not app screenshots or phone acceptance.

`tools/derive_woven_assets.py` derives launcher images from the approved originals and records provenance. Regeneration is optional and may produce different PNG bytes with a different Pillow version; keep image changes and recorded hashes together.

The adaptive foreground retains the full supplied canvas at approximately 78% of the 108-unit viewport. Legacy icons use the central 72-unit viewport. The launcher proof is a synthetic mask approximation. Actual launcher masks, themed icons, font scaling, scrolling, touch geometry and visual texture strength remain device checks.
