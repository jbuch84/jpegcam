using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;

namespace FilmGrainPreview
{
    public struct GrainSettings
    {
        public int Grain;
        public int GrainSize;
        public int OutputScaleDenom;
        public bool EnableChromaGrain;
        public uint Seed;
        public int BlockSize;
        public int TemplateSize;
    }

    public struct PixelRGB
    {
        public byte R;
        public byte G;
        public byte B;
    }

    internal struct CurvePoint
    {
        public float X;
        public float Y;

        public CurvePoint(float x, float y)
        {
            X = x;
            Y = y;
        }
    }

    internal sealed class StreamingFilmGrainEngine
    {
        private sealed class GrainTemplates
        {
            public int Size;
            public int Mask;
            public sbyte[] Fine;
            public sbyte[] Medium;
            public sbyte[] Coarse;
            public sbyte[] Point;
            public sbyte[] PointLarge;
            public sbyte[] PointHuge;
            public sbyte[] Chroma;
            public sbyte[] DeepDense;
            public sbyte[] ShadowDense;
            public sbyte[] MidOpen;
            public sbyte[] BrightOpen;
            public sbyte[] HighDense;
            public sbyte[][] AtlasStates;
        }

        private sealed class ControlGrid
        {
            public int Shift;
            public int Width;
            public int Height;
            public byte[] RolledY;
            public byte[] BlurY;
            public byte[] DensityY;
            public byte[] EdgeMag;
            public byte[] Flatness;
            public byte[] TextureBoostQ;
            public byte[] Offset0X;
            public byte[] Offset0Y;
            public byte[] Offset1X;
            public byte[] Offset1Y;
        }

        private int width;
        private GrainSettings settings;
        private GrainTemplates templates;
        private ControlGrid controlGrid;
        private bool initialized;
        private int atlasPhase0X;
        private int atlasPhase0Y;
        private int atlasPhase1X;
        private int atlasPhase1Y;

        private readonly byte[] toneLut = new byte[256];
        private readonly byte[] grainAmountLut = new byte[256];
        private readonly byte[] grainDensityLut = new byte[256];
        private readonly byte[] microRolloffLut = new byte[256];
        private readonly byte[] atlasProfileLut = new byte[256];
        private readonly byte[] atlasProfileLoLut = new byte[256];
        private readonly byte[] atlasProfileHiLut = new byte[256];
        private readonly byte[] atlasProfileMixLut = new byte[256];
        private readonly ushort[] densityEncodeLut = new ushort[256];
        private readonly byte[] densityDecodeLut = new byte[4096];

        private const uint AtlasCacheMagic = 0x54414746u;
        private const int AtlasCacheVersion = 4;

        private PixelRGB[] prevRgb;
        private PixelRGB[] currRgb;
        private PixelRGB[] nextRgb;
        private byte[] prevY;
        private byte[] currY;
        private byte[] nextY;
        private byte[] prevToneY;
        private byte[] currToneY;
        private byte[] nextToneY;
        private byte[] rowCtrlRolledY;
        private byte[] rowCtrlDensityY;
        private byte[] rowCtrlFlat;
        private byte[] rowCtrlTextureQ;
        private byte[] rowCtrlSurfaceLift;
        private byte[] rowCtrlSuppress;
        private byte[] rowCtrlDarkBoost;
        private byte[] rowCtrlHighlightBoost;
        private byte[] rowCtrlEdgeAtten;
        private byte[] rowCtrlProfile;
        private byte[] rowCtrlEdgeMag;
        private byte[] rowCtrlOffsetX;
        private byte[] rowCtrlOffsetY;

        private bool havePrev;
        private bool haveCurr;
        private int currAbsY;

        public bool Init(int rowWidth, GrainSettings initSettings)
        {
            if (rowWidth <= 0) return false;
            if (initSettings.TemplateSize < 32) return false;
            if ((initSettings.TemplateSize & (initSettings.TemplateSize - 1)) != 0) return false;

            width = rowWidth;
            settings = initSettings;
            BuildLuts();
            AllocateRows();
            templates = LoadOrBuildTemplates(initSettings.TemplateSize, initSettings.Seed);
            atlasPhase0X = (int)(settings.Seed & (uint)templates.Mask);
            atlasPhase0Y = (int)((settings.Seed >> 8) & (uint)templates.Mask);
            atlasPhase1X = (int)((settings.Seed >> 16) & (uint)templates.Mask);
            atlasPhase1Y = (int)((settings.Seed >> 24) & (uint)templates.Mask);
            initialized = true;
            Reset();
            return true;
        }

        public void Reset()
        {
            havePrev = false;
            haveCurr = false;
            currAbsY = -1;
        }

        public bool SubmitRow(PixelRGB[] input, int offset, int absY, PixelRGB[] output, out int outAbsY)
        {
            outAbsY = -1;
            if (!initialized || input == null || output == null) return false;

            if (!haveCurr)
            {
                CopyRow(input, offset, currRgb, currY, currToneY);
                currAbsY = absY;
                haveCurr = true;
                return false;
            }

            CopyRow(input, offset, nextRgb, nextY, nextToneY);

            if (!havePrev)
            {
                ProcessRowCore(currRgb, currY, currY, nextY, currAbsY, output);
                outAbsY = currAbsY;
                RotateRows(absY);
                havePrev = true;
                return true;
            }

            ProcessRowCore(currRgb, prevY, currY, nextY, currAbsY, output);
            outAbsY = currAbsY;
            RotateRows(absY);
            return true;
        }

        public bool Flush(PixelRGB[] output, out int outAbsY)
        {
            outAbsY = -1;
            if (!initialized || output == null || !haveCurr) return false;

            if (!havePrev)
            {
                ProcessRowCore(currRgb, currY, currY, currY, currAbsY, output);
                outAbsY = currAbsY;
                haveCurr = false;
                return true;
            }

            ProcessRowCore(currRgb, prevY, currY, currY, currAbsY, output);
            outAbsY = currAbsY;
            haveCurr = false;
            havePrev = false;
            return true;
        }

        public static void ProcessFrame(PixelRGB[] pixels, int width, int height, GrainSettings settings)
        {
            var engine = new StreamingFilmGrainEngine();
            if (!engine.Init(width, settings)) return;
            engine.BuildControlGrid(pixels, height);

            var outRow = new PixelRGB[width];
            var frame = new PixelRGB[pixels.Length];
            int outY;

            for (int y = 0; y < height; ++y)
            {
                if (engine.SubmitRow(pixels, y * width, y, outRow, out outY))
                {
                    Array.Copy(outRow, 0, frame, outY * width, width);
                }
            }

            while (engine.Flush(outRow, out outY))
            {
                Array.Copy(outRow, 0, frame, outY * width, width);
            }

            Array.Copy(frame, pixels, pixels.Length);
        }

        private static int Clamp8(int x)
        {
            return x < 0 ? 0 : (x > 255 ? 255 : x);
        }

        private static int AbsI(int v)
        {
            return v < 0 ? -v : v;
        }

        private static float ClampF(float v, float lo, float hi)
        {
            return v < lo ? lo : (v > hi ? hi : v);
        }

        private static float Smoothstep01(float t)
        {
            t = ClampF(t, 0.0f, 1.0f);
            return t * t * (3.0f - 2.0f * t);
        }

        private static uint HashU32(uint x, uint y, uint seed)
        {
            uint h = x * 0x9E3779B1u;
            h ^= y * 0x85EBCA77u;
            h ^= seed * 0xC2B2AE3Du;
            h ^= h >> 16;
            h *= 0x7FEB352Du;
            h ^= h >> 15;
            h *= 0x846CA68Bu;
            h ^= h >> 16;
            return h;
        }

        private static uint NextRng(ref uint state)
        {
            state = state * 1664525u + 1013904223u;
            return state;
        }

        private static float RandUnit(ref uint state)
        {
            return ((NextRng(ref state) >> 8) & 0x00FFFFFFu) * (1.0f / 16777215.0f);
        }

        private static float Gaussianish(ref uint state)
        {
            float sum = 0.0f;
            for (int i = 0; i < 6; ++i) sum += RandUnit(ref state);
            return (sum - 3.0f) * 1.41421356f;
        }

        private static int Luma8(PixelRGB px)
        {
            return (px.R * 77 + px.G * 150 + px.B * 29) >> 8;
        }

        private static void RgbToYCbCr(PixelRGB px, out int y, out int cb, out int cr)
        {
            y = Luma8(px);
            cb = px.B - y;
            cr = px.R - y;
        }

        private static PixelRGB YCbCrToRgb(int y, int cb, int cr)
        {
            PixelRGB px;
            px.R = (byte)Clamp8(y + cr);
            px.B = (byte)Clamp8(y + cb);
            px.G = (byte)Clamp8(y - ((cb * 29 + cr * 77) >> 8));
            return px;
        }

        private static PixelRGB RebuildRgbFromLumaRatio(PixelRGB src, int targetY)
        {
            int srcY = Math.Max(1, Luma8(src));
            int scale = ((targetY + 4) << 12) / (srcY + 4);
            if (scale < 2048) scale = 2048;
            if (scale > 8192) scale = 8192;

            PixelRGB px;
            px.R = (byte)Clamp8((src.R * scale + 2048) >> 12);
            px.G = (byte)Clamp8((src.G * scale + 2048) >> 12);
            px.B = (byte)Clamp8((src.B * scale + 2048) >> 12);

            int lift = targetY - Luma8(px);
            px.R = (byte)Clamp8(px.R + lift);
            px.G = (byte)Clamp8(px.G + lift);
            px.B = (byte)Clamp8(px.B + lift);
            return px;
        }

        private static int Bilerp8(int a00, int a10, int a01, int a11, int fx, int fy)
        {
            int wx0 = 256 - fx;
            int wy0 = 256 - fy;
            int top = a00 * wx0 + a10 * fx;
            int bot = a01 * wx0 + a11 * fx;
            return (top * wy0 + bot * fy) >> 16;
        }

        private static int SignedHash8(int x, int y, uint seed)
        {
            return (int)(HashU32((uint)x, (uint)y, seed) & 255u) - 128;
        }

        private static int ValueNoise(int x, int y, int cellSize, uint seed)
        {
            if (cellSize < 1) cellSize = 1;
            int bx = x / cellSize;
            int by = y / cellSize;
            int fx = ((x % cellSize) << 8) / cellSize;
            int fy = ((y % cellSize) << 8) / cellSize;

            int a00 = SignedHash8(bx, by, seed);
            int a10 = SignedHash8(bx + 1, by, seed);
            int a01 = SignedHash8(bx, by + 1, seed);
            int a11 = SignedHash8(bx + 1, by + 1, seed);
            return Bilerp8(a00, a10, a01, a11, fx, fy);
        }

