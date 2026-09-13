/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * You should have received a copy of the Apache License
 * along with this program.  If not, see <http://www.apache.org/licenses/LICENSE-2.0>.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw.model

/**
 * Determines which pixels a spatial effect (vignette, gradient) is applied to
 * when segmentation masks are available.
 *
 * [All] — no masking, effect is applied uniformly (default).
 * [Subject] — effect weighted by the subject/foreground saliency mask.
 * [Background] — effect weighted by the inverse subject mask.
 */
enum class SegmentTarget { All, Subject, Background }
