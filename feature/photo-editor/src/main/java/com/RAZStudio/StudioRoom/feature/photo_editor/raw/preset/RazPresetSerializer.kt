/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw.preset

import android.util.Xml
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.RawActionSerializer
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.MaskClass
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawAction
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.io.StringWriter

/**
 * XML format for a portable [RazPreset]. Reuses [RawActionSerializer] for
 * each card's macro values — the preset file is essentially:
 *
 *   <razPreset version="1" name="..." appVersion="..." createdEpochMs="...">
 *     <rawActions version="1">
 *       <action ... maskClass="Sky" ... />
 *       ...
 *     </rawActions>
 *   </razPreset>
 *
 * Reusing the macro serializer means every new UserMacro field is
 * automatically captured by both sidecars AND presets without parallel
 * code paths.
 */
internal object RazPresetSerializer {
    private const val VERSION = "1"
    private val NS: String? = null

    fun serialize(preset: RazPreset): String {
        val sw = StringWriter()
        val s = Xml.newSerializer()
        s.setOutput(sw)
        s.startDocument("UTF-8", true)
        s.startTag(NS, "razPreset")
        s.attribute(NS, "version",        VERSION)
        s.attribute(NS, "schemaVersion",  preset.schemaVersion.toString())
        s.attribute(NS, "name",           preset.name)
        s.attribute(NS, "appVersion",     preset.appVersion)
        s.attribute(NS, "createdEpochMs", preset.createdEpochMs.toString())

        // Project PresetCard list back into RawActions so we can reuse the
        // existing macro serializer verbatim. The generated UUIDs and
        // isLocked/isVisible attributes are fine — replay on a target
        // photo treats every preset card as a fresh, unlocked, visible card.
        val actions = preset.cards.map { c ->
            RawAction(
                label = c.label,
                tabIndex = c.tabIndex,
                macro = c.macro,
                maskClass = c.maskClass,
                maskClasses = c.maskClasses,
                isAutoExposure = c.isAutoExposure,
            )
        }
        val rawActionsXml = RawActionSerializer.serialize(actions, stripMaskFields = false)
        // Strip the inner XML declaration line so the outer document stays
        // well-formed when we concatenate.
        val inner = rawActionsXml.substringAfter("?>").trim()
        s.flush()
        sw.write(inner)
        s.endTag(NS, "razPreset")
        s.endDocument()
        return sw.toString()
    }

    fun deserialize(xml: String): RazPreset? {
        return runCatching {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(xml))
            var name = ""
            var schemaVersion = RazPreset.CURRENT_SCHEMA_VERSION
            var appVersion = ""
            var createdEpochMs = 0L
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "razPreset") {
                    name = parser.getAttributeValue(NS, "name") ?: ""
                    schemaVersion = parser.getAttributeValue(NS, "schemaVersion")
                        ?.toIntOrNull() ?: RazPreset.CURRENT_SCHEMA_VERSION
                    appVersion = parser.getAttributeValue(NS, "appVersion") ?: ""
                    createdEpochMs = parser.getAttributeValue(NS, "createdEpochMs")
                        ?.toLongOrNull() ?: 0L
                    break
                }
                event = parser.next()
            }
            // Hand the rest of the document to the action deserializer.
            // We pass the original full XML; the action parser walks past
            // anything that isn't <rawActions> on its own.
            val actions = RawActionSerializer.deserialize(xml)
            RazPreset(
                name = name,
                schemaVersion = schemaVersion,
                appVersion = appVersion,
                createdEpochMs = createdEpochMs,
                cards = actions.map { a ->
                    PresetCard(
                        label = a.label,
                        tabIndex = a.tabIndex,
                        macro = a.macro,
                        maskClass = a.maskClass,
                        maskClasses = a.maskClasses,
                        isAutoExposure = a.isAutoExposure,
                    )
                },
            )
        }.getOrNull()
    }
}