        private static int GrainPacketField(int x, int y, int cellSize, uint seed, int bias)
        {
            if (cellSize < 2) cellSize = 2;

            int gx = x / cellSize;
            int gy = y / cellSize;
            int px = x << 8;
            int py = y << 8;
            int accum = 0;

            for (int cy = gy - 1; cy <= gy + 1; ++cy)
            {
                for (int cx = gx - 1; cx <= gx + 1; ++cx)
                {
                    uint h = HashU32((uint)cx, (uint)cy, seed);
                    int cxp = (cx * cellSize << 8) + (int)(h & 255u) * cellSize;
                    int cyp = (cy * cellSize << 8) + (int)((h >> 8) & 255u) * cellSize;
                    int radius = cellSize * (150 + (int)((h >> 16) & 63u));
                    int strength = 108 + (int)((h >> 24) & 63u);
                    int dx = px - cxp;
                    int dy = py - cyp;
                    long dist2 = (long)dx * dx + (long)dy * dy;
                    long r2 = (long)radius * radius;
                    if (dist2 >= r2) continue;

                    int t = 256 - (int)((dist2 * 256L) / Math.Max(1L, r2));
                    accum += (strength * t * t) >> 8;
                }
            }

            accum -= bias;
            if (accum < -255) accum = -255;
            if (accum > 255) accum = 255;
            return accum;
        }

        private static int GrainDotField(int x, int y, int cellSize, uint seed)
        {
            if (cellSize < 3) cellSize = 3;

            int gx = x / cellSize;
            int gy = y / cellSize;
            int px = x << 8;
            int py = y << 8;
            int accum = 0;

            for (int cy = gy - 1; cy <= gy + 1; ++cy)
            {
                for (int cx = gx - 1; cx <= gx + 1; ++cx)
                {
                    uint h = HashU32((uint)cx, (uint)cy, seed);
                    int cxp = (cx * cellSize << 8) + (int)(h & 255u) * cellSize;
                    int cyp = (cy * cellSize << 8) + (int)((h >> 8) & 255u) * cellSize;
                    int radius = cellSize * (92 + (int)((h >> 16) & 31u));
                    int strength = 92 + (int)((h >> 21) & 63u);
                    int sign = (h & 0x10000000u) != 0 ? 1 : -1;
                    int dx = px - cxp;
                    int dy = py - cyp;
                    long dist2 = (long)dx * dx + (long)dy * dy;
                    long r2 = (long)radius * radius;
                    if (dist2 >= r2) continue;

                    int t = 256 - (int)((dist2 * 256) / Math.Max(1L, r2));
                    accum += sign * ((strength * t * t) >> 8);
                }
            }

            if (accum < -255) accum = -255;
            if (accum > 255) accum = 255;
            return accum;
        }

        private void AllocateRows()
        {
            prevRgb = new PixelRGB[width];
            currRgb = new PixelRGB[width];
            nextRgb = new PixelRGB[width];
            prevY = new byte[width];
            currY = new byte[width];
            nextY = new byte[width];
            prevToneY = new byte[width];
            currToneY = new byte[width];
            nextToneY = new byte[width];
            rowCtrlRolledY = new byte[width];
            rowCtrlDensityY = new byte[width];
            rowCtrlFlat = new byte[width];
            rowCtrlTextureQ = new byte[width];
            rowCtrlSurfaceLift = new byte[width];
            rowCtrlSuppress = new byte[width];
            rowCtrlDarkBoost = new byte[width];
            rowCtrlHighlightBoost = new byte[width];
            rowCtrlEdgeAtten = new byte[width];
            rowCtrlProfile = new byte[width];
            rowCtrlEdgeMag = new byte[width];
            rowCtrlOffsetX = new byte[width];
            rowCtrlOffsetY = new byte[width];
        }

        private void BuildLuts()
        {
            for (int i = 0; i < 256; ++i) toneLut[i] = (byte)i;

            BuildCurveLut(new[]
            {
                new CurvePoint(0.00f, 0.30f),
                new CurvePoint(0.07f, 0.31f),
                new CurvePoint(0.16f, 0.38f),
                new CurvePoint(0.28f, 0.58f),
                new CurvePoint(0.40f, 0.90f),
                new CurvePoint(0.50f, 1.00f),
                new CurvePoint(0.58f, 0.90f),
                new CurvePoint(0.66f, 0.68f),
                new CurvePoint(0.74f, 0.40f),
                new CurvePoint(0.82f, 0.16f),
                new CurvePoint(0.88f, 0.08f),
                new CurvePoint(0.90f, 0.06f),
                new CurvePoint(1.00f, 0.03f)
            }, grainAmountLut);

            BuildCurveLut(new[]
            {
                new CurvePoint(0.00f, 1.00f),
                new CurvePoint(0.08f, 0.98f),
                new CurvePoint(0.18f, 0.90f),
                new CurvePoint(0.32f, 0.56f),
                new CurvePoint(0.48f, 0.18f),
                new CurvePoint(0.58f, 0.14f),
                new CurvePoint(0.68f, 0.24f),
                new CurvePoint(0.80f, 0.58f),
                new CurvePoint(0.90f, 0.84f),
                new CurvePoint(1.00f, 0.98f)
            }, grainDensityLut);

            BuildCurveLut(new[]
            {
                new CurvePoint(0.00f, 0.00f),
                new CurvePoint(0.10f, 0.00f),
                new CurvePoint(0.22f, 0.08f),
                new CurvePoint(0.38f, 0.18f),
                new CurvePoint(0.52f, 0.26f),
                new CurvePoint(0.66f, 0.30f),
                new CurvePoint(0.80f, 0.24f),
                new CurvePoint(0.92f, 0.10f),
                new CurvePoint(1.00f, 0.00f)
            }, microRolloffLut);

            BuildDensityLuts();
            BuildAtlasProfileLut();
        }

        private void BuildDensityLuts()
        {
            for (int y = 0; y < 256; ++y)
            {
                float exposure = (y + 1.0f) / 256.0f;
                int density = (int)Math.Round(-Math.Log(Math.Max(1e-6f, exposure)) * 640.0);
                if (density < 0) density = 0;
                if (density >= densityDecodeLut.Length) density = densityDecodeLut.Length - 1;
                densityEncodeLut[y] = (ushort)density;
            }

            for (int d = 0; d < densityDecodeLut.Length; ++d)
            {
                float exposure = (float)Math.Exp(-d / 640.0);
                densityDecodeLut[d] = (byte)Clamp8((int)Math.Round(exposure * 256.0 - 1.0));
            }
        }

        private void BuildAtlasProfileLut()
        {
            const int transitionWidth = 20;
            for (int d = 0; d < 256; ++d)
            {
                if (d < 56) atlasProfileLut[d] = 0;
                else if (d < 104) atlasProfileLut[d] = 1;
                else if (d < 156) atlasProfileLut[d] = 2;
                else if (d < 208) atlasProfileLut[d] = 3;
                else atlasProfileLut[d] = 4;

                int lo = atlasProfileLut[d];
                int hi = lo;
                int mix = 0;

                if (d >= 56 - transitionWidth && d < 56 + transitionWidth)
                {
                    lo = 0;
                    hi = 1;
                    mix = ((d - (56 - transitionWidth)) * 255 + transitionWidth) / (transitionWidth << 1);
                }
                else if (d >= 104 - transitionWidth && d < 104 + transitionWidth)
                {
                    lo = 1;
                    hi = 2;
                    mix = ((d - (104 - transitionWidth)) * 255 + transitionWidth) / (transitionWidth << 1);
                }
                else if (d >= 156 - transitionWidth && d < 156 + transitionWidth)
                {
                    lo = 2;
                    hi = 3;
                    mix = ((d - (156 - transitionWidth)) * 255 + transitionWidth) / (transitionWidth << 1);
                }
                else if (d >= 208 - transitionWidth && d < 208 + transitionWidth)
                {
                    lo = 3;
                    hi = 4;
                    mix = ((d - (208 - transitionWidth)) * 255 + transitionWidth) / (transitionWidth << 1);
                }

                if (mix < 0) mix = 0;
                if (mix > 255) mix = 255;
                atlasProfileLoLut[d] = (byte)lo;
                atlasProfileHiLut[d] = (byte)hi;
                atlasProfileMixLut[d] = (byte)mix;
            }
        }

        private static void BuildCurveLut(CurvePoint[] points, byte[] output)
        {
            int segment = 0;
            for (int i = 0; i < 256; ++i)
            {
                float x = i * (1.0f / 255.0f);
                while (segment + 1 < points.Length - 1 && x > points[segment + 1].X) ++segment;
                CurvePoint a = points[segment];
                CurvePoint b = points[segment + 1];
                float denom = Math.Max(1e-6f, b.X - a.X);
                float t = Smoothstep01((x - a.X) / denom);
                float y = a.Y + (b.Y - a.Y) * t;
                output[i] = (byte)Clamp8((int)Math.Round(ClampF(y, 0.0f, 1.0f) * 255.0f));
            }
        }

        private static void BlurField(float[] field, int size, int passes)
        {
            float[] original = field;
            var tmp = new float[field.Length];

            for (int pass = 0; pass < passes; ++pass)
            {
                for (int y = 0; y < size; ++y)
                {
                    for (int x = 0; x < size; ++x)
                    {
                        int y0 = (y - 1 + size) % size;
                        int y1 = y;
                        int y2 = (y + 1) % size;
                        int x0 = (x - 1 + size) % size;
                        int x1 = x;
                        int x2 = (x + 1) % size;
                        int idx = y * size + x;

                        float blur =
                            (field[y0 * size + x0] + field[y0 * size + x2] +
                             field[y2 * size + x0] + field[y2 * size + x2] +
                             2.0f * (field[y0 * size + x1] + field[y1 * size + x0] + field[y1 * size + x2] + field[y2 * size + x1]) +
                             4.0f * field[y1 * size + x1]) * (1.0f / 16.0f);

                        tmp[idx] = blur;
                    }
                }
                var swap = field;
                field = tmp;
                tmp = swap;
            }

            if (!object.ReferenceEquals(field, original))
            {
                Array.Copy(field, original, field.Length);
            }
        }

        private static sbyte[] GenerateCorrelatedTemplate(int size, uint seed, int blurA, int blurB, int blurC, float mixB, float mixC, int targetStd)
        {
            int count = size * size;
            var baseField = new float[count];
            var detailField = new float[count];
            var lowField = new float[count];

            uint baseSeed = HashU32((uint)size, (uint)targetStd, seed ^ 0xA341316Cu);
            uint detailSeed = HashU32((uint)(size * 3), (uint)(targetStd * 7), seed ^ 0xC8013EA4u);
            uint lowSeed = HashU32((uint)(size * 5), (uint)(targetStd * 11), seed ^ 0xAD90777Du);

            for (int i = 0; i < count; ++i)
            {
                baseField[i] = Gaussianish(ref baseSeed);
                detailField[i] = Gaussianish(ref detailSeed);
                lowField[i] = Gaussianish(ref lowSeed);
            }

            BlurField(baseField, size, Math.Max(1, blurA));
            BlurField(detailField, size, Math.Max(1, blurB));
            BlurField(lowField, size, Math.Max(1, blurC));

            double mean = 0.0;
            for (int i = 0; i < count; ++i)
            {
                float shaped = baseField[i] * (1.0f - mixB) + detailField[i] * mixB - lowField[i] * mixC;
                shaped /= 1.0f + 0.22f * Math.Abs(shaped);
                baseField[i] = shaped;
                mean += shaped;
            }
            mean /= count;

            double variance = 0.0;
            for (int i = 0; i < count; ++i)
            {
                double d = baseField[i] - mean;
                variance += d * d;
            }
            variance /= Math.Max(1, count - 1);
            double stdDev = Math.Sqrt(Math.Max(1e-9, variance));
            double scale = targetStd / stdDev;

            var output = new sbyte[count];
            for (int i = 0; i < count; ++i)
            {
                int v = (int)Math.Round((baseField[i] - mean) * scale);
                if (v < -127) v = -127;
                if (v > 127) v = 127;
                output[i] = (sbyte)v;
            }
            return output;
        }

