/*
 * libdualiso — optimised small-array median networks.
 * Licensed under the GNU General Public License v2 or later.
 *
 * Original:
 *   N. Devillard — 1998. Authored as public-domain code, embedded
 *   inside cr2hdr's optmed.h. We carry it forward under GPL-2 because
 *   it's only meaningful in the context of cr2hdr's blend pipeline;
 *   the original public-domain attribution is retained here.
 *
 * Three flavours used by chroma_smooth:
 *   opt_med5   — for the 2x2 chroma_smooth pattern (5 samples)
 *   opt_med9   — for the 3x3 chroma_smooth pattern (9 samples)
 *   opt_med25  — for the 5x5 chroma_smooth pattern (25 samples)
 *
 * All operate in place on the input array via a sorting network.
 */

#pragma once

namespace dualiso::lib {

namespace optmed_detail {
inline void pix_sort(int& a, int& b) noexcept {
    if (a > b) { const int t = a; a = b; b = t; }
}
} // optmed_detail

/** Median of 5 ints. Mutates the input array. ~7 compares. */
inline int opt_med5(int* p) noexcept {
    using optmed_detail::pix_sort;
    pix_sort(p[0], p[1]); pix_sort(p[3], p[4]); pix_sort(p[0], p[3]);
    pix_sort(p[1], p[4]); pix_sort(p[1], p[2]); pix_sort(p[2], p[3]);
    pix_sort(p[1], p[2]);
    return p[2];
}

/** Median of 9 ints. Mutates the input array. ~19 compares. */
inline int opt_med9(int* p) noexcept {
    using optmed_detail::pix_sort;
    pix_sort(p[1], p[2]); pix_sort(p[4], p[5]); pix_sort(p[7], p[8]);
    pix_sort(p[0], p[1]); pix_sort(p[3], p[4]); pix_sort(p[6], p[7]);
    pix_sort(p[1], p[2]); pix_sort(p[4], p[5]); pix_sort(p[7], p[8]);
    pix_sort(p[0], p[3]); pix_sort(p[5], p[8]); pix_sort(p[4], p[7]);
    pix_sort(p[3], p[6]); pix_sort(p[1], p[4]); pix_sort(p[2], p[5]);
    pix_sort(p[4], p[7]); pix_sort(p[4], p[2]); pix_sort(p[6], p[4]);
    pix_sort(p[4], p[2]);
    return p[4];
}

/** Median of 25 ints. Mutates the input array. ~99 compares. */
inline int opt_med25(int* p) noexcept {
    using optmed_detail::pix_sort;
    pix_sort(p[0], p[1]);   pix_sort(p[3], p[4]);   pix_sort(p[2], p[4]);
    pix_sort(p[2], p[3]);   pix_sort(p[6], p[7]);   pix_sort(p[5], p[7]);
    pix_sort(p[5], p[6]);   pix_sort(p[9], p[10]);  pix_sort(p[8], p[10]);
    pix_sort(p[8], p[9]);   pix_sort(p[12], p[13]); pix_sort(p[11], p[13]);
    pix_sort(p[11], p[12]); pix_sort(p[15], p[16]); pix_sort(p[14], p[16]);
    pix_sort(p[14], p[15]); pix_sort(p[18], p[19]); pix_sort(p[17], p[19]);
    pix_sort(p[17], p[18]); pix_sort(p[21], p[22]); pix_sort(p[20], p[22]);
    pix_sort(p[20], p[21]); pix_sort(p[23], p[24]); pix_sort(p[2], p[5]);
    pix_sort(p[3], p[6]);   pix_sort(p[0], p[6]);   pix_sort(p[0], p[3]);
    pix_sort(p[4], p[7]);   pix_sort(p[1], p[7]);   pix_sort(p[1], p[4]);
    pix_sort(p[11], p[14]); pix_sort(p[8], p[14]);  pix_sort(p[8], p[11]);
    pix_sort(p[12], p[15]); pix_sort(p[9], p[15]);  pix_sort(p[9], p[12]);
    pix_sort(p[13], p[16]); pix_sort(p[10], p[16]); pix_sort(p[10], p[13]);
    pix_sort(p[20], p[23]); pix_sort(p[17], p[23]); pix_sort(p[17], p[20]);
    pix_sort(p[21], p[24]); pix_sort(p[18], p[24]); pix_sort(p[18], p[21]);
    pix_sort(p[19], p[22]); pix_sort(p[8], p[17]);  pix_sort(p[9], p[18]);
    pix_sort(p[0], p[18]);  pix_sort(p[0], p[9]);   pix_sort(p[10], p[19]);
    pix_sort(p[1], p[19]);  pix_sort(p[1], p[10]);  pix_sort(p[11], p[20]);
    pix_sort(p[2], p[20]);  pix_sort(p[2], p[11]);  pix_sort(p[12], p[21]);
    pix_sort(p[3], p[21]);  pix_sort(p[3], p[12]);  pix_sort(p[13], p[22]);
    pix_sort(p[4], p[22]);  pix_sort(p[4], p[13]);  pix_sort(p[14], p[23]);
    pix_sort(p[5], p[23]);  pix_sort(p[5], p[14]);  pix_sort(p[15], p[24]);
    pix_sort(p[6], p[24]);  pix_sort(p[6], p[15]);  pix_sort(p[7], p[16]);
    pix_sort(p[7], p[19]);  pix_sort(p[13], p[21]); pix_sort(p[15], p[23]);
    pix_sort(p[7], p[13]);  pix_sort(p[7], p[15]);  pix_sort(p[1], p[9]);
    pix_sort(p[3], p[11]);  pix_sort(p[5], p[17]);  pix_sort(p[11], p[17]);
    pix_sort(p[9], p[17]);  pix_sort(p[4], p[10]);  pix_sort(p[6], p[12]);
    pix_sort(p[7], p[14]);  pix_sort(p[4], p[6]);   pix_sort(p[4], p[7]);
    pix_sort(p[12], p[14]); pix_sort(p[10], p[14]); pix_sort(p[6], p[7]);
    pix_sort(p[10], p[12]); pix_sort(p[6], p[10]);  pix_sort(p[6], p[17]);
    pix_sort(p[12], p[17]); pix_sort(p[7], p[17]);  pix_sort(p[7], p[10]);
    pix_sort(p[12], p[18]); pix_sort(p[7], p[12]);  pix_sort(p[10], p[18]);
    pix_sort(p[12], p[20]); pix_sort(p[10], p[20]); pix_sort(p[10], p[12]);
    return p[12];
}

} // namespace dualiso::lib
