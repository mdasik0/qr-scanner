import { createRequire } from "node:module";
import { Jimp } from "jimp";

const require = createRequire(import.meta.url);
const jsQR = require("jsqr") as (
  data: Uint8ClampedArray,
  width: number,
  height: number,
  options?: { inversionAttempts?: string }
) => { data: string } | null;

function tryDecode(
  data: Buffer | Uint8Array | Uint8ClampedArray,
  width: number,
  height: number
): string | null {
  const code = jsQR(new Uint8ClampedArray(data), width, height, {
    inversionAttempts: "attemptBoth",
  });
  return code?.data ?? null;
}

type Img = Awaited<ReturnType<typeof Jimp.read>>;

/**
 * Decode QR from an image buffer. Tuned for phone photos of screens (moiré):
 * blur softens the pixel grid, then a few scales / crops are tried.
 */
export async function decodeQrFromImageBuffer(
  buffer: Buffer
): Promise<string | null> {
  const original = (await Jimp.read(buffer)) as Img;

  const variants: Img[] = [
    original.clone() as Img,
    original.clone().blur(1) as Img,
    original.clone().blur(2) as Img,
    original.clone().greyscale() as Img,
    original.clone().greyscale().blur(1) as Img,
    original.clone().contrast(0.35).blur(1) as Img,
  ];

  const scales = [1, 0.7, 0.5, 0.35];
  const crops = [1, 0.7, 0.5];

  for (const base of variants) {
    for (const crop of crops) {
      for (const scale of scales) {
        const im = base.clone() as Img;
        if (crop < 1) {
          const w = Math.max(8, Math.floor(im.width * crop));
          const h = Math.max(8, Math.floor(im.height * crop));
          const x = Math.floor((im.width - w) / 2);
          const y = Math.floor((im.height - h) / 2);
          im.crop({ x, y, w, h });
        }
        if (scale !== 1) im.scale(scale);
        const { data, width, height } = im.bitmap;
        const hit = tryDecode(data, width, height);
        if (hit) return hit;
      }
    }
  }

  return null;
}