        private static sbyte[] GeneratePointTemplate(int size, uint seed, int dotCount, int blurPasses, int targetStd)
        {
            int count = size * size;
            var field = new float[count];
            uint state = HashU32((uint)(size * 9), (uint)dotCount, seed ^ 0xA24BAED4u);

            for (int i = 0; i < dotCount; ++i)
            {
                float x = ((NextRng(ref state) & 0xFFFFu) * (1.0f / 65536.0f)) * size;
                float y = (((NextRng(ref state) >> 8) & 0xFFFFu) * (1.0f / 65536.0f)) * size;
                float sign = (NextRng(ref state) & 1u) == 0 ? -1.0f : 1.0f;
                float strength = 1.0f + RandUnit(ref state) * 1.8f;
                float radius = 0.9f + RandUnit(ref state) * (1.0f + blurPasses * 0.45f);
                DrawSoftDot(field, size, x, y, radius, sign * strength);
            }

            if (blurPasses > 0)
            {
                BlurField(field, size, Math.Max(1, blurPasses >> 1));
            }

            double mean = 0.0;
            for (int i = 0; i < count; ++i) mean += field[i];
            mean /= count;

            double variance = 0.0;
            for (int i = 0; i < count; ++i)
            {
                double d = field[i] - mean;
                variance += d * d;
            }
            variance /= Math.Max(1, count - 1);
            double stdDev = Math.Sqrt(Math.Max(1e-9, variance));
            double scale = targetStd / stdDev;

            var output = new sbyte[count];
            for (int i = 0; i < count; ++i)
            {
                int v = (int)Math.Round((field[i] - mean) * scale);
                if (v < -127) v = -127;
                if (v > 127) v = 127;
                output[i] = (sbyte)v;
            }
            return output;
        }

        private static void DrawSoftDot(float[] field, int size, float fx, float fy, float radius, float amplitude)
        {
            int ir = (int)Math.Ceiling(radius * 1.85f);
            int cx = (int)Math.Floor(fx);
            int cy = (int)Math.Floor(fy);
            float invR2 = 1.0f / Math.Max(0.25f, radius * radius);

            for (int oy = -ir; oy <= ir; ++oy)
            {
                int y = cy + oy;
                while (y < 0) y += size;
                while (y >= size) y -= size;
                float dy = (cy + oy + 0.5f) - fy;

                for (int ox = -ir; ox <= ir; ++ox)
                {
                    int x = cx + ox;
                    while (x < 0) x += size;
                    while (x >= size) x -= size;
                    float dx = (cx + ox + 0.5f) - fx;
                    float dist2 = dx * dx + dy * dy;
                    float t = 1.0f - dist2 * invR2;
                    if (t <= 0.0f) continue;
                    t *= t;
                    field[y * size + x] += amplitude * t;
                }
            }
        }

        private static sbyte[] BuildCarrierTemplate(int size, sbyte[] fine, sbyte[] medium, sbyte[] coarse, sbyte[] point,
            int wPoint, int wFine, int wMedium, int wCoarse, int targetStd)
        {
            int count = size * size;
            var field = new float[count];
            double mean = 0.0;
            for (int i = 0; i < count; ++i)
            {
                float shaped =
                    point[i] * (wPoint / 256.0f) +
                    fine[i] * (wFine / 256.0f) +
                    medium[i] * (wMedium / 256.0f) +
                    coarse[i] * (wCoarse / 256.0f);
                shaped /= 1.0f + 0.16f * Math.Abs(shaped);
                field[i] = shaped;
                mean += shaped;
            }
            mean /= count;

            double variance = 0.0;
            for (int i = 0; i < count; ++i)
            {
                double d = field[i] - mean;
                variance += d * d;
            }
            variance /= Math.Max(1, count - 1);
            double stdDev = Math.Sqrt(Math.Max(1e-9, variance));
            double scale = targetStd / stdDev;

            var output = new sbyte[count];
            for (int i = 0; i < count; ++i)
            {
                int v = (int)Math.Round((field[i] - mean) * scale);
                if (v < -127) v = -127;
                if (v > 127) v = 127;
                output[i] = (sbyte)v;
            }
            return output;
        }

        private static string AtlasCachePath(int size, uint seed)
        {
            string cacheRoot = Environment.GetEnvironmentVariable("FILM_GRAIN_CACHE_DIR");
            if (string.IsNullOrWhiteSpace(cacheRoot))
            {
                cacheRoot = Path.Combine(Directory.GetCurrentDirectory(), "film_grain_atlas_cache");
            }
            Directory.CreateDirectory(cacheRoot);
            return Path.Combine(cacheRoot, string.Format("atlas_v{0}_s{1}_seed{2:X8}.bin", AtlasCacheVersion, size, seed));
        }

        private static GrainTemplates FinalizeTemplates(GrainTemplates templates)
        {
            templates.AtlasStates = new[] { templates.DeepDense, templates.ShadowDense, templates.MidOpen, templates.BrightOpen, templates.HighDense };
            return templates;
        }

        private static void WriteArray(BinaryWriter writer, sbyte[] values)
        {
            writer.Write(values.Length);
            byte[] bytes = new byte[values.Length];
            Buffer.BlockCopy(values, 0, bytes, 0, bytes.Length);
            writer.Write(bytes);
        }

        private static sbyte[] ReadArray(BinaryReader reader)
        {
            int length = reader.ReadInt32();
            byte[] bytes = reader.ReadBytes(length);
            if (bytes.Length != length) throw new EndOfStreamException();
            sbyte[] values = new sbyte[length];
            Buffer.BlockCopy(bytes, 0, values, 0, bytes.Length);
            return values;
        }

        private static GrainTemplates TryLoadTemplates(int size, uint seed)
        {
            string path = AtlasCachePath(size, seed);
            if (!File.Exists(path)) return null;

            try
            {
                using (var stream = File.OpenRead(path))
                using (var reader = new BinaryReader(stream))
                {
                    if (reader.ReadUInt32() != AtlasCacheMagic) return null;
                    if (reader.ReadInt32() != AtlasCacheVersion) return null;
                    if (reader.ReadInt32() != size) return null;
                    if (reader.ReadUInt32() != seed) return null;

                    var templates = new GrainTemplates
                    {
                        Size = size,
                        Mask = size - 1,
                        Fine = ReadArray(reader),
                        Medium = ReadArray(reader),
                        Coarse = ReadArray(reader),
                        Point = ReadArray(reader),
                        PointLarge = ReadArray(reader),
                        PointHuge = ReadArray(reader),
                        Chroma = ReadArray(reader),
                        DeepDense = ReadArray(reader),
                        ShadowDense = ReadArray(reader),
                        MidOpen = ReadArray(reader),
                        BrightOpen = ReadArray(reader),
                        HighDense = ReadArray(reader)
                    };
                    return FinalizeTemplates(templates);
                }
            }
            catch
            {
                return null;
            }
        }

        private static void SaveTemplates(GrainTemplates templates, uint seed)
        {
            string path = AtlasCachePath(templates.Size, seed);
            try
            {
                using (var stream = File.Create(path))
                using (var writer = new BinaryWriter(stream))
                {
                    writer.Write(AtlasCacheMagic);
                    writer.Write(AtlasCacheVersion);
                    writer.Write(templates.Size);
                    writer.Write(seed);
                    WriteArray(writer, templates.Fine);
                    WriteArray(writer, templates.Medium);
                    WriteArray(writer, templates.Coarse);
                    WriteArray(writer, templates.Point);
                    WriteArray(writer, templates.PointLarge);
                    WriteArray(writer, templates.PointHuge);
                    WriteArray(writer, templates.Chroma);
                    WriteArray(writer, templates.DeepDense);
                    WriteArray(writer, templates.ShadowDense);
                    WriteArray(writer, templates.MidOpen);
                    WriteArray(writer, templates.BrightOpen);
                    WriteArray(writer, templates.HighDense);
                }
            }
            catch
            {
            }
        }

        private static GrainTemplates BuildTemplates(int size, uint seed)
        {
            sbyte[] fine = GenerateCorrelatedTemplate(size, seed ^ 0x91E10DA5u, 1, 2, 6, 0.30f, 0.22f, 64);
            sbyte[] medium = GenerateCorrelatedTemplate(size, seed ^ 0xC79E7B1Du, 1, 3, 7, 0.24f, 0.18f, 28);
            sbyte[] coarse = GenerateCorrelatedTemplate(size, seed ^ 0xD1B54A35u, 2, 4, 9, 0.18f, 0.12f, 10);
            sbyte[] point = GeneratePointTemplate(size, seed ^ 0xA24BAED4u, (size * size) / 22, 2, 72);
            sbyte[] pointLarge = GeneratePointTemplate(size, seed ^ 0x4C7F0B29u, (size * size) / 34, 4, 104);
            sbyte[] pointHuge = GeneratePointTemplate(size, seed ^ 0x19E377A1u, (size * size) / 44, 5, 118);

            var templates = new GrainTemplates
            {
                Size = size,
                Mask = size - 1,
                Fine = fine,
                Medium = medium,
                Coarse = coarse,
                Point = point,
                PointLarge = pointLarge,
                PointHuge = pointHuge,
                Chroma = GenerateCorrelatedTemplate(size, seed ^ 0x94D049BBu, 1, 2, 4, 0.14f, 0.03f, 5),
                DeepDense = BuildCarrierTemplate(size, fine, medium, coarse, pointLarge, 116, 104, 20, 6, 70),
                ShadowDense = BuildCarrierTemplate(size, fine, medium, coarse, pointHuge, 150, 70, 18, 6, 86),
                MidOpen = BuildCarrierTemplate(size, fine, medium, coarse, pointHuge, 196, 52, 18, 6, 112),
                BrightOpen = BuildCarrierTemplate(size, fine, medium, coarse, pointLarge, 152, 70, 18, 6, 88),
                HighDense = BuildCarrierTemplate(size, fine, medium, coarse, point, 84, 112, 18, 6, 58)
            };
            return FinalizeTemplates(templates);
        }

        private static GrainTemplates LoadOrBuildTemplates(int size, uint seed)
        {
            GrainTemplates templates = TryLoadTemplates(size, seed);
            if (templates != null) return templates;

            templates = BuildTemplates(size, seed);
            SaveTemplates(templates, seed);
            return templates;
        }

        private void CopyRow(PixelRGB[] input, int offset, PixelRGB[] rgbOut, byte[] yOut, byte[] toneOut)
        {
            Array.Copy(input, offset, rgbOut, 0, width);
            for (int x = 0; x < width; ++x)
            {
                int y = Luma8(input[offset + x]);
                yOut[x] = (byte)y;
                toneOut[x] = toneLut[y];
            }
        }

        private void RotateRows(int newAbsY)
        {
            var tmpRgb = prevRgb;
            prevRgb = currRgb;
            currRgb = nextRgb;
            nextRgb = tmpRgb;

            var tmpY = prevY;
            prevY = currY;
            currY = nextY;
            nextY = tmpY;

            var tmpTone = prevToneY;
            prevToneY = currToneY;
            currToneY = nextToneY;
            nextToneY = tmpTone;

            currAbsY = newAbsY;
        }

