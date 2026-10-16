These tiny RGB PNG fixtures were encoded with Pillow 12.3.0, not handwritten PNG/base64 data.
The JVM helper `TestPngFixtures` verifies every chunk CRC, IHDR dimensions, the complete zlib
stream, PNG scanline filters and every decoded RGB pixel. Instrumentation separately uses
Android's Bitmap PNG encoder and decoder. No desktop Java API is needed on the test classpath.

To reproduce with Pillow:

```python
from pathlib import Path
from PIL import Image
root = Path("app/src/test/resources/images")
for width, height, color in [(3, 2, 0xd52c36), (4, 3, 0x225fc7), (3, 2, 0x225fc7)]:
    rgb = ((color >> 16) & 255, (color >> 8) & 255, color & 255)
    Image.new("RGB", (width, height), rgb).save(
        root / f"solid-{width}x{height}-{color:06x}.png", format="PNG", compress_level=6)
```
