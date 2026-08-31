#!/usr/bin/env node
/**
 * Generates the density-specific Nexus Beam launcher icons and Android TV banners.
 *
 * The renderer deliberately uses only geometry defined below. That keeps the Android
 * assets reproducible, text-exact, and independent of installed fonts or image tools.
 */
import { mkdir, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { deflateSync } from "node:zlib";

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const supersample = 4;

const palette = {
  amber: [245, 158, 11, 255],
  background: [15, 19, 24, 255],
  blue: [37, 99, 235, 255],
  brightBlue: [59, 130, 246, 255],
  bannerBackground: [13, 15, 18, 255],
  bannerFooter: [23, 26, 33, 255],
  white: [248, 250, 252, 255],
};

function createSurface(width, height) {
  return {
    width,
    height,
    widthHi: width * supersample,
    heightHi: height * supersample,
    pixels: new Uint8ClampedArray(width * height * supersample * supersample * 4),
  };
}

function setPixel(surface, x, y, color) {
  if (x < 0 || y < 0 || x >= surface.widthHi || y >= surface.heightHi) return;
  const offset = (y * surface.widthHi + x) * 4;
  surface.pixels[offset] = color[0];
  surface.pixels[offset + 1] = color[1];
  surface.pixels[offset + 2] = color[2];
  surface.pixels[offset + 3] = color[3];
}

function fillRect(surface, x, y, width, height, color) {
  const scale = supersample;
  const left = Math.floor(x * scale);
  const top = Math.floor(y * scale);
  const right = Math.ceil((x + width) * scale);
  const bottom = Math.ceil((y + height) * scale);
  for (let py = top; py < bottom; py += 1) {
    for (let px = left; px < right; px += 1) setPixel(surface, px, py, color);
  }
}

function fillRoundedRect(surface, x, y, width, height, radius, color) {
  const scale = supersample;
  const left = Math.floor(x * scale);
  const top = Math.floor(y * scale);
  const right = Math.ceil((x + width) * scale);
  const bottom = Math.ceil((y + height) * scale);
  const radiusHi = radius * scale;
  const innerLeft = (x + radius) * scale;
  const innerRight = (x + width - radius) * scale;
  const innerTop = (y + radius) * scale;
  const innerBottom = (y + height - radius) * scale;

  for (let py = top; py < bottom; py += 1) {
    for (let px = left; px < right; px += 1) {
      const withinStraightEdge = px >= innerLeft && px <= innerRight || py >= innerTop && py <= innerBottom;
      const nearestX = Math.max(innerLeft, Math.min(px, innerRight));
      const nearestY = Math.max(innerTop, Math.min(py, innerBottom));
      const cornerDistance = Math.hypot(px - nearestX, py - nearestY);
      if (withinStraightEdge || cornerDistance <= radiusHi) setPixel(surface, px, py, color);
    }
  }
}

function drawLine(surface, x1, y1, x2, y2, width, color) {
  const scale = supersample;
  const startX = x1 * scale;
  const startY = y1 * scale;
  const endX = x2 * scale;
  const endY = y2 * scale;
  const radius = width * scale / 2;
  const minX = Math.floor(Math.min(startX, endX) - radius);
  const maxX = Math.ceil(Math.max(startX, endX) + radius);
  const minY = Math.floor(Math.min(startY, endY) - radius);
  const maxY = Math.ceil(Math.max(startY, endY) + radius);
  const dx = endX - startX;
  const dy = endY - startY;
  const lengthSquared = dx * dx + dy * dy;

  for (let py = minY; py <= maxY; py += 1) {
    for (let px = minX; px <= maxX; px += 1) {
      const progress = lengthSquared === 0
        ? 0
        : Math.max(0, Math.min(1, ((px - startX) * dx + (py - startY) * dy) / lengthSquared));
      const nearestX = startX + progress * dx;
      const nearestY = startY + progress * dy;
      if (Math.hypot(px - nearestX, py - nearestY) <= radius) setPixel(surface, px, py, color);
    }
  }
}

function fillTriangle(surface, a, b, c, color) {
  const scale = supersample;
  const points = [a, b, c].map(([x, y]) => [x * scale, y * scale]);
  const minX = Math.floor(Math.min(...points.map(([x]) => x)));
  const maxX = Math.ceil(Math.max(...points.map(([x]) => x)));
  const minY = Math.floor(Math.min(...points.map(([, y]) => y)));
  const maxY = Math.ceil(Math.max(...points.map(([, y]) => y)));
  const signedArea = (p1, p2, p3) => (p1[0] - p3[0]) * (p2[1] - p3[1]) - (p2[0] - p3[0]) * (p1[1] - p3[1]);
  const area = signedArea(points[0], points[1], points[2]);

  for (let py = minY; py <= maxY; py += 1) {
    for (let px = minX; px <= maxX; px += 1) {
      const point = [px, py];
      const w1 = signedArea(point, points[1], points[2]) / area;
      const w2 = signedArea(points[0], point, points[2]) / area;
      const w3 = signedArea(points[0], points[1], point) / area;
      if (w1 >= 0 && w2 >= 0 && w3 >= 0) setPixel(surface, px, py, color);
    }
  }
}

function renderIcon(size) {
  const surface = createSurface(size, size);
  const pad = size * 0.04;
  fillRoundedRect(surface, pad, pad, size - pad * 2, size - pad * 2, size * 0.18, palette.background);

  const xLeft = size * 0.29;
  const xRight = size * 0.71;
  const yTop = size * 0.29;
  const yBottom = size * 0.71;
  const beamWidth = size * 0.105;
  drawLine(surface, xLeft, yBottom, xLeft, yTop, beamWidth, palette.blue);
  drawLine(surface, xLeft, yTop, xRight, yBottom, beamWidth, palette.blue);
  drawLine(surface, xRight, yBottom, xRight, yTop, beamWidth, palette.brightBlue);
  fillTriangle(
    surface,
    [size * 0.47, size * 0.43],
    [size * 0.47, size * 0.59],
    [size * 0.62, size * 0.51],
    palette.amber,
  );
  return surface;
}

const glyphs = {
  A: [[[0, 1], [0.5, 0], [1, 1]], [[0.2, 0.6], [0.8, 0.6]]],
  D: [[[0, 0], [0, 1], [0.55, 1], [1, 0.75], [1, 0.25], [0.55, 0], [0, 0]]],
  E: [[[1, 0], [0, 0], [0, 1], [1, 1]], [[0, 0.5], [0.78, 0.5]]],
  G: [[[1, 0.18], [0.75, 0], [0.25, 0], [0, 0.25], [0, 0.75], [0.25, 1], [0.75, 1], [1, 0.82]], [[1, 0.57], [0.57, 0.57]]],
  I: [[[0.5, 0], [0.5, 1]]],
  N: [[[0, 1], [0, 0], [1, 1], [1, 0]]],
  S: [[[1, 0.15], [0.78, 0], [0.25, 0], [0, 0.22], [0.12, 0.43], [0.84, 0.58], [1, 0.78], [0.78, 1], [0.22, 1], [0, 0.85]]],
  T: [[[0, 0], [1, 0]], [[0.5, 0], [0.5, 1]]],
  U: [[[0, 0], [0, 0.75], [0.22, 1], [0.78, 1], [1, 0.75], [1, 0]]],
  V: [[[0, 0], [0.5, 1], [1, 0]]],
  X: [[[0, 0], [1, 1]], [[1, 0], [0, 1]]],
};

function wordWidth(text, height, spacing) {
  let units = 0;
  for (const character of text) units += character === " " ? 0.48 : 1 + spacing;
  return Math.max(0, units - spacing) * height * 0.62;
}

function drawWord(surface, text, centerX, y, height, color, strokeWidth) {
  const spacing = 0.2;
  let cursor = centerX - wordWidth(text, height, spacing) / 2;
  for (const character of text) {
    if (character === " ") {
      cursor += height * 0.62 * 0.48;
      continue;
    }
    const glyph = glyphs[character];
    if (!glyph) continue;
    const glyphWidth = height * 0.62;
    for (const path of glyph) {
      for (let index = 1; index < path.length; index += 1) {
        const [fromX, fromY] = path[index - 1];
        const [toX, toY] = path[index];
        drawLine(
          surface,
          cursor + fromX * glyphWidth,
          y + fromY * height,
          cursor + toX * glyphWidth,
          y + toY * height,
          strokeWidth,
          color,
        );
      }
    }
    cursor += glyphWidth * (1 + spacing);
  }
}

function renderBanner(width, height) {
  const surface = createSurface(width, height);
  const unit = width / 320;
  fillRect(surface, 0, 0, width, height, palette.bannerBackground);
  fillRect(surface, 0, height * 0.73, width, height * 0.27, palette.bannerFooter);

  const xLeft = 43 * unit;
  const xRight = 129 * unit;
  const yTop = 50 * unit;
  const yBottom = 131 * unit;
  const beamWidth = 17 * unit;
  drawLine(surface, xLeft, yBottom, xLeft, yTop, beamWidth, palette.blue);
  drawLine(surface, xLeft, yTop, xRight, yBottom, beamWidth, palette.blue);
  drawLine(surface, xRight, yBottom, xRight, yTop, beamWidth, palette.brightBlue);
  fillTriangle(surface, [77 * unit, 75 * unit], [77 * unit, 106 * unit], [105 * unit, 90.5 * unit], palette.amber);

  drawWord(surface, "NEXUS", 232 * unit, 45 * unit, 34 * unit, palette.white, 3.1 * unit);
  drawWord(surface, "TV GIDS", 232 * unit, 105 * unit, 24 * unit, palette.brightBlue, 2.6 * unit);
  return surface;
}

function downsample(surface) {
  const result = new Uint8Array(surface.width * surface.height * 4);
  const samples = supersample * supersample;
  for (let y = 0; y < surface.height; y += 1) {
    for (let x = 0; x < surface.width; x += 1) {
      let red = 0;
      let green = 0;
      let blue = 0;
      let alpha = 0;
      for (let dy = 0; dy < supersample; dy += 1) {
        for (let dx = 0; dx < supersample; dx += 1) {
          const source = (((y * supersample + dy) * surface.widthHi) + x * supersample + dx) * 4;
          const opacity = surface.pixels[source + 3] / 255;
          red += surface.pixels[source] * opacity;
          green += surface.pixels[source + 1] * opacity;
          blue += surface.pixels[source + 2] * opacity;
          alpha += opacity;
        }
      }
      const target = (y * surface.width + x) * 4;
      const opacity = alpha / samples;
      if (opacity > 0) {
        result[target] = Math.round(red / alpha);
        result[target + 1] = Math.round(green / alpha);
        result[target + 2] = Math.round(blue / alpha);
      }
      result[target + 3] = Math.round(opacity * 255);
    }
  }
  return result;
}

const crcTable = (() => {
  const table = new Uint32Array(256);
  for (let index = 0; index < 256; index += 1) {
    let value = index;
    for (let bit = 0; bit < 8; bit += 1) value = value & 1 ? 0xEDB88320 ^ (value >>> 1) : value >>> 1;
    table[index] = value >>> 0;
  }
  return table;
})();

function crc32(data) {
  let value = 0xFFFFFFFF;
  for (const byte of data) value = crcTable[(value ^ byte) & 0xFF] ^ (value >>> 8);
  return (value ^ 0xFFFFFFFF) >>> 0;
}

function pngChunk(type, data) {
  const typeData = Buffer.from(type, "ascii");
  const length = Buffer.alloc(4);
  length.writeUInt32BE(data.length);
  const checksum = Buffer.alloc(4);
  checksum.writeUInt32BE(crc32(Buffer.concat([typeData, data])));
  return Buffer.concat([length, typeData, data, checksum]);
}

function encodePng(width, height, pixels) {
  const raw = Buffer.alloc((width * 4 + 1) * height);
  for (let y = 0; y < height; y += 1) {
    const row = y * (width * 4 + 1);
    raw[row] = 0;
    Buffer.from(pixels.buffer, pixels.byteOffset + y * width * 4, width * 4).copy(raw, row + 1);
  }
  const header = Buffer.alloc(13);
  header.writeUInt32BE(width, 0);
  header.writeUInt32BE(height, 4);
  header[8] = 8;
  header[9] = 6;
  const signature = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
  return Buffer.concat([signature, pngChunk("IHDR", header), pngChunk("IDAT", deflateSync(raw)), pngChunk("IEND", Buffer.alloc(0))]);
}

async function writeAsset(relativePath, surface) {
  const output = resolve(projectRoot, relativePath);
  await mkdir(dirname(output), { recursive: true });
  await writeFile(output, encodePng(surface.width, surface.height, downsample(surface)));
  console.log(relativePath);
}

for (const [density, size] of Object.entries({ mdpi: 48, hdpi: 72, xhdpi: 96, xxhdpi: 144, xxxhdpi: 192 })) {
  await writeAsset(`android/app/src/main/res/mipmap-${density}/ic_launcher_nexus_beam.png`, renderIcon(size));
}

for (const [density, width] of Object.entries({ mdpi: 160, hdpi: 240, xhdpi: 320, xxhdpi: 480, xxxhdpi: 640 })) {
  await writeAsset(`android/app/src/main/res/drawable-${density}/tv_banner_nexus_beam.png`, renderBanner(width, width * 9 / 16));
}