        private int FlatnessWeight(byte[] prevRow, byte[] currRow, byte[] nextRow, int x)
        {
            int xm1 = x > 0 ? x - 1 : x;
            int xp1 = x + 1 < width ? x + 1 : x;

            int gx = AbsI(currRow[x] - currRow[xm1]) + AbsI(currRow[xp1] - currRow[x]);
            int gy = AbsI(currRow[x] - prevRow[x]) + AbsI(nextRow[x] - currRow[x]);
            int diag =
                AbsI(currRow[x] - prevRow[xm1]) +
                AbsI(currRow[x] - prevRow[xp1]) +
                AbsI(currRow[x] - nextRow[xm1]) +
                AbsI(currRow[x] - nextRow[xp1]);

            int activity = (gx + gy + (diag >> 1) + 2) >> 2;
            int edge = gx + gy + (diag >> 2);

            int weight = 228;
            if (activity < 20) weight += 20 - activity;
            weight -= Math.Min(132, edge + (activity >> 1));
            if (weight < 96) weight = 96;
            if (weight > 272) weight = 272;
            return weight;
        }

        private int Blur3x3Center(byte[] prevRow, byte[] currRow, byte[] nextRow, int x)
        {
            int xm1 = x > 0 ? x - 1 : x;
            int xp1 = x + 1 < width ? x + 1 : x;
            int sum =
                prevRow[xm1] + (prevRow[x] << 1) + prevRow[xp1] +
                (currRow[xm1] << 1) + (currRow[x] << 2) + (currRow[xp1] << 1) +
                nextRow[xm1] + (nextRow[x] << 1) + nextRow[xp1];
            return (sum + 8) >> 4;
        }

        private int RolledToneLuma(byte[] prevRow, byte[] currRow, byte[] nextRow, int x)
        {
            int center = currRow[x];
            int blur = Blur3x3Center(prevRow, currRow, nextRow, x);
            int detail = center - blur;
            int detailAbs = AbsI(detail);

            int rolloff = microRolloffLut[center];
            int gate = 256 - Math.Min(196, detailAbs * 9);
            if (gate < 36) gate = 36;
            rolloff = (rolloff * gate + 128) >> 8;
            return Clamp8(center - ((detail * rolloff + 128) >> 8));
        }

        private int TextureLumaBoost(byte[] prevRow, byte[] currRow, byte[] nextRow, int x)
        {
            int xm1 = x > 0 ? x - 1 : x;
            int xp1 = x + 1 < width ? x + 1 : x;

            int center = currRow[x];
            int blur = Blur3x3Center(prevRow, currRow, nextRow, x);
            int detailAbs = AbsI(center - blur);
            int dx = currRow[xp1] - currRow[xm1];
            int dy = nextRow[x] - prevRow[x];
            int ax = AbsI(dx);
            int ay = AbsI(dy);
            int coherence = AbsI(ax - ay);
            int activity =
                ax + ay +
                AbsI(currRow[x] - prevRow[xm1]) +
                AbsI(currRow[x] - prevRow[xp1]) +
                AbsI(currRow[x] - nextRow[xm1]) +
                AbsI(currRow[x] - nextRow[xp1]);

            int boost = 256;
            if (activity < 16) boost += 112;
            else if (activity < 32) boost += 84;
            else if (activity < 56) boost += 48;

            if (detailAbs < 6) boost += 64;
            else if (detailAbs < 12) boost += 28;

            int textureScore = activity - coherence * 2;
            if (textureScore > 52 && detailAbs < 18) boost += Math.Min(56, textureScore - 52);
            if (coherence > 22) boost -= Math.Min(104, (coherence - 22) * 3);
            if (boost < 216) boost = 216;
            if (boost > 432) boost = 432;
            return boost;
        }

        private static int SampleTemplate(sbyte[] tpl, int sizeMask, int x, int y)
        {
            return tpl[((y & sizeMask) * (sizeMask + 1)) + (x & sizeMask)];
        }

        private void BuildControlGrid(PixelRGB[] pixels, int height)
        {
            controlGrid = null;
            if (settings.GrainSize < 2 || pixels == null || height <= 0) return;

            const int shift = 2;
            int cellSize = 1 << shift;
            int gridW = (width + cellSize - 1) >> shift;
            int gridH = (height + cellSize - 1) >> shift;

            var grid = new ControlGrid
            {
                Shift = shift,
                Width = gridW,
                Height = gridH,
                RolledY = new byte[gridW * gridH],
                BlurY = new byte[gridW * gridH],
                DensityY = new byte[gridW * gridH],
                EdgeMag = new byte[gridW * gridH],
                Flatness = new byte[gridW * gridH],
                TextureBoostQ = new byte[gridW * gridH],
                Offset0X = new byte[gridW * gridH],
                Offset0Y = new byte[gridW * gridH],
                Offset1X = new byte[gridW * gridH],
                Offset1Y = new byte[gridW * gridH]
            };

            var luma = new byte[pixels.Length];
            for (int i = 0; i < pixels.Length; ++i)
            {
                luma[i] = (byte)Luma8(pixels[i]);
            }

            for (int gy = 0; gy < gridH; ++gy)
            {
                int sy = Math.Min(height - 1, (gy << shift) + (cellSize >> 1));
                for (int gx = 0; gx < gridW; ++gx)
                {
                    int sx = Math.Min(width - 1, (gx << shift) + (cellSize >> 1));
                    int center = FrameLumaAt(luma, sx, sy, height);
                    int blur = FrameBlur3x3Center(luma, sx, sy, height);
                    int rolled = FrameRolledToneLuma(luma, sx, sy, height);
                    int flat = FrameFlatnessWeight(luma, sx, sy, height);
                    int textureBoost = FrameTextureLumaBoost(luma, sx, sy, height);
                    int xm1 = sx > 0 ? sx - 1 : sx;
                    int xp1 = sx + 1 < width ? sx + 1 : sx;
                    int ym1 = sy > 0 ? sy - 1 : sy;
                    int yp1 = sy + 1 < height ? sy + 1 : sy;
                    int dx = FrameLumaAt(luma, xp1, sy, height) - FrameLumaAt(luma, xm1, sy, height);
                    int dy = FrameLumaAt(luma, sx, yp1, height) - FrameLumaAt(luma, sx, ym1, height);
                    int edgeMag = Math.Max(AbsI(dx), AbsI(dy));
                    int density = (center + rolled + (blur << 1) + 2) >> 2;

                    int idx = gy * gridW + gx;
                    grid.RolledY[idx] = (byte)Clamp8(rolled);
                    grid.BlurY[idx] = (byte)Clamp8(blur);
                    grid.DensityY[idx] = (byte)Clamp8(density);
                    grid.EdgeMag[idx] = (byte)Clamp8(edgeMag);
                    grid.Flatness[idx] = (byte)Clamp8(flat);
                    grid.TextureBoostQ[idx] = (byte)Clamp8((textureBoost + 1) >> 1);
                    uint h0 = HashU32((uint)gx, (uint)gy, settings.Seed ^ 0x4C7F0B29u);
                    uint h1 = HashU32((uint)gx, (uint)gy, settings.Seed ^ 0x91E10DA5u);
                    grid.Offset0X[idx] = (byte)(h0 & (uint)templates.Mask);
                    grid.Offset0Y[idx] = (byte)((h0 >> 8) & (uint)templates.Mask);
                    grid.Offset1X[idx] = (byte)(h1 & (uint)templates.Mask);
                    grid.Offset1Y[idx] = (byte)((h1 >> 8) & (uint)templates.Mask);
                }
            }

            controlGrid = grid;
        }

        private int FrameLumaAt(byte[] luma, int x, int y, int height)
        {
            if (x < 0) x = 0;
            if (x >= width) x = width - 1;
            if (y < 0) y = 0;
            if (y >= height) y = height - 1;
            return luma[y * width + x];
        }

        private int FrameBlur3x3Center(byte[] luma, int x, int y, int height)
        {
            int xm1 = x > 0 ? x - 1 : x;
            int xp1 = x + 1 < width ? x + 1 : x;
            int ym1 = y > 0 ? y - 1 : y;
            int yp1 = y + 1 < height ? y + 1 : y;
            int sum =
                FrameLumaAt(luma, xm1, ym1, height) + (FrameLumaAt(luma, x, ym1, height) << 1) + FrameLumaAt(luma, xp1, ym1, height) +
                (FrameLumaAt(luma, xm1, y, height) << 1) + (FrameLumaAt(luma, x, y, height) << 2) + (FrameLumaAt(luma, xp1, y, height) << 1) +
                FrameLumaAt(luma, xm1, yp1, height) + (FrameLumaAt(luma, x, yp1, height) << 1) + FrameLumaAt(luma, xp1, yp1, height);
            return (sum + 8) >> 4;
        }

        private int FrameRolledToneLuma(byte[] luma, int x, int y, int height)
        {
            int center = FrameLumaAt(luma, x, y, height);
            int blur = FrameBlur3x3Center(luma, x, y, height);
            int detail = center - blur;
            int detailAbs = AbsI(detail);

            int rolloff = microRolloffLut[center];
            int gate = 256 - Math.Min(196, detailAbs * 9);
            if (gate < 36) gate = 36;
            rolloff = (rolloff * gate + 128) >> 8;
            return Clamp8(center - ((detail * rolloff + 128) >> 8));
        }

        private int FrameFlatnessWeight(byte[] luma, int x, int y, int height)
        {
            int xm1 = x > 0 ? x - 1 : x;
            int xp1 = x + 1 < width ? x + 1 : x;
            int ym1 = y > 0 ? y - 1 : y;
            int yp1 = y + 1 < height ? y + 1 : y;

            int center = FrameLumaAt(luma, x, y, height);
            int gx = AbsI(center - FrameLumaAt(luma, xm1, y, height)) + AbsI(FrameLumaAt(luma, xp1, y, height) - center);
            int gy = AbsI(center - FrameLumaAt(luma, x, ym1, height)) + AbsI(FrameLumaAt(luma, x, yp1, height) - center);
            int diag =
                AbsI(center - FrameLumaAt(luma, xm1, ym1, height)) +
                AbsI(center - FrameLumaAt(luma, xp1, ym1, height)) +
                AbsI(center - FrameLumaAt(luma, xm1, yp1, height)) +
                AbsI(center - FrameLumaAt(luma, xp1, yp1, height));

            int activity = (gx + gy + (diag >> 1) + 2) >> 2;
            int edge = gx + gy + (diag >> 2);

            int weight = 228;
            if (activity < 20) weight += 20 - activity;
            weight -= Math.Min(132, edge + (activity >> 1));
            if (weight < 96) weight = 96;
            if (weight > 272) weight = 272;
            return weight;
        }

