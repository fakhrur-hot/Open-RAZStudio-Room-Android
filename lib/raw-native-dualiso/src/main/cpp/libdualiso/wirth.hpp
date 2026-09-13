/*
 * libdualiso — Wirth's k-th smallest algorithm.
 * Licensed under the GNU General Public License v2 or later.
 *
 * Original:
 *   Algorithm from N. Wirth (1976), implementation by N. Devillard.
 *   Public domain. Source: http://ndevilla.free.fr/median/median/
 *
 * Used by cr2hdr's white_detect and match_exposures. Faithful port of
 * cr2hdr's `wirth.h`: integer-only specialization, no allocator, no
 * exceptions — same in-place partition + recursion pattern.
 *
 * SIDE-EFFECT WARNING: This function REORDERS the input array in
 * place (partial quickselect). Callers that need the original order
 * must copy first. cr2hdr's call sites all build a throwaway buffer
 * specifically for this, so the in-place mutation is fine.
 */

#pragma once

namespace dualiso::lib {

/**
 * Find the k-th smallest element of a[0..n-1] in O(n) average time.
 * Mutates `a` (partial quickselect).
 *
 *   @param a  array to partition (modified in place)
 *   @param n  array length, must be > 0
 *   @param k  rank to extract, must be 0 <= k < n
 *   @return   the k-th smallest value
 *
 * Caller MUST validate n > 0 and 0 <= k < n. cr2hdr's version printed
 * an error + exit(1); we let UB happen for invalid input because (a)
 * we don't want exceptions in detection-pass code, (b) all our call
 * sites can statically prove the bounds.
 */
inline int kth_smallest_int(int* a, int n, int k) noexcept {
    int l = 0;
    int m = n - 1;
    while (l < m) {
        const int x = a[k];
        int i = l;
        int j = m;
        do {
            while (a[i] < x) ++i;
            while (x < a[j]) --j;
            if (i <= j) {
                const int t = a[i];
                a[i] = a[j];
                a[j] = t;
                ++i;
                --j;
            }
        } while (i <= j);
        if (j < k) l = i;
        if (k < i) m = j;
    }
    return a[k];
}

/**
 * Median of n integers via Wirth. Same in-place mutation contract as
 * [kth_smallest_int]. cr2hdr's macro picked k = (n/2) for odd n,
 * (n/2 - 1) for even n — i.e. the lower of the two middle elements
 * for even n, not the average. We preserve that exact behaviour so
 * the downstream maths matches.
 */
inline int median_int_wirth(int* a, int n) noexcept {
    const int k = (n & 1) ? (n / 2) : (n / 2 - 1);
    return kth_smallest_int(a, n, k);
}

} // namespace dualiso::lib
