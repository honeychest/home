import { describe, expect, it } from 'vitest';
import { mergeGapRanges } from './utils.js';

describe('mergeGapRanges', () => {
    it('merges adjacent and overlapping ranges only', () => {
        const rows = [
            { gap_start_ms: 180_000, gap_end_ms: 240_000 },
            { gap_start_ms: 0, gap_end_ms: 60_000 },
            { gap_start_ms: 60_000, gap_end_ms: 180_000 },
            { gap_start_ms: 300_000, gap_end_ms: 360_000 },
        ];

        expect(mergeGapRanges(rows)).toEqual([
            { start: 0, end: 240_000 },
            { start: 300_000, end: 360_000 },
        ]);
    });

    it('rejects invalid or unaligned ranges without changing input', () => {
        const rows = [
            { gap_start_ms: 1, gap_end_ms: 60_001 },
            { gap_start_ms: 120_000, gap_end_ms: 60_000 },
            { gap_start_ms: 240_000, gap_end_ms: 300_000 },
        ];

        expect(mergeGapRanges(rows)).toEqual([{ start: 240_000, end: 300_000 }]);
        expect(rows).toEqual([
            { gap_start_ms: 1, gap_end_ms: 60_001 },
            { gap_start_ms: 120_000, gap_end_ms: 60_000 },
            { gap_start_ms: 240_000, gap_end_ms: 300_000 },
        ]);
    });
});