        private int FrameTextureLumaBoost(byte[] luma, int x, int y, int height)
        {
            int xm1 = x > 0 ? x - 1 : x;
            int xp1 = x + 1 < width ? x + 1 : x;
            int ym1 = y > 0 ? y - 1 : y;
            int yp1 = y + 1 < height ? y + 1 : y;

            int center = FrameLumaAt(luma, x, y, height);
            int blur = FrameBlur3x3Center(luma, x, y, height);
            int detailAbs = AbsI(center - blur);
            int dx = FrameLumaAt(luma, xp1, y, height) - FrameLumaAt(luma, xm1, y, height);
            int dy = FrameLumaAt(luma, x, yp1, height) - FrameLumaAt(luma, x, ym1, height);
            int ax = AbsI(dx);
            int ay = AbsI(dy);
            int coherence = AbsI(ax - ay);
            int activity =
                ax + ay +
                AbsI(center - FrameLumaAt(luma, xm1, ym1, height)) +
                AbsI(center - FrameLumaAt(luma, xp1, ym1, height)) +
                AbsI(center - FrameLumaAt(luma, xm1, yp1, height)) +
                AbsI(center - FrameLumaAt(luma, xp1, yp1, height));

            int boost = 256;
            if (activity < 16) boost += 112;
            else if (activity < 32) boost += 84;
            else if (activity < 56) boost += 48;

            if (detailAbs < 6) boost += 64;
            else if (detailAbs < 12) boost += 28;

            int textureScore = activity - coherence * 2;
            if (textureScore > 52 && detailAbs < 18) boost += Math.Min(56, textureScore - 52);
            if (coherence > 22) boost -= Math.Min(104, (coherence - 22) * 3);
            if (boost < 216) boost = 216;
            if (boost > 432) boost = 432;
            return boost;
        }

        private int SampleControlByte(byte[] field, int x, int y)
        {
            int shift = controlGrid.Shift;
            int cellSize = 1 << shift;
            int gx = x >> shift;
            int gy = y >> shift;
            int fx = ((x & (cellSize - 1)) << 8) / cellSize;
            int fy = ((y & (cellSize - 1)) << 8) / cellSize;

            int gx1 = gx + 1 < controlGrid.Width ? gx + 1 : gx;
            int gy1 = gy + 1 < controlGrid.Height ? gy + 1 : gy;

            int i00 = gy * controlGrid.Width + gx;
            int i10 = gy * controlGrid.Width + gx1;
            int i01 = gy1 * controlGrid.Width + gx;
            int i11 = gy1 * controlGrid.Width + gx1;
            return Bilerp8(field[i00], field[i10], field[i01], field[i11], fx, fy);
        }

        private int SampleControlByteNearest(byte[] field, int x, int y)
        {
            int gx = x >> controlGrid.Shift;
            int gy = y >> controlGrid.Shift;
            if (gx < 0) gx = 0;
            else if (gx >= controlGrid.Width) gx = controlGrid.Width - 1;
            if (gy < 0) gy = 0;
            else if (gy >= controlGrid.Height) gy = controlGrid.Height - 1;
            return field[gy * controlGrid.Width + gx];
        }

        private int SampleAtlasTemplate(sbyte[] atlas, int x, int y, int ox, int oy)
        {
            return SampleTemplate(atlas, templates.Mask, x + atlasPhase0X + ox, y + atlasPhase0Y + oy);
        }

        private static int LerpI(int a, int b, int t)
        {
            return a + (((b - a) * t + 128) >> 8);
        }

        private int GrainAtlasMix(int x, int y, int densityY, int flatness, int amp, int ox, int oy)
        {
            if (amp <= 0) return 0;

            int sample = SampleAtlasTemplate(templates.AtlasStates[atlasProfileLut[Clamp8(densityY)]], x, y, ox, oy);
            int gate = 126 + ((flatness * 3) >> 3);
            return (sample * gate * amp + (1 << 15)) >> 16;
        }

        private int GrainAtlasProfileSample(int x, int y, int profileIndex, int flatness, int amp, int ox, int oy)
        {
            if (amp <= 0) return 0;
            int sample = SampleAtlasTemplate(templates.AtlasStates[profileIndex], x, y, ox, oy);
            int gate = 126 + ((flatness * 3) >> 3);
            return (sample * gate * amp + (1 << 15)) >> 16;
        }

        private int SelectAtlasProfileFullRes(int densityY, int x, int y, int ox, int oy)
        {
            int d = Clamp8(densityY + 8);
            int lo = atlasProfileLoLut[d];
            int hi = atlasProfileHiLut[d];
            if (lo == hi) return lo;

            int threshold = SampleTemplate(
                templates.Fine,
                templates.Mask,
                x + ox + atlasPhase1X,
                y + oy + atlasPhase1Y) + 128;

            return threshold < atlasProfileMixLut[d] ? hi : lo;
        }

        private void ExpandControlRowFast(int absY)
        {
            if (controlGrid == null) return;

            int sampleY = absY < 0 ? 0 : absY;
            for (int x0 = 0; x0 < width; x0 += 2)
            {
                int edgeMag = SampleControlByte(controlGrid.EdgeMag, x0, sampleY);
                int flat = SampleControlByte(controlGrid.Flatness, x0, sampleY);
                int rolledY = SampleControlByte(controlGrid.RolledY, x0, sampleY);
                int densityY = SampleControlByte(controlGrid.DensityY, x0, sampleY);
                int blurY = SampleControlByte(controlGrid.BlurY, x0, sampleY);
                int textureBoost = SampleControlByte(controlGrid.TextureBoostQ, x0, sampleY) << 1;
                if (textureBoost < 216) textureBoost = 216;
                int sharedOffsetX = SampleControlByte(controlGrid.Offset0X, x0, sampleY);
                int sharedOffsetY = SampleControlByte(controlGrid.Offset0Y, x0, sampleY);

                int surfaceLift = Math.Max(0, 144 - densityY);
                surfaceLift = (surfaceLift * Math.Max(0, flat - 152) + 128) >> 8;
                surfaceLift -= Math.Min(32, edgeMag << 1);
                if (surfaceLift < 0) surfaceLift = 0;
                if (surfaceLift > 56) surfaceLift = 56;

                int openness = 255 - grainDensityLut[Clamp8(densityY)];
                int coherentMid = flat - Math.Min(160, edgeMag * 6);
                if (coherentMid < 0) coherentMid = 0;
                if (coherentMid > 224) coherentMid = 224;
                int suppress = 256 - ((coherentMid * (openness + 32)) >> 8);
                if (suppress < 148) suppress = 148;
                if (suppress > 255) suppress = 255;

                int darkBoost = 0;
                int highlightBoost = 0;
                int darkBase = Math.Max(0, 156 - densityY);
                int sceneExposure = Math.Max(0, blurY - 48);
                darkBoost = ((darkBase * sceneExposure + 128) >> 8) + (Math.Max(0, flat - 168) >> 2) - (edgeMag << 1);
                if (darkBoost < 0) darkBoost = 0;
                if (darkBoost > 72) darkBoost = 72;

                int hiBase = Math.Max(0, densityY - 208);
                highlightBoost = ((hiBase * Math.Max(0, flat - 136) + 128) >> 8) - edgeMag;
                if (highlightBoost < 0) highlightBoost = 0;
                if (highlightBoost > 40) highlightBoost = 40;

                int edgeAtten = 256 - Math.Min(176, edgeMag * 4);
                if (edgeAtten < 80) edgeAtten = 80;
                if (edgeAtten > 255) edgeAtten = 255;

                int x1 = x0 + 1 < width ? x0 + 1 : x0;
                for (int xi = x0; xi <= x1; ++xi)
                {
                    rowCtrlRolledY[xi] = (byte)rolledY;
                    rowCtrlDensityY[xi] = (byte)densityY;
                    rowCtrlFlat[xi] = (byte)flat;
                    rowCtrlTextureQ[xi] = (byte)(textureBoost >> 1);
                    rowCtrlSurfaceLift[xi] = (byte)surfaceLift;
                    rowCtrlSuppress[xi] = (byte)suppress;
                    rowCtrlDarkBoost[xi] = (byte)darkBoost;
                    rowCtrlHighlightBoost[xi] = (byte)highlightBoost;
                    rowCtrlEdgeAtten[xi] = (byte)edgeAtten;
                    rowCtrlProfile[xi] = atlasProfileLut[Clamp8(densityY)];
                    rowCtrlEdgeMag[xi] = (byte)edgeMag;
                    rowCtrlOffsetX[xi] = (byte)sharedOffsetX;
                    rowCtrlOffsetY[xi] = (byte)sharedOffsetY;
                }
            }
        }

        private static void OffsetField(int x, int y, int blockSize, int tplMask, uint seed, uint salt, out int ox, out int oy)
        {
            int bx = x / blockSize;
            int by = y / blockSize;
            int fx = ((x % blockSize) << 8) / blockSize;
            int fy = ((y % blockSize) << 8) / blockSize;

            uint h00 = HashU32((uint)bx, (uint)by, seed ^ salt);
            uint h10 = HashU32((uint)(bx + 1), (uint)by, seed ^ salt);
            uint h01 = HashU32((uint)bx, (uint)(by + 1), seed ^ salt);
            uint h11 = HashU32((uint)(bx + 1), (uint)(by + 1), seed ^ salt);

            int m = tplMask;
            int ox00 = (int)(h00 & (uint)m);
            int oy00 = (int)((h00 >> 8) & (uint)m);
            int ox10 = (int)(h10 & (uint)m);
            int oy10 = (int)((h10 >> 8) & (uint)m);
            int ox01 = (int)(h01 & (uint)m);
            int oy01 = (int)((h01 >> 8) & (uint)m);
            int ox11 = (int)(h11 & (uint)m);
            int oy11 = (int)((h11 >> 8) & (uint)m);

            ox = Bilerp8(ox00, ox10, ox01, ox11, fx, fy);
            oy = Bilerp8(oy00, oy10, oy01, oy11, fx, fy);
        }

        private static int GrainResolutionScale256(int scaleDenom)
        {
            switch (scaleDenom)
            {
                case 2: return 169;
                case 4: return 85;
                default: return 256;
            }
        }

        private int GrainBaseStrength()
        {
            int[] baseValues = { 0, 14, 22, 36, 48, 62 };
            int g = settings.Grain;
            if (g < 0) g = 0;
            if (g > 5) g = 5;
            int baseStrength = baseValues[g];
            if (settings.GrainSize <= 0)
            {
                baseStrength = (baseStrength * 5) >> 2;
            }
            else if (settings.GrainSize == 1)
            {
                baseStrength = (baseStrength * 9) >> 3;
            }
            baseStrength = (baseStrength * GrainResolutionScale256(settings.OutputScaleDenom) + 128) >> 8;
            return baseStrength;
        }

