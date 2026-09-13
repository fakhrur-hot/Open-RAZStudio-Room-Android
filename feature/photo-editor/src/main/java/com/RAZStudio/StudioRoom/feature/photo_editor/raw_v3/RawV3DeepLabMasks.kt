/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * Per-class binary masks produced by [RawV3DeepLabProcessor] (DeepLabV3+
 * ResNet50 trained on LIP). Each FloatArray is 320×320 row-major with
 * values 0.0 (pixel does not belong to this class group) or 1.0 (belongs).
 *
 * LIP 20-class mapping → semantic groups used by the Mask tab:
 *
 *   face        → class 13
 *   hair        → class 2
 *   upperBody   → classes 5 (upper-clothes), 6 (dress), 7 (coat), 10 (jumpsuits)
 *   lowerBody   → classes 9 (pants), 12 (skirt)
 *   arms        → classes 14 (left-arm), 15 (right-arm)
 *   legs        → classes 16 (left-leg), 17 (right-leg)
 *   shoes       → classes 18 (left-shoe), 19 (right-shoe)
 *   accessories → classes 1 (hat), 3 (gloves), 4 (sunglasses), 8 (socks), 11 (scarf)
 *
 * The grouping is intentionally semantic rather than anatomic so that the
 * existing Mask-tab class buttons (Hair, FaceSkin, BodySkin, Clothes) can
 * use these masks as a higher-quality alternative to selfie_multiclass — and
 * without adding new UI buttons that would clutter the tab.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

data class RawV3DeepLabMasks(
    val face:        FloatArray,   // LIP class 13
    val hair:        FloatArray,   // LIP class 2
    val upperBody:   FloatArray,   // LIP classes 5, 6, 7, 10
    val lowerBody:   FloatArray,   // LIP classes 9, 12
    val arms:        FloatArray,   // LIP classes 14, 15
    val legs:        FloatArray,   // LIP classes 16, 17
    val shoes:       FloatArray,   // LIP classes 18, 19
    val accessories: FloatArray,   // LIP classes 1, 3, 4, 8, 11
) {
    /** All clothing — upper + lower combined. Useful for "Select Clothes". */
    val allClothes: FloatArray
        get() = FloatArray(SIZE * SIZE) { i ->
            maxOf(upperBody[i], lowerBody[i]).coerceIn(0f, 1f)
        }

    /** All skin-exposed regions — arms + legs (body skin the camera sees). */
    val bodySkin: FloatArray
        get() = FloatArray(SIZE * SIZE) { i ->
            maxOf(arms[i], legs[i]).coerceIn(0f, 1f)
        }

    companion object {
        const val SIZE = 320   // matches RawV3SegmentationMasks.MASK_SIZE

        // LIP class groups
        private val FACE_CLASSES        = intArrayOf(13)
        private val HAIR_CLASSES        = intArrayOf(2)
        private val UPPER_BODY_CLASSES  = intArrayOf(5, 6, 7, 10)
        private val LOWER_BODY_CLASSES  = intArrayOf(9, 12)
        private val ARM_CLASSES         = intArrayOf(14, 15)
        private val LEG_CLASSES         = intArrayOf(16, 17)
        private val SHOE_CLASSES        = intArrayOf(18, 19)
        private val ACCESSORY_CLASSES   = intArrayOf(1, 3, 4, 8, 11)

        /**
         * Convert a dense argmax class map at [srcSize]×[srcSize] (512) into
         * per-group binary FloatArrays at [SIZE]×[SIZE] (320).
         * Nearest-neighbour resample keeps class boundaries crisp.
         */
        fun fromClassMap(classMap: IntArray, srcSize: Int): RawV3DeepLabMasks {
            val n = SIZE * SIZE
            val face        = FloatArray(n)
            val hair        = FloatArray(n)
            val upperBody   = FloatArray(n)
            val lowerBody   = FloatArray(n)
            val arms        = FloatArray(n)
            val legs        = FloatArray(n)
            val shoes       = FloatArray(n)
            val accessories = FloatArray(n)

            for (ty in 0 until SIZE) {
                val sy   = (ty * srcSize / SIZE).coerceIn(0, srcSize - 1)
                val sRow = sy * srcSize
                val tRow = ty * SIZE
                for (tx in 0 until SIZE) {
                    val sx  = (tx * srcSize / SIZE).coerceIn(0, srcSize - 1)
                    val cls = classMap[sRow + sx]
                    val ti  = tRow + tx
                    when (cls) {
                        in FACE_CLASSES       -> face[ti]        = 1f
                        in HAIR_CLASSES       -> hair[ti]        = 1f
                        in UPPER_BODY_CLASSES -> upperBody[ti]   = 1f
                        in LOWER_BODY_CLASSES -> lowerBody[ti]   = 1f
                        in ARM_CLASSES        -> arms[ti]        = 1f
                        in LEG_CLASSES        -> legs[ti]        = 1f
                        in SHOE_CLASSES       -> shoes[ti]       = 1f
                        in ACCESSORY_CLASSES  -> accessories[ti] = 1f
                    }
                }
            }
            return RawV3DeepLabMasks(face, hair, upperBody, lowerBody, arms, legs, shoes, accessories)
        }
    }
}
