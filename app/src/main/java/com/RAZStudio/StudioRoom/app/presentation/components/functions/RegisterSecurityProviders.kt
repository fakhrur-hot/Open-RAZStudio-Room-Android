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

package com.RAZStudio.StudioRoom.app.presentation.components.functions

import com.RAZStudio.StudioRoom.core.domain.model.CipherType
import com.RAZStudio.StudioRoom.core.domain.model.HashingType
import java.security.Security

internal fun registerSecurityProviders() {
    HashingType.registerSecurityMessageDigests(
        Security.getAlgorithms("MessageDigest").filterNotNull()
    )

    CipherType.registerSecurityCiphers(
        Security.getAlgorithms("Cipher").filterNotNull().mapNotNull { cipher ->
            if (CipherType.BROKEN.any { cipher.contains(it, true) }) return@mapNotNull null
            val oid = cipher.removePrefix("OID.")
            if (oid.all { it.isDigit() || it.isWhitespace() || it == '.' }) {
                return@mapNotNull null
            }
            CipherType.getInstance(cipher = cipher).also {
                if (it.cipher == "DES" || it.name == "DES/CBC"
                    || it.name == "THREEFISH-512" || it.name == "THREEFISH-1024"
                    || it.name == "CCM"
                ) return@mapNotNull null
            }
        }
    )
}