        private int GrainSampleMix(int x, int y, int lumaY, int flatness, int amp)
        {
            if (amp <= 0) return 0;

            int phase0x = (int)(settings.Seed & (uint)templates.Mask);
            int phase0y = (int)((settings.Seed >> 8) & (uint)templates.Mask);
            int phase1x = (int)((settings.Seed >> 16) & (uint)templates.Mask);
            int phase1y = (int)((settings.Seed >> 24) & (uint)templates.Mask);

            int cellA = 2;
            int cellB = 3;
            int cellC = 8;
            int cellD = 15;
            int cellE = 27;
            int cellF = 41;
            int biasA = 120;
            int biasB = 108;
            int wSalt = 34;
            int wA = 126;
            int wB = 96;
            int wC = 38;
            int envStrength = 22;
            int packetStrength = 44;
            int coverageStrength = 26;
            int quietStrength = 18;
            if (settings.GrainSize <= 0)
            {
                cellA = 3;
                cellB = 4;
                cellC = 5;
                cellD = 8;
                cellE = 12;
                cellF = 18;
                biasA = 124;
                biasB = 110;
                wSalt = 92;
                wA = 78;
                wB = 54;
                wC = 84;
                envStrength = 4;
                packetStrength = 8;
                coverageStrength = 4;
                quietStrength = 2;
            }
            else if (settings.GrainSize >= 2)
            {
                cellA = 3;
                cellB = 4;
                cellC = 9;
                cellD = 17;
                cellE = 31;
                cellF = 47;
                biasA = 116;
                biasB = 104;
                wSalt = 32;
                wA = 120;
                wB = 108;
                wC = 46;
                envStrength = 28;
                packetStrength = 52;
                coverageStrength = 34;
                quietStrength = 22;
            }
            else
            {
                cellA = 2;
                cellB = 4;
                cellC = 8;
                cellD = 15;
                cellE = 25;
                cellF = 39;
                biasA = 120;
                biasB = 106;
                wSalt = 28;
                wA = 136;
                wB = 104;
                wC = 40;
                envStrength = 22;
                packetStrength = 44;
                coverageStrength = 24;
                quietStrength = 16;
            }

            int salt = SignedHash8(x + phase0x, y + phase0y, settings.Seed ^ 0x6C8E9CF5u);
            int fineTpl = SampleTemplate(templates.Fine, templates.Mask, x + phase0x, y + phase0y);
            int mediumTpl = SampleTemplate(templates.Medium, templates.Mask, x + phase1x, y + phase1y);
            int cloudA = GrainPacketField(x + phase1x, y + phase1y, cellA, settings.Seed ^ 0x91E10DA5u, biasA);
            int cloudB = GrainPacketField(x + phase0y, y + phase1x, cellB, settings.Seed ^ 0xC79E7B1Du, biasB);
            int cloudC = ValueNoise(x - phase1y, y + phase0x, cellC, settings.Seed ^ 0xE19B01AAu);
            int envelopeNoise = ValueNoise(x + phase0x + 11, y + phase1y + 7, cellD, settings.Seed ^ 0xD1B54A35u);
            int coverageNoise = ValueNoise(x - phase0y - 17, y + phase1x + 29, cellE, settings.Seed ^ 0x94D049BBu);
            int quietNoise = ValueNoise(x + phase1y + 31, y - phase0x - 13, cellF, settings.Seed ^ 0x5A12CF37u);
            int dotNoiseA = ValueNoise(x + phase1x + 5, y - phase0y - 9, 3, settings.Seed ^ 0x7A4F9E11u);
            int dotNoiseB = SignedHash8(x + phase0y + 7, y + phase1x + 13, settings.Seed ^ 0x3C6EF372u);

            int mix = (salt * wSalt + cloudA * wA + cloudB * wB + cloudC * wC) >> 8;
            if (settings.GrainSize <= 0)
            {
                mix = (salt * 48 + fineTpl * 116 + mediumTpl * 54 + cloudC * 38 + cloudA * 20 + cloudB * 12) >> 8;
            }
            else if (settings.GrainSize >= 2)
            {
                int density = grainDensityLut[Clamp8(lumaY)];
                int openness = 255 - density;
                int dotSizeA = 2 + ((openness + 128) >> 8);
                int dotSizeB = 2 + ((openness + 96) >> 7);
                int dotDenseA = GrainDotField(x + phase1x, y + phase0y, dotSizeA, settings.Seed ^ 0xA24BAED4u);
                int dotDenseB = GrainDotField(x - phase1y, y + phase0x, dotSizeB, settings.Seed ^ 0x9FB21C53u);
                mix = (salt * 15 + dotDenseA * (58 + (openness >> 2)) + dotDenseB * (44 + (openness >> 2)) + cloudC * 20 + cloudA * 10 + cloudB * 7) >> 8;
            }

            int envelope = 256 + ((envelopeNoise * envStrength) >> 7);
            int packet = 208 + ((Math.Max(0, AbsI(envelopeNoise) - 20) * packetStrength) >> 6);
            if (packet > 304) packet = 304;
            envelope = (envelope * packet + 128) >> 8;

            int coverage = 232 + ((coverageNoise * coverageStrength) >> 7);
            coverage += (Math.Max(0, AbsI(coverageNoise) - 34) * coverageStrength) >> 5;
            coverage -= (Math.Max(0, 36 - AbsI(quietNoise)) * quietStrength) >> 2;
            if (settings.GrainSize <= 0)
            {
                if (coverage < 220) coverage = 220;
                if (coverage > 292) coverage = 292;
            }
            else
            {
                if (coverage < 200) coverage = 200;
                if (coverage > 312) coverage = 312;
            }
            if (settings.GrainSize >= 2)
            {
                int density = grainDensityLut[Clamp8(lumaY)];
                coverage += (density - 128) >> 2;
            }
            envelope = (envelope * coverage + 128) >> 8;

            if (settings.GrainSize <= 0)
            {
                if (envelope < 214) envelope = 214;
                if (envelope > 286) envelope = 286;
            }
            else
            {
                if (envelope < 192) envelope = 192;
                if (envelope > 296) envelope = 296;
            }
            if (settings.GrainSize >= 2)
            {
                int density = grainDensityLut[Clamp8(lumaY)];
                int contrastBias = 136 + (((255 - density) * 104) >> 8);
                mix = (mix * contrastBias) >> 8;
            }
            mix = (mix * envelope) >> 8;
            if (settings.GrainSize <= 0)
            {
                int dotCarrier = 200;
                dotCarrier += (Math.Max(0, AbsI(dotNoiseA) - 18) * 5) >> 1;
                dotCarrier += (Math.Max(0, AbsI(dotNoiseB) - 44) * 3) >> 1;
                if (dotCarrier < 196) dotCarrier = 196;
                if (dotCarrier > 344) dotCarrier = 344;
                mix = (mix * dotCarrier) >> 8;
            }

            int gate = 112 + ((flatness * 3) >> 3);
            return (mix * gate * amp + (1 << 15)) >> 16;
        }

        private int GrainSampleMixLite(int x, int y, int lumaY, int flatness, int amp)
        {
            if (amp <= 0) return 0;

            int phase0x = (int)(settings.Seed & (uint)templates.Mask);
            int phase0y = (int)((settings.Seed >> 8) & (uint)templates.Mask);
            int phase1x = (int)((settings.Seed >> 16) & (uint)templates.Mask);
            int phase1y = (int)((settings.Seed >> 24) & (uint)templates.Mask);

            int salt = SignedHash8(x + phase0x, y + phase0y, settings.Seed ^ 0x6C8E9CF5u);
            int fineTpl = SampleTemplate(templates.Fine, templates.Mask, x + phase0x, y + phase0y);
            int mediumTpl = SampleTemplate(templates.Medium, templates.Mask, x + phase1x, y + phase1y);
            int coarseTpl = SampleTemplate(templates.Coarse, templates.Mask, x + phase0x + 11, y + phase1y + 7);
            int coarseTpl2 = SampleTemplate(templates.Coarse, templates.Mask, x - phase0y - 17, y + phase1x + 29);
            int pointTpl = SampleTemplate(templates.Point, templates.Mask, x + phase1x, y + phase0y);

            int density = grainDensityLut[Clamp8(lumaY)];
            int openness = 255 - density;
            int mix;
            int envelope;
            if (settings.GrainSize <= 0)
            {
                mix = (salt * 38 + fineTpl * 126 + mediumTpl * 52 + coarseTpl * 20) >> 8;
                envelope = 244 + ((coarseTpl * 10) >> 7) + ((coarseTpl2 * 6) >> 7);
                if (envelope < 220) envelope = 220;
                if (envelope > 292) envelope = 292;
            }
            else if (settings.GrainSize >= 2)
            {
                mix = (salt * 16 + pointTpl * (112 + (openness >> 3)) + fineTpl * 88 + mediumTpl * 44 + coarseTpl * 10) >> 8;
                envelope = 238 + ((coarseTpl * 10) >> 7) + ((coarseTpl2 * 8) >> 7);
                envelope += (density - 128) >> 3;
                if (envelope < 218) envelope = 218;
                if (envelope > 286) envelope = 286;
                int contrastBias = 138 + ((openness * 80) >> 8);
                mix = (mix * contrastBias) >> 8;
            }
            else
            {
                mix = (salt * 20 + pointTpl * 116 + fineTpl * 82 + mediumTpl * 48 + coarseTpl * 18) >> 8;
                envelope = 238 + ((coarseTpl * 18) >> 7) + ((coarseTpl2 * 12) >> 7);
                if (envelope < 206) envelope = 206;
                if (envelope > 300) envelope = 300;
            }

            mix = (mix * envelope) >> 8;
            int gate = 112 + ((flatness * 3) >> 3);
            return (mix * gate * amp + (1 << 15)) >> 16;
        }

        private int GrainSampleMixCheap(int x, int y, int lumaY, int flatness, int amp)
        {
            if (amp <= 0) return 0;

            int phase0x = (int)(settings.Seed & (uint)templates.Mask);
            int phase0y = (int)((settings.Seed >> 8) & (uint)templates.Mask);
            int phase1x = (int)((settings.Seed >> 16) & (uint)templates.Mask);

            int salt = SignedHash8(x + phase0x, y + phase0y, settings.Seed ^ 0x6C8E9CF5u);
            int fineTpl = SampleTemplate(templates.Fine, templates.Mask, x + phase0x, y + phase0y);
            int pointTpl = SampleTemplate(templates.Point, templates.Mask, x + phase1x, y + phase0y);
            int mediumTpl = SampleTemplate(templates.Medium, templates.Mask, x + phase1x, y + phase0y + 9);
            int density = grainDensityLut[Clamp8(lumaY)];
            int openness = 255 - density;

            int mix = (salt * 20 + fineTpl * 90 + mediumTpl * 28 + pointTpl * (86 + (openness >> 4))) >> 8;
            int envelope = 240 + ((SampleTemplate(templates.Coarse, templates.Mask, x + phase0x + 11, y + phase0y + 7) * 6) >> 7);
            if (envelope < 226) envelope = 226;
            if (envelope > 274) envelope = 274;
            mix = (mix * envelope) >> 8;

            int gate = 120 + ((flatness * 5) >> 4);
            return (mix * gate * amp + (1 << 15)) >> 16;
        }

        private int CarrierMode(int densityY, int flatness, int edgeMag, int amp)
        {
            if (settings.GrainSize < 2) return 0;
            if (amp < 8) return 2;
            if (densityY >= 100 && densityY <= 172 && flatness >= 208 && edgeMag <= 10 && amp >= 22) return 0;
            if (densityY >= 68 && densityY <= 204 && flatness >= 148 && edgeMag <= 24 && amp >= 12) return 1;
            return 2;
        }

        private int ApplyDensityGrainY(int baseY, int grainTerm)
        {
            int density = densityEncodeLut[Clamp8(baseY)];
            int densityDelta = -grainTerm;
            if (densityDelta > 0) density += (densityDelta * 7) >> 2;
            else density += (densityDelta * 3) >> 2;
            if (density < 0) density = 0;
            if (density >= densityDecodeLut.Length) density = densityDecodeLut.Length - 1;
            return densityDecodeLut[density];
        }

        private int FormedNeighborY(int sampleX, int sampleY, int baseY, int amp, int flatness)
        {
            int sampleAmp = (amp * (188 + (flatness >> 2)) + 128) >> 8;
            if (sampleAmp < 0) sampleAmp = 0;
            int sampleFlat = flatness + 20;
            if (sampleFlat > 288) sampleFlat = 288;
            int sampleTerm = GrainSampleMix(sampleX, sampleY, baseY, sampleFlat, sampleAmp);
            return ApplyDensityGrainY(baseY, sampleTerm);
        }

        private int StochasticReconstructY(byte[] prevRow, byte[] currRow, byte[] nextRow, int x, int y, int rawAmp, int flatness, int currentY)
        {
            if (rawAmp <= 0) return currentY;

            int xm1 = x > 0 ? x - 1 : x;
            int xp1 = x + 1 < width ? x + 1 : x;
            int dx = AbsI(currRow[xp1] - currRow[xm1]);
            int dy = AbsI(nextRow[x] - prevRow[x]);
            int coherence = AbsI(dx - dy);

            int pull = 10 + (rawAmp >> 1);
            pull += Math.Min(20, coherence >> 1);
            pull += Math.Max(0, flatness - 196) >> 4;
            pull -= Math.Max(0, 168 - flatness) >> 3;
            if (pull < 0) pull = 0;
            if (pull > 92) pull = 92;
            if (pull < 8) return currentY;

            int selector = SignedHash8(x + 57, y + 91, settings.Seed ^ 0x72C3A51Du);
            int chosenX = selector < 0 ? xm1 : xp1;
            int chosenY = y;
            int chosen = selector < 0 ? currRow[xm1] : currRow[xp1];
            if (dy > dx + 4 || (coherence < 10 && SignedHash8(x + 11, y + 149, settings.Seed ^ 0x19E377A1u) < 0))
            {
                chosenX = x;
                chosenY = selector < 0 ? y - 1 : y + 1;
                chosen = selector < 0 ? prevRow[x] : nextRow[x];
            }

            int formedChosen = FormedNeighborY(chosenX, chosenY, chosen, rawAmp, flatness);
            return Clamp8((currentY * (256 - pull) + formedChosen * pull + 128) >> 8);
        }

        private int EdgeFuzzBlend(byte[] prevRow, byte[] currRow, byte[] nextRow, int x, int y, int amp, int flatness, int currentY)
        {
            if (amp <= 0) return currentY;

            int xm1 = x > 0 ? x - 1 : x;
            int xp1 = x + 1 < width ? x + 1 : x;

            int dx = currRow[xp1] - currRow[xm1];
            int dy = nextRow[x] - prevRow[x];
            int ax = AbsI(dx);
            int ay = AbsI(dy);
            int edgeMag = Math.Max(ax, ay);
            int coherence = edgeMag - Math.Min(ax, ay);
            if (edgeMag < 14 || coherence < 6) return currentY;

            int selector = SignedHash8(x + 19, y + 73, settings.Seed ^ 0x3F84D5B5u);
            int chosenX = selector < 0 ? xm1 : xp1;
            int chosenY = y;
            int chosen = selector < 0 ? currRow[xm1] : currRow[xp1];
            if (ay > ax)
            {
                chosenX = x;
                chosenY = selector < 0 ? y - 1 : y + 1;
                chosen = selector < 0 ? prevRow[x] : nextRow[x];
            }

            int neighborAmp = (amp * (176 + (flatness >> 1)) + 128) >> 8;
            if (neighborAmp < 0) neighborAmp = 0;
            int neighborFlat = Math.Min(288, flatness + 18);
            int neighborTerm = GrainSampleMix(chosenX, chosenY, chosen, neighborFlat, neighborAmp);
            chosen = ApplyDensityGrainY(chosen, neighborTerm);

            int blend = (((edgeMag << 1) + (coherence * 3)) * amp + 256) >> 9;
            if (blend > 104) blend = 104;
            if (blend < 6) return currentY;
            return (currentY * (256 - blend) + chosen * blend + 128) >> 8;
        }

        private void ProcessRowCore(PixelRGB[] currRowRgb, byte[] prevRowY, byte[] currRowY, byte[] nextRowY, int absY, PixelRGB[] output)
        {
            int baseStrength = GrainBaseStrength();
            if (baseStrength <= 0)
            {
                Array.Copy(currRowRgb, output, width);
                return;
            }

            if (settings.GrainSize >= 2 && controlGrid != null)
            {
                ExpandControlRowFast(absY);
            }

            for (int x = 0; x < width; ++x)
            {
                PixelRGB input = currRowRgb[x];
                int centerRawY = currRowY[x];
                int xm1 = x > 0 ? x - 1 : x;
                int xp1 = x + 1 < width ? x + 1 : x;
                int dx = currRowY[xp1] - currRowY[xm1];
                int dy = nextRowY[x] - prevRowY[x];
                int edgeMag = Math.Max(AbsI(dx), AbsI(dy));
                int flat = FlatnessWeight(prevRowY, currRowY, nextRowY, x);
                int rolledY = RolledToneLuma(prevRowY, currRowY, nextRowY, x);
                int densityY = (centerRawY + rolledY + (Blur3x3Center(prevRowY, currRowY, nextRowY, x) << 1) + 2) >> 2;
                int textureBoost = TextureLumaBoost(prevRowY, currRowY, nextRowY, x);
                int surfaceLift = 0;
                int lateDarkSurfaceBoost = 0;
                int lateHighlightBoost = 0;
                int suppress = 255;
                int profileIndex = atlasProfileLut[Clamp8(densityY)];
                int offsetX = 0;
                int offsetY = 0;
                if (settings.GrainSize >= 2 && controlGrid != null)
                {
                    edgeMag = rowCtrlEdgeMag[x];
                    flat = rowCtrlFlat[x];
                    rolledY = rowCtrlRolledY[x];
                    densityY = rowCtrlDensityY[x];
                    textureBoost = rowCtrlTextureQ[x] << 1;
                    surfaceLift = rowCtrlSurfaceLift[x];
                    suppress = rowCtrlSuppress[x];
                    lateDarkSurfaceBoost = rowCtrlDarkBoost[x];
                    lateHighlightBoost = rowCtrlHighlightBoost[x];
                    profileIndex = rowCtrlProfile[x];
                    offsetX = rowCtrlOffsetX[x];
                    offsetY = rowCtrlOffsetY[x];
                }
                if (settings.GrainSize >= 2 && settings.OutputScaleDenom == 1)
                {
                    profileIndex = SelectAtlasProfileFullRes(densityY, x, absY, offsetX, offsetY);
                }
                int amountY = centerRawY;
                if (settings.GrainSize >= 2)
                {
                    amountY += surfaceLift;
                    if (amountY > 255) amountY = 255;
                }
                int rawAmp = (baseStrength * grainAmountLut[amountY] + 128) >> 8;
                int amp = rawAmp;
                int edgeAtten = settings.GrainSize >= 2 && controlGrid != null ? rowCtrlEdgeAtten[x] : Math.Max(80, 256 - Math.Min(176, edgeMag * 4));
                amp = (amp * edgeAtten + 128) >> 8;
                amp = (amp * textureBoost + 128) >> 8;
                int formationBlend = (amp * (flat + 96) + 128) >> 8;
                if (edgeMag > 10) formationBlend -= Math.Min(48, (edgeMag - 10) * 3);
                if (settings.GrainSize <= 0) formationBlend += 12;
                if (formationBlend < 0) formationBlend = 0;
                if (formationBlend > 108) formationBlend = 108;
                int formedBaseY = (centerRawY * (256 - formationBlend) + rolledY * formationBlend + 128) >> 8;

                if (settings.GrainSize >= 2)
                {
                    amp = (amp * suppress + 128) >> 8;
                }

                if (settings.OutputScaleDenom > 1 && settings.GrainSize >= 2)
                {
                    int preserve = lateDarkSurfaceBoost + (lateHighlightBoost >> 1);
                    if (preserve > 112) preserve = 112;
                    int floorScale = settings.OutputScaleDenom == 2 ? 192 : 136;
                    int floorAmp = (rawAmp * preserve * floorScale + 32768) >> 16;
                    if (floorAmp > amp) amp = floorAmp;
                }

                int grainTerm = GrainAtlasProfileSample(x, absY, profileIndex, flat, amp, offsetX, offsetY);
                if (lateDarkSurfaceBoost > 0)
                {
                    grainTerm += (grainTerm * lateDarkSurfaceBoost + 128) >> 8;
                }

                int targetY = ApplyDensityGrainY(formedBaseY, grainTerm);
                int softBlend = 8 + (amp >> 4) + Math.Max(0, flat - 160) / 32;
                if (softBlend > 20) softBlend = 20;
                targetY = (targetY * (256 - softBlend) + formedBaseY * softBlend + 128) >> 8;
                int residual = (grainTerm * 7) >> 4;
                if (residual < -24) residual = -24;
                if (residual > 24) residual = 24;
                if (lateDarkSurfaceBoost > 0)
                {
                    int darkResidual = (grainTerm * lateDarkSurfaceBoost) >> 8;
                    if (darkResidual < -14) darkResidual = -14;
                    if (darkResidual > 14) darkResidual = 14;
                    residual += darkResidual;
                }
                targetY += residual;
                targetY = Clamp8(targetY);
                PixelRGB outPx = RebuildRgbFromLumaRatio(input, targetY);

                if (settings.EnableChromaGrain)
                {
                    int phaseCx = (int)((settings.Seed ^ 0x55AA3311u) & (uint)templates.Mask);
                    int phaseCy = (int)(((settings.Seed ^ 0x55AA3311u) >> 8) & (uint)templates.Mask);
                    int cg = SampleTemplate(templates.Chroma, templates.Mask, x + phaseCx, absY + phaseCy);
                    int cAmp = Math.Max(0, (amp * Math.Min(flat, 256)) >> 12);
                    int jitter = (cg * cAmp) >> 8;
                    outPx.R = (byte)Clamp8(outPx.R + jitter);
                    outPx.B = (byte)Clamp8(outPx.B - (jitter >> 1));
                }

                output[x] = outPx;
            }
        }
    }

    public static class BatchRenderer
    {
        public static void RenderFolder(string inputDir, string outputDir, GrainSettings settings)
        {
            Directory.CreateDirectory(outputDir);
            foreach (string path in Directory.GetFiles(inputDir)
                .Where(p => p.EndsWith(".jpg", StringComparison.OrdinalIgnoreCase) || p.EndsWith(".jpeg", StringComparison.OrdinalIgnoreCase))
                .OrderBy(p => p, StringComparer.OrdinalIgnoreCase))
            {
                RenderSingle(path, outputDir, settings);
            }
        }

        public static void RenderSingle(string inputPath, string outputDir, GrainSettings settings)
        {
            int width, height;
            PixelRGB[] original = LoadPixels(inputPath, out width, out height);
            if (settings.OutputScaleDenom > 1)
            {
                int scaledWidth = Math.Max(1, width / settings.OutputScaleDenom);
                int scaledHeight = Math.Max(1, height / settings.OutputScaleDenom);
                original = ResizePixels(original, width, height, scaledWidth, scaledHeight);
                width = scaledWidth;
                height = scaledHeight;
            }
            PixelRGB[] processed = new PixelRGB[original.Length];
            Array.Copy(original, processed, original.Length);
            StreamingFilmGrainEngine.ProcessFrame(processed, width, height, settings);

            string stem = Path.GetFileNameWithoutExtension(inputPath);
            string baseName = string.Format("{0}_v4_g{1}", stem, settings.Grain);

            SaveJpeg(processed, width, height, Path.Combine(outputDir, baseName + ".jpg"), 95L);

            using (Bitmap side = BuildSideBySide(original, processed, width, height, 2048))
            {
                side.Save(Path.Combine(outputDir, baseName + "_side.jpg"), GetJpegEncoder(), BuildEncoderParams(92L));
            }

            using (Bitmap crops = BuildCropSheet(original, processed, width, height))
            {
                crops.Save(Path.Combine(outputDir, baseName + "_crops.jpg"), GetJpegEncoder(), BuildEncoderParams(95L));
            }
        }

        private static PixelRGB[] LoadPixels(string path, out int width, out int height)
        {
            using (var source = new Bitmap(path))
            using (var bitmap = Ensure24bpp(source))
            {
                width = bitmap.Width;
                height = bitmap.Height;
                var pixels = new PixelRGB[width * height];

                Rectangle rect = new Rectangle(0, 0, width, height);
                BitmapData data = bitmap.LockBits(rect, ImageLockMode.ReadOnly, PixelFormat.Format24bppRgb);
                try
                {
                    int stride = data.Stride;
                    byte[] raw = new byte[stride * height];
                    Marshal.Copy(data.Scan0, raw, 0, raw.Length);
                    int dst = 0;
                    for (int y = 0; y < height; ++y)
                    {
                        int row = y * stride;
                        for (int x = 0; x < width; ++x)
                        {
                            int src = row + x * 3;
                            pixels[dst].B = raw[src + 0];
                            pixels[dst].G = raw[src + 1];
                            pixels[dst].R = raw[src + 2];
                            ++dst;
                        }
                    }
                }
                finally
                {
                    bitmap.UnlockBits(data);
                }

                return pixels;
            }
        }

        private static void SaveJpeg(PixelRGB[] pixels, int width, int height, string path, long quality)
        {
            using (Bitmap bitmap = PixelsToBitmap(pixels, width, height))
            {
                bitmap.Save(path, GetJpegEncoder(), BuildEncoderParams(quality));
            }
        }

        private static PixelRGB[] ResizePixels(PixelRGB[] source, int sourceWidth, int sourceHeight, int targetWidth, int targetHeight)
        {
            using (Bitmap sourceBitmap = PixelsToBitmap(source, sourceWidth, sourceHeight))
            using (Bitmap resized = new Bitmap(targetWidth, targetHeight, PixelFormat.Format24bppRgb))
            using (Graphics g = Graphics.FromImage(resized))
            {
                g.Clear(Color.Black);
                g.InterpolationMode = InterpolationMode.HighQualityBicubic;
                g.PixelOffsetMode = PixelOffsetMode.HighQuality;
                g.DrawImage(sourceBitmap, new Rectangle(0, 0, targetWidth, targetHeight));

                var pixels = new PixelRGB[targetWidth * targetHeight];
                Rectangle rect = new Rectangle(0, 0, targetWidth, targetHeight);
                BitmapData data = resized.LockBits(rect, ImageLockMode.ReadOnly, PixelFormat.Format24bppRgb);
                try
                {
                    int stride = data.Stride;
                    byte[] raw = new byte[stride * targetHeight];
                    Marshal.Copy(data.Scan0, raw, 0, raw.Length);
                    int dst = 0;
                    for (int y = 0; y < targetHeight; ++y)
                    {
                        int row = y * stride;
                        for (int x = 0; x < targetWidth; ++x)
                        {
                            int src = row + x * 3;
                            pixels[dst].B = raw[src + 0];
                            pixels[dst].G = raw[src + 1];
                            pixels[dst].R = raw[src + 2];
                            ++dst;
                        }
                    }
                }
                finally
                {
                    resized.UnlockBits(data);
                }

                return pixels;
            }
        }

        private static Bitmap PixelsToBitmap(PixelRGB[] pixels, int width, int height)
        {
            var bitmap = new Bitmap(width, height, PixelFormat.Format24bppRgb);
            Rectangle rect = new Rectangle(0, 0, width, height);
            BitmapData data = bitmap.LockBits(rect, ImageLockMode.WriteOnly, PixelFormat.Format24bppRgb);
            try
            {
                int stride = data.Stride;
                byte[] raw = new byte[stride * height];
                int src = 0;
                for (int y = 0; y < height; ++y)
                {
                    int row = y * stride;
                    for (int x = 0; x < width; ++x)
                    {
                        int dst = row + x * 3;
                        raw[dst + 0] = pixels[src].B;
                        raw[dst + 1] = pixels[src].G;
                        raw[dst + 2] = pixels[src].R;
                        ++src;
                    }
                }
                Marshal.Copy(raw, 0, data.Scan0, raw.Length);
            }
            finally
            {
                bitmap.UnlockBits(data);
            }
            return bitmap;
        }

        private static Bitmap Ensure24bpp(Bitmap source)
        {
            if (source.PixelFormat == PixelFormat.Format24bppRgb)
            {
                return (Bitmap)source.Clone();
            }

            var converted = new Bitmap(source.Width, source.Height, PixelFormat.Format24bppRgb);
            using (Graphics g = Graphics.FromImage(converted))
            {
                g.InterpolationMode = InterpolationMode.HighQualityBicubic;
                g.DrawImage(source, 0, 0, source.Width, source.Height);
            }
            return converted;
        }

        private static Bitmap BuildSideBySide(PixelRGB[] left, PixelRGB[] right, int width, int height, int maxWidth)
        {
            using (Bitmap leftBmp = PixelsToBitmap(left, width, height))
            using (Bitmap rightBmp = PixelsToBitmap(right, width, height))
            {
                int targetWidth = Math.Min(maxWidth, width * 2);
                int targetHeight = (int)Math.Round(height * (targetWidth / (double)(width * 2)));
                if (targetHeight < 1) targetHeight = 1;

                Bitmap canvas = new Bitmap(targetWidth, targetHeight, PixelFormat.Format24bppRgb);
                using (Graphics g = Graphics.FromImage(canvas))
                {
                    g.Clear(Color.White);
                    g.InterpolationMode = InterpolationMode.HighQualityBicubic;
                    g.PixelOffsetMode = PixelOffsetMode.HighQuality;
                    int halfWidth = targetWidth / 2;
                    g.DrawImage(leftBmp, new Rectangle(0, 0, halfWidth, targetHeight));
                    g.DrawImage(rightBmp, new Rectangle(halfWidth, 0, targetWidth - halfWidth, targetHeight));
                }
                return canvas;
            }
        }

        private static Bitmap BuildCropSheet(PixelRGB[] original, PixelRGB[] processed, int width, int height)
        {
            const int crop = 512;
            const int gutter = 18;
            Point[] points = PickCropPoints(original, width, height, crop);

            Bitmap canvas = new Bitmap(crop * 2 + gutter * 3, crop * points.Length + gutter * (points.Length + 1), PixelFormat.Format24bppRgb);
            using (Graphics g = Graphics.FromImage(canvas))
            using (Bitmap origBmp = PixelsToBitmap(original, width, height))
            using (Bitmap procBmp = PixelsToBitmap(processed, width, height))
            {
                g.Clear(Color.White);
                g.InterpolationMode = InterpolationMode.NearestNeighbor;
                g.PixelOffsetMode = PixelOffsetMode.Half;

                for (int i = 0; i < points.Length; ++i)
                {
                    Rectangle src = new Rectangle(points[i].X, points[i].Y, crop, crop);
                    int y = gutter + i * (crop + gutter);
                    g.DrawImage(origBmp, new Rectangle(gutter, y, crop, crop), src, GraphicsUnit.Pixel);
                    g.DrawImage(procBmp, new Rectangle(gutter * 2 + crop, y, crop, crop), src, GraphicsUnit.Pixel);
                }
            }

            return canvas;
        }

        private static Point[] PickCropPoints(PixelRGB[] pixels, int width, int height, int crop)
        {
            int marginX = Math.Max(0, width - crop);
            int marginY = Math.Max(0, height - crop);
            int stepX = Math.Max(crop / 3, 128);
            int stepY = Math.Max(crop / 3, 128);

            Point flatMid = new Point(marginX / 2, marginY / 2);
            Point brightFlat = flatMid;
            Point shadowTexture = flatMid;

            double bestFlatMid = double.MaxValue;
            double bestBrightFlat = double.MaxValue;
            double bestShadowTexture = double.MaxValue;

            for (int y = 0; y <= marginY; y += stepY)
            {
                for (int x = 0; x <= marginX; x += stepX)
                {
                    SampleMetrics metrics = MeasureCrop(pixels, width, height, x, y, crop);

                    double flatMidScore = Math.Abs(metrics.AverageLuma - 118.0) * 1.8 + metrics.Activity * 2.8;
                    if (flatMidScore < bestFlatMid)
                    {
                        bestFlatMid = flatMidScore;
                        flatMid = new Point(x, y);
                    }

                    double brightFlatScore = Math.Abs(metrics.AverageLuma - 205.0) * 1.6 + metrics.Activity * 2.4;
                    if (brightFlatScore < bestBrightFlat)
                    {
                        bestBrightFlat = brightFlatScore;
                        brightFlat = new Point(x, y);
                    }

                    double shadowTextureScore = Math.Abs(metrics.AverageLuma - 92.0) * 1.4 + Math.Abs(metrics.Activity - 22.0) * 4.0;
                    if (shadowTextureScore < bestShadowTexture)
                    {
                        bestShadowTexture = shadowTextureScore;
                        shadowTexture = new Point(x, y);
                    }
                }
            }

            return new[] { flatMid, brightFlat, shadowTexture };
        }

        private struct SampleMetrics
        {
            public double AverageLuma;
            public double Activity;
        }

        private static SampleMetrics MeasureCrop(PixelRGB[] pixels, int width, int height, int x0, int y0, int crop)
        {
            int x1 = Math.Min(width - 1, x0 + crop - 1);
            int y1 = Math.Min(height - 1, y0 + crop - 1);
            int sampleStep = Math.Max(8, crop / 24);

            double lumaSum = 0.0;
            double activitySum = 0.0;
            int count = 0;

            for (int y = y0; y < y1; y += sampleStep)
            {
                for (int x = x0; x < x1; x += sampleStep)
                {
                    int idx = y * width + x;
                    int luma = (pixels[idx].R * 77 + pixels[idx].G * 150 + pixels[idx].B * 29) >> 8;
                    int xr = Math.Min(x1, x + sampleStep);
                    int yd = Math.Min(y1, y + sampleStep);
                    int idxR = y * width + xr;
                    int idxD = yd * width + x;
                    int lumaR = (pixels[idxR].R * 77 + pixels[idxR].G * 150 + pixels[idxR].B * 29) >> 8;
                    int lumaD = (pixels[idxD].R * 77 + pixels[idxD].G * 150 + pixels[idxD].B * 29) >> 8;
                    lumaSum += luma;
                    activitySum += Math.Abs(luma - lumaR) + Math.Abs(luma - lumaD);
                    ++count;
                }
            }

            if (count == 0) count = 1;
            return new SampleMetrics
            {
                AverageLuma = lumaSum / count,
                Activity = activitySum / count
            };
        }

        private static ImageCodecInfo GetJpegEncoder()
        {
            return ImageCodecInfo.GetImageEncoders().First(codec => codec.MimeType == "image/jpeg");
        }

        private static EncoderParameters BuildEncoderParams(long quality)
        {
            var encoderParams = new EncoderParameters(1);
            encoderParams.Param[0] = new EncoderParameter(Encoder.Quality, quality);
            return encoderParams;
        }
    }
}
